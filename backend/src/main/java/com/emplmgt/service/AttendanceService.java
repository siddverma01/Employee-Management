package com.emplmgt.service;

import com.emplmgt.dto.AttendanceDtos;
import com.emplmgt.dto.DashboardDtos;
import com.emplmgt.dto.TeamDtos;
import com.emplmgt.entity.*;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.AttendanceRepository;
import com.emplmgt.repository.DepartmentRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.HolidayRepository;
import com.emplmgt.repository.LeaveRequestRepository;
import com.emplmgt.service.TeamAccessService.AccessMode;
import com.emplmgt.service.TeamAccessService.ResolvedTeam;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.WeekOffUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AttendanceService {

    private static final List<AttendanceType> TODAY_TYPES = List.of(
            AttendanceType.WORK_FROM_OFFICE, AttendanceType.WORK_FROM_HOME,
            AttendanceType.LEAVE, AttendanceType.COMP_OFF,
            AttendanceType.PRIVILEGE_LEAVE, AttendanceType.SICK_LEAVE,
            AttendanceType.FURLOUGH, AttendanceType.ATTRITION);

    private final AttendanceRepository attendanceRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final HolidayRepository holidayRepository;
    private final DepartmentRepository departmentRepository;
    private final AppClock appClock;
    private final AuditService auditService;
    private final TeamAccessService teamAccessService;
    private final WeekOffUtil weekOffUtil;

    /**
     * Builds "today's status" for a selected team (or the whole organisation for admins).
     * <ul>
     *   <li>FULL  (own team / admin): working + on-leave entries and rich member rows.</li>
     *   <li>BASIC (other team):       basic availability rows only (no leave details).</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public DashboardDtos.TodayStatus todayStatus(Long teamId, String location) {
        ResolvedTeam ctx = teamAccessService.resolve(teamId);
        TeamDay day = buildTeamDay(ctx);
        if (ctx.mode() == AccessMode.BASIC) {
            return new DashboardDtos.TodayStatus("BASIC", ctx.teamId(), day.teamName(),
                    List.of(), List.of(), day.membersRows());
        }
        return fullStatus(ctx, day, location);
    }

    @Transactional(readOnly = true)
    public TeamDtos.TeamDashboard teamStatus(Long teamId) {
        ResolvedTeam ctx = teamAccessService.resolve(teamId);
        TeamDay day = buildTeamDay(ctx);
        return new TeamDtos.TeamDashboard(ctx.teamId(), day.teamName(), ctx.mode().name(),
                day.working(), day.wfh(), day.onLeave(), day.weekOff(),
                day.membersRows());
    }

    private DashboardDtos.TodayStatus fullStatus(ResolvedTeam ctx, TeamDay day, String location) {
        LocalDate today = appClock.today();
        List<DashboardDtos.TodayEntry> working = new ArrayList<>();
        List<DashboardDtos.TodayEntry> onLeave = new ArrayList<>();

        Map<Long, Attendance> attendanceByEmployee = new HashMap<>();
        for (Attendance a : day.attendanceToday()) {
            attendanceByEmployee.put(a.getEmployee().getId(), a);
        }

        Set<Long> onLeaveIds = new HashSet<>();
        for (LeaveRequest lr : day.approvedToday()) {
            if (onLeaveIds.add(lr.getEmployee().getId())) {
                onLeave.add(toEntry(lr.getEmployee(), AttendanceType.LEAVE, lr.getLeaveType()));
            }
        }
        for (Attendance a : day.attendanceToday()) {
            Employee e = a.getEmployee();
            if (a.getAttendanceType() == AttendanceType.COMP_OFF && !onLeaveIds.contains(e.getId())) {
                onLeave.add(toEntry(e, AttendanceType.COMP_OFF, null));
            }
        }
        if (!day.isHoliday()) {
            for (Employee e : day.members()) {
                if (onLeaveIds.contains(e.getId())) {
                    continue;
                }
                if (location != null && !location.isBlank()
                        && !location.equalsIgnoreCase(e.getLocation() == null ? "" : e.getLocation())) {
                    continue;
                }
                if (weekOffUtil.isWeekOff(e, today)) {
                    continue;
                }
                Attendance att = attendanceByEmployee.get(e.getId());
                if (att != null && (att.getAttendanceType() == AttendanceType.WORK_FROM_OFFICE
                        || att.getAttendanceType() == AttendanceType.WORK_FROM_HOME)) {
                    working.add(toEntry(e, att.getAttendanceType(), null));
                } else if (att == null) {
                    // No explicit record on a normal working day -> default to WFO.
                    working.add(toEntry(e, AttendanceType.WORK_FROM_OFFICE, null));
                }
            }
        }
        return new DashboardDtos.TodayStatus("FULL", ctx.teamId(), day.teamName(),
                working, onLeave, day.membersRows());
    }

    private TeamDay buildTeamDay(ResolvedTeam ctx) {
        LocalDate today = appClock.today();
        Long teamId = ctx.teamId();
        List<Employee> allActive = employeeRepository.findByEmploymentStatus(EmploymentStatus.ACTIVE);
        List<Employee> members = teamId == null
                ? allActive
                : allActive.stream()
                        .filter(e -> e.getDepartment() != null && teamId.equals(e.getDepartment().getId()))
                        .toList();
        String teamName = teamId == null ? null
                : departmentRepository.findById(teamId).map(Department::getName).orElse(null);

        List<Attendance> attendanceToday = attendanceRepository.findByDateAndTypes(today, TODAY_TYPES);
        Map<Long, Attendance> attendanceByEmployee = new HashMap<>();
        for (Attendance a : attendanceToday) {
            attendanceByEmployee.put(a.getEmployee().getId(), a);
        }
        List<LeaveRequest> approvedToday = leaveRequestRepository.findApprovedInRange(today, today);
        Set<Long> onLeaveIds = new HashSet<>();
        for (LeaveRequest lr : approvedToday) {
            onLeaveIds.add(lr.getEmployee().getId());
        }
        boolean isHoliday = today != null
                && !holidayRepository.findVisibleInRange(today, today, null, teamId).isEmpty();

        long working = 0, wfh = 0, onLeave = 0, weekOff = 0;
        List<TeamDtos.TeamMemberAvailability> rows = new ArrayList<>();
        boolean full = ctx.mode() == AccessMode.FULL;
        for (Employee e : members) {
            if (onLeaveIds.contains(e.getId())) {
                onLeave++;
                rows.add(rowFor(e, TeamDtos.AvailabilityStatus.OFF, full));
                continue;
            }
            Attendance att = attendanceByEmployee.get(e.getId());
            TeamDtos.AvailabilityStatus status;
            if (att == null) {
                if (isHoliday) {
                    status = TeamDtos.AvailabilityStatus.OFF;
                } else if (weekOffUtil.isWeekOff(e, today)) {
                    status = TeamDtos.AvailabilityStatus.WEEK_OFF;
                } else {
                    status = TeamDtos.AvailabilityStatus.WORKING;
                }
            } else if (att.getAttendanceType() == AttendanceType.WORK_FROM_OFFICE) {
                status = TeamDtos.AvailabilityStatus.WORKING;
            } else if (att.getAttendanceType() == AttendanceType.WORK_FROM_HOME) {
                status = TeamDtos.AvailabilityStatus.WFH;
            } else {
                status = TeamDtos.AvailabilityStatus.OFF;
            }
            switch (status) {
                case WORKING -> working++;
                case WFH -> wfh++;
                case OFF -> onLeave++;
                case WEEK_OFF -> weekOff++;
            }
            rows.add(rowFor(e, status, full));
        }
        return new TeamDay(teamName, members, attendanceToday, approvedToday, isHoliday, rows,
                working, wfh, onLeave, weekOff);
    }

    private TeamDtos.TeamMemberAvailability rowFor(Employee e, TeamDtos.AvailabilityStatus status, boolean full) {
        return new TeamDtos.TeamMemberAvailability(
                e.getId(),
                e.getEmployeeCode(),
                e.getFullName(),
                status.name(),
                e.getShift(),
                e.getLocation(),
                e.getWeekOff(),
                full ? e.getDesignation() : null,
                full ? e.getProfilePicture() : null);
    }

    private DashboardDtos.TodayEntry toEntry(Employee e, AttendanceType type, LeaveType leaveType) {
        return new DashboardDtos.TodayEntry(
                e.getId(),
                e.getEmployeeCode(),
                e.getFullName(),
                e.getDepartment() != null ? e.getDepartment().getName() : null,
                e.getDesignation(),
                e.getProfilePicture(),
                type,
                leaveType,
                e.getLocation());
    }

    @Transactional(readOnly = true)
    public long workingToday() {
        return todayStatus(null, null).working().size();
    }

    @Transactional(readOnly = true)
    public long onLeaveToday() {
        DashboardDtos.TodayStatus status = todayStatus(null, null);
        if ("BASIC".equals(status.mode())) {
            return status.members().stream().filter(m -> "OFF".equals(m.status())).count();
        }
        return status.onLeave().size();
    }

    @Transactional(readOnly = true)
    public Page<AttendanceDtos.Response> search(LocalDate from, LocalDate to, Long employeeId, Long departmentId,
                                                AttendanceType type, Pageable pageable) {
        return attendanceRepository.search(from, to, employeeId, departmentId, type, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<AttendanceDtos.Response> myAttendance(Long userId, YearMonth month) {
        Employee employee = employeeRepository.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("Employee profile not found"));
        return attendanceRepository.findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDate(
                        employee.getId(), month.atDay(1), month.atEndOfMonth())
                .stream().map(a -> new AttendanceDtos.Response(
                        a.getId(), a.getEmployee().getId(), a.getEmployee().getEmployeeCode(), a.getEmployee().getFullName(),
                        a.getEmployee().getDepartment() != null ? a.getEmployee().getDepartment().getName() : null,
                        a.getAttendanceDate(), a.getAttendanceType(), a.getSource().name(), a.getRemarks()))
                .toList();
    }

    @Transactional
    public AttendanceDtos.Response upsert(AttendanceDtos.ManualCreateRequest request) {
        Employee employee = employeeRepository.findByEmployeeCodeIgnoreCase(request.employeeCode())
                .orElseThrow(() -> ApiException.badRequest("Unknown employee code: " + request.employeeCode()));
        Attendance existing = attendanceRepository.findByEmployeeIdAndAttendanceDate(employee.getId(), request.date())
                .orElse(null);
        if (existing != null) {
            existing.setAttendanceType(request.attendanceType());
            existing.setRemarks(request.remarks());
            existing.setSource(AttendanceSource.MANUAL);
            Attendance saved = attendanceRepository.save(existing);
            auditService.record("ATTENDANCE_UPDATED", "Attendance", String.valueOf(saved.getId()),
                    Map.of("type", request.attendanceType().name(), "date", request.date().toString()), null);
            return toResponse(saved);
        }
        Attendance attendance = Attendance.builder()
                .employee(employee)
                .attendanceDate(request.date())
                .attendanceType(request.attendanceType())
                .source(AttendanceSource.MANUAL)
                .remarks(request.remarks())
                .build();
        Attendance saved = attendanceRepository.save(attendance);
        auditService.record("ATTENDANCE_CREATED", "Attendance", String.valueOf(saved.getId()),
                null, Map.of("type", request.attendanceType().name(), "date", request.date().toString()));
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long attendanceId) {
        attendanceRepository.deleteById(attendanceId);
        auditService.record("ATTENDANCE_DELETED", "Attendance", String.valueOf(attendanceId), null, null);
    }

    private AttendanceDtos.Response toResponse(Attendance a) {
        return new AttendanceDtos.Response(
                a.getId(), a.getEmployee().getId(), a.getEmployee().getEmployeeCode(), a.getEmployee().getFullName(),
                a.getEmployee().getDepartment() != null ? a.getEmployee().getDepartment().getName() : null,
                a.getAttendanceDate(), a.getAttendanceType(), a.getSource().name(), a.getRemarks());
    }

    private record TeamDay(
            String teamName,
            List<Employee> members,
            List<Attendance> attendanceToday,
            List<LeaveRequest> approvedToday,
            boolean isHoliday,
            List<TeamDtos.TeamMemberAvailability> membersRows,
            long working,
            long wfh,
            long onLeave,
            long weekOff) {
    }
}