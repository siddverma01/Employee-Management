-- Historical roster grid: allow team scoping of imported employees so the
-- month-wise Attendance Roster can be filtered per department.
ALTER TABLE import_employees ADD COLUMN team_id BIGINT REFERENCES departments(id) ON DELETE SET NULL;

CREATE INDEX idx_import_employees_team_id ON import_employees(team_id);