package com.emplmgt.service;

import com.emplmgt.dto.EmployeeHistoricalAttendanceDtos;
import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.ImportEmployee;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmployeeHistoricalAttendanceServiceTest {

    @Mock EmployeeRepository employeeRepository;
    @Mock ImportEmployeeRepository importEmployeeRepository;
    @Mock AttendanceRecordRepository recordRepository;

    EmployeeHistoricalAttendanceService service;

    @BeforeEach
    void setUp() {
        service = new EmployeeHistoricalAttendanceService(employeeRepository, importEmployeeRepository, recordRepository);
    }

    private static List<Object[]> grouped() {
        return List.of(
                new Object[]{LocalDate.of(2025, 1, 6), "WFO", 1L},
                new Object[]{LocalDate.of(2025, 1, 7), "WFH", 1L},
                new Object[]{LocalDate.of(2025, 1, 12), "WO", 1L},
                new Object[]{LocalDate.of(2025, 2, 3), "SL", 1L},
                new Object[]{LocalDate.of(2025, 2, 10), "STRIKE", 1L});
    }

    @Test
    void overviewComputesDateJoinedTotalDaysAndRollsOthers() {
        EmployeeHistoricalAttendanceDtos.Overview o = EmployeeHistoricalAttendanceService.overview(grouped());

        assertThat(o.dateJoined()).isEqualTo("2025-01-06");
        assertThat(o.totalDays()).isEqualTo(5L);
        assertThat(o.byStatus())
                .containsEntry("WFO", 1L)
                .containsEntry("WFH", 1L)
                .containsEntry("WO", 1L)
                .containsEntry("SL", 1L)
                .containsEntry("HPEH", 0L)
                .containsEntry("FL", 0L)
                .containsEntry("HD", 0L)
                .containsEntry(EmployeeHistoricalAttendanceService.OTHER, 1L);
    }

    @Test
    void monthsAndMonthlyBreakdownGroupedPerMonth() {
        List<String> months = EmployeeHistoricalAttendanceService.months(grouped());
        List<EmployeeHistoricalAttendanceDtos.MonthStat> monthly =
                EmployeeHistoricalAttendanceService.monthly(grouped(), months);

        assertThat(months).containsExactly("2025-01", "2025-02");
        assertThat(monthly).hasSize(2);
        EmployeeHistoricalAttendanceDtos.MonthStat jan = monthly.get(0);
        assertThat(jan.month()).isEqualTo("2025-01");
        assertThat(jan.counts())
                .containsEntry("WFO", 1L)
                .containsEntry("WFH", 1L)
                .containsEntry("WO", 1L)
                .containsEntry("PL", 0L)
                .containsEntry("SL", 0L)
                .containsEntry("CO", 0L)
                .containsEntry(EmployeeHistoricalAttendanceService.OTHER, 0L);
        assertThat(monthly.get(1).counts().get(EmployeeHistoricalAttendanceService.OTHER)).isEqualTo(1L);
    }

    @Test
    void withOtherFillsZeroesAndNeverGoesNegative() {
        Map<String, Long> counted = EmployeeHistoricalAttendanceService.withOther(
                Map.of("WFO", 3L, "SOMETHING", 9L), 12L, EmployeeHistoricalAttendanceService.MONTH_CODES);

        assertThat(counted).containsEntry("WFO", 3L).containsEntry("WFH", 0L);
        assertThat(counted).containsEntry(EmployeeHistoricalAttendanceService.OTHER, 9L);
    }

    @Test
    void profileReturnsNotFoundWhenNoRecords() {
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(
                Employee.builder().id(1L).employeeCode("E1").fullName("Alice").build()));
        when(recordRepository.countByDateAndStatusCode("E1")).thenReturn(List.of());

        EmployeeHistoricalAttendanceDtos.ProfileResponse res = service.profile(1L, null);

        assertThat(res.found()).isFalse();
        assertThat(res.employeeId()).isEqualTo("E1");
        assertThat(res.overview()).isNull();
        assertThat(res.months()).isEmpty();
    }

    @Test
    void profileBuildsOverviewMonthlyAndCalendarForLatestMonthByDefault() {
        Employee em = Employee.builder().id(1L).employeeCode("E1").fullName("Alice").build();
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(em));
        when(recordRepository.countByDateAndStatusCode("E1")).thenReturn(grouped());
        when(importEmployeeRepository.findById("E1"))
                .thenReturn(Optional.of(ImportEmployee.builder().employeeId("E1").employeeName("Alice I").build()));

        AttendanceRecord r1 = new AttendanceRecord();
        r1.setEmployeeId("E1");
        r1.setAttendanceDate(LocalDate.of(2025, 2, 3));
        r1.setStatusCode("SL");
        when(recordRepository.findByEmployeeIdAndAttendanceDateBetweenOrderByAttendanceDateAsc(
                anyString(), any(), any())).thenReturn(List.of(r1));

        EmployeeHistoricalAttendanceDtos.ProfileResponse res = service.profile(1L, null);

        assertThat(res.found()).isTrue();
        assertThat(res.employeeName()).isEqualTo("Alice I");
        assertThat(res.overview().totalDays()).isEqualTo(5L);
        assertThat(res.months()).containsExactly("2025-01", "2025-02");
        assertThat(res.calendar().month()).isEqualTo("2025-02");
        assertThat(res.calendar().days()).hasSize(28);
        assertThat(res.calendar().days().get(2).date()).isEqualTo("2025-02-03");
        assertThat(res.calendar().days().get(2).statusCode()).isEqualTo("SL");
        assertThat(res.calendar().days().get(0).statusCode()).isNull();
        assertThat(res.calendar().days().stream().filter(d -> d.weekend()).count()).isEqualTo(8);
    }

    @Test
    void profileHonoursRequestedMonthAndRejectsInvalidInput() {
        when(employeeRepository.findById(1L)).thenReturn(Optional.of(
                Employee.builder().id(1L).employeeCode("E1").fullName("Alice").build()));
        when(recordRepository.countByDateAndStatusCode("E1")).thenReturn(grouped());

        EmployeeHistoricalAttendanceDtos.ProfileResponse res = service.profile(1L, "2025-01");
        assertThat(res.calendar().month()).isEqualTo("2025-01");
        assertThat(res.calendar().days()).hasSize(31);

        assertThatThrownBy(() -> service.profile(1L, "2025-13"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.profile(99L, null))
                .isInstanceOf(ApiException.class);
    }
}