package com.emplmgt.service;

import com.emplmgt.dto.CalendarDtos;
import com.emplmgt.entity.ApplicableLocation;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.Event;
import com.emplmgt.entity.Holiday;
import com.emplmgt.entity.HolidayType;
import com.emplmgt.entity.ScopeType;
import com.emplmgt.repository.AttendanceRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.EventRepository;
import com.emplmgt.repository.HolidayRepository;
import com.emplmgt.repository.LeaveRequestRepository;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.service.TeamAccessService.AccessMode;
import com.emplmgt.service.TeamAccessService.ResolvedTeam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalendarServiceTest {

    @Mock HolidayRepository holidayRepository;
    @Mock EventRepository eventRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock LeaveRequestRepository leaveRequestRepository;
    @Mock AttendanceRepository attendanceRepository;
    @Mock TeamAccessService teamAccessService;
    @Mock SecurityUtils securityUtils;

    private CalendarService service;

    private static final LocalDate DATE = LocalDate.of(2026, 9, 14);

    @BeforeEach
    void setUp() {
        service = new CalendarService(holidayRepository, eventRepository, employeeRepository,
                leaveRequestRepository, attendanceRepository, teamAccessService, securityUtils);
    }

    private Holiday holiday(long id, String name, LocalDate date, HolidayType type,
                            ApplicableLocation location, boolean active) {
        return Holiday.builder()
                .id(id)
                .name(name)
                .holidayDate(date)
                .country("IN")
                .holidayType(type)
                .applicableLocations(location)
                .active(active)
                .scope(ScopeType.GLOBAL)
                .build();
    }

    private Employee employee(String location) {
        return Employee.builder()
                .id(10L)
                .employeeCode("EMP-001")
                .fullName("Test Employee")
                .location(location)
                .build();
    }

    /** Caller is an employee with the given location (userId 7). */
    private void callerIs(Employee employee) {
        when(securityUtils.currentUserId()).thenReturn(7L);
        when(employeeRepository.findByUserId(7L)).thenReturn(Optional.of(employee));
    }

    private void callerIsAdminWithoutProfile() {
        when(securityUtils.currentUserId()).thenReturn(null);
    }

    private List<CalendarDtos.CalendarEvent> month(List<Holiday> holidays) {
        YearMonth ym = YearMonth.of(2026, 9);
        when(teamAccessService.resolve(null)).thenReturn(new ResolvedTeam(null, AccessMode.BASIC));
        when(holidayRepository.findVisibleInRange(ym.atDay(1), ym.atEndOfMonth(), null, null))
                .thenReturn(holidays);
        when(eventRepository.findVisibleInRange(ym.atDay(1), ym.atEndOfMonth(), null))
                .thenReturn(List.of());
        return service.month(2026, 9, null);
    }

    private List<String> titles(List<CalendarDtos.CalendarEvent> events) {
        return events.stream().map(CalendarDtos.CalendarEvent::title).collect(Collectors.toList());
    }

    @Nested
    class PuneEmployee {

        @Test
        void seesAllLocationAndPuneMumbaiHpeHolidays() {
            callerIs(employee("Pune"));

            List<CalendarDtos.CalendarEvent> events = month(List.of(
                    holiday(1L, "New Year's Day", LocalDate.of(2026, 1, 1), HolidayType.HPE_HOLIDAY,
                            ApplicableLocation.ALL, true),
                    holiday(2L, "Gudi Padwa / Ugadi", LocalDate.of(2026, 3, 19), HolidayType.HPE_HOLIDAY,
                            ApplicableLocation.PUNE_MUMBAI, true),
                    holiday(3L, "Regional Day", LocalDate.of(2026, 4, 1), HolidayType.HPE_HOLIDAY,
                            ApplicableLocation.BANGALORE, true)));

            assertThat(titles(events))
                    .containsExactly("New Year's Day", "Gudi Padwa / Ugadi");
        }

        @Test
        void seesEveryExpectedPuneHpeHolidayOf2026() {
            callerIs(employee("RTCC, Pune"));

            List<CalendarDtos.CalendarEvent> events = month(List.of(
                    holiday(1L, "New Year's Day", DATE, HolidayType.HPE_HOLIDAY, ApplicableLocation.ALL, true),
                    holiday(2L, "Ganesh Chaturthi", DATE, HolidayType.HPE_HOLIDAY, ApplicableLocation.PUNE_MUMBAI, true),
                    holiday(3L, "Bhai Duj", DATE, HolidayType.HPE_HOLIDAY, ApplicableLocation.PUNE_MUMBAI, true),
                    holiday(4L, "Christmas", DATE, HolidayType.HPE_HOLIDAY, ApplicableLocation.ALL, true)));

            assertThat(titles(events))
                    .containsExactly("New Year's Day", "Ganesh Chaturthi", "Bhai Duj", "Christmas");
        }
    }

    @Nested
    class OtherLocations {

        @Test
        void bangaloreEmployeeSeesAllLocationHolidaysOnly() {
            callerIs(employee("Bangalore"));

            List<CalendarDtos.CalendarEvent> events = month(List.of(
                    holiday(1L, "Gandhi Jayanti", DATE, HolidayType.HPE_HOLIDAY, ApplicableLocation.ALL, true),
                    holiday(2L, "Ganesh Chaturthi", DATE, HolidayType.HPE_HOLIDAY, ApplicableLocation.PUNE_MUMBAI, true)));

            assertThat(titles(events)).containsExactly("Gandhi Jayanti");
        }

        @Test
        void regionalHolidayOfAnotherLocationIsNeverShown() {
            callerIs(employee("Chennai"));

            List<CalendarDtos.CalendarEvent> events = month(List.of(
                    holiday(1L, "Gudi Padwa / Ugadi", DATE, HolidayType.HPE_HOLIDAY, ApplicableLocation.PUNE_MUMBAI, true)));

            assertThat(events).isEmpty();
        }
    }

    @Nested
    class ActiveAndLocationRules {

        @Test
        void inactiveHpeHolidayIsHidden() {
            callerIs(employee("Pune"));

            List<CalendarDtos.CalendarEvent> events = month(List.of(
                    holiday(1L, "Withdrawn Holiday", DATE, HolidayType.HPE_HOLIDAY, ApplicableLocation.ALL, false),
                    holiday(2L, "Ganesh Chaturthi", DATE, HolidayType.HPE_HOLIDAY, ApplicableLocation.PUNE_MUMBAI, true)));

            assertThat(titles(events)).containsExactly("Ganesh Chaturthi");
        }

        @Test
        void callerWithoutAProfileSeesEveryHoliday() {
            callerIsAdminWithoutProfile();

            List<CalendarDtos.CalendarEvent> events = month(List.of(
                    holiday(1L, "New Year's Day", DATE, HolidayType.HPE_HOLIDAY, ApplicableLocation.ALL, true),
                    holiday(2L, "Regional Day", DATE, HolidayType.HPE_HOLIDAY, ApplicableLocation.BANGALORE, true)));

            assertThat(titles(events)).containsExactly("New Year's Day", "Regional Day");
        }

        @Test
        void existingPublicHolidayIsUnchanged() {
            callerIs(employee("Pune"));

            List<CalendarDtos.CalendarEvent> events = month(List.of(
                    holiday(1L, "Company Day", LocalDate.of(2026, 9, 14), HolidayType.PUBLIC,
                            ApplicableLocation.ALL, true)));

            assertThat(titles(events)).containsExactly("Company Day");
            assertThat(events.get(0).kind()).isEqualTo(CalendarDtos.EventKind.HOLIDAY.name());
            assertThat(events.get(0).extra()).containsEntry("holidayType", "PUBLIC");
        }

        @Test
        void regionalFilterAlsoAppliesToRegularHolidaysOnceALocationIsKnown() {
            callerIs(employee("Pune"));

            List<CalendarDtos.CalendarEvent> events = month(List.of(
                    holiday(1L, "Regional Public Day", DATE, HolidayType.PUBLIC, ApplicableLocation.BANGALORE, true),
                    holiday(2L, "Shared Public Day", DATE, HolidayType.PUBLIC, ApplicableLocation.PUNE_MUMBAI, true)));

            assertThat(titles(events)).containsExactly("Shared Public Day");
        }
    }

    @Nested
    class EventPayload {

        @Test
        void carriesTheHolidayTypeTheFrontendNeedsToBadgeIt() {
            callerIs(employee("Pune"));

            List<CalendarDtos.CalendarEvent> events = month(List.of(
                    holiday(1L, "Ganesh Chaturthi", DATE, HolidayType.HPE_HOLIDAY, ApplicableLocation.PUNE_MUMBAI, true)));

            CalendarDtos.CalendarEvent event = events.get(0);
            assertThat(event.kind()).isEqualTo(CalendarDtos.EventKind.HOLIDAY.name());
            assertThat(event.subtitle()).isEqualTo("HPE_HOLIDAY");
            assertThat(event.extra())
                    .containsEntry("holidayType", "HPE_HOLIDAY")
                    .containsEntry("scope", "GLOBAL")
                    .containsEntry("country", "IN");
        }

        @Test
        void neverTurnsEarnedEntitlementsIntoCalendarEvents() {
            callerIs(employee("Pune"));

            List<CalendarDtos.CalendarEvent> events = month(List.of(
                    holiday(1L, "Ganesh Chaturthi", DATE, HolidayType.HPE_HOLIDAY, ApplicableLocation.PUNE_MUMBAI, true)));

            assertThat(events).hasSize(1);
            assertThat(events.get(0).employeeId()).isNull();
            assertThat(events.get(0).leaveType()).isNull();
            // The service only depends on holiday/event/leave/attendance repositories - no entitlement source.
            assertThat(java.util.Arrays.stream(CalendarService.class.getDeclaredFields())
                    .map(java.lang.reflect.Field::getName)
                    .toList())
                    .doesNotContain("entitlementRepository", "hpeEntitlementService");
        }

        @Test
        void eventsAreSortedByDate() {
            callerIs(employee("Pune"));
            Holiday later = holiday(2L, "Later", LocalDate.of(2026, 9, 20), HolidayType.HPE_HOLIDAY,
                    ApplicableLocation.ALL, true);
            Holiday earlier = holiday(1L, "Earlier", LocalDate.of(2026, 9, 1), HolidayType.HPE_HOLIDAY,
                    ApplicableLocation.ALL, true);

            assertThat(titles(month(List.of(later, earlier)))).containsExactly("Earlier", "Later");
        }
    }

    @Nested
    class OtherSources {

        @Test
        void companyEventsAreStillReturned() {
            callerIs(employee("Pune"));
            Event event = Event.builder()
                    .id(5L)
                    .title("Town Hall")
                    .eventDate(DATE)
                    .scope(ScopeType.GLOBAL)
                    .build();
            YearMonth ym = YearMonth.of(2026, 9);
            when(teamAccessService.resolve(null)).thenReturn(new ResolvedTeam(null, AccessMode.BASIC));
            when(holidayRepository.findVisibleInRange(ym.atDay(1), ym.atEndOfMonth(), null, null))
                    .thenReturn(List.of());
            when(eventRepository.findVisibleInRange(ym.atDay(1), ym.atEndOfMonth(), null))
                    .thenReturn(List.of(event));

            List<CalendarDtos.CalendarEvent> events = service.month(2026, 9, null);

            assertThat(titles(events)).containsExactly("Town Hall");
            assertThat(events.get(0).kind()).isEqualTo(CalendarDtos.EventKind.EVENT.name());
        }
    }
}
