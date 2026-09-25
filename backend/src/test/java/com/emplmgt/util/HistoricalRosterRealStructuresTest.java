package com.emplmgt.util;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test suite built from nine real monthly roster structures seen in the wild:
 * JanFY25, AprilFY25, June'25, Nov"25, Jan"26, Feb-26, Apr-26, May-26 and
 * September-26. The parser must detect each layout purely from its structure —
 * nothing here is hardcoded by sheet name.
 */
class HistoricalRosterRealStructuresTest {

    private static CellStyle dateStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd-mmm-yyyy"));
        return s;
    }

    private static void text(Row r, int c, String v) {
        r.createCell(c).setCellValue(v);
    }

    private static void num(Row r, int c, int v) {
        r.createCell(c).setCellValue(v);
    }

    private static void dt(Row r, int c, LocalDate d, CellStyle style) {
        r.createCell(c).setCellValue(
                java.util.Date.from(d.atStartOfDay().atZone(java.time.ZoneId.systemDefault()).toInstant()));
        r.getCell(c).setCellStyle(style);
    }

    private static HistoricalRosterParser.SheetResult sheet(Workbook wb, String name) {
        return HistoricalRosterParser.parse(wb).sheets().stream()
                .filter(s -> s.sheetName().contentEquals(name))
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------- the nine months

    @Test
    void parsesJanFY25FlatSheet() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle ds = dateStyle(wb);
            Sheet s = wb.createSheet("JanFY25");
            text(s.createRow(0), 0, "Team Voice Attendance Report");
            Row h = s.createRow(1);
            text(h, 0, "Sl. No");
            text(h, 1, "Emp Code");
            text(h, 2, "Emp Name");
            text(h, 3, "Email");
            text(h, 4, "Location");
            dt(h, 5, LocalDate.of(2025, 1, 1), ds);
            dt(h, 6, LocalDate.of(2025, 1, 2), ds);
            Row d1 = s.createRow(2);
            num(d1, 0, 1);
            text(d1, 1, "250001");
            text(d1, 2, "Rahul Gupta");
            text(d1, 3, "rahul@hpe.com");
            text(d1, 4, "Noida");
            text(d1, 5, "WFO");
            text(d1, 6, "WO");
            Row d2 = s.createRow(3);
            num(d2, 0, 2);
            text(d2, 1, "250002");
            text(d2, 2, "Priya Sharma");
            text(d2, 3, "priya@hpe.com");
            text(d2, 4, "Noida");
            text(d2, 5, "WFH");
            text(d2, 6, "PL");

            var result = sheet(wb, "JanFY25");
            assertThat(result.skipped()).isFalse();
            assertThat(result.month()).isEqualTo(YearMonth.of(2025, 1));
            assertThat(result.records()).hasSize(4);
            assertThat(result.records().get(0).employeeName()).isEqualTo("Rahul Gupta");
            assertThat(result.records().get(0).statusCode()).isEqualTo("WFO");
            assertThat(result.records().get(1).statusCode()).isEqualTo("WO");
        }
    }

    @Test
    void parsesAprilFY25DayNumbersFromTitleRow() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Roster");
            text(s.createRow(0), 0, "VOICE ROSTER FOR AprilFY25");
            Row h = s.createRow(1);
            text(h, 0, "Employee Code");
            text(h, 1, "Employee Name");
            text(h, 2, "Manager");
            text(h, 3, "Shift");
            text(h, 4, "Week Off");
            num(h, 5, 1);
            num(h, 6, 2);
            num(h, 7, 3);
            Row d = s.createRow(2);
            text(d, 0, "25106149");
            text(d, 1, "Navin Kumar");
            text(d, 2, "Ritu");
            text(d, 3, "Day Shift");
            text(d, 4, "Sunday");
            text(d, 5, "WFO");
            text(d, 6, "WFO");
            text(d, 7, "WO");

            var result = sheet(wb, "Roster");
            assertThat(result.month()).isEqualTo(YearMonth.of(2025, 4));
            assertThat(result.headerRow()).isEqualTo(2);
            assertThat(result.dateColumnCount()).isEqualTo(3);
            assertThat(result.records()).hasSize(3);
            assertThat(result.records().get(0).attendanceDate()).isEqualTo(LocalDate.of(2025, 4, 1));
            assertThat(result.records().get(2).attendanceDate()).isEqualTo(LocalDate.of(2025, 4, 3));
            assertThat(result.records().get(0).shift()).isEqualTo("Day Shift");
            assertThat(result.records().get(0).weekOff()).isEqualTo("Sunday");
        }
    }

    @Test
    void parsesJuneTwoLineHeaderWithRealDatesBelow() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle ds = dateStyle(wb);
            Sheet s = wb.createSheet("June'25");
            Row h = s.createRow(0);
            text(h, 0, "Emp ID");
            text(h, 1, "Emp Name");
            num(h, 2, 1);
            num(h, 3, 2);
            num(h, 4, 3);
            Row dates = s.createRow(1);
            dt(dates, 2, LocalDate.of(2025, 6, 1), ds);
            dt(dates, 3, LocalDate.of(2025, 6, 2), ds);
            dt(dates, 4, LocalDate.of(2025, 6, 3), ds);
            Row d = s.createRow(2);
            text(d, 0, "E1");
            text(d, 1, "Alice");
            text(d, 2, "SL");
            text(d, 3, "WFO");
            text(d, 4, "PL");

            var result = sheet(wb, "June'25");
            assertThat(result.month()).isEqualTo(YearMonth.of(2025, 6));
            assertThat(result.records()).hasSize(3);
            assertThat(result.records().get(0).attendanceDate()).isEqualTo(LocalDate.of(2025, 6, 1));
            assertThat(result.records().get(0).statusCode()).isEqualTo("SL");
        }
    }

    @Test
    void parsesNovCurlyQuoteWithSunMonExcelDateHeaderAndMessyCodes() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle ds = dateStyle(wb);
            Sheet s = wb.createSheet("Nov\u201D25");
            text(s.createRow(0), 0, "VOICE ROSTER FOR Nov\u201D25");
            Row h = s.createRow(1);
            text(h, 0, "Emp ID");
            text(h, 1, "Emp Name");
            text(h, 2, "Location");
            text(h, 3, "Shift");
            text(h, 4, "WeekOff");
            dt(h, 5, LocalDate.of(2025, 11, 3), ds);
            dt(h, 6, LocalDate.of(2025, 11, 4), ds);
            dt(h, 7, LocalDate.of(2025, 11, 5), ds);
            Row d1 = s.createRow(2);
            text(d1, 0, "E1");
            text(d1, 1, "Rahul");
            text(d1, 2, "Noida");
            text(d1, 3, "DAY");
            text(d1, 4, "Sun");
            text(d1, 5, "wfh");
            text(d1, 6, " WFH ");
            text(d1, 7, "Wo");
            Row d2 = s.createRow(3);
            text(d2, 0, "E2");
            text(d2, 1, "Bob");
            text(d2, 2, "Bangalore");
            text(d2, 3, "NIGHT");
            text(d2, 4, "Sat");
            text(d2, 5, "SILVER DUTY");
            text(d2, 6, "HPE HOLIDAY");
            text(d2, 7, "PL");

            var result = sheet(wb, "Nov\u201D25");
            assertThat(result.month()).isEqualTo(YearMonth.of(2025, 11));
            assertThat(result.records()).hasSize(6);
            assertThat(result.records().get(0).statusCode()).isEqualTo("WFH");
            assertThat(result.records().get(1).statusCode()).isEqualTo("WFH");
            assertThat(result.records().get(2).statusCode()).isEqualTo("WO");
            assertThat(result.records().get(3).statusCode()).isEqualTo("SILVER DUTY");
            assertThat(result.records().get(3).unknown()).isTrue();
            assertThat(result.records().get(4).statusCode()).isEqualTo("HPEH");
            assertThat(result.records().get(0).location()).isEqualTo("Noida");
            assertThat(result.records().get(0).shift()).isEqualTo("DAY");
            assertThat(result.records().get(0).weekOff()).isEqualTo("Sun");
        }
    }

    @Test
    void parsesJanCurlyQuoteDayNumbersAndKeepsRawVsNormalized() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Jan\u201D26");
            Row h = s.createRow(0);
            text(h, 0, "Emp Code");
            text(h, 1, "Emp Name");
            text(h, 2, "Email");
            num(h, 3, 1);
            num(h, 4, 2);
            num(h, 5, 3);
            Row d = s.createRow(1);
            text(d, 0, "E1");
            text(d, 1, "Alice");
            text(d, 2, " alice@hpe.com  ");
            text(d, 3, "SW  OFF");
            text(d, 4, " wk wrk ");
            text(d, 5, "SILVER DUTY");

            var result = sheet(wb, "Jan\u201D26");
            assertThat(result.month()).isEqualTo(YearMonth.of(2026, 1));
            assertThat(result.records()).hasSize(3);

            assertThat(result.records().get(0).statusCode()).isEqualTo("SW OFF");
            assertThat(result.records().get(0).normalizedCode()).isEqualTo("SW OFF");
            assertThat(result.records().get(0).rawCode()).isEqualTo("SW  OFF");

            assertThat(result.records().get(1).statusCode()).isEqualTo("WK WRK");
            assertThat(result.records().get(1).rawCode()).isEqualTo("wk wrk");

            assertThat(result.records().get(2).statusCode()).isEqualTo("SILVER DUTY");
            assertThat(result.records().get(2).normalizedCode()).isEqualTo("SILVER DUTY");
            assertThat(result.records().get(2).unknown()).isTrue();

            assertThat(result.records().get(0).email()).isEqualTo("alice@hpe.com");
        }
    }

    @Test
    void parsesFebDashTextDates() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Feb-26");
            Row h = s.createRow(0);
            text(h, 0, "Emp ID");
            text(h, 1, "Emp Name");
            text(h, 2, "01/02/2026");
            text(h, 3, "02/02/2026");
            Row d = s.createRow(1);
            text(d, 0, "E1");
            text(d, 1, "Alice");
            text(d, 2, "WFO");
            text(d, 3, "SL");

            var result = sheet(wb, "Feb-26");
            assertThat(result.month()).isEqualTo(YearMonth.of(2026, 2));
            assertThat(result.records().get(0).attendanceDate()).isEqualTo(LocalDate.of(2026, 2, 1));
            assertThat(result.records().get(1).attendanceDate()).isEqualTo(LocalDate.of(2026, 2, 2));
        }
    }

    @Test
    void parsesAprWithSectionHeadersAndEmptyRowsSkipped() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Apr-26");
            text(s.createRow(0), 0, "April-26 Roster");
            Row h = s.createRow(1);
            text(h, 0, "Emp ID");
            text(h, 1, "Emp Name");
            text(h, 2, "Location");
            num(h, 3, 1);
            num(h, 4, 2);
            num(h, 5, 3);
            Row e1 = s.createRow(2);
            text(e1, 0, "E1");
            text(e1, 1, "Alice");
            text(e1, 2, "Noida");
            text(e1, 3, "WFO");
            text(e1, 4, "WFO");
            text(e1, 5, "WO");
            Row empty = s.createRow(3);
            for (int c = 0; c < 6; c++) {
                empty.getCell(c); // never create it — row stays completely blank
            }
            Row e2 = s.createRow(4);
            text(e2, 0, "E2");
            text(e2, 1, "Bob");
            text(e2, 2, "Noida");
            text(e2, 3, "WFH");
            text(e2, 4, "WFH");
            text(e2, 5, "SL");
            Row banner = s.createRow(5);
            text(banner, 0, "Apr-26 Roster - Team B");
            Row section = s.createRow(6);
            text(section, 0, "Emp ID");
            text(section, 1, "Emp Name");
            text(section, 2, "Location");
            num(section, 3, 1);
            num(section, 4, 2);
            num(section, 5, 3);
            Row e3 = s.createRow(7);
            text(e3, 0, "E3");
            text(e3, 1, "Carol");
            text(e3, 2, "Bangalore");
            text(e3, 3, "SW OFF");
            text(e3, 4, "WK WRK");
            text(e3, 5, "PL");

            var result = sheet(wb, "Apr-26");
            assertThat(result.month()).isEqualTo(YearMonth.of(2026, 4));
            assertThat(result.records()).hasSize(9);
            assertThat(result.employeeIds()).containsExactly("E1", "E2", "E3");
            assertThat(result.warnings()).anyMatch(w -> w.contains("section/header row"));
            assertThat(result.records().get(6).statusCode()).isEqualTo("SW OFF");
            assertThat(result.records().get(8).statusCode()).isEqualTo("PL");
        }
    }

    @Test
    void parsesMayWithUntokenisedColumnAmongMetadata() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("May-26");
            text(s.createRow(0), 0, "VOICE ROSTER FOR May-26");
            Row h = s.createRow(1);
            text(h, 0, "Sl. No");
            text(h, 1, "Emp Code");
            text(h, 2, "Emp Name");
            text(h, 3, "Designation");
            text(h, 4, "Location");
            num(h, 5, 1);
            num(h, 6, 2);
            num(h, 7, 3);
            Row d = s.createRow(2);
            num(d, 0, 1);
            text(d, 1, "E1");
            text(d, 2, "Alice");
            text(d, 3, "SWE");
            text(d, 4, "Noida");
            text(d, 5, "WFO");
            text(d, 6, "WO");
            text(d, 7, "WFH");

            var result = sheet(wb, "May-26");
            assertThat(result.month()).isEqualTo(YearMonth.of(2026, 5));
            assertThat(result.employeeColumnCount()).isEqualTo(3);
            assertThat(result.dateColumnCount()).isEqualTo(3);
            assertThat(result.records()).hasSize(3);
            assertThat(result.records().get(0).attendanceDate()).isEqualTo(LocalDate.of(2026, 5, 1));
            assertThat(result.records().get(2).statusCode()).isEqualTo("WFH");
        }
    }

    @Test
    void parsesSeptemberHeaderNearBottomOfInspectionWindow() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("September-26");
            for (int r = 0; r < 14; r++) {
                text(s.createRow(r), 0, "VOICE ROSTER September-26");
            }
            s.addMergedRegion(new CellRangeAddress(0, 13, 0, 5));
            Row h = s.createRow(14);
            text(h, 0, "Emp ID");
            text(h, 1, "Emp Name");
            text(h, 2, "Email");
            text(h, 3, "Location");
            num(h, 4, 1);
            num(h, 5, 2);
            num(h, 6, 3);
            num(h, 7, 4);
            Row d = s.createRow(15);
            text(d, 0, "E1");
            text(d, 1, "Alice");
            text(d, 2, "a@hpe.com");
            text(d, 3, "Noida");
            text(d, 4, "WFO");
            text(d, 5, "WFO");
            text(d, 6, "SL");
            text(d, 7, "PL");

            var result = sheet(wb, "September-26");
            assertThat(result.skipped()).isFalse();
            assertThat(result.month()).isEqualTo(YearMonth.of(2026, 9));
            assertThat(result.headerRow()).isEqualTo(15);
            assertThat(result.records()).hasSize(4);
            assertThat(result.records().get(2).attendanceDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        }
    }

    // ------------------------------------------------- future / arbitrary names

    @Test
    void parsesFutureSheetNamesWithoutHardcoding() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet a = wb.createSheet("October-26");
            Row ah = a.createRow(0);
            text(ah, 0, "Emp ID");
            text(ah, 1, "Emp Name");
            text(ah, 2, "01/10/2026");
            text(ah, 3, "02/10/2026");
            Row ad = a.createRow(1);
            text(ad, 0, "E1");
            text(ad, 1, "Alice");
            text(ad, 2, "WO");
            text(ad, 3, "WFH");

            Sheet b = wb.createSheet("October 2026 Roster");
            Row bh = b.createRow(0);
            text(bh, 0, "Emp ID");
            text(bh, 1, "Emp Name");
            num(bh, 2, 1);
            num(bh, 3, 2);
            Row bd = b.createRow(1);
            text(bd, 0, "E2");
            text(bd, 1, "Bob");
            text(bd, 2, "WFO");
            text(bd, 3, "PL");

            var octDash = sheet(wb, "October-26");
            assertThat(octDash.month()).isEqualTo(YearMonth.of(2026, 10));
            assertThat(octDash.records()).hasSize(2);

            var octName = sheet(wb, "October 2026 Roster");
            assertThat(octName.month()).isEqualTo(YearMonth.of(2026, 10));
            assertThat(octName.records()).hasSize(2);
            assertThat(octName.records().get(1).statusCode()).isEqualTo("PL");
        }
    }

    // -------------------------------------------------------------- behaviour

    @Test
    void ignoresCompletelyEmptyEmployeeRows() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Mar-26");
            Row h = s.createRow(0);
            text(h, 0, "Emp ID");
            text(h, 1, "Emp Name");
            text(h, 2, "01/03/2026");
            text(h, 3, "02/03/2026");
            Row d1 = s.createRow(1);
            text(d1, 0, "E1");
            text(d1, 1, "Alice");
            text(d1, 2, "WFO");
            text(d1, 3, "WO");
            Row blank = s.createRow(2);
            for (int c = 0; c < 4; c++) {
                blank.getCell(c);
            }
            Row d2 = s.createRow(3);
            text(d2, 0, "E2");
            text(d2, 1, "Bob");
            text(d2, 2, "WFH");
            text(d2, 3, "SL");

            var result = sheet(wb, "Mar-26");
            assertThat(result.records()).hasSize(4);
            assertThat(result.employeeIds()).containsExactly("E1", "E2");
            assertThat(result.warnings()).noneMatch(w -> w.contains("empty"));
        }
    }

    @Test
    void detectsDuplicateEmployeeRowsWithinSheet() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Jul-26");
            Row h = s.createRow(0);
            text(h, 0, "Emp ID");
            text(h, 1, "Emp Name");
            text(h, 2, "01/07/2026");
            Row d1 = s.createRow(1);
            text(d1, 0, "E1");
            text(d1, 1, "Alice");
            text(d1, 2, "WFO");
            Row d2 = s.createRow(2);
            text(d2, 0, "E1");
            text(d2, 1, "Alice again");
            text(d2, 2, "PL");

            var result = sheet(wb, "Jul-26");
            assertThat(result.employeeIds()).containsExactly("E1", "E1");
            assertThat(result.warnings()).anyMatch(w -> w.contains("more than one row"));
        }
    }

    @Test
    void preservesMetadataApplicableToEachMonthlyRoster() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            for (int i = 0; i < 2; i++) {
                String name = i == 0 ? "Aug-26" : "Sep-26";
                String empName = i == 0 ? "Rahul Gupta" : "Rahul G";
                String location = i == 0 ? "Noida" : "Bangalore";
                String monthDay = i == 0 ? "01/08/2026" : "01/09/2026";
                Sheet s = wb.createSheet(name);
                Row h = s.createRow(0);
                text(h, 0, "Emp ID");
                text(h, 1, "Emp Name");
                text(h, 2, "Location");
                text(h, 3, monthDay);
                Row d = s.createRow(1);
                text(d, 0, "25106149");
                text(d, 1, empName);
                text(d, 2, location);
                text(d, 3, "WFO");
            }

            var aug = sheet(wb, "Aug-26").records().get(0);
            var sep = sheet(wb, "Sep-26").records().get(0);
            assertThat(aug.employeeName()).isEqualTo("Rahul Gupta");
            assertThat(aug.location()).isEqualTo("Noida");
            assertThat(aug.attendanceDate()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(sep.employeeName()).isEqualTo("Rahul G");
            assertThat(sep.location()).isEqualTo("Bangalore");
            assertThat(sep.attendanceDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        }
    }

    @Test
    void headerScoringExampleTitleRowScoresZero() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle ds = dateStyle(wb);
            Sheet s = wb.createSheet("May-26");
            text(s.createRow(0), 0, "VOICE ROSTER FOR May-26");
            Row title2 = s.createRow(1);
            text(title2, 0, "");
            Row h = s.createRow(2);
            text(h, 0, "Emp ID");
            text(h, 1, "Emp Name");
            text(h, 2, "Location");
            text(h, 3, "Shift");
            text(h, 4, "WeekOff");
            dt(h, 5, LocalDate.of(2026, 5, 4), ds);
            dt(h, 6, LocalDate.of(2026, 5, 5), ds);
            Row d = s.createRow(3);
            text(d, 0, "E1");
            text(d, 1, "Alice");
            text(d, 2, "Noida");
            text(d, 3, "DAY");
            text(d, 4, "Sun");
            text(d, 5, "WFO");
            text(d, 6, "WO");

            var result = sheet(wb, "May-26");
            // Title rows (score 0) are skipped; the real header at row 3 is chosen.
            assertThat(result.headerRow()).isEqualTo(3);
            assertThat(result.records()).hasSize(2);
            assertThat(result.month()).isEqualTo(YearMonth.of(2026, 5));
        }
    }
}