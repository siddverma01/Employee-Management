-- Add exit tracking columns to employees table
ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS last_working_date DATE,
    ADD COLUMN IF NOT EXISTS exit_date DATE,
    ADD COLUMN IF NOT EXISTS exit_reason VARCHAR(500),
    ADD COLUMN IF NOT EXISTS exited_by BIGINT,
    ADD COLUMN IF NOT EXISTS exited_at TIMESTAMPTZ;

-- Update employment_status check constraint to include EXITED
ALTER TABLE employees
    DROP CONSTRAINT IF EXISTS employees_employment_status_check;

ALTER TABLE employees
    ADD CONSTRAINT employees_employment_status_check
    CHECK (employment_status IN ('ACTIVE', 'EXITED', 'INACTIVE'));

-- Add exit tracking columns to import_employees table
ALTER TABLE import_employees
    ADD COLUMN IF NOT EXISTS last_working_date DATE,
    ADD COLUMN IF NOT EXISTS exit_date DATE;

-- Add index for exit date queries
CREATE INDEX IF NOT EXISTS idx_employees_exit_date ON employees (exit_date) WHERE exit_date IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_import_employees_exit_date ON import_employees (exit_date) WHERE exit_date IS NOT NULL;