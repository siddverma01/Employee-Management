package com.emplmgt.service;

import com.emplmgt.dto.ImportDtos;
import com.emplmgt.entity.*;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.*;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExcelImportService {

    private enum Field { EMPLOYEE_CODE, ATTENDANCE_DATE, ATTENDANCE_TYPE, REMARKS }

    /** Numeric date convention used by a column, detected per column so files that use
     * dd/MM/yyyy are never silently mis-read as MM/dd/yyyy (and vice-versa). */
    private enum DateFamily { ANY, DD_MM, MM_DD }

    private static final List<String> ISO_DATES = List.of(
            "yyyy-MM-dd", "yyyy/MM/dd", "yyyy-MM");

    private static final List<String> MONTH_NAME_DATES = List.of(
            "dd-MMM-yyyy", "d-MMM-yyyy", "dd-MMM-yy", "d-MMM-yy",
            "dd MMM yyyy", "d MMM yyyy", "MMM dd, yyyy", "MMM d, yyyy",
            "dd MMMM yyyy", "d MMMM yyyy", "MMMM d, yyyy", "MMM d yyyy");

    private static final List<String> DD_MM_DATES = List.of(
            "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "d-M-yyyy",
            "dd.MM.yyyy", "d.M.yyyy", "dd/MM/yy", "d/M/yy");

    private static final List<String> MM_DD_DATES = List.of(
            "MM/dd/yyyy", "M/d/yyyy", "MM-dd-yyyy", "M-d-yyyy",
            "MM/dd/yy", "M/d/yy");

    private static final String ET_MISSING = "MISSING_VALUE";
    private static final String ET_UNRECOGNISED_DATE = "UNRECOGNISED_DATE";
    private static final String ET_UNRECOGNISED_TYPE = "UNRECOGNISED_TYPE";
    private static final String ET_UNKNOWN_EMPLOYEE = "UNKNOWN_EMPLOYEE";
    private static final String ET_DUPLICATE_FILE = "DUPLICATE_IN_FILE";
    private static final String ET_DUPLICATE_DB = "DUPLICATE_IN_DB";

    /** Collects user-facing messages (legacy) plus structured per-cell {@link ImportDtos.ImportError}s. */
    private static final class RowValidation {
        final List<String> messages = new ArrayList<>();
        final List<ImportDtos.ImportError> details = new ArrayList<>();

        void fail(String sheet, Integer row, String column, String employeeId, String employeeName,
                  String date, String rawValue, String errorType, String message) {
            messages.add(message);
            details.add(new ImportDtos.ImportError(
                    sheet, row, column, employeeId, employeeName, date, rawValue, errorType, message));
        }
    }

    public static final Map<Field, String> FIELD_KEY = Map.of(
            Field.EMPLOYEE_CODE, "employeeCode",
            Field.ATTENDANCE_DATE, "attendanceDate",
            Field.ATTENDANCE_TYPE, "attendanceType",
            Field.REMARKS, "remarks");

    private final ExcelImportRepository excelImportRepository;
    private final ExcelImportRowRepository excelImportRowRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceRepository attendanceRepository;
    private final AuditService auditService;
    private final JsonUtil jsonUtil;
    private final AppClock appClock;

    @Value("${application.import.upload-dir:./data/uploads}")
    private String uploadDir;

    // ---------------------------------------------------------------- UPLOAD

    @Transactional
    public ImportDtos.UploadResponse upload(MultipartFile file, Long adminUserId) {
        String original = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        if (!isExcel(original)) {
            throw ApiException.badRequest("Only .xlsx and .xls files are supported");
        }
        if (file.getSize() == 0) {
            throw ApiException.badRequest("Uploaded file is empty");
        }

        ParsedWorkbook parsed;
        try (InputStream in = file.getInputStream()) {
            parsed = parseWorkbook(in, original);
        } catch (IOException e) {
            throw ApiException.badRequest("Failed to read the uploaded file: " + e.getMessage());
        }

        if (parsed.headers().isEmpty()) {
            throw ApiException.badRequest("Spreadsheet has no columns");
        }
        if (parsed.rows().isEmpty()) {
            throw ApiException.badRequest("Spreadsheet has no data rows");
        }

        // Persist the file for later parsing steps.
        String storedName = UUID.randomUUID() + "-" + sanitize(original);
        try {
            Path dir = Files.createDirectories(Paths.get(uploadDir));
            Files.copy(file.getInputStream(), dir.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw ApiException.badRequest("Failed to store uploaded file: " + e.getMessage());
        }

        ExcelImport excelImport = ExcelImport.builder()
                .fileName(storedName)
                .originalFileName(original)
                .uploadedAt(appClock.now())
                .uploadedBy(employeeRepository.findByUserId(adminUserId).map(Employee::getUser).orElse(null))
                .status(ExcelImportStatus.UPLOADED)
                .totalRows(parsed.rows().size())
                .build();
        excelImport = excelImportRepository.save(excelImport);

        auditService.record("EXCEL_UPLOADED", "ExcelImport", String.valueOf(excelImport.getId()),
                null, Map.of("file", original, "rows", parsed.rows().size()));

        return new ImportDtos.UploadResponse(excelImport.getId(), storedName, original, ExcelImportStatus.UPLOADED,
                parsed.headers(), parsed.rows().size(), suggestMapping(parsed.headers()));
    }

    // -------------------------------------------------------------- MAPPING+VALIDATION

    @Transactional
    public ImportDtos.PreviewResponse mapAndPreview(Long importId, Map<String, Object> mapping) {
        ExcelImport excelImport = load(importId);
        if (excelImport.getStatus() == ExcelImportStatus.IMPORTED) {
            throw ApiException.conflict("This import has already been committed");
        }

        ParsedWorkbook parsed = readStoredFile(excelImport.getFileName());

        Map<Field, Integer> resolved = resolveMapping(mapping, parsed.headers());
        requireMapped(parsed, resolved);

        // Determine the numeric date convention (dd/MM vs MM/dd) once per date column so
        // ambiguous values like "01/09/2025" are never silently read with the wrong meaning.
        DateFamily dateFamily = DateFamily.ANY;
        Integer dateColIdx = resolved.get(Field.ATTENDANCE_DATE);
        if (dateColIdx != null) {
            List<String> dateValues = parsed.rows().stream()
                    .map(pr -> raw(pr, resolved, Field.ATTENDANCE_DATE))
                    .toList();
            dateFamily = detectDateFamily(dateValues);
            log.info("Excel preview [importId={}]: date column [{}] parsed using {} convention",
                    importId, parsed.headers().get(dateColIdx), dateFamily);
        }

        // Drop any previous validation rows for this import.
        excelImportRowRepository.deleteByExcelImportId(importId);

        int valid = 0;
        int invalid = 0;
        int duplicate = 0;
        String sheetName = parsed.sheetName();
        Set<String> seenInFile = new HashSet<>();
        Map<String, Boolean> seenInDb = new HashMap<>();
        for (ParsedRow pr : parsed.rows()) {
            RowValidation v = new RowValidation();
            String empCode = raw(pr, resolved, Field.EMPLOYEE_CODE);
            boolean typeMapped = resolved.containsKey(Field.ATTENDANCE_TYPE);
            String rawDate = raw(pr, resolved, Field.ATTENDANCE_DATE);
            String rawType = typeMapped ? raw(pr, resolved, Field.ATTENDANCE_TYPE) : null;

            LocalDate date = parseDate(rawDate, dateFamily, v, sheetName, pr.rowNumber(),
                    headerAt(resolved, Field.ATTENDANCE_DATE, parsed), empCode, null);
            AttendanceType type = parseType(rawType, typeMapped, v, sheetName, pr.rowNumber(),
                    headerAt(resolved, Field.ATTENDANCE_TYPE, parsed), empCode, date);

            String empName = null;
            Employee employee = null;
            if (empCode == null || empCode.isBlank()) {
                v.fail(sheetName, pr.rowNumber(), headerAt(resolved, Field.EMPLOYEE_CODE, parsed),
                        null, null, date == null ? null : date.toString(), empCode,
                        ET_MISSING, "Employee code is missing");
            } else {
                employee = employeeRepository.findByEmployeeCodeIgnoreCase(empCode.trim()).orElse(null);
                if (employee == null) {
                    v.fail(sheetName, pr.rowNumber(), headerAt(resolved, Field.EMPLOYEE_CODE, parsed),
                            empCode.trim(), null, date == null ? null : date.toString(), empCode.trim(),
                            ET_UNKNOWN_EMPLOYEE, "Unknown employee code: " + empCode.trim());
                } else {
                    empName = employee.getFullName();
                }
            }

            ExcelRowStatus status = ExcelRowStatus.VALID;
            if (v.details.isEmpty() && date != null && employee != null) {
                String key = employee.getId() + "|" + date;
                if (!seenInFile.add(key)) {
                    status = ExcelRowStatus.DUPLICATE;
                    v.fail(sheetName, pr.rowNumber(), null, empCode.trim(), empName, date.toString(),
                            empCode.trim(), ET_DUPLICATE_FILE,
                            "Duplicate row within the file (same employee & date)");
                } else {
                    Employee emp = employee;
                    LocalDate d = date;
                    boolean dbKey = seenInDb.computeIfAbsent(key,
                            k -> attendanceRepository.existsByEmployeeIdAndAttendanceDate(emp.getId(), d));
                    if (dbKey) {
                        status = ExcelRowStatus.DUPLICATE;
                        v.fail(sheetName, pr.rowNumber(), null, empCode.trim(), empName, date.toString(),
                                empCode.trim(), ET_DUPLICATE_DB,
                                "Attendance record already exists for this employee & date");
                    }
                }
            } else if (!v.details.isEmpty()) {
                status = ExcelRowStatus.INVALID;
            }

            switch (status) {
                case VALID -> valid++;
                case INVALID -> invalid++;
                default -> duplicate++;
            }
            if (!v.details.isEmpty()) {
                log.debug("Excel preview [importId={}]: row {} rejected: {} | raw={}",
                        importId, pr.rowNumber(), v.details, pr.values());
            }

            Map<String, Object> rawData = new LinkedHashMap<>();
            for (int i = 0; i < parsed.headers().size(); i++) {
                rawData.put(parsed.headers().get(i), i < pr.values().size() ? pr.values().get(i) : null);
            }
            Map<String, Object> mappedData = new LinkedHashMap<>();
            mappedData.put("employeeCode", empCode);
            mappedData.put("attendanceDate", date != null ? date.toString() : null);
            mappedData.put("attendanceType", type != null ? type.name() : null);
            mappedData.put("remarks", raw(pr, resolved, Field.REMARKS));
            excelImportRowRepository.save(ExcelImportRow.builder()
                    .excelImport(excelImport)
                    .rowNumber(pr.rowNumber())
                    .rawData(jsonUtil.write(rawData))
                    .mappedData(jsonUtil.write(mappedData))
                    .validationErrors(v.messages.isEmpty() ? null : String.join("; ", v.messages))
                    .errorDetails(jsonUtil.writeList(v.details))
                    .rowStatus(status)
                    .employee(employee)
                    .attendanceDate(date)
                    .attendanceType(type)
                    .build());
        }

        log.info("Excel preview [importId={}]: total={}, valid={}, invalid={}, duplicate={}",
                importId, parsed.rows().size(), valid, invalid, duplicate);

        excelImport.setMappingJson(jsonUtil.write(mapping));
        excelImport.setStatus(ExcelImportStatus.READY);
        excelImport.setTotalRows(parsed.rows().size());
        excelImport.setValidRows(valid);
        excelImport.setInvalidRows(invalid);
        excelImport.setDuplicateRows(duplicate);
        excelImport = excelImportRepository.save(excelImport);

        auditService.record("EXCEL_PREVIEWED", "ExcelImport", String.valueOf(importId),
                Map.of("status", "UPLOADED"),
                Map.of("status", "READY", "valid", valid, "invalid", invalid, "duplicate", duplicate));
        return preview(importId);
    }

    // ----------------------------------------------------------------- PREVIEW

    @Transactional(readOnly = true)
    public ImportDtos.PreviewResponse preview(Long importId) {
        ExcelImport excelImport = load(importId);
        List<ExcelImportRow> rows = excelImportRowRepository.findByExcelImportIdOrderByRowNumber(importId);
        List<ImportDtos.RowView> views = rows.stream().map(row -> new ImportDtos.RowView(
                row.getRowNumber(),
                jsonUtil.read(row.getRawData()),
                row.getRowStatus(),
                row.getValidationErrors() == null ? List.of()
                        : java.util.Arrays.stream(row.getValidationErrors().split("; ")).toList(),
                jsonUtil.<ImportDtos.ImportError>readList(row.getErrorDetails(), ImportDtos.ImportError.class)))
                .toList();
        return new ImportDtos.PreviewResponse(
                importId, excelImport.getStatus(), headersOf(excelImport), jsonUtil.read(excelImport.getMappingJson()),
                excelImport.getTotalRows(), excelImport.getValidRows(), excelImport.getInvalidRows(),
                excelImport.getDuplicateRows(), views);
    }

    // ----------------------------------------------------------------- COMMIT

    @Transactional
    public ImportDtos.CommitResponse commit(Long importId, Long adminUserId) {
        ExcelImport excelImport = load(importId);
        if (excelImport.getStatus() != ExcelImportStatus.READY) {
            throw ApiException.conflict("Import is not ready to commit. Map columns and preview first.");
        }
        if (excelImport.getValidRows() == 0) {
            throw ApiException.badRequest("There are no valid rows to import");
        }

        List<ExcelImportRow> validRows = excelImportRowRepository
                .findByExcelImportIdOrderByRowNumber(importId).stream()
                .filter(r -> r.getRowStatus() == ExcelRowStatus.VALID)
                .toList();

        java.util.concurrent.atomic.AtomicInteger imported = new java.util.concurrent.atomic.AtomicInteger();
        for (ExcelImportRow row : validRows) {
            boolean exists = attendanceRepository.existsByEmployeeIdAndAttendanceDate(
                    row.getEmployee().getId(), row.getAttendanceDate());
            if (exists) {
                // Not skipped silently: record why the row could not be committed.
                row.setRowStatus(ExcelRowStatus.DUPLICATE);
                row.setValidationErrors("Attendance record already exists for this employee & date (found at commit time)");
                excelImportRowRepository.save(row);
                log.warn("Excel commit [importId={}]: row {} skipped — record already exists for employee {} on {}",
                        importId, row.getRowNumber(), row.getEmployee().getEmployeeCode(), row.getAttendanceDate());
                continue;
            }
            attendanceRepository.save(Attendance.builder()
                    .employee(row.getEmployee())
                    .attendanceDate(row.getAttendanceDate())
                    .attendanceType(row.getAttendanceType())
                    .source(AttendanceSource.IMPORT)
                    .remarks("Excel import")
                    .build());
            row.setRowStatus(ExcelRowStatus.IMPORTED);
            excelImportRowRepository.save(row);
            imported.incrementAndGet();
        }

        excelImport.setStatus(ExcelImportStatus.IMPORTED);
        excelImport.setImportedRows(imported.get());
        excelImport.setCommittedAt(appClock.now());
        excelImportRepository.save(excelImport);

        auditService.record("EXCEL_IMPORTED", "ExcelImport", String.valueOf(importId),
                Map.of("status", "READY"),
                Map.of("status", "IMPORTED", "importedRows", imported.get(), "skippedDuplicates",
                        excelImport.getValidRows() - imported.get()));
        return new ImportDtos.CommitResponse(importId, ExcelImportStatus.IMPORTED,
                excelImport.getTotalRows(), excelImport.getValidRows(), excelImport.getInvalidRows(),
                excelImport.getDuplicateRows(), imported.get());
    }

    @Transactional(readOnly = true)
    public List<ImportDtos.HistoryItem> history() {
        return excelImportRepository.findAllByOrderByUploadedAtDesc().stream().map(i -> {
                    String uploader = i.getUploadedBy() != null ? i.getUploadedBy().getEmail() : "system";
                    return new ImportDtos.HistoryItem(
                            i.getId(), i.getFileName(), i.getOriginalFileName(), uploader, i.getUploadedAt(),
                            i.getStatus(), i.getTotalRows(), i.getValidRows(), i.getInvalidRows(),
                            i.getDuplicateRows(), i.getImportedRows(), i.getCommittedAt());
                })
                .toList();
    }

    // ---------------------------------------------------------------- HELPERS

    private ExcelImport load(Long importId) {
        return excelImportRepository.findById(importId)
                .orElseThrow(() -> ApiException.notFound("Import not found"));
    }

    private List<String> headersOf(ExcelImport excelImport) {
        ParsedWorkbook parsed = readStoredFile(excelImport.getFileName());
        return parsed.headers();
    }

    private ParsedWorkbook readStoredFile(String fileName) {
        Path path = Paths.get(uploadDir).resolve(fileName);
        if (!Files.exists(path)) {
            throw ApiException.badRequest("Stored file is missing. Please re-upload.");
        }
        try (InputStream in = Files.newInputStream(path)) {
            return parseWorkbook(in, fileName);
        } catch (IOException e) {
            throw ApiException.badRequest("Failed to read stored file: " + e.getMessage());
        }
    }

    private ParsedWorkbook parseWorkbook(InputStream in, String fileName) throws IOException {
        Workbook workbook;
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".xls")) {
            workbook = new HSSFWorkbook(in);
        } else {
            workbook = new XSSFWorkbook(in);
        }
        try (workbook) {
            Sheet sheet = pickDataSheet(workbook);
            if (sheet.getLastRowNum() < 0) {
                return new ParsedWorkbook(sheet.getSheetName(), List.of(), List.of());
            }

            // Scan the first 10 rows to find the true header row instead of
            // assuming row 0 (files often start with a title or blank rows).
            int headerRowIndex = 0;
            int maxColumns = -1;
            int scanLimit = Math.min(sheet.getLastRowNum(), 9);
            for (int i = 0; i <= scanLimit; i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                int filledCells = 0;
                boolean looksLikeHeader = false;
                for (int c = 0; c <= row.getLastCellNum(); c++) {
                    String value = cellString(row.getCell(c));
                    if (value != null && !value.isBlank()) {
                        filledCells++;
                        String lower = value.toLowerCase(Locale.ROOT);
                        if (lower.contains("emp id") || lower.contains("employee")
                                || lower.contains("name") || lower.contains("date")
                                || lower.contains("type")) {
                            looksLikeHeader = true;
                        }
                    }
                }
                if (filledCells >= 2 && looksLikeHeader) {
                    headerRowIndex = i;
                    break;
                }
                if (filledCells > maxColumns) {
                    maxColumns = filledCells;
                    headerRowIndex = i;
                }
            }

            Row headerRow = sheet.getRow(headerRowIndex);
            List<String> headers = new ArrayList<>();
            int headerColumns = headerRow == null ? 0 : headerRow.getLastCellNum();
            for (int c = 0; c < headerColumns; c++) {
                String raw = cellString(headerRow.getCell(c));
                String header = raw == null ? "" : raw.trim();
                headers.add(header.isEmpty() ? "Unnamed Column " + (c + 1) : header);
            }

            log.info("Excel parse [{}]: selected sheet [{}], detected header row (1-based) {}, {} column(s), headers {}",
                    fileName, sheet.getSheetName(), headerRowIndex + 1, headers.size(), headers);

            List<ParsedRow> rows = new ArrayList<>();
            int rowNum = headerRowIndex + 2; // 1-based display, header = row headerRowIndex + 1
            int lastDataRow = sheet.getLastRowNum();
            for (int i = headerRowIndex + 1; i <= lastDataRow; i++) {
                Row row = sheet.getRow(i);
                if (row != null && !isEmpty(row)) {
                    List<String> values = new ArrayList<>();
                    for (int c = 0; c < headers.size(); c++) {
                        values.add(cellString(row.getCell(c)));
                    }
                    rows.add(new ParsedRow(rowNum, values));
                }
                rowNum++;
            }
            return new ParsedWorkbook(sheet.getSheetName(), headers, rows);
        }
    }

    /**
     * Files are often exported with multiple sheets where the first one is a cover/title
     * page. Pick the sheet whose table header contains the most import keywords, falling
     * back to the first sheet when none looks like a data table.
     */
    private Sheet pickDataSheet(Workbook workbook) {
        if (workbook.getNumberOfSheets() <= 1) {
            return workbook.getSheetAt(0);
        }
        Sheet best = null;
        int bestScore = -1;
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);
            int score = headerKeywordScore(sheet);
            if (score > bestScore) {
                bestScore = score;
                best = sheet;
            }
        }
        if (bestScore <= 0) {
            log.info("Excel parse: no sheet with a recognisable data header found; falling back to first sheet");
            best = workbook.getSheetAt(0);
        } else {
            log.info("Excel parse: selected sheet [{}] (header keyword score {}) out of {} sheet(s) ",
                    best.getSheetName(), bestScore, workbook.getNumberOfSheets());
        }
        return best;
    }

    private int headerKeywordScore(Sheet sheet) {
        int scanLimit = Math.min(sheet.getLastRowNum(), 9);
        int best = 0;
        for (int i = 0; i <= scanLimit; i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            int score = 0;
            for (int c = 0; c <= row.getLastCellNum(); c++) {
                String value = cellString(row.getCell(c));
                if (value == null || value.isBlank()) {
                    continue;
                }
                String lower = value.toLowerCase(Locale.ROOT);
                if (lower.contains("emp id") || lower.contains("employee") || lower.contains("emp code")
                        || lower.contains("empno") || lower.contains("date") || lower.contains("day")
                        || lower.contains("type") || lower.contains("status") || lower.contains("name")
                        || lower.contains("week off") || lower.contains("shift") || lower.contains("location")) {
                    score++;
                }
            }
            best = Math.max(best, score);
        }
        return best;
    }

    private String cellString(Cell cell) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                double v = cell.getNumericCellValue();
                if (v == Math.floor(v)) {
                    return String.valueOf((long) v);
                }
                return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                } catch (Exception e) {
                    return cell.getCellFormula();
                }
            default: return null;
        }
    }

    private boolean isEmpty(Row row) {
        for (Cell cell : row) {
            if (cell != null && cell.getCellType() != CellType.BLANK && !cellString(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private Map<String, String> suggestMapping(List<String> headers) {
        Map<String, String> suggested = new LinkedHashMap<>();
        Map<String, String> aliasToField = new LinkedHashMap<>();
        aliasToField.put("employeeid", "employeeCode");
        aliasToField.put("empid", "employeeCode");
        aliasToField.put("empno", "employeeCode");
        aliasToField.put("empcode", "employeeCode");
        aliasToField.put("employeeidno", "employeeCode");
        aliasToField.put("employeecode", "employeeCode");
        aliasToField.put("employeeno", "employeeCode");
        aliasToField.put("employeenumber", "employeeCode");
        aliasToField.put("date", "attendanceDate");
        aliasToField.put("attendancedate", "attendanceDate");
        aliasToField.put("workdate", "attendanceDate");
        aliasToField.put("payrolldate", "attendanceDate");
        aliasToField.put("day", "attendanceDate");
        aliasToField.put("type", "attendanceType");
        aliasToField.put("attendancetype", "attendanceType");
        aliasToField.put("status", "attendanceType");
        aliasToField.put("worktype", "attendanceType");
        aliasToField.put("pstatus", "attendanceType");
        aliasToField.put("worklocation", "attendanceType");
        aliasToField.put("empstatus", "attendanceType");
        aliasToField.put("remarks", "remarks");
        aliasToField.put("remark", "remarks");
        aliasToField.put("comments", "remarks");

        for (String header : headers) {
            if (header == null || header.isBlank()) continue;
            String normalized = header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            String field = aliasToField.get(normalized);
            if (field != null && !suggested.containsValue(field)) {
                suggested.put(header, field);
            }
        }

        // Wide roster grids use date cells as column headers (e.g. "2025-09-01") — suggest
        // the first date-like header as the attendance date column when none was matched above.
        if (!suggested.containsValue("attendanceDate")) {
            for (String header : headers) {
                if (header == null || header.isBlank()) continue;
                if (tryParseDate(header.trim(), DateFamily.ANY) != null) {
                    suggested.put(header, "attendanceDate");
                    break;
                }
            }
        }
        return suggested;
    }

    private Map<Field, Integer> resolveMapping(Map<String, Object> mapping, List<String> headers) {
        Map<Field, Integer> resolved = new EnumMap<>(Field.class);
        if (mapping == null) {
            return resolved;
        }
        for (Map.Entry<String, Object> e : mapping.entrySet()) {
            Field field = FIELD_KEY.entrySet().stream()
                    .filter(kv -> kv.getValue().equals(e.getKey()))
                    .map(Map.Entry::getKey)
                    .findFirst().orElse(null);
            if (field == null) {
                continue;
            }
            String rawValue = String.valueOf(e.getValue());
            Integer idx = resolveColumn(rawValue, headers);
            if (idx != null) {
                resolved.put(field, idx);
            }
        }
        return resolved;
    }

    private Integer resolveColumn(String rawValue, List<String> headers) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        if (rawValue.matches("\\d+")) {
            int idx = Integer.parseInt(rawValue);
            if (idx >= 0 && idx < headers.size()) {
                return idx;
            }
            return null;
        }
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).equalsIgnoreCase(rawValue.trim())) {
                return i;
            }
        }
        return null;
    }

    private void requireMapped(ParsedWorkbook parsed, Map<Field, Integer> resolved) {
        if (!resolved.containsKey(Field.EMPLOYEE_CODE)) {
            throw ApiException.badRequest("Map the 'Employee Code' column before previewing");
        }
        if (!resolved.containsKey(Field.ATTENDANCE_DATE)) {
            throw ApiException.badRequest("Map the 'Attendance Date' column before previewing");
        }
        for (Map.Entry<Field, Integer> e : resolved.entrySet()) {
            if (e.getValue() >= parsed.headers().size()) {
                throw ApiException.badRequest("Column index out of range for " + e.getKey());
            }
        }
    }

    private String raw(ParsedRow row, Map<Field, Integer> resolved, Field field) {
        Integer idx = resolved.get(field);
        return idx == null || idx >= row.values().size() ? null : row.values().get(idx);
    }

    private LocalDate parseDate(String value, DateFamily family, RowValidation v,
                                String sheetName, int row, String column, String employeeId, String employeeName) {
        if (value == null || value.isBlank()) {
            String col = column == null ? "attendance date" : column;
            v.fail(sheetName, row, column, employeeId, employeeName, null, value,
                    ET_MISSING, "Attendance date is missing");
            return null;
        }
        LocalDate parsed = tryParseDate(value.trim(), family);
        if (parsed == null) {
            v.fail(sheetName, row, column, employeeId, employeeName, null, value,
                    ET_UNRECOGNISED_DATE, "Unrecognised date format: " + value);
        }
        return parsed;
    }

    private LocalDate tryParseDate(String v, DateFamily family) {
        List<String> patterns = new ArrayList<>();
        patterns.addAll(ISO_DATES);
        patterns.addAll(MONTH_NAME_DATES);
        switch (family) {
            case DD_MM -> patterns.addAll(DD_MM_DATES);
            case MM_DD -> patterns.addAll(MM_DD_DATES);
            case ANY -> {
                patterns.addAll(DD_MM_DATES);
                patterns.addAll(MM_DD_DATES);
            }
        }
        // Month-name parsing is attempted case-insensitively (upper/lower variants).
        List<String> variants = List.of(v, v.toUpperCase(Locale.ROOT), v.toLowerCase(Locale.ROOT));
        for (String variant : variants) {
            for (String pattern : patterns) {
                try {
                    return java.time.LocalDate.parse(variant,
                            java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH));
                } catch (Exception ignored) {
                    // try next pattern/variant
                }
            }
        }
        return null;
    }

    /**
     * Determines whether a numeric date column should be read as dd/MM or MM/dd.
     * Month values greater than 12 and letter-containing values disambiguate the column;
     * ties default to dd/MM (the local convention).
     */
    private DateFamily detectDateFamily(List<String> dateValues) {
        int ddmm = 0;
        int mmdd = 0;
        for (String value : dateValues) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String t = value.trim();
            if (t.matches(".*\\p{L}.*")) {
                return DateFamily.ANY;
            }
            if (matchesAny(t, DD_MM_DATES)) ddmm++;
            if (matchesAny(t, MM_DD_DATES)) mmdd++;
        }
        if (ddmm == 0 && mmdd == 0) {
            return DateFamily.ANY;
        }
        return ddmm >= mmdd ? DateFamily.DD_MM : DateFamily.MM_DD;
    }

    private boolean matchesAny(String value, List<String> patterns) {
        for (String pattern : patterns) {
            try {
                java.time.LocalDate.parse(value,
                        java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH));
                return true;
            } catch (Exception ignored) {
                // try next
            }
        }
        return false;
    }

    private AttendanceType parseType(String value, boolean columnMapped, RowValidation v,
                                     String sheetName, int row, String column,
                                     String employeeId, LocalDate date) {
        if (!columnMapped) {
            return AttendanceType.WORK_FROM_OFFICE;
        }
        if (value == null || value.isBlank()) {
            v.fail(sheetName, row, column, employeeId, null,
                    date == null ? null : date.toString(), value,
                    ET_MISSING, "Attendance type is missing");
            return null;
        }
        String t = value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        AttendanceType parsed = switch (t) {
            case "wfo", "workfromoffice", "office", "present", "p", "inoffice" -> AttendanceType.WORK_FROM_OFFICE;
            case "wfh", "workfromhome", "home", "remote" -> AttendanceType.WORK_FROM_HOME;
            case "pl", "privilegeleave" -> AttendanceType.PRIVILEGE_LEAVE;
            case "sl", "sickleave" -> AttendanceType.SICK_LEAVE;
            case "co", "compoff", "compensatoryoff" -> AttendanceType.COMP_OFF;
            case "fl", "furlough" -> AttendanceType.FURLOUGH;
            case "wo", "weekoff", "weekoffday" -> AttendanceType.WEEK_OFF;
            case "hpeh", "hpeholiday", "holiday", "hol" -> AttendanceType.HOLIDAY;
            default -> null;
        };
        if (parsed == null) {
            if (t.matches("atr\\d*")) {
                parsed = AttendanceType.ATTRITION;
            } else {
                v.fail(sheetName, row, column, employeeId, null,
                        date == null ? null : date.toString(), value,
                        ET_UNRECOGNISED_TYPE, "Unrecognised attendance type: " + value);
            }
        }
        return parsed;
    }

    private String headerAt(Map<Field, Integer> resolved, Field field, ParsedWorkbook parsed) {
        Integer idx = resolved.get(field);
        return idx == null || idx >= parsed.headers().size() ? null : parsed.headers().get(idx);
    }

    private boolean isExcel(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private record ParsedWorkbook(String sheetName, List<String> headers, List<ParsedRow> rows) {
    }

    private record ParsedRow(int rowNumber, List<String> values) {
    }
}