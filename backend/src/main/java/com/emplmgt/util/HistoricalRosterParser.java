package com.emplmgt.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Robust, pure parser for heterogeneous multi-sheet attendance workbooks.
 * Per-sheet: merged title blocks, headers found anywhere (scored by identity
 * alias hits + date column count), two-line headers (day numbers on header
 * row, real dates below), Excel/text dates, unknown column orders, NBSP/case
 * masks. Each sheet is parsed independently — a bad sheet never fails the
 * import. No I/O or DB access: trivially unit-testable with POI workbooks.
 */
public final class HistoricalRosterParser {

    private HistoricalRosterParser() {
    }

    /**
     * One attendance cell. {@code rawCode} is the original grid value exactly as
     * written (trimmed); {@code normalizedCode} is the uppercase,
     * whitespace-normalised form used for status matching; {@code statusCode}
     * is the canonical dictionary code (or the normalised code when unknown).
     * Unknown statuses are never discarded — they are preserved verbatim and
     * flagged {@code unknown=true}.
     */
    public record ParsedRecord(int sourceRow, String employeeId, String employeeName, String email,
                               String location, String manager, String shift, String weekOff,
                               LocalDate attendanceDate, String rawCode, String normalizedCode,
                               String statusCode, String statusName, boolean unknown, String warning) {
    }

    public record SheetResult(String sheetName, YearMonth month, Integer headerRow,
                              int employeeColumnCount, int dateColumnCount, int employeeCount,
                              int cellCount, int unknownCodeCount, int emptyCellCount,
                              boolean skipped, String skipReason, boolean ignorable,
                              List<String> employeeIds, List<ParsedRecord> records,
                              List<String> warnings) {
    }

    public record ParsedWorkbook(List<SheetResult> sheets, List<String> globalWarnings) {
        public int totalSheets() {
            return sheets.size();
        }

        public long sheetsSkipped() {
            return sheets.stream().filter(s -> s.skipped).count();
        }

        public long recordCount() {
            return sheets.stream().mapToLong(s -> s.records.size()).sum();
        }
    }

    private enum Field {EMP_ID, EMP_NAME, EMP_EMAIL, LOCATION, SHIFT, WEEK_OFF, MANAGER}

    /** Header detection window: columns 0..14 of rows 0..14. */
    private static final int HEADER_WINDOW_ROWS = 15;
    private static final int HEADER_WINDOW_COLS = 15;

    private static final Map<String, Field> FIELD_BY_COMPACT = new HashMap<>();

    private static void alias(Field f, String... names) {
        for (String n : names) {
            FIELD_BY_COMPACT.put(HistoricalImportCodes.compactCode(n), f);
        }
    }

    static {
        alias(Field.EMP_ID, "Emp ID", "Employee ID", "Emp Code", "Employee Code", "Emp No", "Code");
        alias(Field.EMP_NAME, "Emp Name", "Employee Name", "Name");
        alias(Field.EMP_EMAIL, "Email", "Email ID", "Mail", "E-Mail");
        alias(Field.LOCATION, "Location", "City", "Branch", "Site");
        alias(Field.SHIFT, "Shift", "Shift Time", "Timings", "Shift Type");
        alias(Field.WEEK_OFF, "Week Off", "Weekly Off", "WeekOff", "WO Day", "Off Day", "Rest Day");
        alias(Field.MANAGER, "Manager", "Reporting Manager", "Reporting To", "Lead", "Manager Name");
    }

    private static final List<String> IGNORABLE = List.of(
            "rts", "index", "readme", "notes", "instruction", "cover", "title", "furlough");

