package com.emplmgt.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "leave_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeaveRequest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Enumerated(EnumType.STRING)
    @Column(name = "leave_type", nullable = false, length = 30)
    private LeaveType leaveType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false, precision = 5, scale = 1)
    private BigDecimal days;

    @Column(length = 1000)
    private String reason;

    @Column(length = 500)
    private String attachment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private LeaveStatus status = LeaveStatus.PENDING;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /**
     * The earned HPE Holiday entitlement this request consumes. Set only for
     * {@link LeaveType#COMP_OFF} requests that are backed by an HPE Holiday.
     * The compensatory off date is chosen independently and is not tied to the
     * original HPE Holiday date.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hpe_entitlement_id")
    private HPEEntitlement hpeEntitlement;
}