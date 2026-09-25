package com.emplmgt.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "holidays",
     uniqueConstraints = @UniqueConstraint(columnNames = {"holiday_date", "country", "name"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Holiday extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(length = 80)
    @Builder.Default
    private String country = "US";

    @Enumerated(EnumType.STRING)
    @Column(name = "holiday_type", length = 30)
    @Builder.Default
    private HolidayType holidayType = HolidayType.PUBLIC;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 10, nullable = false)
    @Builder.Default
    private ScopeType scope = ScopeType.GLOBAL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Department team;
}