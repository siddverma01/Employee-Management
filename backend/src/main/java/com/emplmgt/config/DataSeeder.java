package com.emplmgt.config;

import com.emplmgt.entity.*;
import com.emplmgt.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;

/**
 * Development seed data.
 * <p>
 * Credentials (development only):
 *   ADMIN    : admin@emplmgt.com / Admin@123 (login-only, no employee profile)
 *   EMPLOYEE : the HPE Voice team members below / Welcome@123
 * <p>
 * All employees belong to the Voice team. Default designation is
 * L1 Compute Engineer, with the following exceptions:
 *   - Srushti Khairnar : RTCC
 *   - Mangesh Sutar    : SME
 * <p>
 * Enable/disable with application.seed.enabled (default true in dev profile).
 */
@Component
@Profile("!prod")
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final EmployeeRepository employeeRepository;
    private final DepartmentRepository departmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final HolidayRepository holidayRepository;
    private final EventRepository eventRepository;
    private final LeaveBalanceRepository leaveBalanceRepository;
    private final NotificationRepository notificationRepository;

    @Value("${application.seed.enabled:true}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled || userRepository.count() > 0) {
            return;
        }
        log.info("Seeding development data ...");

        Department voice = departmentRepository.save(Department.builder().name("Voice").description("Voice operations").build());

        // Admin login account (no employee profile, so it never appears in the roster).
        userRepository.save(User.builder()
                .email("admin@emplmgt.com")
                .passwordHash(passwordEncoder.encode("Admin@123"))
                .role(Role.ADMIN)
                .enabled(true)
                .build());

        List<SeedEmployee> roster = List.of(
                new SeedEmployee("25106149", "masher.choudary-ext@hpe.com", "Masher Choudary", "Pune", "05:30-14:30", "Sun-Mon"),
                new SeedEmployee("25102404", "siddhesh.verma-ext@hpe.com", "Siddhesh Verma", "Pune", "05:30-14:30", "Fri-Sat"),
                new SeedEmployee("25101553", "pradnya.mane-ext@hpe.com", "Pradnya Mane", "Pune", "05:30-14:30", "Sun-Mon"),
                new SeedEmployee("60179491", "rutuja.ashish-palaye@hpe.com", "Rutuja Palaye", "Pune", "13:30-22:30", "Sat-Sun"),
                new SeedEmployee("25098410", "ritik.raina@hpe.com", "Ritik Raina", "Pune", "13:30-22:30", "Fri-Sat"),
                new SeedEmployee("25102661", "omkar-vikas.jagdale-ext@hpe.com", "Omkar Jagdale", "Pune", "15:00-00:00", "Sun-Mon (1:30-10:30)"),
                new SeedEmployee("25101845", "pavan.pawale-ext@hpe.com", "Pavan Pawale", "Pune", "17:00-02:00", "Sat-Sun"),
                new SeedEmployee("60179383", "snehal-vaman.karale@hpe.com", "Snehal Vaman Karale", "Pune", "17:00-02:00", "Sun-Mon"),
                new SeedEmployee("25107303", "ashutosh-sanjay.tiwari-ext@hpe.com", "Ashutosh", "Pune", "17:00-02:00", "Fri-Sat"),
                new SeedEmployee("60170198", "diya.javeed-kudchi@hpe.com", "Diya Javeed Kudchi", "Pune", "19:00-04:00", "Wed-Thurs"),
                new SeedEmployee("25111496", "pari.sharma-ext@hpe.com", "Pari Sharma", "Pune", "19:00-04:00", "Sat-Sun"),
                new SeedEmployee("25116620", "kamran.hamid-ext@hpe.com", "Kamran Hamid", "Pune", "19:00-04:00", "Sat-Sun"),
                new SeedEmployee("60178092", "pranav.vijay-chavhan@hpe.com", "Pranav Chavhan", "Pune", "19:00-04:00", "Sat-Sun"),
                new SeedEmployee("25102660", "shreyas-sharad.mithbawkar-ext@hpe.com", "Shreyas Mithbawkar", "Pune", "19:00-04:00", "Sat-Sun"),
                new SeedEmployee("60183530", "anubhav.mayank@hpe.com", "Anubhav Mayank", "Pune", "19:00-04:00", "Sun-Mon (9-6)"),
                new SeedEmployee("60169893", "srushti@hpe.com", "Srushti Khairnar", "RTCC", "Pune", "19:00-04:00", "Sat-Sun"),
                new SeedEmployee("60178089", "gauri-narendra.mankhair@hpe.com", "Gauri Narendra Mankhair", "Pune", "21:00-06:00", "Sat-Sun"),
                new SeedEmployee("60175312", "siya.pardhi@hpe.com", "Siya Pardhi", "Pune", "21:00-06:00", "Sat-Sun"),
                new SeedEmployee("60177879", "prachi.mahesh-dawkhar@hpe.com", "Prachi Dawkhar", "Pune", "21:00-06:00", "Fri-Sat"),
                new SeedEmployee("25107005", "soundharya.m-ext@hpe.com", "Soundharya M", "Pune", "21:00-06:00", "Sat-Sun"),
                new SeedEmployee("25107127", "shafiya.warunkar-ext@hpe.com", "Shafiya Warunkar", "Pune", "21:00-06:00", "Sun-Mon"),
                new SeedEmployee("25099452", "manoj.jm-ext@hpe.com", "Manoj J M", "BLR", "21:00-06:00", "Sat-Sun"),
                new SeedEmployee("25082297", "mangesh.sutar@hpe.com", "Mangesh Sutar", "SME", "Pune", "19:00-04:00", "Sat-Sun")
        );

        for (SeedEmployee se : roster) {
            createEmployee(se.code(), se.name(), se.email(), null, voice,
                    se.designation(), se.location(), null, null,
                    null, se.shift(), se.weekOff());
        }

        List<Employee> all = employeeRepository.findAll();

        // Seed leave balances for current year.
        for (Employee e : all) {
            for (LeaveType type : LeaveType.values()) {
                BigDecimal allocated = type == LeaveType.COMP_OFF ? BigDecimal.ZERO : BigDecimal.valueOf(type == LeaveType.PRIVILEGE_LEAVE ? 20 : 12);
                leaveBalanceRepository.save(LeaveBalance.builder()
                        .employee(e).leaveType(type).year(Year.now().getValue()).allocated(allocated).build());
            }
        }

        seedHolidays(voice);
        seedEvents(voice);

        // Welcome notifications for everyone.
        LocalDate today = LocalDate.now();
        for (Employee e : all) {
            if (e.getUser() != null) {
                notificationRepository.save(Notification.builder().user(e.getUser())
                        .title("Welcome to Employee Management")
                        .body("Your account is ready. Feel free to explore the dashboard, apply for leaves and more.")
                        .type(NotificationType.SYSTEM)
                        .build());
            }
        }

        log.info("Seeded {} Voice team employees. Admin login: admin@emplmgt.com / Admin@123", roster.size());
    }

    private Employee createEmployee(String code, String name, String email, User user, Department dept,
                                    String designation, String location, LocalDate doj, LocalDate dob, Employee manager,
                                    String shift, String weekOff) {
        User savedUser = user;
        if (savedUser == null) {
            savedUser = userRepository.save(User.builder()
                    .email(email)
                    .passwordHash(passwordEncoder.encode("Welcome@123"))
                    .role(Role.EMPLOYEE)
                    .enabled(true)
                    .build());
        }
        return employeeRepository.save(Employee.builder()
                .employeeCode(code)
                .user(savedUser)
                .department(dept)
                .manager(manager)
                .fullName(name)
                .email(email)
                .designation(designation)
                .location(location)
                .shift(shift)
                .weekOff(weekOff)
                .dateOfJoining(doj)
                .dateOfBirth(dob)
                .employmentStatus(EmploymentStatus.ACTIVE)
                .build());
    }

    private void seedHolidays(Department voiceTeam) {
        int y = Year.now().getValue();
        holiday(y, 1, 1, "New Year's Day", "US", null);
        holiday(y, 1, 19, "Martin Luther King Jr. Day", "US", null);
        holiday(y, 2, 16, "Presidents' Day", "US", null);
        holiday(y, 5, 25, "Memorial Day", "US", null);
        holiday(y, 6, 19, "Juneteenth", "US", null);
        holiday(y, 7, 3, "Independence Day (observed)", "US", null);
        holiday(y, 9, 7, "Labor Day", "US", null);
        holiday(y, 11, 26, "Thanksgiving Day", "US", null);
        holiday(y, 12, 25, "Christmas Day", "US", null);
        holiday(LocalDate.now().plusDays(14), "Company Foundation Day", "US", null);
        // Team-scoped holiday visible only to the Voice team.
        holiday(LocalDate.now().plusDays(18), "Voice Team Offsite", "US", voiceTeam);
    }

    private void holiday(int year, int month, int day, String name, String country, Department team) {
        holiday(LocalDate.of(year, month, day), name, country, team);
    }

    private void holiday(LocalDate date, String name, String country, Department team) {
        holidayRepository.save(Holiday.builder()
                .name(name).holidayDate(date).country(country)
                .holidayType(HolidayType.PUBLIC)
                .scope(team == null ? ScopeType.GLOBAL : ScopeType.TEAM)
                .team(team)
                .build());
    }

    private void seedEvents(Department voiceTeam) {
        LocalDate today = LocalDate.now();
        eventRepository.save(Event.builder().title("Quarterly Town Hall").description("All-hands meeting with execs")
                .eventDate(today.plusDays(7)).eventType(EventType.COMPANY_EVENT).build());
        eventRepository.save(Event.builder().title("Engineering All Hands")
                .eventDate(today.plusDays(21)).eventType(EventType.TEAM_MEETING).build());
        eventRepository.save(Event.builder().title("Voice Team Standup")
                .eventDate(today.plusDays(4)).eventType(EventType.TEAM_MEETING)
                .scope(ScopeType.TEAM).team(voiceTeam).build());
    }

    /**
     * Voice team roster row. Default designation is L1 Compute Engineer;
     * the {@code designation} field allows overrides (e.g. RTCC, SME).
     */
    private record SeedEmployee(String code, String email, String name, String designation,
                                String location, String shift, String weekOff) {
        SeedEmployee(String code, String email, String name, String location, String shift, String weekOff) {
            this(code, email, name, "L1 Compute Engineer", location, shift, weekOff);
        }
    }
}