package com.emplmgt.util;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalises and validates the attendance-status codes used in roster grids.
 *
 * <p>Supported codes: WO | WFO | WFH | PL | SL | CO | FL | HPEH | ATR[0-9]* (attrition).
 * Aliases (e.g. "Work From Home", "Week Off", "Left Team") are collapsed to the canonical code.</p>
 */
public final class RosterStatusCodes {

    private RosterStatusCodes() {
    }

    public static final String WO = "WO";
    public static final String WFO = "WFO";
    public static final String WFH = "WFH";
    public static final String PL = "PL";
    public static final String SL = "SL";
    public static final String CO = "CO";
    public static final String FL = "FL";
    public static final String HPEH = "HPEH";
    public static final String ATR = "ATR";

    /** Canonical base codes that can appear in a roster cell (ATR[0-9]* is matched separately). */
    public static final Set<String> BASE_CODES = Set.of(WO, WFO, WFH, PL, SL, CO, FL, HPEH, ATR);

    /** Code -> human label used by the UI legend. */
    public static final Map<String, String> LABELS = labels();

    private static final Pattern IS_ATR = Pattern.compile("^ATR(\\d*)$", Pattern.CASE_INSENSITIVE);

    private static Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(WO, "Week Off");
        m.put(WFO, "Work From Office");
        m.put(WFH, "Work From Home");
        m.put(PL, "Privilege Leave");
        m.put(SL, "Sick Leave");
        m.put(CO, "Compensatory Off");
        m.put(FL, "Furlough");
        m.put(HPEH, "HPE Holiday");
        m.put(ATR, "Attrition / Left Team");
        return m;
    }

    /**
     * @return the canonical status code, or {@code null} when the value is blank.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        String lower = v.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-./()]+", "");

        if (lower.equals("wo") || lower.equals("weekoff") || lower.equals("weeklyoff")
                || lower.equals("off") || lower.equals("dayoff")) {
            return WO;
        }
        if (lower.equals("wfo") || lower.equals("workfromoffice") || lower.equals("office")
                || lower.equals("inoffice") || lower.equals("present") || lower.equals("p")
                || lower.equals("insite")) {
            return WFO;
        }
        if (lower.equals("wfh") || lower.equals("workfromhome") || lower.equals("home")
                || lower.equals("remote") || lower.equals("h") || lower.equals("workfromhomeday")) {
            return WFH;
        }
        if (lower.equals("pl") || lower.equals("privilegeleave") || lower.equals("leave")) {
            return PL;
        }
        if (lower.equals("sl") || lower.equals("sickleave") || lower.equals("medical")) {
            return SL;
        }
        if (lower.equals("co") || lower.equals("compoff") || lower.equals("compensatoryoff")
                || lower.equals("compensatoryleave") || lower.equals("compensateoff")) {
            return CO;
        }
        if (lower.equals("fl") || lower.equals("furlough") || lower.equals("furloughleave")) {
            return FL;
        }
        if (lower.equals("hpeh") || lower.equals("hpeholiday") || lower.equals("holiday")
                || lower.equals("hpeholidays") || lower.equals("hpe")) {
            return HPEH;
        }
        if (lower.equals("atr") || lower.equals("attrition") || lower.equals("left")
                || lower.equals("leftteam") || lower.equals("resigned") || lower.equals("relieved")
                || lower.equals("attrited") || lower.equals("attritionleft") || lower.equals("exit")) {
            return ATR;
        }
        java.util.regex.Matcher atr = IS_ATR.matcher(v);
        if (atr.matches()) {
            String suffix = atr.group(1);
            return suffix == null || suffix.isEmpty() ? ATR : ATR + suffix;
        }
        return null;
    }

    /**
     * Whether a value already in canonical form is acceptable for storage.
     */
    public static boolean isValid(String code) {
        if (code == null || code.isBlank()) {
            return true; // blank means "no status"
        }
        if (BASE_CODES.contains(code.trim())) {
            return true;
        }
        return IS_ATR.matcher(code.trim()).matches();
    }

    /**
     * Human label for a stored/selent code (ATRn shares the attrition label).
     */
    public static String labelOf(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        if (LABELS.containsKey(code)) {
            return LABELS.get(code);
        }
        if (code.startsWith(ATR)) {
            return LABELS.get(ATR);
        }
        return code;
    }
}