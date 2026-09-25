-- V14: HPE Holiday / HPEH entitlement foundation
--  * master HPE holiday definitions for 2026 (one row per holiday, never per employee)
--  * active flag on the master holiday definition
--  * employee specific rows live only in hpe_entitlements (created when earned)

-- ---------------------------------------------------------------------------
-- Master holiday definition: active flag
-- ---------------------------------------------------------------------------
ALTER TABLE holidays ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX IF NOT EXISTS idx_holidays_holiday_type ON holidays (holiday_type);

-- ---------------------------------------------------------------------------
-- Master HPE holiday definitions for 2026 (Pune HPE Voice calendar)
-- applicable_locations: ALL -> applies to every location
--                       PUNE_MUMBAI -> applies to Pune and Mumbai employees
-- Idempotent: never overwrites an existing definition (admin edits are kept).
-- ---------------------------------------------------------------------------
INSERT INTO holidays (name, holiday_date, country, holiday_type, applicable_locations, description, scope, active)
VALUES
    ('New Year''s Day',          '2026-01-01', 'IN', 'HPE_HOLIDAY', 'ALL',        'HPE holiday - applicable to all locations',        'GLOBAL', TRUE),
    ('Republic Day',             '2026-01-26', 'IN', 'HPE_HOLIDAY', 'ALL',        'HPE holiday - applicable to all locations',        'GLOBAL', TRUE),
    ('Gudi Padwa / Ugadi',       '2026-03-19', 'IN', 'HPE_HOLIDAY', 'PUNE_MUMBAI','HPE holiday - applicable to Pune/Mumbai',          'GLOBAL', TRUE),
    ('Good Friday',              '2026-04-03', 'IN', 'HPE_HOLIDAY', 'ALL',        'HPE holiday - applicable to all locations',        'GLOBAL', TRUE),
    ('May Day/Maharashtra Day',  '2026-05-01', 'IN', 'HPE_HOLIDAY', 'PUNE_MUMBAI','HPE holiday - applicable to Pune/Mumbai',          'GLOBAL', TRUE),
    ('Id-Ul-zuha',               '2026-05-28', 'IN', 'HPE_HOLIDAY', 'ALL',        'HPE holiday - applicable to all locations',        'GLOBAL', TRUE),
    ('Ganesh Chaturthi',         '2026-09-14', 'IN', 'HPE_HOLIDAY', 'PUNE_MUMBAI','HPE holiday - applicable to Pune/Mumbai',          'GLOBAL', TRUE),
    ('Gandhi Jayanti',           '2026-10-02', 'IN', 'HPE_HOLIDAY', 'ALL',        'HPE holiday - applicable to all locations',        'GLOBAL', TRUE),
    ('Dussehra (Ayudha Pooja)',  '2026-10-20', 'IN', 'HPE_HOLIDAY', 'ALL',        'HPE holiday - applicable to all locations',        'GLOBAL', TRUE),
    ('Diwali (Deepavali)',       '2026-11-09', 'IN', 'HPE_HOLIDAY', 'ALL',        'HPE holiday - applicable to all locations',        'GLOBAL', TRUE),
    ('Bhai Duj',                 '2026-11-11', 'IN', 'HPE_HOLIDAY', 'PUNE_MUMBAI','HPE holiday - applicable to Pune/Mumbai',          'GLOBAL', TRUE),
    ('Christmas',                '2026-12-25', 'IN', 'HPE_HOLIDAY', 'ALL',        'HPE holiday - applicable to all locations',        'GLOBAL', TRUE)
ON CONFLICT (holiday_date, country, name) DO NOTHING;
