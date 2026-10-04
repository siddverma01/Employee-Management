package com.emplmgt.service;

import com.emplmgt.dto.AttendanceAnalyticsDtos;
import com.emplmgt.entity.Attendance;
import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.Department;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.ImportEmployee;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.AttendanceRepository;
import com.emplmgt.repository.DepartmentRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.HolidayRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import com.emplmgt.util.RosterStatusCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

/**
 * Analytics service for employee attendance.
 *
 * <p>The employee list comes from the current Attendance Roster
 * ({@link AttendanceRosterService#rosterEmployees}), so Analytics and the roster always
 * show the same people in the same order. Attendance statistics are then aggregated for
 * those rostered employees from both historical imports ({@code attendance_records})
 * and website-maintained records ({@code attendance}); history supplies the numbers only
 * and never decides who is listed.</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AttendanceAnalyticsService {

    private final AttendanceRecordRepository attendanceRecordRepository;
    private final AttendanceRepository attendanceRepository;
    private final ImportEmployeeRepository importEmployeeRepository;
    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final HolidayRepository holidayRepository;
    private final AttendanceRosterService attendanceRosterService;
    private final EntityManager entityManager;

    // Status code categories
    private static final Set<String> WFO_CODES = Set.of("WFO");
    private static final Set<String> WFH_CODES = Set.of("WFH");
    private static final Set<String> PL_CODES = Set.of("PL");
    private static final Set<String> SL_CODES = Set.of("SL");
    private static final Set<String> CO_CODES = Set.of("CO");
    private static final Set<String> WO_CODES = Set.of("WO");
    private static final Set<String> HPEH_CODES = Set.of("HPEH");
    private static final Set<String> FL_CODES = Set.of("FL");
    private static final Set<String> HD_CODES = Set.of("HD");
    private static final Set<String> ATR_CODES = Set.of("ATR");
    private static final Set<String> SW_OFF_CODES = Set.of("SW-OFF", "SW_OFF");
    private static final Set<String> SW_WK_CODES = Set.of("SW-WK", "SW_WK");
    private static final Set<String> WK_WRK_CODES = Set.of("WK-WRK", "WK_WRK");
    private static final Set<String> TR_CODES = Set.of("TR");
    private static final Set<String> ITS_CODES = Set.of("ITS");
    private static final Set<String> WDT_CODES = Set.of("WDT");
    private static final Set<String> WX_CODES = Set.of("WX");
    private static final Set<String> LEAVE_CODES = Set.of("PL", "SL", "CO", "FL", "HD", "SW-OFF", "SW_OFF", "SW-WK", "SW_WK", "WK-WRK", "WK_WRK", "TR", "ITS", "WDT", "WX");
    private static final Set<String> ATTENDANCE_CODES = Set.of("WFO", "WFH", "PL", "SL", "CO", "HPEH");
    private static final Set<String> LEAVE_CODES_ALL = Set.of("PL", "SL", "CO", "FL", "HD", "SW-OFF", "SW_OFF", "SW-WK", "SW_WK", "WK-WRK", "WK_WRK", "TR", "ITS", "WDT", "WX", "ATR");
    private static final Set<String> SHRINKAGE_LEAVE_CODES = Set.of("PL", "SL", "CO", "FL", "HD", "SW-OFF", "SW_OFF", "SW-WK", "SW_WK", "WK-WRK", "WK_WRK", "TR", "ITS", "WDT", "WX");

    // ------------------------------------------------------------------ METADATA

    public AttendanceAnalyticsDtos.MetaResponse meta(AttendanceAnalyticsDtos.MetaQuery query) {
        List<AttendanceAnalyticsDtos.TeamOption> teams = departmentRepository.findAll().stream()
                .map(d -> new AttendanceAnalyticsDtos.TeamOption(d.getId(), d.getName()))
                .sorted(Comparator.comparing(AttendanceAnalyticsDtos.TeamOption::name, String.CASE_INSENSITIVE_ORDER))
                .toList();

        // Get distinct months from attendance_records
        List<LocalDate> recordDates = attendanceRecordRepository.findDistinctAttendanceDatesAsc();
        Set<YearMonth> monthsSeen = new LinkedHashSet<>();
        Set<Integer> yearsSeen = new LinkedHashSet<>();
        for (LocalDate d : recordDates) {
            YearMonth ym = YearMonth.from(d);
            monthsSeen.add(ym);
            yearsSeen.add(d.getYear());
        }

        // Also include dates from website attendance table
        List<LocalDate> attendanceDates = attendanceRepository.findDistinctAttendanceDatesAsc();
        for (LocalDate d : attendanceDates) {
            YearMonth ym = YearMonth.from(d);
            monthsSeen.add(ym);
            yearsSeen.add(d.getYear());
        }

        List<String> months = monthsSeen.stream().sorted().map(YearMonth::toString).toList();
        List<String> years = yearsSeen.stream().sorted().map(String::valueOf).toList();

        // Locations come from the roster employee master, the same source the employee
        // list is filtered by, so every offered location can actually match a row.
        String rosterMonth = query.month() != null && query.year() != null
                ? YearMonth.of(query.year(), query.month()).toString()
                : attendanceRosterService.resolveCurrentMonth();
        List<String> mergedLocations = attendanceRosterService.rosterLocations(query.teamId(), rosterMonth);

        List<AttendanceAnalyticsDtos.LocationOption> locations = mergedLocations.stream()
                .filter(Objects::nonNull)
                .filter(s -> !s.isBlank())
                .map(AttendanceAnalyticsDtos.LocationOption::new)
                .sorted(Comparator.comparing(AttendanceAnalyticsDtos.LocationOption::value, String.CASE_INSENSITIVE_ORDER))
                .toList();

        // Status options from roster status codes
        List<AttendanceAnalyticsDtos.StatusOption> statuses = Arrays.stream(RosterStatusCodes.BASE_CODES.toArray(new String[0]))
                .map(code -> new AttendanceAnalyticsDtos.StatusOption(code, RosterStatusCodes.labelOf(code)))
                .toList();

        return new AttendanceAnalyticsDtos.MetaResponse(new AttendanceAnalyticsDtos.Meta(teams, months, years, locations, statuses));
    }

    // ------------------------------------------------------------------ EMPLOYEE STATS (MAIN TABLE)

    public AttendanceAnalyticsDtos.EmployeeStatsResponse getEmployeeStats(AttendanceAnalyticsDtos.AnalyticsQuery query) {
        LocalDate from = null;
        LocalDate to = null;

        if (query.month() != null && query.year() != null) {
            YearMonth ym = YearMonth.of(query.year(), query.month());
            from = ym.atDay(1);
            to = ym.atEndOfMonth();
        } else if (query.year() != null) {
            from = LocalDate.of(query.year(), 1, 1);
            to = LocalDate.of(query.year(), 12, 31);
        }

        // For OVERALL mode, use earliest available date to today
        if (query.mode() == AttendanceAnalyticsDtos.ReportMode.OVERALL) {
            if (from == null) {
                LocalDate earliestRecord = getEarliestAttendanceDate();
                if (earliestRecord != null) {
                    from = earliestRecord;
                }
            }
            if (to == null) {
                to = LocalDate.now();
            }
        }

        // Cap 'to' at today for working day calculations
        if (to != null && to.isAfter(LocalDate.now())) {
            to = LocalDate.now();
        }

        // The employee list is the current roster, never the set of people who happen to
        // appear in attendance records. In MONTHLY mode that is the selected month; in
        // OVERALL mode the totals span all history while the roster month stays on the
        // currently selected roster, so both modes list the same current employees.
        String rosterMonth = resolveRosterMonth(query);
        List<AttendanceRosterService.RosterEmployee> roster = attendanceRosterService.rosterEmployees(
                query.teamId(), rosterMonth, query.location(), query.status(), null);

        String searchId = query.employeeId() == null ? null : query.employeeId().trim().toLowerCase();
        String searchName = query.employeeName() == null ? null : query.employeeName().trim().toLowerCase();
        boolean searching = isPresent(searchId) || isPresent(searchName);

        List<AttendanceAnalyticsDtos.EmployeeSummary> summaries = new ArrayList<>();
        if (searching) {
            // A search deliberately widens the net past the roster: someone who has left
            // is absent from it, yet their history must stay reachable. Roster members
            // keep the roster's resolved shift/week off; everyone else falls back to the
            // employee master, which is all a former employee has.
            Map<String, AttendanceAnalyticsDtos.EmployeeSummary> byCode = new LinkedHashMap<>();
            for (AttendanceRosterService.RosterEmployee e : roster) {
                if (matches(e.employeeCode(), e.employeeName(), searchId, searchName)) {
                    byCode.put(e.employeeCode(), buildEmployeeSummary(e, from, to, query.mode(), true));
                }
            }
            for (ImportEmployee e : importEmployeeRepository.searchByNameOrId(
                    isPresent(searchId) ? searchId : searchName)) {
                if (byCode.containsKey(e.getEmployeeId())) {
                    continue;
                }
                if (teamIdMismatch(query.teamId(), e.getTeamId()) || locationMismatch(query.location(), e.getLocation())) {
                    continue;
                }
                AttendanceRosterService.RosterEmployee candidate = new AttendanceRosterService.RosterEmployee(
                        e.getEmployeeId(), e.getEmployeeName(), e.getLocation(),
                        e.getDefaultShift(), e.getWeekOff());
                byCode.put(e.getEmployeeId(), buildEmployeeSummary(candidate, from, to, query.mode(), false));
            }
            // Safety net for an employee id with no master row at all: still findable by id.
            for (String code : importEmployeeRepository.findAttendanceOnlyEmployeeIds()) {
                if (byCode.containsKey(code) || !matches(code, code, searchId, searchName)) {
                    continue;
                }
                AttendanceRosterService.RosterEmployee candidate =
                        new AttendanceRosterService.RosterEmployee(code, code, null, null, null);
                byCode.put(code, buildEmployeeSummary(candidate, from, to, query.mode(), false));
            }
            summaries.addAll(byCode.values());
        } else {
            // Default: exactly the selected month's roster, in roster order, including
            // anyone on it with no attendance in the period.
            for (AttendanceRosterService.RosterEmployee e : roster) {
                summaries.add(buildEmployeeSummary(e, from, to, query.mode(), true));
            }
        }

        int page = query.page() != null ? Math.max(0, query.page()) : 0;
        int pageSize = query.size() != null ? Math.max(1, Math.min(query.size(), 100)) : 30;

        // Default order is roster order (the list is already in it). An explicit sort
        // reorders the full filtered set, so a sort means the same thing on every page.
        if (query.sortBy() != null && !query.sortBy().isBlank()) {
            Comparator<AttendanceAnalyticsDtos.EmployeeSummary> comparator = comparatorFor(query.sortBy());
            if ("desc".equalsIgnoreCase(query.sortDir())) {
                comparator = comparator.reversed();
            }
            summaries.sort(comparator);
        }

        int totalElements = summaries.size();
        int totalPages = (int) Math.ceil((double) totalElements / pageSize);
        int fromIdx = page * pageSize;
        int toIdx = Math.min(fromIdx + pageSize, totalElements);
        List<AttendanceAnalyticsDtos.EmployeeSummary> pageSummaries =
                fromIdx >= totalElements ? List.of() : new ArrayList<>(summaries.subList(fromIdx, toIdx));

        // S.No follows the displayed order, continuing across pages.
        List<AttendanceAnalyticsDtos.EmployeeSummary> numbered = new ArrayList<>(pageSummaries.size());
        long sNo = (long) fromIdx + 1;
        for (AttendanceAnalyticsDtos.EmployeeSummary s : pageSummaries) {
            numbered.add(new AttendanceAnalyticsDtos.EmployeeSummary(
                    sNo++, s.employeeId(), s.employeeName(), s.location(), s.shift(), s.weekOff(),
                    s.inCurrentRoster(), s.employmentStatus(), s.wfo(), s.wfh(), s.wo(), s.pl(), s.co(), s.sl(), s.hd(),
                    s.wkWrk(), s.shrinkage(), s.atr(), s.totalWorkingDays(), s.totalLeaves(),
                    s.attendancePercentage(), s.attendanceRecorded()));
        }

        // Calculate overall summary
        AttendanceAnalyticsDtos.EmployeeSummary overallSummary = calculateOverallSummary(numbered);

        return new AttendanceAnalyticsDtos.EmployeeStatsResponse(
                numbered,
                overallSummary,
                totalElements,
                page,
                pageSize,
                totalPages
        );
    }

    /**
     * The roster month whose employees back the report.
     *
     * <p>A selected month wins. Otherwise the roster's current month is used, which is
     * what makes an OVERALL report (no month picker) still list today's roster.</p>
     */
    private String resolveRosterMonth(AttendanceAnalyticsDtos.AnalyticsQuery query) {
        if (query.month() != null && query.year() != null) {
            return YearMonth.of(query.year(), query.month()).toString();
        }
        return attendanceRosterService.resolveCurrentMonth();
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Whether an employee matches the active search.
     *
     * <p>A blank field on one side does not veto a match on the other: searching by id
     * alone must not require the name to contain the id as well.</p>
     */
    private static boolean matches(String code, String name, String searchId, String searchName) {
        boolean idHit = isPresent(searchId) && code != null && code.toLowerCase().contains(searchId);
        boolean nameHit = isPresent(searchName) && name != null && name.toLowerCase().contains(searchName);
        return idHit || nameHit;
    }

    private static boolean teamIdMismatch(Long filterTeamId, Long employeeTeamId) {
        return filterTeamId != null && !filterTeamId.equals(employeeTeamId);
    }

    private static boolean locationMismatch(String filterLocation, String employeeLocation) {
        if (!isPresent(filterLocation)) {
            return false;
        }
        return employeeLocation == null || !employeeLocation.equals(filterLocation.trim());
    }

    // ------------------------------------------------------------------ EMPLOYEE DETAIL VIEW

    public AttendanceAnalyticsDtos.EmployeeDetailResponse getEmployeeDetail(String employeeCode, AttendanceAnalyticsDtos.AnalyticsQuery query) {
        ImportEmployee impEmp = importEmployeeRepository.findById(employeeCode)
                .orElseThrow(() -> ApiException.notFound("Employee not found in import records"));

        // Get all records for this employee
        Map<LocalDate, String> statusByDate = new LinkedHashMap<>();

        for (AttendanceRecord r : attendanceRecordRepository.findByEmployeeIdOrderByAttendanceDateAsc(employeeCode)) {
            statusByDate.put(r.getAttendanceDate(), r.getStatusCode());
        }

        employeeRepository.findByEmployeeCodeIgnoreCase(employeeCode).ifPresent(empEnt ->
                attendanceRepository.findByEmployeeIdOrderByAttendanceDateAsc(empEnt.getId()).forEach(a -> {
                    LocalDate date = a.getAttendanceDate();
                    if (!statusByDate.containsKey(date)) {
                        statusByDate.put(date, a.getAttendanceType().getShortLabel());
                    }
                })
        );

        // Build monthly breakdown with all required fields
        List<AttendanceAnalyticsDtos.MonthStat> monthlyBreakdown = new ArrayList<>();
        LocalDate earliest = statusByDate.keySet().stream().min(LocalDate::compareTo).orElse(null);
        LocalDate latest = statusByDate.keySet().stream().max(LocalDate::compareTo).orElse(null);

        if (earliest != null) {
            YearMonth startYm = YearMonth.from(earliest);
            YearMonth endYm = YearMonth.from(LocalDate.now());
            YearMonth current = startYm;

            while (!current.isAfter(endYm) && !current.isAfter(YearMonth.from(LocalDate.now()))) {
                long workingDays = 0, wfo = 0, wfh = 0, wo = 0, pl = 0, co = 0, sl = 0, hd = 0, wkWrk = 0, totalLeaves = 0;
                double shrinkage = 0.0;

                for (int d = 1; d <= current.lengthOfMonth(); d++) {
                    LocalDate date = current.atDay(d);
                    String status = statusByDate.get(date);
                    if (status != null) {
                        if (isWorkingDay(status)) wkWrk++;
                        if (ATTENDANCE_CODES.contains(status)) {
                            workingDays++;
                        }
                        switch (status) {
                            case "WFO" -> {}
                            case "WFH" -> {}
                            case "PL" -> totalLeaves++;
                            case "SL" -> totalLeaves++;
                            case "CO" -> totalLeaves++;
                            case "FL" -> totalLeaves++;
                            case "HD" -> totalLeaves++;
                            case "WO" -> {}
                            case "HPEH" -> totalLeaves++;
                            default -> totalLeaves++;
                        }
                    }
                }

                if (wkWrk > 0) {
                    shrinkage = ((double) totalLeaves / wkWrk) * 100.0;
                }

                monthlyBreakdown.add(new AttendanceAnalyticsDtos.MonthStat(
                        current.toString(), workingDays, wfo, wfh, wo, pl, co, sl, hd, wkWrk, shrinkage, totalLeaves
                ));

                current = current.plusMonths(1);
                if (current.isAfter(YearMonth.from(LocalDate.now()))) break;
            }
        }

        // Attendance history (date-wise)
        List<AttendanceAnalyticsDtos.AttendanceDayView> history = new ArrayList<>();
        List<LocalDate> sortedDates = new ArrayList<>(statusByDate.keySet());
        sortedDates.sort(LocalDate::compareTo);

        for (LocalDate date : sortedDates) {
            String status = statusByDate.get(date);
            DayOfWeek dow = date.getDayOfWeek();
            String dayName = dow.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);

            history.add(new AttendanceAnalyticsDtos.AttendanceDayView(
                    date.toString(),
                    dayName,
                    status,
                    null,
                    "System",
                    ""
            ));
        }

        // Calculate overall stats
        long totalWorkingDays = statusByDate.values().stream().filter(this::isWorkingDay).count();
        long wfo = statusByDate.values().stream().filter(s -> WFO_CODES.contains(s)).count();
        long wfh = statusByDate.values().stream().filter(s -> WFH_CODES.contains(s)).count();
        long pl = statusByDate.values().stream().filter(s -> PL_CODES.contains(s)).count();
        long sl = statusByDate.values().stream().filter(s -> SL_CODES.contains(s)).count();
        long co = statusByDate.values().stream().filter(s -> CO_CODES.contains(s)).count();
        long wo = statusByDate.values().stream().filter(s -> WO_CODES.contains(s)).count();
        long hd = statusByDate.values().stream().filter(s -> HD_CODES.contains(s)).count();
        long hpeh = statusByDate.values().stream().filter(s -> HPEH_CODES.contains(s)).count();
        long fl = statusByDate.values().stream().filter(s -> FL_CODES.contains(s)).count();
        long atr = statusByDate.values().stream().filter(s -> ATR_CODES.contains(s)).count();
        long wkWrk = statusByDate.values().stream().filter(this::isWorkingDay).count();
        long totalLeaves = statusByDate.values().stream().filter(s -> LEAVE_CODES_ALL.contains(s)).count();
        double shrinkage = wkWrk > 0 ? ((double) totalLeaves / wkWrk) * 100.0 : 0.0;

        long attendanceRecorded = statusByDate.size();
        double attendancePct = wkWrk > 0 ? (double) (wfo + wfh) / wkWrk * 100.0 : 0.0;

        Employee emp = employeeRepository.findByEmployeeCodeIgnoreCase(employeeCode).orElse(null);
        String teamName = null;
        if (impEmp.getTeamId() != null) {
            teamName = departmentRepository.findById(impEmp.getTeamId()).map(Department::getName).orElse(null);
        }
        LocalDate joiningDate = emp != null ? emp.getDateOfJoining() : null;

        // Prefer the roster's current-period shift/week off so the modal agrees with the
        // row that opened it; fall back to the employee master when the employee is not
        // on the current roster (only reachable for a non-roster id typed into the URL).
        String detailShift = impEmp.getDefaultShift();
        String detailWeekOff = impEmp.getWeekOff();
        String currentMonth = attendanceRosterService.resolveCurrentMonth();
        for (AttendanceRosterService.RosterEmployee r :
                attendanceRosterService.rosterEmployees(null, currentMonth, null, null, null)) {
            if (r.employeeCode().equals(employeeCode)) {
                if (r.shift() != null) {
                    detailShift = r.shift();
                }
                if (r.weekOff() != null) {
                    detailWeekOff = r.weekOff();
                }
                break;
            }
        }

        AttendanceAnalyticsDtos.EmployeeDetail detail = new AttendanceAnalyticsDtos.EmployeeDetail(
                employeeCode,
                impEmp.getEmail(),
                impEmp.getEmployeeName(),
                teamName,
                impEmp.getLocation(),
                detailShift,
                detailWeekOff,
                joiningDate,
                emp != null ? emp.getEmploymentStatus().name() : "UNKNOWN",
                totalWorkingDays,
                wfo, wfh, wo, pl, co, sl, hd, wkWrk, shrinkage,
                atr > 0 ? "Yes" : "No",
                totalLeaves,
                Math.round(attendancePct * 100.0) / 100.0,
                attendanceRecorded,
                monthlyBreakdown,
                history
        );

        return new AttendanceAnalyticsDtos.EmployeeDetailResponse(detail);
    }

    // ------------------------------------------------------------------ EMPLOYEE SUMMARY BUILDER

    private AttendanceAnalyticsDtos.EmployeeSummary buildEmployeeSummary(AttendanceRosterService.RosterEmployee rosterEmployee, LocalDate from, LocalDate to, AttendanceAnalyticsDtos.ReportMode mode, boolean inCurrentRoster) {
        String employeeCode = rosterEmployee.employeeCode();

        // Get all records for this employee within the date range (or all if null)
        List<AttendanceRecord> records;
        if (from != null && to != null) {
            records = attendanceRecordRepository.findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(employeeCode, from, to);
        } else {
            records = attendanceRecordRepository.findByEmployeeIdOrderByAttendanceDateAsc(employeeCode);
        }

        // Also get website attendance records
        List<Attendance> webRecords = new ArrayList<>();
        employeeRepository.findByEmployeeCodeIgnoreCase(employeeCode).ifPresent(emp ->
                webRecords.addAll(attendanceRepository.findByEmployeeIdOrderByAttendanceDateAsc(emp.getId()))
        );

        // Merge records - historical takes precedence, website fills gaps
        Map<LocalDate, String> statusByDate = new LinkedHashMap<>();

        for (AttendanceRecord r : records) {
            if (from == null || (!r.getAttendanceDate().isBefore(from) && !r.getAttendanceDate().isAfter(to))) {
                statusByDate.put(r.getAttendanceDate(), r.getStatusCode());
            }
        }

        // Add website records for dates not in historical records
        employeeRepository.findByEmployeeCodeIgnoreCase(employeeCode).ifPresent(emp ->
                attendanceRepository.findByEmployeeIdOrderByAttendanceDateAsc(emp.getId()).forEach(a -> {
                    LocalDate date = a.getAttendanceDate();
                    if ((from == null || (!date.isBefore(from) && !date.isAfter(to))) && !statusByDate.containsKey(date)) {
                        statusByDate.put(date, a.getAttendanceType().getShortLabel());
                    }
                })
        );

        // Calculate stats based on status codes
        long wfo = countStatus(statusByDate, WFO_CODES);
        long wfh = countStatus(statusByDate, WFH_CODES);
        long wo = countStatus(statusByDate, WO_CODES);
        long pl = countStatus(statusByDate, PL_CODES);
        long co = countStatus(statusByDate, CO_CODES);
        long sl = countStatus(statusByDate, SL_CODES);
        long hd = countStatus(statusByDate, HD_CODES);
        long hpeh = countStatus(statusByDate, HPEH_CODES);
        long fl = countStatus(statusByDate, FL_CODES);
        long atr = countStatus(statusByDate, ATR_CODES);

        // Calculate WK WRK (working days based on actual schedule)
        long wkWrk = statusByDate.values().stream().filter(this::isWorkingDay).count();

        // Total leaves = all leave codes
        long totalLeaves = statusByDate.values().stream().filter(s -> LEAVE_CODES_ALL.contains(s)).count();

        // Shrinkage = (Total Leaves / WK WRK) * 100
        double shrinkage = wkWrk > 0 ? ((double) totalLeaves / wkWrk) * 100.0 : 0.0;

        // ATR flag
        String atrFlag = atr > 0 ? "Yes" : "No";

        // Total working days = days with attendance codes (WFO, WFH, PL, SL, CO, HPEH)
        long totalWorkingDays = statusByDate.values().stream().filter(this::isWorkingDay).count();

        double attendancePct = wkWrk > 0 ? (double) (wfo + wfh) / wkWrk * 100.0 : 0.0;

        // Identity comes from the roster row, so Location / Shift / Week Off read exactly
        // as the roster grid shows them for this month rather than from the employee master.
        Employee emp = employeeRepository.findByEmployeeCodeIgnoreCase(employeeCode).orElse(null);
        ImportEmployee impEmp = importEmployeeRepository.findById(employeeCode).orElse(null);
        String employmentStatus = emp != null
                ? emp.getEmploymentStatus().name()
                : (impEmp != null && Boolean.TRUE.equals(impEmp.getActive()) ? "ACTIVE" : "INACTIVE");

        return new AttendanceAnalyticsDtos.EmployeeSummary(
                0L, // sNo is assigned by the caller, after ordering
                employeeCode,
                rosterEmployee.employeeName(),
                rosterEmployee.location(),
                rosterEmployee.shift(),
                rosterEmployee.weekOff(),
                inCurrentRoster,
                employmentStatus,
                wfo, wfh, wo, pl, co, sl, hd, wkWrk,
                shrinkage, atrFlag, totalWorkingDays, totalLeaves,
                Math.round(attendancePct * 100.0) / 100.0, statusByDate.size()
        );
    }

    private long countStatus(Map<LocalDate, String> statusByDate, Set<String> codes) {
        return statusByDate.values().stream().filter(codes::contains).count();
    }

    private boolean isWorkingDay(String status) {
        return status != null && (WFO_CODES.contains(status) || WFH_CODES.contains(status) || PL_CODES.contains(status)
                || SL_CODES.contains(status) || CO_CODES.contains(status) || HPEH_CODES.contains(status));
    }

    // ------------------------------------------------------------------ OVERALL SUMMARY CALCULATION

    private AttendanceAnalyticsDtos.EmployeeSummary calculateOverallSummary(List<AttendanceAnalyticsDtos.EmployeeSummary> summaries) {
        if (summaries.isEmpty()) {
            return new AttendanceAnalyticsDtos.EmployeeSummary(
                    0L, "TOTAL", "Overall Summary", "", "", "", false, "",
                    0, 0, 0, 0, 0, 0, 0, 0, 0.0, "No",
                    0, 0, 0.0, 0
            );
        }

        long totalWfo = summaries.stream().mapToLong(AttendanceAnalyticsDtos.EmployeeSummary::wfo).sum();
        long totalWfh = summaries.stream().mapToLong(AttendanceAnalyticsDtos.EmployeeSummary::wfh).sum();
        long totalWo = summaries.stream().mapToLong(AttendanceAnalyticsDtos.EmployeeSummary::wo).sum();
        long totalPl = summaries.stream().mapToLong(AttendanceAnalyticsDtos.EmployeeSummary::pl).sum();
        long totalCo = summaries.stream().mapToLong(AttendanceAnalyticsDtos.EmployeeSummary::co).sum();
        long totalSl = summaries.stream().mapToLong(AttendanceAnalyticsDtos.EmployeeSummary::sl).sum();
        long totalHd = summaries.stream().mapToLong(AttendanceAnalyticsDtos.EmployeeSummary::hd).sum();
        long totalWkWrk = summaries.stream().mapToLong(AttendanceAnalyticsDtos.EmployeeSummary::wkWrk).sum();
        long totalWorkingDays = summaries.stream().mapToLong(AttendanceAnalyticsDtos.EmployeeSummary::totalWorkingDays).sum();
        long totalLeaves = summaries.stream().mapToLong(AttendanceAnalyticsDtos.EmployeeSummary::totalLeaves).sum();
        long totalRecorded = summaries.stream().mapToLong(AttendanceAnalyticsDtos.EmployeeSummary::attendanceRecorded).sum();

        double overallShrinkage = totalLeaves > 0 && totalWkWrk > 0 ? ((double) totalLeaves / totalWkWrk) * 100.0 : 0.0;
        double overallAttendancePct = totalWorkingDays > 0 ? (double) (summaries.stream().mapToLong(e -> e.wfo() + e.wfh()).sum()) / totalWorkingDays * 100.0 : 0.0;

        // Determine if any employee has ATR
        String atrFlag = summaries.stream().anyMatch(e -> "Yes".equals(e.atr())) ? "Yes" : "No";

        return new AttendanceAnalyticsDtos.EmployeeSummary(
                0L, "TOTAL", "Overall Summary", "", "", "", false, "",
                totalWfo, totalWfh, totalWo, totalPl, totalCo, 0, totalHd, 0,
                overallShrinkage, "No",
                totalWorkingDays, totalLeaves,
                Math.round(overallAttendancePct * 100.0) / 100.0, totalRecorded
        );
    }

    // ------------------------------------------------------------------ HELPER METHODS

    private LocalDate getEarliestAttendanceDate() {
        List<LocalDate> dates = attendanceRecordRepository.findDistinctAttendanceDatesAsc();
        if (!dates.isEmpty()) return dates.get(0);
        List<LocalDate> webDates = attendanceRepository.findDistinctAttendanceDatesAsc();
        return webDates.isEmpty() ? null : webDates.get(0);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Comparator<AttendanceAnalyticsDtos.EmployeeSummary> comparatorFor(String sortBy) {
        Comparator<AttendanceAnalyticsDtos.EmployeeSummary> comparator = switch (sortBy.toLowerCase()) {
            case "employeeid" -> Comparator.comparing(AttendanceAnalyticsDtos.EmployeeSummary::employeeId,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "location" -> Comparator.comparing(AttendanceAnalyticsDtos.EmployeeSummary::location,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "shift" -> Comparator.comparing(AttendanceAnalyticsDtos.EmployeeSummary::shift,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "wfo" -> Comparator.comparingLong(AttendanceAnalyticsDtos.EmployeeSummary::wfo);
            case "wfh" -> Comparator.comparingLong(AttendanceAnalyticsDtos.EmployeeSummary::wfh);
            case "wo" -> Comparator.comparingLong(AttendanceAnalyticsDtos.EmployeeSummary::wo);
            case "pl" -> Comparator.comparingLong(AttendanceAnalyticsDtos.EmployeeSummary::pl);
            case "sl" -> Comparator.comparingLong(AttendanceAnalyticsDtos.EmployeeSummary::sl);
            case "co" -> Comparator.comparingLong(AttendanceAnalyticsDtos.EmployeeSummary::co);
            case "hd" -> Comparator.comparingLong(AttendanceAnalyticsDtos.EmployeeSummary::hd);
            case "totalleaves" -> Comparator.comparingLong(AttendanceAnalyticsDtos.EmployeeSummary::totalLeaves);
            case "totalworkingdays" -> Comparator.comparingLong(AttendanceAnalyticsDtos.EmployeeSummary::totalWorkingDays);
            case "attendancerecorded" -> Comparator.comparingLong(AttendanceAnalyticsDtos.EmployeeSummary::attendanceRecorded);
            case "attendancepercentage" -> Comparator.comparingDouble(AttendanceAnalyticsDtos.EmployeeSummary::attendancePercentage);
            case "shrinkage" -> Comparator.comparingDouble(AttendanceAnalyticsDtos.EmployeeSummary::shrinkage);
            default -> Comparator.comparing(AttendanceAnalyticsDtos.EmployeeSummary::employeeName,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        };
        return comparator;
    }

    // ------------------------------------------------------------------ EXPORT

    /**
     * The employee list the table shows, written out as CSV using the table's columns.
     *
     * <p>Export reuses {@link #getEmployeeStats} rather than a parallel query so a
     * downloaded file can never disagree with what the user was looking at. Pages are
     * walked in turn so a roster larger than one page still exports in full.</p>
     */
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> export(
            AttendanceAnalyticsDtos.ExportRequest request) {
        StringBuilder csv = new StringBuilder();
        csv.append("S.No,Employee ID,Employee Name,Location,Shift,Week Off,In Current Roster,")
                .append("WFO,WFH,WO,PL,CO,SL,HD,WK WRK,Shrinkage,ATR,Recorded\n");

        int page = 0;
        int totalPages;
        long sNo = 1;
        do {
            AttendanceAnalyticsDtos.AnalyticsQuery query = new AttendanceAnalyticsDtos.AnalyticsQuery(
                    request.month(), request.year(), request.mode(), request.teamId(), request.location(),
                    request.employeeId(), null, request.status(), page, 100, null, null);

            AttendanceAnalyticsDtos.EmployeeStatsResponse stats = getEmployeeStats(query);
            totalPages = stats.totalPages();

            for (AttendanceAnalyticsDtos.EmployeeSummary s : stats.employees()) {
                csv.append(sNo++).append(',')
                        .append(csv(s.employeeId())).append(',')
                        .append(csv(s.employeeName())).append(',')
                        .append(csv(s.location())).append(',')
                        .append(csv(s.shift())).append(',')
                        .append(csv(s.weekOff())).append(',')
                        .append(s.inCurrentRoster() ? "Yes" : "No").append(',')
                        .append(s.wfo()).append(',')
                        .append(s.wfh()).append(',')
                        .append(s.wo()).append(',')
                        .append(s.pl()).append(',')
                        .append(s.co()).append(',')
                        .append(s.sl()).append(',')
                        .append(s.hd()).append(',')
                        .append(s.wkWrk()).append(',')
                        .append(s.shrinkage()).append(',')
                        .append(s.atr()).append(',')
                        .append(s.attendanceRecorded())
                        .append('\n');
            }
            page++;
        } while (page < totalPages);

        byte[] bytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        org.springframework.core.io.Resource resource =
                new org.springframework.core.io.InputStreamResource(new ByteArrayInputStream(bytes));
        String filename = "attendance-analytics-" + System.currentTimeMillis() + ".csv";

        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv"))
                .contentLength(bytes.length)
                .body(resource);
    }

    /** Escapes a value for CSV: blank when absent, quoted when it contains a delimiter. */
    private static String csv(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}