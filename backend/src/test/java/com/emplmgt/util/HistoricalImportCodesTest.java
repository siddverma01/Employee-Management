package com.emplmgt.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalImportCodesTest {

    @Test
    void trimsAndNormalizesCaseAndSpace() {
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus(" WFO ")).isEqualTo("WFO");
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("  wfh  ")).isEqualTo("WFH");
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("SW  OFF")).isEqualTo("SW OFF");
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus(" wk wrk ")).isEqualTo("WK WRK");
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("HPE HOLIDAY")).isEqualTo("HPEH");
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("Swap Off")).isEqualTo("SW OFF");
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("W\u00A0F\u00A0O")).isEqualTo("WFO");
    }

    @Test
    void returnsNullForNullAndBlank() {
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus(null)).isNull();
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("")).isNull();
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("   ")).isNull();
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("\u00A0")).isNull();
    }

    @Test
    void returnsNullForFillerTokens() {
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus(".")).isNull();
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("-")).isNull();
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("—")).isNull();
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("0")).isNull();
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("1899-12-31")).isNull();
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("1899-12-30")).isNull();
    }

    @Test
    void preservesUnknownValuesWithoutSilentConversion() {
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("ABC")).isEqualTo("ABC");
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("silver duty")).isEqualTo("SILVER DUTY");
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("7:00AM")).isEqualTo("7:00AM");
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("0.1154")).isEqualTo("0.1154");
    }

    @Test
    void mapsAttritionFamilyToATR() {
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("ATRn")).isEqualTo("ATR");
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("ATR")).isEqualTo("ATR");
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("ATR1")).isEqualTo("ATR1");
        assertThat(HistoricalImportCodes.normalizeAttendanceStatus("ATR3")).isEqualTo("ATR3");
    }
}