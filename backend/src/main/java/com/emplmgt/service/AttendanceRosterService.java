package com.emplmgt.service;

import com.emplmgt.dto.AttendanceRosterDtos;
import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.Department;
import com.emplmgt.entity.Holiday;
import com.emplmgt.entity.ImportEmployee;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.AttendanceStatusRepository;
import com.emplmgt.repository.DepartmentRepository;
import com.emplmgt.repository.HolidayRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.HistoricalImportCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Month-wise Attendance Roster: a wide grid of employee vs. day status cells
 * backed by {@code attendance_records} + {@code import_employees}. All heavy
 * lifting (month selection, filtering, pagination, counters, batch save) is
 * done server-side so the UI only ever loads one month at a time.
 */
@Service
@RequiredArgsConstructor
public class AttendanceRosterService {

    /** Counter status codes surfaced at the top-right of the grid. */
    private static final List<String> COUNTER_CODES = List.of("WO", "PL", "WFH", "WFO", "SL", "CO");

    private final AttendanceRecordRepository recordRepository;
    private final ImportEmployeeRepository importEmployeeRepository;
    private final DepartmentRepository departmentRepository;
    private final AttendanceStatusRepository statusRepository;
    private final HolidayRepository holidayRepository;
    private final AuditService auditService;
    private final AppClock appClock;

    // ------------------------------------------------------------------ GRID

    @Transactional(readOnly = true)
    public AttendanceRosterDtos.MonthlyResponse monthly(Long teamId, String month, String q,
                                                        String status, String location, String shift,
                                                        int page, int size) {
        YearMonth ym = parseMonth(month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        String query = blankToNull(q);
        String code = blankToNull(status);
        String loc = blankToNull(location);
        String sh = blankToNull(shift);

        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));
        Page<ImportEmployee> employees = importEmployeeRepository
                .findRosterEmployeePage(teamId, query, loc, sh, from, to, code, pageable);

        List<String> pageEmployeeIds = employees.getContent().stream()
                .map(ImportEmployee::getEmployeeId).toList();

        Map<String, Map<String, String>> cellMap = groupCells(recordRepository
                .findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(from, to, pageEmployeeIds));

        Map<Long, String> teamNames = teamNames(employees.getContent());

        List<AttendanceRosterDtos.EmployeeRow> rows = employees.getContent().stream()
                .map(e -> new AttendanceRosterDtos.EmployeeRow(
                        e.getEmployeeId(), e.getEmployeeName(), e.getEmail(),
                        e.getLocation(), e.getDefaultShift(), e.getWeekOff(),
                        e.getTeamId(), e.getTeamId() == null ? null : teamNames.get(e.getTeamId()),
                        cellMap.getOrDefault(e.getEmployeeId(), Map.of())))
                .toList();

        Map<String, Long> counters = counters(teamId, query, loc, sh, code, from, to);
        long totalEmployees = importEmployeeRepository.countEmployeeRows(teamId, null, null, null, from, to);
        long matchedEmployees = importEmployeeRepository.countEmployeeRows(teamId, query, loc, sh, from, to);

        String teamName = teamId == null ? null
                : departmentRepository.findById(teamId).map(Department::getName).orElse(null);

