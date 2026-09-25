package com.emplmgt.service;

import com.emplmgt.dto.ImportDtos;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.ExcelImport;
import com.emplmgt.entity.ExcelImportRow;
import com.emplmgt.entity.ExcelRowStatus;
import com.emplmgt.entity.User;
import com.emplmgt.repository.AttendanceRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.ExcelImportRepository;
import com.emplmgt.repository.ExcelImportRowRepository;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.JsonUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExcelImportServiceTest {

    @Mock ExcelImportRepository excelImportRepository;
    @Mock ExcelImportRowRepository excelImportRowRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock AuditService auditService;
    @Mock AppClock appClock;

    ExcelImportService service;
    List<ExcelImportRow> savedRows = new ArrayList<>();
    ExcelImport savedImport;
    Map<String, Employee> employeesByCode = new LinkedHashMap<>();
    boolean dbHasExistingAttendance = false;

    @BeforeEach
    void setUp() throws Exception {
        savedRows.clear();
        savedImport = null;
        dbHasExistingAttendance = false;
        employeesByCode.clear();
        employeesByCode.put("25104925", Employee.builder().id(1L).employeeCode("25104925").fullName("Asha Patel").build());
        employeesByCode.put("25104930", Employee.builder().id(2L).employeeCode("25104930").fullName("Ravi Kumar").build());
        employeesByCode.put("E-100", Employee.builder().id(3L).employeeCode("E-100").fullName("Alpha Employee").build());

        when(appClock.now()).thenReturn(Instant.parse("2025-09-01T00:00:00Z"));
        when(excelImportRepository.save(any(ExcelImport.class))).thenAnswer(inv -> {
            ExcelImport i = inv.getArgument(0);
            if (i.getId() == null) i.setId(7L);
            savedImport = i;
            return i;
        });
        when(excelImportRepository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(savedImport));
        doNothing().when(excelImportRowRepository).deleteByExcelImportId(anyLong());
        when(excelImportRowRepository.save(any(ExcelImportRow.class))).thenAnswer(inv -> {
            ExcelImportRow r = inv.getArgument(0);
            savedRows.add(r);
            return r;
        });
        when(excelImportRowRepository.findByExcelImportIdOrderByRowNumber(anyLong()))
                .thenAnswer(inv -> new ArrayList<>(savedRows));
        when(employeeRepository.findByUserId(anyLong()))
                .thenReturn(Optional.of(Employee.builder().user(new User()).build()));
        when(employeeRepository.findByEmployeeCodeIgnoreCase(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(employeesByCode.get(((String) inv.getArgument(0)).trim())));
        when(attendanceRepository.existsByEmployeeIdAndAttendanceDate(anyLong(), any()))
                .thenAnswer(inv -> dbHasExistingAttendance);
        when(attendanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new ExcelImportService(excelImportRepository, excelImportRowRepository, employeeRepository,
                attendanceRepository, auditService, new JsonUtil(new com.fasterxml.jackson.databind.ObjectMapper()),
                appClock);
        java.lang.reflect.Field f = ExcelImportService.class.getDeclaredField("uploadDir");
        f.setAccessible(true);
        f.set(service, "target/excel-import-test-uploads");
    }

    // ---------------------------------------------------------------- helpers

    private byte[] workbook(Object[][] grid) {
        return workbook(grid, 1);
    }

    private byte[] workbook(Object[][] grid, int sheets) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            for (int s = 0; s < sheets; s++) {
                var sheet = wb.createSheet(s == 0 ? (sheets > 1 ? "Cover" : "Data") : "Data");
                Object[][] g = s == 1 && grid != null ? grid : (s == 0 && sheets == 1 ? grid : new Object[][]{{"Attendance Report"}, {"HR Dept"}});
                for (int r = 0; r < g.length; r++) {
                    if (g[r] == null) continue;
                    var row = sheet.createRow(r);
                    for (int c = 0; c < g[r].length; c++) {
                        Object v = g[r][c];
                        if (v != null) row.createCell(c).setCellValue(String.valueOf(v));
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ImportDtos.PreviewResponse uploadAndPreview(byte[] bytes, String name,
                                                        Consumer<Map<String, Object>> mappingCustomizer) {
        savedRows.clear();
        ImportDtos.UploadResponse up = service.upload(new MockMultipartFile("file", name, null, bytes), 1L);
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("employeeCode", up.suggestedMapping().get("employeeCode")
                != null ? up.suggestedMapping().get("employeeCode") : up.headers().get(0));
        mapping.put("attendanceDate", up.suggestedMapping().get("attendanceDate")
                != null ? up.suggestedMapping().get("attendanceDate") : up.headers().get(up.headers().size() - 2));
        mapping.put("attendanceType", up.headers().get(up.headers().size() - 1));
        if (mappingCustomizer != null) mappingCustomizer.accept(mapping);
        return service.mapAndPreview(savedImport.getId(), mapping);
    }

    private ImportDtos.RowView row(ImportDtos.PreviewResponse p, int rowNumber) {
        return p.rows().stream().filter(r -> r.rowNumber() == rowNumber).findFirst().orElseThrow();
    }

    /** Parsed values persisted by the service for the given preview row (mappedData, if the row was saved). */
    private Map<String, Object> mapped(int rowNumber) {
        return savedRows.stream()
                .filter(r -> r.getRowNumber() != null && r.getRowNumber() == rowNumber)
                .reduce((first, second) -> second) // the latest save for this rowNumber
                .map(r -> new JsonUtil(new com.fasterxml.jackson.databind.ObjectMapper()).read(r.getMappedData()))
                .orElse(Map.of());
    }

    // ---------------------------------------------------------------- cases

    @Test
    void importsRealWorldLongFormatWithTitleRowAndTextDates() {
        ImportDtos.PreviewResponse prev = uploadAndPreview(
                workbook(new Object[][]{
                        {"Monthly Attendance", null, null, null},
                        {"Employee ID", "Employee Name", "Date", "Type"},
                        {"25104925", "Asha Patel", "01-Sep-2025", "WFO"},
                        {"25104930", "Ravi Kumar", "02-Sep-2025", "WFH"},
                        {"25104925", "Asha Patel", "03-Sep-2025", "SL"},
                }),
                "att.xlsx", m -> {
                    m.put("employeeCode", "Employee ID");
                    m.put("attendanceDate", "Date");
                    m.put("attendanceType", "Type");
                });

        assertEquals(3, prev.validRows(), "all rows should be valid");
        assertEquals(0, prev.invalidRows());
        assertEquals(0, prev.rows().stream().filter(r -> !r.errors().isEmpty()).count());
        assertEquals("2025-09-01", mapped(3).get("attendanceDate"));
        assertEquals("2025-09-02", mapped(4).get("attendanceDate"));
        assertEquals("2025-09-03", mapped(5).get("attendanceDate"));

        ImportDtos.CommitResponse commit = service.commit(7L, 1L);
        assertEquals(3, commit.importedRows());
    }

    @Test
    void parsesCommonTextAndNumericDateFormats() {
        String[][] variants = {
                {"01-Sep-2025", "2025-09-01"}, {"1-Sep-25", "2025-09-01"},
                {"Sep 1, 2025", "2025-09-01"}, {"01.09.2025", "2025-09-01"},
                {"01/09/2025", "2025-09-01"}, {"1/9/2025", "2025-09-01"},
                {"2025-09-01", "2025-09-01"}, {"01 December 2025", "2025-12-01"},
        };
        for (String[] v : variants) {
            ImportDtos.PreviewResponse prev = uploadAndPreview(
                    workbook(new Object[][]{{"Employee ID", "Date", "Type"}, {"25104925", v[0], "WFO"}}),
                    "d.xlsx", null);
            assertEquals(1, prev.validRows(), v[0] + " should parse");
            assertEquals(0, row(prev, 2).errors().size(), v[0] + " errors: " + row(prev, 2).errors());
            assertEquals(v[1], mapped(2).get("attendanceDate"), v[0] + " parsed value");
        }
    }

    @Test
    void reportsUnrecognisedDateFormatAsInvalid() {
        ImportDtos.PreviewResponse prev = uploadAndPreview(
                workbook(new Object[][]{{"Employee ID", "Date", "Type"}, {"25104925", "13th February", "WFO"}}),
                "bad.xlsx", null);
        assertEquals(ExcelRowStatus.INVALID, row(prev, 2).status());
        assertEquals(1, row(prev, 2).details().size());
        assertEquals("UNRECOGNISED_DATE", row(prev, 2).details().get(0).errorType());
        assertTrue(row(prev, 2).details().get(0).rawValue().contains("13th February"));
    }

    @Test
    void ignoresCoverSheetAndReadsDataSheet() {
        ImportDtos.PreviewResponse prev = uploadAndPreview(
                workbook(new Object[][]{{"Employee ID", "Date", "Type"}, {"25104925", "01-Sep-2025", "WFO"}}),
                "multi.xlsx", null);
        assertEquals(1, prev.validRows());
    }

    @Test
    void alphanumericEmployeeCodeResolves() {
        ImportDtos.PreviewResponse prev = uploadAndPreview(
                workbook(new Object[][]{{"Emp Code", "Date"}, {"E-100", "01-Sep-2025"}}),
                "alpha.xlsx", m -> {
                    m.put("employeeCode", "Emp Code");
                    m.put("attendanceDate", "Date");
                    m.remove("attendanceType");
                });
        assertEquals(1, prev.validRows());
        assertEquals("E-100", mapped(2).get("employeeCode"));
        assertEquals("2025-09-01", mapped(2).get("attendanceDate"));
        assertEquals("WORK_FROM_OFFICE", mapped(2).get("attendanceType"));
    }

    @Test
    void unknownEmployeeCodeGivesStructuredError() {
        ImportDtos.PreviewResponse prev = uploadAndPreview(
                workbook(new Object[][]{{"Employee ID", "Date", "Type"}, {"25104999", "01-Sep-2025", "WFO"}}),
                "unknown.xlsx", null);
        assertEquals(ExcelRowStatus.INVALID, row(prev, 2).status());
        assertEquals("UNKNOWN_EMPLOYEE", row(prev, 2).details().get(0).errorType());
    }

    @Test
    void duplicateInFileAndDuplicateInDbAreFlagged() {
        ImportDtos.PreviewResponse prev = uploadAndPreview(
                workbook(new Object[][]{
                        {"Employee ID", "Date", "Type"},
                        {"25104925", "01-Sep-2025", "WFO"},
                        {"25104925", "01-Sep-2025", "WFH"},
                }),
                "dupfile.xlsx", null);
        assertEquals(1, prev.validRows());
        assertEquals(1, prev.duplicateRows());
        assertEquals("DUPLICATE_IN_FILE", row(prev, 3).details().get(0).errorType());

        dbHasExistingAttendance = true;
        ImportDtos.PreviewResponse prev2 = uploadAndPreview(
                workbook(new Object[][]{{"Employee ID", "Date", "Type"}, {"25104925", "01-Sep-2025", "WFO"}}),
                "dupdb.xlsx", null);
        assertEquals(1, prev2.duplicateRows());
        assertEquals("DUPLICATE_IN_DB", row(prev2, 2).details().get(0).errorType());
    }

    @Test
    void ddMmConventionIsNotMisread() {
        // 13/09/2025 is only a valid dd/MM date; the column must be read as Sep 13, not Jan 9.
        ImportDtos.PreviewResponse prev = uploadAndPreview(
                workbook(new Object[][]{{"Employee ID", "Date", "Type"}, {"25104925", "13/09/2025", "WFO"}}),
                "family.xlsx", null);
        assertEquals(1, prev.validRows(), "13/09/2025 must not be silently read as a US-style date");
        assertEquals("2025-09-13", mapped(2).get("attendanceDate"));
    }

    @Test
    void footerRowsAreReportedNotSilentlySkipped() {
        ImportDtos.PreviewResponse prev = uploadAndPreview(
                workbook(new Object[][]{
                        {"Employee ID", "Date", "Type"},
                        {"25104925", "01-Sep-2025", "WFO"},
                        {"Generated by HR system", null, null},
                }),
                "footer.xlsx", null);
        assertEquals(1, prev.validRows());
        assertEquals(1, prev.invalidRows());
        assertEquals(ExcelRowStatus.INVALID, row(prev, 3).status());
        assertTrue(row(prev, 3).details().stream()
                        .anyMatch(d -> "UNKNOWN_EMPLOYEE".equals(d.errorType())),
                "footer row should surface an unknown-employee error, got: " + row(prev, 3).details());
    }

    @Test
    void commitWithNoValidRowsIsRejected() {
        uploadAndPreview(
                workbook(new Object[][]{{"Employee ID", "Date", "Type"}, {"25104925", "not-a-date", "WFO"}}),
                "none.xlsx", null);
        assertThrows(RuntimeException.class, () -> service.commit(7L, 1L));
    }
}