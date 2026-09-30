-- V19: Upcoming Events — assign an engineer
--
-- Purpose
--   The "Add Event" modal lets the scheduler assign a meeting to one engineer.
--   `assigned_to` is a nullable FK on `events.employee_id`, so an unassigned
--   event keeps the previous behaviour exactly (NULL means nobody is assigned).
--
-- Notes
--   * Nullable on purpose: existing rows and admin-managed events stay unassigned.
--   * The engineer's name is *not* denormalised into a second column. The DTO
--     resolves it from the join at read time, so a later name correction in
--     `employees` propagates to every past event automatically.
--   * on delete set null: removing an employee must not delete their meetings.

ALTER TABLE events ADD COLUMN IF NOT EXISTS assigned_to BIGINT;

ALTER TABLE events DROP CONSTRAINT IF EXISTS fk_event_assigned_to;
ALTER TABLE events ADD CONSTRAINT fk_event_assigned_to
    FOREIGN KEY (assigned_to) REFERENCES employees (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_events_assigned_to ON events (assigned_to);
