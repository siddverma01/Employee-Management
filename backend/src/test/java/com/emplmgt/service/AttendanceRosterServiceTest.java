package com.emplmgt.service;

import com.emplmgt.dto.AttendanceRosterDtos;
import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.AttendanceStatus;
import com.emplmgt.entity.Department;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.Holiday;
import com.emplmgt.entity.ImportEmployee;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.AttendanceStatusRepository;
import com.emplmgt.repository.DepartmentRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.HolidayRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import com.emplmgt.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
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

    @Mock AttendanceRecordRepository recordRepository;
    @Mock ImportEmployeeRepository importEmployeeRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock DepartmentRepository departmentRepository;
    @Mock AttendanceStatusRepository statusRepository;
    @Mock HolidayRepository holidayRepository;
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
        service = new AttendanceRosterService(recordRepository, importEmployeeRepository,
                employeeRepository, departmentRepository, statusRepository, holidayRepository, auditService, appClock);
    }

    private void stubStaff(List<ImportEmployee> employees) {
        when(importEmployeeRepository.findRosterEmployees(any(), any(), any(), any(),
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
        when(importEmployeeRepository.findFilteredEmployeeIds(any(), any(), any(), any()))
                .thenReturn(List.of("E1"));
        when(recordRepository.countByStatusCodes(any(), any(), any(), any()))
                .thenReturn(List.of(new Object[]{"WFO", 1L}, new Object[]{"WO", 1L}));
        when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any(), any()))
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
        assertThat(res.matchedEmployees()).isEqualTo(2L);
    }

    @Test
    void monthlyFallsBackToMasterEmployeeEmailWhenImportEmailMissing() {
        ImportEmployee bob = ImportEmployee.builder()
                .employeeId("E2").employeeName("Bob").email(null).location("Pune").build();
        stubStaff(List.of(bob));
        when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                any(), any(), anyList())).thenReturn(List.of());
        when(importEmployeeRepository.findFilteredEmployeeIds(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any(), any()))
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
    void monthlyMarksHolidayColumns() {
        stubStaff(List.of(alice));
        when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                any(), any(), anyList())).thenReturn(List.of());
        when(importEmployeeRepository.findFilteredEmployeeIds(any(), any(), any(), any()))
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

        when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                any(), any(), anyList())).thenReturn(List.of());
        when(importEmployeeRepository.findFilteredEmployeeIds(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any(), any()))
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
        when(importEmployeeRepository.findFilteredEmployeeIds(any(), any(), any(), any()))
                .thenReturn(List.of());
        when(importEmployeeRepository.countEmployeeRows(any(), any(), any(), any(), any(), any()))
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
        when(importEmployeeRepository.findDistinctShiftsInMonth(any(), any(), any()))
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
}