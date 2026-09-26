-- V17: US Federal Holidays for 2026
--
-- Purpose
--   US holidays are INFORMATIONAL ONLY in this application:
--     * they never create HPE entitlements
--     * they are not region-scoped (applicable_locations = ALL) so every employee
--       can see the US calendar for reference, regardless of work location
--   Only HPE_HOLIDAY rows are region-scoped and entitlement-bearing.
--
-- Idempotency
--   The demo seeder (DataSeeder.seedHolidays) may already have inserted several of
--   these dates with slightly different names/casing (for example
--   "Independence Day (observed)" vs "Independence Day (Observed)").
--   The unique key is (holiday_date, country, name) and it is case-sensitive, so a
--   plain INSERT ... ON CONFLICT DO NOTHING would create duplicate rows on the same
--   date. This migration therefore reconciles on (holiday_date, country):
--   1. canonicalise any existing US row that sits on a federal holiday date
--   2. insert only the federal dates that have no US row at all
--
-- Re-runnable: running it twice leaves the table unchanged.

CREATE TEMPORARY TABLE tmp_us_federal_2026 (
    holiday_date   DATE PRIMARY KEY,
    name           VARCHAR(150) NOT NULL,
    description    VARCHAR(500)
);

INSERT INTO tmp_us_federal_2026 (holiday_date, name, description) VALUES
    ('2026-01-01', 'New Year''s Day',                              'US Federal Holiday'),
    ('2026-01-19', 'Birthday of Martin Luther King, Jr.',         'US Federal Holiday'),
    ('2026-02-16', 'Washington''s Birthday (Presidents'' Day)',  'US Federal Holiday'),
    ('2026-05-25', 'Memorial Day',                                'US Federal Holiday'),
    ('2026-06-19', 'Juneteenth National Independence Day',        'US Federal Holiday'),
    ('2026-07-03', 'Independence Day (Observed)',                 'US Federal Holiday (observed; July 4 falls on a Saturday)'),
    ('2026-09-07', 'Labor Day',                                   'US Federal Holiday'),
    ('2026-10-12', 'Columbus Day',                                'US Federal Holiday'),
    ('2026-11-11', 'Veterans Day',                                'US Federal Holiday'),
    ('2026-11-26', 'Thanksgiving Day',                            'US Federal Holiday'),
    ('2026-12-25', 'Christmas Day',                               'US Federal Holiday');

-- 1) Canonicalise existing rows first, so the INSERT below cannot collide on
--    (holiday_date, country, name) and so duplicate-ish seeded names are merged.
UPDATE holidays h
SET name                = t.name,
    description         = t.description,
    applicable_locations = 'ALL',
    active              = TRUE
FROM tmp_us_federal_2026 t
WHERE h.country = 'US'
  AND h.holiday_date = t.holiday_date;

-- 2) Insert only the federal dates that have no US row yet.
INSERT INTO holidays (name, holiday_date, country, holiday_type, applicable_locations, description, scope, active)
SELECT t.name,
       t.holiday_date,
       'US',
       'PUBLIC',
       'ALL',
       t.description,
       'GLOBAL',
       TRUE
FROM tmp_us_federal_2026 t
WHERE NOT EXISTS (
    SELECT 1
    FROM holidays h
    WHERE h.country = 'US'
      AND h.holiday_date = t.holiday_date
);

DROP TABLE tmp_us_federal_2026;
