-- Structured per-cell import errors (sheet/row/column/raw value) for the Excel attendance import preview.
ALTER TABLE excel_import_rows ADD COLUMN error_details TEXT;