        return new AttendanceRosterDtos.MonthlyResponse(month, teamId, teamName,
                buildDays(ym, teamId), rows, counters, totalEmployees, matchedEmployees,
                employees.getNumber(), employees.getSize(), employees.getTotalElements(),
                employees.getTotalPages());
    }

    // ------------------------------------------------------------------ META

    @Transactional(readOnly = true)
    public AttendanceRosterDtos.PageMeta meta(Long teamId, String month) {
        List<Long> teamIdsWithEmployees = importEmployeeRepository.findDistinctTeamIds();
        List<AttendanceRosterDtos.TeamOption> teams = teamIdsWithEmployees.isEmpty() ? List.of()
                : departmentRepository.findAllById(teamIdsWithEmployees).stream()
                .sorted(Comparator.comparing(Department::getName, String.CASE_INSENSITIVE_ORDER))
                .map(d -> new AttendanceRosterDtos.TeamOption(d.getId(), d.getName()))
                .toList();

        List<String> months = months();

        List<String> locations = List.of();
        List<String> shifts = List.of();
        if (month != null && !month.isBlank()) {
            YearMonth ym = parseMonth(month);
            LocalDate from = ym.atDay(1);
            LocalDate to = ym.atEndOfMonth();
            locations = importEmployeeRepository.findDistinctLocationsInMonth(teamId, from, to);
            shifts = importEmployeeRepository.findDistinctShiftsInMonth(teamId, from, to);
        }

        List<AttendanceRosterDtos.StatusOption> statuses = statusRepository.findAllByOrderByCodeAsc().stream()
                .map(s -> new AttendanceRosterDtos.StatusOption(s.getCode(), s.getName(), s.getDisplayColor()))
                .toList();

        return new AttendanceRosterDtos.PageMeta(teams, months, locations, shifts, statuses);
    }

    private List<String> months() {
        Set<YearMonth> months = new LinkedHashSet<>();
        recordRepository.findDistinctAttendanceDatesAsc()
                .forEach(d -> months.add(YearMonth.from(d)));
        return months.stream()
                .sorted()
                .map(YearMonth::toString)
                .toList();
    }

    // ------------------------------------------------------------------ SAVE

    @Transactional
    public AttendanceRosterDtos.BatchSaveResponse save(AttendanceRosterDtos.BatchSaveRequest req) {
        Map<String, AttendanceRosterDtos.CellEdit> deduped = new LinkedHashMap<>();
        for (AttendanceRosterDtos.CellEdit c : req.changes()) {
            deduped.put(c.employeeId() + "|" + c.date(), c);
        }
        if (deduped.isEmpty()) {
            return new AttendanceRosterDtos.BatchSaveResponse(0);
        }

        int saved = 0;
        for (AttendanceRosterDtos.CellEdit c : deduped.values()) {
            String code = HistoricalImportCodes.resolveStatus(c.statusCode());
            if (code == null || code.isBlank()) {
                recordRepository.deleteByEmployeeIdAndAttendanceDate(c.employeeId(), c.date());
                saved++;
                continue;
            }
            ensureEmployeeExists(c.employeeId());
            saved += upsertRecord(c, code);
        }

        auditService.record("ROSTER_CELLS_UPDATED", "AttendanceRecord", null,
                null, Map.of("changedCount", saved));

        return new AttendanceRosterDtos.BatchSaveResponse(saved);
    }

    private int upsertRecord(AttendanceRosterDtos.CellEdit c, String code) {
        AttendanceRecord rec = recordRepository
                .findByEmployeeIdAndAttendanceDate(c.employeeId(), c.date())
                .orElse(null);
        boolean isNew = rec == null;
        if (rec == null) {
            rec = new AttendanceRecord();
            rec.setEmployeeId(c.employeeId());
            rec.setAttendanceDate(c.date());
            rec.setImportedAt(appClock.now());
        }
        rec.setStatusCode(code);
        boolean known = HistoricalImportCodes.isKnown(code);
        rec.setStatusName(known ? HistoricalImportCodes.nameOf(code) : null);
        rec.setIsUnknown(!known);
        rec.setUpdatedAt(appClock.now());
        recordRepository.save(rec);
        return 1;
    }

    private void ensureEmployeeExists(String employeeId) {
        if (!importEmployeeRepository.existsById(employeeId)) {
            ImportEmployee emp = ImportEmployee.builder().employeeId(employeeId).active(Boolean.TRUE).build();
            importEmployeeRepository.save(emp);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            throw ApiException.badRequest("Month is required (yyyy-MM)");
        }
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            throw ApiException.badRequest("Invalid month '" + month + "'. Expected yyyy-MM");
        }
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static Map<String, Map<String, String>> groupCells(List<AttendanceRecord> records) {
        Map<String, Map<String, String>> out = new HashMap<>();
        for (AttendanceRecord r : records) {
            out.computeIfAbsent(r.getEmployeeId(), k -> new LinkedHashMap<>())
                    .put(r.getAttendanceDate().toString(), r.getStatusCode());
        }
        return out;
    }

    private Map<Long, String> teamNames(List<ImportEmployee> employees) {
        Set<Long> ids = employees.stream().map(ImportEmployee::getTeamId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return departmentRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Department::getId, Department::getName));
    }

    private Map<String, Long> counters(Long teamId, String q, String location, String shift,
                                       String status, LocalDate from, LocalDate to) {
        List<String> filtered = importEmployeeRepository.findFilteredEmployeeIds(teamId, q, location, shift);
        Map<String, Long> counts = new HashMap<>();
        COUNTER_CODES.forEach(c -> counts.put(c, 0L));
        if (filtered.isEmpty()) {
            return counts;
        }
        for (Object[] row : recordRepository.countByStatusCodes(from, to, status, filtered)) {
            String code = (String) row[0];
            counts.put(code, counts.getOrDefault(code, 0L) + (Long) row[1]);
        }
        return counts;
    }

    private List<AttendanceRosterDtos.DayInfo> buildDays(YearMonth ym, Long teamId) {
        Map<LocalDate, String> holidays = holidays(ym, teamId);
        List<AttendanceRosterDtos.DayInfo> days = new ArrayList<>();
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            LocalDate date = ym.atDay(d);
            DayOfWeek dow = date.getDayOfWeek();
            boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            String holidayName = holidays.get(date);
            days.add(new AttendanceRosterDtos.DayInfo(date.toString(), d,
                    dow.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT),
                    weekend, holidayName != null, holidayName));
        }
        return days;
    }

    private Map<LocalDate, String> holidays(YearMonth ym, Long teamId) {
        Map<LocalDate, String> out = new LinkedHashMap<>();
        holidayRepository.findVisibleInRange(ym.atDay(1), ym.atEndOfMonth(), null, teamId)
                .forEach(h -> out.putIfAbsent(h.getHolidayDate(), h.getName()));
        return out;
    }
}