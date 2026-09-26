package com.emplmgt.entity;

public enum HPEEntitlementStatus {
    /** Earned and not yet claimed by anything. */
    AVAILABLE,
    /**
     * Held by a leave request that is still PENDING. The entitlement is earmarked so it
     * cannot be claimed twice, but it is <b>not</b> consumed - it becomes USED only when
     * the request is approved, and returns to AVAILABLE if the request is rejected.
     */
    RESERVED,
    /** Consumed by an approved request. */
    USED,
    EXPIRED
}