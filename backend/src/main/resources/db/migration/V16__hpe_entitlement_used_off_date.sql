-- Records which compensatory-off day an earned HPE Holiday entitlement was actually
-- spent on, so a consumed entitlement can be inspected (and audited) without having to
-- join back through leave_requests.
--
-- used_date        = the date the entitlement was consumed (i.e. the approval date)
-- used_off_date    = the compensatory-off day the employee requested
--
-- For a single-day Compensatory Off request these are unrelated values on purpose:
-- an entitlement earned on 14 Sep 2026 and approved on 20 Nov 2026 for an off on
-- 05 Oct 2026 records used_date = 20 Nov 2026 and used_off_date = 05 Oct 2026.
--
-- NULL for entitlements that have not been consumed yet (AVAILABLE / RESERVED / EXPIRED).
ALTER TABLE hpe_entitlements ADD COLUMN IF NOT EXISTS used_off_date DATE;

COMMENT ON COLUMN hpe_entitlements.used_off_date IS
    'Compensatory-off day this entitlement was spent on (set on approval)';
