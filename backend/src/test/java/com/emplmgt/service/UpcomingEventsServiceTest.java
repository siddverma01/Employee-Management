package com.emplmgt.service;

import com.emplmgt.dto.CalendarDtos;
import com.emplmgt.dto.CalendarDtos.UpcomingEventCategory;
import com.emplmgt.dto.CalendarDtos.UpcomingEventSource;
import com.emplmgt.dto.EmployeeDtos;
import com.emplmgt.dto.HolidayDtos;
import com.emplmgt.entity.Department;
import com.emplmgt.entity.ApplicableLocation;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.EmploymentStatus;
import com.emplmgt.entity.Event;
import com.emplmgt.entity.EventType;
import com.emplmgt.entity.HolidayType;
import com.emplmgt.entity.ScopeType;
import com.emplmgt.entity.User;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.EventRepository;
import com.emplmgt.repository.UserRepository;
import com.emplmgt.security.SecurityUtils;
import com.emplmgt.util.AppClock;
import com.emplmgt.util.JsonUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpcomingEventsServiceTest {

    @Mock EventRepository eventRepository;
    @Mock UserRepository userRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock HolidayService holidayService;
    @Mock EmployeeService employeeService;
    @Mock SecurityUtils securityUtils;
    @Mock AuditService auditService;
    @Mock AppClock appClock;
    @Mock JsonUtil jsonUtil;

    private UpcomingEventsService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);
    private static final Long USER_ID = 7L;
    private static final Long EMPLOYEE_ID = 42L;
    private static final Long TEAM_ID = 3L;

    @BeforeEach
    void setUp() {
        service = new UpcomingEventsService(eventRepository, userRepository, employeeRepository,
                holidayService, employeeService, securityUtils, auditService, appClock, jsonUtil);
        // lenient: the assignable-engineers listing never reads the clock.
        lenient().when(appClock.today()).thenReturn(TODAY);
    }

    private HolidayDtos.Response holidayRow(long id, String name, LocalDate date) {
        return new HolidayDtos.Response(id, name, date, "US", HolidayType.PUBLIC, null,
                com.emplmgt.entity.ApplicableLocation.ALL, true, ScopeType.GLOBAL, null);
    }

    private Event meeting(long id, String title, LocalDate date, EventType type) {
        return Event.builder()
                .id(id)
                .title(title)
                .eventDate(date)
                .eventType(type)
                .scope(ScopeType.TEAM)
                .build();
    }

    @Nested
    class Aggregation {

        @Test
        void combinesHolidaysBirthdaysAndMeetingsIntoOneChronologicalFeed() {
            givenCallerHasProfile();

            when(holidayService.listForEmployee(eq(EMPLOYEE_ID), eq(TODAY), eq(TODAY.plusDays(29))))
                    .thenReturn(List.of(holidayRow(1L, "Independence Day (Observed)", TODAY.plusDays(10))));
            when(employeeService.upcomingBirthdays(anyInt())).thenReturn(List.of(
                    new EmployeeService.Birthday(EMPLOYEE_ID, "Masher Choudary", TODAY.plusDays(3)),
                    new EmployeeService.Birthday(99L, "Siddhesh Verma", TODAY.plusDays(1))));
            when(eventRepository.findVisibleInRange(eq(TODAY), eq(TODAY.plusDays(29)), eq(TEAM_ID)))
                    .thenReturn(List.of(
                            meeting(5L, "Customer VRS", TODAY.plusDays(7), EventType.CUSTOMER_REMOTE_SESSION),
                            meeting(4L, "Office sync", TODAY.plusDays(2), EventType.OFFICE_MEETING)));

            CalendarDtos.UpcomingEventsResponse response = service.upcoming(30, null);

            assertThat(response.today()).isEqualTo(TODAY);
            assertThat(response.days()).isEqualTo(30);
            assertThat(response.events()).extracting(CalendarDtos.UpcomingEvent::subject)
                    .containsExactly("Siddhesh Verma", "Office sync", "Masher Choudary",
                            "Customer VRS", "Independence Day (Observed)");

            assertThat(response.events()).extracting(CalendarDtos.UpcomingEvent::category)
                    .containsExactly(UpcomingEventCategory.BIRTHDAY, UpcomingEventCategory.OFFICE_MEETING,
                            UpcomingEventCategory.BIRTHDAY, UpcomingEventCategory.CUSTOMER_REMOTE_SESSION,
                            UpcomingEventCategory.HOLIDAY);
        }

        @Test
        void reportsSourceAndDaysUntilForEachEntry() {
            givenCallerHasProfile();

            when(holidayService.listForEmployee(eq(EMPLOYEE_ID), any(), any())).thenReturn(List.of());
            when(employeeService.upcomingBirthdays(anyInt())).thenReturn(List.of(
                    new EmployeeService.Birthday(EMPLOYEE_ID, "Masher Choudary", TODAY)));
            when(eventRepository.findVisibleInRange(any(), any(), any())).thenReturn(List.of());

            CalendarDtos.UpcomingEvent today = service.upcoming(30, null).events().get(0);

            assertThat(today.source()).isEqualTo(UpcomingEventSource.BIRTHDAY.name());
            assertThat(today.daysUntil()).isZero();
            assertThat(today.employeeName()).isEqualTo("Masher Choudary");
            assertThat(today.startTime()).isNull();
            assertThat(today.meetingLink()).isNull();
        }

        @Test
        void mapsStoredEventTypesOntoPageCategories() {
            givenCallerHasProfile();

            when(holidayService.listForEmployee(eq(EMPLOYEE_ID), any(), any())).thenReturn(List.of());
            when(employeeService.upcomingBirthdays(anyInt())).thenReturn(List.of());
            when(eventRepository.findVisibleInRange(any(), any(), any())).thenReturn(List.of(
                    meeting(1L, "Legacy team meeting", TODAY.plusDays(1), EventType.TEAM_MEETING),
                    meeting(2L, "One to one", TODAY.plusDays(2), EventType.SCHEDULED_MEETING)));

            assertThat(service.upcoming(30, null).events())
                    .extracting(CalendarDtos.UpcomingEvent::category)
                    .containsExactly(UpcomingEventCategory.OFFICE_MEETING,
                            UpcomingEventCategory.SCHEDULED_MEETING);
        }

        @Test
        void holidaysUseLocationScopedLookupSoRegionalHolidaysAreFiltered() {
            givenCallerHasProfile();
            when(holidayService.listForEmployee(eq(EMPLOYEE_ID), any(), any())).thenReturn(List.of());
            when(employeeService.upcomingBirthdays(anyInt())).thenReturn(List.of());
            when(eventRepository.findVisibleInRange(any(), any(), any())).thenReturn(List.of());

            service.upcoming(30, null);

            verify(holidayService).listForEmployee(EMPLOYEE_ID, TODAY, TODAY.plusDays(29));
            verify(holidayService, never()).list(any(), any(), any(), any(), any());
        }

        @Test
        void callerWithoutEmployeeProfileStillSeesActiveHolidays() {
            when(securityUtils.currentUserId()).thenReturn(USER_ID);
            when(employeeService.findEmployeeByUser(USER_ID)).thenReturn(Optional.empty());
            when(holidayService.list(eq(TODAY), eq(TODAY.plusDays(29)), eq(null), eq(null), eq(null)))
                    .thenReturn(List.of(
                            holidayRow(1L, "Active holiday", TODAY.plusDays(5)),
                            new HolidayDtos.Response(2L, "Retired holiday", TODAY.plusDays(6), "US",
                                    HolidayType.PUBLIC, null,
                                    com.emplmgt.entity.ApplicableLocation.ALL, false,
                                    ScopeType.GLOBAL, null)));
            when(employeeService.upcomingBirthdays(anyInt())).thenReturn(List.of());
            when(eventRepository.findVisibleInRange(any(), any(), any())).thenReturn(List.of());

            assertThat(service.upcoming(30, null).events())
                    .extracting(CalendarDtos.UpcomingEvent::subject)
                    .containsExactly("Active holiday");
        }

        @Test
        void windowIsClampedAndDefaultsToThirtyDays() {
            givenCallerHasProfile();
            when(holidayService.listForEmployee(any(), any(), any())).thenReturn(List.of());
            when(employeeService.upcomingBirthdays(anyInt())).thenReturn(List.of());
            when(eventRepository.findVisibleInRange(any(), any(), any())).thenReturn(List.of());

            assertThat(service.upcoming(0, null).days()).isEqualTo(1);
            assertThat(service.upcoming(5_000, null).days())
                    .isEqualTo(UpcomingEventsService.MAX_WINDOW_DAYS);
            assertThat(service.upcoming(UpcomingEventsService.DEFAULT_WINDOW_DAYS, null).days())
                    .isEqualTo(30);
        }

        @Test
        void explicitTeamIdOverridesTheCallersOwnTeam() {
            givenCallerHasProfile();
            when(holidayService.listForEmployee(any(), any(), any())).thenReturn(List.of());
            when(employeeService.upcomingBirthdays(anyInt())).thenReturn(List.of());
            when(eventRepository.findVisibleInRange(any(), any(), eq(99L))).thenReturn(List.of());

            service.upcoming(30, 99L);

            verify(eventRepository).findVisibleInRange(TODAY, TODAY.plusDays(29), 99L);
        }

        /** lenient so the shared helper works for the teamId-override case too. */
        private void givenCallerHasProfile() {
            lenient().when(securityUtils.currentUserId()).thenReturn(USER_ID);
            lenient().when(securityUtils.currentTeamId()).thenReturn(TEAM_ID);
            lenient().when(employeeService.findEmployeeByUser(USER_ID)).thenReturn(Optional.of(
                    Employee.builder().id(EMPLOYEE_ID).department(Department.builder().id(TEAM_ID).build()).build()));
        }
    }

    @Nested
    class Create {

        @Test
        void storesMeetingFieldsOnTheEventRow() {
            when(securityUtils.currentUserId()).thenReturn(USER_ID);
            when(employeeService.findEmployeeByUser(USER_ID)).thenReturn(Optional.of(
                    Employee.builder().id(EMPLOYEE_ID)
                            .department(Department.builder().id(TEAM_ID).build()).build()));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                    User.builder().id(USER_ID).email("masher@example.com").build()));
            when(eventRepository.save(any(Event.class))).thenAnswer(i -> {
                Event e = i.getArgument(0);
                e.setId(101L);
                return e;
            });
            when(jsonUtil.writeList(List.of("share agenda"))).thenReturn("[\"share agenda\"]");

            CalendarDtos.UpcomingEvent created = service.create(new CalendarDtos.CreateEventRequest(
                    " Customer review ", "walkthrough", TODAY.plusDays(3),
                    LocalDateTime.of(2026, 9, 17, 10, 30),
                    UpcomingEventCategory.CUSTOMER_REMOTE_SESSION,
                    "  https://meet.example.com/abc ", true, List.of("share agenda"), null,
                    "Vertex Retail Systems", LocalDateTime.of(2026, 9, 17, 11, 30),
                    "Virtual Teams Room"));

            assertThat(created.id()).isEqualTo(101L);
            assertThat(created.subject()).isEqualTo("Customer review");
            assertThat(created.category()).isEqualTo(UpcomingEventCategory.CUSTOMER_REMOTE_SESSION);
            assertThat(created.eventType()).isEqualTo(EventType.CUSTOMER_REMOTE_SESSION.name());
            assertThat(created.meetingLink()).isEqualTo("https://meet.example.com/abc");
            assertThat(created.setReminder()).isTrue();
            assertThat(created.source()).isEqualTo(UpcomingEventSource.EVENT.name());

            verify(eventRepository).save(any(Event.class));
        }

        @Test
        void defaultsToOfficeMeetingWhenNoCategoryIsGiven() {
            when(securityUtils.currentUserId()).thenReturn(USER_ID);
            when(employeeService.findEmployeeByUser(USER_ID)).thenReturn(Optional.of(
                    Employee.builder().id(EMPLOYEE_ID)
                            .department(Department.builder().id(TEAM_ID).build()).build()));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                    User.builder().id(USER_ID).email("masher@example.com").build()));
            when(eventRepository.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));

            CalendarDtos.UpcomingEvent created = service.create(new CalendarDtos.CreateEventRequest(
                    "Quick sync", null, TODAY.plusDays(1), null, null, null, null, null, null, null, null,
                    null));

            assertThat(created.category()).isEqualTo(UpcomingEventCategory.OFFICE_MEETING);
            assertThat(created.eventType()).isEqualTo(EventType.OFFICE_MEETING.name());
            assertThat(created.setReminder()).isFalse();
        }

        @Test
        void createsAHolidayThroughTheHolidayServiceRatherThanAsAnEventRow() {
            when(securityUtils.currentUserId()).thenReturn(USER_ID);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                    User.builder().id(USER_ID).email("masher@example.com").build()));
            when(holidayService.create(any(HolidayDtos.HolidayRequest.class))).thenReturn(
                    new HolidayDtos.Response(55L, "Gandhi Jayanti", TODAY.plusDays(2), "US",
                            HolidayType.PUBLIC, "Company closed", ApplicableLocation.ALL, true,
                            ScopeType.GLOBAL, null));

            CalendarDtos.UpcomingEvent created = service.create(new CalendarDtos.CreateEventRequest(
                    " Gandhi Jayanti ", "Company closed", TODAY.plusDays(2),
                    null, UpcomingEventCategory.HOLIDAY, null, null, null, null, null, null, null));

            // The holiday is stored in `holidays`, so it reaches the holiday section
            // of the feed instead of surfacing as an office meeting.
            verify(eventRepository, never()).save(any(Event.class));
            verify(holidayService).create(any(HolidayDtos.HolidayRequest.class));
            assertThat(created.id()).isEqualTo(55L);
            assertThat(created.subject()).isEqualTo("Gandhi Jayanti");
            assertThat(created.category()).isEqualTo(UpcomingEventCategory.HOLIDAY);
            assertThat(created.source()).isEqualTo(UpcomingEventSource.HOLIDAY.name());
            assertThat(created.daysUntil()).isEqualTo(2L);
            // Meeting-only fields are never populated for a holiday.
            assertThat(created.startTime()).isNull();
            assertThat(created.assignedEngineerId()).isNull();
        }

        @Test
        void passesThePurposeAndDateThroughToTheHolidayRequest() {
            when(securityUtils.currentUserId()).thenReturn(USER_ID);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                    User.builder().id(USER_ID).email("masher@example.com").build()));
            when(holidayService.create(any(HolidayDtos.HolidayRequest.class))).thenReturn(
                    new HolidayDtos.Response(56L, "Pudchast", TODAY.plusDays(1), "US",
                            HolidayType.PUBLIC, null, ApplicableLocation.ALL, true,
                            ScopeType.GLOBAL, null));

            service.create(new CalendarDtos.CreateEventRequest(
                    " Pudchast ", "Office closed", TODAY.plusDays(1),
                    null, UpcomingEventCategory.HOLIDAY, null, null, null, null, null, null, null));

            ArgumentCaptor<HolidayDtos.HolidayRequest> captor =
                    ArgumentCaptor.forClass(HolidayDtos.HolidayRequest.class);
            verify(holidayService).create(captor.capture());
            // The name is trimmed here and the rest is left to HolidayService's
            // documented defaults (US / PUBLIC / ALL locations / active).
            assertThat(captor.getValue().name()).isEqualTo("Pudchast");
            assertThat(captor.getValue().date()).isEqualTo(TODAY.plusDays(1));
            assertThat(captor.getValue().description()).isEqualTo("Office closed");
            assertThat(captor.getValue().country()).isNull();
            assertThat(captor.getValue().holidayType()).isNull();
            assertThat(captor.getValue().applicableLocations()).isNull();
            assertThat(captor.getValue().active()).isNull();
        }

        @Test
        void birthdayIsStillRejectedRatherThanStoredAsAMeeting() {
            when(securityUtils.currentUserId()).thenReturn(USER_ID);

            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                    User.builder().id(USER_ID).email("masher@example.com").build()));

            // A birthday is derived from date of birth, so it must fail loudly
            // rather than silently become an office meeting.
            assertThatThrownBy(() -> service.create(new CalendarDtos.CreateEventRequest(
                    "Birthday", null, TODAY.plusDays(1),
                    null, UpcomingEventCategory.BIRTHDAY, null, null, null, null, null, null, null)))
                    .hasMessageContaining("BIRTHDAY");

            verify(eventRepository, never()).save(any(Event.class));
        }

        @Test
        void rejectsAPastDate() {
            assertThatThrownBy(() -> service.create(new CalendarDtos.CreateEventRequest(
                    "Retro", null, TODAY.minusDays(1), null, null, null, null, null, null, null, null,
                    null)))
                    .hasMessageContaining("past");

            verify(eventRepository, never()).save(any());
        }

        @Test
        void blankMeetingLinkIsStoredAsNull() {
            when(securityUtils.currentUserId()).thenReturn(USER_ID);
            when(employeeService.findEmployeeByUser(USER_ID)).thenReturn(Optional.of(
                    Employee.builder().id(EMPLOYEE_ID)
                            .department(Department.builder().id(TEAM_ID).build()).build()));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                    User.builder().id(USER_ID).email("masher@example.com").build()));
            when(eventRepository.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));

            assertThat(service.create(new CalendarDtos.CreateEventRequest(
                    "Room booking", null, TODAY.plusDays(2), null,
                    UpcomingEventCategory.CUSTOMER_REMOTE_SESSION, "   ", null, null, null, null, null,
                    null)).meetingLink())
                    .isNull();
        }
    }

    @Nested
    class Assignment {

        private Employee engineer(long id, String name, String code) {
            return Employee.builder().id(id).fullName(name).employeeCode(code).build();
        }

        /** Mirrors Aggregation#givenCallerHasProfile, which is private to that nested class. */
        private void givenCallerHasProfile() {
            lenient().when(securityUtils.currentUserId()).thenReturn(USER_ID);
            lenient().when(securityUtils.currentTeamId()).thenReturn(TEAM_ID);
            lenient().when(employeeService.findEmployeeByUser(USER_ID)).thenReturn(Optional.of(
                    Employee.builder().id(EMPLOYEE_ID).department(Department.builder().id(TEAM_ID).build()).build()));
        }

        /** Stubs the parts of create() that are unrelated to assignment. */
        private void givenCreatorIsAuthenticated() {
            when(securityUtils.currentUserId()).thenReturn(USER_ID);
            when(employeeService.findEmployeeByUser(USER_ID)).thenReturn(Optional.of(
                    Employee.builder().id(EMPLOYEE_ID)
                            .department(Department.builder().id(TEAM_ID).build()).build()));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                    User.builder().id(USER_ID).email("masher@example.com").build()));
            when(eventRepository.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        void persistsTheSelectedEngineerAndReturnsBothIdAndName() {
            givenCreatorIsAuthenticated();
            when(employeeRepository.findById(99L)).thenReturn(Optional.of(engineer(99L, "Siddhesh Verma", "EMP-99")));

            CalendarDtos.UpcomingEvent created = service.create(new CalendarDtos.CreateEventRequest(
                    "1:1", null, TODAY.plusDays(1), LocalDateTime.of(2026, 9, 15, 9, 0),
                    UpcomingEventCategory.SCHEDULED_MEETING, null, true, null, 99L, null, null, null));

            assertThat(created.assignedEngineerId()).isEqualTo(99L);
            assertThat(created.assignedEngineerName()).isEqualTo("Siddhesh Verma");

            ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(saved.capture());
            assertThat(saved.getValue().getAssignedTo().getId()).isEqualTo(99L);
        }

        @Test
        void storesTheOrganizationLocationAndEndTimeOnTheEventRow() {
            givenCreatorIsAuthenticated();
            when(employeeRepository.findById(any())).thenReturn(Optional.of(engineer(99L, "Sarah Jenkins", "EMP-99")));

            CalendarDtos.UpcomingEvent created = service.create(new CalendarDtos.CreateEventRequest(
                    "Q4 Architecture Review", null, TODAY.plusDays(1),
                    LocalDateTime.of(2026, 9, 15, 14, 0), UpcomingEventCategory.CUSTOMER_REMOTE_SESSION,
                    "https://teams.example.com/vertex", true, null, 99L,
                    "  Vertex Retail Systems  ", LocalDateTime.of(2026, 9, 15, 15, 30),
                    "  Virtual Teams Room  "));

            assertThat(created.organization()).isEqualTo("Vertex Retail Systems");
            assertThat(created.location()).isEqualTo("Virtual Teams Room");
            assertThat(created.endTime()).isEqualTo(LocalDateTime.of(2026, 9, 15, 15, 30));

            ArgumentCaptor<Event> saved = ArgumentCaptor.forClass(Event.class);
            verify(eventRepository).save(saved.capture());
            assertThat(saved.getValue().getOrganization()).isEqualTo("Vertex Retail Systems");
            assertThat(saved.getValue().getLocation()).isEqualTo("Virtual Teams Room");
            assertThat(saved.getValue().getEndTime()).isEqualTo(LocalDateTime.of(2026, 9, 15, 15, 30));
        }

        @Test
        void leavesOrganizationLocationAndEndTimeNullWhenOmitted() {
            givenCreatorIsAuthenticated();

            CalendarDtos.UpcomingEvent created = service.create(new CalendarDtos.CreateEventRequest(
                    "Internal sync", null, TODAY.plusDays(1), null, null, null, null, null,
                    null, null, null, null));

            assertThat(created.organization()).isNull();
            assertThat(created.location()).isNull();
            assertThat(created.endTime()).isNull();
        }

        // ------------------------------------------------------------------
        // Reassignment, backing the Customer Meetings card actions
        // ------------------------------------------------------------------

        /**
         * The team lookup is only consulted for team-scoped events, so it is lenient:
         * the global and creator-owned cases below legitimately never read it.
         */
        private void givenEventExists(Event event) {
            when(securityUtils.currentUserId()).thenReturn(USER_ID);
            lenient().when(securityUtils.currentTeamId()).thenReturn(TEAM_ID);
            when(eventRepository.findById(event.getId())).thenReturn(Optional.of(event));
            lenient().when(eventRepository.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));
        }

        @Test
        void reassignsAMeetingToAnotherEngineer() {
            givenEventExists(Event.builder()
                    .id(11L).title("Q4 Architecture Review").eventDate(TODAY.plusDays(1))
                    .eventType(EventType.CUSTOMER_REMOTE_SESSION).scope(ScopeType.TEAM)
                    .team(Department.builder().id(TEAM_ID).build())
                    .assignedTo(engineer(99L, "Sarah Jenkins", "EMP-99"))
                    .build());
            when(employeeRepository.findById(77L)).thenReturn(Optional.of(engineer(77L, "Aarav Mehta", "EMP-77")));

            CalendarDtos.UpcomingEvent updated = service.assign(11L, 77L);

            assertThat(updated.assignedEngineerId()).isEqualTo(77L);
            assertThat(updated.assignedEngineerName()).isEqualTo("Aarav Mehta");
        }

        @Test
        void assigningToTheSameEngineerIsIdempotent() {
            givenEventExists(Event.builder()
                    .id(11L).title("Q4 Architecture Review").eventDate(TODAY.plusDays(1))
                    .eventType(EventType.CUSTOMER_REMOTE_SESSION).scope(ScopeType.TEAM)
                    .team(Department.builder().id(TEAM_ID).build())
                    .assignedTo(engineer(99L, "Sarah Jenkins", "EMP-99"))
                    .build());
            when(employeeRepository.findById(99L)).thenReturn(Optional.of(engineer(99L, "Sarah Jenkins", "EMP-99")));

            assertThat(service.assign(11L, 99L).assignedEngineerName()).isEqualTo("Sarah Jenkins");
        }

        @Test
        void assigningNullClearsTheEngineer() {
            givenEventExists(Event.builder()
                    .id(11L).title("Q4 Architecture Review").eventDate(TODAY.plusDays(1))
                    .eventType(EventType.CUSTOMER_REMOTE_SESSION).scope(ScopeType.TEAM)
                    .team(Department.builder().id(TEAM_ID).build())
                    .assignedTo(engineer(99L, "Sarah Jenkins", "EMP-99"))
                    .build());

            CalendarDtos.UpcomingEvent updated = service.assign(11L, null);

            assertThat(updated.assignedEngineerId()).isNull();
            assertThat(updated.assignedEngineerName()).isNull();
            verify(employeeRepository, never()).findById(any());
        }

        @Test
        void assigningAnUnassignedMeetingWorks() {
            givenEventExists(Event.builder()
                    .id(12L).title("Global Logistics Check-in").eventDate(TODAY.plusDays(3))
                    .eventType(EventType.CUSTOMER_REMOTE_SESSION).scope(ScopeType.TEAM)
                    .team(Department.builder().id(TEAM_ID).build())
                    .organization("Maersk Global Tech")
                    .build());
            when(employeeRepository.findById(77L)).thenReturn(Optional.of(engineer(77L, "Aarav Mehta", "EMP-77")));

            assertThat(service.assign(12L, 77L).assignedEngineerName()).isEqualTo("Aarav Mehta");
        }

        @Test
        void rejectsAnEngineerIdThatDoesNotResolveOnReassign() {
            givenEventExists(Event.builder()
                    .id(11L).title("Q4 Architecture Review").eventDate(TODAY.plusDays(1))
                    .eventType(EventType.CUSTOMER_REMOTE_SESSION).scope(ScopeType.TEAM)
                    .team(Department.builder().id(TEAM_ID).build())
                    .build());
            when(employeeRepository.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.assign(11L, 404L))
                    .hasMessageContaining("Assigned engineer not found");
        }

        @Test
        void rejectsAMeetingFromAnotherTeam() {
            when(securityUtils.currentUserId()).thenReturn(USER_ID);
            when(securityUtils.currentTeamId()).thenReturn(TEAM_ID);
            when(eventRepository.findById(11L)).thenReturn(Optional.of(Event.builder()
                    .id(11L).title("Someone else's meeting").eventDate(TODAY.plusDays(1))
                    .eventType(EventType.CUSTOMER_REMOTE_SESSION).scope(ScopeType.TEAM)
                    .team(Department.builder().id(999L).build())
                    .build()));

            assertThatThrownBy(() -> service.assign(11L, 77L)).hasMessageContaining("Event not found");
            verify(eventRepository, never()).save(any());
        }

        @Test
        void letsTheCreatorReassignTheirOwnTeamlessMeeting() {
            // The known profile-less case: team is null, but the caller created it.
            when(securityUtils.currentUserId()).thenReturn(USER_ID);
            when(eventRepository.findById(13L)).thenReturn(Optional.of(Event.builder()
                    .id(13L).title("Self-created call").eventDate(TODAY.plusDays(1))
                    .eventType(EventType.SCHEDULED_MEETING).scope(ScopeType.TEAM)
                    .team(null)
                    .createdBy(User.builder().id(USER_ID).email("masher@example.com").build())
                    .build()));
            when(eventRepository.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));
            when(employeeRepository.findById(77L)).thenReturn(Optional.of(engineer(77L, "Aarav Mehta", "EMP-77")));

            assertThat(service.assign(13L, 77L).assignedEngineerName()).isEqualTo("Aarav Mehta");
        }

        @Test
        void globalMeetingsAreReassignableByAnyCaller() {
            when(securityUtils.currentUserId()).thenReturn(USER_ID);
            when(eventRepository.findById(14L)).thenReturn(Optional.of(Event.builder()
                    .id(14L).title("Company-wide customer briefing").eventDate(TODAY.plusDays(1))
                    .eventType(EventType.CUSTOMER_REMOTE_SESSION).scope(ScopeType.GLOBAL)
                    .build()));
            when(eventRepository.save(any(Event.class))).thenAnswer(i -> i.getArgument(0));
            when(employeeRepository.findById(77L)).thenReturn(Optional.of(engineer(77L, "Aarav Mehta", "EMP-77")));

            assertThat(service.assign(14L, 77L).assignedEngineerName()).isEqualTo("Aarav Mehta");
        }

        @Test
        void movesAMeetingToANewDateAndWindow() {
            Event event = Event.builder()
                    .id(11L).title("Q4 Architecture Review").eventDate(TODAY.plusDays(1))
                    .eventType(EventType.CUSTOMER_REMOTE_SESSION).scope(ScopeType.TEAM)
                    .team(Department.builder().id(TEAM_ID).build())
                    .startTime(TODAY.plusDays(1).atTime(10, 0))
                    .endTime(TODAY.plusDays(1).atTime(11, 0))
                    .build();
            givenEventExists(event);

            CalendarDtos.UpcomingEvent updated = service.reschedule(11L,
                    new CalendarDtos.RescheduleEventRequest(
                            TODAY.plusDays(5),
                            TODAY.plusDays(5).atTime(14, 30),
                            TODAY.plusDays(5).atTime(15, 30)));

            // UpcomingEvent carries real LocalDate/LocalDateTime, not strings.
            assertThat(updated.date()).isEqualTo(TODAY.plusDays(5));
            assertThat(updated.startTime()).isEqualTo(TODAY.plusDays(5).atTime(14, 30));
            assertThat(updated.endTime()).isEqualTo(TODAY.plusDays(5).atTime(15, 30));
        }

        @Test
        void reschedulingCanAlsoMakeAMeetingAllDay() {
            Event event = Event.builder()
                    .id(11L).title("Q4 Architecture Review").eventDate(TODAY.plusDays(1))
                    .eventType(EventType.CUSTOMER_REMOTE_SESSION).scope(ScopeType.TEAM)
                    .team(Department.builder().id(TEAM_ID).build())
                    .startTime(TODAY.plusDays(1).atTime(10, 0))
                    .build();
            givenEventExists(event);

            CalendarDtos.UpcomingEvent updated = service.reschedule(11L,
                    new CalendarDtos.RescheduleEventRequest(TODAY.plusDays(3), null, null));

            assertThat(updated.startTime()).isNull();
            assertThat(updated.endTime()).isNull();
        }

        @Test
        void rejectsReschedulingIntoThePast() {
            givenEventExists(Event.builder()
                    .id(11L).title("Q4 Architecture Review").eventDate(TODAY.plusDays(1))
                    .eventType(EventType.CUSTOMER_REMOTE_SESSION).scope(ScopeType.TEAM)
                    .team(Department.builder().id(TEAM_ID).build())
                    .build());

            assertThatThrownBy(() -> service.reschedule(11L,
                    new CalendarDtos.RescheduleEventRequest(TODAY.minusDays(1), null, null)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("past");
            verify(eventRepository, never()).save(any(Event.class));
        }

        @Test
        void rejectsAnEndTimeThatIsNotAfterTheStart() {
            givenEventExists(Event.builder()
                    .id(11L).title("Q4 Architecture Review").eventDate(TODAY.plusDays(1))
                    .eventType(EventType.CUSTOMER_REMOTE_SESSION).scope(ScopeType.TEAM)
                    .team(Department.builder().id(TEAM_ID).build())
                    .build());

            assertThatThrownBy(() -> service.reschedule(11L,
                    new CalendarDtos.RescheduleEventRequest(TODAY.plusDays(2),
                            TODAY.plusDays(2).atTime(15, 0),
                            TODAY.plusDays(2).atTime(14, 0))))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("after start time");
            verify(eventRepository, never()).save(any(Event.class));
        }

        @Test
        void rejectsReschedulingAMeetingFromAnotherTeam() {
            when(securityUtils.currentUserId()).thenReturn(USER_ID);
            when(securityUtils.currentTeamId()).thenReturn(TEAM_ID);
            when(eventRepository.findById(11L)).thenReturn(Optional.of(Event.builder()
                    .id(11L).title("Someone else's meeting").eventDate(TODAY.plusDays(1))
                    .eventType(EventType.CUSTOMER_REMOTE_SESSION).scope(ScopeType.TEAM)
                    .team(Department.builder().id(TEAM_ID + 1L).build())
                    .build()));

            assertThatThrownBy(() -> service.reschedule(11L,
                    new CalendarDtos.RescheduleEventRequest(TODAY.plusDays(2), null, null)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("not found");
        }

        @Test
        void leavesTheEventUnassignedWhenNoEngineerIsChosen() {
            givenCreatorIsAuthenticated();

            CalendarDtos.UpcomingEvent created = service.create(new CalendarDtos.CreateEventRequest(
                    "Open call", null, TODAY.plusDays(1), null,
                    UpcomingEventCategory.CUSTOMER_REMOTE_SESSION, null, null, null, null, null, null,
                    null));

            assertThat(created.assignedEngineerId()).isNull();
            assertThat(created.assignedEngineerName()).isNull();
            verify(employeeRepository, never()).findById(any());
        }

        @Test
        void rejectsAnEngineerIdThatDoesNotResolve() {
            // Stubs only what runs before the assignee lookup; the save stub in
            // givenCreatorIsAuthenticated() is deliberately not used here.
            when(securityUtils.currentUserId()).thenReturn(USER_ID);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                    User.builder().id(USER_ID).email("masher@example.com").build()));
            when(employeeRepository.findById(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.create(new CalendarDtos.CreateEventRequest(
                    "Bad assign", null, TODAY.plusDays(1), null,
                    UpcomingEventCategory.OFFICE_MEETING, null, null, null, 404L, null, null, null)))
                    .hasMessageContaining("Assigned engineer not found");

            verify(eventRepository, never()).save(any());
        }

        @Test
        void exposesTheAssigneeOnListedEvents() {
            givenCallerHasProfile();
            when(holidayService.listForEmployee(any(), any(), any())).thenReturn(List.of());
            when(employeeService.upcomingBirthdays(anyInt())).thenReturn(List.of());
            Employee leadArchitect = Employee.builder()
                    .id(99L).fullName("Sarah Jenkins").employeeCode("EMP-99")
                    .designation("Lead Architect")
                    .department(Department.builder().name("Voice").build())
                    .build();
            Event assigned = Event.builder()
                    .id(11L).title("Design review").eventDate(TODAY.plusDays(2))
                    .eventType(EventType.SCHEDULED_MEETING).scope(ScopeType.TEAM)
                    .location("Virtual Teams Room")
                    .assignedTo(leadArchitect)
                    .build();
            when(eventRepository.findVisibleInRange(any(), any(), any())).thenReturn(List.of(assigned));

            CalendarDtos.UpcomingEvent listed = service.upcoming(30, null).events().get(0);

            assertThat(listed.assignedEngineerId()).isEqualTo(99L);
            assertThat(listed.assignedEngineerName()).isEqualTo("Sarah Jenkins");
            // The card chip prefers the job title over the department.
            assertThat(listed.assignedEngineerDesignation()).isEqualTo("Lead Architect");
            assertThat(listed.assignedEngineerDepartment()).isEqualTo("Voice");
            assertThat(listed.location()).isEqualTo("Virtual Teams Room");
        }

        @Test
        void holidaysAndBirthdaysAreNeverReportedAsAssigned() {
            givenCallerHasProfile();
            when(holidayService.listForEmployee(any(), any(), any()))
                    .thenReturn(List.of(holidayRow(1L, "Thanksgiving", TODAY.plusDays(5))));
            when(employeeService.upcomingBirthdays(anyInt())).thenReturn(List.of(
                    new EmployeeService.Birthday(EMPLOYEE_ID, "Masher Choudary", TODAY.plusDays(1))));
            when(eventRepository.findVisibleInRange(any(), any(), any())).thenReturn(List.of());

            assertThat(service.upcoming(30, null).events())
                    .allSatisfy(e -> {
                        assertThat(e.assignedEngineerId()).isNull();
                        assertThat(e.assignedEngineerName()).isNull();
                    });
        }

        @Test
        void assignableEngineersReturnsActiveEmployeesSortedByName() {
            when(employeeRepository.findByEmploymentStatus(EmploymentStatus.ACTIVE)).thenReturn(List.of(
                    engineer(3L, "zeta Patel", "EMP-3"),
                    engineer(1L, "Alice Brown", "EMP-1"),
                    engineer(2L, "bob Chen", "EMP-2")));

            List<EmployeeDtos.Simple> result = service.assignableEngineers();

            assertThat(result).extracting(EmployeeDtos.Simple::fullName)
                    .containsExactly("Alice Brown", "bob Chen", "zeta Patel");
            assertThat(result).extracting(EmployeeDtos.Simple::id)
                    .containsExactly(1L, 2L, 3L);
        }
    }
}
