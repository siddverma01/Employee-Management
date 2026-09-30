-- V18: Upcoming Events
--
-- Purpose
--   The employee-facing "Upcoming Events" page aggregates three different sources:
--     1. holidays              -> rows in `holidays`   (already location-scoped)
--     2. birthdays             -> derived from employees.date_of_birth (no rows)
--     3. meetings / sessions   -> rows in `events`
--   Only source 3 needs new columns. HOLIDAY and BIRTHDAY are *categories* derived at
--   query time, so they are deliberately NOT stored event_type values.
--
-- New columns on `events`
--   meeting_link   : URL for virtual/remote sessions (CUSTOMER_REMOTE_SESSION)
--   start_time     : wall-clock start; event_date stays the authoritative calendar date
--   set_reminder   : per-event opt-in flag, mirrored onto notifications in a later step
--   todo_items     : JSON array of strings, stored as TEXT like other JSON-in-TEXT columns
--   created_by     : FK to users.id, so user-created events can be attributed and listed
--
-- event_type CHECK
--   Widened to add the three meeting kinds requested for the new page. Existing values
--   are retained so admin-managed events (COMPANY_EVENT, CONFERENCE, TEAM_MEETING,
--   CUSTOM) keep working unchanged.

ALTER TABLE events DROP CONSTRAINT IF EXISTS events_event_type_check;
ALTER TABLE events ADD CONSTRAINT events_event_type_check
    CHECK (event_type IN (
        'COMPANY_EVENT',
        'CONFERENCE',
        'TEAM_MEETING',
        'CUSTOM',
        'OFFICE_MEETING',
        'SCHEDULED_MEETING',
        'CUSTOMER_REMOTE_SESSION'
    ));

ALTER TABLE events ADD COLUMN IF NOT EXISTS meeting_link VARCHAR(500);
ALTER TABLE events ADD COLUMN IF NOT EXISTS start_time TIMESTAMP;
ALTER TABLE events ADD COLUMN IF NOT EXISTS set_reminder BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE events ADD COLUMN IF NOT EXISTS todo_items TEXT;
ALTER TABLE events ADD COLUMN IF NOT EXISTS created_by BIGINT;

-- start_time is timezone-naive on purpose: it is a wall-clock meeting time, not an
-- instant. Avoids the TIMESTAMPTZ semantics used by created_at/updated_at.
ALTER TABLE events DROP CONSTRAINT IF EXISTS fk_event_created_by;
ALTER TABLE events ADD CONSTRAINT fk_event_created_by
    FOREIGN KEY (created_by) REFERENCES users (id);

CREATE INDEX IF NOT EXISTS idx_events_created_by ON events (created_by);
CREATE INDEX IF NOT EXISTS idx_events_start_time ON events (start_time);
