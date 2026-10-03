package com.emplmgt.service;

import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.AttendanceStatus;
import com.emplmgt.entity.Department;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.Holiday;
import com.emplmgt.entity.AttendanceShiftAssignment;
import com.emplmgt.entity.AttendanceWeekOffAssignment;
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
import com.emplmgt.util.ShiftTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the downloadable Excel roster from live application data.
 *
 * <p>The workbook is deliberately written in the exact shape the historical
 * importer understands ({@link com.emplmgt.util.HistoricalRosterParser}): one
 * title row, one header row of employee metadata columns
 * ({@link #META_HEADERS}) followed by one real date cell per day of the month,
 * and canonical attendance codes in the grid. An exported file can therefore be
 * uploaded straight back through {@code /admin/historical/preview} without
 * reshaping it by hand.</p>
 *
 * <p>Data always comes from the database ({@code import_employees} +
 * {@code attendance_records}), never from the originally uploaded workbook, so
 * edits made in the UI (cell status changes, approved leave, swap-offs) are
 * reflected in what the admin downloads.</p>
 *
 * <p>Round-trip caveat, inherent to the template format rather than to this
 * exporter: an employee with no attendance at all in the month produces a row of
 * blank cells, and the parser skips rows that carry no day values. Nothing is
 * lost — a blank month has nothing to import — but such an employee will not
 * reappear as a row on re-import.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RosterExportService {

    /** What the admin asked for. */
    public enum Scope {
        /** The month currently selected in the roster UI. */
        MONTH,
        /** The calendar month of "today", regardless of the UI selection. */
        CURRENT_MONTH,
        /** Every month of the selected year that holds attendance, one sheet each. */
        FULL_YEAR;

        public static Scope of(String raw) {
            if (raw == null || raw.isBlank()) {
                return MONTH;
            }
            try {
                return Scope.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Unknown export scope '" + raw
                        + "'. Expected one of: MONTH, CURRENT_MONTH, FULL_YEAR");
            }
        }
    }

    public record ExportRequest(Scope scope, String month, Integer year,
                                Long teamId, String q, String location, String shift, String status,
                                boolean includeEmpty) {
    }

    /** A validated request plus the sheet list it implies. */
    public record Plan(Scope scope, YearMonth month, int year, List<YearMonth> sheets,
                       Long teamId, String teamName, String q, String location, String shift,
                       String status, boolean includeEmpty) {
    }

    public record ExportResult(String filename, int sheets, int employeeRows, int statusCells) {
    }

    // ------------------------------------------------------------------ TEMPLATE

    /**
     * Employee metadata headers, in the exact wording (and column order) the
     * importer's alias table recognises. Changing a label here silently breaks
     * round-tripping, so these must stay in sync with
     * {@link com.emplmgt.util.HistoricalRosterParser}'s {@code alias(...)} calls.
     */
    static final String[] META_HEADERS = {"Emp ID", "Emp Name", "Email", "Location", "Shift", "Week Off"};
    static final int META_COLUMNS = META_HEADERS.length;

    private static final int TITLE_ROW = 0;
    private static final int HEADER_ROW = 1;
    private static final int FIRST_DATA_ROW = 2;

    private static final DateTimeFormatter SHEET_NAME = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter FILE_MONTH = DateTimeFormatter.ofPattern("MMMM_yyyy", Locale.ENGLISH);
    private static final Pattern UNSAFE_FILE_CHARS = Pattern.compile("[^A-Za-z0-9._-]+");

    /**
     * Palette mirroring the light-theme {@code --attendance-*-bg} /
     * {@code --attendance-*-text} tokens in frontend/src/styles/tokens.css, so a
     * downloaded roster looks like the grid the admin is looking at. Excel has no
     * CSS variables, so the hex values are repeated here as the single source for
     * the Excel renderer. Key: canonical code, or {@link #UNKNOWN_KEY}.
     */
    static final String UNKNOWN_KEY = "__UNKNOWN__";

    private static final Map<String, String[]> STATUS_PALETTE = Map.ofEntries(
            Map.entry("WO", new String[]{"E5E7EB", "374151"}),
            Map.entry("WFO", new String[]{"86EFAC", "166534"}),
            Map.entry("WFH", new String[]{"FDE68A", "78350F"}),
            Map.entry("PL", new String[]{"FDE68A", "78350F"}),
            Map.entry("SL", new String[]{"FEF3C7", "78350F"}),
            Map.entry("CO", new String[]{"DBEAFE", "1E40AF"}),
            Map.entry("HPEH", new String[]{"F0FDF4", "166534"}),
            Map.entry("FL", new String[]{"FED7AA", "7C2D12"}),
            Map.entry("SW OFF", new String[]{"FDE68A", "78350F"}),
            Map.entry("SW WK", new String[]{"86EFAC", "166534"}),
            Map.entry("WK WRK", new String[]{"86EFAC", "166534"}),
            Map.entry("HD", new String[]{"FED7AA", "7C2D12"}),
            Map.entry("WX", new String[]{"DDD6FE", "5B21B6"}),
            Map.entry("TR", new String[]{"BFDBFE", "1E40AF"}),
            Map.entry("ITS", new String[]{"FECACA", "991B1B"}),
            Map.entry("WDT", new String[]{"BFDBFE", "1E40AF"}),
            Map.entry("ATR", new String[]{"FECACA", "991B1B"}),
            Map.entry(UNKNOWN_KEY, new String[]{"FFE4E6", "9F1239"}));

    private static final String META_TEXT = "374151";
    private static final String HEADER_FILL = "1F3A5F";
    private static final String TITLE_COLOR = "1F3A5F";
    private static final String GRID_LINE = "D6DBE1";
    private static final String GRID_LINE_STRONG = "9AA4B2";
    private static final String HEADER_FILL_WEEKEND = "DCE4F0";
    private static final String HEADER_FILL_HOLIDAY = "FDE9C8";
    private static final String GROUP_SEPARATOR = "8A94A6";
    private static final String EMPTY_FILL = "F7F8FA";
    private static final String EMPTY_TEXT = "A6AEBB";

    /** Refuse to build an unreasonable workbook rather than exhausting the heap. */
    private static final int MAX_EMPLOYEES = 20_000;

    private final ImportEmployeeRepository importEmployeeRepository;
    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final AttendanceRecordRepository recordRepository;
    private final AttendanceShiftAssignmentRepository shiftAssignmentRepository;
    private final AttendanceWeekOffAssignmentRepository weekOffAssignmentRepository;
    private final AttendanceStatusRepository statusRepository;
    private final HolidayRepository holidayRepository;
    private final AuditService auditService;
    private final AppClock appClock;

    // ------------------------------------------------------------------ PLANNING

    /**
     * Turns the raw request into the concrete list of sheets to write. Separate
     * from rendering so scope/month/year validation happens once, up front, and
     * the filename is known before a single byte is streamed.
     */
    public Plan plan(ExportRequest request) {
        Scope scope = request.scope() == null ? Scope.MONTH : request.scope();
        String month = trim(request.month());
        int year;

        YearMonth resolvedMonth;
        if (scope == Scope.CURRENT_MONTH) {
            resolvedMonth = YearMonth.from(appClock.today());
            year = resolvedMonth.getYear();
        } else {
            resolvedMonth = month == null ? null : parseMonth(month);
            year = request.year() != null ? request.year()
                    : resolvedMonth != null ? resolvedMonth.getYear() : appClock.today().getYear();
        }
        if (scope == Scope.FULL_YEAR && (year < 1900 || year > 2100)) {
            throw ApiException.badRequest("Invalid year '" + year + "'. Expected e.g. 2026");
        }
        if (scope != Scope.FULL_YEAR && resolvedMonth == null) {
            throw ApiException.badRequest("Month is required (yyyy-MM)");
        }

        List<YearMonth> sheets;
        if (scope == Scope.FULL_YEAR) {
            // One sheet per month that actually holds attendance in that year, so
            // the workbook mirrors the shape of a multi-month source file instead
            // of padding it out with empty sheets.
            sheets = recordRepository.findDistinctAttendanceDatesAsc().stream()
                    .map(YearMonth::from)
                    .filter(ym -> ym.getYear() == year)
                    .distinct()
                    .sorted()
                    .toList();
            if (sheets.isEmpty()) {
                throw ApiException.badRequest("No attendance data found for " + year);
            }
        } else {
            sheets = List.of(resolvedMonth);
        }

        Long teamId = request.teamId();
        String teamName = teamId == null ? null
                : departmentRepository.findById(teamId).map(Department::getName).orElse(null);

        return new Plan(scope, resolvedMonth, year, List.copyOf(sheets), teamId, teamName,
                trim(request.q()), trim(request.location()), trim(request.shift()), trim(request.status()),
                request.includeEmpty());
    }

    /** Content-Disposition filename, e.g. {@code Employee_Attendance_September_2026_Voice.xlsx}. */
    public String filenameFor(Plan plan) {
        String base = plan.scope() == Scope.FULL_YEAR
                ? "Employee_Attendance_" + plan.year()
                : "Employee_Attendance_" + FILE_MONTH.format(plan.month());
        String name = UNSAFE_FILE_CHARS.matcher(base).replaceAll("_");
        if (plan.teamName() != null && !plan.teamName().isBlank()) {
            name = name + "_" + UNSAFE_FILE_CHARS.matcher(plan.teamName().trim()).replaceAll("_");
        }
        return name + ".xlsx";
    }

    public static String contentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    // ------------------------------------------------------------------ EXPORT

    @Transactional(readOnly = true)
    public ExportResult export(OutputStream out, ExportRequest request) throws IOException {
        Plan plan = plan(request);
        Styles styles;
        int sheetCount = 0;
        int employeeRows = 0;
        int statusCells = 0;

        try (Workbook wb = new XSSFWorkbook()) {
            styles = new Styles(wb, statusLabels());
            for (YearMonth sheet : plan.sheets()) {
                SheetStaff staff = employees(plan, sheet);
                Counts counts = writeSheet(wb, plan, sheet, staff, styles);
                sheetCount++;
                employeeRows += counts.rows();
                statusCells += counts.cells();
            }
            wb.write(out);
        }

        auditService.record("ROSTER_EXPORTED", "AttendanceRecord", null, null,
                Map.of("scope", plan.scope().name(),
                        "sheets", sheetCount,
                        "employeeRows", employeeRows,
                        "statusCells", statusCells,
                        "includeEmpty", plan.includeEmpty(),
                        "teamId", plan.teamId() == null ? "ALL" : plan.teamId()));

        log.info("Roster export: scope={} sheets={} employeeRows={} statusCells={}",
                plan.scope(), sheetCount, employeeRows, statusCells);

        return new ExportResult(filenameFor(plan), sheetCount, employeeRows, statusCells);
    }

    private record Counts(int rows, int cells) {
    }

    /**
     * Employees for one sheet, in the same order the grid shows them: shift start
     * time ascending, then name, then id.
     *
     * <p>By default this is exactly the grid's employee set — people matching the
     * filters who hold at least one attendance record in the month. That matters
     * because {@code import_employees} is an import artefact, not a clean employee
     * master: it also holds rows the leave-tracker sheets produced, e.g.
     * {@code employee_id = "Abhilash Yadav", employee_name = "WO - Sat-Sun"}. Those
     * rows have no shift, no week-off and no attendance, so the grid never shows
     * them, and exporting them would put obvious garbage in a roster the admin is
     * meant to trust.</p>
     *
     * <p>{@code includeEmpty} opts into listing every filtered employee instead,
     * month blanks included. Such rows survive the download but not a re-import —
     * the parser skips rows with no day values.</p>
     */
    private SheetStaff employees(Plan plan, YearMonth sheet) {
        LocalDate from = sheet.atDay(1);
        LocalDate to = sheet.atEndOfMonth();
        List<ImportEmployee> all;
        if (plan.includeEmpty()) {
            all = new ArrayList<>(importEmployeeRepository.findEmployeesForExport(
                    plan.teamId(), plan.q(), plan.location()));
            // A status filter decides only *which* employees are listed (exactly as
            // on the grid); every day of the month is still written for them.
            if (plan.status() != null) {
                Set<String> withStatus = new HashSet<>(recordRepository
                        .findDistinctEmployeeIdsByStatusInRange(from, to, plan.status()));
                all.removeIf(e -> !withStatus.contains(e.getEmployeeId()));
            }
        } else {
            // Same query as the grid, so an export never quietly drops someone the grid
            // shows. In particular an attrited employee keeps their past months; only the
            // "today" view is allowed to filter on the current active flag.
            all = new ArrayList<>(importEmployeeRepository.findEmployeesForRosterWithExit(
                    plan.teamId(), plan.q(), plan.location(), from, to, plan.status()));
            YearMonth sheetMonth = YearMonth.from(from);
            all.removeIf(e -> !AttendanceRosterService.belongsInMonth(e, sheetMonth));
        }

        // The exported Shift column must be the shift rostered for THIS sheet,
        // not the employee's current master shift, so a year-long download does
        // not show one shift on all twelve months.
        Map<String, String> periodShifts = periodShifts(all, from);
        String wantedShiftKey = ShiftTime.comparisonKey(plan.shift());
        if (wantedShiftKey != null) {
            all.removeIf(e -> !wantedShiftKey.equals(
                    ShiftTime.comparisonKey(periodShifts.get(e.getEmployeeId()))));
        }

        if (all.size() > MAX_EMPLOYEES) {
            throw ApiException.badRequest("Too many employees to export (" + all.size()
                    + "). Narrow the team or filters first.");
        }

        all.sort(Comparator
                .comparing((ImportEmployee e) -> ShiftTime.parseShiftStartTime(
                                periodShifts.get(e.getEmployeeId())),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Comparator.comparing(ImportEmployee::getEmployeeName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .thenComparing(Comparator.comparing(ImportEmployee::getEmployeeId,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))));
        return new SheetStaff(all, periodShifts, periodWeekOffs(all, from));
    }

    /** The employees written to one sheet plus the shift each was rostered to for it. */
    private record SheetStaff(List<ImportEmployee> employees, Map<String, String> periodShifts,
                              Map<String, String> periodWeekOffs) {
    }

    /**
     * Shift per employee for the exported period, mirroring the roster grid: only
     * the period's own assignment is written, so an export never carries a master
     * shift into a period the source did not roster.
     */
    private Map<String, String> periodShifts(List<ImportEmployee> employees, LocalDate from) {
        Map<String, String> resolved = new HashMap<>();
        if (employees.isEmpty()) {
            return resolved;
        }
        List<String> ids = employees.stream().map(ImportEmployee::getEmployeeId).toList();
        for (AttendanceShiftAssignment a : shiftAssignmentRepository.findByEmployeeIdIn(ids)) {
            if (from.equals(a.getPeriodStart()) && a.getShiftValue() != null) {
                resolved.put(a.getEmployeeId(), a.getShiftValue());
            }
        }
        return resolved;
    }

    /**
     * Week off per employee for the exported sheet, mirroring the roster grid:
     * only the sheet's own assignment is written, so a month never carries the
     * employee's master or a neighbouring month's schedule.
     */
    private Map<String, String> periodWeekOffs(List<ImportEmployee> employees, LocalDate from) {
        Map<String, String> resolved = new HashMap<>();
        if (employees.isEmpty()) {
            return resolved;
        }
        List<String> ids = employees.stream().map(ImportEmployee::getEmployeeId).toList();
        for (AttendanceWeekOffAssignment a : weekOffAssignmentRepository.findByEmployeeIdIn(ids)) {
            if (from.equals(a.getPeriodStart()) && a.getWeekOffValue() != null) {
                resolved.put(a.getEmployeeId(), a.getWeekOffValue());
            }
        }
        return resolved;
    }

    private Counts writeSheet(Workbook wb, Plan plan, YearMonth sheet,
                              SheetStaff staff, Styles styles) {
        List<ImportEmployee> employees = staff.employees();
        Map<String, String> periodShifts = staff.periodShifts();
        Sheet ws = wb.createSheet(sheetName(sheet));
        ws.setDisplayGridlines(false);

        int days = sheet.lengthOfMonth();
        int lastCol = META_COLUMNS + days - 1;

        Map<String, String> emails = masterEmails(employees);
        Map<String, Map<LocalDate, String>> cells = cells(employees, sheet);
        Map<LocalDate, String> holidays = holidays(plan, sheet);

        // ---- title (row 0). A pure title row carries no identity labels, so the
        // importer's header detector skips it and lands on the real header below.
        Row title = ws.createRow(TITLE_ROW);
        title.setHeightInPoints(20f);
        Cell titleCell = title.createCell(0);
        titleCell.setCellValue(titleText(plan, sheet));
        titleCell.setCellStyle(styles.title);
        ws.addMergedRegion(new CellRangeAddress(TITLE_ROW, TITLE_ROW, 0, lastCol));

        // ---- header (row 1)
        Row header = ws.createRow(HEADER_ROW);
        header.setHeightInPoints(28f);
        for (int c = 0; c < META_COLUMNS; c++) {
            Cell cell = header.createCell(c);
            cell.setCellValue(META_HEADERS[c]);
            cell.setCellStyle(styles.header);
        }
        for (int d = 1; d <= days; d++) {
            LocalDate date = sheet.atDay(d);
            Cell cell = header.createCell(META_COLUMNS + d - 1);
            // A real date cell: the importer reads the serial date, so the display
            // format is free to stay narrow and readable.
            cell.setCellValue(date);
            cell.setCellStyle(styles.headerFor(date, holidays.containsKey(date)));
            comment(wb, ws, cell, headerNote(date, holidays.get(date)));
        }

        // ---- data
        int cellsWritten = 0;
        String previousShiftKey = null;
        for (int i = 0; i < employees.size(); i++) {
            ImportEmployee emp = employees.get(i);
            Row row = ws.createRow(FIRST_DATA_ROW + i);
            row.setHeightInPoints(15f);

            // Shift-group separator, mirroring the tinted shift bands in the grid.
            String shiftValue = periodShifts.get(emp.getEmployeeId());
            String shiftKey = shiftValue == null ? "" : shiftValue.trim();
            boolean newGroup = !shiftKey.equals(previousShiftKey);
            previousShiftKey = shiftKey;

            writeText(row, 0, emp.getEmployeeId(), styles.meta(shiftKey, newGroup, false));
            writeText(row, 1, emp.getEmployeeName(), styles.meta(shiftKey, newGroup, false));
            writeText(row, 2, emails.getOrDefault(emp.getEmployeeId(), emp.getEmail()),
                    styles.meta(shiftKey, newGroup, false));
            writeText(row, 3, emp.getLocation(), styles.meta(shiftKey, newGroup, false));
            writeText(row, 4, shiftValue, styles.meta(shiftKey, newGroup, false));
            writeText(row, COL_WEEK_OFF, staff.periodWeekOffs().get(emp.getEmployeeId()),
                    styles.meta(shiftKey, newGroup, true));

            Map<LocalDate, String> dayCells = cells.getOrDefault(emp.getEmployeeId(), Map.of());
            for (int d = 1; d <= days; d++) {
                LocalDate date = sheet.atDay(d);
                String code = dayCells.get(date);
                Cell cell = row.createCell(META_COLUMNS + d - 1);
                if (code != null && !code.isBlank()) {
                    cell.setCellValue(code);
                    cellsWritten++;
                    if (!styles.isKnown(code)) {
                        // Rare, so the extra drawing is affordable — and it flags
                        // for review exactly the cells that are not importable.
                        comment(wb, ws, cell, styles.labelOf(code));
                    }
                }
                cell.setCellStyle(styles.forStatus(code, shiftKey, newGroup));
            }
        }

        int lastRow = FIRST_DATA_ROW + Math.max(employees.size() - 1, 0);
        ws.setAutoFilter(new CellRangeAddress(HEADER_ROW, lastRow, 0, lastCol));
        // Freeze the six employee identity columns plus the title/header rows.
        ws.createFreezePane(META_COLUMNS, FIRST_DATA_ROW);
        setWidths(ws, days);
        configurePrint(ws, plan.sheets().size(), lastRow, lastCol);
        return new Counts(employees.size(), cellsWritten);
    }

    private static void setWidths(Sheet ws, int days) {
        int[] widths = {12, 26, 30, 14, 20, 14};
        for (int c = 0; c < widths.length; c++) {
            ws.setColumnWidth(c, widths[c] * 256);
        }
        for (int d = 0; d < days; d++) {
            ws.setColumnWidth(META_COLUMNS + d, 6 * 256);
        }
    }

    private void configurePrint(Sheet ws, int sheetCount, int lastRow, int lastCol) {
        ws.setFitToPage(true);
        ws.setAutobreaks(true);
        PrintSetup ps = ws.getPrintSetup();
        ps.setLandscape(true);
        ps.setPaperSize(PrintSetup.A4_PAPERSIZE);
        ps.setFitWidth((short) 1);
        ps.setFitHeight((short) 0);
        // Repeat the header on every printed page, and the identity columns when
        // a month is too wide to fit legibly on one page.
        ws.setRepeatingRows(new CellRangeAddress(HEADER_ROW, HEADER_ROW, -1, -1));
        if (sheetCount <= 1) {
            ws.setRepeatingColumns(new CellRangeAddress(-1, -1, 0, META_COLUMNS - 1));
        }
        ws.getWorkbook().setPrintArea(wbIndexOf(ws), 0, lastCol, TITLE_ROW, lastRow);
        ws.setMargin(Sheet.LeftMargin, 0.25);
        ws.setMargin(Sheet.RightMargin, 0.25);
    }

    private static int wbIndexOf(Sheet ws) {
        return ws.getWorkbook().getSheetIndex(ws);
    }

    private void comment(Workbook wb, Sheet ws, Cell cell, String text) {
        if (text == null) {
            return;
        }
        CreationHelper helper = wb.getCreationHelper();
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setCol1(cell.getColumnIndex() + 1);
        anchor.setCol2(cell.getColumnIndex() + 5);
        anchor.setRow1(cell.getRowIndex());
        anchor.setRow2(cell.getRowIndex() + 3);
        Comment comment = ws.createDrawingPatriarch().createCellComment(anchor);
        comment.setString(helper.createRichTextString(text));
        comment.setAuthor("Roster");
        cell.setCellComment(comment);
    }

    private static void writeText(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null && !value.isBlank()) {
            cell.setCellValue(value);
        }
        cell.setCellStyle(style);
    }

    /**
     * {@code "Sep 2026"} — understood by the importer's month resolver, and unlike
     * a team name it can never collide with its ignorable-sheet fragments
     * ("rts", "index", "notes", "cover", ...), which would make the sheet skipped
     * on re-import.
     */
    private static String sheetName(YearMonth sheet) {
        return SHEET_NAME.format(sheet);
    }

    private String titleText(Plan plan, YearMonth sheet) {
        StringBuilder sb = new StringBuilder("ATTENDANCE ROSTER - ");
        sb.append(sheet.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                .append(' ').append(sheet.getYear());
        if (plan.teamName() != null && !plan.teamName().isBlank()) {
            sb.append(" - ").append(plan.teamName().trim());
        }
        return sb.toString();
    }

    private static String headerNote(LocalDate date, String holidayName) {
        String base = LONG_DATE.format(date) + " ("
                + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ")";
        return holidayName == null ? base : base + " - " + holidayName;
    }

    /** Master {@code Employee.email} wins over the imported snapshot (same rule as the grid). */
    private Map<String, String> masterEmails(List<ImportEmployee> employees) {
        if (employees.isEmpty()) {
            return Map.of();
        }
        List<String> ids = employees.stream().map(ImportEmployee::getEmployeeId).toList();
        Map<String, String> out = new HashMap<>();
        for (Employee e : employeeRepository.findByEmployeeCodeIn(ids)) {
            if (e.getEmail() != null && !e.getEmail().isBlank()) {
                out.put(e.getEmployeeCode(), e.getEmail());
            }
        }
        return out;
    }

    private Map<String, Map<LocalDate, String>> cells(List<ImportEmployee> employees, YearMonth sheet) {
        if (employees.isEmpty()) {
            return Map.of();
        }
        List<String> ids = employees.stream().map(ImportEmployee::getEmployeeId).toList();
        Map<String, Map<LocalDate, String>> out = new HashMap<>();
        for (AttendanceRecord r : recordRepository
                .findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                        sheet.atDay(1), sheet.atEndOfMonth(), ids)) {
            out.computeIfAbsent(r.getEmployeeId(), k -> new HashMap<>())
                    .put(r.getAttendanceDate(), r.getStatusCode());
        }
        return out;
    }

    private Map<LocalDate, String> holidays(Plan plan, YearMonth sheet) {
        Map<LocalDate, String> out = new LinkedHashMap<>();
        for (Holiday h : holidayRepository.findVisibleInRange(
                sheet.atDay(1), sheet.atEndOfMonth(), null, plan.teamId())) {
            out.putIfAbsent(h.getHolidayDate(), h.getName());
        }
        return out;
    }

    /**
     * Canonical code -> human label, preferring the {@code attendance_status} table
     * and falling back to the built-in dictionary so no status ever exports or
     * comments as a bare code.
     */
    private Map<String, String> statusLabels() {
        Map<String, String> labels = new HashMap<>();
        for (AttendanceStatus s : statusRepository.findAllByOrderByCodeAsc()) {
            if (s.getName() != null && !s.getName().isBlank()) {
                labels.put(s.getCode(), s.getName());
            }
        }
        for (String canonical : List.of("WO", "WFO", "WFH", "PL", "SL", "CO", "HPEH", "FL",
                "SW OFF", "SW WK", "WK WRK", "HD", "WX", "TR", "ITS", "WDT", "ATR")) {
            labels.putIfAbsent(canonical, HistoricalImportCodes.nameOf(canonical));
        }
        return labels;
    }

    private static YearMonth parseMonth(String month) {
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            throw ApiException.badRequest("Invalid month '" + month + "'. Expected yyyy-MM");
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    // ------------------------------------------------------------------ STYLES

    /**
     * Builds every cell style once and caches on (status, shift, group-start), so
     * a 40 x 31 month reuses a few dozen styles instead of creating thousands.
     * The shift key only drives band separation; the structural difference is the
     * medium rule that opens a new shift group.
     */
    private static final class Styles {

        private final Workbook wb;
        private final Map<String, String> labels;
        private final CellStyle title;
        private final CellStyle header;
        private final CellStyle headerPlain;
        private final CellStyle headerWeekend;
        private final CellStyle headerHoliday;
        private final CellStyle emptyCell;
        private final Map<String, CellStyle> dateHeaders = new HashMap<>();
        private final Map<String, CellStyle> statusStyles = new HashMap<>();
        private final Map<String, CellStyle> metaStyles = new HashMap<>();

        Styles(Workbook wb, Map<String, String> labels) {
            this.wb = wb;
            this.labels = labels;

            title = base(font(12, true, TITLE_COLOR), HorizontalAlignment.LEFT,
                    null, null, BorderStyle.NONE, false, false);

            header = solidHeader(HorizontalAlignment.LEFT);
            headerWeekend = solidHeader(HorizontalAlignment.CENTER);
            headerHoliday = solidHeader(HorizontalAlignment.CENTER);
            headerPlain = solidHeader(HorizontalAlignment.CENTER);
            headerWeekend.setFillForegroundColor(color(HEADER_FILL_WEEKEND));
            headerWeekend.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerHoliday.setFillForegroundColor(color(HEADER_FILL_HOLIDAY));
            headerHoliday.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            XSSFCellStyle empty = base(font(9, false, EMPTY_TEXT), HorizontalAlignment.CENTER,
                    null, null, BorderStyle.HAIR, true, false);
            empty.setFillForegroundColor(color(EMPTY_FILL));
            empty.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            emptyCell = empty;
        }

        private CellStyle solidHeader(HorizontalAlignment align) {
            XSSFCellStyle s = base(font(9, true, "FFFFFF"), align, null, null,
                    BorderStyle.THIN, true, false);
            s.setFillForegroundColor(color(HEADER_FILL));
            s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return s;
        }

        /** Weekend and holiday day headers read differently from a plain day. */
        CellStyle headerFor(LocalDate date, boolean holiday) {
            String key = holiday ? "holiday" : isWeekend(date) ? "weekend" : "plain";
            return dateHeaders.computeIfAbsent(key, k -> {
                CellStyle template = switch (k) {
                    case "holiday" -> headerHoliday;
                    case "weekend" -> headerWeekend;
                    default -> headerPlain;
                };
                CellStyle copy = wb.createCellStyle();
                copy.cloneStyleFrom(template);
                copy.setDataFormat(dateFormat());
                return copy;
            });
        }

        /** Status cell, filled from the palette so it matches the on-screen grid. */
        CellStyle forStatus(String code, String shiftKey, boolean newGroup) {
            if (code == null || code.isBlank()) {
                return emptyCell;
            }
            return statusStyles.computeIfAbsent(code + "|" + shiftKey + "|" + newGroup, k -> {
                String paletteKey = isKnown(code) ? HistoricalImportCodes.canonicalOf(code) : UNKNOWN_KEY;
                String[] rgb = STATUS_PALETTE.getOrDefault(paletteKey, STATUS_PALETTE.get(UNKNOWN_KEY));
                XSSFCellStyle s = base(font(9, true, rgb[1]), HorizontalAlignment.CENTER, null, null,
                        BorderStyle.HAIR, true, newGroup);
                s.setFillForegroundColor(color(rgb[0]));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                return s;
            });
        }

        /** Employee metadata cell, ruled on top where a new shift group starts. */
        CellStyle meta(String shiftKey, boolean newGroup, boolean lastMetaColumn) {
            return metaStyles.computeIfAbsent(shiftKey + "|" + newGroup + "|" + lastMetaColumn, k -> {
                XSSFCellStyle s = base(font(10, false, META_TEXT), HorizontalAlignment.LEFT, null,
                        null, BorderStyle.THIN, true, newGroup);
                if (lastMetaColumn) {
                    s.setBorderRight(BorderStyle.MEDIUM);
                    s.setRightBorderColor(color(GRID_LINE_STRONG));
                }
                return s;
            });
        }

        boolean isKnown(String code) {
            return code != null && HistoricalImportCodes.isKnown(code);
        }

        /** Comment text for an unrecognised code, so reviewers can see why it stands out. */
        String labelOf(String code) {
            String canonical = HistoricalImportCodes.canonicalOf(code);
            String label = canonical == null ? null : labels.get(canonical);
            if (label == null && code != null && code.startsWith("ATR")) {
                label = labels.get("ATR");
            }
            if (label == null) {
                label = HistoricalImportCodes.nameOf(code);
            }
            return label == null
                    ? "Unrecognised status \"" + code + "\" — this code is not in the status dictionary"
                    : "Unrecognised status \"" + code + "\" — closest known meaning: " + label;
        }

        private short dateFormat() {
            return wb.getCreationHelper().createDataFormat().getFormat("d-MMM-yy");
        }

        private XSSFFont font(int points, boolean bold, String rgb) {
            XSSFFont f = (XSSFFont) wb.createFont();
            f.setFontName("Calibri");
            f.setFontHeightInPoints((short) points);
            f.setBold(bold);
            if (rgb != null) {
                f.setColor(color(rgb));
            }
            return f;
        }

        private XSSFCellStyle base(Font font, HorizontalAlignment align, Short dataFormat,
                                  String fill, BorderStyle border, boolean wrap, boolean newGroup) {
            XSSFCellStyle s = (XSSFCellStyle) wb.createCellStyle();
            s.setFont(font);
            s.setAlignment(align);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            if (dataFormat != null) {
                s.setDataFormat(dataFormat);
            }
            if (border != null) {
                s.setBorderTop(border);
                s.setBorderBottom(border);
                s.setBorderLeft(border);
                s.setBorderRight(border);
                s.setTopBorderColor(color(GRID_LINE));
                s.setBottomBorderColor(color(GRID_LINE));
                s.setLeftBorderColor(color(GRID_LINE));
                s.setRightBorderColor(color(GRID_LINE));
            }
            if (newGroup) {
                s.setBorderTop(BorderStyle.MEDIUM);
                s.setTopBorderColor(color(GROUP_SEPARATOR));
            }
            s.setWrapText(wrap);
            return s;
        }

        private XSSFColor color(String rgb) {
            int r = Integer.parseInt(rgb.substring(0, 2), 16);
            int g = Integer.parseInt(rgb.substring(2, 4), 16);
            int b = Integer.parseInt(rgb.substring(4, 6), 16);
            return new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, null);
        }
    }

    private static final int COL_WEEK_OFF = META_COLUMNS - 1;
}