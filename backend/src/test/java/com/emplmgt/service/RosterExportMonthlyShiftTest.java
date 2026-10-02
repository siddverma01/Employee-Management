package com.emplmgt.service;

import com.emplmgt.entity.AttendanceShiftAssignment;
import com.emplmgt.entity.ImportEmployee;
import com.emplmgt.repository.AttendanceRecordRepository;
import com.emplmgt.repository.AttendanceShiftAssignmentRepository;
import com.emplmgt.repository.AttendanceWeekOffAssignmentRepository;
import com.emplmgt.repository.AttendanceStatusRepository;
import com.emplmgt.repository.DepartmentRepository;
import com.emplmgt.repository.EmployeeRepository;
import com.emplmgt.repository.HolidayRepository;
import com.emplmgt.repository.ImportEmployeeRepository;
import com.emplmgt.util.AppClock;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The downloaded workbook must carry the shift each employee was rostered to for
 * each exported sheet. Exporting the employee master's shift instead put one
 * shift on all twelve months of a year-long download, which then re-imported as
 * a flat roster and looked like a data error in the source file.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RosterExportMonthlyShiftTest {

    private static final String AM = "05:30-14:30";
    private static final String NIGHT = "19:00-04:00";
    private static final String PM = "13:30-22:30";

    @Mock ImportEmployeeRepository importEmployeeRepository;
    @Mock EmployeeRepository employeeRepository;
    @Mock DepartmentRepository departmentRepository;
    @Mock AttendanceRecordRepository recordRepository;
    @Mock AttendanceShiftAssignmentRepository shiftAssignmentRepository;
    @Mock AttendanceWeekOffAssignmentRepository weekOffAssignmentRepository;
    @Mock AttendanceStatusRepository statusRepository;
    @Mock HolidayRepository holidayRepository;
    @Mock AuditService auditService;
    @Mock AppClock appClock;

    private RosterExportService service;

    /** Master says PM; the rosters say AM in September, Night in October, PM in November. */
    private final ImportEmployee alice = ImportEmployee.builder()
            .employeeId("E1").employeeName("Alice").email("a@x.com").location("Pune")
            .defaultShift(PM).weekOff("Sun-Mon").active(Boolean.TRUE).build();

    @BeforeEach
    void setUp() {
        when(appClock.now()).thenReturn(java.time.Instant.parse("2026-12-01T00:00:00Z"));
        when(appClock.today()).thenReturn(LocalDate.of(2026, 12, 1));
        when(statusRepository.findAllByOrderByCodeAsc()).thenReturn(List.of());
        when(holidayRepository.findVisibleInRange(any(), any(), any(), any())).thenReturn(List.of());
        when(employeeRepository.findByEmployeeCodeIn(any())).thenReturn(List.of());
        when(recordRepository.findByAttendanceDateBetweenAndEmployeeIdInOrderByAttendanceDateAsc(
                any(), any(), any())).thenReturn(List.of());
        when(importEmployeeRepository.findRosterEmployees(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(alice));
        service = new RosterExportService(importEmployeeRepository, employeeRepository, departmentRepository,
                recordRepository, shiftAssignmentRepository, weekOffAssignmentRepository, statusRepository,
                holidayRepository, auditService, appClock);
    }

    private void assignments(String period, String shift) {
        when(shiftAssignmentRepository.findByEmployeeIdIn(any()))
                .thenReturn(List.of(assignment(YearMonth.parse(period), shift)));
    }

    private static AttendanceShiftAssignment assignment(YearMonth period, String shift) {
        return AttendanceShiftAssignment.builder().employeeId("E1")
                .periodStart(period.atDay(1)).shiftValue(shift).build();
    }

    private String exportedShift(YearMonth month) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.export(out, new RosterExportService.ExportRequest(
                RosterExportService.Scope.MONTH, month.toString(), null,
                null, null, null, null, null, false));
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            Row row = sheet.getRow(2);   // title, subtitle, then the first employee
            return row == null ? null : row.getCell(4).getStringCellValue();
        }
    }

    @Test
    @DisplayName("a single-month export shows that month's shift, not the master shift")
    void septemberExportsItsOwnShift() throws IOException {
        assignments("2026-09", AM);
        assertThat(exportedShift(YearMonth.of(2026, 9))).isEqualTo(AM);
    }

    @Test
    void octoberExportsItsOwnShift() throws IOException {
        assignments("2026-10", NIGHT);
        assertThat(exportedShift(YearMonth.of(2026, 10))).isEqualTo(NIGHT);
    }

    @Test
    void historicalMonthDoesNotExportTheCurrentMasterShift() throws IOException {
        assignments("2026-09", AM);
        assertThat(exportedShift(YearMonth.of(2026, 9)))
                .isEqualTo(AM)
                .isNotEqualTo(alice.getDefaultShift());
    }

    @Test
    @DisplayName("every sheet of a full-year export carries its own shift")
    void fullYearExportWritesPerMonthShifts() throws IOException {
        when(recordRepository.findDistinctAttendanceDatesAsc()).thenReturn(List.of(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1)));
        when(shiftAssignmentRepository.findByEmployeeIdIn(any())).thenAnswer(inv -> {
            // One assignment per month, keyed off nothing in the call - the service
            // must pick the one matching the sheet it is currently writing.
            return List.of(
                    assignment(YearMonth.of(2026, 9), AM),
                    assignment(YearMonth.of(2026, 10), NIGHT),
                    assignment(YearMonth.of(2026, 11), PM));
        });

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.export(out, new RosterExportService.ExportRequest(
                RosterExportService.Scope.FULL_YEAR, null, 2026,
                null, null, null, null, null, false));

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            for (int i = 0; i < wb.getNumberOfSheets(); i++) {
                String shift = wb.getSheetAt(i).getRow(2).getCell(4).getStringCellValue();
                assertThat(shift).as("sheet %s", wb.getSheetName(i))
                        .isIn(AM, NIGHT, PM);
            }
            assertThat(wb.getSheetAt(0).getRow(2).getCell(4).getStringCellValue()).isEqualTo(AM);
            assertThat(wb.getSheetAt(1).getRow(2).getCell(4).getStringCellValue()).isEqualTo(NIGHT);
            assertThat(wb.getSheetAt(2).getRow(2).getCell(4).getStringCellValue()).isEqualTo(PM);
        }
    }

    @Test
    void employeeWithNoPeriodDataExportsNoShiftRatherThanTheMasterOne() throws IOException {
        // An export must not write a master shift into a period the source never
        // rostered them for, or the spreadsheet would disagree with the grid.
        when(shiftAssignmentRepository.findByEmployeeIdIn(any())).thenReturn(List.of());
        assertThat(exportedShift(YearMonth.of(2026, 9))).isBlank();
    }
}