package com.emplmgt.entity;

public enum AttendanceType {
    WORK_FROM_OFFICE("WFO"),
    WORK_FROM_HOME("WFH"),
    LEAVE("Leave"),
    PRIVILEGE_LEAVE("PL"),
    SICK_LEAVE("SL"),
    COMP_OFF("CO"),
    FURLOUGH("FL"),
    HOLIDAY("Holiday"),
    WEEK_OFF("Week Off"),
    ATTRITION("ATR");

    private final String shortLabel;

    AttendanceType(String shortLabel) {
        this.shortLabel = shortLabel;
    }

    public String getShortLabel() {
        return shortLabel;
    }
}