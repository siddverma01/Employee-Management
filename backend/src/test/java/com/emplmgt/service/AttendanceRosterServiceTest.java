package com.emplmgt.service;

import com.emplmgt.dto.AttendanceRosterDtos;
import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.AttendanceShiftAssignment;
import com.emplmgt.entity.AttendanceWeekOffAssignment;
import com.emplmgt.entity.AttendanceStatus;
import com.emplmgt.entity.Department;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.Holiday;
import com.emplmgt.entity.ImportEmployee;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.AttendanceShiftAssignmentRepository;
import com.emplmgt.repository.AttendanceWeekOffAssignmentRepository;
import com.emplmgt.util.WeekOffUtil;
import com.emplmgt.repository.AttendanceStatusRepository;
import com.emplmgt.repository.DepartmentRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.HolidayRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import com.emplmgt.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceRosterServiceTest {

    private static AttendanceShiftAssignment assignment(String id, String period, String shift) {
        return AttendanceShiftAssignment.builder().employeeId(id)
                .periodStart(YearMonth.parse(period).atDay(1)).shiftValue(shift).build();
    }

    private static AttendanceWeekOffAssignment weekOffAssignment(String id, String period, String weekOff) {
        return AttendanceWeekOffAssignment.builder().employeeId(id)
                .periodStart(YearMonth.parse(period).atDay(1)).weekOffValue(weekOff)
                .weekOffKey(WeekOffUtil.comparisonKey(weekOff)).build();
    }

    @Mock AttendanceRecordRepository recordRepository;
    @Mock ImportEmployeeRepository importEmployeeRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock DepartmentRepository departmentRepository;
    @Mock AttendanceStatusRepository statusRepository;
    @Mock HolidayRepository holidayRepository;
    @Mock AttendanceShiftAssignmentRepository shiftAssignmentRepository;
    @Mock AttendanceWeekOffAssignmentRepository weekOffAssignmentRepository;
    @Mock AuditService auditService;
    @Mock AppClock appClock;

    AttendanceRosterService service;
    private static final Instant NOW = Instant.parse("2025-09-01T00:00:00Z");

    private final ImportEmployee alice = ImportEmployee.builder()
            .employeeId("E1").employeeName("Alice").email("a@x.com").location("HYD")
            .defaultShift("US Shift").weekOff("Sunday").teamId(1L).active(Boolean.TRUE).build();

    private final Department voice = Department.builder().id(1L).name("Voice").build();

    @BeforeEach
    void setUp() {
        when(appClock.now()).thenReturn(NOW);
        when(holidayRepository.findVisibleInRange(any(), any(), any(), any())).thenReturn(List.of());
        when(shiftAssignmentRepository.findByEmployeeIdIn(any())).thenReturn(List.of());
        service = new AttendanceRosterService(recordRepository, shiftAssignmentRepository,
                weekOffAssignmentRepository, importEmployeeRepository, employeeRepository, departmentRepository,
                statusRepository, holidayRepository, auditService, appClock);
    }

    private void stubStaff(List<ImportEmployee> employees) {
        when(importEmployeeRepository.findRosterEmployees(any(), any(), any(),
                any(), any(), any())).thenReturn(employees);
        when(importEmployeeRepository.findEmployeesForRosterWithExit(any(), any(), any(),
                any(), any(), any())).thenReturn(employees);
    }

    // ------------------------------------------------------------------ monthly

    @Test
    void monthlyBuildsGridWithDaysAndCells() {
        stubStaff(List.of(alice));

        AttendanceRecord r1 = new AttendanceRecord();
        r1.setEmployeeId("E1");
        r1.setAttendanceDate(LocalDate.of(2025, 9, 1));
        r1.setStatusCode("WFO");
        AttendanceRecord r2 = new AttendanceRecord();
        r2.setEmployeeId("E1");
        r2.setAttendanceDate(LocalDate.of(2025, 9, 6));
        r2.setStatusCode("WO");
        when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                any(), any(), anyList())).thenReturn(List.of(r1, r2));
        when(recordRepository.countByStatusCodes(any(), any(), any(), any()))
                .thenReturn(List.of(new Object[]{"WFO", 1L}, new Object[]{"WO", 1L}));
        when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any()))
                .thenReturn(2L);
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(voice));
        when(departmentRepository.findAllById(any())).thenReturn(List.of(voice));
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(
                Department.builder().id(1L).name("Voice").build()));

        AttendanceRosterDtos.MonthlyResponse res = service.monthly(1L, "2025-09", null, null, null, null, 0, 25);

        assertThat(res.month()).isEqualTo("2025-09");
        assertThat(res.days()).hasSize(30);
        assertThat(res.days().get(0).weekend()).isFalse();
        assertThat(res.days().stream().filter(d -> d.weekend()).count()).isEqualTo(8);
        assertThat(res.employees()).hasSize(1);
        AttendanceRosterDtos.EmployeeRow row = res.employees().get(0);
        assertThat(row.employeeId()).isEqualTo("E1");
        assertThat(row.teamName()).isEqualTo("Voice");
        assertThat(row.email()).isEqualTo("a@x.com");
        assertThat(row.days())
                .containsEntry("2025-09-01", "WFO")
                .containsEntry("2025-09-06", "WO");
        assertThat(res.counters().get("WFO")).isEqualTo(1L);
        assertThat(res.counters().get("WO")).isEqualTo(1L);
        assertThat(res.counters().get("PL")).isZero();
        assertThat(res.totalEmployees()).isEqualTo(2L);
        assertThat(res.matchedEmployees()).isEqualTo(1L);
    }

    @Test
    void monthlyFallsBackToMasterEmployeeEmailWhenImportEmailMissing() {
        ImportEmployee bob = ImportEmployee.builder()
                .employeeId("E2").employeeName("Bob").email(null).location("Pune").build();
        stubStaff(List.of(bob));
        when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                any(), any(), anyList())).thenReturn(List.of());
        when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any()))
                .thenReturn(1L);
        Employee master = Employee.builder()
                .employeeCode("E2").email("bob@hpe.com").build();
        when(employeeRepository.findByEmployeeCodeIn(any())).thenReturn(List.of(master));

        AttendanceRosterDtos.MonthlyResponse res = service.monthly(null, "2025-09", null, null, null, null, 0, 25);

        assertThat(res.employees()).hasSize(1);
        assertThat(res.employees().get(0).email()).isEqualTo("bob@hpe.com");
    }

    @Test
    void monthlyRejectsInvalidOrMissingMonth() {
        assertThatThrownBy(() -> service.monthly(null, "2025-13", null, null, null, null, 0, 25))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.monthly(null, null, null, null, null, null, 0, 25))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void monthlyKeepsAttritedEmployeeInMonthsBeforeTheirExit() {
        // Regression: an employee flagged inactive/EXITED by attrition must still appear in
        // every month up to and including their exit month, not vanish from past rosters.
        ImportEmployee leaver = ImportEmployee.builder()
                .employeeId("E9").employeeName("Leaver").location("Pune").active(Boolean.FALSE)
                .lastWorkingDate(LocalDate.of(2025, 9, 20))
                .exitDate(LocalDate.of(2025, 9, 30))
                .build();

        stubStaff(List.of(leaver));
        when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                any(), any(), anyList())).thenReturn(List.of());
        when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any())).thenReturn(1L);

        assertThat(service.monthly(null, "2025-05", null, null, null, null, 0, 25).employees())
                .extracting(AttendanceRosterDtos.EmployeeRow::employeeId)
                .containsExactly("E9");
        assertThat(service.monthly(null, "2025-09", null, null, null, null, 0, 25).employees())
                .extracting(AttendanceRosterDtos.EmployeeRow::employeeId)
                .containsExactly("E9");
    }

    @Test
    void monthlyHidesAttritedEmployeeFromMonthsAfterTheirExit() {
        ImportEmployee leaver = ImportEmployee.builder()
                .employeeId("E9").employeeName("Leaver").location("Pune").active(Boolean.FALSE)
                .lastWorkingDate(LocalDate.of(2025, 9, 20))
                .exitDate(LocalDate.of(2025, 9, 30))
                .build();

        stubStaff(List.of(leaver));
        when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                any(), any(), anyList())).thenReturn(List.of());
        when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any())).thenReturn(0L);

        assertThat(service.monthly(null, "2025-10", null, null, null, null, 0, 25).employees()).isEmpty();
        assertThat(service.monthly(null, "2026-01", null, null, null, null, 0, 25).employees()).isEmpty();
    }

    @Test
    void monthlyMarksHolidayColumns() {
        stubStaff(List.of(alice));
        when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                any(), any(), anyList())).thenReturn(List.of());
        when(importEmployeeRepository.findFilteredEmployeeIds(any(), any(), any()))
                .thenReturn(List.of());
        Holiday h = Holiday.builder().name("HPE Foundation Day")
                .holidayDate(LocalDate.of(2025, 9, 15)).build();
        when(holidayRepository.findVisibleInRange(any(), any(), any(), anyLong())).thenReturn(List.of(h));

        AttendanceRosterDtos.MonthlyResponse res = service.monthly(1L, "2025-09", null, null, null, null, 0, 25);

        AttendanceRosterDtos.DayInfo day = res.days().get(14);
        assertThat(day.holiday()).isTrue();
        assertThat(day.holidayName()).isEqualTo("HPE Foundation Day");
    }

    @Test
    void monthlySortsByShiftStartThenNameWithUnknownsLast() {
        ImportEmployee adam = ImportEmployee.builder().employeeId("E7")
                .employeeName("Adam").defaultShift("05:30-14:30").build();
        ImportEmployee cathy = ImportEmployee.builder().employeeId("E3")
                .employeeName("Cathy").defaultShift("05:30-14:30").build();
        ImportEmployee bob = ImportEmployee.builder().employeeId("E6")
                .employeeName("Bob").defaultShift("19:00-04:00").build();
        ImportEmployee dana = ImportEmployee.builder().employeeId("E4")
                .employeeName("Dana").defaultShift("21:00-06:00").build();
        ImportEmployee zoe = ImportEmployee.builder().employeeId("E5")
                .employeeName("Zoe").defaultShift(null).build();
        ImportEmployee troy = ImportEmployee.builder().employeeId("E8")
                .employeeName("Troy").defaultShift("Unknown Band").build();
        stubStaff(List.of(bob, zoe, cathy, troy, dana, adam));
        when(shiftAssignmentRepository.findByEmployeeIdIn(any())).thenReturn(List.of(
                assignment("E7", "2025-09", "05:30-14:30"),
                assignment("E3", "2025-09", "05:30-14:30"),
                assignment("E6", "2025-09", "19:00-04:00"),
                assignment("E4", "2025-09", "21:00-06:00"),
                assignment("E8", "2025-09", "Unknown Band")));

        when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                any(), any(), anyList())).thenReturn(List.of());
        when(importEmployeeRepository.findFilteredEmployeeIds(any(), any(), any()))
                .thenReturn(List.of());
        when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any()))
                .thenReturn(6L);

        AttendanceRosterDtos.MonthlyResponse res = service.monthly(null, "2025-09", null, null, null, null, 0, 25);

        assertThat(res.employees()).extracting(AttendanceRosterDtos.EmployeeRow::employeeId)
                .containsExactly("E7", "E3", "E6", "E4", "E8", "E5");
    }

    @Test
    void monthlyPaginatesAfterShiftSort() {
        List<ImportEmployee> staff = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            staff.add(ImportEmployee.builder().employeeId("N" + i)
                    .employeeName("Night" + i).defaultShift("21:00-06:00").build());
            staff.add(ImportEmployee.builder().employeeId("M" + i)
                    .employeeName("Morning" + i).defaultShift("05:30-14:30").build());
        }
        stubStaff(staff);

        when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                any(), any(), anyList())).thenReturn(List.of());
        when(importEmployeeRepository.findFilteredEmployeeIds(any(), any(), any()))
                .thenReturn(List.of());
        when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any()))
                .thenReturn(8L);

        // Shift grouping survives pagination: page 1 holds the tail of the
        // morning-shift group before any night-shift row appears.
        AttendanceRosterDtos.MonthlyResponse res = service.monthly(null, "2025-09", null, null, null, null, 1, 3);

        assertThat(res.employees()).extracting(AttendanceRosterDtos.EmployeeRow::employeeId)
                .containsExactly("M3", "N0", "N1");
        assertThat(res.page()).isEqualTo(1);
        assertThat(res.totalElements()).isEqualTo(8L);
        assertThat(res.totalPages()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ meta

    @Test
    void metaDerivesMonthsTeamsLocationsShiftsStatuses() {
        when(importEmployeeRepository.findDistinctTeamIds()).thenReturn(List.of(1L));
        Department voice = Department.builder().id(1L).name("Voice").build();
        when(departmentRepository.findAllById(anyList())).thenReturn(List.of(voice));
        when(recordRepository.findDistinctAttendanceDatesAsc()).thenReturn(List.of(
                LocalDate.of(2025, 1, 15), LocalDate.of(2025, 9, 20)));
        when(importEmployeeRepository.findDistinctLocationsInMonth(any(), any(), any()))
                .thenReturn(List.of("HYD"));
        when(shiftAssignmentRepository.findDistinctShiftValuesInPeriod(any(), any(), any(), any()))
                .thenReturn(List.of("UK Shift"));
        AttendanceStatus pl = AttendanceStatus.builder().code("PL").name("Privilege Leave").build();
        when(statusRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(pl));

        AttendanceRosterDtos.PageMeta meta = service.meta(1L, "2025-09");

        assertThat(meta.months()).containsExactly("2025-01", "2025-09");
        assertThat(meta.teams()).extracting(AttendanceRosterDtos.TeamOption::name).contains("Voice");
        assertThat(meta.locations()).containsExactly("HYD");
        assertThat(meta.shifts()).containsExactly("UK Shift");
        assertThat(meta.statuses()).extracting(AttendanceRosterDtos.StatusOption::code).contains("PL");
    }

    // ------------------------------------------------------------------ save

    @Test
    void saveUpsertsKnownCodesAndMarksUnknowns() {
        AttendanceRecord existing = new AttendanceRecord();
        existing.setEmployeeId("E1");
        existing.setAttendanceDate(LocalDate.of(2025, 9, 2));
        existing.setStatusCode("SL");
        when(importEmployeeRepository.existsById("E1")).thenReturn(true);
        when(recordRepository.findByEmployeeIdAndAttendanceDate("E1", LocalDate.of(2025, 9, 1)))
                .thenReturn(Optional.empty());
        when(recordRepository.findByEmployeeIdAndAttendanceDate("E1", LocalDate.of(2025, 9, 2)))
                .thenReturn(Optional.of(existing));
        when(recordRepository.findByEmployeeIdAndAttendanceDate("E1", LocalDate.of(2025, 9, 3)))
                .thenReturn(Optional.empty());

        AttendanceRosterDtos.BatchSaveResponse res = service.save(new AttendanceRosterDtos.BatchSaveRequest(List.of(
                new AttendanceRosterDtos.CellEdit("E1", LocalDate.of(2025, 9, 1), "week off"),
                new AttendanceRosterDtos.CellEdit("E1", LocalDate.of(2025, 9, 2), "PL"),
                new AttendanceRosterDtos.CellEdit("E1", LocalDate.of(2025, 9, 3), "SILVER DUTY"))));

        assertThat(res.saved()).isEqualTo(3);
        verify(recordRepository).save(argThat(r ->
                r.getAttendanceDate().equals(LocalDate.of(2025, 9, 1)) && "WO".equals(r.getStatusCode())));
        verify(recordRepository).save(argThat(r ->
                r.getAttendanceDate().equals(LocalDate.of(2025, 9, 2)) && "PL".equals(r.getStatusCode())));
        verify(recordRepository).save(argThat(r ->
                r.getAttendanceDate().equals(LocalDate.of(2025, 9, 3))
                        && "SILVER DUTY".equals(r.getStatusCode())
                        && Boolean.TRUE.equals(r.getIsUnknown())));
        verify(auditService).record(eq("ROSTER_CELLS_UPDATED"), eq("AttendanceRecord"), isNull(),
                isNull(), any());
    }

    @Test
    void saveCreatesMissingEmployeeSnapshot() {
        when(importEmployeeRepository.existsById("E9")).thenReturn(false);
        when(recordRepository.findByEmployeeIdAndAttendanceDate(eq("E9"), any()))
                .thenReturn(Optional.empty());

        AttendanceRosterDtos.BatchSaveResponse res = service.save(new AttendanceRosterDtos.BatchSaveRequest(List.of(
                new AttendanceRosterDtos.CellEdit("E9", LocalDate.of(2025, 9, 10), "TR"))));

        assertThat(res.saved()).isEqualTo(1);
        verify(importEmployeeRepository).save(argThat(e -> "E9".equals(e.getEmployeeId())));
    }

    @Test
    void saveRemovesRecordWhenCleared() {
        when(recordRepository.deleteByEmployeeIdAndAttendanceDate("E1", LocalDate.of(2025, 9, 5))).thenReturn(1);

        AttendanceRosterDtos.BatchSaveResponse res = service.save(new AttendanceRosterDtos.BatchSaveRequest(List.of(
                new AttendanceRosterDtos.CellEdit("E1", LocalDate.of(2025, 9, 5), " "))));

        assertThat(res.saved()).isEqualTo(1);
        verify(recordRepository).deleteByEmployeeIdAndAttendanceDate("E1", LocalDate.of(2025, 9, 5));
    }

    @Test
    void saveDeduplicatesSameEmployeeDate() {
        when(importEmployeeRepository.existsById("E1")).thenReturn(true);
        when(recordRepository.findByEmployeeIdAndAttendanceDate("E1", LocalDate.of(2025, 9, 1)))
                .thenReturn(Optional.empty());

        AttendanceRosterDtos.BatchSaveResponse res = service.save(new AttendanceRosterDtos.BatchSaveRequest(List.of(
                new AttendanceRosterDtos.CellEdit("E1", LocalDate.of(2025, 9, 1), "WFH"),
                new AttendanceRosterDtos.CellEdit("E1", LocalDate.of(2025, 9, 1), "PL"))));

        assertThat(res.saved()).isEqualTo(1);
        verify(recordRepository).save(argThat(r -> "PL".equals(r.getStatusCode())));
    }

    // ------------------------------------------------------------------ monthly shift

    /**
     * Shift rotates month to month, so the grid must show the shift rostered for
     * the selected month. Regression cover for the defect where every month
     * rendered the employee master's current shift.
     */
    @Nested
    class MonthlyShiftResolution {

        private static final String AM = "05:30-14:30";
        private static final String NIGHT = "19:00-04:00";
        private static final String PM = "13:30-22:30";

        /** Alice's current master shift; historical months must never fall back to it. */
        private final ImportEmployee rotating = ImportEmployee.builder()
                .employeeId("E1").employeeName("Alice").location("Pune")
                .defaultShift(PM).weekOff("Sun-Mon").active(Boolean.TRUE).build();

        private final ImportEmployee legacy = ImportEmployee.builder()
                .employeeId("E2").employeeName("Bob").location("Pune")
                .defaultShift(NIGHT).active(Boolean.TRUE).build();

        private void stub(List<ImportEmployee> staff, String month, AttendanceShiftAssignment... assignments) {
            stubStaff(staff);
            when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                    any(), any(), anyList())).thenReturn(List.of());
            when(shiftAssignmentRepository.findByEmployeeIdIn(any())).thenReturn(List.of(assignments));
            when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any()))
                    .thenReturn((long) staff.size());
        }

        @Test
        void septemberOctoberNovemberShowTheirOwnShift() {
            stub(List.of(rotating), "2026-09",
                    assignment("E1", "2026-09", AM),
                    assignment("E1", "2026-10", NIGHT),
                    assignment("E1", "2026-11", PM));

            assertThat(service.monthly(null, "2026-09", null, null, null, null, 0, 25)
                    .employees().get(0).shift()).isEqualTo(AM);
            assertThat(service.monthly(null, "2026-10", null, null, null, null, 0, 25)
                    .employees().get(0).shift()).isEqualTo(NIGHT);
            assertThat(service.monthly(null, "2026-11", null, null, null, null, 0, 25)
                    .employees().get(0).shift()).isEqualTo(PM);
        }

        @Test
        void historicalMonthDoesNotFallBackToTheCurrentMasterShift() {
            stub(List.of(rotating), "2026-09",
                    assignment("E1", "2026-09", AM),
                    assignment("E1", "2026-10", NIGHT),
                    assignment("E1", "2026-11", PM));

            assertThat(service.monthly(null, "2026-09", null, null, null, null, 0, 25)
                    .employees().get(0).shift())
                    .isEqualTo(AM)
                    .isNotEqualTo(rotating.getDefaultShift());
        }

        @Test
        void monthWithNoAssignmentIsLeftBlankRatherThanInherited() {
            stub(List.of(rotating), "2026-09", assignment("E1", "2026-09", AM));

            // December has no assignment of its own; October's shift must not leak in.
            assertThat(service.monthly(null, "2026-12", null, null, null, null, 0, 25)
                    .employees().get(0).shift()).isNull();
        }

        @Test
        void employeeWithNoPeriodDataAtAllIsLeftBlankRatherThanGivenTheMasterShift() {
            stub(List.of(legacy), "2026-09");

            // The source never rostered them for this month, so a shift must not
            // appear on the grid just because their master record carries one.
            assertThat(service.monthly(null, "2026-09", null, null, null, null, 0, 25)
                    .employees().get(0).shift()).isNull();
        }

        @Test
        void engineersWithDifferentShiftsInTheSameMonthAreIndependent() {
            ImportEmployee second = ImportEmployee.builder().employeeId("E2").employeeName("Bob")
                    .defaultShift(PM).active(Boolean.TRUE).build();
            stubStaff(List.of(rotating, second));
            when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                    any(), any(), anyList())).thenReturn(List.of());
            when(shiftAssignmentRepository.findByEmployeeIdIn(any())).thenReturn(List.of(
                    assignment("E1", "2026-09", AM),
                    assignment("E2", "2026-09", NIGHT)));
            when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any())).thenReturn(2L);

            assertThat(service.monthly(null, "2026-09", null, null, null, null, 0, 25).employees())
                    .extracting(AttendanceRosterDtos.EmployeeRow::shift)
                    .containsExactlyInAnyOrder(AM, NIGHT);
        }

        @Test
        void shiftFilterMatchesTheMonthsValueNotTheMasterValue() {
            stubStaff(List.of(rotating));
            when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                    any(), any(), anyList())).thenReturn(List.of());
            when(shiftAssignmentRepository.findByEmployeeIdIn(any())).thenReturn(List.of(
                    assignment("E1", "2026-09", AM), assignment("E1", "2026-10", NIGHT)));
            when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any())).thenReturn(1L);

            // Filtering September by the master PM shift must return nobody, even
            // though the employee's master value really is PM.
            assertThat(service.monthly(null, "2026-09", null, null, null, PM, 0, 25).employees()).isEmpty();
            assertThat(service.monthly(null, "2026-09", null, null, null, AM, 0, 25).employees()).hasSize(1);
        }

        @Test
        void shiftFilterToleratesTheWorkbooksLooseSpelling() {
            stubStaff(List.of(rotating));
            when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                    any(), any(), anyList())).thenReturn(List.of());
            when(shiftAssignmentRepository.findByEmployeeIdIn(any())).thenReturn(List.of(
                    assignment("E1", "2026-10", "19:00 - 04:00")));
            when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any())).thenReturn(1L);

            assertThat(service.monthly(null, "2026-10", null, null, null, NIGHT, 0, 25).employees())
                    .hasSize(1);
        }

        @Test
        void rowsAreOrderedByTheMonthsShift() {
            ImportEmployee late = ImportEmployee.builder().employeeId("E2").employeeName("Zoe")
                    .defaultShift(AM).active(Boolean.TRUE).build();
            stubStaff(List.of(rotating, late));
            when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                    any(), any(), anyList())).thenReturn(List.of());
            // Both masters say AM, but October rosters Zoe to the night shift, so
            // Zoe must sort after Alice.
            when(shiftAssignmentRepository.findByEmployeeIdIn(any())).thenReturn(List.of(
                    assignment("E1", "2026-10", AM),
                    assignment("E2", "2026-10", NIGHT)));
            when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any())).thenReturn(2L);

            assertThat(service.monthly(null, "2026-10", null, null, null, null, 0, 25).employees())
                    .extracting(AttendanceRosterDtos.EmployeeRow::employeeId)
                    .containsExactly("E1", "E2");
        }

        /**
         * The workbooks roster in five-week blocks, so a block routinely straddles
         * two months and an engineer can change shift part-way through the month
         * on screen. The Shift cell shows the dominant one and flags the change
         * rather than quietly presenting it as a single shift for the month.
         */
        @Test
        void midMonthShiftChangeShowsTheDominantShiftAndIsFlagged() {
            List<AttendanceRecord> days = new ArrayList<>();
            // Roster block rolled over on the 3rd: two days AM, the rest Night.
            days.add(record("E1", LocalDate.of(2026, 9, 1), AM));
            days.add(record("E1", LocalDate.of(2026, 9, 2), AM));
            for (int d = 3; d <= 30; d++) {
                days.add(record("E1", LocalDate.of(2026, 9, d), NIGHT));
            }
            stubStaff(List.of(rotating));
            when(shiftAssignmentRepository.findByEmployeeIdIn(any()))
                    .thenReturn(List.of(assignment("E1", "2026-09", NIGHT)));
            stubRecordedDays(days);
            when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any())).thenReturn(1L);

            AttendanceRosterDtos.EmployeeRow row = service
                    .monthly(null, "2026-09", null, null, null, null, 0, 25).employees().get(0);

            assertThat(row.shift()).isEqualTo(NIGHT);
            assertThat(row.shiftChangesWithinMonth()).isTrue();
        }

        @Test
        void stableMonthIsNotFlaggedAsChanging() {
            List<AttendanceRecord> days = new ArrayList<>();
            for (int d = 1; d <= 28; d++) {
                days.add(record("E1", LocalDate.of(2026, 9, d), AM));
            }
            stubStaff(List.of(rotating));
            when(shiftAssignmentRepository.findByEmployeeIdIn(any()))
                    .thenReturn(List.of(assignment("E1", "2026-09", AM)));
            stubRecordedDays(days);
            when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any())).thenReturn(1L);

            AttendanceRosterDtos.EmployeeRow row = service
                    .monthly(null, "2026-09", null, null, null, null, 0, 25).employees().get(0);

            assertThat(row.shiftChangesWithinMonth()).isFalse();
        }

        @Test
        void cosmeticSpellingsAreNotTreatedAsAChange() {
            List<AttendanceRecord> days = List.of(
                    record("E1", LocalDate.of(2026, 9, 1), "19:00 - 04:00"),
                    record("E1", LocalDate.of(2026, 9, 2), "19:00-04:00"));
            stubStaff(List.of(rotating));
            when(shiftAssignmentRepository.findByEmployeeIdIn(any()))
                    .thenReturn(List.of(assignment("E1", "2026-09", "19:00 - 04:00")));
            stubRecordedDays(days);
            when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any())).thenReturn(1L);

            AttendanceRosterDtos.EmployeeRow row = service
                    .monthly(null, "2026-09", null, null, null, null, 0, 25).employees().get(0);

            assertThat(row.shift()).isEqualTo("19:00 - 04:00");
            assertThat(row.shiftChangesWithinMonth()).isFalse();
        }

        private static AttendanceRecord record(String id, LocalDate date, String shift) {
            AttendanceRecord r = new AttendanceRecord();
            r.setEmployeeId(id);
            r.setAttendanceDate(date);
            r.setStatusCode("WFO");
            r.setShift(shift);
            return r;
        }

        private static AttendanceRecordRepository.EmployeeShiftView shiftView(String id, String shift) {
            return new AttendanceRecordRepository.EmployeeShiftView() {
                @Override
                public String getEmployeeId() {
                    return id;
                }

                @Override
                public String getShift() {
                    return shift;
                }
            };
        }

        /**
         * Feeds the same days through the cell query and the lightweight shift
         * projection the roster now sorts on, so both stay in step.
         */
        private void stubRecordedDays(List<AttendanceRecord> days) {
            when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                    any(), any(), anyList())).thenReturn(days);
            when(recordRepository.findShiftsInRange(any(), any(), any())).thenReturn(days.stream()
                    .map(d -> shiftView(d.getEmployeeId(), d.getShift()))
                    .toList());
        }

        @Test
        void blankNameAndBlankShiftStillSortInsteadOfFailing() {
            // Two real imported rows carry no name at all; the comparator used to
            // NPE on the null name and 500 the whole month.
            ImportEmployee noName = ImportEmployee.builder().employeeId("E9")
                    .employeeName(null).location("Pune").defaultShift(null).active(Boolean.TRUE).build();
            stubStaff(List.of(noName, rotating));
            when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                    any(), any(), anyList())).thenReturn(List.of());
            when(shiftAssignmentRepository.findByEmployeeIdIn(any())).thenReturn(List.of(
                    assignment("E1", "2026-09", AM)));
            when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any())).thenReturn(2L);

            assertThat(service.monthly(null, "2026-09", null, null, null, null, 0, 25).employees())
                    .extracting(AttendanceRosterDtos.EmployeeRow::employeeId)
                    .containsExactly("E1", "E9");
        }

        @Test
        void metaOffersOnlyTheShiftsRosteredInThatMonth() {
            when(importEmployeeRepository.findDistinctTeamIds()).thenReturn(List.of());
            when(recordRepository.findDistinctAttendanceDatesAsc())
                    .thenReturn(List.of(LocalDate.of(2026, 9, 1)));
            when(importEmployeeRepository.findDistinctLocationsInMonth(any(), any(), any()))
                    .thenReturn(List.of("Pune"));
            when(shiftAssignmentRepository.findDistinctShiftValuesInPeriod(any(), any(), any(), any()))
                    .thenReturn(List.of(AM, NIGHT));
            when(importEmployeeRepository.findDistinctShiftsInMonth(any(), any(), any()))
                    .thenReturn(List.of());
            when(statusRepository.findAllByOrderByCodeAsc()).thenReturn(List.of());

            assertThat(service.meta(null, "2026-09").shifts()).containsExactly(AM, NIGHT);
        }

        @Test
        void metaCollapsesCosmeticVariantsOfOneShift() {
            when(importEmployeeRepository.findDistinctTeamIds()).thenReturn(List.of());
            when(recordRepository.findDistinctAttendanceDatesAsc())
                    .thenReturn(List.of(LocalDate.of(2026, 9, 1)));
            when(importEmployeeRepository.findDistinctLocationsInMonth(any(), any(), any()))
                    .thenReturn(List.of());
            when(shiftAssignmentRepository.findDistinctShiftValuesInPeriod(any(), any(), any(), any()))
                    .thenReturn(List.of("19:00 - 04:00", "19:00-04:00"));
            when(importEmployeeRepository.findDistinctShiftsInMonth(any(), any(), any()))
                    .thenReturn(List.of());
            when(statusRepository.findAllByOrderByCodeAsc()).thenReturn(List.of());

            assertThat(service.meta(null, "2026-09").shifts()).hasSize(1);
        }
    }

    /**
     * Week off rotates month to month independently of shift, so the grid must show
     * the schedule rostered for the selected month. Regression cover for the defect
     * where every month rendered the employee master's first-imported week off.
     */
    @Nested
    class MonthlyWeekOffResolution {

        /** Alice's current master week off; historical months must never fall back to it. */
        private final ImportEmployee rotating = ImportEmployee.builder()
                .employeeId("E1").employeeName("Alice").location("Pune")
                .defaultShift("13:30-22:30").weekOff("Mon-Tues").active(Boolean.TRUE).build();

        private void stubWeekOff(AttendanceWeekOffAssignment... assignments) {
            stubStaff(List.of(rotating));
            when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                    any(), any(), anyList())).thenReturn(List.of());
            when(shiftAssignmentRepository.findByEmployeeIdIn(any())).thenReturn(List.of());
            when(weekOffAssignmentRepository.findByEmployeeIdIn(any())).thenReturn(List.of(assignments));
            when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any())).thenReturn(1L);
        }

        @Test
        void septemberOctoberNovemberShowTheirOwnWeekOff() {
            stubWeekOff(
                    weekOffAssignment("E1", "2026-09", "Sat-Sun"),
                    weekOffAssignment("E1", "2026-10", "Wed-Thurs"),
                    weekOffAssignment("E1", "2026-11", "Mon-Tues"));

            assertThat(service.monthly(null, "2026-09", null, null, null, null, 0, 25)
                    .employees().get(0).weekOff()).isEqualTo("Sat-Sun");
            assertThat(service.monthly(null, "2026-10", null, null, null, null, 0, 25)
                    .employees().get(0).weekOff()).isEqualTo("Wed-Thurs");
            assertThat(service.monthly(null, "2026-11", null, null, null, null, 0, 25)
                    .employees().get(0).weekOff()).isEqualTo("Mon-Tues");
        }

        @Test
        void historicalMonthDoesNotFallBackToTheCurrentMasterWeekOff() {
            stubWeekOff(weekOffAssignment("E1", "2026-09", "Sat-Sun"));

            assertThat(service.monthly(null, "2026-09", null, null, null, null, 0, 25)
                    .employees().get(0).weekOff())
                    .isEqualTo("Sat-Sun")
                    .isNotEqualTo(rotating.getWeekOff());
        }

        @Test
        void aMonthWithNoAssignmentDoesNotCarryThePreviousMonthForward() {
            stubWeekOff(weekOffAssignment("E1", "2026-09", "Sat-Sun"));

            // October was never rostered, so September must not leak into it.
            assertThat(service.monthly(null, "2026-10", null, null, null, null, 0, 25)
                    .employees().get(0).weekOff()).isNull();
        }

        @Test
        void navigatingBackAndForthKeepsEachMonthStable() {
            stubWeekOff(
                    weekOffAssignment("E1", "2026-09", "Sat-Sun"),
                    weekOffAssignment("E1", "2026-11", "Mon-Tues"));

            for (int i = 0; i < 3; i++) {
                assertThat(service.monthly(null, "2026-09", null, null, null, null, 0, 25)
                        .employees().get(0).weekOff()).isEqualTo("Sat-Sun");
                assertThat(service.monthly(null, "2026-11", null, null, null, null, 0, 25)
                        .employees().get(0).weekOff()).isEqualTo("Mon-Tues");
            }
        }

        @Test
        void employeesKeepIndependentWeekOffsInTheSameMonth() {
            ImportEmployee second = ImportEmployee.builder().employeeId("E2").employeeName("Bob")
                    .weekOff("Sun-Mon").active(Boolean.TRUE).build();
            stubStaff(List.of(rotating, second));
            when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                    any(), any(), anyList())).thenReturn(List.of());
            when(shiftAssignmentRepository.findByEmployeeIdIn(any())).thenReturn(List.of());
            when(weekOffAssignmentRepository.findByEmployeeIdIn(any())).thenReturn(List.of(
                    weekOffAssignment("E1", "2026-09", "Sat-Sun"),
                    weekOffAssignment("E2", "2026-09", "Wed-Thurs")));
            when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any())).thenReturn(2L);

            assertThat(service.monthly(null, "2026-09", null, null, null, null, 0, 25).employees())
                    .extracting(AttendanceRosterDtos.EmployeeRow::weekOff)
                    .containsExactlyInAnyOrder("Sat-Sun", "Wed-Thurs");
        }

        @Test
        void weekOffResolutionDoesNotDisturbTheMonthShift() {
            stubStaff(List.of(rotating));
            when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                    any(), any(), anyList())).thenReturn(List.of());
            when(shiftAssignmentRepository.findByEmployeeIdIn(any()))
                    .thenReturn(List.of(assignment("E1", "2026-09", "05:30-14:30")));
            when(weekOffAssignmentRepository.findByEmployeeIdIn(any()))
                    .thenReturn(List.of(weekOffAssignment("E1", "2026-09", "Sat-Sun")));
            when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any())).thenReturn(1L);

            AttendanceRosterDtos.EmployeeRow row = service
                    .monthly(null, "2026-09", null, null, null, null, 0, 25).employees().get(0);

            assertThat(row.shift()).isEqualTo("05:30-14:30");
            assertThat(row.weekOff()).isEqualTo("Sat-Sun");
        }
    }


}
