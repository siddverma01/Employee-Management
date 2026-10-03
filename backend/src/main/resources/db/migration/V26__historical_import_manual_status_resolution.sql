-- ---------------------------------------------------------------------------
-- Manual status resolution for staged historical attendance rows.
--
-- The original workflow staged every unrecognised or blank cell as a normal
-- INSERT/UPDATE and let it through to attendance_records with is_unknown set,
-- which meant an unresolved entry such as "P" or an empty cell was imported as
-- a real status. The admin now has to resolve each such cell explicitly, either
-- by correcting it to a valid attendance_status code or by skipping it, and the
-- commit is blocked until nothing is left unresolved.
--
-- Nothing here modifies the uploaded workbook: source_column / original_status
-- only record where the value came from and what it said, so the correction is
-- traceable back to the Excel cell it replaces.
-- ---------------------------------------------------------------------------

ALTER TABLE attendance_import_rows
    ADD COLUMN source_column   INTEGER,
    ADD COLUMN original_status VARCHAR(60),
    ADD COLUMN issue           VARCHAR(500),
    ADD COLUMN corrected       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN skipped         BOOLEAN NOT NULL DEFAULT FALSE;

-- SKIPPED is an explicit admin decision ("leave this cell alone") and must be
-- distinguishable from INVALID, which is an automatic verdict.
ALTER TABLE attendance_import_rows
    DROP CONSTRAINT attendance_import_rows_action_check;

ALTER TABLE attendance_import_rows
    ADD CONSTRAINT attendance_import_rows_action_check
    CHECK (action IN ('INSERT','UPDATE','DUPLICATE','INVALID','SKIPPED'));

-- The resolution screen pages through unresolved cells only, so it needs a
-- leading index that is not usable once the columns above are filtered on.
CREATE INDEX idx_attendance_import_rows_unresolved
    ON attendance_import_rows (import_id, skipped)
    WHERE skipped = FALSE AND corrected = FALSE;