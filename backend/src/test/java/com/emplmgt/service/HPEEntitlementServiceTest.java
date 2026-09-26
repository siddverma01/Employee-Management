package com.emplmgt.service;

import com.emplmgt.dto.HolidayDtos;
import com.emplmgt.entity.ApplicableLocation;
import com.emplmgt.entity.Employee;
import com.emplmgt.entity.EmploymentStatus;
import com.emplmgt.entity.HPEEntitlement;
import com.emplmgt.entity.HPEEntitlementStatus;
import com.emplmgt.entity.Holiday;
import com.emplmgt.entity.HolidayType;
import com.emplmgt.entity.ScopeType;
import com.emplmgt.exception.ApiException;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.HPEEntitlementRepository;
import com.emplmgt.repository.HolidayRepository;
import com.emplmgt.service.RosterWorkStatusService.DayWorkStatus;
import com.emplmgt.service.RosterWorkStatusService.WorkSource;
import com.emplmgt.service.RosterWorkStatusService.WorkStatus;
import com.emplmgt.util.AppClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HPEEntitlementServiceTest {

    @Mock HPEEntitlementRepository entitlementRepository;
    @Mock HolidayRepository holidayRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock RosterWorkStatusService workStatusService;
    @Mock AppClock appClock;
    @Mock AuditService auditService;

    private HPEEntitlementService service;

    private Employee puneEmployee;

    @BeforeEach
    void setUp() {
        service = new HPEEntitlementService(entitlementRepository, holidayRepository, employeeRepository,
                workStatusService, appClock, auditService);
        puneEmployee = Employee.builder()
                .id(10L)
                .employeeCode("EMP-001")
                .fullName("Pune Employee")
                .email("pune@hpe.com")
                .location("Pune")
                .employmentStatus(EmploymentStatus.ACTIVE)
                .build();
    }

    private Holiday hpeHoliday(long id, LocalDate date, ApplicableLocation location) {
        return Holiday.builder()
                .id(id)
                .name("HPE Holiday " + id)
                .holidayDate(date)
                .country("IN")
                .holidayType(HolidayType.HPE_HOLIDAY)
                .applicableLocations(location)
                .active(true)
                .scope(ScopeType.GLOBAL)
                .build();
    }

    private HPEEntitlement entitlement(Holiday holiday, HPEEntitlementStatus status, LocalDate expiry) {
        return HPEEntitlement.builder()
                .id(500L)
                .employee(puneEmployee)
                .holiday(holiday)
                .earnedDate(holiday.getHolidayDate())
                .expiryDate(expiry)
                .status(status)
                .build();
    }

    private DayWorkStatus worked(String code) {
        return new DayWorkStatus(WorkStatus.WORKED, WorkSource.ROSTER, code);
    }

    private DayWorkStatus notWorked(WorkSource source, String code) {
        return new DayWorkStatus(WorkStatus.NOT_WORKED, source, code);
    }

    private DayWorkStatus unknown() {
        return new DayWorkStatus(WorkStatus.UNKNOWN, WorkSource.NONE, null);
    }

    @Nested
    class ApplicableHpeHolidays {

        @Test
        void puneEmployeeSeesAllAndPuneMumbaiHolidaysOnly() {
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(holidayRepository.findByHolidayTypeAndActiveTrueOrderByHolidayDate(HolidayType.HPE_HOLIDAY))
                    .thenReturn(List.of(
                            hpeHoliday(1L, LocalDate.of(2026, 1, 1), ApplicableLocation.ALL),
                            hpeHoliday(2L, LocalDate.of(2026, 3, 19), ApplicableLocation.PUNE_MUMBAI),
                            hpeHoliday(3L, LocalDate.of(2026, 4, 1), ApplicableLocation.BANGALORE)));

            List<HolidayDtos.HPEHolidayResponse> holidays = service.getApplicableHpeHolidays(10L);

            assertThat(holidays).extracting(HolidayDtos.HPEHolidayResponse::id).containsExactly(1L, 2L);
            assertThat(holidays.get(1).applicableLocations()).isEqualTo(ApplicableLocation.PUNE_MUMBAI);
        }

        @Test
        void allHolidaysApplyWhenEmployeeHasNoLocation() {
            puneEmployee.setLocation(null);
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(holidayRepository.findByHolidayTypeAndActiveTrueOrderByHolidayDate(HolidayType.HPE_HOLIDAY))
                    .thenReturn(List.of(
                            hpeHoliday(1L, LocalDate.of(2026, 1, 1), ApplicableLocation.ALL),
                            hpeHoliday(2L, LocalDate.of(2026, 3, 19), ApplicableLocation.PUNE_MUMBAI)));

            assertThat(service.getApplicableHpeHolidays(10L))
                    .extracting(HolidayDtos.HPEHolidayResponse::id).containsExactly(1L);
        }

        @Test
        void mumbaiEmployeeAlsoGetsPuneMumbaiHolidays() {
            puneEmployee.setLocation("Mumbai");
            Holiday holiday = hpeHoliday(2L, LocalDate.of(2026, 3, 19), ApplicableLocation.PUNE_MUMBAI);

            assertThat(service.isApplicableToLocation(holiday, puneEmployee.getLocation())).isTrue();
        }

        @Test
        void locationMatchingCoversAllAndPuneMumbaiRules() {
            Holiday all = hpeHoliday(1L, LocalDate.of(2026, 1, 1), ApplicableLocation.ALL);
            Holiday puneMumbai = hpeHoliday(2L, LocalDate.of(2026, 3, 19), ApplicableLocation.PUNE_MUMBAI);

            assertThat(service.isApplicableToLocation(all, null)).isTrue();
            assertThat(service.isApplicableToLocation(puneMumbai, "Pune")).isTrue();
            assertThat(service.isApplicableToLocation(puneMumbai, "Mumbai")).isTrue();
            assertThat(service.isApplicableToLocation(puneMumbai, "RTCC, Pune")).isTrue();
            assertThat(service.isApplicableToLocation(puneMumbai, "BLR")).isFalse();
            assertThat(service.isApplicableToLocation(puneMumbai, null)).isFalse();
        }
    }

    @Nested
    class CreateEntitlement {

        @Test
        void expiryIsThreeMonthsFromOriginalHolidayDateNotFromSubmission() {
            Holiday holiday = hpeHoliday(7L, LocalDate.of(2026, 5, 1), ApplicableLocation.PUNE_MUMBAI);
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(holidayRepository.findById(7L)).thenReturn(Optional.of(holiday));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 6, 10));
            when(entitlementRepository.findByEmployeeIdAndHolidayId(10L, 7L)).thenReturn(Optional.empty());
            when(workStatusService.resolve(puneEmployee, holiday.getHolidayDate())).thenReturn(worked("WFO"));
            when(entitlementRepository.save(any(HPEEntitlement.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            HolidayDtos.HPEEntitlementResponse response = service.createHpeEntitlement(10L, 7L);

            assertThat(response.expiryDate()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(response.earnedDate()).isEqualTo(LocalDate.of(2026, 5, 1));
            assertThat(response.status()).isEqualTo("AVAILABLE");
            assertThat(response.expiryDate()).isNotEqualTo(LocalDate.of(2026, 9, 10));
        }

        @Test
        void rejectsDuplicateEntitlementForSameEmployeeAndHoliday() {
            Holiday holiday = hpeHoliday(7L, LocalDate.of(2026, 5, 1), ApplicableLocation.PUNE_MUMBAI);
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(holidayRepository.findById(7L)).thenReturn(Optional.of(holiday));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 2));
            when(entitlementRepository.findByEmployeeIdAndHolidayId(10L, 7L))
                    .thenReturn(Optional.of(entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 8, 1))));

            assertThatThrownBy(() -> service.createHpeEntitlement(10L, 7L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("already exists");

            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void rejectsHolidayThatDoesNotApplyToEmployeeLocation() {
            Holiday holiday = hpeHoliday(8L, LocalDate.of(2026, 5, 1), ApplicableLocation.BANGALORE);
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(holidayRepository.findById(8L)).thenReturn(Optional.of(holiday));

            assertThatThrownBy(() -> service.createHpeEntitlement(10L, 8L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("does not apply");
        }

        @Test
        void rejectsWhenEmployeeTookTheHolidayOnTheRoster() {
            Holiday holiday = hpeHoliday(7L, LocalDate.of(2026, 5, 1), ApplicableLocation.PUNE_MUMBAI);
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(holidayRepository.findById(7L)).thenReturn(Optional.of(holiday));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 2));
            when(entitlementRepository.findByEmployeeIdAndHolidayId(10L, 7L)).thenReturn(Optional.empty());
            when(workStatusService.resolve(puneEmployee, holiday.getHolidayDate()))
                    .thenReturn(notWorked(WorkSource.ROSTER, "HPEH"));

            assertThatThrownBy(() -> service.createHpeEntitlement(10L, 7L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("roster status HPEH");

            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void rejectsWhenAttendanceShowsTheDayWasNotWorked() {
            Holiday holiday = hpeHoliday(7L, LocalDate.of(2026, 5, 1), ApplicableLocation.PUNE_MUMBAI);
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(holidayRepository.findById(7L)).thenReturn(Optional.of(holiday));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 2));
            when(entitlementRepository.findByEmployeeIdAndHolidayId(10L, 7L)).thenReturn(Optional.empty());
            when(workStatusService.resolve(puneEmployee, holiday.getHolidayDate()))
                    .thenReturn(notWorked(WorkSource.ATTENDANCE, "Holiday"));

            assertThatThrownBy(() -> service.createHpeEntitlement(10L, 7L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("only earned by working");
        }

        @Test
        void rejectsWhenNoStatusIsRecordedForTheDate() {
            Holiday holiday = hpeHoliday(7L, LocalDate.of(2026, 5, 1), ApplicableLocation.PUNE_MUMBAI);
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(holidayRepository.findById(7L)).thenReturn(Optional.of(holiday));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 2));
            when(entitlementRepository.findByEmployeeIdAndHolidayId(10L, 7L)).thenReturn(Optional.empty());
            when(workStatusService.resolve(puneEmployee, holiday.getHolidayDate())).thenReturn(unknown());

            assertThatThrownBy(() -> service.createHpeEntitlement(10L, 7L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("No working status is recorded");

            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void createsEntitlementWhenRosterShowsTheDayWasWorked() {
            Holiday holiday = hpeHoliday(7L, LocalDate.of(2026, 5, 1), ApplicableLocation.PUNE_MUMBAI);
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(holidayRepository.findById(7L)).thenReturn(Optional.of(holiday));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 2));
            when(entitlementRepository.findByEmployeeIdAndHolidayId(10L, 7L)).thenReturn(Optional.empty());
            when(workStatusService.resolve(puneEmployee, holiday.getHolidayDate())).thenReturn(worked("WFO"));
            when(entitlementRepository.save(any(HPEEntitlement.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            HolidayDtos.HPEEntitlementResponse response = service.createHpeEntitlement(10L, 7L);

            assertThat(response.status()).isEqualTo("AVAILABLE");
            assertThat(response.expiryDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        }

        @Test
        void rejectsEntitlementForAFutureHoliday() {
            Holiday holiday = hpeHoliday(9L, LocalDate.of(2026, 12, 25), ApplicableLocation.ALL);
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(holidayRepository.findById(9L)).thenReturn(Optional.of(holiday));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 6, 1));

            assertThatThrownBy(() -> service.createHpeEntitlement(10L, 9L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("on or after the HPE holiday date");
        }

        @Test
        void rejectsNonHpeHoliday() {
            Holiday holiday = Holiday.builder()
                    .id(11L).name("Public Holiday").holidayDate(LocalDate.of(2026, 5, 1))
                    .country("IN").holidayType(HolidayType.PUBLIC).scope(ScopeType.GLOBAL).build();
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(holidayRepository.findById(11L)).thenReturn(Optional.of(holiday));

            assertThatThrownBy(() -> service.createHpeEntitlement(10L, 11L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Only HPE holidays");
        }
    }

    @Nested
    class SyncEntitlements {

        private LocalDate date = LocalDate.of(2026, 9, 14);
        private Holiday holiday;

        @BeforeEach
        void holiday() {
            holiday = hpeHoliday(7L, date, ApplicableLocation.ALL);
        }

        @Test
        void awardsEntitlementsOnlyToEmployeesRecordedAsHavingWorked() {
            Employee other = Employee.builder()
                    .id(11L).employeeCode("EMP-002").fullName("Other")
                    .location("Pune").employmentStatus(EmploymentStatus.ACTIVE).build();
            when(appClock.today()).thenReturn(LocalDate.of(2026, 9, 25));
            when(employeeRepository.findByEmploymentStatus(EmploymentStatus.ACTIVE))
                    .thenReturn(List.of(puneEmployee, other));
            when(entitlementRepository.findByEmployeeIdAndHolidayId(10L, 7L)).thenReturn(Optional.empty());
            when(entitlementRepository.findByEmployeeIdAndHolidayId(11L, 7L)).thenReturn(Optional.empty());
            when(workStatusService.resolve(puneEmployee, date)).thenReturn(worked("WFO"));
            when(workStatusService.resolve(other, date)).thenReturn(notWorked(WorkSource.ROSTER, "HPEH"));
            when(entitlementRepository.save(any(HPEEntitlement.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            HolidayDtos.HpeEntitlementSyncResponse result = service.syncEntitlements(holiday);

            assertThat(result.evaluated()).isEqualTo(2);
            assertThat(result.created()).isEqualTo(1);
            assertThat(result.notWorking()).isEqualTo(1);
            assertThat(result.alreadyExists()).isZero();
            assertThat(result.notApplicable()).isZero();
            assertThat(result.unknownStatus()).isZero();
            verify(entitlementRepository, times(1)).save(any(HPEEntitlement.class));
        }

        @Test
        void neverDuplicatesAnExistingEntitlement() {
            when(appClock.today()).thenReturn(LocalDate.of(2026, 9, 25));
            when(employeeRepository.findByEmploymentStatus(EmploymentStatus.ACTIVE))
                    .thenReturn(List.of(puneEmployee));
            when(entitlementRepository.findByEmployeeIdAndHolidayId(10L, 7L))
                    .thenReturn(Optional.of(entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 12, 14))));

            HolidayDtos.HpeEntitlementSyncResponse result = service.syncEntitlements(holiday);

            assertThat(result.created()).isZero();
            assertThat(result.alreadyExists()).isEqualTo(1);
            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
            verify(workStatusService, never()).resolve(any(), any());
        }

        @Test
        void skipsFutureHolidaysWithoutScanningEmployees() {
            Holiday future = hpeHoliday(8L, LocalDate.of(2026, 12, 25), ApplicableLocation.ALL);
            when(appClock.today()).thenReturn(LocalDate.of(2026, 9, 25));

            HolidayDtos.HpeEntitlementSyncResponse result = service.syncEntitlements(future);

            assertThat(result.created()).isZero();
            assertThat(result.evaluated()).isZero();
            verify(employeeRepository, never()).findByEmploymentStatus(any(EmploymentStatus.class));
        }

        @Test
        void skipsInactiveHolidays() {
            holiday.setActive(false);
            when(appClock.today()).thenReturn(LocalDate.of(2026, 9, 25));

            HolidayDtos.HpeEntitlementSyncResponse result = service.syncEntitlements(holiday);

            assertThat(result.created()).isZero();
            assertThat(result.evaluated()).isZero();
            verify(employeeRepository, never()).findByEmploymentStatus(any(EmploymentStatus.class));
        }

        @Test
        void countsUnknownInsteadOfAwardingWhenNoStatusIsRecorded() {
            when(appClock.today()).thenReturn(LocalDate.of(2026, 9, 25));
            when(employeeRepository.findByEmploymentStatus(EmploymentStatus.ACTIVE))
                    .thenReturn(List.of(puneEmployee));
            when(entitlementRepository.findByEmployeeIdAndHolidayId(10L, 7L)).thenReturn(Optional.empty());
            when(workStatusService.resolve(puneEmployee, date)).thenReturn(unknown());

            HolidayDtos.HpeEntitlementSyncResponse result = service.syncEntitlements(holiday);

            assertThat(result.created()).isZero();
            assertThat(result.unknownStatus()).isEqualTo(1);
            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void countsEmployeesOutsideTheHolidayLocationAsNotApplicable() {
            Holiday puneMumbai = hpeHoliday(7L, date, ApplicableLocation.PUNE_MUMBAI);
            Employee bangalore = Employee.builder()
                    .id(12L).employeeCode("EMP-003").fullName("BLR Employee")
                    .location("Bangalore").employmentStatus(EmploymentStatus.ACTIVE).build();
            when(appClock.today()).thenReturn(LocalDate.of(2026, 9, 25));
            when(employeeRepository.findByEmploymentStatus(EmploymentStatus.ACTIVE))
                    .thenReturn(List.of(puneEmployee, bangalore));
            when(entitlementRepository.findByEmployeeIdAndHolidayId(10L, 7L)).thenReturn(Optional.empty());
            when(workStatusService.resolve(puneEmployee, date)).thenReturn(worked("WFH"));

            HolidayDtos.HpeEntitlementSyncResponse result = service.syncEntitlements(puneMumbai);

            assertThat(result.created()).isEqualTo(1);
            assertThat(result.notApplicable()).isEqualTo(1);
        }

        @Test
        void syncAllOnlyScansPastActiveHolidays() {
            Holiday past = hpeHoliday(1L, LocalDate.of(2026, 9, 14), ApplicableLocation.ALL);
            Holiday future = hpeHoliday(2L, LocalDate.of(2026, 12, 25), ApplicableLocation.ALL);
            when(appClock.today()).thenReturn(LocalDate.of(2026, 9, 25));
            when(holidayRepository.findByHolidayTypeAndActiveTrueOrderByHolidayDate(HolidayType.HPE_HOLIDAY))
                    .thenReturn(List.of(past, future));
            when(employeeRepository.findByEmploymentStatus(EmploymentStatus.ACTIVE)).thenReturn(List.of());

            List<HolidayDtos.HpeEntitlementSyncResponse> results = service.syncAllDueEntitlements();

            assertThat(results).hasSize(1);
            assertThat(results.get(0).holidayId()).isEqualTo(1L);
        }

        @Test
        void syncRejectsNonHpeHoliday() {
            Holiday publicHoliday = Holiday.builder()
                    .id(11L).name("Public Holiday").holidayDate(LocalDate.of(2026, 5, 1))
                    .country("IN").holidayType(HolidayType.PUBLIC).scope(ScopeType.GLOBAL).build();

            assertThatThrownBy(() -> service.syncEntitlements(publicHoliday))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Only HPE holidays");
        }

        @Test
        void syncUnknownHolidayIdIsNotFound() {
            when(holidayRepository.findById(999L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.syncEntitlements(999L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("not found");
        }
    }

    @Nested
    class UseEntitlement {

        private Holiday holiday;

        @BeforeEach
        void holiday() {
            holiday = hpeHoliday(7L, LocalDate.of(2026, 5, 1), ApplicableLocation.PUNE_MUMBAI);
        }

        @Test
        void rejectsUseAfterExpiryDate() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 8, 1));
            when(entitlementRepository.findById(500L)).thenReturn(Optional.of(entitlement));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 8, 2));

            assertThatThrownBy(() -> service.useHpeEntitlement(10L, 500L, 77L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("expired on 2026-08-01");

            assertThat(entitlement.getStatus()).isEqualTo(HPEEntitlementStatus.AVAILABLE);
            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void allowsUseOnTheExpiryDateItself() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 8, 1));
            when(entitlementRepository.findById(500L)).thenReturn(Optional.of(entitlement));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 8, 1));
            when(entitlementRepository.save(any(HPEEntitlement.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            HolidayDtos.HPEEntitlementResponse response = service.useHpeEntitlement(10L, 500L, 77L);

            assertThat(response.status()).isEqualTo("USED");
            assertThat(response.usedDate()).isEqualTo(LocalDate.of(2026, 8, 1));
            assertThat(response.usedRequestId()).isEqualTo(77L);
        }

        @Test
        void neverAllowsASecondUse() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.USED, LocalDate.of(2026, 8, 1));
            entitlement.setUsedDate(LocalDate.of(2026, 6, 1));
            when(entitlementRepository.findById(500L)).thenReturn(Optional.of(entitlement));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 6, 5));

            assertThatThrownBy(() -> service.useHpeEntitlement(10L, 500L, 77L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("already been used");

            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void rejectsUseOfAnotherEmployeesEntitlement() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 8, 1));
            when(entitlementRepository.findById(500L)).thenReturn(Optional.of(entitlement));

            assertThatThrownBy(() -> service.useHpeEntitlement(11L, 500L, 77L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("another employee");
        }
    }

    /**
     * Reservation lifecycle driven by the leave approval flow: submission reserves, approval
     * consumes, rejection/cancellation releases. Submitting must never mark an entitlement USED.
     *
     * <p>reserve / consume / release all go through {@code findByIdForUpdate}
     * ({@code SELECT ... FOR UPDATE}), which is what serialises two concurrent approvals or two
     * concurrent applications for the same entitlement.</p>
     */
    @Nested
    class Reservation {

        private Holiday holiday;

        /** The compensatory-off day an approved request would grant. */
        private static final LocalDate OFF_DATE = LocalDate.of(2026, 6, 10);
        private static final Long APPROVER = 900L;

        @BeforeEach
        void holiday() {
            holiday = hpeHoliday(7L, LocalDate.of(2026, 5, 1), ApplicableLocation.PUNE_MUMBAI);
        }

        @Test
        void reservingDoesNotConsumeTheEntitlement() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 8, 1));
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 15));
            when(entitlementRepository.save(any(HPEEntitlement.class))).thenAnswer(inv -> inv.getArgument(0));

            HolidayDtos.HPEEntitlementResponse response = service.reserveForRequest(10L, 500L, 77L);

            assertThat(response.status()).isEqualTo("RESERVED");
            assertThat(response.reservedRequestId()).isEqualTo(77L);
            // Not consumed: no used marker is written at submission time.
            assertThat(response.usedRequestId()).isNull();
            assertThat(response.usedDate()).isNull();
            assertThat(response.usedOffDate()).isNull();
        }

        @Test
        void reservingTakesTheRowLockSoConcurrentApplicationsSerialise() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 8, 1));
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 15));
            when(entitlementRepository.save(any(HPEEntitlement.class))).thenAnswer(inv -> inv.getArgument(0));

            service.reserveForRequest(10L, 500L, 77L);

            // The unlocked findById is never used on the write path - the reservation is
            // always read FOR UPDATE, so the check-then-write cannot interleave.
            verify(entitlementRepository).findByIdForUpdate(500L);
            verify(entitlementRepository, never()).findById(500L);
        }

        @Test
        void reservedEntitlementsAreNotOfferedAsAvailable() {
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 15));
            // The AVAILABLE-only query can never return a RESERVED row.
            when(entitlementRepository.findAvailableByEmployeeId(10L, LocalDate.of(2026, 5, 15)))
                    .thenReturn(List.of());

            assertThat(service.getAvailableHpeEntitlements(10L)).isEmpty();
        }

        @Test
        void approvalConsumesTheEntitlementAndStampsTheRequestAndOffDate() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.RESERVED, LocalDate.of(2026, 8, 1));
            entitlement.setReservedRequestId(77L);
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 20));
            when(entitlementRepository.save(any(HPEEntitlement.class))).thenAnswer(inv -> inv.getArgument(0));

            service.consumeReservation(500L, 77L, OFF_DATE, APPROVER);

            assertThat(entitlement.getStatus()).isEqualTo(HPEEntitlementStatus.USED);
            // usedDate is the consumption/approval date...
            assertThat(entitlement.getUsedDate()).isEqualTo(LocalDate.of(2026, 5, 20));
            // ...while usedOffDate is the day the employee actually gets off, which is a
            // separate, independently chosen value and may well be a different day.
            assertThat(entitlement.getUsedOffDate()).isEqualTo(OFF_DATE);
            assertThat(entitlement.getUsedRequestId()).isEqualTo(77L);
            assertThat(entitlement.getReservedRequestId()).isNull();
        }

        @Test
        void approvalIsIdempotentForAnAlreadyConsumedEntitlement() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.USED, LocalDate.of(2026, 8, 1));
            entitlement.setUsedRequestId(77L);
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));

            service.consumeReservation(500L, 77L, OFF_DATE, APPROVER);

            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void approvalRefusesToConsumeAnEntitlementAlreadySpentByAnotherRequest() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.USED, LocalDate.of(2026, 8, 1));
            entitlement.setUsedRequestId(99L);
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));

            // Same entitlement, different request: this would double-spend the entitlement.
            assertThatThrownBy(() -> service.consumeReservation(500L, 77L, OFF_DATE, APPROVER))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("already been consumed by another request");

            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void approvalRefusesToConsumeAnEntitlementReservedForADifferentRequest() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.RESERVED, LocalDate.of(2026, 8, 1));
            entitlement.setReservedRequestId(99L);
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));

            assertThatThrownBy(() -> service.consumeReservation(500L, 77L, OFF_DATE, APPROVER))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("not reserved for this request");

            // The entitlement is left exactly as it was - never marked USED.
            assertThat(entitlement.getStatus()).isEqualTo(HPEEntitlementStatus.RESERVED);
            assertThat(entitlement.getUsedDate()).isNull();
            assertThat(entitlement.getUsedOffDate()).isNull();
            assertThat(entitlement.getUsedRequestId()).isNull();
            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void approvalRefusesToConsumeAnUnreservedAvailableEntitlement() {
            // Nothing reserved it for this request, so consuming it would mark an entitlement
            // USED that this request never legitimately held.
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 8, 1));
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));

            assertThatThrownBy(() -> service.consumeReservation(500L, 77L, OFF_DATE, APPROVER))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("not reserved for this request");

            assertThat(entitlement.getStatus()).isEqualTo(HPEEntitlementStatus.AVAILABLE);
            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void approvalRefusesWhenTheEntitlementExpiredBeforeItWasDecided() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.EXPIRED, LocalDate.of(2026, 5, 1));
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));

            assertThatThrownBy(() -> service.consumeReservation(500L, 77L, OFF_DATE, APPROVER))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("expired on 2026-05-01");

            assertThat(entitlement.getStatus()).isEqualTo(HPEEntitlementStatus.EXPIRED);
            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void approvalAbortsWhenTheLinkedEntitlementNoLongerExists() {
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.consumeReservation(500L, 77L, OFF_DATE, APPROVER))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("no longer exists");
        }

        @Test
        void approvalOfANonCompOffRequestConsumesNothing() {
            // A privilege/sick leave has no linked entitlement; approval must be a no-op.
            service.consumeReservation(null, 77L, OFF_DATE, APPROVER);

            verify(entitlementRepository, never()).findByIdForUpdate(any());
            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void rejectionReturnsTheEntitlementToAvailable() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.RESERVED, LocalDate.of(2026, 8, 1));
            entitlement.setReservedRequestId(77L);
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 20));
            when(entitlementRepository.save(any(HPEEntitlement.class))).thenAnswer(inv -> inv.getArgument(0));

            service.releaseReservation(500L, 77L);

            assertThat(entitlement.getStatus()).isEqualTo(HPEEntitlementStatus.AVAILABLE);
            assertThat(entitlement.getReservedRequestId()).isNull();
            // Never consumed, so it carries no used marker.
            assertThat(entitlement.getUsedRequestId()).isNull();
            assertThat(entitlement.getUsedDate()).isNull();
            assertThat(entitlement.getUsedOffDate()).isNull();
        }

        @Test
        void cancellingAnApprovedRequestNeverResurrectsASpentEntitlement() {
            // An approved request already granted the day off and spent the entitlement.
            // Cancelling it must not hand the entitlement back for a second spend.
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.USED, LocalDate.of(2026, 8, 1));
            entitlement.setUsedRequestId(77L);
            entitlement.setUsedDate(LocalDate.of(2026, 5, 20));
            entitlement.setUsedOffDate(OFF_DATE);
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));

            service.releaseReservation(500L, 77L);

            assertThat(entitlement.getStatus()).isEqualTo(HPEEntitlementStatus.USED);
            assertThat(entitlement.getUsedRequestId()).isEqualTo(77L);
            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void cancellationDoesNotStealAnEntitlementHeldByAnotherRequest() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.RESERVED, LocalDate.of(2026, 8, 1));
            entitlement.setReservedRequestId(99L);
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));

            service.releaseReservation(500L, 77L);

            assertThat(entitlement.getStatus()).isEqualTo(HPEEntitlementStatus.RESERVED);
            assertThat(entitlement.getReservedRequestId()).isEqualTo(99L);
            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void releasingAnEntitlementWhoseWindowClosedExpiresRatherThanRevivesIt() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.RESERVED, LocalDate.of(2026, 5, 1));
            entitlement.setReservedRequestId(77L);
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 6, 1));
            when(entitlementRepository.save(any(HPEEntitlement.class))).thenAnswer(inv -> inv.getArgument(0));

            service.releaseReservation(500L, 77L);

            assertThat(entitlement.getStatus()).isEqualTo(HPEEntitlementStatus.EXPIRED);
        }

        @Test
        void reservedEntitlementsCannotBeConsumedOutOfBand() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.RESERVED, LocalDate.of(2026, 8, 1));
            when(entitlementRepository.findById(500L)).thenReturn(Optional.of(entitlement));

            assertThatThrownBy(() -> service.useHpeEntitlement(10L, 500L, 88L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("reserved by a pending request");

            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void aSecondRequestCannotReserveTheSameEntitlement() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.RESERVED, LocalDate.of(2026, 8, 1));
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));

            assertThatThrownBy(() -> service.reserveForRequest(10L, 500L, 88L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("already attached to another pending request");

            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void reservingRejectsAnExpiredEntitlement() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 5, 1));
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 6, 1));

            assertThatThrownBy(() -> service.reserveForRequest(10L, 500L, 88L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("expired on 2026-05-01");

            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void reservingRejectsAnEntitlementBelongingToAnotherEmployee() {
            HPEEntitlement entitlement = entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 8, 1));
            when(entitlementRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(entitlement));

            assertThatThrownBy(() -> service.reserveForRequest(11L, 500L, 88L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("another employee");

            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void summaryReportsReservedSeparatelyFromAvailable() {
            HPEEntitlement fresh = entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 8, 1));
            HPEEntitlement held = entitlement(holiday, HPEEntitlementStatus.RESERVED, LocalDate.of(2026, 8, 1));
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 15));
            when(entitlementRepository.findByEmployeeIdOrderByCreatedAtDesc(10L))
                    .thenReturn(List.of(held, fresh));

            HolidayDtos.EntitlementStatusSummary summary = service.getEntitlementStatusSummary(10L);

            assertThat(summary.available()).isEqualTo(1);
            assertThat(summary.reserved()).isEqualTo(1);
            assertThat(summary.used()).isZero();
            assertThat(summary.expired()).isZero();
        }
    }

    @Nested
    class Expire {

        @Test
        void marksOverdueAvailableEntitlementsAsExpired() {
            Holiday holiday = hpeHoliday(7L, LocalDate.of(2026, 1, 1), ApplicableLocation.ALL);
            HPEEntitlement overdue = entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 4, 1));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 1));
            when(entitlementRepository.findExpired(LocalDate.of(2026, 5, 1))).thenReturn(List.of(overdue));

            int expired = service.expireHpeEntitlements();

            assertThat(expired).isEqualTo(1);
            assertThat(overdue.getStatus()).isEqualTo(HPEEntitlementStatus.EXPIRED);
            verify(entitlementRepository).save(overdue);
        }

        @Test
        void doesNothingWhenNothingIsOverdue() {
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 1));
            when(entitlementRepository.findExpired(LocalDate.of(2026, 5, 1))).thenReturn(List.of());

            assertThat(service.expireHpeEntitlements()).isZero();
            verify(entitlementRepository, never()).save(any(HPEEntitlement.class));
        }

        @Test
        void summaryCountsOverdueAvailableEntitlementsAsExpired() {
            Holiday holiday = hpeHoliday(7L, LocalDate.of(2026, 1, 1), ApplicableLocation.ALL);
            HPEEntitlement overdue = entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 4, 1));
            HPEEntitlement fresh = HPEEntitlement.builder()
                    .id(501L).employee(puneEmployee).holiday(holiday)
                    .earnedDate(LocalDate.of(2026, 5, 1)).expiryDate(LocalDate.of(2026, 8, 1))
                    .status(HPEEntitlementStatus.AVAILABLE)
                    .build();
            HPEEntitlement used = HPEEntitlement.builder()
                    .id(502L).employee(puneEmployee).holiday(holiday)
                    .earnedDate(LocalDate.of(2026, 1, 1)).expiryDate(LocalDate.of(2026, 4, 1))
                    .status(HPEEntitlementStatus.USED)
                    .build();
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 2));
            when(entitlementRepository.findByEmployeeIdOrderByCreatedAtDesc(10L))
                    .thenReturn(List.of(used, fresh, overdue));

            HolidayDtos.EntitlementStatusSummary summary = service.getEntitlementStatusSummary(10L);

            assertThat(summary.available()).isEqualTo(1);
            assertThat(summary.used()).isEqualTo(1);
            assertThat(summary.expired()).isEqualTo(1);
        }
    }

    @Nested
    class Queries {

        @Test
        void availableEntitlementsUseTheApplicationClock() {
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 5, 2));
            Holiday holiday = hpeHoliday(7L, LocalDate.of(2026, 5, 1), ApplicableLocation.ALL);
            when(entitlementRepository.findAvailableByEmployeeId(10L, LocalDate.of(2026, 5, 2)))
                    .thenReturn(List.of(entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 8, 1))));

            List<HolidayDtos.HPEEntitlementResponse> result = service.getAvailableHpeEntitlements(10L);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).employeeId()).isEqualTo(10L);
            assertThat(result.get(0).holidayId()).isEqualTo(7L);
        }

        @Test
        void availableEntitlementsHideRowsWhoseExpiryHasPassed() {
            when(employeeRepository.findById(10L)).thenReturn(Optional.of(puneEmployee));
            when(appClock.today()).thenReturn(LocalDate.of(2026, 8, 2));
            Holiday holiday = hpeHoliday(7L, LocalDate.of(2026, 5, 1), ApplicableLocation.ALL);
            when(entitlementRepository.findAvailableByEmployeeId(10L, LocalDate.of(2026, 8, 2)))
                    .thenReturn(List.of(entitlement(holiday, HPEEntitlementStatus.AVAILABLE, LocalDate.of(2026, 8, 1))));

            assertThat(service.getAvailableHpeEntitlements(10L)).isEmpty();
        }

        @Test
        void unknownEmployeeIsRejected() {
            when(employeeRepository.findById(404L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getApplicableHpeHolidays(404L))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("Employee not found");
        }
    }
}
