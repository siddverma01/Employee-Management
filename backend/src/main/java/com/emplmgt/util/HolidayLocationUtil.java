package com.emplmgt.util;

import com.emplmgt.entity.ApplicableLocation;
import com.emplmgt.entity.Holiday;

import java.util.Locale;

/**
 * Shared location-applicability rules for master holiday definitions.
 *
 * <p>{@code ALL} applies to everyone; a regional value (e.g. {@code PUNE_MUMBAI}) only applies
 * when the employee's free-text location contains that region. A holiday with no location
 * recorded never restricts anyone.</p>
 */
public final class HolidayLocationUtil {

    private HolidayLocationUtil() {
    }

    public static boolean applies(Holiday holiday, String employeeLocation) {
        ApplicableLocation applicable = holiday == null ? null : holiday.getApplicableLocations();
        if (applicable == null || applicable == ApplicableLocation.ALL) {
            return true;
        }
        if (employeeLocation == null || employeeLocation.isBlank()) {
            return false;
        }
        String location = employeeLocation.toUpperCase(Locale.ROOT);
        if (applicable == ApplicableLocation.PUNE_MUMBAI) {
            return location.contains("PUNE") || location.contains("MUMBAI");
        }
        return location.contains(applicable.name()) || location.contains(applicable.name().replace('_', ' '));
    }
}
