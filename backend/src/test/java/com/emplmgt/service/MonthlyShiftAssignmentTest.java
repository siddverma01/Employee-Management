package com.emplmgt.service;

import com.emplmgt.dto.HistoricalImportDtos;
import com.emplmgt.entity.AttendanceImportHistory;
import com.emplmgt.entity.AttendanceImportRow;
import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.AttendanceShiftAssignment;
import com.emplmgt.repository.AttendanceImportHistoryRepository;
import com.emplmgt.repository.AttendanceImportRowRepository;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.AttendanceShiftAssignmentRepository;
import com.emplmgt.repository.AttendanceWeekOffAssignmentRepository;
import com.emplmgt.repository.AttendanceStatusRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.LeaveDaysCalculator;
import com.emplmgt.util.WeekOffUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression cover for the shift-rotation defect: the Attendance Roster showed
 * one fixed shift per engineer for every month because the shift lived only on
 * the employee master.
 *
 * <p>The fixture is deliberately the same engineer rostered to a different shift
 * in each month, alongside a second engineer whose shifts move independently and
 * one engineer with no shift at all, so "one global shift", "reuse last month's
 * value" and "default when missing" all fail here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonthlyShiftAssignmentTest {

    private static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final YearMonth NOV = YearMonth.of(2026, 11);

    /** The engineer's current master shift, i.e. the value that used to leak into every month. */
    private static final String MASTER_PM = "13:30-22:30";

    private static final String AM = "05:30-14:30";
    private static final String NIGHT = "19:00-04:00";

    @Mock AttendanceStatusRepository statusRepository;
    @Mock ImportEmployeeRepository importEmployeeRepository;
    @Mock AttendanceRecordRepository recordRepository;
    @Mock AttendanceShiftAssignmentRepository shiftAssignmentRepository;
    @Mock AttendanceWeekOffAssignmentRepository weekOffAssignmentRepository;
    @Mock LeaveDaysCalculator leaveDaysCalculator;
    @Mock AttendanceImportHistoryRepository historyRepository;
    @Mock AttendanceImportRowRepository rowRepository;
    @Mock AuditService auditService;
    @Mock AppClock appClock;

    private WeekOffUtil weekOffUtil;
    private HistoricalImportService service;
    private final List<AttendanceImportRow> persistedRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        weekOffUtil = new com.emplmgt.util.WeekOffUtil(leaveDaysCalculator);
        when(appClock.now()).thenReturn(NOW);
        when(historyRepository.save(any())).thenAnswer(inv -> {
            AttendanceImportHistory h = inv.getArgument(0);
            if (h.getId() == null) {
                h.setId(100L);
            }
            return h;
        });
        when(rowRepository.saveAll(any())).thenAnswer(inv -> {
            java.util.Collection<AttendanceImportRow> rows = inv.getArgument(0);
            for (AttendanceImportRow r : rows) {
                if (r.getId() == null) {
                    r.setId((long) persistedRows.size() + 1);
                }
                persistedRows.add(r);
            }
            return rows;
        });
        when(shiftAssignmentRepository.findByEmployeeIdAndPeriodStart(anyString(), any()))
                .thenReturn(Optional.empty());
        service = new HistoricalImportService(statusRepository, importEmployeeRepository, recordRepository,
                shiftAssignmentRepository, weekOffAssignmentRepository, weekOffUtil, historyRepository,
                rowRepository, auditService, appClock, new ObjectMapper());
    }

    // ------------------------------------------------------------- fixture

    /** One rostered person: {@code {id, name, location, shift, weekOff}} then their day statuses. */
    private record Staff(String id, String name, String location, String shift, String weekOff, String statuses) {
    }

    private static byte[] monthBook(YearMonth ym, Staff... staff) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle ds = wb.createCellStyle();
            ds.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd-mmm-yyyy"));
            Sheet s = wb.createSheet(ym.getMonth().name().substring(0, 3) + " " + ym.getYear());

            Row h = s.createRow(0);
            text(h, 0, "Emp Code");
            text(h, 1, "Emp Name");
            text(h, 2, "Location");
            text(h, 3, "Shift");
            text(h, 4, "Week Off");
            int days = ym.lengthOfMonth();
            for (int i = 0; i < days; i++) {
                var c = h.createCell(5 + i);
                c.setCellValue(java.util.Date.from(ym.atDay(i + 1).atStartOfDay()
                        .atZone(java.time.ZoneId.systemDefault()).toInstant()));
                c.setCellStyle(ds);
            }
            for (int r = 0; r < staff.length; r++) {
                Staff p = staff[r];
                Row row = s.createRow(1 + r);
                text(row, 0, p.id());
                text(row, 1, p.name());
                text(row, 2, p.location());
                text(row, 3, p.shift());
                text(row, 4, p.weekOff());
                for (int i = 0; i < p.statuses().length(); i++) {
                    text(row, 5 + i, p.statuses().charAt(i) == 'W' ? "WFO" : "WO");
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void text(Row r, int c, String v) {
        if (v != null) {
            r.createCell(c).setCellValue(v);
        }
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    /** Engineer {@code E1} rotates AM -> Night -> PM; {@code E2} moves independently. */
    private static byte[] september() throws IOException {
        return monthBook(SEP,
                new Staff("E1", "Alice", "Pune", AM, "Sun-Mon", "WW"),
                new Staff("E2", "Bob", "Pune", NIGHT, "Sat-Sun", "WW"));
    }

    private static byte[] october() throws IOException {
        return monthBook(OCT,
                new Staff("E1", "Alice", "Pune", NIGHT, "Sun-Mon", "WW"),
                new Staff("E2", "Bob", "BLR", AM, "Fri-Sat", "WW"));
    }

    /** {@code E1} back on to PM (its master value); {@code E2} has no shift cell at all. */
    private static byte[] november() throws IOException {
        return monthBook(NOV,
                new Staff("E1", "Alice", "Pune", MASTER_PM, "Sun-Mon", "WW"),
                new Staff("E2", "Bob", "BLR", null, "Fri-Sat", "WW"));
    }

    private static AttendanceImportHistory history(long id, String status) {
        return AttendanceImportHistory.builder().id(id).fileName("historical/x" + id + ".xlsx")
                .originalFileName("roster.xlsx").importedAt(NOW).status(status)
                .totalSheets(1).sheetsImported(1).sheetsSkipped(0)
                .employeesDetected(2).recordsDetected(4).build();
    }

    private static AttendanceImportRow staged(AttendanceImportHistory h, String id, String shift) {
        return AttendanceImportRow.builder().importHistory(h).employeeId(id).employeeName("n")
                .employeeShift(shift)
                .attendanceDate(LocalDate.of(2025, 9, 1)).incomingStatus("WFO")
                .statusName("Work From Office").isUnknown(false).sheetName("Sep").sourceRow(2)
                .action("INSERT").build();
    }

    private List<AttendanceShiftAssignment> savedAssignments() {
        ArgumentCaptor<AttendanceShiftAssignment> captor = ArgumentCaptor.forClass(AttendanceShiftAssignment.class);
        verify(shiftAssignmentRepository, atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    // ------------------------------------------------------------- parsing

    @Nested
    @DisplayName("Excel is the source of truth")
    class Parsing {

        @Test
        void eachMonthIsParsedWithItsOwnShift() throws IOException {
            HistoricalImportDtos.PreviewResponse sep = service.preview(file("sep.xlsx", september()), 1L);
            HistoricalImportDtos.PreviewResponse oct = service.preview(file("oct.xlsx", october()), 1L);
            HistoricalImportDtos.PreviewResponse nov = service.preview(file("nov.xlsx", november()), 1L);

            Map<String, String> shiftsByPeriod = new java.util.TreeMap<>();
            shiftsByPeriod.put("2026-09", shiftOf(sep, "E1"));
            shiftsByPeriod.put("2026-10", shiftOf(oct, "E1"));
            shiftsByPeriod.put("2026-11", shiftOf(nov, "E1"));

            assertThat(shiftsByPeriod)
                    .containsExactly(Map.entry("2026-09", AM),
                            Map.entry("2026-10", NIGHT),
                            Map.entry("2026-11", MASTER_PM));
        }

        @Test
        void previewShiftMatchesTheRosteredEmployeeNotAMasterValue() throws IOException {
            HistoricalImportDtos.PreviewResponse oct = service.preview(file("oct.xlsx", october()), 1L);

            // E1's master is PM; October's sheet says Night, and that must win.
            assertThat(shiftOf(oct, "E1")).isEqualTo(NIGHT);
            assertThat(shiftOf(oct, "E2")).isEqualTo(AM);
        }

        @Test
        void engineersKeepIndependentAssignments() throws IOException {
            HistoricalImportDtos.PreviewResponse oct = service.preview(file("oct.xlsx", october()), 1L);

            assertThat(shiftOf(oct, "E1")).isNotEqualTo(shiftOf(oct, "E2"));
        }

        @Test
        void consecutiveIdenticalShiftsAreKeptIdentical() throws IOException {
            // Same shift text in both months, deliberately with the loose
            // spelling the workbooks use, to prove it is not rewritten.
            String looseNight = "19:00 - 04:00";
            HistoricalImportDtos.PreviewResponse sep = service.preview(
                    file("sep.xlsx", monthBook(SEP, new Staff("E1", "Alice", "Pune", looseNight, "Sun-Mon", "WW"))), 1L);
            HistoricalImportDtos.PreviewResponse oct = service.preview(
                    file("oct.xlsx", monthBook(OCT, new Staff("E1", "Alice", "Pune", looseNight, "Sun-Mon", "WW"))), 1L);

            assertThat(shiftOf(sep, "E1")).isEqualTo(looseNight);
            assertThat(shiftOf(oct, "E1")).isEqualTo(looseNight);
        }

        @Test
        void missingShiftIsFlaggedRatherThanFilledIn() throws IOException {
            HistoricalImportDtos.PreviewResponse nov = service.preview(file("nov.xlsx", november()), 1L);

            assertThat(shiftOf(nov, "E2")).isNull();
            assertThat(nov.issues())
                    .filteredOn(i -> "MISSING_SHIFT".equals(i.type()))
                    .singleElement()
                    .satisfies(i -> {
                        assertThat(i.severity()).isEqualTo("WARNING");
                        assertThat(i.employeeId()).isEqualTo("E2");
                        assertThat(i.message()).contains("2026-11");
                    });
        }

        @Test
        void unparsableShiftIsFlaggedSeparately() throws IOException {
            HistoricalImportDtos.PreviewResponse resp = service.preview(file("sep.xlsx",
                    monthBook(SEP, new Staff("E1", "Alice", "Pune", "General Shift", "Sun-Mon", "WW"))), 1L);

            assertThat(shiftOf(resp, "E1")).isEqualTo("General Shift");
            assertThat(resp.issues()).extracting(HistoricalImportDtos.ValidationIssue::type)
                    .contains("UNPARSED_SHIFT");
        }

        /** {@code null} is a valid answer: it means the sheet had no shift for that engineer. */
        private String shiftOf(HistoricalImportDtos.PreviewResponse resp, String employeeId) {
            for (HistoricalImportDtos.RowView r : resp.rows()) {
                if (employeeId.equals(r.employeeId())) {
                    return r.shift();
                }
            }
            throw new AssertionError("no rows for " + employeeId);
        }
    }

    // ------------------------------------------------------------- commit

    @Nested
    @DisplayName("Commit persists shift per period")
    class Commit {

        @Test
        void writesOneAssignmentPerEmployeePerMonth() throws IOException {
            Map<Long, AttendanceImportHistory> staged = stageThreeMonths();

            commit(staged.get(1L));
            commit(staged.get(2L));
            commit(staged.get(3L));

            List<AttendanceShiftAssignment> saved = savedAssignments();
            assertThat(saved.stream().filter(a -> "E1".equals(a.getEmployeeId())))
                    .extracting(a -> a.getPeriodStart().toString())
                    .contains("2026-09-01", "2026-10-01", "2026-11-01");
            assertThat(saved.stream().filter(a -> "E1".equals(a.getEmployeeId())))
                    .extracting(AttendanceShiftAssignment::getShiftValue)
                    .containsExactlyInAnyOrder(AM, NIGHT, MASTER_PM);
        }

        @Test
        void monthKeysAreDistinctSoMonthsCannotCollide() throws IOException {
            Map<Long, AttendanceImportHistory> staged = stageThreeMonths();

            commit(staged.get(1L));
            commit(staged.get(2L));
            commit(staged.get(3L));

            List<AttendanceShiftAssignment> e1 = savedAssignments().stream()
                    .filter(a -> "E1".equals(a.getEmployeeId())).toList();
            assertThat(e1).hasSize(3);
            assertThat(e1.stream().map(AttendanceShiftAssignment::getPeriodStart).distinct().count())
                    .isEqualTo(3);
        }

        @Test
        void importingOneMonthDoesNotRewriteAnotherMonthsShift() throws IOException {
            Map<Long, AttendanceImportHistory> staged = stageThreeMonths();
            commit(staged.get(1L));

            // October arrives on its own; September's stored value must survive.
            AttendanceShiftAssignment september = AttendanceShiftAssignment.builder()
                    .id(42L).employeeId("E1").periodStart(SEP.atDay(1))
                    .shiftValue(AM).shiftKey("05:30-14:30").build();
            when(shiftAssignmentRepository.findByEmployeeIdAndPeriodStart("E1", SEP.atDay(1)))
                    .thenReturn(Optional.of(september));
            clearInvocations(shiftAssignmentRepository);

            commit(staged.get(2L));

            // October is written; September's row is not touched again at all.
            verify(shiftAssignmentRepository, never())
                    .findByEmployeeIdAndPeriodStart("E1", SEP.atDay(1));
            verify(shiftAssignmentRepository).save(org.mockito.ArgumentMatchers.argThat(
                    a -> OCT.atDay(1).equals(a.getPeriodStart()) && NIGHT.equals(a.getShiftValue())));
            assertThat(september.getShiftValue()).isEqualTo(AM);
        }

        @Test
        void masterShiftEndsUpAsTheMostRecentPeriodNotTheFirst() throws IOException {
            Map<Long, AttendanceImportHistory> staged = stageThreeMonths();

            commit(staged.get(1L));
            commit(staged.get(2L));
            commit(staged.get(3L));

            // The master tracks the most recently imported period, so it ends on
            // November's PM and never stays stuck on September's AM.
            verify(importEmployeeRepository).refreshMasterShift("E1", MASTER_PM);
            verify(importEmployeeRepository, never()).refreshMasterShift("E2", MASTER_PM);
        }

        @Test
        void blankSourceShiftWritesNoAssignmentAndNoMasterValue() {
            AttendanceImportHistory h = history(9L, "PREVIEWED");
            when(historyRepository.findById(9L)).thenReturn(Optional.of(h));
            when(rowRepository.findByImportHistoryIdAndActionInOrderByIdAsc(9L, List.of("INSERT", "UPDATE")))
                    .thenReturn(List.of(staged(h, "E1", null)));
            when(recordRepository.findByEmployeeIdAndAttendanceDate(anyString(), any()))
                    .thenReturn(Optional.empty());

            service.commit(9L, null, 1L);

            verify(shiftAssignmentRepository, never())
                    .save(org.mockito.ArgumentMatchers.argThat(
                            a -> a.getShiftValue() == null));
            verify(importEmployeeRepository, never()).refreshMasterShift(anyString(), anyString());
        }

        @Test
        void reimportingAMonthUpdatesItInsteadOfAddingAConflictingRow() throws IOException {
            AttendanceImportHistory h = history(5L, "PREVIEWED");
            when(historyRepository.findById(5L)).thenReturn(Optional.of(h));
            when(rowRepository.findByImportHistoryIdAndActionInOrderByIdAsc(5L, List.of("INSERT", "UPDATE")))
                    .thenReturn(List.of(staged(h, "E1", NIGHT)));
            when(recordRepository.findByEmployeeIdAndAttendanceDate(anyString(), any()))
                    .thenReturn(Optional.empty());
            AttendanceShiftAssignment existing = AttendanceShiftAssignment.builder()
                    .id(42L).employeeId("E1").periodStart(LocalDate.of(2025, 9, 1))
                    .shiftValue("wrong").shiftKey("wrong").build();
            when(shiftAssignmentRepository.findByEmployeeIdAndPeriodStart("E1", LocalDate.of(2025, 9, 1)))
                    .thenReturn(Optional.of(existing));

            service.commit(5L, null, 1L);

            ArgumentCaptor<AttendanceShiftAssignment> captor = ArgumentCaptor.forClass(AttendanceShiftAssignment.class);
            verify(shiftAssignmentRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(existing);
            assertThat(existing.getShiftValue()).isEqualTo(NIGHT);
        }

        /** Stages September, October and November for E1/E2 and returns their history ids. */
        private Map<Long, AttendanceImportHistory> stageThreeMonths() throws IOException {
            Map<Long, AttendanceImportHistory> out = new java.util.LinkedHashMap<>();
            long[] ids = {1L, 2L, 3L};
            YearMonth[] months = {SEP, OCT, NOV};
            byte[][] books = {september(), october(), november()};
            for (int i = 0; i < ids.length; i++) {
                AttendanceImportHistory h = history(ids[i], "PREVIEWED");
                when(historyRepository.findById(ids[i])).thenReturn(Optional.of(h));

                List<AttendanceImportRow> rows = new ArrayList<>();
                HistoricalImportDtos.PreviewResponse preview = service.preview(file("m" + i + ".xlsx", books[i]), 1L);
                for (HistoricalImportDtos.RowView v : preview.rows()) {
                    if (!"INSERT".equals(v.action())) {
                        continue;
                    }
                    rows.add(AttendanceImportRow.builder().importHistory(h)
                            .employeeId(v.employeeId()).employeeName(v.employeeName())
                            .employeeShift(v.shift()).employeeLocation(v.location())
                            .attendanceDate(v.attendanceDate()).incomingStatus(v.incomingStatus())
                            .statusName(v.statusName()).isUnknown(v.unknown())
                            .sheetName("s" + i).sourceRow(v.sourceRow()).action("INSERT").build());
                }
                when(rowRepository.findByImportHistoryIdAndActionInOrderByIdAsc(ids[i], List.of("INSERT", "UPDATE")))
                        .thenReturn(rows);
                when(recordRepository.findByEmployeeIdAndAttendanceDate(anyString(), any()))
                        .thenReturn(Optional.empty());
                out.put(ids[i], h);
            }
            return out;
        }

        private void commit(AttendanceImportHistory h) {
            service.commit(h.getId(), null, 1L);
        }
    }
}