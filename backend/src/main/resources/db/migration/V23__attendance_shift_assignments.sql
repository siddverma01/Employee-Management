-- The Attendance Roster showed one fixed shift per engineer for every month,
-- because the shift lived only on the employee master (import_employees.
-- default_shift) and that column was written once, on the first month ever
-- imported for that person. Monthly workbooks rotate engineers between shifts,
-- so 60% of employee-months rendered the wrong shift.
--
-- attendance_records.shift already held the correct per-day value (the importer
-- has always written it), but no roster query ever read it. This migration
-- promotes that existing data into a first-class period-scoped table rather than
-- re-importing anything.
--
-- Nothing is deleted or rewritten in attendance_records: the new table is purely
-- additive, and the backfill below only reads it.

CREATE TABLE attendance_shift_assignments (
    id           BIGSERIAL PRIMARY KEY,
    employee_id  VARCHAR(40) NOT NULL REFERENCES import_employees (employee_id) ON DELETE CASCADE,
    period_start DATE NOT NULL,
    shift_value  VARCHAR(60),
    shift_key    VARCHAR(60),
    import_id    BIGINT,
    source_sheet VARCHAR(200),
    source_row   INTEGER,
    source_file  VARCHAR(500),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_shift_assignment_employee_period UNIQUE (employee_id, period_start)
);

CREATE INDEX idx_shift_assignments_period   ON attendance_shift_assignments (period_start);
CREATE INDEX idx_shift_assignments_employee ON attendance_shift_assignments (employee_id);

-- Backfill one assignment per employee per month from the shift already stored
-- on their attendance rows for that month.
--
-- Two normalisations happen here:
--   * shift_key  - the workbooks spell one shift several ways ("21:00 - 06:00"
--                  vs "21:00-06:00"), which would otherwise split one shift into
--                  several in the roster's filter and banding. The key collapses
--                  them to "HH:mm-HH:mm". shift_value keeps the source spelling.
--   * one row per month - where a month genuinely mixes spellings, the most
--                  frequent spelling wins so the pick is deterministic.
WITH src AS (
    SELECT r.employee_id,
           date_trunc('month', r.attendance_date)::date AS period_start,
           r.shift,
           r.source_file,
           r.source_sheet,
           r.source_row,
           r.id
    FROM attendance_records r
    WHERE r.shift IS NOT NULL
      AND btrim(replace(r.shift, chr(160), ' ')) <> ''
),
parsed AS (
    SELECT s.employee_id,
           s.period_start,
           s.shift,
           s.source_file,
           s.source_sheet,
           s.source_row,
           s.id,
           CASE
               WHEN m.parts IS NOT NULL
                   THEN lpad(m.parts[1], 2, '0') || ':' || m.parts[2] || '-'
                        || lpad(m.parts[3], 2, '0') || ':' || m.parts[4]
               ELSE regexp_replace(lower(s.shift), '[^a-z0-9]', '', 'g')
           END AS shift_key
    FROM src s
    LEFT JOIN LATERAL (
        SELECT regexp_match(
            s.shift, '^\s*(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})\s*$') AS parts
    ) m ON TRUE
),
freq AS (
    SELECT employee_id, period_start, shift, count(*) AS cnt
    FROM parsed
    GROUP BY employee_id, period_start, shift
),
top AS (
    SELECT DISTINCT ON (p.employee_id, p.period_start)
           p.employee_id, p.period_start, p.shift, p.shift_key,
           p.source_file, p.source_sheet, p.source_row
    FROM parsed p
    JOIN freq f ON f.employee_id = p.employee_id
               AND f.period_start = p.period_start
               AND f.shift = p.shift
    ORDER BY p.employee_id, p.period_start, f.cnt DESC, p.id
)
INSERT INTO attendance_shift_assignments
    (employee_id, period_start, shift_value, shift_key,
     source_file, source_sheet, source_row, created_at, updated_at)
SELECT employee_id, period_start, shift, shift_key,
       source_file, source_sheet, source_row, now(), now()
FROM top
ON CONFLICT (employee_id, period_start) DO NOTHING;

-- import_employees.default_shift still latched the first month ever seen. Bring
-- it up to date with each employee's most recent rostered period so the column
-- means "current shift" rather than "shift from an arbitrary old import", which
-- is what it is used for as the legacy fallback for employees with no period
-- assignment at all.
UPDATE import_employees e
SET default_shift = latest.shift_value
FROM (
    SELECT DISTINCT ON (a.employee_id) a.employee_id, a.shift_value
    FROM attendance_shift_assignments a
    ORDER BY a.employee_id, a.period_start DESC
) latest
WHERE latest.employee_id = e.employee_id
  AND (e.default_shift IS NULL OR e.default_shift IS DISTINCT FROM latest.shift_value);