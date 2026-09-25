-- Attendance History (admin search/analysis) supporting indexes.
-- Composite date+status accelerates the history table + summary counts;
-- the remaining indexes speed up employee-name / location / shift filters.
CREATE INDEX idx_attendance_records_date_status ON attendance_records (attendance_date, status_code);
CREATE INDEX idx_import_employees_name ON import_employees (employee_name);
CREATE INDEX idx_import_employees_location ON import_employees (location);
CREATE INDEX idx_import_employees_shift ON import_employees (default_shift);