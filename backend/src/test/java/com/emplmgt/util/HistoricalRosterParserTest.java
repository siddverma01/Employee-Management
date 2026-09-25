package com.emplmgt.util;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalRosterParserTest {

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

    private static List<HistoricalRosterParser.SheetResult> parseWorkbook(Workbook wb) {
        return HistoricalRosterParser.parse(wb).sheets();
    }

    @Test
    void parsesFlatSheetWithTitleRowsAndDifferentColumnOrder() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle ds = dateStyle(wb);
            Sheet s = wb.createSheet("Attendance Sep 2025");
            // Merged title block (row 0), title month (row 1), then header at row 2.
            s.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 8));
            Row title0 = s.createRow(0);
            text(title0, 0, "Team Voice - Attendance Report");
            Row t = s.createRow(1);
            text(t, 0, "September 2025");
            Row h = s.createRow(2);
            text(h, 0, "Sl. No");
            text(h, 1, "Emp Code");
            text(h, 2, "Emp Name");
            text(h, 3, "Email");
            text(h, 4, "Location");
            dt(h, 5, LocalDate.of(2025, 9, 1), ds);
            dt(h, 6, LocalDate.of(2025, 9, 2), ds);
            dt(h, 7, LocalDate.of(2025, 9, 3), ds);

            Row d1 = s.createRow(3);
            num(d1, 0, 1);
            text(d1, 1, "25106149");
            text(d1, 2, "Rahul Gupta");
            text(d1, 3, "rahul.gupta@hpe.com");
            text(d1, 4, "Noida");
            text(d1, 5, "WFO");
            text(d1, 6, "WFO");
            text(d1, 7, "WO");
            Row d2 = s.createRow(4);
            num(d2, 0, 2);
            text(d2, 1, "25106150");
            text(d2, 2, "Priya Sharma");
            text(d2, 3, "priya@hpe.com");
            text(d2, 4, "Bangalore");
            text(d2, 5, "PL");
            text(d2, 6, "WFH");
            text(d2, 7, "WFH");

            var result = parseWorkbook(wb).get(0);
            assertThat(result.skipped()).isFalse();
            assertThat(result.records()).hasSize(6);
            HistoricalRosterParser.ParsedRecord first = result.records().get(0);
            assertThat(first.employeeId()).isEqualTo("25106149");
            assertThat(first.employeeName()).isEqualTo("Rahul Gupta");
            assertThat(first.email()).isEqualTo("rahul.gupta@hpe.com");
            assertThat(first.attendanceDate()).isEqualTo(LocalDate.of(2025, 9, 1));
            assertThat(first.statusCode()).isEqualTo("WFO");
            assertThat(first.unknown()).isFalse();
            assertThat(result.records().get(2).statusCode()).isEqualTo("WO");
            assertThat(result.records().get(3).statusCode()).isEqualTo("PL");
        }
    }

    @Test
    void detectsTwoLineHeaderWithDayNumbersAndRealDatesBelow() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle ds = dateStyle(wb);
            Sheet s = wb.createSheet("Team Matrix");
            Row h = s.createRow(0);
            text(h, 0, "Emp ID");
            text(h, 1, "Emp Name");
            num(h, 2, 1);
            num(h, 3, 2);
            num(h, 4, 3);
            Row dates = s.createRow(1);
            dt(dates, 2, LocalDate.of(2025, 9, 1), ds);
            dt(dates, 3, LocalDate.of(2025, 9, 2), ds);
            dt(dates, 4, LocalDate.of(2025, 9, 3), ds);
            Row d1 = s.createRow(2);
            text(d1, 0, "E100");
            text(d1, 1, "Alice");
            text(d1, 2, "WK WRK");
            text(d1, 4, "SL");

            var result = parseWorkbook(wb).get(0);
            assertThat(result.records()).hasSize(2);
            assertThat(result.records().get(0).attendanceDate()).isEqualTo(LocalDate.of(2025, 9, 1));
            assertThat(result.records().get(0).statusCode()).isEqualTo("WK WRK");
            assertThat(result.records().get(1).attendanceDate()).isEqualTo(LocalDate.of(2025, 9, 3));
            assertThat(result.records().get(1).statusCode()).isEqualTo("SL");
        }
    }

    @Test
    void parsesTextDatesAndNonBreakingSpaceHeaders() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Attendance");
            Row h = s.createRow(0);
            text(h, 0, "Emp\u00A0ID");
            text(h, 1, "Emp\u00A0Name");
            text(h, 2, "01/09/2025");
            text(h, 3, "02/09/2025");
            text(h, 4, "03/09/2025");
            Row d = s.createRow(1);
            text(d, 0, "25106149");
            text(d, 1, "Navin Kumar");
            text(d, 2, "SL");
            text(d, 3, "PL");
            text(d, 4, "HPE HOLIDAY");

            var result = parseWorkbook(wb).get(0);
            assertThat(result.records()).hasSize(3);
            assertThat(result.records().get(0).statusCode()).isEqualTo("SL");
            assertThat(result.records().get(0).statusName()).isEqualTo("Sick Leave");
            assertThat(result.records().get(1).statusCode()).isEqualTo("PL");
            assertThat(result.records().get(2).statusCode()).isEqualTo("HPEH");
            assertThat(result.records().get(0).attendanceDate()).isEqualTo(LocalDate.of(2025, 9, 1));
        }
    }

    @Test
    void skipsIrrelevantSheetsButParsesIgnorableNamedSheetWithData() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet idx = wb.createSheet("Index");
            text(idx.createRow(0), 0, "Sheet links and navigation only");
            Sheet rts = wb.createSheet("#RTS Count");
            num(rts.createRow(0), 0, 1);
            num(rts.createRow(1), 0, 2);

            Sheet furlough = wb.createSheet("Furlough Leave");
            Row fh = furlough.createRow(0);
            text(fh, 0, "Employee Code");
            text(fh, 1, "01/09/2025");
            Row fd = furlough.createRow(1);
            text(fd, 0, "F100");
            text(fd, 1, "FL");

            Sheet real = wb.createSheet("Actual Attendance");
            Row rh = real.createRow(0);
            text(rh, 0, "Emp Code");
            text(rh, 1, "01/09/2025");
            Row rd = real.createRow(1);
            text(rd, 0, "E1");
            text(rd, 1, "WFO");

            var results = parseWorkbook(wb);
            assertThat(results.stream().filter(s -> s.skipped())).hasSize(2);
            assertThat(results.get(0).sheetName()).isEqualTo("Index");
            assertThat(results.get(1).sheetName()).isEqualTo("#RTS Count");
            assertThat(results.get(2).sheetName()).isEqualTo("Furlough Leave");
            assertThat(results.get(2).skipped()).isFalse();
            assertThat(results.get(2).records()).hasSize(1);
            assertThat(results.get(2).records().get(0).statusCode()).isEqualTo("FL");
            assertThat(results.get(3).skipped()).isFalse();
        }
    }

    @Test
    void preservesUnknownCodesAndAttritionNumericVariants() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Sep 2025");
            Row h = s.createRow(0);
            text(h, 0, "Emp Code");
            text(h, 1, "01/09/2025");
            text(h, 2, "02/09/2025");
            Row d = s.createRow(1);
            text(d, 0, "E1");
            text(d, 1, "SILVER DUTY");
            text(d, 2, "ATR3");

            var result = parseWorkbook(wb).get(0);
            HistoricalRosterParser.ParsedRecord unknown = result.records().get(0);
            assertThat(unknown.statusCode()).isEqualTo("SILVER DUTY");
            assertThat(unknown.statusName()).isEqualTo("Unknown");
            assertThat(unknown.unknown()).isTrue();
            HistoricalRosterParser.ParsedRecord atr = result.records().get(1);
            assertThat(atr.statusCode()).isEqualTo("ATR3");
            assertThat(atr.statusName()).isEqualTo("Attrition / Left Team");
            assertThat(atr.unknown()).isFalse();
        }
    }

    @Test
    void resolvesBareDayNumbersFromTitleRowAndSheetName() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Roster");
            text(s.createRow(0), 0, "February 2025");
            Row h = s.createRow(1);
            text(h, 0, "Emp Code");
            num(h, 1, 28);
            num(h, 2, 31);
            Row d = s.createRow(2);
            text(d, 0, "E1");
            text(d, 1, "WFO");
            text(d, 2, "WFO");

            var result = parseWorkbook(wb).get(0);
            assertThat(result.records()).hasSize(2);
            assertThat(result.records().get(0).attendanceDate()).isEqualTo(LocalDate.of(2025, 2, 28));
            assertThat(result.records().get(1).attendanceDate()).isNull();
            assertThat(result.records().get(1).warning()).contains("not valid");
            assertThat(result.warnings()).isNotEmpty();
        }
    }

    @Test
    void resolvesBareDayNumbersFromSheetName() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Sep 2026");
            Row h = s.createRow(0);
            text(h, 0, "Emp Code");
            num(h, 1, 1);
            num(h, 2, 2);
            Row d = s.createRow(1);
            text(d, 0, "E1");
            text(d, 1, "WO");
            text(d, 2, "WFH");

            var result = parseWorkbook(wb).get(0);
            assertThat(result.records().get(0).attendanceDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(result.records().get(1).attendanceDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        }
    }

    @Test
    void missingEmployeeIdYieldsWarningNotRecords() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Sep 2025");
            Row h = s.createRow(0);
            text(h, 0, "Emp Code");
            text(h, 1, "01/09/2025");
            Row d = s.createRow(1);
            text(d, 0, "E1");
            text(d, 1, "WFO");
            Row bad = s.createRow(2);
            text(bad, 1, "WFO");
            Row blank = s.createRow(3);

            var result = parseWorkbook(wb).get(0);
            assertThat(result.records()).hasSize(1);
            assertThat(result.warnings()).anyMatch(w -> w.contains("no employee id"));
        }
    }

    @Test
    void ignoresRepeatedHeaderRowsInsideSheets() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle ds = dateStyle(wb);
            Sheet s = wb.createSheet("Sep 2026");
            Row h = s.createRow(0);
            text(h, 0, "Emp ID");
            text(h, 1, "Emp Name");
            text(h, 2, "Shift");
            dt(h, 3, LocalDate.of(2026, 9, 1), ds);
            dt(h, 4, LocalDate.of(2026, 9, 2), ds);
            dt(h, 5, LocalDate.of(2026, 9, 3), ds);

            // Full repeated header: id + name + shift labels (rule A and B).
            Row dup = s.createRow(1);
            text(dup, 0, "Emp ID");
            text(dup, 1, "Emp Name");
            text(dup, 2, "Shift");
            text(dup, 3, "WFO");
            text(dup, 4, "WFO");
            text(dup, 5, "WFO");

            // Id cell alone restates the label (rule A).
            Row idOnly = s.createRow(2);
            text(idOnly, 0, "Employee ID");
            text(idOnly, 3, "PL");
            text(idOnly, 4, "PL");
            text(idOnly, 5, "PL");

            // No id/name label, but two metadata cells restate labels (rule B).
            Row metaOnly = s.createRow(3);
            text(metaOnly, 0, "Location");
            text(metaOnly, 1, "Shift");
            text(metaOnly, 3, "WFH");
            text(metaOnly, 4, "WFH");
            text(metaOnly, 5, "WFH");

            Row real = s.createRow(4);
            text(real, 0, "60179401");
            text(real, 1, "Abhilash Yadav");
            text(real, 2, "05:30-14:30");
            text(real, 3, "WFO");
            text(real, 4, "WFO");
            text(real, 5, "WO");

            var result = parseWorkbook(wb).get(0);
            assertThat(result.skipped()).isFalse();
            assertThat(result.employeeIds()).containsExactly("60179401");
            assertThat(result.records()).hasSize(3);
            assertThat(result.records()).allMatch(r -> r.employeeId().equals("60179401"));
            assertThat(result.warnings())
                    .anyMatch(w -> w.contains("3 section/header row(s)"));
        }
    }
}