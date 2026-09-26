package com.emplmgt.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "hpe_entitlements",
     uniqueConstraints = @UniqueConstraint(columnNames = {"employee_id", "holiday_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HPEEntitlement extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holiday_id", nullable = false)
    private Holiday holiday;

    @Column(name = "earned_date", nullable = false)
    private LocalDate earnedDate;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private HPEEntitlementStatus status = HPEEntitlementStatus.AVAILABLE;

    @Column(name = "used_date")
    private LocalDate usedDate;

    @Column(name = "used_request_id")
    private Long usedRequestId;

    /**
     * The compensatory-off day this entitlement was actually spent on, captured when the
     * leave request is approved. Deliberately distinct from {@link #usedDate}, which is the
     * date the entitlement was consumed (the approval date) - the two are usually different
     * because the employee picks the off date themselves within the 3-month window.
     * {@code null} until the entitlement is consumed.
     */
    @Column(name = "used_off_date")
    private LocalDate usedOffDate;

    /**
     * The PENDING leave request currently holding this entitlement. Set while status is
     * {@link HPEEntitlementStatus#RESERVED} and cleared once the request is approved
     * (entitlement becomes USED) or rejected/cancelled (entitlement returns to AVAILABLE).
     */
    @Column(name = "reserved_request_id")
    private Long reservedRequestId;

    @Column(name = "notes", length = 500)
    private String notes;
}