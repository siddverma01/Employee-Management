package com.emplmgt.service;

import com.emplmgt.dto.AttendanceHistoryDtos;
import com.emplmgt.dto.AttendanceRosterDtos;
import com.emplmgt.entity.AttendanceStatus;
import com.emplmgt.entity.Department;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.AttendanceStatusRepository;
import com.emplmgt.repository.DepartmentRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Admin Attendance History search/analysis over every imported
 * {@code attendance_records} row joined to {@code import_employees} + team.
 * All filtering, sorting, pagination, summary counts and export generation run
 * server-side so browsing years of history never loads the full set into the
 * UI. Query construction is kept in pure static helpers so the SQL shape is
 * unit-testable without a running database.
 */
@Service
@RequiredArgsConstructor
public class AttendanceHistoryService {

    public static final int MAX_PAGE_SIZE = 100;
    private static final int EXPORT_BATCH = 1000;

    private static final String FROM_JPQL = """
            from AttendanceRecord r
            left join ImportEmployee e on e.employeeId = r.employeeId
            left join Department d on d.id = e.teamId
            """;

    private static final String SELECT_JPQL = """
            select r.attendanceDate, r.employeeId, e.employeeName, e.teamId, d.name,
                   coalesce(r.location, e.location), coalesce(r.shift, e.defaultShift),
                   r.statusCode, r.statusName, r.sourceSheet, r.sourceFile, r.sourceRow,
                   r.importedAt, r.isUnknown
            """ + FROM_JPQL;

    private static final String COUNT_JPQL = "select count(r)\n" + FROM_JPQL;
    private static final String SUMMARY_JPQL = "select r.statusCode, count(r)\n" + FROM_JPQL;
    private static final String EXPORT_ORDER = " order by r.attendanceDate asc, r.employeeId asc";

    private static final String[] EXPORT_COLUMNS = {
            "Date", "Employee ID", "Employee Name", "Team", "Location", "Shift", "Status",
            "Source Month", "Source Sheet"};

