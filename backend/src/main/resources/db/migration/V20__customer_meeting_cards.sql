-- V20: Customer meeting cards — organization, end time, reassignment
--
-- Purpose
--   The "Customer Meetings" section renders large featured cards that need three
--   things the events table did not have:
--     1. organization  -> the client name shown in the card header
--     2. end_time      -> so the card can render a real range, e.g. 02:00-03:30 PM
--     3. (no new column) reassignment only writes the existing `assigned_to` FK,
--        which is why the assign endpoint is a service-layer change, not a schema one.
--
-- Notes
--   * Both columns are nullable. Every existing event keeps rendering with a
--     sensible fallback instead of a blank.
--   * end_time is timezone-naive for the same reason as start_time: these are
--     wall-clock meeting times, not instants.

ALTER TABLE events ADD COLUMN IF NOT EXISTS organization VARCHAR(120);
ALTER TABLE events ADD COLUMN IF NOT EXISTS end_time TIMESTAMP;