    public static ParsedWorkbook parse(Workbook wb) {
        List<String> global = new ArrayList<>();
        List<SheetResult> out = new ArrayList<>();
        FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet s = wb.getSheetAt(i);
            try {
                out.add(decodeSheet(s, ev));
            } catch (Exception e) {
                global.add("Sheet '" + s.getSheetName() + "' failed to parse and was skipped: " + e.getMessage());
            }
        }
        return new ParsedWorkbook(out, global);
    }

    // ------------------------------------------------------------------
    // Sheet decoding
    // ------------------------------------------------------------------

    private static SheetResult decodeSheet(Sheet sheet, FormulaEvaluator ev) {
        String name = sheet.getSheetName();
        if (sheet.getPhysicalNumberOfRows() == 0) {
            return skipped(name, "empty sheet", List.of());
        }

        Frame fr = materialize(sheet, ev);
        HeaderResult h = detectHeader(fr);
        if (h == null) {
            return skipped(name, "no employee/date header",
                    List.of("No header row detected."));
        }

        Map<Field, Integer> fields = mapFields(fr, h);
        List<DayColumn> days = detectDayColumns(fr, h, fields);
        if (days.isEmpty()) {
            return skipped(name, "no date columns",
                    List.of("Header detected but no date/day columns."));
        }

        YearMonth month = resolveMonth(fr, name, h, days);
        if (month == null) {
            return skipped(name, "cannot determine month",
                    List.of("Attendance uses day numbers but the month could not be determined."));
        }

        List<String> warnings = new ArrayList<>();
        List<ParsedRecord> records = new ArrayList<>();
        List<String> employeeIds = new ArrayList<>();
        int emptyCells = 0;
        int sectionRows = 0;
        Set<String> seenEmployees = new HashSet<>();
        Set<String> duplicateWarned = new HashSet<>();

        for (int r = h.dayCellsRow + 1; r < fr.rows; r++) {
            // Section / repeated-header rows (e.g. "Emp ID | Emp Name | 1 | 2 | ..."
            // framing a team block) are NOT employees and must be ignored.
            if (isSectionHeader(fr, r)) {
                sectionRows++;
                continue;
            }
            // Rows that carry no attendance values at all are banners, blanks
            // or filler — they are not employees.
            boolean hasVal = hasDayValues(fr, r, days);
            if (!hasVal) {
                continue;
            }
            String rawEmp = fr.text(r, fields.getOrDefault(Field.EMP_ID, -1));
            if (rawEmp == null || rawEmp.isBlank()) {
                warnings.add("Row " + (r + 1) + " has attendance values but no employee id — skipped.");
                continue;
            }
            String empId = HistoricalImportCodes.normaliseText(rawEmp);
            if (!seenEmployees.add(empId)) {
                if (duplicateWarned.add(empId)) {
                    warnings.add("Employee '" + empId + "' appears in more than one row in this sheet — "
                            + "duplicate rows will be skipped.");
                }
            }
            employeeIds.add(empId);
            String empName = normalisedMetadata(fr.text(r, fields.getOrDefault(Field.EMP_NAME, -1)));
            String email = HistoricalImportCodes.normaliseText(fr.text(r, fields.getOrDefault(Field.EMP_EMAIL, -1)));
            String location = HistoricalImportCodes.normaliseText(fr.text(r, fields.getOrDefault(Field.LOCATION, -1)));
            String manager = HistoricalImportCodes.normaliseText(fr.text(r, fields.getOrDefault(Field.MANAGER, -1)));
            String shift = HistoricalImportCodes.normaliseText(fr.text(r, fields.getOrDefault(Field.SHIFT, -1)));
            String weekOff = HistoricalImportCodes.normaliseText(fr.text(r, fields.getOrDefault(Field.WEEK_OFF, -1)));
            for (DayColumn dc : days) {
                String raw = fr.text(r, dc.index);
                if (raw == null || raw.isBlank()) {
                    emptyCells++;
                    continue;
                }
                LocalDate date = resolveDate(dc, month);
                String warning = null;
                if (date == null) {
                    warning = "Day " + raw + " is not valid for month " + month + " — skipped.";
                }
                String normalizedCode = HistoricalImportCodes.normaliseRaw(raw);
                String statusCode = HistoricalImportCodes.resolveStatus(raw);
                records.add(new ParsedRecord(r + 1, empId, empName, email, location, manager, shift, weekOff,
                        date, blankToNull(raw), normalizedCode, statusCode,
                        HistoricalImportCodes.nameOf(statusCode) != null
                                ? HistoricalImportCodes.nameOf(statusCode) : "Unknown",
                        !HistoricalImportCodes.isKnown(statusCode), warning));
            }
        }

        if (sectionRows > 0) {
            warnings.add(sectionRows + " section/header row(s) inside the sheet were ignored.");
        }

        // Surface per-record warnings (e.g. invalid days) on the sheet too.
        for (ParsedRecord rec : records) {
            if (rec.warning() != null) {
                warnings.add(rec.warning());
            }
        }

        if (records.isEmpty()) {
            return skipped(name, "no data rows",
                    List.of("Header/date columns found but no records to import."));
        }
        return ready(name, month, h, fields, days, employeeIds, emptyCells, records, warnings);
    }

    private static SheetResult skipped(String name, String reason, List<String> warnings) {
        return new SheetResult(name, null, null, 0, 0, 0, 0, 0, 0,
                true, reason, ignorable(name), List.of(), List.of(), warnings);
    }

    private static SheetResult ready(String name, YearMonth month, HeaderResult h,
                                     Map<Field, Integer> fields, List<DayColumn> days,
                                     List<String> employeeIds, int emptyCells,
                                     List<ParsedRecord> records, List<String> warnings) {
        Set<String> distinct = new HashSet<>(employeeIds);
        int unknown = 0;
        for (ParsedRecord rec : records) {
            if (rec.unknown()) {
                unknown++;
            }
        }
        return new SheetResult(name, month, h.headerRow() + 1, fields.size(), days.size(),
                distinct.size(), records.size(), unknown, emptyCells, false, null, ignorable(name),
                List.copyOf(employeeIds), records, warnings);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Employee name: trim + collapse whitespace, original case preserved. */
    private static String normalisedMetadata(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim().replace('\u00A0', ' ').replaceAll("[\\s]+", " ").trim();
    }

    public static boolean ignorable(String sheetName) {
        String c = HistoricalImportCodes.compactCode(sheetName);
        for (String frag : IGNORABLE) {
            if (c.contains(frag)) return true;
        }
        return false;
    }

    private static boolean hasDayValues(Frame fr, int r, List<DayColumn> days) {
        for (DayColumn dc : days) {
            String raw = fr.text(r, dc.index);
            if (raw != null && !raw.isBlank()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Frame materialisation (merged-cell aware)
    // ------------------------------------------------------------------

    private static final class Frame {
        final int rows;
        final int cols;
        final String[][] text;
        final boolean[][] isDate;
        final LocalDate[][] dates;

        Frame(int rows, int cols) {
            this.rows = rows;
            this.cols = cols;
            this.text = new String[rows][cols];
            this.isDate = new boolean[rows][cols];
            this.dates = new LocalDate[rows][cols];
        }

        String text(int r, int c) {
            if (r < 0 || r >= rows || c < 0 || c >= cols) return null;
            return text[r][c];
        }
    }

    private static Frame materialize(Sheet sheet, FormulaEvaluator ev) {
        int first = sheet.getFirstRowNum();
        int last = sheet.getLastRowNum();
        int cols = 0;
        for (int r = first; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row != null && row.getLastCellNum() > cols) {
                cols = row.getLastCellNum();
            }
        }
        Frame fr = new Frame(last - first + 1, cols);

        for (int r = first; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < cols; c++) {
                Cell cell = row.getCell(c);
                if (cell == null) continue;
                readCell(cell, ev, fr, r - first, c);
            }
        }

        // Expand merged regions by propagating the top-left value.
        for (CellRangeAddress rg : sheet.getMergedRegions()) {
            int r0 = rg.getFirstRow() - first;
            int r1 = rg.getLastRow() - first;
            int c0 = rg.getFirstColumn();
            int c1 = rg.getLastColumn();
            if (r0 < 0) continue;
            for (int r = r0; r <= r1; r++) {
                for (int c = c0; c <= c1; c++) {
                    if (r >= fr.rows || c >= fr.cols || r == r0 && c == c0) continue;
                    if (isEmpty(fr.text[r][c])) {
                        fr.text[r][c] = fr.text[r0][c0];
                        fr.isDate[r][c] = fr.isDate[r0][c0];
                        fr.dates[r][c] = fr.dates[r0][c0];
                    }
                }
            }
        }
        return fr;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }

    private static void readCell(Cell cell, FormulaEvaluator ev, Frame fr, int r, int c) {
        CellType type;
        try {
            type = cell.getCellType();
        } catch (Exception e) {
            return;
        }
        if (type == CellType.FORMULA) {
            try {
                ev.evaluateFormulaCell(cell);
                type = cell.getCachedFormulaResultType();
            } catch (Exception e) {
                return;
            }
        }
        switch (type) {
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDate ld = cell.getLocalDateTimeCellValue().toLocalDate();
                    fr.text[r][c] = ld.toString();
                    fr.isDate[r][c] = true;
                    fr.dates[r][c] = ld;
                } else {
                    double v = cell.getNumericCellValue();
                    fr.text[r][c] = v == (long) v ? Long.toString((long) v) : Double.toString(v);
                }
            }
            case STRING -> fr.text[r][c] = cell.getStringCellValue().replace('\u00A0', ' ');
            case BOOLEAN -> fr.text[r][c] = Boolean.toString(cell.getBooleanCellValue());
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------
    // Header detection
    // ------------------------------------------------------------------

    private record HeaderResult(int headerRow, int dayCellsRow) {
    }

    private record DayColumn(int index, LocalDate date, Integer day) {
    }

    /**
     * Detection pipeline: inspect the first {@link #HEADER_WINDOW_ROWS} rows and
     * the first {@link #HEADER_WINDOW_COLS} columns, score every row on how many
     * expected employee-header tokens (Emp ID, Emp Name, Employee Name, Email,
     * Location, Shift, WeekOff, Manager, ...) it contains, and pick the highest
     * scoring row that actually carries an employee-id column.
     *
     * <p>A pure title row such as "VOICE ROSTER FOR May-26" scores 0 (no
     * identity tokens); the row holding the real header scores high.</p>
     */
    private static HeaderResult detectHeader(Frame fr) {
        int best = -1, bestScore = -1;
        int maxRow = Math.min(fr.rows, HEADER_WINDOW_ROWS);
        for (int r = 0; r < maxRow; r++) {
            boolean hasId = false, hasName = false;
            int metadata = 0, dates = 0;
            int cols = Math.min(fr.cols, HEADER_WINDOW_COLS);
            for (int c = 0; c < cols; c++) {
                Field f = FIELD_BY_COMPACT.get(HistoricalImportCodes.compactCode(fr.text(r, c)));
                if (f == Field.EMP_ID) {
                    hasId = true;
                } else if (f == Field.EMP_NAME) {
                    hasName = true;
                } else if (f != null) {
                    metadata++;
                }
                if (isDateish(fr, r, c)) {
                    dates++;
                }
            }
            if (!hasId) {
                continue;
            }
            int score = metadata * 100 + (hasName ? 250 : 0) + dates;
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        if (best < 0) return null;

        // Two-line headers: prefer real (full) dates on the row below the
        // header over bare day numbers when the next row actually carries them.
        int dayRow = best;
        if (best + 1 < fr.rows
                && countFullDates(fr, best) < countFullDates(fr, best + 1)
                && countFullDates(fr, best + 1) >= 2) {
            dayRow = best + 1;
        }
        return new HeaderResult(best, dayRow);
    }

    /**
     * Rows below the header that re-state identity labels (e.g. a repeated
     * "Emp ID | Emp Name | ..." banner framing a team/department section)
     * are section headers, not employees.
     */
    private static boolean isSectionHeader(Frame fr, int r) {
        int tokens = 0;
        int cols = Math.min(fr.cols, HEADER_WINDOW_COLS);
        for (int c = 0; c < cols; c++) {
            Field f = FIELD_BY_COMPACT.get(HistoricalImportCodes.compactCode(fr.text(r, c)));
            if (f == Field.EMP_ID || f == Field.EMP_NAME) {
                tokens++;
            }
        }
        return tokens >= 2;
    }

    private static int countFullDates(Frame fr, int r) {
        int n = 0;
        for (int c = 0; c < fr.cols; c++) {
            if (fr.isDate[r][c] || parseFullDate(fr.text(r, c)) != null) n++;
        }
        return n;
    }

    private static boolean isDateish(Frame fr, int r, int c) {
        if (fr.isDate[r][c]) return true;
        String t = fr.text(r, c);
        if (t == null || t.isBlank()) return false;
        return parseFullDate(t) != null || dayNumber(t) != null;
    }

    private static Map<Field, Integer> mapFields(Frame fr, HeaderResult h) {
        Map<Field, Integer> m = new EnumMap<>(Field.class);
        for (int c = 0; c < fr.cols; c++) {
            Field f = FIELD_BY_COMPACT.get(HistoricalImportCodes.compactCode(fr.text(h.headerRow, c)));
            if (f != null) m.putIfAbsent(f, c);
        }
        return m;
    }

    /**
     * Date columns live strictly after the employee metadata columns. A column
     * qualifies when, on the header/date row, its actual Excel cell value is a
     * date (or safely parseable as one) or a bare day number (1..31) for the
     * resolved month.
     */
    private static List<DayColumn> detectDayColumns(Frame fr, HeaderResult h, Map<Field, Integer> fields) {
        List<DayColumn> days = new ArrayList<>();
        int start = metadataEnd(fields);
        for (int c = start; c < fr.cols; c++) {
            if (isDateish(fr, h.dayCellsRow, c)) {
                LocalDate date = null;
                Integer day = null;
                if (fr.isDate[h.dayCellsRow][c]) {
                    date = fr.dates[h.dayCellsRow][c];
                } else {
                    date = parseFullDate(fr.text(h.dayCellsRow, c));
                }
                if (date == null) {
                    day = dayNumber(fr.text(h.dayCellsRow, c));
                }
                days.add(new DayColumn(c, date, day));
            }
        }
        return days;
    }

    /** One past the last fixed (employee metadata) column on the header row. */
    private static int metadataEnd(Map<Field, Integer> fields) {
        int max = -1;
        for (Integer idx : fields.values()) {
            if (idx != null && idx > max) {
                max = idx;
            }
        }
        return max + 1;
    }

    private static LocalDate resolveDate(DayColumn dc, YearMonth month) {
        if (dc.date != null) return dc.date;
        if (dc.day == null) return null;
        try {
            return month.atDay(dc.day);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Month resolution
    // ------------------------------------------------------------------

    private static YearMonth resolveMonth(Frame fr, String sheetName, HeaderResult h, List<DayColumn> days) {
        // 1) A full-date column pins the month (first one wins but verify).
        for (DayColumn dc : days) {
            if (dc.date != null) return YearMonth.from(dc.date);
        }
        // 2) Title rows above the header.
        for (int r = 0; r < h.headerRow; r++) {
            for (int c = 0; c < fr.cols; c++) {
                YearMonth ym = parseYearMonth(fr.text(r, c));
                if (ym != null) return ym;
            }
        }
        // 3) Sheet name, e.g. "Sep 2026" or "AprilFY25".
        YearMonth fromName = parseYearMonth(sheetName);
        if (fromName != null) return fromName;
        // 4) Fall back to the current month.
        return YearMonth.now();
    }

    private static YearMonth parseYearMonth(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        Integer month = null;
        Integer year = null;

        // Month token: leading known name (jan..dec / sept), or inside a title
        // like "VOICE ROSTER FOR AprilFY25", or a 1..12 number.
        for (int i = 0; i < Months.TOKENS.size() && month == null; i++) {
            String tok = Months.TOKENS.get(i);
            if (lower.startsWith(tok)) month = Months.VALUES.get(i);
        }
        String parts = lower.replaceAll("[^a-z0-9]", " ").trim();
        if (month == null) {
            for (String tok : parts.split("\\s+")) {
                for (int i = 0; i < Months.TOKENS.size() && month == null; i++) {
                    String name = Months.TOKENS.get(i);
                    if (tok.length() >= 3 && tok.startsWith(name)) {
                        month = Months.VALUES.get(i);
                    }
                }
            }
        }
        year = scanYear(lower);

        // Numeric month/year forms like "09/2026" or "2026-09".
        if (month == null && year == null) {
            String[] toks = parts.split("\\s+");
            if (toks.length == 2 && toks[0].matches("\\d{1,2}") && toks[1].matches("\\d{4}")) {
                month = Integer.parseInt(toks[0]);
                year = Integer.parseInt(toks[1]);
            } else if (toks.length == 2 && toks[0].matches("\\d{4}") && toks[1].matches("\\d{1,2}")) {
                year = Integer.parseInt(toks[0]);
                month = Integer.parseInt(toks[1]);
            }
        }
        if (month == null || month < 1 || month > 12 || year == null) return null;
        try {
            return YearMonth.of(year, month);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Year from a free-form label: a 4-digit year wins, else an explicit
     * "fyYY" token, else the right-most standalone 2-digit number (so the
     * year in "AprilFY25", "Sep-26" or "May 2026" is recovered). Century
     * convention: 26 -&gt; 2026, 98 -&gt; 1998.
     */
    private static Integer scanYear(String lower) {
        java.util.regex.Matcher m4 = java.util.regex.Pattern.compile("\\d{4}").matcher(lower);
        if (m4.find()) {
            int y = Integer.parseInt(m4.group());
            return (y >= 1900 && y <= 2100) ? y : null;
        }
        java.util.regex.Matcher fy = java.util.regex.Pattern.compile("fy(\\d{2})").matcher(lower);
        if (fy.find()) {
            int y = Integer.parseInt(fy.group(1));
            return y < 90 ? 2000 + y : 1900 + y;
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("\\d{2}").matcher(lower);
        Integer last = null;
        while (m2.find()) {
            last = Integer.parseInt(m2.group());
        }
        if (last == null) return null;
        return last < 90 ? 2000 + last : 1900 + last;
    }

    private static final class Months {
        static final List<String> TOKENS = List.of(
                "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec");
        static final List<Integer> VALUES = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 9, 10, 11, 12);
    }

    // ------------------------------------------------------------------
    // Date / day parsing
    // ------------------------------------------------------------------

    /** Strict month-name-aware date parsing; null when not a full date. */
    static LocalDate parseFullDate(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String s = raw.trim().replace('\u00A0', ' ').replace('.', '-');
        if (s.isEmpty()) return null;

        for (String pattern : new String[]{
                "yyyy-MM-dd", "dd/MM/yyyy", "MM/dd/yyyy", "yyyy/MM/dd",
                "d-MMM-yyyy", "d-MMM-yy", "d MMM yyyy", "d MMMM yyyy",
                "MMM d yyyy", "MMMM d yyyy", "MMM d, yyyy"}) {
            DateTimeFormatter fmt = new DateTimeFormatterBuilder()
                    .parseLenient()
                    .appendPattern(pattern)
                    .toFormatter(Locale.ENGLISH);
            try {
                LocalDate ld = LocalDate.parse(s, fmt);
                if (ld.getYear() >= 1900 && ld.getYear() <= 2100) {
                    return ld;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Integer dayNumber(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        if (t.matches("[0-2]?\\d|3[01]")) {
            try {
                int d = Integer.parseInt(t);
                return (d >= 1 && d <= 31) ? d : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}