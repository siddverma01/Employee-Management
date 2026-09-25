package com.emplmgt.entity;

public enum LeaveType {
    PRIVILEGE_LEAVE("PL", "Privilege Leave"),
    SICK_LEAVE("SL", "Sick Leave"),
    COMP_OFF("CO", "Compensatory Off");

    private final String code;
    private final String label;

    LeaveType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }
}