package com.emplmgt.service;

import com.emplmgt.dto.HistoricalImportDtos;
import com.emplmgt.entity.AttendanceImportHistory;
import com.emplmgt.entity.AttendanceImportRow;
import com.emplmgt.entity.AttendanceWeekOffAssignment;
import com.emplmgt.repository.AttendanceImportHistoryRepository;
import com.emplmgt.repository.AttendanceImportRowRepository;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.AttendanceShiftAssignmentRepository;
import com.emplmgt.repository.AttendanceStatusRepository;
import com.emplmgt.repository.AttendanceWeekOffAssignmentRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression cover for the week-off rotation defect: the Attendance Roster showed
 * one fixed week off per engineer for every month because the schedule lived only
 * on the employee master.
 *
 * <p>The fixture is the same engineer rostered to a different week off in each
 * month, alongside a second engineer whose schedule moves independently, plus an
 * engineer whose schedule is unrecognisable text, so "one global week off", "reuse
 * last month's value" and "default when missing" all fail here.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MonthlyWeekOffAssignmentTest {

    private static final Instant NOW = Instant.parse("2026-12-01T00:00:00Z");

    private static final YearMonth SEP = YearMonth.of(2026, 9);
    private static final YearMonth OCT = YearMonth.of(2026, 10);
    private static final YearMonth NOV = YearMonth.of(2026, 11);

    /** The engineer's current master week off, i.e. the value that used to leak into every month. */
    private static final String MASTER = "Mon-Tues";
    private static final String NIGHT = "19:00-04:00";
    private static final String DAY = "09:00-18:00";

    @Mock AttendanceStatusRepository statusRepository;
    @Mock ImportEmployeeRepository importEmployeeRepository;
    @Mock AttendanceRecordRepository recordRepository;
    @Mock AttendanceShiftAssignmentRepository shiftAssignmentRepository;
    @Mock AttendanceWeekOffAssignmentRepository weekOffAssignmentRepository;
    @Mock AttendanceImportHistoryRepository historyRepository;
    @Mock AttendanceImportRowRepository rowRepository;
    @Mock AuditService auditService;
    @Mock AppClock appClock;
    @Mock LeaveDaysCalculator leaveDaysCalculator;

    private WeekOffUtil weekOffUtil;

    private HistoricalImportService service;
    private final List<AttendanceImportRow> persistedRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        weekOffUtil = new WeekOffUtil(leaveDaysCalculator);
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
        when(weekOffAssignmentRepository.findByEmployeeIdAndPeriodStart(anyString(), any()))
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

    /** E1 rotates Sat-Sun -> Wed-Thurs -> Mon-Tues; E2 moves independently. */
    private static byte[] september() throws IOException {
        return monthBook(SEP,
                new Staff("E1", "Alice", "Pune", "05:30-14:30", "Sat-Sun", "WW"),
                new Staff("E2", "Bob", "Pune", "19:00-04:00", "Fri-Sat", "WW"));
    }

    private static byte[] october() throws IOException {
        return monthBook(OCT,
                new Staff("E1", "Alice", "Pune", "19:00-04:00", "Wed-Thurs", "WW"),
                new Staff("E2", "Bob", "BLR", "05:30-14:30", "Sun-Mon", "WW"));
    }

    /** E1 back on to its master value; E2 has no week off cell at all. */
    private static byte[] november() throws IOException {
        return monthBook(NOV,
                new Staff("E1", "Alice", "Pune", "13:30-22:30", MASTER, "WW"),
                new Staff("E2", "Bob", "BLR", "05:30-14:30", null, "WW"));
    }

    private static AttendanceImportHistory history(long id, String status) {
        return AttendanceImportHistory.builder().id(id).fileName("historical/x" + id + ".xlsx")
                .originalFileName("roster.xlsx").importedAt(NOW).status(status)
                .totalSheets(1).sheetsImported(1).sheetsSkipped(0)
                .employeesDetected(2).recordsDetected(4).build();
    }

    /** Staged source row that also carries the shift from its sheet. */
    private static AttendanceImportRow staged(AttendanceImportHistory h, String id, String status,
                                               String shift, String weekOff) {
        AttendanceImportRow r = staged(h, id, weekOff);
        r.setEmployeeShift(shift);
        r.setIncomingStatus(status == null ? "WFO" : status);
        return r;
    }

    private static AttendanceImportRow staged(AttendanceImportHistory h, String id, String weekOff) {
        return AttendanceImportRow.builder().importHistory(h).employeeId(id).employeeName("n")
                .employeeWeekOff(weekOff)
                .attendanceDate(LocalDate.of(2025, 9, 1)).incomingStatus("WFO")
                .statusName("Work From Office").isUnknown(false).sheetName("Sep").sourceRow(2)
                .action("INSERT").build();
    }

    /** Stages rows for a PREVIEWED import and points the service at that history. */
    private void stageCommit(long id, AttendanceImportRow... rows) {
        when(historyRepository.findById(id)).thenReturn(Optional.of(history(id, "PREVIEWED")));
        when(rowRepository.findByImportHistoryIdAndActionInOrderByIdAsc(id, List.of("INSERT", "UPDATE")))
                .thenReturn(List.of(rows));
    }

        @Test
        void anUnrecognisedValueIsWarnedAboutAndNeverPersisted() {
            AttendanceImportHistory h = history(12L, "PREVIEWED");
            stageCommit(12L, staged(h, "E1", null, NIGHT, "Ram Murthy"));

            service.commit(12L, null, 7L);

            // A manager's name must never reach the roster's Week Off column.
            verify(weekOffAssignmentRepository, never()).save(any());
        }

        @Test
        void aValidWeekOffSucceedingAnInvalidOneInTheSameMonthIsStillStored() {
            AttendanceImportHistory h = history(13L, "PREVIEWED");
            stageCommit(13L,
                    staged(h, "E1", null, NIGHT, "Manager"),
                    staged(h, "E1", null, DAY, "Sat-Sun"));

            service.commit(13L, null, 7L);

            ArgumentCaptor<AttendanceWeekOffAssignment> captor =
                    ArgumentCaptor.forClass(AttendanceWeekOffAssignment.class);
            verify(weekOffAssignmentRepository).save(captor.capture());
            assertThat(captor.getValue().getWeekOffValue()).isEqualTo("Sat-Sun");
        }

        @Test
        void theFullAndShortDaySpellingsCollapseToOneKey() {
            assertThat(WeekOffUtil.comparisonKey("Monday")).isEqualTo("MON");
            assertThat(WeekOffUtil.comparisonKey("MON")).isEqualTo("MON");
            assertThat(WeekOffUtil.comparisonKey("monday")).isEqualTo("MON");
            assertThat(WeekOffUtil.comparisonKey("Tuesday")).isEqualTo("TUE");
            assertThat(WeekOffUtil.comparisonKey("Tues")).isEqualTo("TUE");
            assertThat(WeekOffUtil.comparisonKey("Thursday")).isEqualTo("THU");
            assertThat(WeekOffUtil.comparisonKey("Thurs")).isEqualTo("THU");
        }

        @Test
        void cosmeticVariantsOfOneScheduleShareAKeyButRealChangesDoNot() {
            String base = WeekOffUtil.comparisonKey("Sat-Sun");
            assertThat(WeekOffUtil.comparisonKey("sat-sun")).isEqualTo(base);
            assertThat(WeekOffUtil.comparisonKey("SAT - SUN")).isEqualTo(base);
            assertThat(WeekOffUtil.comparisonKey("Sat, Sun")).isEqualTo(base);
            assertThat(WeekOffUtil.comparisonKey("Sat-Sun (9-6)")).isEqualTo(base);
            assertThat(WeekOffUtil.comparisonKey("Sunday-Saturday")).isEqualTo("SUN-SAT");
            assertThat(WeekOffUtil.comparisonKey("Mon-Tues")).isEqualTo("MON-TUE");
            assertThat(WeekOffUtil.comparisonKey("Mon-Tues (9-6)")).isEqualTo("MON-TUE");
        }

        @Test
        void aRangeKeepsItsDirectionSoAWrappedRangeStaysDistinct() {
            // parse() reads TUE-MON as a range wrapping through the week, which is
            // not Mon-Tue, so canonicalising must not sort the tokens.
            assertThat(WeekOffUtil.comparisonKey("Tue-Mon")).isNotEqualTo(
                    WeekOffUtil.comparisonKey("Mon-Tue"));
        }

        @Test
        void placeholdersAreNotMistakenForSchedules() {
            assertThat(weekOffUtil.isRecognised("Sat-Sun")).isTrue();
            assertThat(weekOffUtil.isRecognised("Mon-Tues (9-6)")).isTrue();
            assertThat(weekOffUtil.isRecognised("Wednesday")).isTrue();

            assertThat(weekOffUtil.isRecognised("WeekOff")).isFalse();
            assertThat(weekOffUtil.isRecognised("WOs")).isFalse();
            assertThat(weekOffUtil.isRecognised("Manager")).isFalse();
            assertThat(weekOffUtil.isRecognised("Ram Murthy")).isFalse();
            assertThat(weekOffUtil.isRecognised("NA VOICE ROSTER FOR October-26")).isFalse();
            assertThat(weekOffUtil.isRecognised("")).isFalse();
            assertThat(weekOffUtil.isRecognised(null)).isFalse();
        }

        @Test
        void anUnrecognisedValueAloneLeavesTheMonthUnassigned() {
            AttendanceImportHistory h = history(14L, "PREVIEWED");
            stageCommit(14L, staged(h, "E1", null, NIGHT, "WOs"));

            service.commit(14L, null, 7L);

            verify(weekOffAssignmentRepository, never()).save(any());
            verify(importEmployeeRepository, never()).refreshMasterWeekOff(anyString(), anyString());
        }

    private List<AttendanceWeekOffAssignment> savedAssignments() {
        ArgumentCaptor<AttendanceWeekOffAssignment> captor =
                ArgumentCaptor.forClass(AttendanceWeekOffAssignment.class);
        verify(weekOffAssignmentRepository, atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    private static String weekOffOf(HistoricalImportDtos.PreviewResponse preview, String employeeId) {
        // Not a stream map(): a missing week off is a null field, and Optional.map
        // rejects nulls.
        for (HistoricalImportDtos.RowView r : preview.rows()) {
            if (employeeId.equals(r.employeeId())) {
                return r.weekOff();
            }
        }
        return null;
    }

    // ------------------------------------------------------------- parsing

    @Nested
    @DisplayName("Excel is the source of truth")
    class Parsing {

        @Test
        void eachMonthIsParsedWithItsOwnWeekOff() throws IOException {
            HistoricalImportDtos.PreviewResponse sep = service.preview(file("sep.xlsx", september()), 1L);
            HistoricalImportDtos.PreviewResponse oct = service.preview(file("oct.xlsx", october()), 1L);
            HistoricalImportDtos.PreviewResponse nov = service.preview(file("nov.xlsx", november()), 1L);

            Map<String, String> byPeriod = new java.util.TreeMap<>();
            byPeriod.put("2026-09", weekOffOf(sep, "E1"));
            byPeriod.put("2026-10", weekOffOf(oct, "E1"));
            byPeriod.put("2026-11", weekOffOf(nov, "E1"));

            assertThat(byPeriod)
                    .containsExactly(Map.entry("2026-09", "Sat-Sun"),
                            Map.entry("2026-10", "Wed-Thurs"),
                            Map.entry("2026-11", MASTER));
        }

        @Test
        void differentEmployeesHaveIndependentWeekOffs() throws IOException {
            HistoricalImportDtos.PreviewResponse sep = service.preview(file("sep.xlsx", september()), 1L);
            HistoricalImportDtos.PreviewResponse oct = service.preview(file("oct.xlsx", october()), 1L);

            assertThat(weekOffOf(sep, "E1")).isEqualTo("Sat-Sun");
            assertThat(weekOffOf(sep, "E2")).isEqualTo("Fri-Sat");
            assertThat(weekOffOf(oct, "E1")).isEqualTo("Wed-Thurs");
            assertThat(weekOffOf(oct, "E2")).isEqualTo("Sun-Mon");
        }

        @Test
        void consecutiveIdenticalValuesArePreservedAsWritten() throws IOException {
            // Same schedule in two months, spelled the way each sheet spells it.
            byte[] sepBook = monthBook(SEP, new Staff("E1", "Alice", "Pune", "05:30-14:30", "sat-sun", "WW"));
            byte[] octBook = monthBook(OCT, new Staff("E1", "Alice", "Pune", "05:30-14:30", "Sat-Sun", "WW"));

            HistoricalImportDtos.PreviewResponse sep = service.preview(file("sep.xlsx", sepBook), 1L);
            HistoricalImportDtos.PreviewResponse oct = service.preview(file("oct.xlsx", octBook), 1L);

            // Each month keeps its own spelling rather than being rewritten.
            assertThat(weekOffOf(sep, "E1")).isEqualTo("sat-sun");
            assertThat(weekOffOf(oct, "E1")).isEqualTo("Sat-Sun");
        }

        @Test
        void trailingScheduleNotesArePreservedInTheValue() throws IOException {
            byte[] sepBook = monthBook(SEP,
                    new Staff("E1", "Alice", "Pune", "05:30-14:30", "Sun-mon(Sat 9-6)", "WW"));

            HistoricalImportDtos.PreviewResponse sep = service.preview(file("sep.xlsx", sepBook), 1L);

            assertThat(weekOffOf(sep, "E1")).isEqualTo("Sun-mon(Sat 9-6)");
        }

        @Test
        void missingWeekOffIsFlaggedRatherThanDefaulted() throws IOException {
            HistoricalImportDtos.PreviewResponse nov = service.preview(file("nov.xlsx", november()), 1L);

            assertThat(weekOffOf(nov, "E2")).isNull();
            assertThat(nov.issues())
                    .anyMatch(i -> "MISSING_WEEK_OFF".equals(i.type())
                            && "E2".equals(i.employeeId())
                            && "WARNING".equals(i.severity()));
        }

        @Test
        void unrecognisedWeekOffIsFlaggedForReview() throws IOException {
            byte[] book = monthBook(SEP, new Staff("E1", "Alice", "Pune", "05:30-14:30", "Ram Murthy", "WW"));

            HistoricalImportDtos.PreviewResponse preview = service.preview(file("sep.xlsx", book), 1L);

            assertThat(preview.issues())
                    .anyMatch(i -> "UNPARSED_WEEK_OFF".equals(i.type())
                            && "E1".equals(i.employeeId())
                            && i.message().contains("Ram Murthy"));
        }
    }

    // ------------------------------------------------------------- persistence

    @Nested
    @DisplayName("one assignment per employee per month")
    class Persistence {

        @Test
        void threeMonthsBecomeThreePeriodAssignments() {
            when(historyRepository.findById(10L)).thenReturn(Optional.of(history(10L, "PREVIEWED")));
            when(rowRepository.findByImportHistoryIdAndActionInOrderByIdAsc(10L, List.of("INSERT", "UPDATE")))
                    .thenReturn(List.of(
                            staged(history(10L, "PREVIEWED"), "E1", "Sat-Sun"),
                            staged(history(10L, "PREVIEWED"), "E2", "Fri-Sat")));

            service.commit(10L, null, 7L);

            assertThat(savedAssignments())
                    .extracting(a -> a.getEmployeeId() + "|" + a.getPeriodStart() + "|" + a.getWeekOffValue())
                    .containsExactlyInAnyOrder(
                            "E1|2025-09-01|Sat-Sun",
                            "E2|2025-09-01|Fri-Sat");
        }

        @Test
        void assignmentCarriesACanonicalKeyAndProvenance() {
            when(historyRepository.findById(10L)).thenReturn(Optional.of(history(10L, "PREVIEWED")));
            when(rowRepository.findByImportHistoryIdAndActionInOrderByIdAsc(10L, List.of("INSERT", "UPDATE")))
                    .thenReturn(List.of(
                            staged(history(10L, "PREVIEWED"), "E1", "sun-mon(Sat 9-6)")));

            service.commit(10L, null, 7L);

            AttendanceWeekOffAssignment saved = savedAssignments().get(0);
            // Source spelling is kept verbatim for display...
            assertThat(saved.getWeekOffValue()).isEqualTo("sun-mon(Sat 9-6)");
            // ...while the key folds case, spacing and the trailing note.
            assertThat(saved.getWeekOffKey()).isEqualTo("SUN-MON");
            assertThat(saved.getSourceSheet()).isEqualTo("Sep");
            assertThat(saved.getSourceRow()).isEqualTo(2);
            assertThat(saved.getPeriodStart()).isEqualTo(LocalDate.of(2025, 9, 1));
        }

        @Test
        void reimportingTheSameMonthUpdatesInPlaceInsteadOfDuplicating() {
            AttendanceWeekOffAssignment existing = AttendanceWeekOffAssignment.builder()
                    .id(7L).employeeId("E1").periodStart(LocalDate.of(2025, 9, 1))
                    .weekOffValue("Fri-Sat").createdAt(NOW).build();
            when(weekOffAssignmentRepository.findByEmployeeIdAndPeriodStart("E1", LocalDate.of(2025, 9, 1)))
                    .thenReturn(Optional.of(existing));
            when(historyRepository.findById(10L)).thenReturn(Optional.of(history(10L, "PREVIEWED")));
            when(rowRepository.findByImportHistoryIdAndActionInOrderByIdAsc(10L, List.of("INSERT", "UPDATE")))
                    .thenReturn(List.of(
                            staged(history(10L, "PREVIEWED"), "E1", "Sat-Sun")));

            service.commit(10L, null, 7L);

            ArgumentCaptor<AttendanceWeekOffAssignment> captor =
                    ArgumentCaptor.forClass(AttendanceWeekOffAssignment.class);
            verify(weekOffAssignmentRepository).save(captor.capture());
            assertThat(captor.getValue().getId()).isEqualTo(7L);
            assertThat(captor.getValue().getWeekOffValue()).isEqualTo("Sat-Sun");
        }

        @Test
        void missingWeekOffNeverOverwritesAnEarlierImport() {
            AttendanceWeekOffAssignment existing = AttendanceWeekOffAssignment.builder()
                    .id(7L).employeeId("E1").periodStart(LocalDate.of(2025, 9, 1))
                    .weekOffValue("Sat-Sun").createdAt(NOW).build();
            when(weekOffAssignmentRepository.findByEmployeeIdAndPeriodStart("E1", LocalDate.of(2025, 9, 1)))
                    .thenReturn(Optional.of(existing));
            when(historyRepository.findById(10L)).thenReturn(Optional.of(history(10L, "PREVIEWED")));
            when(rowRepository.findByImportHistoryIdAndActionInOrderByIdAsc(10L, List.of("INSERT", "UPDATE")))
                    .thenReturn(List.of(
                            staged(history(10L, "PREVIEWED"), "E1", null)));

            service.commit(10L, null, 7L);

            // Nothing is written, so the stored schedule survives untouched.
            verify(weekOffAssignmentRepository, org.mockito.Mockito.never()).save(any());
            assertThat(existing.getWeekOffValue()).isEqualTo("Sat-Sun");
        }

        @Test
        void importingANewMonthLeavesEarlierMonthsAlone() {
            // September arrives first, then October. Both must survive.
            AttendanceWeekOffAssignment september = AttendanceWeekOffAssignment.builder()
                    .id(7L).employeeId("E1").periodStart(LocalDate.of(2025, 9, 1))
                    .weekOffValue("Sat-Sun").createdAt(NOW).build();
            when(weekOffAssignmentRepository.findByEmployeeIdAndPeriodStart("E1", LocalDate.of(2025, 9, 1)))
                    .thenReturn(Optional.of(september));
            AttendanceImportRow octoberRow = AttendanceImportRow.builder()
                    .importHistory(history(11L, "PREVIEWED")).employeeId("E1").employeeName("n")
                    .employeeWeekOff("Wed-Thurs")
                    .attendanceDate(LocalDate.of(2025, 10, 1)).incomingStatus("WFO")
                    .statusName("Work From Office").isUnknown(false).sheetName("Oct").sourceRow(2)
                    .action("INSERT").build();
            when(historyRepository.findById(11L)).thenReturn(Optional.of(history(11L, "PREVIEWED")));
            when(rowRepository.findByImportHistoryIdAndActionInOrderByIdAsc(11L, List.of("INSERT", "UPDATE")))
                    .thenReturn(List.of(octoberRow));

            service.commit(11L, null, 7L);

            // October is written; September is not touched.
            ArgumentCaptor<AttendanceWeekOffAssignment> captor =
                    ArgumentCaptor.forClass(AttendanceWeekOffAssignment.class);
            verify(weekOffAssignmentRepository).save(captor.capture());
            assertThat(captor.getValue().getPeriodStart()).isEqualTo(LocalDate.of(2025, 10, 1));
            assertThat(september.getWeekOffValue()).isEqualTo("Sat-Sun");
        }

        @Test
        void masterTakesTheLatestImportedMonthNotTheFirstSeen() {
            AttendanceImportRow sep = staged(history(10L, "PREVIEWED"), "E1", "Sat-Sun");
            AttendanceImportRow oct = AttendanceImportRow.builder()
                    .importHistory(history(10L, "PREVIEWED")).employeeId("E1").employeeName("n")
                    .employeeWeekOff("Wed-Thurs")
                    .attendanceDate(LocalDate.of(2025, 10, 1)).incomingStatus("WFO")
                    .statusName("Work From Office").isUnknown(false).sheetName("Oct").sourceRow(2)
                    .action("INSERT").build();
            when(historyRepository.findById(10L)).thenReturn(Optional.of(history(10L, "PREVIEWED")));
            when(rowRepository.findByImportHistoryIdAndActionInOrderByIdAsc(10L, List.of("INSERT", "UPDATE")))
                    .thenReturn(List.of(sep, oct));

            service.commit(10L, null, 7L);

            verify(importEmployeeRepository).refreshMasterWeekOff("E1", "Wed-Thurs");
        }
    }
}