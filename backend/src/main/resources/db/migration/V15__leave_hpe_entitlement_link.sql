-- V15: Link Compensatory Off leave requests to the earned HPE Holiday entitlement they consume
--
-- Lifecycle: a leave request only *reserves* an entitlement while it is PENDING.
-- The entitlement becomes USED when the request is approved and returns to AVAILABLE
-- when the request is rejected or cancelled. used_request_id therefore stays NULL
-- until approval, and reserved_request_id tracks the in-flight request.

-- Which earned HPE Holiday entitlement a Compensatory Off request is consuming.
ALTER TABLE leave_requests ADD COLUMN IF NOT EXISTS hpe_entitlement_id BIGINT REFERENCES hpe_entitlements(id);

CREATE INDEX IF NOT EXISTS idx_leave_requests_hpe_entitlement_id ON leave_requests(hpe_entitlement_id);

-- Backstop for "the same entitlement cannot be attached to two live requests":
-- only PENDING/APPROVED rows claim an entitlement exclusively, so a rejected or
-- cancelled request releases it for reuse.
CREATE UNIQUE INDEX IF NOT EXISTS uq_leave_requests_active_hpe_entitlement
    ON leave_requests(hpe_entitlement_id)
    WHERE hpe_entitlement_id IS NOT NULL AND status IN ('PENDING', 'APPROVED');

-- The request currently holding a RESERVED (not yet consumed) entitlement.
ALTER TABLE hpe_entitlements ADD COLUMN IF NOT EXISTS reserved_request_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_hpe_entitlements_reserved_request_id ON hpe_entitlements(reserved_request_id);
