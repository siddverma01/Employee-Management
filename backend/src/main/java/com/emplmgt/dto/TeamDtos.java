package com.emplmgt.dto;

import java.util.List;

public final class TeamDtos {

    private TeamDtos() {
    }

    public enum AvailabilityStatus {
        WORKING,
        WFH,
        OFF,
        WEEK_OFF
    }

    /**
     * Basic availability row. In BASIC (other-team) mode only identity + status,
     * shift, location and week-off are exposed; designation and avatar are null.
     */
    public record TeamMemberAvailability(
            Long employeeId,
            String employeeCode,
            String fullName,
            String status,
            String shift,
            String location,
            String weekOff,
            String designation,
            String avatar) {
    }

    public record TeamDashboard(
            Long teamId,
            String teamName,
            String mode,
            long workingToday,
            long wfhToday,
            long onLeaveToday,
            long weekOffToday,
            List<TeamMemberAvailability> members) {
    }
}