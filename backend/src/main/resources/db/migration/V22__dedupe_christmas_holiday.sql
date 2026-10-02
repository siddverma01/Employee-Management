-- V14 seeded the HPE Christmas as "Christmas" (IN) and V17 seeded the US federal
-- one as "Christmas Day" (US). Both fall on 2026-12-25, so the admin Holidays &
-- events table showed two rows for one holiday. The console collapses rows that
-- share a date and name, and "Christmas Day" never matched "Christmas".
--
-- Rename rather than delete: the US row is the source of the "US Holiday" badge
-- and carries the US country, so dropping it would lose that half of the merged
-- row. Renaming folds it into the group, leaving one row showing both badges.
--
-- Safe against uq_holiday_country_date (holiday_date, country, name): the two
-- rows have different countries, so the new ('2026-12-25', 'US', 'Christmas') key
-- cannot collide with the existing ('2026-12-25', 'IN', 'Christmas').

UPDATE holidays
SET name = 'Christmas'
WHERE holiday_date = DATE '2026-12-25'
  AND country = 'US'
  AND name = 'Christmas Day';