package com.emplmgt.entity;

public enum EventType {
    COMPANY_EVENT,
    CONFERENCE,
    TEAM_MEETING,
    CUSTOM,
    /** Physical/virtual office meeting scheduled by a user or an admin. */
    OFFICE_MEETING,
    /** Booked slot on a calendar, e.g. a 1:1 or a review. */
    SCHEDULED_MEETING,
    /** Customer virtual/remote session; typically carries a meeting link. */
    CUSTOMER_REMOTE_SESSION
}