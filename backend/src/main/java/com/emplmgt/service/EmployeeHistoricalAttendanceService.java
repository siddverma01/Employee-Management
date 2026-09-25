package com.emplmgt.service;

import com.emplmgt.dto.EmployeeHistoricalAttendanceDtos;
import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.ImportEmployee;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Historical attendance aggregates for one employee's admin profile. Reads
 * only normalised {@code attendance_records} (+ {@code import_employees}); no
 * workbook parsing client-side. Builders are static and pure so the
 * aggregation rules are unit-testable without a database.
 */
@Service
@RequiredArgsConstructor
public class EmployeeHistoricalAttendanceService {

    /** Codes surfaced individually in the profile overview; the rest roll into "OTHER". */
    public static final List<String> OVERVIEW_CODES = List.of("WFO", "WFH", "WO", "PL", "SL", "CO", "HPEH", "FL", "HD");
    /** Codes surfaced individually in each monthly row; the rest roll into "OTHER". */
    public static final List<String> MONTH_CODES = List.of("WFO", "WFH", "WO", "PL", "SL", "CO");
    public static final String OTHER = "OTHER";

    private final EmployeeRepository employeeRepository;
    private final ImportEmployeeRepository importEmployeeRepository;
    private final AttendanceRecordRepository recordRepository;

    @Transactional(readOnly = true)
    public EmployeeHistoricalAttendanceDtos.ProfileResponse profile(Long employeeId, String month) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> ApiException.notFound("Employee not found"));
        String employeeCode = employee.getEmployeeCode();

        List<Object[]> grouped = recordRepository.countByDateAndStatusCode(employeeCode);
        if (grouped.isEmpty()) {
            return new EmployeeHistoricalAttendanceDtos.ProfileResponse(
                    false, employeeCode, null, null, List.of(), List.of(), null);
        }

        String employeeName = importEmployeeRepository.findById(employeeCode)
                .map(ImportEmployee::getEmployeeName)
                .orElse(employee.getFullName());

        EmployeeHistoricalAttendanceDtos.Overview overview = overview(grouped);
        List<String> months = months(grouped);
        List<EmployeeHistoricalAttendanceDtos.MonthStat> monthly = monthly(grouped, months);
        String calendarMonth = resolveCalendarMonth(month, months);
        EmployeeHistoricalAttendanceDtos.Calendar calendar = calendar(employeeCode, calendarMonth);

        return new EmployeeHistoricalAttendanceDtos.ProfileResponse(
                true, employeeCode, employeeName, overview, months, monthly, calendar);
    }

    // ------------------------------------------------------------------ aggregation (pure)

    static List<LocalDate> distinctDates(List<Object[]> grouped) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        grouped.forEach(row -> dates.add((LocalDate) row[0]));
        return dates.stream().sorted().toList();
    }

    static EmployeeHistoricalAttendanceDtos.Overview overview(List<Object[]> grouped) {
        List<LocalDate> dates = distinctDates(grouped);
        Map<String, Long> raw = rawCounts(grouped);
        return new EmployeeHistoricalAttendanceDtos.Overview(
                dates.isEmpty() ? null : dates.get(0).toString(), dates.size(),
                withOther(raw, dates.size(), OVERVIEW_CODES));
    }

    static List<String> months(List<Object[]> grouped) {
        return distinctDates(grouped).stream()
                .map(d -> YearMonth.from(d).toString())
                .distinct()
                .sorted()
                .toList();
    }

    static List<EmployeeHistoricalAttendanceDtos.MonthStat> monthly(List<Object[]> grouped, List<String> months) {
        Map<String, Map<String, Long>> perMonth = new LinkedHashMap<>();
        for (Object[] row : grouped) {
            String ym = YearMonth.from((LocalDate) row[0]).toString();
            perMonth.computeIfAbsent(ym, k -> new LinkedHashMap<>())
                    .merge((String) row[1], ((Number) row[2]).longValue(), Long::sum);
        }
        List<EmployeeHistoricalAttendanceDtos.MonthStat> out = new ArrayList<>(months.size());
        for (String m : months) {
            Map<String, Long> raw = perMonth.getOrDefault(m, Map.of());
            long total = raw.values().stream().mapToLong(Long::longValue).sum();
            out.add(new EmployeeHistoricalAttendanceDtos.MonthStat(m, withOther(raw, total, MONTH_CODES)));
        }
        return out;
    }

    static Map<String, Long> withOther(Map<String, Long> raw, long total, List<String> codes) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String c : codes) {
            out.put(c, raw.getOrDefault(c, 0L));
        }
        long counted = out.values().stream().mapToLong(Long::longValue).sum();
        out.put(OTHER, Math.max(0L, total - counted));
        return out;
    }

    // ------------------------------------------------------------------ calendar

    EmployeeHistoricalAttendanceDtos.Calendar calendar(String employeeCode, String month) {
        YearMonth ym = parseMonth(month);
        List<AttendanceRecord> records = recordRepository
                .findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                        employeeCode, ym.atDay(1), ym.atEndOfMonth());
        Map<LocalDate, AttendanceRecord> byDate = records.stream().collect(Collectors.toMap(
                AttendanceRecord::getAttendanceDate, Function.identity(), (a, b) -> a));

        List<EmployeeHistoricalAttendanceDtos.CalendarDay> days = new ArrayList<>();
        for (int d = 1; d <= ym.lengthOfMonth(); d++) {
            LocalDate date = ym.atDay(d);
            AttendanceRecord rec = byDate.get(date);
            DayOfWeek dow = date.getDayOfWeek();
            boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            days.add(new EmployeeHistoricalAttendanceDtos.CalendarDay(
                    date.toString(),
                    rec == null ? null : rec.getStatusCode(),
                    rec == null ? null : rec.getStatusName(),
                    rec != null && Boolean.TRUE.equals(rec.getIsUnknown()),
                    weekend));
        }
        return new EmployeeHistoricalAttendanceDtos.Calendar(ym.toString(), days);
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Long> rawCounts(List<Object[]> grouped) {
        Map<String, Long> raw = new LinkedHashMap<>();
        for (Object[] row : grouped) {
            raw.merge((String) row[1], ((Number) row[2]).longValue(), Long::sum);
        }
        return raw;
    }

    private static String resolveCalendarMonth(String month, List<String> months) {
        if (month != null && !month.isBlank()) {
            return parseMonth(month.trim()).toString();
        }
        return months.get(months.size() - 1);
    }

    private static YearMonth parseMonth(String value) {
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Invalid month '" + value + "'. Expected yyyy-MM");
        }
    }
}