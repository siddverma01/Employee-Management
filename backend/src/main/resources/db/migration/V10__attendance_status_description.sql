-- ---------------------------------------------------------------------------
-- Attendance status description / reason: a free-text note attached to a
-- specific employee + date attendance record, with audit trail of who created
-- and last updated it.
--
-- Stored denormalised on attendance_records (unique key employee_id + date),
-- which is the row the monthly roster reads/writes, so a description always
-- travels with the status cell it was written for. The actor names are copied
-- in so the audit trail stays readable even if a user is later removed.
-- ---------------------------------------------------------------------------

ALTER TABLE attendance_records
    ADD COLUMN description                  TEXT,
    ADD COLUMN description_created_by       BIGINT REFERENCES users (id),
    ADD COLUMN description_created_name     VARCHAR(255),
    ADD COLUMN description_created_at       TIMESTAMPTZ,
    ADD COLUMN description_updated_by       BIGINT REFERENCES users (id),
    ADD COLUMN description_updated_name     VARCHAR(255),
    ADD COLUMN description_updated_at       TIMESTAMPTZ;