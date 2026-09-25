package com.emplmgt.service;

import com.emplmgt.dto.CalendarDtos;
import com.emplmgt.entity.*;
import com.emplmgt.repository.*;
import com.emplmgt.service.TeamAccessService.AccessMode;
import com.emplmgt.service.TeamAccessService.ResolvedTeam;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CalendarService {

    private final HolidayRepository holidayRepository;
    private final EventRepository eventRepository;
    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final AttendanceRepository attendanceRepository;
    private final TeamAccessService teamAccessService;

    @Transactional(readOnly = true)
    public List<CalendarDtos.CalendarEvent> month(int year, int month, Long teamId) {
        ResolvedTeam ctx = teamAccessService.resolve(teamId);
        boolean full = ctx.mode() == AccessMode.FULL;
        Long scopedTeamId = ctx.teamId();

        YearMonth ym = YearMonth.of(year, month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();

        List<CalendarDtos.CalendarEvent> events = new ArrayList<>();

        for (Holiday h : holidayRepository.findVisibleInRange(from, to, null, scopedTeamId)) {
            events.add(new CalendarDtos.CalendarEvent(
                    h.getId(), CalendarDtos.EventKind.HOLIDAY.name(), h.getHolidayDate(), h.getHolidayDate(), h.getHolidayDate(),
                    h.getName(), h.getHolidayType() != null ? h.getHolidayType().name() : "PUBLIC",
                    null, null, null, null, null, null, null,
                    h.getDescription(),
                    Map.of("country", h.getCountry() == null ? "" : h.getCountry(), "scope", h.getScope().name())));
        }

        for (Event e : eventRepository.findVisibleInRange(from, to, scopedTeamId)) {
            events.add(new CalendarDtos.CalendarEvent(
                    e.getId(), CalendarDtos.EventKind.EVENT.name(), e.getEventDate(), e.getEventDate(), e.getEventDate(),
                    e.getTitle(), e.getEventType() != null ? e.getEventType().name() : "COMPANY_EVENT",
                    null, null, null, null, null, null, null,
                    e.getDescription(),
                    Map.of("scope", e.getScope().name())));
        }

        // Employee-level entries are only shown for the caller's own team (FULL access).
        if (full) {
            List<Employee> teamEmployees = employeesIn(scopedTeamId);

            // Birthdays (only month/day shown, no full DOB exposure).
            for (Employee emp : teamEmployees) {
                if (emp.getDateOfBirth() == null) {
                    continue;
                }
                LocalDate birthday;
                try {
                    birthday = LocalDate.of(year, emp.getDateOfBirth().getMonthValue(), emp.getDateOfBirth().getDayOfMonth());
                } catch (Exception ex) {
                    continue;
                }
                if (!birthday.isBefore(from) && !birthday.isAfter(to)) {
                    events.add(new CalendarDtos.CalendarEvent(
                            emp.getId(), CalendarDtos.EventKind.BIRTHDAY.name(), birthday, birthday, birthday,
                            emp.getFullName(), "Birthday",
                            emp.getFullName(), emp.getEmployeeCode(), emp.getId(), null, null, null, null, null, Map.of()));
                }
            }

            for (LeaveRequest lr : leaveRequestRepository.findApprovedInRange(from, to)) {
                if (!inTeam(lr.getEmployee(), scopedTeamId)) {
                    continue;
                }
                LocalDate effectiveStart = lr.getStartDate().isBefore(from) ? from : lr.getStartDate();
                LocalDate effectiveEnd = lr.getEndDate().isAfter(to) ? to : lr.getEndDate();
                for (LocalDate d = effectiveStart; !d.isAfter(effectiveEnd); d = d.plusDays(1)) {
                    String kind = lr.getLeaveType() == LeaveType.COMP_OFF
                            ? CalendarDtos.EventKind.COMP_OFF.name() : CalendarDtos.EventKind.LEAVE.name();
                    events.add(new CalendarDtos.CalendarEvent(
                            lr.getId(), kind, d, lr.getStartDate(), lr.getEndDate(),
                            displayTitle(lr), lr.getLeaveType().getLabel(),
                            lr.getEmployee().getFullName(), lr.getEmployee().getEmployeeCode(), lr.getEmployee().getId(),
                            lr.getLeaveType().name(), lr.getLeaveType().getCode(), lr.getLeaveType().getLabel(), lr.getStatus().name(),
                            lr.getReason(),
                            Map.of("days", lr.getDays())));
                }
            }

            List<Attendance> compOffs = attendanceRepository
                    .findByAttendanceDateBetweenAndAttendanceType(from, to, AttendanceType.COMP_OFF);
            for (Attendance a : compOffs) {
                if (a.getEmployee() == null || !inTeam(a.getEmployee(), scopedTeamId)) {
                    continue;
                }
                boolean alreadyListed = events.stream().anyMatch(ev -> ev.date().equals(a.getAttendanceDate())
                        && a.getEmployee().getId().equals(ev.employeeId())
                        && ev.kind().equals(CalendarDtos.EventKind.COMP_OFF.name()));
                if (!alreadyListed) {
                    events.add(new CalendarDtos.CalendarEvent(
                            a.getId(), CalendarDtos.EventKind.COMP_OFF.name(), a.getAttendanceDate(),
                            a.getAttendanceDate(), a.getAttendanceDate(),
                            displayTitleForCompOff(a), "Compensatory Off by Swapped Work",
                            a.getEmployee().getFullName(), a.getEmployee().getEmployeeCode(), a.getEmployee().getId(),
                            null, "CO", "Compensatory Off", "APPROVED", a.getRemarks(), Map.of()));
                }
            }
        }

        events.sort((x, y) -> x.date().compareTo(y.date()));
        return events;
    }

    private List<Employee> employeesIn(Long teamId) {
        List<Employee> active = employeeRepository.findByEmploymentStatus(EmploymentStatus.ACTIVE);
        if (teamId == null) {
            return active;
        }
        return active.stream()
                .filter(e -> e.getDepartment() != null && teamId.equals(e.getDepartment().getId()))
                .toList();
    }

    private boolean inTeam(Employee e, Long teamId) {
        return teamId == null || (e.getDepartment() != null && teamId.equals(e.getDepartment().getId()));
    }

    private String displayTitle(LeaveRequest lr) {
        return lr.getEmployee().getFullName() + " - " + lr.getLeaveType().getLabel();
    }

    private String displayTitleForCompOff(Attendance a) {
        return a.getEmployee().getFullName() + " - CO";
    }
}