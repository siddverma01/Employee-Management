package com.emplmgt.service;

import com.emplmgt.dto.RosterDtos;
import com.emplmgt.entity.AttendanceRoster;
import com.emplmgt.entity.Department;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.AttendanceRosterRepository;
import com.emplmgt.repository.DepartmentRepository;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.JsonUtil;
import com.emplmgt.util.RosterStatusCodes;
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
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class RosterService {

    private static final List<DateTimeFormatter> DATE_PATTERNS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("MM/dd/yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("dd.MM.yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("d-M-yyyy"),
            DateTimeFormatter.ofPattern("dd/MMM/yyyy"),
            DateTimeFormatter.ofPattern("d-MMM-yyyy"),
            DateTimeFormatter.ofPattern("MMM d, yyyy"),
            DateTimeFormatter.ofPattern("d-MMM-yyyy"));

    private static final Pattern DAY_NUMBER = Pattern.compile("^([0-2]?\\d|3[01])$");
    private static final Pattern MONTH_EMBEDDED =
            Pattern.compile("(?i)\\b(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?"
                    + "|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
                    + "\\b[^\\d]{0,4}(\\d{2,4})");
    private static final Map<String, Integer> MONTH_NAME = monthNameMap();

    private static final Set<String> CODE_ALIASES = Set.of(
            "employeeid", "empid", "empno", "employeecode", "employeeno", "employeenumber",
            "employeeidnumber", "eid", "employeecodeid");
    private static final Set<String> EMAIL_ALIASES = Set.of(
            "email", "emailid", "mailid", "mail", "emailaddress", "e-mail");
    private static final Set<String> NAME_ALIASES = Set.of(
            "name", "employeename", "fullname", "employeefullname", "username", "employee");
    private static final Set<String> LOCATION_ALIASES = Set.of(
            "location", "baselocation", "worklocation", "office", "officecity", "city",
            "workcity", "site", "baseoffice");
    private static final Set<String> SHIFT_ALIASES = Set.of(
            "shift", "shifttiming", "shifttimings", "shiftname", "shiftid", "shiftcode", "shiftstart");
    private static final Set<String> WEEKOFF_ALIASES = Set.of(
            "weekoff", "weekoffday", "weeklyoff", "weeklyoffday", "woff", "offday", "wkoff");
    private static final Set<String> SKIPPED_HEADERS = Set.of(
            "srno", "slno", "serialno", "sno", "num", "number", "row", "s/n", "id");

    private final AttendanceRosterRepository rosterRepository;
    private final DepartmentRepository departmentRepository;
    private final AuditService auditService;
    private final JsonUtil jsonUtil;
    private final AppClock appClock;

    @Value("${application.import.upload-dir:./data/uploads}")
    private String uploadDir;

    // ------------------------------------------------------------- PREVIEW

    @Transactional
    public RosterDtos.ImportPreviewResponse preview(Long teamId, MultipartFile file, Long adminUserId) {
        Department team = requireTeam(teamId);
        String original = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        if (!isExcel(original)) {
            throw ApiException.badRequest("Only .xlsx and .xls files are supported");
        }
        if (file.getSize() == 0) {
            throw ApiException.badRequest("Uploaded file is empty");
        }

        ParsedGrid grid;
        try (InputStream in = file.getInputStream()) {
            grid = parseWorkbook(in, original);
        } catch (IOException e) {
            throw ApiException.badRequest("Failed to read the uploaded file: " + e.getMessage());
        }
        if (grid.month() == null) {
            throw ApiException.badRequest("Could not determine the roster month — add the month (e.g. \"September 2026\" or \"Sep 2026\") as a header row or sheet name.");
        }
        if (grid.rows().isEmpty()) {
            throw ApiException.badRequest("Roster has no employee rows");
        }

        // Persist the file for traceability (reuses the Excel import upload dir).
        String storedName = UUID.randomUUID() + "-" + sanitize(original);
        try {
            Path dir = Files.createDirectories(Paths.get(uploadDir));
            Files.copy(file.getInputStream(), dir.resolve(storedName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw ApiException.badRequest("Failed to store uploaded file: " + e.getMessage());
        }

        Set<String> seenInFile = new HashSet<>();
        List<RosterDtos.RowView> views = new ArrayList<>();
        int newRows = 0, existingRows = 0, invalidRows = 0;
        Set<String> usedStatuses = new TreeSet<>();
        for (RawRow raw : grid.rows()) {
            List<String> errors = new ArrayList<>();
            String employeeCode = raw.employeeCode() == null ? null : raw.employeeCode().trim();

            String status;
            if (employeeCode == null || employeeCode.isBlank()) {
                status = "INVALID";
                errors.add("Employee ID is missing");
            } else if (!seenInFile.add(employeeCode)) {
                status = "DUPLICATE";
                errors.add("Duplicate employee in file");
            } else if (rosterRepository.existsByTeamIdAndMonthAndEmployeeCode(teamId, grid.month(), employeeCode)) {
                status = "EXISTING";
                errors.add("Roster already exists for this employee & month — import will skip it");
            } else {
                status = "NEW";
            }

            Map<String, String> days = new LinkedHashMap<>();
            for (Map.Entry<LocalDate, String> e : raw.days().entrySet()) {
                String code = RosterStatusCodes.normalize(e.getValue());
                if (code == null && e.getValue() != null && !e.getValue().isBlank()) {
                    errors.add("Unrecognised status \"" + e.getValue() + "\" on " + e.getKey() + " (left blank)");
                }
                if (code != null) {
                    usedStatuses.add(code);
                }
                days.put(e.getKey().toString(), code == null ? "" : code);
            }

            if ("NEW".equals(status) || "DUPLICATE".equals(status) || "EXISTING".equals(status)) {
                if ("NEW".equals(status)) newRows++;
                if ("EXISTING".equals(status)) existingRows++;
                if ("DUPLICATE".equals(status)) invalidRows++;
            } else {
                invalidRows++;
            }

            views.add(new RosterDtos.RowView(null, teamId, team.getName(), grid.month(),
                    employeeCode, raw.email(), raw.employeeName(), raw.location(), raw.shift(), raw.weekOff(),
                    days, status, errors));
        }

        auditService.record("ROSTER_IMPORT_PREVIEWED", "AttendanceRoster", teamId + "@" + grid.month(),
                null, Map.of("file", original, "team", team.getName(), "month", grid.month(),
                        "rows", grid.rows().size(), "new", newRows, "existing", existingRows));

        return new RosterDtos.ImportPreviewResponse(teamId, team.getName(), grid.month(), original,
                grid.rows().size(), newRows, existingRows, invalidRows, views, List.copyOf(usedStatuses), grid.warnings());
    }

    // ----------------------------------------------------------------- SAVE

    @Transactional
    public RosterDtos.SaveResponse save(RosterDtos.SaveRequest request, Long adminUserId) {
        Department team = requireTeam(request.teamId());
        String month = normalizeMonth(request.month());

        int imported = 0, updated = 0, skipped = 0;
        List<String> errors = new ArrayList<>();
        Set<String> inRequest = new HashSet<>();

        for (RosterDtos.RowItem item : request.rows()) {
            String employeeCode = item.employeeCode() == null ? null : item.employeeCode().trim();
            String employeeName = item.employeeName() == null ? null : item.employeeName().trim();

            if (employeeCode == null || employeeCode.isBlank()) {
                errors.add("Employee ID is missing for a row");
                skipped++;
                continue;
            }
            if (!inRequest.add(employeeCode)) {
                errors.add("Duplicate employee in request: " + employeeCode);
                skipped++;
                continue;
            }

            Map<String, String> days = normalizeDays(item.days());

            if (request.mode() == RosterDtos.SaveMode.IMPORT) {
                if (rosterRepository.existsByTeamIdAndMonthAndEmployeeCode(team.getId(), month, employeeCode)) {
                    skipped++;
                    continue;
                }
            }

            boolean existed = false;
            AttendanceRoster roster = rosterRepository
                    .findByTeamIdAndMonthAndEmployeeCode(team.getId(), month, employeeCode)
                    .orElse(null);
            if (roster == null && item.id() != null) {
                roster = rosterRepository.findById(item.id())
                        .filter(r -> r.getTeam().getId().equals(team.getId()) && r.getMonth().equals(month))
                        .orElse(null);
            }
            if (roster == null) {
                roster = AttendanceRoster.builder()
                        .team(team)
                        .month(month)
                        .employeeCode(employeeCode)
                        .days("{}")
                        .build();
                existed = false;
            } else {
                existed = true;
            }

            roster.setEmployeeCode(employeeCode);
            roster.setEmployeeName(employeeName == null ? roster.getEmployeeName() : employeeName);
            roster.setEmail(blankToNull(item.email()));
            roster.setLocation(blankToNull(item.location()));
            roster.setShift(blankToNull(item.shift()));
            roster.setWeekOff(blankToNull(item.weekOff()));
            roster.setDays(jsonUtil.write(new LinkedHashMap<>(days == null ? Map.of() : days)));
            rosterRepository.save(roster);

            if (existed) updated++;
            else imported++;
        }

        auditService.record("ROSTER_SAVED", "AttendanceRoster", team.getId() + "@" + month,
                null, Map.of("team", team.getName(), "month", month, "mode", request.mode().name(),
                        "imported", imported, "updated", updated, "skipped", skipped));
        return new RosterDtos.SaveResponse(team.getId(), month, imported, updated, skipped, errors);
    }

    // ----------------------------------------------------------------- GET

    @Transactional(readOnly = true)
    public RosterDtos.MonthResponse get(Long teamId, String month) {
        String m = normalizeMonth(month);
        List<AttendanceRoster> rosters = teamId == null
                ? rosterRepository.findByMonth(m)
                : rosterRepository.findByTeamIdAndMonth(requireTeam(teamId).getId(), m);

        Map<Long, String> teamNames = new HashMap<>();
        rosters.forEach(r -> {
            if (r.getTeam() != null) teamNames.putIfAbsent(r.getTeam().getId(), r.getTeam().getName());
        });

        String teamName = teamId != null ? requireTeam(teamId).getName() : null;
        List<RosterDtos.RowView> rows = rosters.stream()
                .map(r -> new RosterDtos.RowView(r.getId(), r.getTeam().getId(),
                        teamNames.getOrDefault(r.getTeam().getId(), null), m,
                        r.getEmployeeCode(), r.getEmail(), r.getEmployeeName(), r.getLocation(),
                        r.getShift(), r.getWeekOff(), readDays(r.getDays()), "SAVED", List.of()))
                .sorted(Comparator.comparing((RosterDtos.RowView v) -> v.teamName() == null ? "" : v.teamName())
                        .thenComparing(v -> v.employeeName() == null ? "" : v.employeeName(),
                                String.CASE_INSENSITIVE_ORDER))
                .toList();

        Set<String> used = new TreeSet<>();
        rows.forEach(r -> r.days().values().forEach(c -> {
            if (c != null && !c.isBlank()) used.add(c);
        }));
        return new RosterDtos.MonthResponse(teamId, teamName, m, rows, List.copyOf(used));
    }

    @Transactional(readOnly = true)
    public List<String> months(Long teamId) {
        List<String> months = teamId == null
                ? rosterRepository.findAllMonths()
                : rosterRepository.findMonthsByTeamId(requireTeam(teamId).getId());
        return months.stream().sorted(Comparator.reverseOrder()).toList();
    }

    @Transactional
    public void deleteRow(Long id) {
        AttendanceRoster roster = rosterRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Roster row not found"));
        String key = roster.getTeam().getId() + "@" + roster.getMonth() + "#" + roster.getEmployeeCode();
        rosterRepository.delete(roster);
        auditService.record("ROSTER_ROW_DELETED", "AttendanceRoster", key,
                Map.of("employeeCode", roster.getEmployeeCode(), "team", roster.getTeam().getName(),
                        "month", roster.getMonth()),
                null);
    }

    // ------------------------------------------------------------ PARSING

    private record RawRow(String employeeCode, String email, String employeeName, String location,
                          String shift, String weekOff, Map<LocalDate, String> days) {
    }

    private record ParsedGrid(String month, List<RawRow> rows, List<String> warnings) {
    }

    private ParsedGrid parseWorkbook(InputStream in, String fileName) throws IOException {
        Workbook workbook;
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".xls")) {
            workbook = new HSSFWorkbook(in);
        } else {
            workbook = new XSSFWorkbook(in);
        }
        try (workbook) {
            Sheet sheet = workbook.getSheetAt(0);
            String sheetName = sheet.getSheetName() == null ? "" : sheet.getSheetName();

            // Materialise the sheet as a list of rows (trimmed trailing blanks).
            List<List<String>> matrix = new ArrayList<>();
            int maxCols = 0;
            for (Row row : sheet) {
                List<String> values = new ArrayList<>();
                int last = -1;
                for (int c = 0; c <= row.getLastCellNum(); c++) {
                    String v = cellString(row.getCell(c));
                    values.add(v);
                    if (v != null && !v.isBlank()) last = c;
                }
                if (last >= 0) {
                    List<String> trimmed = values.subList(0, last + 1);
                    matrix.add(trimmed);
                    maxCols = Math.max(maxCols, trimmed.size());
                }
            }
            if (matrix.isEmpty()) {
                throw ApiException.badRequest("Spreadsheet has no data rows");
            }

            int headerIdx = findHeaderRow(matrix);
            if (headerIdx < 0) {
                throw ApiException.badRequest("Could not detect a monthly roster layout — expected date columns (e.g. 1..31) followed by status codes (WO, WFO, WFH, PL, SL, CO, FL, HPEH, ATRn).");
            }
            List<String> header = matrix.get(headerIdx);

            List<String> warnings = new ArrayList<>();
            YearMonth yearMonth = resolveYearMonth(matrix, headerIdx, header, sheetName, warnings);
            if (yearMonth == null) {
                throw ApiException.badRequest("Could not determine the roster month from the file — add a month header (e.g. \"September 2026\") or name the sheet with the month.");
            }

            FixedColumns fixed = mapFixedColumns(header);
            if (fixed.codeIdx() == null) {
                throw ApiException.badRequest("Could not find the Employee ID column (expected headers like \"Emp ID\", \"Employee ID\", \"Employee Code\").");
            }

            List<DayCol> dayCols = buildDayColumns(header, yearMonth);
            if (dayCols.isEmpty()) {
                throw ApiException.badRequest("Could not find any day columns for the roster month in the header row.");
            }

            String month = yearMonth.toString();
            List<RawRow> rows = new ArrayList<>();
            for (int i = headerIdx + 1; i < matrix.size(); i++) {
                List<String> r = matrix.get(i);
                if (cellAt(r, fixed.codeIdx()) == null && cellAt(r, fixed.nameIdx() == null ? fixed.codeIdx() : fixed.nameIdx()) == null) {
                    continue;
                }
                Map<LocalDate, String> days = new LinkedHashMap<>();
                for (DayCol dc : dayCols) {
                    String v = cellAt(r, dc.col());
                    days.put(dc.date(), v == null ? "" : v);
                }
                rows.add(new RawRow(
                        cellAt(r, fixed.codeIdx()),
                        fixed.emailIdx() == null ? null : cellAt(r, fixed.emailIdx()),
                        fixed.nameIdx() == null ? null : cellAt(r, fixed.nameIdx()),
                        fixed.locationIdx() == null ? null : cellAt(r, fixed.locationIdx()),
                        fixed.shiftIdx() == null ? null : cellAt(r, fixed.shiftIdx()),
                        fixed.weekOffIdx() == null ? null : cellAt(r, fixed.weekOffIdx()),
                        days));
            }
            return new ParsedGrid(month, rows, warnings);
        }
    }

    private record FixedColumns(Integer codeIdx, Integer emailIdx, Integer nameIdx, Integer locationIdx,
                                Integer shiftIdx, Integer weekOffIdx) {
    }

    private record DayCol(int col, LocalDate date) {
    }

    private int findHeaderRow(List<List<String>> matrix) {
        int limit = Math.min(matrix.size(), 15);
        for (int i = 0; i < limit; i++) {
            int dateish = 0;
            for (String v : matrix.get(i)) {
                if (isDateish(v)) dateish++;
            }
            if (dateish >= 3) {
                return i;
            }
        }
        return -1;
    }

    private boolean isDateish(String v) {
        if (v == null || v.isBlank()) return false;
        return parseFullDate(v) != null || dayNumber(v) != null;
    }

    private Integer dayNumber(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (!DAY_NUMBER.matcher(t).matches()) return null;
        return Integer.parseInt(t);
    }

    private LocalDate parseFullDate(String v) {
        if (v == null || v.isBlank()) return null;
        String t = v.trim();
        for (DateTimeFormatter f : DATE_PATTERNS) {
            try {
                return LocalDate.parse(t, f);
            } catch (Exception ignored) {
                // try next
            }
        }
        return null;
    }

    private YearMonth resolveYearMonth(List<List<String>> matrix, int headerIdx, List<String> header,
                                       String sheetName, List<String> warnings) {
        // 1. Any day header that is a full date carries its own month/year.
        for (String v : header) {
            LocalDate d = parseFullDate(v);
            if (d != null) {
                return YearMonth.from(d);
            }
        }
        // 2. Explicit month strings ("Sep 2026", "09/2026", "2026-09") above the header.
        for (int i = 0; i < headerIdx; i++) {
            for (String v : matrix.get(i)) {
                YearMonth ym = parseYearMonth(v);
                if (ym != null) return ym;
            }
        }
        // 3. Month embedded in any free text above the header.
        for (int i = 0; i < headerIdx; i++) {
            for (String v : matrix.get(i)) {
                YearMonth ym = parseEmbeddedMonth(v);
                if (ym != null) return ym;
            }
        }
        // 4. Sheet name.
        YearMonth ym = parseYearMonth(sheetName);
        if (ym == null) ym = parseEmbeddedMonth(sheetName);
        if (ym != null) {
            if (ym.getYear() < 100) {
                return YearMonth.of(appClock.today().getYear(), ym.getMonthValue());
            }
            return ym;
        }
        // 5. Fallback: current month (the file only has day numbers).
        YearMonth now = YearMonth.from(appClock.today());
        warnings.add("Month not stated in the file — assuming the current month " + now);
        return now;
    }

    private YearMonth parseYearMonth(String v) {
        if (v == null || v.isBlank()) return null;
        String t = v.trim();
        List<DateTimeFormatter> patterns = List.of(
                DateTimeFormatter.ofPattern("MM/yyyy"), DateTimeFormatter.ofPattern("MM-yyyy"),
                DateTimeFormatter.ofPattern("MM.yyyy"), DateTimeFormatter.ofPattern("MMM yyyy"),
                DateTimeFormatter.ofPattern("MMMM yyyy"), DateTimeFormatter.ofPattern("MMM-yyyy"),
                DateTimeFormatter.ofPattern("MMM/yyyy"), DateTimeFormatter.ofPattern("MMMM yyyy"),
                DateTimeFormatter.ofPattern("MMM yy"), DateTimeFormatter.ofPattern("yyyy-MM"));
        for (DateTimeFormatter f : patterns) {
            try {
                return YearMonth.parse(t, f);
            } catch (Exception ignored) {
                // try next
            }
        }
        return parseEmbeddedMonth(v);
    }

    private YearMonth parseEmbeddedMonth(String v) {
        if (v == null || v.isBlank()) return null;
        Matcher m = MONTH_EMBEDDED.matcher(v);
        if (!m.find()) return null;
        Integer month = MONTH_NAME.get(m.group(1).toLowerCase(Locale.ROOT));
        if (month == null) return null;
        String yearStr = m.group(2);
        int year;
        try {
            year = Integer.parseInt(yearStr);
        } catch (NumberFormatException e) {
            year = appClock.today().getYear();
        }
        if (year < 100) {
            year += (year < 70 ? 2000 : 1900);
        }
        return YearMonth.of(year, month);
    }

    private FixedColumns mapFixedColumns(List<String> header) {
        Integer code = null, email = null, name = null, loc = null, shift = null, weekoff = null;
        for (int c = 0; c < header.size(); c++) {
            String norm = norm(header.get(c));
            if (norm.isEmpty() || SKIPPED_HEADERS.contains(norm)) continue;
            if (code == null && CODE_ALIASES.contains(norm)) code = c;
            else if (email == null && EMAIL_ALIASES.contains(norm)) email = c;
            else if (name == null && NAME_ALIASES.contains(norm)) name = c;
            else if (loc == null && LOCATION_ALIASES.contains(norm)) loc = c;
            else if (shift == null && SHIFT_ALIASES.contains(norm)) shift = c;
            else if (weekoff == null && WEEKOFF_ALIASES.contains(norm)) weekoff = c;
        }
        return new FixedColumns(code, email, name, loc, shift, weekoff);
    }

    private List<DayCol> buildDayColumns(List<String> header, YearMonth yearMonth) {
        Map<LocalDate, DayCol> byDate = new TreeMap<>();
        for (int c = 0; c < header.size(); c++) {
            String v = header.get(c);
            LocalDate full = parseFullDate(v);
            LocalDate date = full;
            if (date == null) {
                Integer day = dayNumber(v);
                if (day != null && day <= yearMonth.lengthOfMonth()) {
                    date = yearMonth.atDay(day);
                }
            }
            if (date != null) {
                byDate.putIfAbsent(date, new DayCol(c, date));
            }
        }
        return new ArrayList<>(byDate.values());
    }

    private Map<String, String> normalizeDays(Map<String, String> days) {
        if (days == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : days.entrySet()) {
            String code = RosterStatusCodes.normalize(e.getValue());
            if (code != null) out.put(e.getKey(), code);
        }
        return out;
    }

    private Map<String, String> readDays(String json) {
        Map<String, Object> raw = jsonUtil.read(json);
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(k, v == null ? "" : String.valueOf(v)));
        return out;
    }

    private String cellString(Cell cell) {
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    try {
                        return cell.getLocalDateTimeCellValue().toLocalDate().toString();
                    } catch (Exception e) {
                        return null;
                    }
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
                    try {
                        return cell.getCellFormula();
                    } catch (Exception e2) {
                        return null;
                    }
                }
            default: return null;
        }
    }

    private String cellAt(List<String> row, int idx) {
        if (idx < 0 || row == null || idx >= row.size()) return null;
        String v = row.get(idx);
        if (v == null || v.isBlank()) return null;
        return v.trim();
    }

    private String norm(String v) {
        if (v == null) return "";
        return v.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private Department requireTeam(Long teamId) {
        if (teamId == null) {
            throw ApiException.badRequest("A team must be selected");
        }
        return departmentRepository.findById(teamId)
                .orElseThrow(() -> ApiException.notFound("Team not found"));
    }

    private String normalizeMonth(String month) {
        if (month == null || month.isBlank()) {
            throw ApiException.badRequest("Month is required (yyyy-MM)");
        }
        try {
            return YearMonth.parse(month.trim()).toString();
        } catch (Exception e) {
            throw ApiException.badRequest("Invalid month format: " + month);
        }
    }

    private String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private boolean isExcel(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static Map<String, Integer> monthNameMap() {
        Map<String, Integer> m = new HashMap<>();
        String[] names = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
        for (int i = 0; i < names.length; i++) {
            m.put(names[i], i + 1);
        }
        return m;
    }
}