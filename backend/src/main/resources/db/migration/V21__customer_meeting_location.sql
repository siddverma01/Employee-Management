-- V21: Customer meeting card — location / platform
--
-- Purpose
--   The Customer Meetings card footer shows where the meeting happens, e.g.
--   "Virtual Teams Room" or "Virtual Conference Room A". Those are per-meeting
--   facts, so they cannot be derived from the organization or the meeting link
--   without inventing text.
--
-- Notes
--   * Nullable, so every existing event keeps rendering with a fallback label.
--   * Free text rather than a lookup table: the platform naming is owned by the
--     meeting organiser, and conference rooms are not a fixed company-wide set.

ALTER TABLE events ADD COLUMN IF NOT EXISTS location VARCHAR(120);