    private static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("date", "r.attendanceDate"),
            Map.entry("employeeid", "r.employeeId"),
            Map.entry("employeename", "e.employeeName"),
            Map.entry("teamname", "d.name"),
            Map.entry("location", "coalesce(r.location, e.location)"),
            Map.entry("shift", "coalesce(r.shift, e.defaultShift)"),
            Map.entry("status", "r.statusCode"),
            Map.entry("sourcesheet", "r.sourceSheet"),
            Map.entry("sourcefile", "r.sourceFile"),
            Map.entry("sourcemonth", "r.attendanceDate"),
            Map.entry("importedat", "r.importedAt"));

    private final EntityManager entityManager;
    private final ImportEmployeeRepository importEmployeeRepository;
    private final DepartmentRepository departmentRepository;
    private final AttendanceStatusRepository statusRepository;
    private final AttendanceRecordRepository recordRepository;

    // ------------------------------------------------------------------ SEARCH

    @Transactional(readOnly = true)
    public AttendanceHistoryDtos.SearchResponse search(AttendanceHistoryDtos.HistoryQuery query) {
        Resolved r = resolve(query);
        Parts p = buildParts(r);

        TypedQuery<Object[]> tq = entityManager.createQuery(SELECT_JPQL + p.where() + orderBy(r), Object[].class);
        bind(tq, p);
        tq.setFirstResult(r.page() * r.size());
        tq.setMaxResults(r.size());
        List<Object[]> rows = tq.getResultList();

        long total = count(r);
        List<Object[]> grouped = summaryRows(r);
        int totalPages = r.size() <= 0 ? 0 : (int) ((total + r.size() - 1) / r.size());

        return new AttendanceHistoryDtos.SearchResponse(
                mapRows(rows), toSummary(total, grouped), r.page(), r.size(), total, totalPages);
    }

    private long count(Resolved r) {
        Parts p = buildParts(r);
        TypedQuery<Long> tq = entityManager.createQuery(COUNT_JPQL + p.where(), Long.class);
        bind(tq, p);
        Number n = tq.getSingleResult();
        return n == null ? 0L : n.longValue();
    }

    private List<Object[]> summaryRows(Resolved r) {
        Parts p = buildParts(r);
        TypedQuery<Object[]> tq = entityManager.createQuery(
                SUMMARY_JPQL + p.where() + " group by r.statusCode order by r.statusCode asc", Object[].class);
        bind(tq, p);
        return tq.getResultList();
    }

    // ------------------------------------------------------------------ META

    @Transactional(readOnly = true)
    public AttendanceHistoryDtos.Meta meta() {
        List<AttendanceRosterDtos.TeamOption> teams = teams();
        List<LocalDate> dates = recordRepository.findDistinctAttendanceDatesAsc();

        Set<YearMonth> monthsSeen = new LinkedHashSet<>();
        Set<Integer> yearsSeen = new LinkedHashSet<>();
        for (LocalDate d : dates) {
            monthsSeen.add(YearMonth.from(d));
            yearsSeen.add(d.getYear());
        }

        List<String> months = monthsSeen.stream().sorted()
                .map(YearMonth::toString).toList();
        List<String> years = yearsSeen.stream().sorted()
                .map(String::valueOf).toList();

        List<String> locations = mergeDistinct(
                recordRepository.findDistinctLocations(), importEmployeeRepository.findDistinctLocations());
        List<String> shifts = mergeDistinct(
                recordRepository.findDistinctShifts(), importEmployeeRepository.findDistinctShifts());

        List<AttendanceRosterDtos.StatusOption> statuses = statusRepository.findAllByOrderByCodeAsc().stream()
                .map(s -> new AttendanceRosterDtos.StatusOption(s.getCode(), s.getName(), s.getDisplayColor()))
                .toList();

        return new AttendanceHistoryDtos.Meta(teams, months, years, locations, shifts, statuses);
    }

    private List<AttendanceRosterDtos.TeamOption> teams() {
        List<Long> teamIds = importEmployeeRepository.findDistinctTeamIds();
        if (teamIds.isEmpty()) {
            return List.of();
        }
        return departmentRepository.findAllById(teamIds).stream()
                .sorted(Comparator.comparing(Department::getName, String.CASE_INSENSITIVE_ORDER))
                .map(d -> new AttendanceRosterDtos.TeamOption(d.getId(), d.getName()))
                .toList();
    }

    private static List<String> mergeDistinct(List<String> a, List<String> b) {
        TreeSet<String> merged = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        merged.addAll(a);
        merged.addAll(b);
        return new ArrayList<>(merged);
    }

    // ------------------------------------------------------------------ EXPORT

    @Transactional(readOnly = true)
    public void export(OutputStream out, String format, AttendanceHistoryDtos.HistoryQuery query) throws IOException {
        Resolved r = resolve(query);
        switch (format == null ? "csv" : format.toLowerCase(Locale.ROOT)) {
            case "csv" -> writeCsv(out, r);
            case "xlsx", "excel" -> writeExcel(out, r);
            default -> throw ApiException.badRequest("Unsupported export format '" + format + "'");
        }
    }

    private void writeCsv(OutputStream out, Resolved r) throws IOException {
        Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        w.write(String.join(",", EXPORT_COLUMNS));
        w.write('\n');
        long offset = 0;
        while (true) {
            List<Object[]> batch = rows(r, offset, EXPORT_BATCH);
            for (Object[] row : batch) {
                w.write(csv(text(row, 0)).concat(",")
                        .concat(csv(text(row, 1))).concat(",")
                        .concat(csv(text(row, 2))).concat(",")
                        .concat(csv(text(row, 3))).concat(",")
                        .concat(csv(text(row, 4))).concat(",")
                        .concat(csv(text(row, 5))).concat(",")
                        .concat(csv(text(row, 6))).concat(",")
                        .concat(csv(text(row, 7))).concat(",")
                        .concat(csv(text(row, 8))).concat("\n"));
            }
            offset += batch.size();
            if (batch.size() < EXPORT_BATCH) {
                break;
            }
        }
        w.flush();
    }

    private void writeExcel(OutputStream out, Resolved r) throws IOException {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(100)) {
            Sheet sheet = wb.createSheet("Attendance History");
            Font headerFont = wb.createFont();
            headerFont.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(headerFont);

            Row header = sheet.createRow(0);
            for (int i = 0; i < EXPORT_COLUMNS.length; i++) {
                Cell c = header.createCell(i);
                c.setCellValue(EXPORT_COLUMNS[i]);
                c.setCellStyle(headerStyle);
            }

            long offset = 0;
            int rowIdx = 1;
            while (true) {
                List<Object[]> batch = rows(r, offset, EXPORT_BATCH);
                for (Object[] row : batch) {
                    Row xr = sheet.createRow(rowIdx++);
                    for (int i = 0; i < EXPORT_COLUMNS.length; i++) {
                        xr.createCell(i).setCellValue(text(row, i));
                    }
                }
                offset += batch.size();
                if (batch.size() < EXPORT_BATCH) {
                    break;
                }
            }
            wb.write(out);
        }
    }

    private List<Object[]> rows(Resolved r, long offset, int limit) {
        Parts p = buildParts(r);
        TypedQuery<Object[]> tq = entityManager.createQuery(SELECT_JPQL + p.where() + EXPORT_ORDER, Object[].class);
        bind(tq, p);
        tq.setFirstResult((int) offset);
        tq.setMaxResults(limit);
        return tq.getResultList();
    }

    // ------------------------------------------------------------------ query building (pure, unit-testable)

    static Resolved resolve(AttendanceHistoryDtos.HistoryQuery q) {
        LocalDate from = null;
        LocalDate to = null;
        if (notBlank(q.dateFrom())) {
            from = parseDate("dateFrom", q.dateFrom());
        }
        if (notBlank(q.dateTo())) {
            to = parseDate("dateTo", q.dateTo());
        }
        if (notBlank(q.month())) {
            YearMonth ym = parseMonth(q.month());
            from = maxOrNull(from, ym.atDay(1));
            to = minOrNull(to, ym.atEndOfMonth());
        }
        if (notBlank(q.year())) {
            int year = parseYear(q.year());
            from = maxOrNull(from, LocalDate.of(year, 1, 1));
            to = minOrNull(to, LocalDate.of(year, 12, 31));
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.badRequest("The selected date range is empty (from is after to)");
        }
        int page = Math.max(0, q.page());
        int size = Math.max(1, Math.min(q.size(), MAX_PAGE_SIZE));
        return new Resolved(from, to, trim(q.employeeName()), trim(q.employeeId()), q.teamId(),
                trim(q.location()), trim(q.shift()), trim(q.status()), trim(q.q()),
                page, size, trim(q.sortBy()), trim(q.sortDir()));
    }

    static Parts buildParts(Resolved r) {
        List<String> conds = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();

        if (r.from() != null) {
            conds.add("r.attendanceDate >= :attendanceFrom");
            params.put("attendanceFrom", r.from());
        }
        if (r.to() != null) {
            conds.add("r.attendanceDate <= :attendanceTo");
            params.put("attendanceTo", r.to());
        }
        String namePat = like(r.employeeName());
        if (namePat != null) {
            conds.add("lower(e.employeeName) like :employeeNamePat");
            params.put("employeeNamePat", namePat);
        }
        String idPat = like(r.employeeId());
        if (idPat != null) {
            conds.add("lower(r.employeeId) like :employeeIdPat");
            params.put("employeeIdPat", idPat);
        }
        if (r.teamId() != null) {
            conds.add("e.teamId = :teamId");
            params.put("teamId", r.teamId());
        }
        if (r.location() != null) {
            conds.add("coalesce(r.location, e.location) = :location");
            params.put("location", r.location());
        }
        if (r.shift() != null) {
            conds.add("coalesce(r.shift, e.defaultShift) = :shift");
            params.put("shift", r.shift());
        }
        if (r.status() != null) {
            conds.add("r.statusCode = :status");
            params.put("status", r.status());
        }
        String qPat = like(r.q());
        if (qPat != null) {
            conds.add("(lower(coalesce(e.employeeName, '')) like :qPat"
                    + " or lower(r.employeeId) like :qPat"
                    + " or lower(coalesce(r.statusName, '')) like :qPat"
                    + " or lower(coalesce(r.statusCode, '')) like :qPat"
                    + " or lower(coalesce(r.sourceSheet, '')) like :qPat"
                    + " or lower(coalesce(r.sourceFile, '')) like :qPat)");
            params.put("qPat", qPat);
        }
        if (conds.isEmpty()) {
            return new Parts("", params);
        }
        return new Parts(" where " + String.join(" and ", conds), params);
    }

    static String orderBy(Resolved r) {
        String key = r.sortBy() == null ? "date" : r.sortBy().toLowerCase(Locale.ROOT);
        String column = SORT_COLUMNS.getOrDefault(key, "r.attendanceDate");
        String dir = "desc".equalsIgnoreCase(r.sortDir()) ? "desc" : "asc";
        return " order by " + column + " " + dir + ", r.attendanceDate asc, r.employeeId asc";
    }

    static AttendanceHistoryDtos.Summary toSummary(long total, List<Object[]> grouped) {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        grouped.forEach(row -> byStatus.put((String) row[0], (Long) row[1]));
        List<Map.Entry<String, Long>> entries = new ArrayList<>(byStatus.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()));
        LinkedHashMap<String, Long> sorted = new LinkedHashMap<>();
        entries.forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return new AttendanceHistoryDtos.Summary(total, sorted);
    }

    static List<AttendanceHistoryDtos.RecordView> mapRows(List<Object[]> rows) {
        List<AttendanceHistoryDtos.RecordView> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            LocalDate d = (LocalDate) row[0];
            out.add(new AttendanceHistoryDtos.RecordView(
                    d == null ? null : d.toString(),
                    (String) row[1],
                    (String) row[2],
                    (Long) row[3],
                    (String) row[4],
                    (String) row[5],
                    (String) row[6],
                    (String) row[7],
                    (String) row[8],
                    d == null ? null : YearMonth.from(d).toString(),
                    (String) row[9],
                    (String) row[10],
                    (Integer) row[11],
                    row[12] == null ? null : row[12].toString(),
                    Boolean.TRUE.equals(row[13])));
        }
        return out;
    }

    private static String text(Object[] row, int i) {
        Object v = i < row.length ? row[i] : null;
        return v == null ? "" : v.toString();
    }

    /** Escapes a single CSV field (mirrors {@code HistoricalImportService}). */
    static String csv(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\"", "\"\"");
        return v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0
                ? "\"" + v + "\"" : v;
    }

    private static void bind(TypedQuery<?> tq, Parts p) {
        p.params().forEach(tq::setParameter);
    }

    private static String like(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return "%" + raw.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private static LocalDate parseDate(String name, String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Invalid " + name + " '" + value + "'. Expected yyyy-MM-dd");
        }
    }

    private static YearMonth parseMonth(String value) {
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Invalid month '" + value + "'. Expected yyyy-MM");
        }
    }

    private static int parseYear(String value) {
        try {
            int year = Integer.parseInt(value.trim());
            if (year < 1900 || year > 9999) {
                throw ApiException.badRequest("Invalid year '" + value + "'. Expected yyyy");
            }
            return year;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Invalid year '" + value + "'. Expected yyyy");
        }
    }

    private static LocalDate maxOrNull(LocalDate current, LocalDate candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private static LocalDate minOrNull(LocalDate current, LocalDate candidate) {
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trim(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Normalised filter + pagination after resolve(); drives query building. */
    record Resolved(LocalDate from, LocalDate to, String employeeName, String employeeId,
                    Long teamId, String location, String shift, String status, String q,
                    int page, int size, String sortBy, String sortDir) {
    }

    /** WHERE fragment plus the named parameters it introduces. */
    record Parts(String where, Map<String, Object> params) {
    }
}