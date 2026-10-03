-- ---------------------------------------------------------------------------
-- Attendance description provenance: the roster description already lives on
-- attendance_records (V10). This adds the source metadata required to keep an
-- imported Excel remark traceable: where it came from (comment / cell text /
-- unparsed), the source sheet + cell + original value, the author of the Excel
-- comment, and an immutable copy of the imported text so later manual edits do
-- not destroy the imported original.
--
-- Staging rows (attendance_import_rows) carry the same description + source so
-- the preview can show Employee | Date | Status | Description before commit.
-- ---------------------------------------------------------------------------

ALTER TABLE attendance_records
    ADD COLUMN description_source        VARCHAR(30),
    ADD COLUMN description_source_sheet  VARCHAR(200),
    ADD COLUMN description_source_cell   VARCHAR(20),
    ADD COLUMN description_source_author VARCHAR(255),
    ADD COLUMN description_imported      TEXT,
    ADD COLUMN source_value              VARCHAR(500);

ALTER TABLE attendance_import_rows
    ADD COLUMN description        TEXT,
    ADD COLUMN description_source VARCHAR(30),
    ADD COLUMN description_author VARCHAR(255);
