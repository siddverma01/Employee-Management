-- ---------------------------------------------------------------------------
-- Original comment timestamp: modern threaded Excel comments carry a `dT`
-- attribute (their wall-clock creation time, no timezone). Preserve it next to
-- the original author so the cell popup can show when the remark was actually
-- written, not when the workbook happened to be imported.
--
-- Legacy comments have no such metadata, so the column stays null for them.
-- This is a plain TIMESTAMP (no zone) because the source value has no zone and
-- inventing one would be wrong.
-- ---------------------------------------------------------------------------

ALTER TABLE attendance_records
    ADD COLUMN description_source_at TIMESTAMP;

ALTER TABLE attendance_import_rows
    ADD COLUMN description_at TIMESTAMP;
