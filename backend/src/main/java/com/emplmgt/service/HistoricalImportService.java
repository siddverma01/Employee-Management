package com.emplmgt.service;

import com.emplmgt.dto.HistoricalImportDtos;
import com.emplmgt.entity.AttendanceImportHistory;
import com.emplmgt.entity.AttendanceImportRow;
import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.AttendanceShiftAssignment;
import com.emplmgt.entity.AttendanceWeekOffAssignment;
import com.emplmgt.entity.AttendanceStatus;
import com.emplmgt.entity.ImportEmployee;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.AttendanceImportHistoryRepository;
import com.emplmgt.repository.AttendanceImportRowRepository;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.AttendanceShiftAssignmentRepository;
import com.emplmgt.repository.AttendanceWeekOffAssignmentRepository;
import com.emplmgt.repository.AttendanceStatusRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.HistoricalImportCodes;
import com.emplmgt.util.HistoricalRosterParser;
import com.emplmgt.util.ShiftTime;
import com.emplmgt.util.WeekOffUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class HistoricalImportService {

    private static final List<String> COMMITTABLE = List.of("INSERT", "UPDATE");

    private static final String ERR = "ERROR";
    private static final String WARN = "WARNING";

    private static final String T_READABLE = "UNREADABLE_SHEET";
    private static final String T_NOT_ROSTER = "NOT_AN_ATTENDANCE_ROSTER";
    private static final String T_NO_EMP = "NO_EMPLOYEE_ID";
    private static final String T_NO_DATES = "NO_DATE_COLUMNS";
    private static final String T_NO_MONTH = "NO_MONTH";
    private static final String T_NO_ROWS = "NO_DATA_ROWS";
    private static final String T_INVALID_DATE = "INVALID_DATE";
    private static final String T_MISSING_EMAIL = "MISSING_EMAIL";
    private static final String T_MISSING_MANAGER = "MISSING_MANAGER";
    private static final String T_UNKNOWN_CODE = "UNKNOWN_CODE";
    private static final String T_DUP_EMP = "DUPLICATE_EMPLOYEE_ID";
    private static final String T_INCONSISTENT_NAME = "INCONSISTENT_NAME";
    private static final String T_SHIFT_MISSING = "MISSING_SHIFT";
    private static final String T_SHIFT_INVALID = "UNPARSED_SHIFT";
    private static final String T_WEEK_OFF_MISSING = "MISSING_WEEK_OFF";
    private static final String T_WEEK_OFF_INVALID = "UNPARSED_WEEK_OFF";

    private static final DateTimeFormatter MONTH_FMT =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final AttendanceStatusRepository statusRepository;
    private final ImportEmployeeRepository importEmployeeRepository;
    private final AttendanceRecordRepository recordRepository;
    private final AttendanceShiftAssignmentRepository shiftAssignmentRepository;
    private final AttendanceWeekOffAssignmentRepository weekOffAssignmentRepository;
    private final WeekOffUtil weekOffUtil;
    private final AttendanceImportHistoryRepository historyRepository;
    private final AttendanceImportRowRepository rowRepository;
    private final AuditService auditService;
    private final AppClock appClock;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    @Value("${application.import.upload-dir:./data/uploads}")
    private String uploadDir;

    /** Persisted per-import wizard state: per-sheet analysis, issues, unknown codes. */
    private record Snapshot(List<HistoricalImportDtos.SheetAnalysis> analysis,
                            List<HistoricalImportDtos.ValidationIssue> issues,
                            List<HistoricalImportDtos.UnknownCodeDetail> unknownCodes) {
    }

    // ------------------------------------------------------------------ INSPECT

    @Transactional(readOnly = true)
    public HistoricalImportDtos.InspectResponse inspect(MultipartFile file) {
        String original = file.getOriginalFilename() == null ? "unknown.xlsx" : file.getOriginalFilename();
        if (!isExcel(original)) {
            throw ApiException.badRequest("Only .xlsx and .xls files are supported");
        }
        if (file.getSize() == 0) {
            throw ApiException.badRequest("Uploaded file is empty");
        }
        List<String> sheets = new ArrayList<>();
        try (InputStream in = file.getInputStream(); Workbook wb = openWorkbook(in, original)) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                sheets.add(wb.getSheetAt(i).getSheetName());
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Failed to read the uploaded file: " + e.getMessage());
        }
        return new HistoricalImportDtos.InspectResponse(original, file.getSize(), sheets.size(), sheets);
    }

    // ------------------------------------------------------------------ PREVIEW

    @Transactional
    public HistoricalImportDtos.PreviewResponse preview(MultipartFile file, Long adminUserId) {
        String original = file.getOriginalFilename() == null ? "unknown.xlsx" : file.getOriginalFilename();
        if (!isExcel(original)) {
            throw ApiException.badRequest("Only .xlsx and .xls files are supported");
        }
        if (file.getSize() == 0) {
            throw ApiException.badRequest("Uploaded file is empty");
        }

        HistoricalRosterParser.ParsedWorkbook parsed;
        try (InputStream in = file.getInputStream(); Workbook wb = openWorkbook(in, original)) {
            parsed = HistoricalRosterParser.parse(wb);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badRequest("Failed to read the uploaded file: " + e.getMessage());
        }

        if (parsed.recordCount() == 0L) {
            StringBuilder why = new StringBuilder("No attendance records could be detected in this workbook.");
            int shown = 0;
            for (HistoricalRosterParser.SheetResult s : parsed.sheets()) {
                if (shown++ < 8) {
                    why.append(" [").append(s.sheetName()).append(": ").append(s.skipReason()).append("]");
                }
            }
            parsed.globalWarnings().forEach(w -> why.append(" ").append(w));
            throw ApiException.badRequest(why.toString());
        }

        List<HistoricalRosterParser.ParsedRecord> all = flatten(parsed.sheets());
        Map<String, AttendanceRecord> existing = findExistingRecords(all);

        String sourceFile = store(file);
        AttendanceImportHistory history = AttendanceImportHistory.builder()
                .fileName(sourceFile)
                .originalFileName(original)
                .importedAt(appClock.now())
                .importedBy(adminUserId == null ? null : String.valueOf(adminUserId))
                .status(AttendanceImportHistory.ImportStatus.DRAFT.name())
                .totalSheets(parsed.totalSheets())
                .sheetsImported(parsed.totalSheets() - (int) parsed.sheetsSkipped())
                .sheetsSkipped((int) parsed.sheetsSkipped())
                .employeesDetected(distinctEmployeeCount(all))
                .recordsDetected(all.size())
                .build();
        historyRepository.save(history);

        List<AttendanceImportRow> staged = populateStaged(history, parsed.sheets(), existing);

        // Analysis + validation + unknown codes are derived once and persisted
        // so any later page load / mapping refresh shows the same snapshot.
        List<HistoricalImportDtos.SheetAnalysis> analysis = buildAnalysis(parsed.sheets());
        List<HistoricalImportDtos.ValidationIssue> issues = mergeIssues(buildIssues(parsed.sheets(), staged));
        markImportable(analysis, issues);
        List<HistoricalImportDtos.UnknownCodeDetail> unknown = buildUnknownCodes(parsed);

        persistSnapshot(history, analysis, issues, unknown);
        summarizeAndHold(history, parsed, staged, issues);
        historyRepository.save(history);

        return new HistoricalImportDtos.PreviewResponse(history.getId(), sourceFile, original,
                history.getStatus(), toSummary(history, analysis), staged.size(), 0, 50,
                toRowViews(firstPage(staged, 50)), analysis, issues, unknown,
                buildUnknownValues(parsed));
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isExcel(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    private static Workbook openWorkbook(InputStream in, String original) throws IOException {
        String lower = original.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xls") ? new HSSFWorkbook(in) : new XSSFWorkbook(in);
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String store(MultipartFile file) {
        String stored = UUID.randomUUID() + "-" + sanitize(file.getOriginalFilename());
        String base = (uploadDir == null || uploadDir.isBlank()) ? "./data/uploads" : uploadDir;
        try {
            Path dir = Files.createDirectories(Paths.get(base).resolve("historical"));
            Files.copy(file.getInputStream(), dir.resolve(stored), StandardCopyOption.REPLACE_EXISTING);
            return "historical/" + stored;
        } catch (IOException e) {
            throw ApiException.badRequest("Failed to store uploaded file: " + e.getMessage());
        }
    }

    private static List<HistoricalRosterParser.ParsedRecord> flatten(
            List<HistoricalRosterParser.SheetResult> sheets) {
        List<HistoricalRosterParser.ParsedRecord> out = new ArrayList<>();
        sheets.stream().filter(s -> !s.skipped()).forEach(s -> out.addAll(s.records()));
        return out;
    }

    private static int distinctEmployeeCount(List<HistoricalRosterParser.ParsedRecord> all) {
        return all.stream().map(HistoricalRosterParser.ParsedRecord::employeeId)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)).size();
    }

    private static String key(String employeeId, LocalDate date) {
        return employeeId + "|" + date;
    }

    private Map<String, AttendanceRecord> findExistingRecords(
            List<HistoricalRosterParser.ParsedRecord> all) {
        LocalDate min = all.stream().map(p -> p.attendanceDate())
                .filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        LocalDate max = all.stream().map(p -> p.attendanceDate())
                .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        Map<String, AttendanceRecord> existing = new HashMap<>();
        if (min != null && max != null) {
            recordRepository.findByAttendanceDateBetweenOrderByAttendanceDateAsc(min, max)
                    .forEach(r -> existing.putIfAbsent(key(r.getEmployeeId(), r.getAttendanceDate()), r));
        }
        return existing;
    }

    private List<AttendanceImportRow> populateStaged(AttendanceImportHistory history,
                                                     List<HistoricalRosterParser.SheetResult> sheets,
                                                     Map<String, AttendanceRecord> existing) {
        List<AttendanceImportRow> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (HistoricalRosterParser.SheetResult sheet : sheets) {
            if (sheet.skipped()) {
                continue;
            }
            for (HistoricalRosterParser.ParsedRecord rec : sheet.records()) {
                AttendanceImportRow r = AttendanceImportRow.builder()
                        .importHistory(history)
                        .sheetName(sheet.sheetName())
                        .sourceRow(rec.sourceRow())
                        .sourceColumn(rec.sourceColumn())
                        .employeeId(rec.employeeId())
                        .employeeName(rec.employeeName())
                        .employeeEmail(rec.email())
                        .employeeLocation(rec.location())
                        .employeeManager(rec.manager())
                        .employeeShift(rec.shift())
                        .employeeWeekOff(rec.weekOff())
                        .attendanceDate(rec.attendanceDate())
                        .incomingStatus(rec.statusCode())
                        .statusName(rec.statusName())
                        .isUnknown(rec.unknown())
                        .warning(rec.warning())
                        .originalStatus(blankToNull(rec.rawCode()))
                        .description(blankToNull(rec.description()))
                        .descriptionSource(blankToNull(rec.descriptionSource()))
                        .descriptionAuthor(blankToNull(rec.descriptionAuthor()))
                        .descriptionAt(rec.descriptionAt())
                        .build();
                r.setIssue(unresolvedReason(rec.statusCode(), rec.unknown(), rec.warning()));
                if (rec.attendanceDate() == null) {
                    r.setAction("INVALID");
                } else {
                    String k = key(rec.employeeId(), rec.attendanceDate());
                    if (!seen.add(k)) {
                        r.setAction("DUPLICATE");
                    } else {
                        AttendanceRecord ex = existing.get(k);
                        if (ex != null) {
                            r.setAction("UPDATE");
                            r.setExistingStatus(ex.getStatusCode());
                        } else {
                            r.setAction("INSERT");
                        }
                    }
                }
                rows.add(r);
            }
        }
        rowRepository.saveAll(rows);
        return rows;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /**
     * Why a staged cell still needs the admin's attention, or {@code null} when it
     * is already a recognised status. A blank is treated as unresolved rather than
     * as "nothing to import": the cell exists in the workbook, so silently dropping
     * it would quietly turn a gap in the source data into a missing attendance
     * record.
     */
    private static String unresolvedReason(String statusCode, boolean unknown, String warning) {
        if (statusCode == null || statusCode.isBlank()) {
            return "Blank status — the cell has no value";
        }
        if (unknown) {
            return "Unrecognised status '" + statusCode + "'";
        }
        return blankToNull(warning);
    }

    /** True while the row still needs a correction or an explicit skip. */
    private static boolean needsResolution(AttendanceImportRow r) {
        return r.getIssue() != null
                && !Boolean.TRUE.equals(r.getCorrected())
                && !Boolean.TRUE.equals(r.getSkipped());
    }

    /** Last-resort text when a sheet was skipped without a parser diagnostic. */
    private static String messageFallback(String type) {
        return switch (type) {
            case T_NO_DATES -> "The header row was found but it has no date or day-number columns.";
            case T_NO_MONTH -> "The sheet has day-number columns but no month/year could be determined.";
            case T_NO_EMP -> "Rows were found but none carries an employee id.";
            case T_NO_ROWS -> "The header was found but no employee rows carry attendance data.";
            case T_NOT_ROSTER -> "The sheet holds no attendance roster.";
            default -> "The sheet could not be read as an attendance roster.";
        };
    }

    // -------------------------------------------------- analysis & validation

    private static List<HistoricalImportDtos.SheetAnalysis> buildAnalysis(
            List<HistoricalRosterParser.SheetResult> sheets) {
        List<HistoricalImportDtos.SheetAnalysis> out = new ArrayList<>();
        for (HistoricalRosterParser.SheetResult s : sheets) {
            out.add(new HistoricalImportDtos.SheetAnalysis(
                    s.sheetName(),
                    s.month() == null ? null : s.month().format(MONTH_FMT),
                    s.headerRow(),
                    s.employeeColumnCount(),
                    s.dateColumnCount(),
                    s.employeeCount(),
                    s.cellCount(),
                    s.unknownCodeCount(),
                    s.emptyCellCount(),
                    s.skipped(),
                    s.skipReason(),
                    false,
                    s.warnings()));
        }
        return out;
    }

    private List<HistoricalImportDtos.ValidationIssue> buildIssues(
            List<HistoricalRosterParser.SheetResult> sheets,
            List<AttendanceImportRow> staged) {
        List<HistoricalImportDtos.ValidationIssue> issues = new ArrayList<>();

        // Fatal, sheet-level errors.
        for (HistoricalRosterParser.SheetResult s : sheets) {
            if (!s.skipped()) {
                continue;
            }
            if (s.ignorable()) {
                issues.add(new HistoricalImportDtos.ValidationIssue(WARN, "IGNORED_SHEET",
                        "Sheet '" + s.sheetName() + "' ignored — "
                                + (s.skipDetail() == null || s.skipDetail().isBlank()
                                ? "it is not an attendance roster (" + s.skipReason() + ")."
                                : s.skipDetail()),
                        s.sheetName(), null, null, 1));
                continue;
            }
            String type;
            switch (s.skipReason() == null ? "" : s.skipReason()) {
                case "no date columns" -> type = T_NO_DATES;
                case "cannot determine month" -> type = T_NO_MONTH;
                case "no data rows" -> {
                    boolean noEmp = s.skipDetail() != null && s.skipDetail().contains("no employee id");
                    type = noEmp ? T_NO_EMP : T_NO_ROWS;
                }
                case "not an attendance roster", "empty sheet" -> type = T_NOT_ROSTER;
                default -> type = T_READABLE;
            }
            // Always name the worksheet and state what was actually inspected, so
            // an admin never has to guess which tab failed or why.
            String text = s.skipDetail() == null || s.skipDetail().isBlank()
                    ? messageFallback(type)
                    : s.skipDetail();
            issues.add(new HistoricalImportDtos.ValidationIssue(ERR, type, text,
                    s.sheetName(), null, null, 1));
        }

        // Per-sheet: parser warnings (minus things surfaced as row errors) and
        // per-record invalid dates.
        for (HistoricalRosterParser.SheetResult s : sheets) {
            if (s.skipped()) {
                continue;
            }
            for (HistoricalRosterParser.ParsedRecord rec : s.records()) {
                if (rec.attendanceDate() == null) {
                    issues.add(new HistoricalImportDtos.ValidationIssue(ERR, T_INVALID_DATE,
                            rec.warning() == null ? "Invalid date" : rec.warning(),
                            s.sheetName(), rec.sourceRow(), rec.employeeId(), 1));
                }
            }
            for (String w : s.warnings()) {
                if (w.contains("not valid") || w.contains("no employee id")) {
                    continue;
                }
                issues.add(new HistoricalImportDtos.ValidationIssue(WARN, "SHEET_WARNING", w,
                        s.sheetName(), null, null, 1));
            }
        }

        // Employee-level aggregate warnings.
        missingField(issues, sheets, T_MISSING_EMAIL, "email");
        missingField(issues, sheets, T_MISSING_MANAGER, "manager");

        // Unknown attendance codes.
        Map<String, long[]> unknownCount = new LinkedHashMap<>();
        for (HistoricalRosterParser.SheetResult s : sheets) {
            for (HistoricalRosterParser.ParsedRecord rec : s.records()) {
                if (rec.unknown() && rec.statusCode() != null) {
                    unknownCount.computeIfAbsent(rec.statusCode(), k -> new long[]{0})[0]++;
                }
            }
        }
        unknownCount.entrySet().stream()
                .sorted(Map.Entry.<String, long[]>comparingByValue(Comparator.comparingLong(v -> v[0])).reversed())
                .forEach(e -> issues.add(new HistoricalImportDtos.ValidationIssue(WARN, T_UNKNOWN_CODE,
                        "Unknown attendance code '" + e.getKey() + "' in " + e.getValue()[0] + " record(s)",
                        null, null, null, e.getValue()[0])));

        // Duplicate employee ID (same employee + date appearing more than once).
        long duplicates = staged.stream().filter(r -> "DUPLICATE".equals(r.getAction())).count();
        if (duplicates > 0) {
            issues.add(new HistoricalImportDtos.ValidationIssue(WARN, T_DUP_EMP,
                    duplicates + " duplicate row(s) for an existing employee/date — will be skipped",
                    null, null, null, duplicates));
        }

        // Inconsistent employee names (same id, different names).
        Map<String, Set<String>> namesByEmployee = new LinkedHashMap<>();
        for (HistoricalRosterParser.SheetResult s : sheets) {
            for (HistoricalRosterParser.ParsedRecord rec : s.records()) {
                if (rec.employeeId() == null || rec.employeeName() == null) {
                    continue;
                }
                namesByEmployee.computeIfAbsent(rec.employeeId(), k -> new HashSet<>())
                        .add(rec.employeeName().trim());
            }
        }
        long inconsistent = namesByEmployee.values().stream().filter(s -> s.size() > 1).count();
        if (inconsistent > 0) {
            issues.add(new HistoricalImportDtos.ValidationIssue(WARN, T_INCONSISTENT_NAME,
                    inconsistent + " employee(s) have inconsistent names across the workbook",
                    null, null, null, inconsistent));
        }

        // Shift problems, per employee per period. Shift is a property of the
        // rostered month, so a missing value here would otherwise be silently
        // inherited from another month instead of being reported.
        shiftIssues(issues, sheets);
        weekOffIssues(issues, sheets);

        return mergeIssues(issues);
    }

    /**
     * Flags every employee/period whose week off is absent or does not name a real
     * weekday. Nothing is substituted for the missing value - the point is to
     * surface it so an admin can fix the workbook rather than have the employee
     * inherit a different month's schedule.
     */
    private void weekOffIssues(List<HistoricalImportDtos.ValidationIssue> issues,
                               List<HistoricalRosterParser.SheetResult> sheets) {
        // Keyed by employee|period so one issue is reported per person per month.
        Map<String, String[]> gaps = new LinkedHashMap<>();

        for (HistoricalRosterParser.SheetResult s : sheets) {
            if (s.skipped()) {
                continue;
            }
            for (HistoricalRosterParser.ParsedRecord rec : s.records()) {
                if (rec.employeeId() == null || rec.attendanceDate() == null) {
                    continue;
                }
                String weekOff = rec.weekOff();
                if (weekOff != null && !weekOff.isBlank() && weekOffUtil.isRecognised(weekOff)) {
                    continue;
                }
                String period = YearMonth.from(rec.attendanceDate()).toString();
                gaps.putIfAbsent(rec.employeeId() + "|" + period,
                        new String[]{rec.employeeId(), period, s.sheetName(),
                                String.valueOf(rec.sourceRow()), weekOff == null ? "" : weekOff.trim()});
            }
        }

        for (String[] g : gaps.values()) {
            boolean missing = g[4].isEmpty();
            issues.add(new HistoricalImportDtos.ValidationIssue(
                    WARN, missing ? T_WEEK_OFF_MISSING : T_WEEK_OFF_INVALID,
                    missing
                            ? "No week off in the source roster for employee " + g[0] + " in " + g[1]
                            + " - left blank rather than carried over from another month"
                            : "Unrecognised week off '" + g[4] + "' for employee " + g[0] + " in " + g[1]
                            + " - not stored, please check the workbook",
                    g[2], Integer.valueOf(g[3]), g[0], 1));
        }
    }

    /**
     * Flags every employee/period whose shift is absent or not a parsable
     * {@code HH:mm-HH:mm} range. Nothing is substituted for the missing value -
     * the point is to surface it so an admin can fix the workbook rather than
     * have the employee inherit a different month's shift.
     */
    private static void shiftIssues(List<HistoricalImportDtos.ValidationIssue> issues,
                                    List<HistoricalRosterParser.SheetResult> sheets) {
        // Keyed by employee|period so one issue is reported per person per month.
        Map<String, String[]> gaps = new LinkedHashMap<>();

        for (HistoricalRosterParser.SheetResult s : sheets) {
            if (s.skipped()) {
                continue;
            }
            for (HistoricalRosterParser.ParsedRecord rec : s.records()) {
                if (rec.employeeId() == null || rec.attendanceDate() == null) {
                    continue;
                }
                String shift = rec.shift();
                if (shift != null && !shift.isBlank() && ShiftTime.isTimeRange(shift)) {
                    continue;
                }
                String period = YearMonth.from(rec.attendanceDate()).toString();
                gaps.putIfAbsent(rec.employeeId() + "|" + period,
                        new String[]{rec.employeeId(), period, s.sheetName(),
                                String.valueOf(rec.sourceRow()), shift == null ? "" : shift.trim()});
            }
        }

        for (String[] g : gaps.values()) {
            boolean missing = g[4].isEmpty();
            issues.add(new HistoricalImportDtos.ValidationIssue(
                    WARN, missing ? T_SHIFT_MISSING : T_SHIFT_INVALID,
                    missing
                            ? "No shift in the source roster for employee " + g[0] + " in " + g[1]
                            + " - left blank rather than inherited from another month"
                            : "Unrecognised shift '" + g[4] + "' for employee " + g[0] + " in " + g[1],
                    g[2], Integer.valueOf(g[3]), g[0], 1));
        }
    }

    private static List<HistoricalImportDtos.ValidationIssue> mergeIssues(
            List<HistoricalImportDtos.ValidationIssue> issues) {
        Map<String, HistoricalImportDtos.ValidationIssue> byKey = new LinkedHashMap<>();
        for (HistoricalImportDtos.ValidationIssue it : issues) {
            String key = it.severity() + "|" + it.type() + "|" + it.sheetName() + "|" + it.row();
            HistoricalImportDtos.ValidationIssue existing = byKey.get(key);
            if (existing == null) {
                byKey.put(key, it);
            } else {
                byKey.put(key, new HistoricalImportDtos.ValidationIssue(
                        existing.severity(), existing.type(), existing.message(),
                        existing.sheetName(), existing.row(), existing.employeeId(),
                        existing.count() + it.count()));
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private static void missingField(List<HistoricalImportDtos.ValidationIssue> issues,
                                     List<HistoricalRosterParser.SheetResult> sheets,
                                     String type, String field) {
        Set<String> missing = new LinkedHashSet<>();
        for (HistoricalRosterParser.SheetResult s : sheets) {
            for (HistoricalRosterParser.ParsedRecord rec : s.records()) {
                if (rec.employeeId() == null) {
                    continue;
                }
                String value = "email".equals(field) ? rec.email() : rec.manager();
                if (value == null || value.isBlank()) {
                    missing.add(rec.employeeId());
                }
            }
        }
        if (!missing.isEmpty()) {
            boolean email = "email".equals(field);
            issues.add(new HistoricalImportDtos.ValidationIssue(WARN, type,
                    (email ? "Missing email" : "Missing manager")
                            + " for " + missing.size() + " employee(s)",
                    null, null, null, missing.size()));
        }
    }

    private static void markImportable(List<HistoricalImportDtos.SheetAnalysis> analysis,
                                       List<HistoricalImportDtos.ValidationIssue> issues) {
        for (int i = 0; i < analysis.size(); i++) {
            HistoricalImportDtos.SheetAnalysis a = analysis.get(i);
            boolean hasError = issues.stream()
                    .anyMatch(iss -> iss.severity().equals(ERR) && Objects.equals(iss.sheetName(), a.sheetName()));
            if (a.importable() == (!a.skipped() && !hasError)) {
                continue;
            }
            analysis.set(i, new HistoricalImportDtos.SheetAnalysis(a.sheetName(), a.month(), a.headerRow(),
                    a.employeeColumns(), a.dateColumns(), a.employeeCount(), a.cellCount(),
                    a.unknownCodeCount(), a.emptyCellCount(), a.skipped(), a.skipReason(),
                    !a.skipped() && !hasError, a.warnings()));
        }
    }

    private List<HistoricalImportDtos.UnknownCodeDetail> buildUnknownCodes(
            HistoricalRosterParser.ParsedWorkbook parsed) {
        Map<String, long[]> count = new LinkedHashMap<>();
        Map<String, String> example = new LinkedHashMap<>();
        for (HistoricalRosterParser.SheetResult s : parsed.sheets()) {
            for (HistoricalRosterParser.ParsedRecord rec : s.records()) {
                if (rec.unknown() && rec.statusCode() != null) {
                    count.computeIfAbsent(rec.statusCode(), k -> new long[]{0})[0]++;
                    example.putIfAbsent(rec.statusCode(), rec.rawCode() == null ? rec.statusCode() : rec.rawCode());
                }
            }
        }
        List<HistoricalImportDtos.UnknownCodeDetail> out = new ArrayList<>();
        count.entrySet().stream()
                .sorted(Map.Entry.<String, long[]>comparingByValue(Comparator.comparingLong(v -> v[0])).reversed())
                .forEach(e -> out.add(new HistoricalImportDtos.UnknownCodeDetail(
                        e.getKey(), example.get(e.getKey()), e.getValue()[0],
                        HistoricalImportCodes.suggestMeaning(e.getKey()))));
        return out;
    }

    /**
     * TEMPORARY diagnostic: every unknown cell grouped by the exact value as
     * written, with up to five sample locations so the exact offending cells
     * can be reviewed. Used to decide which values need aliases before the
     * canonicalisation rules are changed.
     */
    private static List<HistoricalImportDtos.UnknownValueDetail> buildUnknownValues(
            HistoricalRosterParser.ParsedWorkbook parsed) {
        Map<String, long[]> count = new LinkedHashMap<>();
        Map<String, String> normalized = new LinkedHashMap<>();
        Map<String, List<HistoricalImportDtos.UnknownValueSample>> examples = new LinkedHashMap<>();
        for (HistoricalRosterParser.SheetResult s : parsed.sheets()) {
            if (s.skipped()) {
                continue;
            }
            for (HistoricalRosterParser.ParsedRecord rec : s.records()) {
                if (!rec.unknown()) {
                    continue;
                }
                String key = rec.rawCode() == null ? "" : rec.rawCode();
                count.computeIfAbsent(key, k -> new long[]{0})[0]++;
                normalized.putIfAbsent(key, rec.statusCode());
                List<HistoricalImportDtos.UnknownValueSample> ex =
                        examples.computeIfAbsent(key, k -> new ArrayList<>());
                if (ex.size() < 5) {
                    ex.add(new HistoricalImportDtos.UnknownValueSample(s.sheetName(), rec.sourceRow(),
                            rec.sourceColumn(), rec.employeeId(), rec.employeeName(), rec.attendanceDate()));
                }
            }
        }
        List<HistoricalImportDtos.UnknownValueDetail> out = new ArrayList<>();
        count.entrySet().stream()
                .sorted(Map.Entry.<String, long[]>comparingByValue(Comparator.comparingLong(v -> v[0])).reversed())
                .forEach(e -> out.add(new HistoricalImportDtos.UnknownValueDetail(
                        e.getKey(), normalized.get(e.getKey()), e.getValue()[0], examples.get(e.getKey()))));
        return out;
    }

    /** Lighter diagnostic used when rehydrating a preview from persisted rows. */
    private static List<HistoricalImportDtos.UnknownValueDetail> unknownValuesFromRows(
            List<AttendanceImportRow> rows) {
        Map<String, long[]> count = new LinkedHashMap<>();
        Map<String, String> normalized = new LinkedHashMap<>();
        for (AttendanceImportRow r : rows) {
            if (Boolean.TRUE.equals(r.getIsUnknown()) && r.getIncomingStatus() != null) {
                count.computeIfAbsent(r.getIncomingStatus(), k -> new long[]{0})[0]++;
                normalized.putIfAbsent(r.getIncomingStatus(), r.getIncomingStatus());
            }
        }
        List<HistoricalImportDtos.UnknownValueDetail> out = new ArrayList<>();
        count.entrySet().stream()
                .sorted(Map.Entry.<String, long[]>comparingByValue(Comparator.comparingLong(v -> v[0])).reversed())
                .forEach(e -> out.add(new HistoricalImportDtos.UnknownValueDetail(
                        e.getKey(), normalized.get(e.getKey()), e.getValue()[0], List.of())));
        return out;
    }

    // ------------------------------------------------------- persisted state

    private void persistSnapshot(AttendanceImportHistory h,
                                 List<HistoricalImportDtos.SheetAnalysis> analysis,
                                 List<HistoricalImportDtos.ValidationIssue> issues,
                                 List<HistoricalImportDtos.UnknownCodeDetail> unknown) {
        try {
            h.setSummaryJson(objectMapper.writeValueAsString(new Snapshot(analysis, issues, unknown)));
        } catch (Exception e) {
            log.warn("Failed to serialise historical import snapshot", e);
            h.setSummaryJson(null);
        }
    }

    private Snapshot readSnapshot(AttendanceImportHistory h) {
        if (h.getSummaryJson() == null || h.getSummaryJson().isBlank()) {
            return new Snapshot(List.of(), List.of(), List.of());
        }
        try {
            return objectMapper.readValue(h.getSummaryJson(), Snapshot.class);
        } catch (Exception e) {
            log.warn("Failed to read historical import snapshot", e);
            return new Snapshot(List.of(), List.of(), List.of());
        }
    }

    private void summarizeAndHold(AttendanceImportHistory h,
                                  HistoricalRosterParser.ParsedWorkbook parsed,
                                  List<AttendanceImportRow> staged,
                                  List<HistoricalImportDtos.ValidationIssue> issues) {
        int insert = 0, update = 0, duplicate = 0, unknown = 0, invalid = 0;
        for (AttendanceImportRow r : staged) {
            switch (r.getAction()) {
                case "INSERT" -> insert++;
                case "UPDATE" -> update++;
                case "DUPLICATE" -> duplicate++;
                case "INVALID" -> invalid++;
                default -> { }
            }
            if (Boolean.TRUE.equals(r.getIsUnknown())) {
                unknown++;
            }
        }
        long warnings = issues.stream().filter(i -> i.severity().equals(WARN)).count();
        long errors = issues.stream().filter(i -> i.severity().equals(ERR)).count();
        h.setInsertedRecords(insert);
        h.setUpdatedRecords(update);
        h.setDuplicateRecords(duplicate);
        h.setUnknownCodes(unknown);
        h.setInvalidRows(invalid);
        h.setErrors((int) errors);
        h.setWarnings((int) warnings);
        h.setStatus(AttendanceImportHistory.ImportStatus.PREVIEWED.name());
    }

    // ------------------------------------------------- summary / view mapping

    private static List<AttendanceImportRow> firstPage(List<AttendanceImportRow> rows, int size) {
        return rows.subList(0, Math.min(size, rows.size()));
    }

    private static List<HistoricalImportDtos.RowView> toRowViews(List<AttendanceImportRow> rows) {
        List<HistoricalImportDtos.RowView> views = new ArrayList<>();
        for (AttendanceImportRow r : rows) {
            views.add(new HistoricalImportDtos.RowView(r.getId(), r.getSheetName(), r.getSourceRow(),
                    r.getEmployeeId(), r.getEmployeeName(), r.getAttendanceDate(), r.getExistingStatus(),
                    r.getIncomingStatus(), r.getStatusName(), r.getAction(), r.getWarning(),
                    Boolean.TRUE.equals(r.getIsUnknown()), r.getEmployeeLocation(), r.getEmployeeShift(),
                    r.getEmployeeWeekOff(), r.getDescription(), r.getDescriptionSource(),
                    r.getDescriptionAuthor(), r.getDescriptionAt()));
        }
        return views;
    }

    private static HistoricalImportDtos.Summary toSummary(AttendanceImportHistory h,
                                                          List<HistoricalImportDtos.SheetAnalysis> details) {
        return new HistoricalImportDtos.Summary(h.getTotalSheets(), h.getSheetsImported(), h.getSheetsSkipped(),
                h.getEmployeesDetected(), h.getRecordsDetected(), h.getInsertedRecords(), h.getUpdatedRecords(),
                h.getDuplicateRecords(), h.getUnknownCodes(), h.getInvalidRows(), h.getWarnings(), h.getErrors(),
                h.getInsertedRecords() + h.getUpdatedRecords(), h.getInvalidRows(), details);
    }

    // ------------------------------------------------------------------ QUERIES

    @Transactional(readOnly = true)
    public HistoricalImportDtos.PreviewResponse previewOf(Long importId, int page, int size) {
        AttendanceImportHistory h = load(importId);
        int safeSize = Math.max(1, Math.min(size, 200));
        long total = rowRepository.countByImportHistoryId(importId);
        Page<AttendanceImportRow> slice = rowRepository.findByImportHistoryId(importId,
                PageRequest.of(Math.max(0, page), safeSize, org.springframework.data.domain.Sort.by("id")));

        Snapshot snap = readSnapshot(h);
        List<HistoricalImportDtos.UnknownCodeDetail> unknown = unknownFromRows(
                rowRepository.findByImportHistoryIdOrderByIdAsc(importId));
        return new HistoricalImportDtos.PreviewResponse(h.getId(), h.getFileName(), h.getOriginalFileName(),
                h.getStatus(), toSummary(h, snap.analysis()), total, slice.getNumber(), safeSize,
                toRowViews(slice.getContent()), snap.analysis(), snap.issues(), unknown,
                unknownValuesFromRows(rowRepository.findByImportHistoryIdOrderByIdAsc(importId)));
    }

    private static List<HistoricalImportDtos.UnknownCodeDetail> unknownFromRows(
            List<AttendanceImportRow> rows) {
        Map<String, long[]> count = new LinkedHashMap<>();
        for (AttendanceImportRow r : rows) {
            if (Boolean.TRUE.equals(r.getIsUnknown()) && r.getIncomingStatus() != null) {
                count.computeIfAbsent(r.getIncomingStatus(), k -> new long[]{0})[0]++;
            }
        }
        List<HistoricalImportDtos.UnknownCodeDetail> out = new ArrayList<>();
        count.entrySet().stream()
                .sorted(Map.Entry.<String, long[]>comparingByValue(Comparator.comparingLong(v -> v[0])).reversed())
                .forEach(e -> out.add(new HistoricalImportDtos.UnknownCodeDetail(
                        e.getKey(), e.getKey(), e.getValue()[0],
                        HistoricalImportCodes.suggestMeaning(e.getKey()))));
        return out;
    }

    // ------------------------------------------------------------ MAP STAGED

    @Transactional
    public HistoricalImportDtos.MapStagedResponse mapStaged(Long importId,
                                                            HistoricalImportDtos.MapStagedRequest req) {
        AttendanceImportHistory h = load(importId);
        if ("COMMITTED".equals(h.getStatus())) {
            throw ApiException.conflict("Import is already committed");
        }
        String from = HistoricalImportCodes.normaliseRaw(req.from());
        if (from == null) {
            throw ApiException.badRequest("Missing source code to map");
        }

        List<AttendanceImportRow> rows = rowRepository.findByImportHistoryIdOrderByIdAsc(importId);
        String toTarget = null;
        String toName = null;
        long mapped = 0;
        for (AttendanceImportRow r : rows) {
            if (!Boolean.TRUE.equals(r.getIsUnknown())) {
                continue;
            }
            if (!from.equals(HistoricalImportCodes.normaliseRaw(r.getIncomingStatus()))) {
                continue;
            }
            String to = req.to();
            if (to == null || to.isBlank()) {
                continue; // keep as unknown
            }
            String target = HistoricalImportCodes.normaliseRaw(to);
            if (target.equals(from) || target.equals("KEEP")) {
                continue; // keep as unknown
            }
            AttendanceStatus st = statusRepository.findById(target).orElseThrow(() ->
                    ApiException.badRequest("Unknown target code '" + target + "'. Known codes: " + knownCodes()));
            r.setIncomingStatus(st.getCode());
            r.setStatusName(st.getName());
            r.setIsUnknown(false);
            mapped++;
            toTarget = st.getCode();
            toName = st.getName();
        }

        if (mapped > 0) {
            rowRepository.saveAll(rows);
            List<HistoricalImportDtos.UnknownCodeDetail> unknown = unknownFromRows(rows);
            h.setUnknownCodes((int) unknown.stream().mapToLong(HistoricalImportDtos.UnknownCodeDetail::count).sum());
            Snapshot snap = readSnapshot(h);
            List<HistoricalImportDtos.ValidationIssue> issues = refreshUnknownIssues(snap.issues(), rows);
            persistSnapshot(h, snap.analysis(), issues, unknown);
            h.setErrors((int) issues.stream().filter(i -> i.severity().equals(ERR)).count());
            h.setWarnings((int) issues.stream().filter(i -> i.severity().equals(WARN)).count());
            historyRepository.save(h);
            auditService.record("HISTORICAL_UNKNOWN_MAPPED", "AttendanceImportRow", String.valueOf(importId),
                    Map.of("from", from),
                    Map.of("to", toTarget == null ? "KEEP" : toTarget, "rows", mapped));
        }
        return new HistoricalImportDtos.MapStagedResponse(mapped, from, toTarget, toName);
    }

    private static List<HistoricalImportDtos.ValidationIssue> refreshUnknownIssues(
            List<HistoricalImportDtos.ValidationIssue> issues,
            List<AttendanceImportRow> rows) {
        List<HistoricalImportDtos.ValidationIssue> remaining = issues.stream()
                .filter(i -> !(i.severity().equals(WARN) && T_UNKNOWN_CODE.equals(i.type())))
                .toList();
        List<HistoricalImportDtos.ValidationIssue> out = new ArrayList<>(remaining);
        unknownFromRows(rows).forEach(u -> out.add(new HistoricalImportDtos.ValidationIssue(WARN, T_UNKNOWN_CODE,
                "Unknown attendance code '" + u.code() + "' in " + u.count() + " record(s)",
                null, null, null, u.count())));
        return mergeIssues(out);
    }

    // -------------------------------------------------------------- RESOLUTION

    /**
     * The admin's worklist for one staged import: every cell that was flagged at
     * parse time and has not yet been corrected or explicitly skipped.
     */
    @Transactional(readOnly = true)
    public HistoricalImportDtos.UnresolvedResponse unresolved(Long importId, int page, int size) {
        return unresolved(importId, page, size, null, null);
    }

    @Transactional(readOnly = true)
    public HistoricalImportDtos.UnresolvedResponse unresolved(Long importId, int page, int size,
                                                              String search, String category) {
        return unresolved(importId, page, size, search, category, null);
    }

    /**
     * Same worklist, additionally split by decision state so the Making step can
     * show completed corrections and skips without losing the audit trail. The
     * reason filter describes the original parse-time problem, so it only narrows
     * pending (or all) rows; resolved buckets are never silently hidden behind a
     * stale category selection.
     */
    @Transactional(readOnly = true)
    public HistoricalImportDtos.UnresolvedResponse unresolved(Long importId, int page, int size,
                                                              String search, String category, String state) {
        load(importId);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 200);
        String bucket = normaliseState(state);
        String cat = ("PENDING".equals(bucket) || "ALL".equals(bucket))
                ? normaliseCategory(category) : null;
        Page<AttendanceImportRow> slice = rowRepository
                .findReviewFiltered(importId, bucket, likePattern(search), cat, PageRequest.of(p, s));
        return new HistoricalImportDtos.UnresolvedResponse(importId, resolutionSummary(importId),
                p, s, slice.getTotalElements(), toUnresolvedEntries(slice.getContent()));
    }

    /**
     * Applies one review decision (correct-to-status or skip) to many flagged cells
     * at once. An explicit row-id list is authoritative; when it is empty the whole
     * current filter is resolved, which is how an admin can clear thousands of
     * entries of the same kind without clicking through every page.
     */
    @Transactional
    public HistoricalImportDtos.BulkResolveResponse bulkResolve(Long importId,
                                                                HistoricalImportDtos.BulkResolveRequest req,
                                                                Long adminUserId) {
        load(importId);
        boolean skip = Boolean.TRUE.equals(req.skip());
        List<AttendanceImportRow> rows;
        if (req.rowIds() != null && !req.rowIds().isEmpty()) {
            rows = rowRepository.findAllById(req.rowIds()).stream()
                    .filter(r -> r.getImportHistory() != null && importId.equals(r.getImportHistory().getId()))
                    .filter(HistoricalImportService::needsResolution)
                    .toList();
        } else {
            rows = rowRepository.findReviewFilteredList(importId, "PENDING",
                    likePattern(req.search()), normaliseCategory(req.category()));
        }
        if (rows.isEmpty()) {
            return new HistoricalImportDtos.BulkResolveResponse(0, resolutionSummary(importId));
        }
        if (skip) {
            String note = blankToNull(req.reason());
            for (AttendanceImportRow row : rows) {
                row.setSkipped(true);
                row.setAction("SKIPPED");
                if (note != null) {
                    String base = row.getIssue() == null ? "Skipped by admin" : row.getIssue();
                    row.setIssue(base + " — skipped: " + note);
                }
            }
        } else {
            String target = HistoricalImportCodes.normaliseRaw(req.status());
            if (target == null || target.isBlank()) {
                throw ApiException.badRequest("Pick a status to correct these entries to.");
            }
            AttendanceStatus st = statusRepository.findById(target).orElseThrow(() ->
                    ApiException.badRequest(
                            "Unknown status '" + req.status() + "'. Known statuses: " + knownCodes()));
            for (AttendanceImportRow row : rows) {
                row.setIncomingStatus(st.getCode());
                row.setStatusName(st.getName());
                row.setIsUnknown(false);
                row.setCorrected(true);
            }
        }
        rowRepository.saveAll(rows);
        auditService.record(skip ? "HISTORICAL_ROWS_SKIPPED" : "HISTORICAL_ROWS_CORRECTED",
                "AttendanceImportHistory", String.valueOf(importId), null,
                Map.of("import", importId, "affected", rows.size(),
                        "to", skip ? "SKIPPED" : HistoricalImportCodes.normaliseRaw(req.status())));
        return new HistoricalImportDtos.BulkResolveResponse(rows.size(), resolutionSummary(importId));
    }

    /** Normalises the search box into a lowercase LIKE pattern, or {@code null} when empty. */
    private static String likePattern(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        return "%" + search.trim().toLowerCase() + "%";
    }

    private static String normaliseCategory(String category) {
        if (category == null || category.isBlank() || "ALL".equalsIgnoreCase(category)) {
            return null;
        }
        return category.trim().toUpperCase();
    }

    /** Maps the review-state filter onto one of the four buckets the query knows. */
    private static String normaliseState(String state) {
        if (state == null || state.isBlank()) {
            return "PENDING";
        }
        String s = state.trim().toUpperCase();
        return switch (s) {
            case "PENDING", "CORRECTED", "SKIPPED", "ALL" -> s;
            default -> "PENDING";
        };
    }

    /**
     * Replaces one unresolved cell with a valid attendance status. The original
     * workbook value is kept on the row so the correction stays traceable; nothing
     * is written to {@code attendance_records} until the import is committed.
     */
    @Transactional
    public HistoricalImportDtos.ResolveRowResponse correctRow(Long importId, Long rowId, String status) {
        AttendanceImportRow row = loadRow(importId, rowId);
        if (!needsResolution(row)) {
            throw ApiException.conflict("Entry " + rowId + " is already resolved.");
        }
        String target = HistoricalImportCodes.normaliseRaw(status);
        if (target == null || target.isBlank()) {
            throw ApiException.badRequest("Pick a status to correct this entry to.");
        }
        AttendanceStatus st = statusRepository.findById(target).orElseThrow(() ->
                ApiException.badRequest("Unknown status '" + status + "'. Known statuses: " + knownCodes()));

        row.setIncomingStatus(st.getCode());
        row.setStatusName(st.getName());
        row.setIsUnknown(false);
        row.setCorrected(true);
        // The original issue is kept rather than cleared: it is what marks the row as
        // having been flagged, so clearing it would both erase why the cell was
        // questioned and make the totals drift as entries are resolved.
        // The action is left alone on purpose: a blank cell never had its action
        // downgraded (INVALID means "no date", which a correction cannot fix), so
        // the row simply becomes committable once it has a real status.
        rowRepository.save(row);
        auditService.record("HISTORICAL_ROW_CORRECTED", "AttendanceImportRow", String.valueOf(rowId),
                null, Map.of("import", importId,
                        "from", row.getOriginalStatus() == null ? "" : row.getOriginalStatus(),
                        "to", st.getCode()));
        return new HistoricalImportDtos.ResolveRowResponse(toUnresolvedEntry(row), resolutionSummary(importId));
    }

    /**
     * Explicitly leaves one cell out of the import. This is a decision, not a
     * verdict, so it is recorded as {@code SKIPPED} and audited with the reason the
     * admin gave.
     */
    @Transactional
    public HistoricalImportDtos.ResolveRowResponse skipRow(Long importId, Long rowId, String reason) {
        AttendanceImportRow row = loadRow(importId, rowId);
        if (!needsResolution(row)) {
            throw ApiException.conflict("Entry " + rowId + " is already resolved.");
        }
        String note = blankToNull(reason);
        row.setSkipped(true);
        row.setAction("SKIPPED");
        if (note != null) {
            String base = row.getIssue() == null ? "Skipped by admin" : row.getIssue();
            row.setIssue(base + " — skipped: " + note);
        }
        rowRepository.save(row);
        auditService.record("HISTORICAL_ROW_SKIPPED", "AttendanceImportRow", String.valueOf(rowId),
                null, Map.of("import", importId,
                        "status", row.getOriginalStatus() == null ? "" : row.getOriginalStatus(),
                        "reason", note == null ? "" : note));
        return new HistoricalImportDtos.ResolveRowResponse(toUnresolvedEntry(row), resolutionSummary(importId));
    }

    private HistoricalImportDtos.UnresolvedSummary resolutionSummary(Long importId) {
        long totalEntries = rowRepository.countByImportHistoryId(importId);
        long flagged = rowRepository.countByImportHistoryIdAndIssueIsNotNull(importId);
        long corrected = rowRepository.countByImportHistoryIdAndIssueIsNotNullAndCorrectedIsTrue(importId);
        long skipped = rowRepository.countByImportHistoryIdAndSkippedIsTrue(importId);
        long remaining = rowRepository
                .countByImportHistoryIdAndIssueIsNotNullAndCorrectedIsFalseAndSkippedIsFalse(importId);
        // Entries that will actually reach the roster: everything except the ones
        // still awaiting a decision and the ones explicitly skipped. Corrected
        // entries count as valid because their status has now been supplied.
        long validEntries = Math.max(totalEntries - remaining - skipped, 0);
        return new HistoricalImportDtos.UnresolvedSummary(totalEntries, validEntries, flagged,
                corrected, skipped, remaining);
    }

    private AttendanceImportRow loadRow(Long importId, Long rowId) {
        AttendanceImportRow row = rowRepository.findById(rowId)
                .orElseThrow(() -> ApiException.notFound("Import row " + rowId + " not found"));
        if (row.getImportHistory() == null || !importId.equals(row.getImportHistory().getId())) {
            throw ApiException.notFound("Import row " + rowId + " does not belong to import " + importId);
        }
        return row;
    }

    private static List<HistoricalImportDtos.UnresolvedEntry> toUnresolvedEntries(List<AttendanceImportRow> rows) {
        return rows.stream().map(HistoricalImportService::toUnresolvedEntry).toList();
    }

    private static HistoricalImportDtos.UnresolvedEntry toUnresolvedEntry(AttendanceImportRow r) {
        return new HistoricalImportDtos.UnresolvedEntry(
                r.getId(), r.getSheetName(),
                r.getSourceRow() == null ? 0 : r.getSourceRow(),
                r.getSourceColumn(), cellRef(r),
                r.getEmployeeId(), r.getEmployeeName(), r.getAttendanceDate(),
                r.getOriginalStatus(), r.getIncomingStatus(), r.getStatusName(),
                r.getIssue(), categorise(r),
                r.getDescription(), r.getDescriptionAuthor(),
                r.getAction(),
                Boolean.TRUE.equals(r.getCorrected()), Boolean.TRUE.equals(r.getSkipped()));
    }

    /**
     * One reason bucket per flagged entry so the Making worklist can be filtered
     * and bulk-reviewed: a duplicate of another staged cell, an unusable date, a
     * cell with no value at all, an unrecognised code, or any other warning
     * (e.g. a day that does not belong to the sheet's month).
     */
    static String categorise(AttendanceImportRow r) {
        if ("DUPLICATE".equals(r.getAction())) {
            return "DUPLICATE";
        }
        if (r.getAttendanceDate() == null || "INVALID".equals(r.getAction())) {
            return "DATE_MISMATCH";
        }
        if (r.getIncomingStatus() == null || r.getIncomingStatus().isBlank()) {
            return "MISSING_DATA";
        }
        if (Boolean.TRUE.equals(r.getIsUnknown())) {
            return "UNMAPPED";
        }
        return "INVALID";
    }

    /** Human-readable A1-style pointer so the admin can find the cell in the workbook. */
    private static String cellRef(AttendanceImportRow r) {
        return cellRefFor(r.getSourceRow(), r.getSourceColumn());
    }

    /** Compact A1 reference (e.g. "G5") suitable for the stored provenance cell. */
    private static String a1CellRef(AttendanceImportRow r) {
        if (r.getSourceRow() == null || r.getSourceColumn() == null) {
            return null;
        }
        return new CellReference(r.getSourceRow() - 1, r.getSourceColumn() - 1)
                .formatAsString().replace("$", "");
    }

    static String cellRefFor(Integer sourceRow, Integer sourceColumn) {
        if (sourceRow == null) {
            return null;
        }
        if (sourceColumn == null) {
            return "Row " + sourceRow;
        }
        return "Row " + sourceRow + ", Column " + columnName(sourceColumn);
    }

    private static String columnName(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.length() == 0 ? String.valueOf(index) : sb.toString();
    }

    // ------------------------------------------------------------------ COMMIT

    @Transactional
    public HistoricalImportDtos.CommitResponse commit(Long importId, Long teamId, Long adminUserId) {
        AttendanceImportHistory h = load(importId);
        if ("COMMITTED".equals(h.getStatus())) {
            return new HistoricalImportDtos.CommitResponse(h.getId(), h.getFileName(), h.getOriginalFileName(),
                    h.getStatus(), h.getImportedAt(), toSummary(h, readSnapshot(h).analysis()),
                    h.getInsertedRecords() + h.getUpdatedRecords(),
                    resultOf(h, reviewCounts(importId)));
        }
        if (!"PREVIEWED".equals(h.getStatus())) {
            throw ApiException.conflict("Import must be previewed before it can be committed");
        }
        if (h.getErrors() != null && h.getErrors() > 0) {
            throw ApiException.conflict("Cannot commit: the workbook has " + h.getErrors()
                    + " fatal error(s). Fix or remove the affected sheet(s) first.");
        }
        long unresolved = rowRepository
                .countByImportHistoryIdAndIssueIsNotNullAndCorrectedIsFalseAndSkippedIsFalse(importId);
        if (unresolved > 0) {
            throw ApiException.conflict("Cannot commit: " + unresolved
                    + " attendance entr(ies) still need review. Correct them or skip them first.");
        }

        List<AttendanceImportRow> rows = rowRepository
                .findByImportHistoryIdAndActionInOrderByIdAsc(importId, COMMITTABLE);
        int inserted = 0, updated = 0, failed = 0;
        int unknown = 0;
        Set<String> employees = new LinkedHashSet<>();
        // employee|period -> the staged row carrying that month's shift. Collected
        // while walking the day rows so the assignment is written once per month.
        Map<String, AttendanceImportRow> shiftRows = new LinkedHashMap<>();
        // Same idea for week off: it rotates independently of shift, so it gets its
        // own per-month collection rather than riding along with the shift rows.
        Map<String, AttendanceImportRow> weekOffRows = new LinkedHashMap<>();
        Instant now = appClock.now();

        // Resolve every employee and existing attendance row once, up front. The
        // old per-row lookups made the commit quadratic: each query auto-flushed a
        // persistence context that grew by one entity per saved row, so a full-year
        // workbook spent minutes inside Hibernate instead of seconds in Postgres.
        Map<String, ImportEmployee> employeeCache = new HashMap<>();
        Map<String, AttendanceRecord> recordCache = new HashMap<>();
        {
            Set<String> employeeIds = new LinkedHashSet<>();
            LocalDate minDate = null;
            LocalDate maxDate = null;
            for (AttendanceImportRow r : rows) {
                if (r.getAttendanceDate() == null || r.getIncomingStatus() == null) {
                    continue;
                }
                employeeIds.add(r.getEmployeeId());
                LocalDate d = r.getAttendanceDate();
                if (minDate == null || d.isBefore(minDate)) {
                    minDate = d;
                }
                if (maxDate == null || d.isAfter(maxDate)) {
                    maxDate = d;
                }
            }
            for (ImportEmployee e : importEmployeeRepository.findAllById(employeeIds)) {
                employeeCache.put(e.getEmployeeId(), e);
            }
            if (minDate != null) {
                for (AttendanceRecord existing : recordRepository
                        .findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                                minDate, maxDate, employeeIds)) {
                    recordCache.put(recordKey(existing.getEmployeeId(), existing.getAttendanceDate()), existing);
                }
            }
        }

        for (AttendanceImportRow r : rows) {
            if (r.getAttendanceDate() == null || r.getIncomingStatus() == null) {
                failed++;
                continue;
            }
            employees.add(r.getEmployeeId());
            if (Boolean.TRUE.equals(r.getIsUnknown())) {
                unknown++;
            }
            upsertEmployee(r, teamId, now, employeeCache);
            if (r.getAttendanceDate() != null) {
                String periodKey = r.getEmployeeId() + "|" + YearMonth.from(r.getAttendanceDate());
                shiftRows.putIfAbsent(periodKey, r);
                // Prefer the first row that actually carries a usable week off, so a
                // blank cell at the top of the block cannot hide a real value further
                // down, and placeholder text from a non-roster sheet cannot mask one.
                if (weekOffUtil.isRecognised(r.getEmployeeWeekOff())) {
                    weekOffRows.putIfAbsent(periodKey, r);
                }
            }
            String rowKey = recordKey(r.getEmployeeId(), r.getAttendanceDate());
            AttendanceRecord rec = recordCache.get(rowKey);
            boolean isNew = rec == null;
            if (rec == null) {
                rec = new AttendanceRecord();
                recordCache.put(rowKey, rec);
            }
            rec.setEmployeeId(r.getEmployeeId());
            rec.setAttendanceDate(r.getAttendanceDate());
            rec.setStatusCode(r.getIncomingStatus());
            rec.setStatusName(r.getStatusName());
            rec.setIsUnknown(r.getIsUnknown());
            rec.setShift(r.getEmployeeShift());
            rec.setLocation(r.getEmployeeLocation());
            rec.setSourceSheet(r.getSheetName());
            rec.setSourceRow(r.getSourceRow());
            rec.setSourceFile(h.getFileName());
            // Descriptions are preserved, never silently overwritten: only a
            // record that has none yet receives the imported text. The immutable
            // copy + source metadata keep the original auditable after edits.
            String stagedDescription = blankToNull(r.getDescription());
            if (stagedDescription != null && rec.getDescription() == null) {
                rec.setDescription(stagedDescription);
                rec.setDescriptionSource(r.getDescriptionSource());
                rec.setDescriptionSourceSheet(r.getSheetName());
                rec.setDescriptionSourceCell(a1CellRef(r));
                rec.setDescriptionSourceAuthor(r.getDescriptionAuthor());
                rec.setDescriptionSourceAt(r.getDescriptionAt());
                rec.setDescriptionImported(stagedDescription);
                rec.setSourceValue(r.getOriginalStatus());
                if (rec.getDescriptionCreatedAt() == null) {
                    rec.setDescriptionCreatedName(r.getDescriptionAuthor() != null
                            ? r.getDescriptionAuthor() : "Excel import");
                    rec.setDescriptionCreatedAt(now);
                }
            }
            if (isNew) {
                rec.setImportedAt(now);
                inserted++;
            } else {
                updated++;
            }
            rec.setUpdatedAt(now);
            recordRepository.save(rec);
        }

        // Persist and detach the day records before the per-period upserts. Those
        // upserts issue a query per employee-month; without this flush each one
        // would dirty-check every record written so far, which is the other half
        // of the quadratic cost. The staged rows are read for scalar fields only,
        // so detaching them here is safe.
        entityManager.flush();
        entityManager.clear();

        // Shift is a per-period property, so it is persisted per employee+month
        // rather than on the employee master. Each month this import touches is
        // written; every other month is left exactly as its own import left it.
        Map<String, String> latestShift = upsertShiftAssignments(shiftRows.values(), h, now);
        upsertWeekOffAssignments(weekOffRows.values(), h, now);

        h.setInsertedRecords(inserted);
        h.setUpdatedRecords(updated);
        h.setUnknownCodes(unknown);
        h.setStatus(AttendanceImportHistory.ImportStatus.COMMITTED.name());
        historyRepository.save(h);

        auditService.record("HISTORICAL_IMPORT_COMMITTED", "AttendanceImportHistory", String.valueOf(h.getId()),
                null, Map.of("file", h.getOriginalFileName() == null ? h.getFileName() : h.getOriginalFileName(),
                        "inserted", inserted, "updated", updated));

        ReviewCounts counts = reviewCounts(importId);
        return new HistoricalImportDtos.CommitResponse(h.getId(), h.getFileName(), h.getOriginalFileName(),
                h.getStatus(), h.getImportedAt(), toSummary(h, readSnapshot(h).analysis()), inserted + updated,
                new HistoricalImportDtos.ImportResult(employees.size(), h.getRecordsDetected(), inserted,
                        updated, h.getDuplicateRecords(), h.getWarnings(), unknown, failed + h.getInvalidRows(),
                        (int) counts.corrected(), (int) counts.skipped()));
    }

    /** Manual review decisions this import carries, kept immutable for the result step. */
    private record ReviewCounts(long corrected, long skipped) {
    }

    private ReviewCounts reviewCounts(Long importId) {
        return new ReviewCounts(
                rowRepository.countByImportHistoryIdAndIssueIsNotNullAndCorrectedIsTrue(importId),
                rowRepository.countByImportHistoryIdAndSkippedIsTrue(importId));
    }

    private static HistoricalImportDtos.ImportResult resultOf(AttendanceImportHistory h, ReviewCounts counts) {
        return new HistoricalImportDtos.ImportResult(
                h.getEmployeesDetected(), h.getRecordsDetected(), h.getInsertedRecords(), h.getUpdatedRecords(),
                h.getDuplicateRecords(), h.getWarnings(),
                h.getUnknownCodes() == null ? 0 : h.getUnknownCodes(),
                h.getInvalidRows() == null ? 0 : h.getInvalidRows(),
                (int) counts.corrected(), (int) counts.skipped());
    }

    /**
     * Writes one shift assignment per employee per period from the staged rows,
     * and returns each employee's most recent period's shift so the employee
     * master can be refreshed to the current shift.
     *
     * <p>The unique key (employee_id, period_start) makes this idempotent:
     * re-importing a workbook corrects that month instead of adding a second,
     * possibly conflicting, row. A period whose source shift is blank never
     * overwrites an existing assignment and never invents one - the gap was
     * already reported as a preview warning.
     */
    private Map<String, String> upsertShiftAssignments(java.util.Collection<AttendanceImportRow> rows,
                                                       AttendanceImportHistory h, Instant now) {
        Map<String, AttendanceShiftAssignment> byPeriod = new LinkedHashMap<>();
        for (AttendanceImportRow r : rows) {
            YearMonth period = YearMonth.from(r.getAttendanceDate());
            AttendanceShiftAssignment a = byPeriod.computeIfAbsent(r.getEmployeeId() + "|" + period,
                    k -> AttendanceShiftAssignment.builder()
                            .employeeId(r.getEmployeeId())
                            .periodStart(period.atDay(1))
                            .createdAt(now)
                            .build());
            String shift = normaliseEmployeeValue(r.getEmployeeShift());
            if (shift == null) {
                continue;
            }
            a.setShiftValue(shift);
            a.setShiftKey(ShiftTime.comparisonKey(shift));
            a.setImportId(h.getId());
            a.setSourceSheet(r.getSheetName());
            a.setSourceRow(r.getSourceRow());
            a.setSourceFile(h.getFileName());
        }

        Map<String, String> latest = new HashMap<>();
        Map<String, YearMonth> latestPeriod = new HashMap<>();
        for (AttendanceShiftAssignment a : byPeriod.values()) {
            if (a.getShiftValue() == null) {
                continue;
            }
            YearMonth current = latestPeriod.get(a.getEmployeeId());
            YearMonth candidate = YearMonth.from(a.getPeriodStart());
            if (current == null || candidate.isAfter(current)) {
                latestPeriod.put(a.getEmployeeId(), candidate);
                latest.put(a.getEmployeeId(), a.getShiftValue());
            }
        }

        for (AttendanceShiftAssignment a : byPeriod.values()) {
            if (a.getShiftValue() == null) {
                // Source had no shift this month. Persist nothing, and never
                // overwrite a shift a previous import already stored.
                continue;
            }
            AttendanceShiftAssignment existing = shiftAssignmentRepository
                    .findByEmployeeIdAndPeriodStart(a.getEmployeeId(), a.getPeriodStart())
                    .orElse(null);
            if (existing == null) {
                shiftAssignmentRepository.save(a);
                continue;
            }
            existing.setShiftValue(a.getShiftValue());
            existing.setShiftKey(a.getShiftKey());
            existing.setImportId(a.getImportId());
            existing.setSourceSheet(a.getSourceSheet());
            existing.setSourceRow(a.getSourceRow());
            existing.setSourceFile(a.getSourceFile());
            existing.setUpdatedAt(now);
            shiftAssignmentRepository.save(existing);
        }

        latest.forEach(importEmployeeRepository::refreshMasterShift);
        return latest;
    }

    /**
     * Persists week off per employee and month, mirroring
     * {@link #upsertShiftAssignments}. Each month this import touches is written;
     * every other month is left exactly as its own import left it.
     *
     * <p>Only a recognised schedule is stored. A value such as "WeekOff" or a
     * manager's name that reached this column from a non-roster sheet would put
     * nonsense in the roster's Week Off column, so it is reported as a warning at
     * inspection time instead and the month is left unassigned.
     */
    private void upsertWeekOffAssignments(java.util.Collection<AttendanceImportRow> rows,
                                          AttendanceImportHistory h, Instant now) {
        Map<String, AttendanceWeekOffAssignment> byPeriod = new LinkedHashMap<>();
        for (AttendanceImportRow r : rows) {
            String weekOff = normaliseEmployeeValue(r.getEmployeeWeekOff());
            if (weekOff == null || !weekOffUtil.isRecognised(weekOff)) {
                // Blank, or placeholder text from a non-roster sheet. Storing that
                // would put a person's name in the roster's Week Off column, so the
                // admin's UNPARSED_WEEK_OFF warning is the only response. A month
                // with nothing usable stays unassigned rather than inheriting one.
                continue;
            }
            YearMonth period = YearMonth.from(r.getAttendanceDate());
            AttendanceWeekOffAssignment a =
                    byPeriod.computeIfAbsent(r.getEmployeeId() + "|" + period,
                            k -> AttendanceWeekOffAssignment.builder()
                                    .employeeId(r.getEmployeeId())
                                    .periodStart(period.atDay(1))
                                    .createdAt(now)
                                    .build());
            a.setWeekOffValue(weekOff);
            a.setWeekOffKey(WeekOffUtil.comparisonKey(weekOff));
            a.setImportId(h.getId());
            a.setSourceSheet(r.getSheetName());
            a.setSourceRow(r.getSourceRow());
            a.setSourceFile(h.getFileName());
        }

        Map<String, String> latest = new HashMap<>();
        Map<String, YearMonth> latestPeriod = new HashMap<>();
        for (AttendanceWeekOffAssignment a : byPeriod.values()) {
            if (a.getWeekOffValue() == null) {
                continue;
            }
            YearMonth current = latestPeriod.get(a.getEmployeeId());
            YearMonth candidate = YearMonth.from(a.getPeriodStart());
            if (current == null || candidate.isAfter(current)) {
                latestPeriod.put(a.getEmployeeId(), candidate);
                latest.put(a.getEmployeeId(), a.getWeekOffValue());
            }
        }

        for (AttendanceWeekOffAssignment a : byPeriod.values()) {
            if (a.getWeekOffValue() == null) {
                // Source had no week off this month. Persist nothing, and never
                // overwrite a schedule a previous import already stored.
                continue;
            }
            AttendanceWeekOffAssignment existing = weekOffAssignmentRepository
                    .findByEmployeeIdAndPeriodStart(a.getEmployeeId(), a.getPeriodStart())
                    .orElse(null);
            if (existing == null) {
                weekOffAssignmentRepository.save(a);
                continue;
            }
            existing.setWeekOffValue(a.getWeekOffValue());
            existing.setWeekOffKey(a.getWeekOffKey());
            existing.setImportId(a.getImportId());
            existing.setSourceSheet(a.getSourceSheet());
            existing.setSourceRow(a.getSourceRow());
            existing.setSourceFile(a.getSourceFile());
            existing.setUpdatedAt(now);
            weekOffAssignmentRepository.save(existing);
        }

        latest.forEach(importEmployeeRepository::refreshMasterWeekOff);
    }

    /**
     * Upsert the employee master snapshot. Master metadata is separately
     * normalised (whitespace collapsed, email lower-cased) and only filled
     * when currently empty — the first seen month wins, so later monthly
     * rosters never blindly overwrite master information. Each attendance
     * record still carries the metadata applicable to its own monthly roster.
     *
     * <p>Shift is deliberately not handled here: it rotates month to month, so
     * it belongs to the period (see {@link AttendanceShiftAssignment}). The
     * master's shift is refreshed separately to the latest imported period.
     */
    private void upsertEmployee(AttendanceImportRow r, Long teamId, Instant now,
                                Map<String, ImportEmployee> employeeCache) {
        ImportEmployee emp = employeeCache.get(r.getEmployeeId());
        boolean isNew = emp == null;
        if (emp == null) {
            emp = ImportEmployee.builder().employeeId(r.getEmployeeId()).build();
            employeeCache.put(r.getEmployeeId(), emp);
        }
        boolean touched = false;
        if (isNew || isBlank(emp.getEmployeeName())) {
            String name = normaliseEmployeeValue(r.getEmployeeName());
            if (name != null) {
                emp.setEmployeeName(name);
                touched = true;
            }
        }
        if (isNew || isBlank(emp.getEmail())) {
            String email = normaliseEmail(r.getEmployeeEmail());
            if (email != null) {
                emp.setEmail(email);
                touched = true;
            }
        }
        if (isNew || isBlank(emp.getLocation())) {
            String location = normaliseEmployeeValue(r.getEmployeeLocation());
            if (location != null) {
                emp.setLocation(location);
                touched = true;
            }
        }
        if (isNew || isBlank(emp.getManager())) {
            String manager = normaliseEmployeeValue(r.getEmployeeManager());
            if (manager != null) {
                emp.setManager(manager);
                touched = true;
            }
        }
        // week_off is deliberately NOT latched here. It rotates per month, so it
        // is persisted per period in attendance_week_off_assignments and the
        // master is refreshed from the latest imported period by
        // upsertWeekOffAssignments; writing it once here would pin the first
        // month ever seen.
        if (emp.getActive() == null) {
            emp.setActive(Boolean.TRUE);
            touched = true;
        }
        if (teamId != null) {
            emp.setTeamId(teamId);
            touched = true;
        }
        if (isNew || touched) {
            importEmployeeRepository.save(emp);
        }
    }

    /** Cache/upsert key for a day record: one record per employee per date. */
    private static String recordKey(String employeeId, LocalDate attendanceDate) {
        return employeeId + "|" + attendanceDate;
    }

    private static String normaliseEmployeeValue(String v) {
        return HistoricalImportCodes.normaliseText(v);
    }

    private static String normaliseEmail(String v) {
        String t = HistoricalImportCodes.normaliseText(v);
        return t == null ? null : t.toLowerCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ------------------------------------------------------------------ QUERIES

    @Transactional(readOnly = true)
    public List<HistoricalImportDtos.HistoryItem> history() {
        return historyRepository.findAllByOrderByImportedAtDesc().stream().map(h ->
                new HistoricalImportDtos.HistoryItem(h.getId(), h.getFileName(), h.getOriginalFileName(),
                        h.getImportedAt(), h.getImportedBy(), h.getStatus(), h.getTotalSheets(),
                        h.getSheetsImported(), h.getSheetsSkipped(), h.getEmployeesDetected(), h.getRecordsDetected(),
                        h.getInsertedRecords(), h.getUpdatedRecords(), h.getDuplicateRecords(), h.getUnknownCodes(),
                        h.getInvalidRows(), h.getWarnings(), h.getErrors(), h.getInvalidRows())).toList();
    }

    @Transactional(readOnly = true)
    public List<HistoricalImportDtos.StatusItem> statuses() {
        return statusRepository.findAllByOrderByCodeAsc().stream()
                .map(s -> new HistoricalImportDtos.StatusItem(s.getCode(), s.getName(), s.getDescription(),
                        s.getDisplayColor()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<HistoricalImportDtos.UnknownCodeItem> unknownCodes() {
        return recordRepository.countUnknownCodes().stream()
                .map(o -> new HistoricalImportDtos.UnknownCodeItem((String) o[0], (Long) o[1]))
                .toList();
    }

    @Transactional
    public HistoricalImportDtos.MapUnknownResponse mapUnknown(HistoricalImportDtos.MapUnknownRequest req) {
        String from = req.from().trim().toUpperCase(Locale.ROOT);
        String to = req.to().trim().toUpperCase(Locale.ROOT);
        AttendanceStatus target = statusRepository.findById(to).orElseThrow(() ->
                ApiException.badRequest("Unknown target code '" + to + "'. Known codes: " + knownCodes()));
        int mapped = recordRepository.remapUnknown(from, to, target.getName(), appClock.now());
        if (mapped > 0) {
            auditService.record("HISTORICAL_UNKNOWN_MAPPED", "AttendanceRecord", null,
                    Map.of("from", from), Map.of("to", to, "name", target.getName()));
        }
        return new HistoricalImportDtos.MapUnknownResponse(mapped, to, target.getName());
    }

    private String knownCodes() {
        return String.join(", ", statusRepository.findAllByOrderByCodeAsc().stream()
                .map(AttendanceStatus::getCode).toList());
    }

    @Transactional(readOnly = true)
    public HistoricalImportDtos.RecordsPage records(LocalDate from, LocalDate to, String employeeId,
                                                    int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 200)));
        Page<AttendanceRecord> p;
        boolean hasEmployee = employeeId != null && !employeeId.isBlank();
        if (hasEmployee && from != null && to != null) {
            p = recordRepository.findByAttendanceDateBetweenAndEmployeeId(pageable, from, to, employeeId.trim());
        } else if (hasEmployee) {
            p = recordRepository.findByEmployeeId(pageable, employeeId.trim());
        } else if (from != null && to != null) {
            p = recordRepository.findByAttendanceDateBetween(pageable, from, to);
        } else {
            p = recordRepository.findAll(pageable);
        }

        Map<String, String> names = new HashMap<>();
        importEmployeeRepository.findAllById(p.getContent().stream().map(AttendanceRecord::getEmployeeId).toList())
                .forEach(e -> names.put(e.getEmployeeId(), e.getEmployeeName()));

        List<HistoricalImportDtos.RecordView> views = p.getContent().stream().map(r ->
                new HistoricalImportDtos.RecordView(r.getId(), r.getEmployeeId(),
                        names.getOrDefault(r.getEmployeeId(), null), r.getAttendanceDate(), r.getStatusCode(),
                        r.getStatusName(), Boolean.TRUE.equals(r.getIsUnknown()), r.getSourceSheet(), r.getSourceRow(),
                        r.getSourceFile(), r.getImportedAt(), r.getDescription(), r.getDescriptionSourceAuthor())).toList();

        return new HistoricalImportDtos.RecordsPage(views, p.getTotalElements(), p.getNumber(), p.getSize());
    }

    // ------------------------------------------------------------------ REPORT

    @Transactional(readOnly = true)
    public String errorReport(Long importId) {
        AttendanceImportHistory h = load(importId);
        List<AttendanceImportRow> rows = rowRepository.findByImportHistoryIdOrderByIdAsc(importId);
        StringBuilder sb = new StringBuilder();
        sb.append("Sheet Name,Source Row,Employee ID,Employee Name,Date,Incoming Status,Action,Issue\n");
        for (AttendanceImportRow r : rows) {
            if (!"INVALID".equals(r.getAction()) && !"DUPLICATE".equals(r.getAction())
                    && r.getWarning() == null && !Boolean.TRUE.equals(r.getIsUnknown())) {
                continue;
            }
            String issue = "INVALID".equals(r.getAction())
                    ? (r.getWarning() == null ? "Invalid date" : r.getWarning())
                    : Boolean.TRUE.equals(r.getIsUnknown())
                    ? "Unknown status code '" + r.getIncomingStatus() + "'"
                    : r.getWarning() == null ? "" : r.getWarning();
            sb.append(csv(r.getSheetName())).append(',')
                    .append(r.getSourceRow() == null ? "" : r.getSourceRow()).append(',')
                    .append(csv(r.getEmployeeId())).append(',')
                    .append(csv(r.getEmployeeName())).append(',')
                    .append(r.getAttendanceDate() == null ? "" : r.getAttendanceDate()).append(',')
                    .append(csv(r.getIncomingStatus())).append(',')
                    .append(r.getAction()).append(',')
                    .append(csv(issue)).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace("\"", "\"\"");
        return v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0
                ? "\"" + v + "\"" : v;
    }

    private AttendanceImportHistory load(Long importId) {
        return historyRepository.findById(importId)
                .orElseThrow(() -> ApiException.notFound("Historical import not found"));
    }
}