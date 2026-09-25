package com.emplmgt.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Historical employee snapshot imported from attendance workbooks.
 * <code>employeeId</code> holds the employee <em>code</em> (e.g. "25106149").
 * This is intentionally decoupled from the live {@link Employee} table so
 * imports never fail on missing/duplicate emails or registration gaps.
 */
@Entity
@Table(name = "import_employees")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportEmployee extends BaseEntity {

    @Id
    @Column(name = "employee_id", length = 40)
    private String employeeId;

    @Column(name = "employee_name", length = 200)
    private String employeeName;

    @Column(length = 255)
    private String email;

    @Column(length = 150)
    private String location;

    @Column(length = 150)
    private String manager;

    @Column(name = "default_shift", length = 60)
    private String defaultShift;

    @Column(name = "week_off", length = 60)
    private String weekOff;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Column(name = "team_id")
    private Long teamId;
}