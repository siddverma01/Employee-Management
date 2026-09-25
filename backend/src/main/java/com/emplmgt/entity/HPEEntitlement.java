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

    @Column(name = "notes", length = 500)
    private String notes;
}