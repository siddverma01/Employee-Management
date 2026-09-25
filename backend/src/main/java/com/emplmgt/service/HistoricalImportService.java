package com.emplmgt.service;

import com.emplmgt.dto.HistoricalImportDtos;
import com.emplmgt.entity.AttendanceImportHistory;
import com.emplmgt.entity.AttendanceImportRow;
import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.AttendanceStatus;
import com.emplmgt.entity.ImportEmployee;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.AttendanceImportHistoryRepository;
import com.emplmgt.repository.AttendanceImportRowRepository;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.AttendanceStatusRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.HistoricalImportCodes;
import com.emplmgt.util.HistoricalRosterParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
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

    private static final DateTimeFormatter MONTH_FMT =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final AttendanceStatusRepository statusRepository;
    private final ImportEmployeeRepository importEmployeeRepository;
    private final AttendanceRecordRepository recordRepository;
    private final AttendanceImportHistoryRepository historyRepository;
    private final AttendanceImportRowRepository rowRepository;
    private final AuditService auditService;
    private final AppClock appClock;
    private final ObjectMapper objectMapper;

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
                        .build();
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
                        "Sheet skipped (not an attendance sheet): " + s.skipReason(),
                        s.sheetName(), null, null, 1));
                continue;
            }
            String type;
            String message;
            switch (s.skipReason() == null ? "" : s.skipReason()) {
                case "no date columns" -> {
                    type = T_NO_DATES;
                    message = "No date columns found";
                }
                case "cannot determine month" -> {
                    type = T_NO_MONTH;
                    message = "Attendance uses day numbers but the month could not be determined";
                }
                case "no data rows" -> {
                    boolean noEmp = s.warnings().stream().anyMatch(w -> w.contains("no employee id"));
                    type = noEmp ? T_NO_EMP : T_NO_ROWS;
                    message = noEmp ? "No employee identifier found" : "No data rows found";
                }
                default -> {
                    type = T_READABLE;
                    message = "Completely unreadable sheet";
                }
            }
            issues.add(new HistoricalImportDtos.ValidationIssue(ERR, type, message,
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

        return mergeIssues(issues);
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
                    Boolean.TRUE.equals(r.getIsUnknown()), r.getEmployeeLocation(), r.getEmployeeShift()));
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

    // ------------------------------------------------------------------ COMMIT

    @Transactional
    public HistoricalImportDtos.CommitResponse commit(Long importId, Long teamId, Long adminUserId) {
        AttendanceImportHistory h = load(importId);
        if ("COMMITTED".equals(h.getStatus())) {
            return new HistoricalImportDtos.CommitResponse(h.getId(), h.getFileName(), h.getOriginalFileName(),
                    h.getStatus(), toSummary(h, readSnapshot(h).analysis()),
                    h.getInsertedRecords() + h.getUpdatedRecords(),
                    resultOf(h));
        }
        if (!"PREVIEWED".equals(h.getStatus())) {
            throw ApiException.conflict("Import must be previewed before it can be committed");
        }
        if (h.getErrors() != null && h.getErrors() > 0) {
            throw ApiException.conflict("Cannot commit: the workbook has " + h.getErrors()
                    + " fatal error(s). Fix or remove the affected sheet(s) first.");
        }

        List<AttendanceImportRow> rows = rowRepository
                .findByImportHistoryIdAndActionInOrderByIdAsc(importId, COMMITTABLE);
        int inserted = 0, updated = 0, failed = 0;
        int unknown = 0;
        Set<String> employees = new LinkedHashSet<>();
        Instant now = appClock.now();

        for (AttendanceImportRow r : rows) {
            if (r.getAttendanceDate() == null || r.getIncomingStatus() == null) {
                failed++;
                continue;
            }
            employees.add(r.getEmployeeId());
            if (Boolean.TRUE.equals(r.getIsUnknown())) {
                unknown++;
            }
            upsertEmployee(r, teamId, now);
            AttendanceRecord rec = recordRepository
                    .findByEmployeeIdAndAttendanceDate(r.getEmployeeId(), r.getAttendanceDate())
                    .orElse(null);
            boolean isNew = rec == null;
            if (rec == null) {
                rec = new AttendanceRecord();
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
            if (isNew) {
                rec.setImportedAt(now);
                inserted++;
            } else {
                updated++;
            }
            rec.setUpdatedAt(now);
            recordRepository.save(rec);
        }

        h.setInsertedRecords(inserted);
        h.setUpdatedRecords(updated);
        h.setUnknownCodes(unknown);
        h.setStatus(AttendanceImportHistory.ImportStatus.COMMITTED.name());
        historyRepository.save(h);

        auditService.record("HISTORICAL_IMPORT_COMMITTED", "AttendanceImportHistory", String.valueOf(h.getId()),
                null, Map.of("file", h.getOriginalFileName() == null ? h.getFileName() : h.getOriginalFileName(),
                        "inserted", inserted, "updated", updated));

        return new HistoricalImportDtos.CommitResponse(h.getId(), h.getFileName(), h.getOriginalFileName(),
                h.getStatus(), toSummary(h, readSnapshot(h).analysis()), inserted + updated,
                new HistoricalImportDtos.ImportResult(employees.size(), h.getRecordsDetected(), inserted,
                        updated, h.getDuplicateRecords(), h.getWarnings(), unknown, failed + h.getInvalidRows()));
    }

    private static HistoricalImportDtos.ImportResult resultOf(AttendanceImportHistory h) {
        return new HistoricalImportDtos.ImportResult(
                h.getEmployeesDetected(), h.getRecordsDetected(), h.getInsertedRecords(), h.getUpdatedRecords(),
                h.getDuplicateRecords(), h.getWarnings(),
                h.getUnknownCodes() == null ? 0 : h.getUnknownCodes(),
                h.getInvalidRows() == null ? 0 : h.getInvalidRows());
    }

    /**
     * Upsert the employee master snapshot. Master metadata is separately
     * normalised (whitespace collapsed, email lower-cased) and only filled
     * when currently empty — the first seen month wins, so later monthly
     * rosters never blindly overwrite master information. Each attendance
     * record still carries the metadata applicable to its own monthly roster.
     */
    private void upsertEmployee(AttendanceImportRow r, Long teamId, Instant now) {
        ImportEmployee emp = importEmployeeRepository.findById(r.getEmployeeId()).orElse(null);
        boolean isNew = emp == null;
        if (emp == null) {
            emp = ImportEmployee.builder().employeeId(r.getEmployeeId()).build();
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
        if (isNew || isBlank(emp.getDefaultShift())) {
            String shift = normaliseEmployeeValue(r.getEmployeeShift());
            if (shift != null) {
                emp.setDefaultShift(shift);
                touched = true;
            }
        }
        if (isNew || isBlank(emp.getWeekOff())) {
            String weekOff = normaliseEmployeeValue(r.getEmployeeWeekOff());
            if (weekOff != null) {
                emp.setWeekOff(weekOff);
                touched = true;
            }
        }
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
                        r.getSourceFile(), r.getImportedAt())).toList();

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