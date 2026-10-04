package com.emplmgt.util;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression suite run against the real {@code Leave Tracker 2025} workbook.
 *
 * <p>This file is the source of the "Completely unreadable sheet — sheet:
 * June'25" report. What actually happened: the neighbouring
 * {@code June'25_ leave} notes tab was never an attendance roster, but because
 * the parser decided "is this a roster?" from a hardcoded list of sheet-name
 * fragments that did not include "leave", the tab was reported as a corrupt
 * roster — with a message that named no reason and no row.</p>
 *
 * <p>The whole workbook is parsed here so that every month, past and future, is
 * covered: the fix lives in the shared parser and must not be a special case
 * for one tab.</p>
 */
class HistoricalRosterLeaveTracker2025Test {

    private static HistoricalRosterParser.ParsedWorkbook parsed;

    private static HistoricalRosterParser.SheetResult sheet(String name) {
        return parsed.sheets().stream()
                .filter(s -> s.sheetName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Sheet not parsed at all: " + name));
    }

    @BeforeAll
    static void parseWorkbookOnce() throws Exception {
        try (InputStream in = HistoricalRosterLeaveTracker2025Test.class
                .getResourceAsStream("/fixtures/leave-tracker-2025.xlsx");
             Workbook wb = new XSSFWorkbook(in)) {
            parsed = HistoricalRosterParser.parse(wb);
        }
    }

    // -------------------------------------------------- the reported failure

    /**
     * The exact regression: June'25 must import, and the adjacent leave notes
     * tab must be reported as ignored rather than as a broken roster.
     */
    @Test
    void june25RosterImportsAndIsNotFlaggedUnreadable() {
        HistoricalRosterParser.SheetResult june = sheet("June'25");

        assertThat(june.skipped()).as("June'25 must import").isFalse();
        assertThat(june.skipReason()).isNull();
        assertThat(june.month()).isEqualTo(YearMonth.of(2025, 6));
        assertThat(june.records()).isNotEmpty();

        HistoricalRosterParser.SheetResult leaveNotes = sheet("June'25_ leave");
        assertThat(leaveNotes.skipped()).isTrue();
        assertThat(leaveNotes.ignorable())
                .as("a leave notes tab is auxiliary, never a fatal error").isTrue();
        assertThat(leaveNotes.skipDetail())
                .contains("June'25_ leave".substring(0, 0) + "No 'Emp ID'")
                .contains("no attendance grid");
    }

    /** June'25: header sits on row 4, not row 1, under a weekday banner. */
    @Test
    void june25HeaderIsFoundOnItsRealRowBelowLeadingBlankAndWeekdayRows() {
        HistoricalRosterParser.SheetResult june = sheet("June'25");

        // Row 4 of the worksheet, reported as a true 1-based sheet row.
        assertThat(june.headerRow()).isEqualTo(4);
        // Emp ID, Emp Name, Location, Shift, WeekOff — this tab has no Manager
        // column, unlike its neighbours, and the metadata must still map.
        assertThat(june.employeeColumnCount()).isEqualTo(5);
        assertThat(june.dateColumnCount()).isEqualTo(35);
    }

    /** Every employee and attendance value on June'25 survives the parse. */
    @Test
    void june25PreservesEveryEmployeeAndAttendanceValue() {
        HistoricalRosterParser.SheetResult june = sheet("June'25");

        assertThat(june.employeeIds()).contains("20342230", "60175312", "60179383", "25081078");
        assertThat(june.employeeCount()).isEqualTo(35);

        HistoricalRosterParser.ParsedRecord first = june.records().stream()
                .filter(r -> r.employeeId().equals("20342230"))
                .findFirst().orElseThrow();
        assertThat(first.employeeName()).isEqualTo("Mariyappa, Sahana");
        assertThat(first.location()).isEqualTo("BLR");
        assertThat(first.shift()).isEqualTo("05:30-14:30");
        assertThat(first.weekOff()).isEqualTo("Mon-Tues");
        assertThat(first.attendanceDate()).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(first.statusCode()).isEqualTo("WFH");
        assertThat(first.unknown()).isFalse();

        // This tab runs 1 June into 5 July: 35 columns, every one preserved at
        // its own date rather than being clipped to the sheet's month.
        List<LocalDate> dates = june.records().stream()
                .filter(r -> r.employeeId().equals("20342230"))
                .map(HistoricalRosterParser.ParsedRecord::attendanceDate)
                .toList();
        assertThat(dates).hasSize(35).doesNotContainNull();
        assertThat(dates.stream().filter(d -> d.getYear() == 2025 && d.getMonthValue() == 6).count()).isEqualTo(30);
        assertThat(dates.stream().filter(d -> d.getYear() == 2025 && d.getMonthValue() == 7).count()).isEqualTo(5);
        assertThat(dates.get(0)).isEqualTo(LocalDate.of(2025, 6, 1));
        assertThat(dates.get(34)).isEqualTo(LocalDate.of(2025, 7, 5));

        assertThat(june.records()).allSatisfy(r -> {
            assertThat(r.attendanceDate()).as("row %d col %d", r.sourceRow(), r.sourceColumn()).isNotNull();
            assertThat(r.employeeId()).isNotBlank();
        });
    }

    /** Decorative weekday banners must never become employees. */
    @Test
    void weekdayBannerRowsAreNotImportedAsEmployees() {
        HistoricalRosterParser.SheetResult june = sheet("June'25");

        assertThat(june.warnings())
                .anyMatch(w -> w.contains("weekday banner row(s)"))
                .noneMatch(w -> w.contains("no employee id"));
        assertThat(june.employeeIds()).noneMatch(id -> id.equalsIgnoreCase("Sun"));
    }

    /**
     * The 1,970-entry review gate was caused by blank grid cells and layout
     * tables (names, ratios, shift-slot labels) being staged as attendance.
     * No real attendance record may ever be staged without a status: a blank
     * cell is a gap, not a record.
     */
    @Test
    void noBlankOrLayoutCellsAreStagedAsAttendanceRecords() {
        parsed.sheets().stream()
                .filter(s -> !s.skipped())
                .flatMap(s -> s.records().stream())
                .forEach(r -> {
                    assertThat(r.statusCode())
                            .as("blank status at row %d col %d", r.sourceRow(), r.sourceColumn())
                            .isNotBlank();
                    assertThat(r.attendanceDate())
                            .as("undated record at row %d col %d", r.sourceRow(), r.sourceColumn())
                            .isNotNull();
                    assertThat(r.employeeId())
                            .as("missing employee id at row %d col %d", r.sourceRow(), r.sourceColumn())
                            .isNotBlank();
                });
    }

    // -------------------------------------------------- requested regression set

    @Test
    void everyMonthlyRosterInTheWorkbookImports() {
        for (String name : List.of("MayFY25", "June'25", "July'25", "October'25", "Dec\"25",
                "Jan\"26", "Feb-26", "Mar-26", "Apr-26", "May-26", "Jun-26", "July-26",
                "August-26", "September-26", "October-26",
                "JanFY25", "FebFY25", "MarFY25", "AprilFY25", "August'25", "September'25", "Nov\"25")) {
            HistoricalRosterParser.SheetResult s = sheet(name);
            assertThat(s.skipped()).as("%s must import", name).isFalse();
            assertThat(s.month()).as("%s month", name).isNotNull();
            assertThat(s.employeeCount()).as("%s employees", name).isPositive();
            assertThat(s.cellCount()).as("%s attendance cells", name).isPositive();
            assertThat(s.headerRow()).as("%s header row", name).isPositive();
        }
    }

    /** A header buried far below row 1 is still found (October'26 sits on row 12). */
    @Test
    void headersAreFoundEvenWhenFarBelowTheFirstRow() {
        assertThat(sheet("October-26").headerRow()).isEqualTo(12);
        assertThat(sheet("October-26").employeeCount()).isPositive();
    }

    /**
     * October'25 carries a mislabelled first header block ("Emp ID" repeated
     * across B–D) and a correctly labelled second one. The block holding the
     * roster must win, so the employees are not silently dropped.
     */
    @Test
    void headerChoiceKeepsTheBlockThatHoldsTheMostEmployees() {
        HistoricalRosterParser.SheetResult oct = sheet("October'25");
        assertThat(oct.skipped()).isFalse();
        assertThat(oct.headerRow()).isEqualTo(4);
        assertThat(oct.employeeCount()).isEqualTo(29);
        // 808 filled attendance cells. The 4 empty grid cells are gaps in the
        // source, not attendance entries, so they are counted as empty cells and
        // never staged as records that would have to be reviewed before commit.
        assertThat(oct.cellCount()).isEqualTo(808);
        assertThat(oct.emptyCellCount()).isEqualTo(4);
    }

    /** Spanning several calendar months is reported, never truncated. */
    @Test
    void multiMonthDateSpansAreReportedAndKept() {
        HistoricalRosterParser.SheetResult june = sheet("June'25");
        assertThat(june.warnings())
                .anyMatch(w -> w.contains("span more than one calendar month"))
                .anyMatch(w -> w.contains("2025-06-01") && w.contains("2025-07-05"));
        assertThat(june.records().stream().map(HistoricalRosterParser.ParsedRecord::attendanceDate))
                .anyMatch(d -> d != null && d.getMonthValue() == 7);
    }

    /** A tab whose name disagrees with its own date columns is surfaced, data kept. */
    @Test
    void mismatchedSheetNameIsFlaggedWithoutDiscardingData() {
        HistoricalRosterParser.SheetResult feb = sheet("FebFY25");
        assertThat(feb.skipped()).isFalse();
        assertThat(feb.warnings()).anyMatch(w -> w.contains("reads as 2025-02"));
        assertThat(feb.records()).isNotEmpty();
    }

    /** Sheet names are normalised, not matched against a hardcoded list. */
    @Test
    void sheetNamesAreNormalisedRatherThanHardcoded() {
        assertThat(sheet("June'25").month()).isEqualTo(YearMonth.of(2025, 6));
        assertThat(sheet("Nov\"25").month()).isEqualTo(YearMonth.of(2025, 11));
        assertThat(sheet("Feb-26").month()).isEqualTo(YearMonth.of(2026, 2));
        assertThat(sheet("September-26").month()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(sheet("AprilFY25").month()).isEqualTo(YearMonth.of(2025, 4));
    }

    // -------------------------------------------------- auxiliary tabs

    @Test
    void nonRosterTabsAreIgnoredWithAReasonRatherThanReportedAsBroken() {
        for (String name : List.of("#RTS Count", "June'25_ leave", "Furlough Leave", "Index")) {
            HistoricalRosterParser.SheetResult s = sheet(name);
            assertThat(s.skipped()).as("%s is skipped", name).isTrue();
            assertThat(s.ignorable()).as("%s must not be a fatal error", name).isTrue();
            assertThat(s.skipDetail()).as("%s states why", name).isNotBlank();
        }
    }

    /** No sheet in the workbook may fail with an unexplained reason. */
    @Test
    void noSheetFailsWithoutAnExplanation() {
        assertThat(parsed.sheets())
                .filteredOn(HistoricalRosterParser.SheetResult::skipped)
                .allSatisfy(s -> {
                    assertThat(s.skipDetail()).as("%s detail", s.sheetName()).isNotBlank();
                    assertThat(s.skipReason()).as("%s reason", s.sheetName()).isNotBlank();
                });
        assertThat(parsed.globalWarnings()).isEmpty();
    }

    /** A blank sheet is auxiliary; a roster-shaped sheet that fails is an error. */
    @Test
    void blankAndRosterShapedFailuresAreClassifiedDifferently() {
        try (Workbook wb = new XSSFWorkbook()) {
            wb.createSheet("Empty Tab");
            HistoricalRosterParser.SheetResult blank =
                    HistoricalRosterParser.parse(wb).sheets().get(0);
            assertThat(blank.skipped()).isTrue();
            assertThat(blank.ignorable()).as("a blank tab is auxiliary").isTrue();
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        // A roster whose header labels are unrecognisable must stay fatal so the
        // admin is told exactly what was searched, rather than being ignored.
        try (Workbook wb = new XSSFWorkbook()) {
            var s = wb.createSheet("Weird");
            var hdr = s.createRow(0);
            hdr.createCell(0).setCellValue("person ref");
            hdr.createCell(1).setCellValue("who");
            var ds = wb.createCellStyle();
            ds.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd-mmm-yyyy"));
            for (int c = 2; c < 32; c++) {
                hdr.createCell(c).setCellValue(LocalDate.of(2025, 6, c - 1));
                hdr.getCell(c).setCellStyle(ds);
            }
            for (int i = 0; i < 12; i++) {
                var d = s.createRow(i + 1);
                d.createCell(0).setCellValue("P" + i);
                d.createCell(1).setCellValue("Name " + i);
                for (int c = 2; c < 32; c++) {
                    d.createCell(c).setCellValue("WO");
                }
            }
            HistoricalRosterParser.SheetResult result =
                    HistoricalRosterParser.parse(wb).sheets().get(0);
            assertThat(result.skipped()).isTrue();
            assertThat(result.ignorable())
                    .as("a roster-shaped sheet with an unreadable header stays fatal").isFalse();
            assertThat(result.skipDetail())
                    .contains("No 'Emp ID' header found")
                    .contains("attendance roster")
                    .contains("rows 1\u2013");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    // -------------------------------------------------- cell comments -> descriptions

    /**
     * The real workbook stores its remarks as modern threaded comments, which
     * POI cannot read. The parser must pull F5 of June'25 (Sahana's
     * "LO at 1pm , Informed Anoushka") through as the description for that cell
     * while keeping the WFH status intact.
     */
    @Test
    void june25ThreadedCommentBecomesCellDescription() {
        HistoricalRosterParser.SheetResult june = sheet("June'25");
        HistoricalRosterParser.ParsedRecord record = june.records().stream()
                .filter(r -> r.sourceRow() == 5 && r.sourceColumn() == 6)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No record for June'25 cell F5"));

        assertThat(record.employeeId()).isEqualTo("20342230");
        assertThat(record.statusCode()).isEqualTo("WFH");
        assertThat(record.description()).isEqualTo("LO at 1pm , Informed Anoushka");
        assertThat(record.descriptionSource()).isEqualTo(ExcelCommentExtractor.SOURCE_THREADED);
        assertThat(record.descriptionAuthor()).isEqualTo("Mariyappa, Sahana");
        assertThat(record.descriptionAt())
                .isEqualTo(java.time.LocalDateTime.of(2025, 6, 1, 0, 54, 7, 760_000_000));
    }

    /** No description ever surfaces POI's "[Threaded comment] ... placeholder". */
    @Test
    void threadedCommentPlaceholderIsNeverStoredAsDescription() {
        long withDescription = parsed.sheets().stream()
                .flatMap(s -> s.records().stream())
                .filter(r -> r.description() != null && !r.description().isBlank())
                .count();
        assertThat(withDescription)
                .as("threaded comments across the workbook become descriptions")
                .isGreaterThan(200);

        assertThat(parsed.sheets().stream()
                .flatMap(s -> s.records().stream())
                .map(HistoricalRosterParser.ParsedRecord::description)
                .filter(java.util.Objects::nonNull))
                .noneMatch(d -> d.contains("Your version of Excel"));
    }
}