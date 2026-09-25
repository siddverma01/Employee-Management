-- Add worked_for_employee_id to swap_off_requests for proper employee-to-employee swap tracking
ALTER TABLE swap_off_requests
    ADD COLUMN worked_for_employee_id BIGINT REFERENCES employees(id);

-- Create index for efficient lookups
CREATE INDEX idx_swap_off_requests_worked_for_employee ON swap_off_requests(worked_for_employee_id);