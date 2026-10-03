package com.emplmgt.service;

import com.emplmgt.dto.AttendanceRosterDtos;
import com.emplmgt.entity.AttendanceRecord;
import com.emplmgt.entity.Employee;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.UserRepository;
import com.emplmgt.security.SecurityUtils;
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
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceStatusDetailServiceTest {

    @Mock AttendanceRecordRepository recordRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock UserRepository userRepository;
    @Mock AttendanceRequestIntegrationService requestIntegration;
    @Mock SecurityUtils securityUtils;
    @Mock AppClock appClock;
    @Mock AuditService auditService;

    AttendanceStatusDetailService service;

    static final LocalDate DATE = LocalDate.of(2025, 6, 5);
    static final LocalDateTime EXCEL_AT = LocalDateTime.of(2025, 6, 1, 0, 54, 7, 760_000_000);
    static final Instant NOW = Instant.parse("2025-07-01T09:00:00Z");

    @BeforeEach
    void setUp() {
        when(appClock.now()).thenReturn(NOW);
        when(requestIntegration.resolveSource(anyString(), any(), anyString(), any(), any()))
                .thenReturn(null);
        service = new AttendanceStatusDetailService(recordRepository, employeeRepository, userRepository,
                requestIntegration, securityUtils, appClock, auditService);
    }

    @Test
    void readReturnsTheOriginalExcelAuthorAndTimestamp() {
        AttendanceRecord rec = importedRecord();
        when(recordRepository.findByEmployeeIdAndAttendanceDate("E1", DATE)).thenReturn(Optional.of(rec));

        AttendanceRosterDtos.StatusDetail detail = service.get("E1", DATE);

        assertThat(detail.descriptionSourceAuthor()).isEqualTo("John Smith");
        assertThat(detail.descriptionSourceAt()).isEqualTo(EXCEL_AT);
        assertThat(detail.descriptionImported()).isEqualTo("Worked on behalf of another engineer");
    }

    @Test
    void adminEditPreservesOriginalAttributionAndRecordsTheModifier() {
        AttendanceRecord rec = importedRecord();
        when(recordRepository.findByEmployeeIdAndAttendanceDate("E1", DATE)).thenReturn(Optional.of(rec));
        when(securityUtils.currentUserId()).thenReturn(7L);
        when(employeeRepository.findByUserId(7L))
                .thenReturn(Optional.of(Employee.builder().fullName("Admin User").build()));

        AttendanceRosterDtos.StatusDetail detail = service.upsert("E1", DATE, "Corrected wording");

        // The admin's edit is recorded...
        assertThat(rec.getDescription()).isEqualTo("Corrected wording");
        assertThat(rec.getDescriptionUpdatedName()).isEqualTo("Admin User");
        assertThat(rec.getDescriptionUpdatedAt()).isEqualTo(NOW);
        // ...while the workbook's own author, timestamp and original text survive.
        assertThat(rec.getDescriptionSourceAuthor()).isEqualTo("John Smith");
        assertThat(rec.getDescriptionSourceAt()).isEqualTo(EXCEL_AT);
        assertThat(rec.getDescriptionImported()).isEqualTo("Worked on behalf of another engineer");
        assertThat(rec.getDescriptionCreatedName()).isEqualTo("John Smith");
        assertThat(detail.descriptionSourceAuthor()).isEqualTo("John Smith");
    }

    private static AttendanceRecord importedRecord() {
        return AttendanceRecord.builder()
                .employeeId("E1")
                .attendanceDate(DATE)
                .statusCode("WFO")
                .description("Worked on behalf of another engineer")
                .descriptionSource("EXCEL_COMMENT")
                .descriptionSourceAuthor("John Smith")
                .descriptionSourceAt(EXCEL_AT)
                .descriptionImported("Worked on behalf of another engineer")
                .descriptionCreatedName("John Smith")
                .build();
    }
}
