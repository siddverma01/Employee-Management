package com.emplmgt.util;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellReference;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    public record ParsedRecord(int sourceRow, int sourceColumn, String employeeId, String employeeName, String email,
                               String location, String manager, String shift, String weekOff,
                               LocalDate attendanceDate, String rawCode, String normalizedCode,
                               String statusCode, String statusName, boolean unknown, String warning,
                               String description, String descriptionSource, String descriptionAuthor,
                               LocalDateTime descriptionAt) {
    }

    /**
     * Why a sheet produced no records. {@code auxiliary} marks sheets that were
     * never attendance rosters in the first place (indices, note pads, leave
     * trackers, hidden helper tabs): they are reported as a warning and ignored.
     * Everything else is a genuine parse failure and is reported as an error
     * with {@link SheetResult#skipDetail()}.
     */
    public enum SkipReason {
        EMPTY_SHEET("empty sheet", true),
        NOT_A_ROSTER("not an attendance roster", true),
        NO_HEADER("no employee/date header", false),
        NO_DATE_COLUMNS("no date columns", false),
        NO_MONTH("cannot determine month", false),
        NO_ROWS("no data rows", false);

        private final String label;
        private final boolean auxiliary;

        SkipReason(String label, boolean auxiliary) {
            this.label = label;
            this.auxiliary = auxiliary;
        }

        public String label() {
            return label;
        }

        public boolean auxiliary() {
            return auxiliary;
        }
    }

    public record SheetResult(String sheetName, YearMonth month, Integer headerRow,
                              int employeeColumnCount, int dateColumnCount, int employeeCount,
                              int cellCount, int unknownCodeCount, int emptyCellCount,
                              boolean skipped, String skipReason, boolean ignorable,
                              String skipDetail, List<String> employeeIds, List<ParsedRecord> records,
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

    /**
     * Upper bound on the rows scanned while hunting for the header row. Headers
     * are never assumed to sit on row 1: real workbooks bury them under titles,
     * logos, weekday banners and spacer rows, so the whole sheet is searched
     * (capped only to keep the scan cheap on pathological files).
     */
    private static final int HEADER_WINDOW_ROWS = 200;
    private static final int HEADER_WINDOW_COLS = 64;

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
            "rts", "index", "readme", "notes", "instruction", "cover", "title", "furlough",
            "leave", "holiday", "calendar", "summary", "dashboard", "backup", "old", "temp",
            "draft", "test", "copy", "sheet");

    /**
     * Weekday labels that decorative banner rows carry above the date columns
     * ("Sun", "Mon", ... "Sat"). They look exactly like unknown attendance codes
     * to the status normaliser, so they must be recognised as layout, not data.
     */
    private static final Set<String> WEEKDAY_LABELS = Set.of(
            "sun", "sunday", "mon", "monday", "tue", "tues", "tuesday", "wed", "wednesday",
            "thu", "thur", "thurs", "thursday", "fri", "friday", "sat", "saturday");

    public static ParsedWorkbook parse(Workbook wb) {
        List<String> global = new ArrayList<>();
        List<SheetResult> out = new ArrayList<>();
        FormulaEvaluator ev = wb.getCreationHelper().createFormulaEvaluator();
        // Cell comments are the primary carrier of the roster "description".
        // POI cannot read the modern threaded ones, so they are pulled from the
        // raw package up front and keyed per sheet by cell reference.
        Map<String, Map<String, ExcelCommentExtractor.CellComment>> comments =
                ExcelCommentExtractor.extract(wb);
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet s = wb.getSheetAt(i);
            // Hidden tabs are helper/scratch sheets; they are never the roster an
            // admin means to import, so they are reported as ignored, not broken.
            boolean hidden = wb.isSheetHidden(i) || wb.isSheetVeryHidden(i);
            try {
                out.add(decodeSheet(s, ev, hidden, comments.getOrDefault(s.getSheetName(), Map.of())));
            } catch (Exception e) {
                global.add("Sheet '" + s.getSheetName() + "' failed to parse and was skipped: " + e.getMessage());
            }
        }
        return new ParsedWorkbook(out, global);
    }

    // ------------------------------------------------------------------
    // Sheet decoding
    // ------------------------------------------------------------------

    private static SheetResult decodeSheet(Sheet sheet, FormulaEvaluator ev, boolean hidden,
                                           Map<String, ExcelCommentExtractor.CellComment> sheetComments) {
        String name = sheet.getSheetName();
        if (sheet.getPhysicalNumberOfRows() == 0) {
            return skipped(name, SkipReason.EMPTY_SHEET, "The worksheet has no rows at all.", hidden);
        }

        Frame fr = materialize(sheet, ev);
        HeaderResult h = detectHeader(fr);
        if (h == null) {
            // No employee header anywhere in the sheet. A name that declares
            // itself auxiliary (Index, Furlough Leave, ...) settles it. Otherwise
            // decide structurally: sheets that were never rosters are warnings,
            // while a sheet that still has the shape of one is a real failure.
            if (ignorable(name) || hidden || !rosterShaped(fr)) {
                return skipped(name, SkipReason.NOT_A_ROSTER,
                        "No 'Emp ID'/'Emp Name' header found in rows 1–" + fr.rows
                                + " (columns A–" + columnName(Math.max(fr.cols - 1, 0)) + "), and the sheet has "
                                + describeShape(fr) + ". It holds no attendance grid, so it was ignored"
                                + (hidden ? " (the sheet is hidden)." : "."), hidden);
            }
            return skipped(name, SkipReason.NO_HEADER,
                    "No 'Emp ID' header found in rows 1–" + fr.rows + " (columns A–"
                            + columnName(Math.max(fr.cols - 1, 0)) + "). The sheet looks like an attendance "
                            + "roster (" + describeShape(fr) + ") but its header labels were not recognised. "
                            + "Expected one of: Emp ID, Employee ID, Emp Code, Employee Code, Emp No, Code.",
                    hidden);
        }

        Map<Field, Integer> fields = mapFields(fr, h);
        List<DayColumn> days = detectDayColumns(fr, h, fields);
        List<String> preWarnings = new ArrayList<>();
        if (days.isEmpty()) {
            return skipped(name, SkipReason.NO_DATE_COLUMNS,
                    "Header row " + (h.headerRow() + 1) + " was recognised ("
                            + describeFields(fields) + ") but none of the columns after "
                            + columnName(Math.max(metadataEnd(fields) - 1, 0))
                            + " hold a date or a day number.", hidden);
        }
        preWarnings.addAll(validateDateSequence(name, days, h, fr, fields));

        YearMonth month = resolveMonth(fr, name, h, days);
        if (month == null) {
            return skipped(name, SkipReason.NO_MONTH,
                    "The " + days.size() + " day column(s) after " + columnName(Math.max(metadataEnd(fields) - 1, 0))
                            + " are bare day numbers and neither the sheet name '" + name
                            + "' nor any row above the header states a month/year.", hidden);
        }
        preWarnings.addAll(checkMonthAgreement(name, month, days));

        List<String> warnings = new ArrayList<>(preWarnings);
        List<ParsedRecord> records = new ArrayList<>();
        List<String> employeeIds = new ArrayList<>();
        int emptyCells = 0;
        int sectionRows = 0;
        int weekdayRows = 0;
        Set<String> seenEmployees = new HashSet<>();
        Set<String> duplicateWarned = new HashSet<>();
        Set<String> usedCommentRefs = new HashSet<>();

        for (int r = h.dayCellsRow + 1; r < fr.rows; r++) {
            // Section / repeated-header rows (e.g. "Emp ID | Emp Name | 1 | 2 | ..."
            // framing a team block) are NOT employees and must be ignored.
            if (isRepeatedHeader(fr, r)) {
                sectionRows++;
                continue;
            }
            // Decorative weekday banners above a team block ("Sun Mon Tue ...")
            // are layout, not attendance — they must never become employee rows.
            if (isWeekdayBanner(fr, r, days)) {
                weekdayRows++;
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
                warnings.add("Row " + (r + 1) + " holds attendance values but column "
                        + columnName(Math.max(fields.getOrDefault(Field.EMP_ID, 0), 0))
                        + " has no employee id — the row was not imported.");
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
                String cellRef = new CellReference(r, dc.index).formatAsString();
                ExcelCommentExtractor.CellComment note = sheetComments.get(cellRef);
                if (note != null) {
                    usedCommentRefs.add(cellRef);
                }
                String status = HistoricalImportCodes.normalizeAttendanceStatus(raw);
                LocalDate date = resolveDate(dc, month);

                // Description sources for this one cell: an inline "STATUS - text"
                // value and/or a cell comment. When the raw value is a known status
                // followed by extra text it is split; otherwise the whole original
                // value is retained and the status left for manual mapping.
                String description = null;
                String descriptionSource = null;
                String descriptionAuthor = null;
                LocalDateTime descriptionAt = null;
                if (status != null && !HistoricalImportCodes.isKnown(status)) {
                    InlineSplit split = splitStatusAndText(raw);
                    if (split != null) {
                        status = split.code();
                        description = split.text();
                        descriptionSource = "EXCEL_CELL_TEXT";
                    } else {
                        description = blankToNull(raw);
                        descriptionSource = "IMPORTED_UNPARSED";
                    }
                }
                if (note != null && note.text() != null && !note.text().isBlank()) {
                    String noteText = note.text().trim();
                    if (description == null || description.isBlank()) {
                        description = noteText;
                        descriptionSource = note.source();
                    } else {
                        // Preserve both without letting either overwrite the other.
                        description = description + "\n" + noteText;
                        descriptionSource = "EXCEL_MULTI_SOURCE";
                    }
                    if (note.author() != null && !note.author().isBlank()) {
                        descriptionAuthor = note.author();
                    }
                    descriptionAt = note.at();
                }

                if (status == null) {
                    // The cell sits inside the attendance grid, so a status belongs
                    // here. Staging it with no status lets the admin correct or skip
                    // it; dropping it would turn a gap in the workbook into a
                    // silently missing attendance record.
                    emptyCells++;
                    records.add(new ParsedRecord(r + 1, dc.index + 1, empId, empName, email, location, manager,
                            shift, weekOff, date, null, null, null, null, false, "Blank status",
                            description, descriptionSource, descriptionAuthor, descriptionAt));
                    continue;
                }
                String warning = null;
                if (date == null) {
                    warning = "Day " + raw + " is not valid for month " + month + " — skipped.";
                }
                records.add(new ParsedRecord(r + 1, dc.index + 1, empId, empName, email, location, manager, shift, weekOff,
                        date, blankToNull(raw), HistoricalImportCodes.normaliseRaw(raw), status,
                        HistoricalImportCodes.nameOf(status) != null
                                ? HistoricalImportCodes.nameOf(status) : "Unknown",
                        !HistoricalImportCodes.isKnown(status), warning,
                        description, descriptionSource, descriptionAuthor, descriptionAt));
            }
        }

        if (sectionRows > 0) {
            warnings.add(sectionRows + " repeated header/section row(s) inside the sheet were ignored.");
        }
        if (weekdayRows > 0) {
            warnings.add(weekdayRows + " weekday banner row(s) above a team block were ignored.");
        }
        if (sheetComments.size() > usedCommentRefs.size()) {
            List<String> unused = sheetComments.keySet().stream()
                    .filter(ref -> !usedCommentRefs.contains(ref)).limit(5).toList();
            warnings.add((sheetComments.size() - usedCommentRefs.size())
                    + " comment(s) on cells outside the attendance grid were not imported (e.g. "
                    + String.join(", ", unused) + ").");
        }

        // Surface per-record warnings (e.g. invalid days) on the sheet too.
        for (ParsedRecord rec : records) {
            if (rec.warning() != null) {
                warnings.add(rec.warning());
            }
        }

        if (records.isEmpty()) {
            return skipped(name, SkipReason.NO_ROWS,
                    "Header row " + (h.headerRow() + 1) + " and " + days.size() + " date column(s) were detected, "
                            + "but no row below the header holds an employee id with attendance data. "
                            + "The first data row expected below row " + (h.dayCellsRow + 2) + ".",
                    hidden);
        }
        return ready(name, month, h, fields, days, employeeIds, emptyCells, records, warnings, hidden);
    }

    /** A recognised status code plus the trailing free text from one cell. */
    private record InlineSplit(String code, String text) {
    }

    /**
     * Conservative split of an inline "STATUS - text" cell such as
     * {@code "WO - Sat-Sun"} or {@code "FL(Dec 23)"}. Only splits when the
     * leading token is an <em>exactly recognised</em> status code, so an
     * unrecognised value is never mangled: it is returned as-is and flagged for
     * manual mapping instead.
     */
    private static final Pattern STATUS_SPLIT = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z0-9]{0,11})\\s*(?:[-–—:;.]|\\(|\\[)\\s*(.+?)\\s*[)\\]\\.]?$");

    private static InlineSplit splitStatusAndText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = STATUS_SPLIT.matcher(raw);
        if (!m.matches()) {
            return null;
        }
        String codePart = m.group(1);
        String rest = m.group(2);
        if (rest == null || rest.isBlank()) {
            return null;
        }
        String canonical = HistoricalImportCodes.canonicalOf(codePart);
        if (canonical == null) {
            return null;
        }
        return new InlineSplit(canonical, rest.trim());
    }

    private static SheetResult skipped(String name, SkipReason reason, String detail, boolean hidden) {
        // An explicitly non-roster name (Index, Furlough Leave, ...) or a hidden
        // tab is auxiliary by intent; everything else is judged on structure.
        boolean ignorable = reason.auxiliary() || ignorable(name) || hidden;
        return new SheetResult(name, null, null, 0, 0, 0, 0, 0, 0,
                true, reason.label(), ignorable, detail, List.of(), List.of(), List.of());
    }

    private static SheetResult ready(String name, YearMonth month, HeaderResult h,
                                     Map<Field, Integer> fields, List<DayColumn> days,
                                     List<String> employeeIds, int emptyCells,
                                     List<ParsedRecord> records, List<String> warnings,
                                     boolean hidden) {
        Set<String> distinct = new HashSet<>(employeeIds);
        int unknown = 0;
        for (ParsedRecord rec : records) {
            if (rec.unknown()) {
                unknown++;
            }
        }
        return new SheetResult(name, month, h.headerRow() + 1, fields.size(), days.size(),
                distinct.size(), records.size(), unknown, emptyCells, false, null, ignorable(name) || hidden,
                null, List.copyOf(employeeIds), records, warnings);
    }

    // ------------------------------------------------------------------
    // Structural classification helpers
    // ------------------------------------------------------------------

    /**
     * A weekday banner: every populated cell across the date columns holds a
     * weekday name. Real attendance rows never look like this, and without this
     * check such rows are mistaken for employees with unknown statuses.
     */
    private static boolean isWeekdayBanner(Frame fr, int r, List<DayColumn> days) {
        if (days.isEmpty()) {
            return false;
        }
        int labelled = 0, other = 0;
        for (DayColumn dc : days) {
            String v = fr.text(r, dc.index);
            if (isEmpty(v)) {
                continue;
            }
            if (WEEKDAY_LABELS.contains(v.trim().toLowerCase(Locale.ROOT))) {
                labelled++;
            } else {
                other++;
            }
        }
        return labelled > 0 && other == 0;
    }

    /**
     * Whether the sheet still carries the shape of an attendance roster even
     * though no header was recognised: several identity labels somewhere, or a
     * wide run of date-like cells, or many rows that start with an id-shaped
     * value. Used to tell "this was never a roster" from "this roster defeated
     * the header scan" — the latter must stay an error.
     */
    private static boolean rosterShaped(Frame fr) {
        int labels = 0, idShapedRows = 0;
        for (int r = 0; r < fr.rows; r++) {
            int rowDates = 0, rowDays = 0;
            for (int c = 0; c < fr.cols; c++) {
                String t = fr.text(r, c);
                if (isEmpty(t)) {
                    continue;
                }
                if (FIELD_BY_COMPACT.containsKey(HistoricalImportCodes.compactCode(t))) {
                    labels++;
                }
                if (fr.isDate[r][c] || parseFullDate(t) != null) {
                    rowDates++;
                }
                // Day numbers survive even when the header's date formatting is
                // lost, and a long run of them is unmistakably a calendar header.
                if (parseFullDate(t) == null && dayNumber(t) != null) {
                    rowDays++;
                }
            }
            if (rowDates >= 10 || rowDays >= 10) {
                return true;
            }
            String first = fr.text(r, 0);
            if (first != null && ID_SHAPED.matcher(first.trim()).matches()) {
                idShapedRows++;
            }
        }
        return labels >= 2 || idShapedRows >= 5;
    }

    private static final java.util.regex.Pattern ID_SHAPED =
            java.util.regex.Pattern.compile("\\d{4,}|[A-Za-z]+[-_ ]?\\d{2,}");

    /** Human-readable size/shape summary used in skip diagnostics. */
    private static String describeShape(Frame fr) {
        int filled = 0;
        for (int r = 0; r < fr.rows; r++) {
            for (int c = 0; c < fr.cols; c++) {
                if (!isEmpty(fr.text[r][c])) {
                    filled++;
                }
            }
        }
        return fr.rows + " row(s) x " + fr.cols + " column(s), " + filled + " populated cell(s)";
    }

    private static String describeFields(Map<Field, Integer> fields) {
        List<String> parts = new ArrayList<>();
        fields.forEach((f, idx) -> parts.add(columnName(idx) + "=" + f.name().toLowerCase(Locale.ROOT)));
        return parts.isEmpty() ? "no labelled columns" : String.join(", ", parts);
    }

    /** Spreadsheet column name for a 0-based index (0 -> A, 26 -> AA). */
    static String columnName(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        }
        return sb.toString();
    }

    /**
     * Checks the detected date columns form a usable sequence: adjacent columns,
     * and — when the header carries real dates — strictly increasing. Cross-month
     * spans are legitimate (a June roster often runs into the first days of July)
     * and are reported, never truncated.
     */
    private static List<String> validateDateSequence(String sheetName, List<DayColumn> days,
                                                     HeaderResult h, Frame fr, Map<Field, Integer> fields) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i < days.size(); i++) {
            if (days.get(i).index() != days.get(i - 1).index() + 1) {
                out.add("Date columns are not contiguous: column " + columnName(days.get(i - 1).index())
                        + " is followed by " + columnName(days.get(i).index()) + ".");
                break;
            }
        }
        LocalDate first = null, last = null;
        int badOrder = 0;
        for (DayColumn dc : days) {
            if (dc.date == null) {
                continue;
            }
            if (first == null) {
                first = dc.date;
            }
            if (last != null && !dc.date.isAfter(last)) {
                badOrder++;
            }
            last = dc.date;
        }
        if (badOrder > 0) {
            out.add(badOrder + " date column(s) are out of order or repeated between " + first + " and " + last
                    + "; every value is still imported at the date written in the header.");
        }
        if (first != null && last != null && !YearMonth.from(first).equals(YearMonth.from(last))) {
            out.add("Date columns span more than one calendar month (" + first + " to " + last
                    + "); every value is imported at its own date rather than being truncated to one month.");
        }
        boolean anyFullDate = days.stream().anyMatch(d -> d.date != null);
        if (!anyFullDate) {
            int maxDay = days.stream().map(DayColumn::day).filter(java.util.Objects::nonNull)
                    .mapToInt(Integer::intValue).max().orElse(0);
            if (maxDay > 31) {
                out.add("Day-number columns reach " + maxDay + "; values above 31 cannot be dates.");
            }
        }
        return out;
    }

    /**
     * When the sheet name states a different month than its own date columns
     * (a mislabelled tab), the data is kept exactly as written and the conflict
     * is surfaced so an admin can decide.
     */
    private static List<String> checkMonthAgreement(String sheetName, YearMonth month, List<DayColumn> days) {
        YearMonth fromName = parseYearMonth(sheetName);
        if (fromName == null || fromName.equals(month)) {
            return List.of();
        }
        LocalDate first = days.stream().map(DayColumn::date).filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        return List.of("Sheet name '" + sheetName + "' reads as " + fromName + " but its date columns start at "
                + first + "; records were imported at the dates written in the header (" + month + ").");
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
            if (HistoricalImportCodes.normalizeAttendanceStatus(fr.text(r, dc.index)) != null) {
                return true;
            }
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
        // Index rows absolutely (0-based == the real sheet row) so every row
        // number the parser reports back to the user is the row they see in
        // Excel. Sheets often start with empty rows; anchoring to the first
        // *populated* row would shift every reported row number.
        Frame fr = new Frame(last + 1, cols);

        for (int r = first; r <= last; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            for (int c = 0; c < cols; c++) {
                Cell cell = row.getCell(c);
                if (cell == null) continue;
                readCell(cell, ev, fr, r, c);
            }
        }

        // Expand merged regions by propagating the top-left value.
        for (CellRangeAddress rg : sheet.getMergedRegions()) {
            int r0 = rg.getFirstRow();
            int r1 = rg.getLastRow();
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
     * How many employee rows a candidate header would capture: rows beneath it
     * that carry an employee id and at least one attendance value across that
     * candidate's own date columns. Used to pick the header that holds the real
     * roster when a workbook repeats (and mislabels) it.
     */
    private static int employeeRowsBelow(Frame fr, int headerRow, int cols) {
        Map<Field, Integer> fields = new EnumMap<>(Field.class);
        for (int c = 0; c < cols; c++) {
            Field f = FIELD_BY_COMPACT.get(HistoricalImportCodes.compactCode(fr.text(headerRow, c)));
            if (f != null) {
                fields.putIfAbsent(f, c);
            }
        }
        Integer idCol = fields.get(Field.EMP_ID);
        if (idCol == null) {
            return 0;
        }
        List<DayColumn> days = detectDayColumns(fr, new HeaderResult(headerRow, headerRow), fields);
        if (days.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int r = headerRow + 1; r < fr.rows; r++) {
            String id = fr.text(r, idCol);
            if (isEmpty(id) || isRepeatedHeader(fr, r) || isWeekdayBanner(fr, r, days)) {
                continue;
            }
            if (hasDayValues(fr, r, days)) {
                n++;
            }
        }
        return n;
    }

    /**
     * Detection pipeline: inspect every row of the sheet (headers are never
     * assumed to be on row 1 — titles, logos, spacer rows and weekday banners
     * routinely precede them) and every column, scoring each row on how many
     * expected identity tokens (Emp ID, Emp Name, Employee Name, Email,
     * Location, Shift, WeekOff, Manager, ...) it carries plus how many date
     * columns follow.
     *
     * <p>A pure title row such as "VOICE ROSTER FOR May-26" scores 0 (no
     * identity tokens); the row holding the real header scores high.</p>
     *
     * <p>Ties are broken by <em>how much data each candidate would capture</em>.
     * Workbooks often repeat the header for a second team block, and one copy
     * can be mislabelled — picking the prettiest header instead of the one that
     * actually holds the roster would silently drop most of the employees. The
     * candidate framing the most employee rows therefore wins, and header
     * quality only breaks ties.</p>
     */
    private static HeaderResult detectHeader(Frame fr) {
        int maxRow = Math.min(fr.rows, HEADER_WINDOW_ROWS);
        int cols = Math.min(fr.cols, HEADER_WINDOW_COLS);
        List<Integer> candidates = new ArrayList<>();
        Map<Integer, Integer> quality = new HashMap<>();
        for (int r = 0; r < maxRow; r++) {
            boolean hasId = false, hasName = false;
            int metadata = 0, dates = 0;
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
            candidates.add(r);
            quality.put(r, metadata * 100 + (hasName ? 250 : 0) + dates);
        }
        if (candidates.isEmpty()) {
            return null;
        }

        int best = candidates.get(0);
        int bestVolume = -1, bestQuality = -1;
        for (int r : candidates) {
            int volume = employeeRowsBelow(fr, r, cols);
            int q = quality.get(r);
            // More captured employees wins; a better-formed header breaks ties;
            // the earlier row breaks remaining ties so the top block is used.
            if (volume > bestVolume || (volume == bestVolume && q > bestQuality)) {
                best = r;
                bestVolume = volume;
                bestQuality = q;
            }
        }

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
     * Rows below the header that restate identity labels are banner/section
     * headers framing a team block, never employees. Two signals:
     *
     * <ul>
     *   <li>the id cell or the name cell carries a header label verbatim
     *       ("Emp ID", "Employee ID", "EmpID", "Emp Name", ... on the compact,
     *       case/space/punctuation-free token), or</li>
     *   <li>two or more cells across columns 0..14 resolve to any metadata
     *       column label (id, name, email, location, shift, week-off, manager).</li>
     * </ul>
     */
    private static boolean isRepeatedHeader(Frame fr, int r) {
        int cols = Math.min(fr.cols, HEADER_WINDOW_COLS);
        boolean idCellLabel = false;
        boolean nameCellLabel = false;
        int labelCells = 0;
        for (int c = 0; c < cols; c++) {
            String compact = HistoricalImportCodes.compactCode(fr.text(r, c));
            Field f = FIELD_BY_COMPACT.get(compact);
            if (f == Field.EMP_ID && HEADER_ID_LABELS.contains(compact)) {
                idCellLabel = true;
            }
            if (f == Field.EMP_NAME && HEADER_NAME_LABELS.contains(compact)) {
                nameCellLabel = true;
            }
            if (f != null) {
                labelCells++;
            }
        }
        return idCellLabel || nameCellLabel || labelCells >= 2;
    }

    private static final Set<String> HEADER_ID_LABELS = Set.of(
            "empid", "employeeid", "empcode", "employeecode", "empno", "code", "slno", "sno");

    private static final Set<String> HEADER_NAME_LABELS = Set.of(
            "empname", "employeename", "name");

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