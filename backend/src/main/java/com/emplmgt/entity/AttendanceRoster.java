package com.emplmgt.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "attendance_rosters",
        uniqueConstraints = @UniqueConstraint(columnNames = {"team_id", "month", "employee_code"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceRoster extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id", nullable = false)
    private Department team;

    @Column(nullable = false, length = 7)
    private String month;

    @Column(name = "employee_code", nullable = false, length = 30)
    private String employeeCode;

    @Column(length = 255)
    private String email;

    @Column(name = "employee_name", nullable = false, length = 150)
    private String employeeName;

    @Column(length = 150)
    private String location;

    @Column(length = 60)
    private String shift;

    @Column(name = "week_off", length = 60)
    private String weekOff;

    /**
     * JSON map of "yyyy-MM-dd" -> status code (WO | WFO | WFH | PL | SL | CO | FL | HPEH | ATR[0-9]* | "").
     */
    @Column(name = "days", nullable = false, columnDefinition = "TEXT")
    private String days;
}