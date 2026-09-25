package com.emplmgt.service;

import com.emplmgt.dto.AttendanceHistoryDtos;
import com.emplmgt.dto.AttendanceRosterDtos;
import com.emplmgt.entity.AttendanceStatus;
import com.emplmgt.entity.Department;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.AttendanceStatusRepository;
import com.emplmgt.repository.DepartmentRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceHistoryServiceTest {

    @Mock EntityManager entityManager;
    @Mock AttendanceRecordRepository recordRepository;
    @Mock ImportEmployeeRepository importEmployeeRepository;
    @Mock DepartmentRepository departmentRepository;
    @Mock AttendanceStatusRepository statusRepository;

    AttendanceHistoryService service;

    @BeforeEach
    void setUp() {
        service = new AttendanceHistoryService(entityManager, importEmployeeRepository,
                departmentRepository, statusRepository, recordRepository);
    }

    private static AttendanceHistoryDtos.HistoryQuery query(
            String dateFrom, String dateTo, String month, String year, String status,
            int page, int size, String sortBy, String sortDir) {
        return new AttendanceHistoryDtos.HistoryQuery(dateFrom, dateTo, month, year,
                null, null, null, null, null, status, null, page, size, sortBy, sortDir);
    }

    // ------------------------------------------------------------------ resolve

    @Test
    void resolveIntersectsDateRangeMonthAndYear() {
        AttendanceHistoryService.Resolved r = AttendanceHistoryService.resolve(query(
                "2025-06-01", "2025-12-31", "2025-09", "2025", null, 0, 25, null, null));

        assertThat(r.from()).isEqualTo(LocalDate.of(2025, 9, 1));
        assertThat(r.to()).isEqualTo(LocalDate.of(2025, 9, 30));
    }

    @Test
    void resolveKeepsBoundsInsideAProvidedMonth() {
        AttendanceHistoryService.Resolved r = AttendanceHistoryService.resolve(query(
                "2025-09-05", "2025-09-25", "2025-09", null, null, 0, 25, null, null));

        assertThat(r.from()).isEqualTo(LocalDate.of(2025, 9, 5));
        assertThat(r.to()).isEqualTo(LocalDate.of(2025, 9, 25));
    }

    @Test
    void resolveRejectsInvalidMonthDateOrEmptyRange() {
        assertThatThrownBy(() -> AttendanceHistoryService.resolve(query(null, null, "2025-13", null, null, 0, 25, null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AttendanceHistoryService.resolve(query("2025-09-xx", null, null, null, null, 0, 25, null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AttendanceHistoryService.resolve(query("2025-09-20", "2025-09-10", null, null, null, 0, 25, null, null)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void resolveClampsPageAndSize() {
        AttendanceHistoryService.Resolved r = AttendanceHistoryService.resolve(query(
                null, null, null, null, null, -3, 5000, null, null));

        assertThat(r.page()).isZero();
        assertThat(r.size()).isEqualTo(AttendanceHistoryService.MAX_PAGE_SIZE);
    }

    // ------------------------------------------------------------------ query building

    @Test
    void buildPartsAddsConditionAndParamPerActiveFilter() {
        AttendanceHistoryService.Resolved r = new AttendanceHistoryService.Resolved(
                LocalDate.of(2025, 9, 1), LocalDate.of(2025, 9, 30), "Alice", "E1",
                1L, "HYD", "UK Shift", "WFO", "attendance", 0, 25, "employeeName", "asc");

        AttendanceHistoryService.Parts p = AttendanceHistoryService.buildParts(r);

        assertThat(p.where())
                .contains("r.attendanceDate >= :attendanceFrom")
                .contains("lower(e.employeeName) like :employeeNamePat")
                .contains("lower(r.employeeId) like :employeeIdPat")
                .contains("e.teamId = :teamId")
                .contains("coalesce(r.location, e.location) = :location")
                .contains("coalesce(r.shift, e.defaultShift) = :shift")
                .contains("r.statusCode = :status")
                .contains(":qPat");
        assertThat(p.params().get("attendanceFrom")).isEqualTo(LocalDate.of(2025, 9, 1));
        assertThat(p.params().get("employeeNamePat")).isEqualTo("%alice%");
        assertThat(p.params().get("teamId")).isEqualTo(1L);
    }

    @Test
    void buildPartsIsEmptyWithoutFilters() {
        AttendanceHistoryService.Resolved r = new AttendanceHistoryService.Resolved(
                null, null, null, null, null, null, null, null, null, 0, 25, null, null);

        AttendanceHistoryService.Parts p = AttendanceHistoryService.buildParts(r);

        assertThat(p.where()).isEmpty();
        assertThat(p.params()).isEmpty();
    }

    @Test
    void orderByFallsBackToDateForUnknownSortAndHonoursDirection() {
        AttendanceHistoryService.Resolved r = new AttendanceHistoryService.Resolved(
                null, null, null, null, null, null, null, null, null, 0, 25, "bogus", "DESC");

        assertThat(AttendanceHistoryService.orderBy(r))
                .contains("order by r.attendanceDate desc");

        AttendanceHistoryService.Resolved team = new AttendanceHistoryService.Resolved(
                null, null, null, null, null, null, null, null, null, 0, 25, "teamName", "asc");
        assertThat(AttendanceHistoryService.orderBy(team))
                .contains("order by d.name asc");
    }

    @Test
    void toSummarySortsByCountThenCode() {
        AttendanceHistoryDtos.Summary s = AttendanceHistoryService.toSummary(10L,
                List.of(new Object[]{"SL", 1L}, new Object[]{"WFO", 8L}, new Object[]{"WO", 1L}));

        assertThat(s.total()).isEqualTo(10L);
        assertThat(s.byStatus().keySet()).containsExactly("WFO", "SL", "WO");
        assertThat(s.byStatus().get("WFO")).isEqualTo(8L);
    }

    @Test
    void csvEscapesCommasQuotesAndNewlines() {
        assertThat(AttendanceHistoryService.csv("plain")).isEqualTo("plain");
        assertThat(AttendanceHistoryService.csv("HYD, India")).isEqualTo("\"HYD, India\"");
        assertThat(AttendanceHistoryService.csv("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(AttendanceHistoryService.csv(null)).isEmpty();
    }

    // ------------------------------------------------------------------ search

    @Test
    void searchMapsRowsAndBuildsSummaryFromMockedQueries() {
        TypedQuery<Object[]> rowsQuery = mock(TypedQuery.class);
        TypedQuery<Object[]> summaryQuery = mock(TypedQuery.class);
        TypedQuery<Long> countQuery = mock(TypedQuery.class);

        when(entityManager.createQuery(argThat(s -> s != null && s.startsWith("select r.attendanceDate")),
                eq(Object[].class))).thenReturn(rowsQuery);
        when(entityManager.createQuery(argThat(s -> s != null && s.startsWith("select r.statusCode")),
                eq(Object[].class))).thenReturn(summaryQuery);
        when(entityManager.createQuery(argThat(s -> s != null && s.startsWith("select count(")),
                eq(Long.class))).thenReturn(countQuery);

        when(rowsQuery.getResultList()).thenReturn(List.<Object[]>of(new Object[]{
                LocalDate.of(2025, 9, 1), "E1", "Alice", 1L, "Voice", "HYD", "UK Shift",
                "WFO", "Work From Office", "Sept 2025", "mar-2025.xlsx", 42,
                Instant.parse("2025-09-01T00:00:00Z"), false}));
        when(summaryQuery.getResultList()).thenReturn(
                List.of(new Object[]{"WFO", 8L}, new Object[]{"SL", 2L}));
        when(countQuery.getSingleResult()).thenReturn(10L);

        AttendanceHistoryDtos.SearchResponse res = service.search(
                query(null, null, "2025-09", null, null, 0, 25, "date", "asc"));

        assertThat(res.records()).hasSize(1);
        AttendanceHistoryDtos.RecordView v = res.records().get(0);
        assertThat(v.date()).isEqualTo("2025-09-01");
        assertThat(v.employeeId()).isEqualTo("E1");
        assertThat(v.employeeName()).isEqualTo("Alice");
        assertThat(v.teamName()).isEqualTo("Voice");
        assertThat(v.location()).isEqualTo("HYD");
        assertThat(v.shift()).isEqualTo("UK Shift");
        assertThat(v.statusCode()).isEqualTo("WFO");
        assertThat(v.statusName()).isEqualTo("Work From Office");
        assertThat(v.sourceMonth()).isEqualTo("2025-09");
        assertThat(v.sourceSheet()).isEqualTo("Sept 2025");
        assertThat(v.sourceFile()).isEqualTo("mar-2025.xlsx");
        assertThat(v.sourceRow()).isEqualTo(42);
        assertThat(v.unknown()).isFalse();

        assertThat(res.summary().total()).isEqualTo(10L);
        assertThat(res.summary().byStatus()).containsEntry("WFO", 8L).containsEntry("SL", 2L);
        assertThat(res.totalElements()).isEqualTo(10L);
        assertThat(res.size()).isEqualTo(25);
        verify(rowsQuery).setFirstResult(0);
        verify(rowsQuery).setMaxResults(25);
    }

    // ------------------------------------------------------------------ meta

    @Test
    void metaMergesMonthsYearsLocationsShiftsTeamsStatuses() {
        when(importEmployeeRepository.findDistinctTeamIds()).thenReturn(List.of(1L));
        when(departmentRepository.findAllById(anyList())).thenReturn(
                List.of(Department.builder().id(1L).name("Voice").build()));
        when(recordRepository.findDistinctAttendanceDatesAsc()).thenReturn(List.of(
                LocalDate.of(2025, 1, 15), LocalDate.of(2025, 9, 20)));
        when(recordRepository.findDistinctLocations()).thenReturn(List.of("Mumbai"));
        when(importEmployeeRepository.findDistinctLocations()).thenReturn(List.of("hyderabad", "Mumbai"));
        when(recordRepository.findDistinctShifts()).thenReturn(List.of("US Shift"));
        when(importEmployeeRepository.findDistinctShifts()).thenReturn(List.of("UK Shift"));
        when(statusRepository.findAllByOrderByCodeAsc()).thenReturn(
                List.of(AttendanceStatus.builder().code("PL").name("Privilege Leave").build()));

        AttendanceHistoryDtos.Meta meta = service.meta();

        assertThat(meta.months()).containsExactly("2025-01", "2025-09");
        assertThat(meta.years()).containsExactly("2025");
        assertThat(meta.locations()).containsExactly("hyderabad", "Mumbai");
        assertThat(meta.shifts()).containsExactly("UK Shift", "US Shift");
        assertThat(meta.teams()).extracting(AttendanceRosterDtos.TeamOption::name).contains("Voice");
        assertThat(meta.statuses()).extracting(AttendanceRosterDtos.StatusOption::code).contains("PL");
    }
}