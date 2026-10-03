package com.emplmgt.service;

import com.emplmgt.dto.AttendanceRosterDtos;
import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.AttendanceShiftAssignment;
import com.emplmgt.entity.AttendanceWeekOffAssignment;
import com.emplmgt.entity.Department;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.Holiday;
import com.emplmgt.entity.ImportEmployee;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.AttendanceShiftAssignmentRepository;
import com.emplmgt.repository.AttendanceWeekOffAssignmentRepository;
import com.emplmgt.repository.AttendanceStatusRepository;
import com.emplmgt.repository.DepartmentRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.HolidayRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.HistoricalImportCodes;
import com.emplmgt.util.RosterStatusCodes;
import com.emplmgt.util.ShiftTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
    private final AttendanceShiftAssignmentRepository shiftAssignmentRepository;
    private final AttendanceWeekOffAssignmentRepository weekOffAssignmentRepository;
    private final ImportEmployeeRepository importEmployeeRepository;
    private final EmployeeRepository employeeRepository;
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

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, 100));

        // Get all employees who have records in this month OR whose exit month is this month
        // This ensures employees in their exit month are included even if they have no records
        List<ImportEmployee> candidates = importEmployeeRepository
                .findEmployeesForRosterWithExit(teamId, query, loc, from, to, code);

        // Hide employees only from months entirely after their exit month. Every month up to
        // and including the exit month still lists them, so past rosters keep their history.
        candidates = candidates.stream()
                .filter(e -> belongsInMonth(e, ym))
                .toList();

        Map<String, String> periodShifts = resolvePeriodShifts(candidates, from);
        Map<String, String> periodWeekOffs = resolvePeriodWeekOffs(candidates, from);

        // The Shift column shows the dominant shift actually rostered in this month
        // (falling back to the period assignment), so the sort key and the shift filter
        // must use that same value. Sorting by the assignment instead made the grid look
        // unsorted whenever the two disagreed, which is most months once shifts rotate.
        List<String> candidateIds = candidates.stream().map(ImportEmployee::getEmployeeId).toList();
        Map<String, ShiftInPeriod> rosteredShifts = candidateIds.isEmpty() ? Map.of()
                : dominantShiftsFromViews(recordRepository.findShiftsInRange(candidateIds, from, to));
        Map<String, String> displayShifts = new HashMap<>();
        for (ImportEmployee e : candidates) {
            ShiftInPeriod rostered = rosteredShifts.get(e.getEmployeeId());
            displayShifts.put(e.getEmployeeId(),
                    rostered != null ? rostered.dominant() : periodShifts.get(e.getEmployeeId()));
        }

        String wantedShiftKey = ShiftTime.comparisonKey(sh);
        List<ImportEmployee> sorted = candidates.stream()
                .filter(e -> wantedShiftKey == null
                        || wantedShiftKey.equals(ShiftTime.comparisonKey(displayShifts.get(e.getEmployeeId()))))
                .sorted(rosterOrder(displayShifts))
                .toList();

        int total = sorted.size();
        int fromIdx = safePage * safeSize;
        int toIdx = Math.min(fromIdx + safeSize, total);
        List<ImportEmployee> pageList = fromIdx >= total
                ? List.of()
                : new ArrayList<>(sorted.subList(fromIdx, toIdx));

        List<String> pageEmployeeIds = pageList.stream()
                .map(ImportEmployee::getEmployeeId).toList();

        Map<String, String> masterEmailsByCode = pageEmployeeIds.isEmpty() ? Map.of()
                : employeeRepository.findByEmployeeCodeIn(pageEmployeeIds).stream()
                .filter(e -> e.getEmail() != null && !e.getEmail().isBlank())
                .collect(Collectors.toMap(Employee::getEmployeeCode, Employee::getEmail, (a, b) -> a));

        // Get existing attendance records for the month
        List<AttendanceRecord> pageRecords = recordRepository
                .findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(from, to, pageEmployeeIds);
        Map<String, Map<String, String>> cellMap = groupCells(pageRecords);

        // Build exit date map for employees in their exit month
        Map<String, LocalDate> exitDateMap = new HashMap<>();
        for (ImportEmployee e : pageList) {
            if (e.getExitDate() != null) {
                YearMonth exitMonth = YearMonth.from(e.getExitDate());
                if (exitMonth.equals(ym)) {
                    // Employee is in their exit month - use lastWorkingDate to determine ATR start
                    LocalDate lwd = e.getLastWorkingDate();
                    if (lwd != null) {
                        exitDateMap.put(e.getEmployeeId(), lwd);
                    }
                }
            }
        }

        // Apply ATR auto-fill for employees in their exit month
        // For dates after lastWorkingDate up to end of month, show ATR on working days
        // (overriding any existing records for those dates). Weekends and holidays remain as WO/HPEH.
        Map<LocalDate, String> holidays = holidays(ym, teamId);
        for (ImportEmployee e : pageList) {
            LocalDate lwd = exitDateMap.get(e.getEmployeeId());
            if (lwd != null) {
                Map<String, String> employeeDays = cellMap.computeIfAbsent(e.getEmployeeId(), k -> new LinkedHashMap<>());
                for (LocalDate d = lwd.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
                    // Skip weekends and holidays - they remain as WO/HPEH
                    DayOfWeek dow = d.getDayOfWeek();
                    boolean isWeekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
                    boolean isHoliday = holidays.containsKey(d);
                    if (isWeekend || isHoliday) {
                        continue;
                    }
                    String dateKey = d.toString();
                    // Override with ATR for working days after last working date
                    employeeDays.put(dateKey, RosterStatusCodes.ATR);
                }
            }
        }

        Map<Long, String> teamNames = teamNames(pageList);

        List<AttendanceRosterDtos.EmployeeRow> rows = pageList.stream()
                .map(e -> {
                    ShiftInPeriod rostered = rosteredShifts.get(e.getEmployeeId());
                    String rowShift = displayShifts.get(e.getEmployeeId());
                    return new AttendanceRosterDtos.EmployeeRow(
                        e.getEmployeeId(), e.getEmployeeName(),
                        resolveEmail(e.getEmail(), masterEmailsByCode.get(e.getEmployeeId())),
                        e.getLocation(), rowShift, periodWeekOffs.get(e.getEmployeeId()),
                        e.getTeamId(), e.getTeamId() == null ? null : teamNames.get(e.getTeamId()),
                        cellMap.getOrDefault(e.getEmployeeId(), Map.of()),
                        rostered != null && rostered.changesWithinPeriod());
                })
                .toList();

        Map<String, Long> counters = counters(sorted, code, from, to);
        long totalEmployees = importEmployeeRepository.countEmployeeRows(teamId, null, null, from, to);
        long matchedEmployees = total;

        String teamName = teamId == null ? null
                : departmentRepository.findById(teamId).map(Department::getName).orElse(null);

        return new AttendanceRosterDtos.MonthlyResponse(month, teamId, teamName,
                buildDays(ym, teamId), rows, counters, totalEmployees, matchedEmployees,
                safePage, safeSize, total, (total + safeSize - 1) / safeSize);
    }

    // ------------------------------------------------------------------ TODAY

    @Transactional(readOnly = true)
    public AttendanceRosterDtos.TodayResponse today(Long teamId, String q,
                                                    String status, String location, String shift) {
        LocalDate today = appClock.today();
        String query = blankToNull(q);
        String code = blankToNull(status);
        String loc = blankToNull(location);
        String sh = blankToNull(shift);

        // Build single day info
        DayOfWeek dow = today.getDayOfWeek();
        boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
        Map<LocalDate, String> holidays = holidayRepository.findVisibleInRange(today, today, null, teamId)
                .stream().collect(Collectors.toMap(Holiday::getHolidayDate, Holiday::getName));
        String holidayName = holidays.get(today);
        boolean holiday = holidayName != null;

        // Fetch the full filtered set for today, ordered by that day's shift start time
        List<ImportEmployee> candidates = importEmployeeRepository
                .findRosterEmployees(teamId, query, loc, today, today, code);
        Map<String, String> periodShifts = resolvePeriodShifts(candidates, monthStart(today));
        Map<String, String> periodWeekOffs = resolvePeriodWeekOffs(candidates, monthStart(today));
        String wantedShiftKey = ShiftTime.comparisonKey(sh);
        List<ImportEmployee> sorted = candidates.stream()
                .filter(e -> wantedShiftKey == null
                        || wantedShiftKey.equals(ShiftTime.comparisonKey(periodShifts.get(e.getEmployeeId()))))
                .sorted(rosterOrder(periodShifts))
                .toList();

        int total = sorted.size();

        List<String> pageEmployeeIds = sorted.stream()
                .map(ImportEmployee::getEmployeeId).toList();

        Map<String, String> masterEmailsByCode = pageEmployeeIds.isEmpty() ? Map.of()
                : employeeRepository.findByEmployeeCodeIn(pageEmployeeIds).stream()
                .filter(e -> e.getEmail() != null && !e.getEmail().isBlank())
                .collect(Collectors.toMap(Employee::getEmployeeCode, Employee::getEmail, (a, b) -> a));

        // Get attendance records for today only
        Map<String, Map<String, String>> cellMap = groupCells(recordRepository
                .findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(today, today, pageEmployeeIds));

        Map<Long, String> teamNames = teamNames(sorted);

        List<AttendanceRosterDtos.EmployeeRow> rows = sorted.stream()
                .map(e -> new AttendanceRosterDtos.EmployeeRow(
                        e.getEmployeeId(), e.getEmployeeName(),
                        resolveEmail(e.getEmail(), masterEmailsByCode.get(e.getEmployeeId())),
                        e.getLocation(), periodShifts.get(e.getEmployeeId()),
                        periodWeekOffs.get(e.getEmployeeId()),
                        e.getTeamId(), e.getTeamId() == null ? null : teamNames.get(e.getTeamId()),
                        cellMap.getOrDefault(e.getEmployeeId(), Map.of()), false))
                .toList();

        Map<String, Long> counters = counters(sorted, code, today, today);
        long totalEmployees = importEmployeeRepository.countEmployeeRows(teamId, null, null, today, today);
        long matchedEmployees = total;

        String teamName = teamId == null ? null
                : departmentRepository.findById(teamId).map(Department::getName).orElse(null);

        return new AttendanceRosterDtos.TodayResponse(
                today, teamId, teamName,
                dow.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toUpperCase(Locale.ROOT),
                weekend, holiday, holidayName,
                rows, counters, totalEmployees, matchedEmployees);
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
            shifts = periodShiftOptions(teamId, from, to);
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

    /**
     * Whether an employee still belongs on the roster for {@code ym}.
     *
     * <p>Attrition must never rewrite history: an employee stays listed for every
     * month up to and including the month they exited, and only disappears from
     * months that begin after their exit date. The current {@code active} flag is
     * deliberately not consulted here — it reflects today's status, not who was
     * rostered in the past.</p>
     */
    static boolean belongsInMonth(ImportEmployee e, YearMonth ym) {
        LocalDate exitDate = e.getExitDate();
        if (exitDate == null) {
            return true;
        }
        return !YearMonth.from(exitDate).isBefore(ym);
    }

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

    /** One employee's shift across a period, and whether it moved while that period ran. */
    private record ShiftInPeriod(String dominant, boolean changesWithinPeriod) {
    }

    /**
     * The shift an employee held for most of the period, plus a flag for a shift
     * that changed mid-period.
     *
     * <p>The source workbooks roster in five-week blocks rather than calendar
     * months, so a block routinely spans two months and an engineer can change
     * shift part-way through the month the user is viewing. Collapsing that to
     * one value is unavoidable for a single Shift cell, so the dominant one is
     * shown and the change is flagged instead of being hidden.
     */
    private static Map<String, ShiftInPeriod> dominantShifts(List<AttendanceRecord> records) {
        Map<String, List<String>> pairs = new HashMap<>();
        for (AttendanceRecord r : records) {
            pairs.computeIfAbsent(r.getEmployeeId(), k -> new ArrayList<>()).add(r.getShift());
        }
        return collapseDominantShifts(pairs);
    }

    /**
     * Same collapse as {@link #dominantShifts(List)}, but over the lightweight
     * {@code (employeeId, shift)} projection so the sort pass can run across every
     * candidate before pagination.
     */
    private static Map<String, ShiftInPeriod> dominantShiftsFromViews(
            List<AttendanceRecordRepository.EmployeeShiftView> views) {
        Map<String, List<String>> pairs = new HashMap<>();
        for (AttendanceRecordRepository.EmployeeShiftView v : views) {
            pairs.computeIfAbsent(v.getEmployeeId(), k -> new ArrayList<>()).add(v.getShift());
        }
        return collapseDominantShifts(pairs);
    }

    private static Map<String, ShiftInPeriod> collapseDominantShifts(Map<String, List<String>> byEmployee) {
        // employee -> canonical key -> [days, original spelling of the first day]
        Map<String, Map<String, int[]>> counts = new HashMap<>();
        Map<String, Map<String, String>> spellings = new HashMap<>();
        byEmployee.forEach((employee, shifts) -> {
            if (shifts == null) {
                return;
            }
            for (String shift : shifts) {
                if (shift == null || shift.isBlank()) {
                    continue;
                }
                String key = ShiftTime.comparisonKey(shift);
                if (key == null) {
                    continue;
                }
                counts.computeIfAbsent(employee, k -> new HashMap<>())
                        .computeIfAbsent(key, k -> new int[1])[0]++;
                spellings.computeIfAbsent(employee, k -> new HashMap<>())
                        .putIfAbsent(key, shift.trim());
            }
        });

        Map<String, ShiftInPeriod> out = new HashMap<>();
        counts.forEach((employee, byKey) -> {
            String bestKey = null;
            int bestCount = -1;
            for (Map.Entry<String, int[]> e : byKey.entrySet()) {
                if (e.getValue()[0] > bestCount) {
                    bestCount = e.getValue()[0];
                    bestKey = e.getKey();
                }
            }
            out.put(employee, new ShiftInPeriod(
                    spellings.get(employee).get(bestKey), byKey.size() > 1));
        });
        return out;
    }

    private static Map<String, Map<String, String>> groupCells(List<AttendanceRecord> records) {
        Map<String, Map<String, String>> out = new HashMap<>();
        for (AttendanceRecord r : records) {
            out.computeIfAbsent(r.getEmployeeId(), k -> new LinkedHashMap<>())
                    .put(r.getAttendanceDate().toString(), r.getStatusCode());
        }
        return out;
    }

    /** Roster email comes from the master employee record (authoritative source)
     *  when available, else falls back to the import record. */
    private String resolveEmail(String importEmail, String masterEmail) {
        if (masterEmail != null && !masterEmail.isBlank()) {
            return masterEmail;
        }
        return importEmail;
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

    private Map<String, Long> counters(List<ImportEmployee> filtered, String status, LocalDate from, LocalDate to) {
        Map<String, Long> counts = new HashMap<>();
        COUNTER_CODES.forEach(c -> counts.put(c, 0L));
        List<String> ids = filtered.stream().map(ImportEmployee::getEmployeeId).toList();
        if (ids.isEmpty()) {
            return counts;
        }
        for (Object[] row : recordRepository.countByStatusCodes(from, to, status, ids)) {
            String code = (String) row[0];
            counts.put(code, counts.getOrDefault(code, 0L) + (Long) row[1]);
        }
        return counts;
    }

    /**
     * Grid ordering: shift start time ascending, then name A-Z, then employee id.
     * Every key is null-safe because an imported row can carry a blank shift and,
     * for a few records, no name at all - both sort last instead of throwing.
     */
    private static Comparator<ImportEmployee> rosterOrder(Map<String, String> periodShifts) {
        return Comparator
                .comparing((ImportEmployee e) -> ShiftTime.parseShiftStartTime(periodShifts.get(e.getEmployeeId())),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ImportEmployee::getEmployeeName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(ImportEmployee::getEmployeeId,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    /**
     * Resolves the shift each employee was rostered to for one attendance period.
     *
     * <p>Shift rotates, so the employee's current master shift must never stand in
     * for a period the source did not roster them to: an employee with no
     * assignment for this period is left blank. Rows can reach this with no shift
     * at all, for instance when the only records for the period came from a
     * status-only sheet, and guessing from the master would put a shift on the
     * grid that no source row supports.
     */
    private Map<String, String> resolvePeriodShifts(List<ImportEmployee> employees, LocalDate periodStart) {
        Map<String, String> resolved = new HashMap<>();
        if (employees.isEmpty()) {
            return resolved;
        }
        List<String> ids = employees.stream().map(ImportEmployee::getEmployeeId).toList();
        for (AttendanceShiftAssignment a : shiftAssignmentRepository.findByEmployeeIdIn(ids)) {
            if (periodStart.equals(a.getPeriodStart()) && a.getShiftValue() != null) {
                resolved.put(a.getEmployeeId(), a.getShiftValue());
            }
        }
        return resolved;
    }

    /**
     * Resolves the week off each employee was rostered to for one period.
     *
     * <p>Like shift, the schedule rotates between months, so the employee's
     * master week off must never be shown for a period the source did not roster
     * them to. An employee with no assignment for this period is left blank
     * rather than carrying the previous month forward.
     */
    private Map<String, String> resolvePeriodWeekOffs(List<ImportEmployee> employees, LocalDate periodStart) {
        Map<String, String> resolved = new HashMap<>();
        if (employees.isEmpty()) {
            return resolved;
        }
        List<String> ids = employees.stream().map(ImportEmployee::getEmployeeId).toList();
        for (AttendanceWeekOffAssignment a : weekOffAssignmentRepository.findByEmployeeIdIn(ids)) {
            if (periodStart.equals(a.getPeriodStart()) && a.getWeekOffValue() != null) {
                resolved.put(a.getEmployeeId(), a.getWeekOffValue());
            }
        }
        return resolved;
    }

    /**
     * Shift values offered by the roster filter for one period.
     *
     * <p>Built from the period's own assignments rather than the employee master,
     * and de-duplicated by canonical key so the cosmetic variants the workbooks
     * use ("21:00 - 06:00" / "21:00-06:00") collapse into a single option.
     */
    private List<String> periodShiftOptions(Long teamId, LocalDate from, LocalDate to) {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String value : shiftAssignmentRepository
                .findDistinctShiftValuesInPeriod(teamId, from, from, to)) {
            String key = ShiftTime.comparisonKey(value);
            if (key != null) {
                byKey.putIfAbsent(key, value);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static LocalDate monthStart(LocalDate date) {
        return date.withDayOfMonth(1);
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