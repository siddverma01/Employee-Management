-- V13: Add HPE Holiday support
-- Add HPE_HOLIDAY to holiday_type allowed values
ALTER TABLE holidays DROP CONSTRAINT IF EXISTS holidays_holiday_type_check;
ALTER TABLE holidays ADD CONSTRAINT holidays_holiday_type_check
    CHECK (holiday_type IN ('PUBLIC', 'OPTIONAL', 'OBSERVED', 'HPE_HOLIDAY'));

-- Add applicable_locations column to holidays table
ALTER TABLE holidays ADD COLUMN IF NOT EXISTS applicable_locations VARCHAR(100) DEFAULT 'ALL';

-- Create hpe_entitlements table
CREATE TABLE IF NOT EXISTS hpe_entitlements (
    id BIGSERIAL PRIMARY KEY,
    employee_id BIGINT NOT NULL REFERENCES employees(id),
    holiday_id BIGINT NOT NULL REFERENCES holidays(id),
    earned_date DATE NOT NULL,
    expiry_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    used_date DATE,
    used_request_id BIGINT,
    notes VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (employee_id, holiday_id)
);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_hpe_entitlements_employee_id ON hpe_entitlements(employee_id);
CREATE INDEX IF NOT EXISTS idx_hpe_entitlements_holiday_id ON hpe_entitlements(holiday_id);
CREATE INDEX IF NOT EXISTS idx_hpe_entitlements_status ON hpe_entitlements(status);
CREATE INDEX IF NOT EXISTS idx_hpe_entitlements_expiry_date ON hpe_entitlements(expiry_date);
CREATE INDEX IF NOT EXISTS idx_hpe_entitlements_employee_holiday ON hpe_entitlements(employee_id, holiday_id);

-- Update existing holidays to have applicable_locations = 'ALL' if null
UPDATE holidays SET applicable_locations = 'ALL' WHERE applicable_locations IS NULL;