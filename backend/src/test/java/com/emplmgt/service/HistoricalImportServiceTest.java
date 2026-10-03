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
import com.emplmgt.repository.AttendanceShiftAssignmentRepository;
import com.emplmgt.repository.AttendanceWeekOffAssignmentRepository;
import com.emplmgt.repository.AttendanceStatusRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import com.emplmgt.util.AppClock;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HistoricalImportServiceTest {

    @Mock AttendanceStatusRepository statusRepository;
    @Mock ImportEmployeeRepository importEmployeeRepository;
    @Mock AttendanceRecordRepository recordRepository;
    @Mock AttendanceShiftAssignmentRepository shiftAssignmentRepository;
    @Mock AttendanceWeekOffAssignmentRepository weekOffAssignmentRepository;
    @Mock com.emplmgt.util.LeaveDaysCalculator leaveDaysCalculator;
    @Mock AttendanceImportHistoryRepository historyRepository;
    @Mock AttendanceImportRowRepository rowRepository;
    @Mock AuditService auditService;
    @Mock AppClock appClock;

    private com.emplmgt.util.WeekOffUtil weekOffUtil;

    HistoricalImportService service;
    AttendanceImportHistory savedImportHistory;
    private static final Instant NOW = Instant.parse("2025-09-01T00:00:00Z");

    @BeforeEach
    void setUp() {
        weekOffUtil = new com.emplmgt.util.WeekOffUtil(leaveDaysCalculator);
        when(appClock.now()).thenReturn(NOW);
        when(historyRepository.save(any(AttendanceImportHistory.class))).thenAnswer(inv -> {
            AttendanceImportHistory h = inv.getArgument(0);
            if (h.getId() == null) {
                h.setId(1L);
            }
            savedImportHistory = h;
            return h;
        });
        when(rowRepository.saveAll(anyIterable())).thenAnswer(inv -> {
            List<AttendanceImportRow> rows = new java.util.ArrayList<>(
                    (java.util.Collection<AttendanceImportRow>) inv.getArgument(0));
            long i = 0;
            for (AttendanceImportRow r : rows) {
                if (r.getId() == null) {
                    r.setId(++i);
                }
            }
            return rows;
        });
        when(shiftAssignmentRepository.findByEmployeeIdAndPeriodStart(any(), any())).thenReturn(java.util.Optional.empty());
        service = new HistoricalImportService(statusRepository, importEmployeeRepository, recordRepository,
                shiftAssignmentRepository, weekOffAssignmentRepository, weekOffUtil, historyRepository,
                rowRepository, auditService, appClock, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private static CellStyle dateStyle(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd-mmm-yyyy"));
        return s;
    }

    private static void text(Row r, int c, String v) {
        r.createCell(c).setCellValue(v);
    }

    private static void day(Row r, int c, int v) {
        r.createCell(c).setCellValue(v);
    }

    private static byte[] flatBook() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle ds = dateStyle(wb);
            Sheet s = wb.createSheet("Sep 2025");
            Row h = s.createRow(0);
            text(h, 0, "Emp Code");
            text(h, 1, "Emp Name");
            for (int i = 0; i < 2; i++) {
                org.apache.poi.ss.usermodel.Cell c = h.createCell(2 + i);
                c.setCellValue(java.util.Date.from(LocalDate.of(2025, 9, 1 + i)
                        .atStartOfDay().atZone(java.time.ZoneId.systemDefault()).toInstant()));
                c.setCellStyle(ds);
            }
            Row a = s.createRow(1);
            text(a, 0, "E1");
            text(a, 1, "Alice");
            text(a, 2, "WFO");
            text(a, 3, "WO");
            Row b = s.createRow(2);
            text(b, 0, "E2");
            text(b, 1, "Bob");
            text(b, 2, "WFH");
            text(b, 3, "PL");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static byte[] febBookWithInvalidDay() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet("Roster");
            text(s.createRow(0), 0, "February 2025");
            Row h = s.createRow(1);
            text(h, 0, "Emp Code");
            day(h, 1, 28);
            day(h, 2, 31);
            Row d = s.createRow(2);
            text(d, 0, "E1");
            text(d, 1, "WFO");
            text(d, 2, "WFO");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    private static AttendanceImportHistory history(String status) {
        return AttendanceImportHistory.builder().id(1L).fileName("historical/x.xlsx").originalFileName("sep.xlsx")
                .importedAt(NOW).status(status).totalSheets(1).sheetsImported(1).sheetsSkipped(0)
                .employeesDetected(2).recordsDetected(2).build();
    }

    @Test
    void previewBuildsInsertRowsAndSummary() throws IOException {
        HistoricalImportDtos.PreviewResponse resp = service.preview(file("sep.xlsx", flatBook()), 1L);

        assertThat(resp.status()).isEqualTo("PREVIEWED");
        assertThat(resp.summary().recordsDetected()).isEqualTo(4);
        assertThat(resp.summary().employeesDetected()).isEqualTo(2);
        assertThat(resp.summary().newRecords()).isEqualTo(4);
        assertThat(resp.summary().unknownCodes()).isZero();
        assertThat(resp.summary().invalidRows()).isZero();
        assertThat(resp.totalRows()).isEqualTo(4);
        assertThat(resp.rows()).hasSize(4);
        assertThat(resp.rows()).allMatch(r -> r.action().equals("INSERT"));
        verify(rowRepository).saveAll(anyIterable());
    }

    @Test
    void previewExposesSheetAnalysisAndIssues() throws IOException {
        HistoricalImportDtos.PreviewResponse resp = service.preview(file("sep.xlsx", flatBook()), 1L);

        assertThat(resp.analysis()).hasSize(1);
        HistoricalImportDtos.SheetAnalysis a = resp.analysis().get(0);
        assertThat(a.sheetName()).isEqualTo("Sep 2025");
        assertThat(a.month()).isEqualTo("September 2025");
        assertThat(a.headerRow()).isEqualTo(1);
        assertThat(a.employeeColumns()).isEqualTo(2);
        assertThat(a.dateColumns()).isEqualTo(2);
        assertThat(a.employeeCount()).isEqualTo(2);
        assertThat(a.cellCount()).isEqualTo(4);
        assertThat(a.unknownCodeCount()).isZero();
        assertThat(a.emptyCellCount()).isZero();
        assertThat(a.skipped()).isFalse();
        assertThat(a.importable()).isTrue();

        assertThat(resp.issues()).extracting(HistoricalImportDtos.ValidationIssue::severity)
                .doesNotContain("ERROR");
        assertThat(resp.issues())
                .extracting(HistoricalImportDtos.ValidationIssue::type)
                .contains("MISSING_EMAIL", "MISSING_MANAGER");
        assertThat(resp.unknownCodes()).isEmpty();
    }

    @Test
    void previewOfRehydratesSnapshotAndRows() throws IOException {
        HistoricalImportDtos.PreviewResponse first = service.preview(file("sep.xlsx", flatBook()), 1L);
        org.mockito.ArgumentCaptor<List<AttendanceImportRow>> capRows =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(rowRepository).saveAll(capRows.capture());
        List<AttendanceImportRow> savedRows = capRows.getValue();

        long id = first.importId();
        when(historyRepository.findById(id)).thenReturn(Optional.of(savedImportHistory));
        when(rowRepository.countByImportHistoryId(id)).thenReturn(4L);
        when(rowRepository.findByImportHistoryId(eq(id), any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(savedRows));
        when(rowRepository.findByImportHistoryIdOrderByIdAsc(id)).thenReturn(savedRows);

        HistoricalImportDtos.PreviewResponse again = service.previewOf(id, 0, 50);

        assertThat(again.status()).isEqualTo("PREVIEWED");
        assertThat(again.totalRows()).isEqualTo(first.totalRows());
        assertThat(again.rows()).hasSize(4);
        assertThat(again.analysis()).hasSize(1);
        assertThat(again.analysis().get(0).sheetName()).isEqualTo("Sep 2025");
    }

    @Test
    void previewMarksInvalidDatesAsErrorsAndNotImportable() throws IOException {
        HistoricalImportDtos.PreviewResponse resp = service.preview(file("feb.xlsx", febBookWithInvalidDay()), 1L);

        assertThat(resp.summary().errors()).isGreaterThan(0);
        assertThat(resp.issues())
                .anyMatch(i -> i.severity().equals("ERROR")
                        && i.type().equals("INVALID_DATE"));
        assertThat(resp.analysis()).anyMatch(a -> !a.importable());
        assertThat(resp.analysis().get(0).cellCount()).isEqualTo(2);
    }

    @Test
    void previewMarksDuplicatesWithinFile() throws IOException {
        byte[] bytes = flatBook();
        byte[] dup;
        try (XSSFWorkbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes))) {
            Row extra = wb.getSheetAt(0).createRow(3);
            text(extra, 0, "E1");
            text(extra, 1, "Alice again");
            text(extra, 2, "WFO");
            text(extra, 3, "WO");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            dup = out.toByteArray();
        }

        HistoricalImportDtos.PreviewResponse resp = service.preview(file("dup.xlsx", dup), 1L);

        assertThat(resp.summary().recordsDetected()).isEqualTo(6);
        assertThat(resp.summary().newRecords()).isEqualTo(4);
        assertThat(resp.summary().duplicateRecords()).isEqualTo(2);
        assertThat(resp.rows()).filteredOn(r -> r.action().equals("DUPLICATE")).hasSize(2);
    }

    @Test
    void previewMarksExistingRecordsAsUpdate() throws IOException {
        AttendanceRecord existing = AttendanceRecord.builder().employeeId("E1")
                .attendanceDate(LocalDate.of(2025, 9, 1)).statusCode("WFH").build();
        when(recordRepository.findByAttendanceDateBetweenOrderByAttendanceDateAsc(any(), any()))
                .thenReturn(List.of(existing));

        HistoricalImportDtos.PreviewResponse resp = service.preview(file("sep.xlsx", flatBook()), 1L);

        assertThat(resp.summary().updatedRecords()).isEqualTo(1);
        assertThat(resp.summary().newRecords()).isEqualTo(3);
        HistoricalImportDtos.RowView upd = resp.rows().stream().filter(r -> r.action().equals("UPDATE")).findFirst().orElseThrow();
        assertThat(upd.existingStatus()).isEqualTo("WFH");
        assertThat(upd.incomingStatus()).isEqualTo("WFO");
    }

    @Test
    void previewMarksDayOutOfRangeAsInvalid() throws IOException {
        HistoricalImportDtos.PreviewResponse resp = service.preview(file("feb.xlsx", febBookWithInvalidDay()), 1L);

        assertThat(resp.summary().recordsDetected()).isEqualTo(2);
        assertThat(resp.summary().invalidRows()).isEqualTo(1);
        assertThat(resp.summary().newRecords()).isEqualTo(1);
        HistoricalImportDtos.RowView inv = resp.rows().stream().filter(r -> r.action().equals("INVALID")).findFirst().orElseThrow();
        assertThat(inv.attendanceDate()).isNull();
        assertThat(inv.warning()).contains("not valid");
    }

    @Test
    void mapStagedRemapsUnknownCodes() {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));

        AttendanceImportRow r1 = AttendanceImportRow.builder().importHistory(h).employeeId("E1")
                .attendanceDate(LocalDate.of(2025, 9, 1)).incomingStatus("SILVER DUTY")
                .isUnknown(true).sheetName("Sep 2025").sourceRow(2).action("INSERT").build();
        AttendanceImportRow r2 = AttendanceImportRow.builder().importHistory(h).employeeId("E1")
                .attendanceDate(LocalDate.of(2025, 9, 2)).incomingStatus("silver duty")
                .isUnknown(true).sheetName("Sep 2025").sourceRow(2).action("INSERT").build();
        AttendanceImportRow r3 = AttendanceImportRow.builder().importHistory(h).employeeId("E2")
                .attendanceDate(LocalDate.of(2025, 9, 1)).incomingStatus("WFO").statusName("Work From Office")
                .isUnknown(false).sheetName("Sep 2025").sourceRow(3).action("INSERT").build();
        when(rowRepository.findByImportHistoryIdOrderByIdAsc(1L)).thenReturn(List.of(r1, r2, r3));
        when(statusRepository.findById("WFO"))
                .thenReturn(Optional.of(new AttendanceStatus("WFO", "Work From Office", null, "sky")));

        HistoricalImportDtos.MapStagedResponse resp =
                service.mapStaged(1L, new HistoricalImportDtos.MapStagedRequest("SILVER DUTY", "WFO"));

        assertThat(resp.mapped()).isEqualTo(2);
        assertThat(resp.to()).isEqualTo("WFO");
        assertThat(r1.getIncomingStatus()).isEqualTo("WFO");
        assertThat(r1.getIsUnknown()).isFalse();
        assertThat(r2.getIncomingStatus()).isEqualTo("WFO");
        assertThat(r3.getIncomingStatus()).isEqualTo("WFO");
        verify(rowRepository).saveAll(List.of(r1, r2, r3));
        verify(auditService).record(any(), any(), any(), any(), any());
    }

    @Test
    void mapStagedKeepsUnknownWhenTargetBlank() {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));

        AttendanceImportRow r1 = AttendanceImportRow.builder().importHistory(h).employeeId("E1")
                .attendanceDate(LocalDate.of(2025, 9, 1)).incomingStatus("SILVER DUTY")
                .isUnknown(true).sheetName("Sep 2025").sourceRow(2).action("INSERT").build();
        when(rowRepository.findByImportHistoryIdOrderByIdAsc(1L)).thenReturn(List.of(r1));

        HistoricalImportDtos.MapStagedResponse resp =
                service.mapStaged(1L, new HistoricalImportDtos.MapStagedRequest("SILVER DUTY", ""));

        assertThat(resp.mapped()).isZero();
        assertThat(r1.getIncomingStatus()).isEqualTo("SILVER DUTY");
        assertThat(r1.getIsUnknown()).isTrue();
    }

    @Test
    void mapStagedRejectsUnknownTargetCode() {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));

        AttendanceImportRow r1 = AttendanceImportRow.builder().importHistory(h).employeeId("E1")
                .attendanceDate(LocalDate.of(2025, 9, 1)).incomingStatus("SILVER DUTY")
                .isUnknown(true).sheetName("Sep 2025").sourceRow(2).action("INSERT").build();
        when(rowRepository.findByImportHistoryIdOrderByIdAsc(1L)).thenReturn(List.of(r1));
        when(statusRepository.findById("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.mapStaged(1L,
                new HistoricalImportDtos.MapStagedRequest("SILVER DUTY", "NOPE")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown target code");
    }

    @Test
    void commitBlockedWhenWorkbookHasFatalErrors() {
        AttendanceImportHistory h = history("PREVIEWED");
        h.setErrors(2);
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));

        assertThatThrownBy(() -> service.commit(1L, null, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("fatal error");
    }

    @Test
    void errorReportExportsCsv() {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));

        AttendanceImportRow bad = AttendanceImportRow.builder().importHistory(h).employeeId("E1")
                .attendanceDate(LocalDate.of(2025, 9, 30)).incomingStatus("SILVER DUTY")
                .isUnknown(true).sheetName("Sep 2025").sourceRow(2).action("INVALID")
                .warning("2019-09-31 is not valid").build();
        AttendanceImportRow dup = AttendanceImportRow.builder().importHistory(h).employeeId("E2")
                .attendanceDate(LocalDate.of(2025, 9, 1)).incomingStatus("WFO")
                .isUnknown(false).sheetName("Sep 2025").sourceRow(3).action("DUPLICATE").build();
        AttendanceImportRow ok = AttendanceImportRow.builder().importHistory(h).employeeId("E3")
                .attendanceDate(LocalDate.of(2025, 9, 1)).incomingStatus("WFO")
                .isUnknown(false).sheetName("Sep 2025").sourceRow(3).action("INSERT").build();
        when(rowRepository.findByImportHistoryIdOrderByIdAsc(1L)).thenReturn(List.of(bad, dup, ok));

        String csv = service.errorReport(1L);

        assertThat(csv).contains("Employee ID");
        assertThat(csv).contains("E1");
        assertThat(csv).contains("SILVER DUTY");
        assertThat(csv).contains("2019-09-31 is not valid");
        assertThat(csv).doesNotContain("E3");
    }

    @Test
    void commitUpsertsEmployeesAndRecords() throws IOException {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));

        AttendanceImportRow insert = AttendanceImportRow.builder().importHistory(h).employeeId("E1")
                .attendanceDate(LocalDate.of(2025, 9, 1)).incomingStatus("WFO").statusName("Work From Office")
                .isUnknown(false).sheetName("Sep 2025").sourceRow(2).action("INSERT").build();
        AttendanceImportRow update = AttendanceImportRow.builder().importHistory(h).employeeId("E2")
                .attendanceDate(LocalDate.of(2025, 9, 1)).incomingStatus("PL").statusName("Privilege Leave")
                .isUnknown(false).sheetName("Sep 2025").sourceRow(3).action("UPDATE").build();
        when(rowRepository.findByImportHistoryIdAndActionInOrderByIdAsc(1L, List.of("INSERT", "UPDATE")))
                .thenReturn(List.of(insert, update));

        AttendanceRecord has = AttendanceRecord.builder().employeeId("E2").attendanceDate(LocalDate.of(2025, 9, 1))
                .statusCode("WO").build();
        when(recordRepository.findByEmployeeIdAndAttendanceDate("E1", LocalDate.of(2025, 9, 1)))
                .thenReturn(Optional.empty());
        when(recordRepository.findByEmployeeIdAndAttendanceDate("E2", LocalDate.of(2025, 9, 1)))
                .thenReturn(Optional.of(has));

        HistoricalImportDtos.CommitResponse resp = service.commit(1L, null, 1L);

        assertThat(resp.status()).isEqualTo("COMMITTED");
        assertThat(resp.committedRows()).isEqualTo(2);
        assertThat(resp.summary().newRecords()).isEqualTo(1);
        assertThat(resp.summary().updatedRecords()).isEqualTo(1);

        verify(importEmployeeRepository).save(argThat(e -> e.getEmployeeId().equals("E1")));
        verify(recordRepository).save(argThat(r -> r.getEmployeeId().equals("E1") && r.getStatusCode().equals("WFO")));
        verify(recordRepository).save(argThat(r -> r.getEmployeeId().equals("E2") && r.getStatusCode().equals("PL")));
        verify(auditService).record(any(), any(), any(), any(), any());
    }

    @Test
    void commitImportsDescriptionWithoutOverwritingExistingOne() {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));

        AttendanceImportRow fresh = AttendanceImportRow.builder().importHistory(h).employeeId("E1")
                .attendanceDate(LocalDate.of(2025, 9, 1)).incomingStatus("WFO").statusName("Work From Office")
                .isUnknown(false).sheetName("Sep 2025").sourceRow(2).sourceColumn(3).action("INSERT")
                .description("Early Logout - wellness").descriptionSource("EXCEL_COMMENT")
                .descriptionAuthor("Sahana").descriptionAt(java.time.LocalDateTime.of(2025, 9, 1, 9, 30))
                .originalStatus("WFO - Early Logout - wellness").build();
        AttendanceImportRow update = AttendanceImportRow.builder().importHistory(h).employeeId("E2")
                .attendanceDate(LocalDate.of(2025, 9, 1)).incomingStatus("PL").statusName("Privilege Leave")
                .isUnknown(false).sheetName("Sep 2025").sourceRow(3).sourceColumn(4).action("UPDATE")
                .description("imported note").descriptionSource("EXCEL_COMMENT").originalStatus("PL").build();
        when(rowRepository.findByImportHistoryIdAndActionInOrderByIdAsc(1L, List.of("INSERT", "UPDATE")))
                .thenReturn(List.of(fresh, update));

        AttendanceRecord hasDescription = AttendanceRecord.builder().employeeId("E2")
                .attendanceDate(LocalDate.of(2025, 9, 1)).statusCode("WO")
                .description("manual note from admin").build();
        when(recordRepository.findByEmployeeIdAndAttendanceDate("E1", LocalDate.of(2025, 9, 1)))
                .thenReturn(Optional.empty());
        when(recordRepository.findByEmployeeIdAndAttendanceDate("E2", LocalDate.of(2025, 9, 1)))
                .thenReturn(Optional.of(hasDescription));

        service.commit(1L, null, 1L);

        // A brand-new record gains the imported description plus its provenance.
        verify(recordRepository).save(argThat(r -> "E1".equals(r.getEmployeeId())
                && "Early Logout - wellness".equals(r.getDescription())
                && "EXCEL_COMMENT".equals(r.getDescriptionSource())
                && "Early Logout - wellness".equals(r.getDescriptionImported())
                && "Sahana".equals(r.getDescriptionSourceAuthor())
                && java.time.LocalDateTime.of(2025, 9, 1, 9, 30).equals(r.getDescriptionSourceAt())
                && "C2".equals(r.getDescriptionSourceCell())
                && "Sep 2025".equals(r.getDescriptionSourceSheet())));
        // An existing description is never overwritten by a re-import.
        verify(recordRepository).save(argThat(r -> "E2".equals(r.getEmployeeId())
                && "manual note from admin".equals(r.getDescription())
                && r.getDescriptionImported() == null));
    }

    @Test
    void commitIsIdempotent() {
        AttendanceImportHistory h = history("COMMITTED");
        h.setInsertedRecords(10);
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));

        HistoricalImportDtos.CommitResponse resp = service.commit(1L, null, 1L);

        assertThat(resp.status()).isEqualTo("COMMITTED");
        assertThat(resp.committedRows()).isEqualTo(10);
        verify(historyRepository).findById(1L);
    }

    @Test
    void commitRejectsNonPreviewedImport() {
        AttendanceImportHistory h = history("DRAFT");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));

        assertThatThrownBy(() -> service.commit(1L, null, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must be previewed");
    }

    @Test
    void mapUnknownRequiresKnownTargetAndRemaps() {
        when(statusRepository.findById("WFO")).thenReturn(Optional.of(
                new AttendanceStatus("WFO", "Work From Office", null, "sky")));
        when(recordRepository.remapUnknown("SILVER DUTY", "WFO", "Work From Office", NOW)).thenReturn(5);
        when(statusRepository.findById("NOPE")).thenReturn(Optional.empty());

        HistoricalImportDtos.MapUnknownResponse ok = service.mapUnknown(
                new HistoricalImportDtos.MapUnknownRequest("silver duty", "wfo"));
        assertThat(ok.mapped()).isEqualTo(5);
        assertThat(ok.to()).isEqualTo("WFO");

        assertThatThrownBy(() -> service.mapUnknown(
                new HistoricalImportDtos.MapUnknownRequest("X", "NOPE")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown target code");
    }

    @Test
    void listsUnknownCodesOrderedByCount() {
        when(recordRepository.countUnknownCodes())
                .thenReturn(List.of(new Object[]{"SILVER DUTY", 3L}, new Object[]{"XYZ", 1L}));

        List<HistoricalImportDtos.UnknownCodeItem> items = service.unknownCodes();

        assertThat(items).hasSize(2);
        assertThat(items.get(0)).satisfies(i -> assertThat(i.code()).isEqualTo("SILVER DUTY"));
        assertThat(items.get(0).count()).isEqualTo(3);
    }

    @Test
    void mapsHistoryStatusesAndRecords() {
        AttendanceImportHistory h = history("COMMITTED");
        h.setInsertedRecords(5);
        h.setUpdatedRecords(2);
        h.setDuplicateRecords(1);
        when(historyRepository.findAllByOrderByImportedAtDesc()).thenReturn(List.of(h));

        List<HistoricalImportDtos.HistoryItem> items = service.history();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).status()).isEqualTo("COMMITTED");
        assertThat(items.get(0).newRecords()).isEqualTo(5);

        when(statusRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(
                new AttendanceStatus("WFO", "Work From Office", null, "sky")));
        List<HistoricalImportDtos.StatusItem> statuses = service.statuses();
        assertThat(statuses).hasSize(1);
        assertThat(statuses.get(0).code()).isEqualTo("WFO");

        AttendanceRecord rec = AttendanceRecord.builder().id(9L).employeeId("E1")
                .attendanceDate(LocalDate.of(2025, 9, 1)).statusCode("WO").isUnknown(false)
                .sourceSheet("Sep 2025").sourceRow(3).sourceFile("historical/x.xlsx").importedAt(NOW).build();
        when(recordRepository.findByAttendanceDateBetween(any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(rec)));
        when(importEmployeeRepository.findAllById(anyIterable()))
                .thenReturn(List.of(ImportEmployee.builder().employeeId("E1").employeeName("Alice").build()));

        HistoricalImportDtos.RecordsPage page = service.records(LocalDate.of(2025, 9, 1),
                LocalDate.of(2025, 9, 30), null, 0, 50);
        assertThat(page.records()).hasSize(1);
        assertThat(page.records().get(0).employeeName()).isEqualTo("Alice");
        assertThat(page.records().get(0).statusCode()).isEqualTo("WO");
    }

    @Test
    void previewRejectsBrokenFiles() {
        MockMultipartFile bad = new MockMultipartFile("file", "sep.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "not an xlsx".getBytes());

        assertThatThrownBy(() -> service.preview(bad, 1L))
                .isInstanceOf(ApiException.class);
    }

    // ------------------------------------------------ MANUAL STATUS RESOLUTION

    /** One valid cell, one unrecognised code, one cell with nothing in it. */
    private static byte[] bookNeedingReview() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle ds = dateStyle(wb);
            Sheet s = wb.createSheet("Sep 2025");
            Row h = s.createRow(0);
            text(h, 0, "Emp Code");
            text(h, 1, "Emp Name");
            for (int i = 0; i < 3; i++) {
                org.apache.poi.ss.usermodel.Cell c = h.createCell(2 + i);
                c.setCellValue(java.util.Date.from(LocalDate.of(2025, 9, 1 + i)
                        .atStartOfDay().atZone(java.time.ZoneId.systemDefault()).toInstant()));
                c.setCellStyle(ds);
            }
            Row a = s.createRow(1);
            text(a, 0, "E1");
            text(a, 1, "Alice");
            text(a, 2, "WFO");
            text(a, 3, "P");
            // Column 4 (index 4) is deliberately left empty.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private AttendanceImportRow unresolvedRow(AttendanceImportHistory h, String original, String issue) {
        return AttendanceImportRow.builder().importHistory(h).id(7L).employeeId("E1")
                .employeeName("Alice").attendanceDate(LocalDate.of(2025, 9, 2))
                .incomingStatus(original).originalStatus(original).statusName("Silver Duty")
                .isUnknown(true).sheetName("Sep 2025").sourceRow(2).sourceColumn(4)
                .action("INSERT").issue(issue).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewFlagsUnrecognisedCellsForManualReview() throws IOException {
        service.preview(file("sep.xlsx", bookNeedingReview()), 1L);

        org.mockito.ArgumentCaptor<Iterable<AttendanceImportRow>> captor =
                org.mockito.ArgumentCaptor.forClass(Iterable.class);
        verify(rowRepository, org.mockito.Mockito.atLeastOnce()).saveAll(captor.capture());

        List<AttendanceImportRow> staged = new java.util.ArrayList<>();
        captor.getAllValues().forEach(it -> it.forEach(staged::add));

        assertThat(staged).anyMatch(r -> "P".equals(r.getOriginalStatus())
                && r.getIssue() != null && r.getIssue().contains("Unrecognised"));

        // A cell the workbook left empty must not vanish: it is staged with no status
        // and an explicit issue, so the admin is asked about it instead of it being
        // silently dropped at commit time.
        assertThat(staged).anyMatch(r -> r.getAttendanceDate() != null
                && (r.getIncomingStatus() == null || r.getIncomingStatus().isBlank())
                && r.getIssue() != null && r.getIssue().contains("Blank status"));
    }

    @Test
    void commitBlockedWhileEntriesAwaitReview() {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));
        when(rowRepository.countByImportHistoryIdAndIssueIsNotNullAndCorrectedIsFalseAndSkippedIsFalse(1L))
                .thenReturn(3L);

        assertThatThrownBy(() -> service.commit(1L, null, 1L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("3 attendance entr")
                .hasMessageContaining("Correct them or skip them");
    }

    @Test
    void correctRowAppliesChosenStatusAndKeepsOriginalValue() {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));
        AttendanceImportRow r = unresolvedRow(h, "P", "Unrecognised status 'P'");
        when(rowRepository.findById(7L)).thenReturn(Optional.of(r));
        when(statusRepository.findById("PL"))
                .thenReturn(Optional.of(new AttendanceStatus("PL", "Privilege Leave", null, "amber")));
        when(rowRepository.countByImportHistoryIdAndIssueIsNotNullAndCorrectedIsFalseAndSkippedIsFalse(1L))
                .thenReturn(0L);
        when(rowRepository.countByImportHistoryIdAndIssueIsNotNullAndCorrectedIsTrue(1L)).thenReturn(1L);

        HistoricalImportDtos.ResolveRowResponse resp =
                service.correctRow(1L, 7L, "PL");

        assertThat(r.getIncomingStatus()).isEqualTo("PL");
        assertThat(r.getStatusName()).isEqualTo("Privilege Leave");
        assertThat(r.getIsUnknown()).isFalse();
        assertThat(r.getCorrected()).isTrue();
        assertThat(r.getIssue()).contains("Unrecognised");
        // The workbook still says "P": the correction is ours, not the file's.
        assertThat(r.getOriginalStatus()).isEqualTo("P");
        // Correcting must not turn a blank/odd cell into a fresh insert of the wrong kind.
        assertThat(r.getAction()).isEqualTo("INSERT");
        assertThat(resp.summary().corrected()).isEqualTo(1L);
        assertThat(resp.summary().remaining()).isZero();
        verify(auditService).record(org.mockito.ArgumentMatchers.eq("HISTORICAL_ROW_CORRECTED"),
                any(), any(), any(), any());
    }

    @Test
    void correctRowRejectsStatusOutsideCentralConfig() {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));
        AttendanceImportRow r = unresolvedRow(h, "P", "Unrecognised status 'P'");
        when(rowRepository.findById(7L)).thenReturn(Optional.of(r));
        when(statusRepository.findById("HACK")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.correctRow(1L, 7L, "HACK"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Unknown status");
        assertThat(r.getCorrected()).isFalse();
        assertThat(r.getIssue()).contains("Unrecognised");
    }

    @Test
    void correctRowRejectsBlankTarget() {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));
        AttendanceImportRow r = unresolvedRow(h, "P", "Unrecognised status 'P'");
        when(rowRepository.findById(7L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.correctRow(1L, 7L, "  "))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Pick a status");
    }

    @Test
    void correctRowRejectsRowOwnedByAnotherImport() {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));
        AttendanceImportRow r = unresolvedRow(history("PREVIEWED"), "P", "Unrecognised status 'P'");
        r.getImportHistory().setId(99L);
        when(rowRepository.findById(7L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.correctRow(1L, 7L, "PL"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not belong to import");
    }

    @Test
    void correctRowRejectsAlreadyResolvedEntry() {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));
        AttendanceImportRow r = unresolvedRow(h, "P", "Unrecognised status 'P'");
        r.setCorrected(true);
        when(rowRepository.findById(7L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.correctRow(1L, 7L, "PL"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already resolved");
    }

    @Test
    void skipRowRecordsExplicitSkipAndTakesRowOutOfCommit() {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));
        AttendanceImportRow r = unresolvedRow(h, "P", "Unrecognised status 'P'");
        when(rowRepository.findById(7L)).thenReturn(Optional.of(r));
        when(rowRepository.countByImportHistoryIdAndSkippedIsTrue(1L)).thenReturn(1L);
        when(rowRepository.countByImportHistoryIdAndIssueIsNotNullAndCorrectedIsFalseAndSkippedIsFalse(1L))
                .thenReturn(0L);

        HistoricalImportDtos.ResolveRowResponse resp = service.skipRow(1L, 7L, "leave was never logged");

        assertThat(r.getSkipped()).isTrue();
        assertThat(r.getAction()).isEqualTo("SKIPPED");
        assertThat(r.getCorrected()).isFalse();
        assertThat(r.getIssue()).contains("skipped: leave was never logged");
        assertThat(resp.summary().skipped()).isEqualTo(1L);
        assertThat(resp.summary().remaining()).isZero();
        verify(auditService).record(org.mockito.ArgumentMatchers.eq("HISTORICAL_ROW_SKIPPED"),
                any(), any(), any(), any());
    }

    @Test
    void unresolvedListsPendingEntriesWithCellReference() {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));
        AttendanceImportRow r = unresolvedRow(h, "P", "Unrecognised status 'P'");
        when(rowRepository
                .findByImportHistoryIdAndIssueIsNotNullAndCorrectedIsFalseAndSkippedIsFalseOrderByIdAsc(
                        eq(1L), any()))
                .thenReturn(new PageImpl<>(List.of(r)));
        when(rowRepository.countByImportHistoryIdAndIssueIsNotNull(1L)).thenReturn(5L);
        when(rowRepository.countByImportHistoryIdAndIssueIsNotNullAndCorrectedIsFalseAndSkippedIsFalse(1L))
                .thenReturn(3L);

        HistoricalImportDtos.UnresolvedResponse resp = service.unresolved(1L, 0, 50);

        assertThat(resp.summary().total()).isEqualTo(5L);
        assertThat(resp.summary().remaining()).isEqualTo(3L);
        assertThat(resp.entries()).hasSize(1);
        assertThat(resp.entries().get(0).cellRef()).isEqualTo("Row 2, Column D");
        assertThat(resp.entries().get(0).originalStatus()).isEqualTo("P");
        assertThat(resp.entries().get(0).issue()).contains("Unrecognised");
        assertThat(resp.entries().get(0).corrected()).isFalse();
        assertThat(resp.entries().get(0).skipped()).isFalse();
    }

    /**
     * The totals have to stay put as entries are resolved. An earlier version
     * cleared the issue on correction, which quietly shrank "total" and pinned
     * "corrected" at zero because both counts are keyed off the issue being present.
     */
    @Test
    void resolutionTotalsDoNotDriftOnceAnEntryIsCorrected() {
        AttendanceImportHistory h = history("PREVIEWED");
        when(historyRepository.findById(1L)).thenReturn(Optional.of(h));
        AttendanceImportRow r = unresolvedRow(h, "P", "Unrecognised status 'P'");
        when(rowRepository.findById(7L)).thenReturn(Optional.of(r));
        when(statusRepository.findById("PL"))
                .thenReturn(Optional.of(new AttendanceStatus("PL", "Privilege Leave", null, "amber")));
        // Both rows stay flagged: one has been corrected, one is still open.
        when(rowRepository.countByImportHistoryIdAndIssueIsNotNull(1L)).thenReturn(2L);
        when(rowRepository.countByImportHistoryIdAndIssueIsNotNullAndCorrectedIsTrue(1L)).thenReturn(1L);
        when(rowRepository.countByImportHistoryIdAndIssueIsNotNullAndCorrectedIsFalseAndSkippedIsFalse(1L))
                .thenReturn(1L);

        HistoricalImportDtos.ResolveRowResponse resp = service.correctRow(1L, 7L, "PL");

        assertThat(resp.summary().total()).isEqualTo(2L);
        assertThat(resp.summary().corrected()).isEqualTo(1L);
        assertThat(resp.summary().remaining()).isEqualTo(1L);
        // The reason the cell was questioned survives the correction.
        assertThat(r.getIssue()).contains("Unrecognised");
    }

    @Test
    void columnNameWalksPastZ() {
        assertThat(HistoricalImportService.cellRefFor(1, 7)).isEqualTo("Row 1, Column G");
        assertThat(HistoricalImportService.cellRefFor(7, 26)).isEqualTo("Row 7, Column Z");
        assertThat(HistoricalImportService.cellRefFor(7, 27)).isEqualTo("Row 7, Column AA");
        assertThat(HistoricalImportService.cellRefFor(7, null)).isEqualTo("Row 7");
    }
}