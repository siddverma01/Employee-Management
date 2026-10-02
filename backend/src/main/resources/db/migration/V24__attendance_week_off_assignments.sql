-- The Attendance Roster showed one fixed week off per engineer for every month,
-- because the schedule lived only on the employee master (import_employees.
-- week_off) and that column was written once, on the first month ever imported
-- for that person. The source workbooks rotate week off just as they rotate
-- shift, so an engineer rostered "Sat-Sun" in one month can be "Sun-Mon" the
-- next and the grid kept showing the original value for all of them.
--
-- This is the same defect the shift fix addressed, with a different key: week off
-- changes on its own schedule and is unrelated to shift, so it gets its own
-- period-scoped table rather than being folded into the shift one.
--
-- The importer has always carried the per-row value on attendance_import_rows
-- (employee_week_off), which survives the commit, so the historical months are
-- recovered from the staged source data rather than by re-importing anything.
--
-- Nothing is deleted or rewritten in attendance_import_rows or attendance_records:
-- the new table is purely additive and the backfill below only reads them.

CREATE TABLE attendance_week_off_assignments (
    id             BIGSERIAL PRIMARY KEY,
    employee_id    VARCHAR(40) NOT NULL REFERENCES import_employees (employee_id) ON DELETE CASCADE,
    period_start   DATE NOT NULL,
    week_off_value VARCHAR(60),
    week_off_key   VARCHAR(60),
    import_id      BIGINT,
    source_sheet   VARCHAR(200),
    source_row     INTEGER,
    source_file    VARCHAR(500),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_week_off_assignment_employee_period UNIQUE (employee_id, period_start)
);

CREATE INDEX idx_week_off_assignments_period   ON attendance_week_off_assignments (period_start);
CREATE INDEX idx_week_off_assignments_employee ON attendance_week_off_assignments (employee_id);

-- Backfill one assignment per employee per month from the week off already staged
-- against their source rows for that month.
--
-- Three decisions are made here:
--   * only recognised schedules are stored. Non-roster sheets leak into this
--     column with placeholder text ("WeekOff", "WOs", a manager's name), and
--     storing that would put a person's name in the roster's Week Off column.
--     Those employee-months are left unassigned here and are surfaced by the
--     importer's UNPARSED_WEEK_OFF warning instead, so an admin can fix the
--     workbook.
--   * week_off_key folds the cosmetic variants the workbooks use ("Sat-Sun" vs
--     "sat-sun" vs "Sun-Mon (9-6)") into one comparable value.
--     week_off_value keeps the exact source spelling for display.
--   * where a month genuinely mixes schedules - the sheets roster in five-week
--     blocks that straddle months, so a block change can land mid-month - the
--     most frequent spelling wins, keeping the pick deterministic.
WITH src AS (
    SELECT r.employee_id,
           date_trunc('month', r.attendance_date)::date AS period_start,
           btrim(r.employee_week_off)                  AS week_off,
           h.file_name                                 AS source_file,
           r.sheet_name                                AS source_sheet,
           r.source_row,
           r.id
    FROM attendance_import_rows r
    LEFT JOIN attendance_import_history h ON h.id = r.import_id
    WHERE r.action IN ('INSERT', 'UPDATE')
      AND r.attendance_date IS NOT NULL
      AND r.employee_week_off IS NOT NULL
      AND btrim(replace(r.employee_week_off, chr(160), ' ')) <> ''
),
-- Mirrors WeekOffUtil.comparisonKey(): drop any trailing note, fold separators and
-- spacing, then expand full/informal day names to the three-letter abbreviation.
-- Longer names are replaced before the shorter "TUES"/"THURS"/"WEDS" forms so
-- "TUESDAY" is not left half-replaced. Both sides must stay in step, because this
-- key is what decides whether two sheets are the same schedule.
base AS (
    SELECT s.*,
           upper(regexp_replace(
               regexp_replace(
                   regexp_replace(
                       regexp_replace(replace(s.week_off, chr(160), ' '),
                                      '\([^)]*\)', ' ', 'g'),
                       '[\s,/_&]+', '-', 'g'),
                   '-{2,}', '-', 'g'),
               '^-+|-+$', '', 'g')) AS keyed
    FROM src s
),
recognised AS (
    SELECT b.*,
           replace(replace(replace(replace(replace(replace(replace(
               replace(replace(replace(
                   b.keyed,
                   'MONDAY', 'MON'), 'TUESDAY', 'TUE'), 'WEDNESDAY', 'WED'),
               'THURSDAY', 'THU'), 'FRIDAY', 'FRI'), 'SATURDAY', 'SAT'),
               'SUNDAY', 'SUN'), 'TUES', 'TUE'), 'THURS', 'THU'), 'WEDS', 'WED') AS week_off_key
    FROM base b
),
valid AS (
    SELECT *
    FROM recognised
    -- Requires every token to name a real weekday, matching WeekOffUtil.isRecognised.
    -- Kept deliberately strict: a prefix-tolerant check would accept placeholders.
    WHERE week_off_key ~
          '^(MON|TUE|WED|THU|FRI|SAT|SUN)(-(MON|TUE|WED|THU|FRI|SAT|SUN))*$'
),
freq AS (
    SELECT employee_id, period_start, week_off, week_off_key, count(*) AS cnt
    FROM valid
    GROUP BY employee_id, period_start, week_off, week_off_key
),
top AS (
    SELECT DISTINCT ON (v.employee_id, v.period_start)
           v.employee_id, v.period_start, v.week_off, v.week_off_key,
           v.source_file, v.source_sheet, v.source_row
    FROM valid v
    JOIN freq f ON f.employee_id = v.employee_id
               AND f.period_start = v.period_start
               AND f.week_off = v.week_off
    ORDER BY v.employee_id, v.period_start, f.cnt DESC, v.id
)
INSERT INTO attendance_week_off_assignments
    (employee_id, period_start, week_off_value, week_off_key,
     source_file, source_sheet, source_row, created_at, updated_at)
SELECT employee_id, period_start, week_off, week_off_key,
       source_file, source_sheet, source_row, now(), now()
FROM top
ON CONFLICT (employee_id, period_start) DO NOTHING;

-- import_employees.week_off still latched the first month ever seen. Bring it up
-- to date with each employee's most recent rostered period so the column means
-- "current week off" rather than "week off from an arbitrary old import". The
-- roster no longer reads it for historical months - each month resolves its own
-- assignment - but the column is still shown on employee master screens.
UPDATE import_employees e
SET week_off = latest.week_off_value
FROM (
    SELECT DISTINCT ON (a.employee_id) a.employee_id, a.week_off_value
    FROM attendance_week_off_assignments a
    ORDER BY a.employee_id, a.period_start DESC
) latest
WHERE latest.employee_id = e.employee_id
  AND (e.week_off IS NULL OR e.week_off IS DISTINCT FROM latest.week_off_value);