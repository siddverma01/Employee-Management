package com.emplmgt.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Event extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", length = 30)
    @Builder.Default
    private EventType eventType = EventType.COMPANY_EVENT;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    @Builder.Default
    private ScopeType scope = ScopeType.GLOBAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Department team;

    /** Meeting URL for virtual/remote sessions. Optional for in-person events. */
    @Column(name = "meeting_link", length = 500)
    private String meetingLink;

    /**
     * Wall-clock start of the event. Deliberately timezone-naive (LocalDateTime /
     * {@code TIMESTAMP}) because it is a meeting time, not an instant;
     * {@code eventDate} remains the authoritative value for calendar placement.
     */
    @Column(name = "start_time")
    private LocalDateTime startTime;

    /**
     * Wall-clock end of the event, for meetings that span a range.
     * Optional; a point-in-time meeting leaves this null.
     */
    @Column(name = "end_time")
    private LocalDateTime endTime;

    /**
     * Client or counterparty name for a customer-facing meeting, shown in the
     * Customer Meetings card header. Optional for internal events.
     */
    @Column(name = "organization", length = 120)
    private String organization;

    /**
     * Where the meeting takes place, shown in the Customer Meetings card footer,
     * e.g. "Virtual Teams Room" or "Virtual Conference Room A".
     *
     * <p>Free text rather than a lookup: the room or platform is chosen by the
     * meeting organiser, so it is stored per event instead of being derived from
     * the organization or the meeting link.</p>
     */
    @Column(name = "location", length = 120)
    private String location;

    /** Per-event opt-in for reminders. Notifications fan-out is wired up in a later step. */
    @Column(name = "set_reminder", nullable = false)
    @Builder.Default
    private boolean setReminder = false;

    /** Checklist items as a JSON array of strings; see {@code JsonUtil#writeList/readList}. */
    @Column(name = "todo_items", columnDefinition = "TEXT")
    private String todoItems;

    /** Creator, set when an employee schedules their own event. Null for admin-managed events. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    /**
     * Engineer this meeting is assigned to, or {@code null} when unassigned.
     *
     * <p>Only the FK is stored; the display name is resolved from the join so a
     * later correction to the employee's name propagates to historical events.</p>
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private Employee assignedTo;
}