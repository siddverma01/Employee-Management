package com.emplmgt.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Canonical attendance status codes used by the historical import.
 *
 * <p>Codes are stored in the DB exactly as they appear in the canonical
 * labels below (e.g. {@code "SW OFF"}, {@code "WK WRK"}). Raw values from the
 * workbook are matched against a set of aliases (case/spacing/non-breaking-space
 * insensitive). Values that cannot be matched are preserved exactly as written,
 * flagged {@code isUnknown = true}, and surfaced for the admin to review/remap.</p>
 */
public final class HistoricalImportCodes {

    private HistoricalImportCodes() {
    }

    private static final Map<String, String> COMPACT_TO_CANONICAL = new HashMap<>();

    private static void reg(String canonical, String... aliases) {
        for (String a : aliases) {
            COMPACT_TO_CANONICAL.put(compactCode(a), canonical);
        }
    }

    static {
        reg("WO", "WO", "Week Off", "Weekly Off", "Week Off Day", "Weekly Off Day", "Off Day");
        reg("WFO", "WFO", "Work From Office", "Working From Office", "Office", "In Office", "Office Duty", "WFO Duty");
        reg("WFH", "WFH", "Work From Home", "Working From Home", "Home", "Home Duty", "WFH Duty");
        reg("PL", "PL", "Privilege Leave", "Planned Leave", "Planned", "Paid Leave", "PL Duty", "P.L");
        reg("SL", "SL", "Sick Leave", "Sick", "Medical Leave", "SL Duty");
        reg("CO", "CO", "Comp Off", "Compensatory Off", "Compensatory", "Comp Off Day", "Compensatory Off Day", "C/O");
        reg("HPEH", "HPEH", "HPE Holiday", "HPE Holidays", "HPE Holiday Day", "HPE Holiday Duty", "Holiday", "HO Day");
        reg("FL", "FL", "Furlough", "Furlough Leave", "Furlough Leave Day", "Furlough Day", "FL Day");
        reg("SW OFF", "SW OFF", "SW Off", "Swap Off", "Swap Off Day", "Swap Off Duty", "SW Off Day", "SW Off Duty");
        reg("SW WK", "SW WK", "SW WK", "Swap Working", "Swap Work", "Swap WK", "Swap Working Day", "SW Weekly");
        reg("WK WRK", "WK WRK", "WK WRK", "Weekend Working", "Weekend Work", "Weekend Duty", "Weekend", "WK Duty");
        reg("HD", "HD", "Half Day", "Half Day Work", "Half Day Duty", "HD Duty");
        reg("WX", "WX", "Wellness", "Wellness Day", "Wellness Leave", "Wellness Off");
        reg("TR", "TR", "Training", "Training Day", "Training Duty", "TR Day");
        reg("ITS", "ITS", "IT Issues", "IT Issue", "ITS Day");
        reg("WDT", "WDT", "Working for Different Team", "Working Different Team", "Different Team", "WDT Duty",
                "Working for Another Team");
        // ATRn handled separately (regex) — ATR, ATR1, ATR2, ...
    }

    private static final Map<String, String> CANONICAL_NAMES = Map.ofEntries(
            Map.entry("WO", "Weekly Off"),
            Map.entry("WFO", "Work From Office"),
            Map.entry("WFH", "Work From Home"),
            Map.entry("PL", "Privilege Leave"),
            Map.entry("SL", "Sick Leave"),
            Map.entry("CO", "Compensatory Off"),
            Map.entry("HPEH", "HPE Holiday"),
            Map.entry("FL", "Furlough Leave"),
            Map.entry("SW OFF", "Swap Off"),
            Map.entry("SW WK", "Swap Working"),
            Map.entry("WK WRK", "Weekend Working"),
            Map.entry("HD", "Half Day"),
            Map.entry("WX", "Wellness"),
            Map.entry("TR", "Training"),
            Map.entry("ITS", "IT Issues"),
            Map.entry("WDT", "Working for Different Team"),
            Map.entry("ATR", "Attrition / Left Team"));

    /**
     * Returns true if {@code code} is one of the canonical known codes.
     */
    public static boolean isKnown(String code) {
        return code != null && (CANONICAL_NAMES.containsKey(code.toUpperCase(Locale.ROOT))
                || code.toUpperCase(Locale.ROOT).matches("ATR\\d*"));
    }

    /**
     * Name (human readable) for a canonical code; null for unknown codes.
     */
    public static String nameOf(String canonical) {
        if (canonical == null) {
            return null;
        }
        String upper = canonical.toUpperCase(Locale.ROOT);
        if (upper.matches("ATR\\d*")) {
            return CANONICAL_NAMES.get("ATR");
        }
        return CANONICAL_NAMES.get(upper);
    }

    /**
     * Normalise whitespace for any free-text value: trim, replace non-breaking
     * spaces, and collapse runs of whitespace to a single space. Returns null
     * when the result is empty. This is the shared text normaliser used for
     * employee metadata (names, emails, locations, shifts) and raw status
     * values before uppercase-canonical matching.
     */
    public static String normaliseText(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().replace('\u00A0', ' ').replaceAll("[\\s]+", " ").trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Normalise a raw value for storage: trim, collapse whitespace (including
     * non-breaking spaces) to a single space, uppercase. This is the value
     * persisted as {@code status_code}. Unknown values are preserved verbatim
     * (just normalised) — never dropped.
     */
    public static String normaliseRaw(String raw) {
        String normalised = normaliseText(raw);
        return normalised == null ? null : normalised.toUpperCase(Locale.ROOT);
    }

    /**
     * Compares two text values ignoring case, spacing, punctuation and
     * non-breaking spaces (used for header alias matching).
     */
    public static String compactCode(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u00A0', ' ')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    /**
     * Match a raw code to a canonical status. Returns null when the value is
     * blank or cannot be recognised (unknown code — preserve raw).
     */
    public static String canonicalOf(String raw) {
        String normalised = normaliseRaw(raw);
        if (normalised == null) {
            return null;
        }
        String compact = compactCode(normalised);
        if (compact.matches("atr\\d*")) {
            // Preserve the numeric suffix so "ATR1"/"ATR3" stay distinguishable.
            return "ATR" + normalised.replaceAll("^ATR", "");
        }
        return COMPACT_TO_CANONICAL.get(compact);
    }

    /**
     * Storage label for a raw code: the canonical code when recognised,
     * otherwise the normalised raw value preserved as-is.
     */
    public static String resolveStatus(String raw) {
        String canonical = canonicalOf(raw);
        return canonical != null ? canonical : normaliseRaw(raw);
    }

    /**
     * Suggest the most plausible canonical meaning for an unrecognised code.
     * The heuristic scores known aliases by how much of their compact form
     * overlaps the unknown code's compact form (substring / prefix / shared
     * tokens). Returns null when nothing resembles the code — the admin then
     * picks a meaning manually or keeps the code as unknown.
     */
    public static String suggestMeaning(String raw) {
        String compact = compactCode(raw);
        if (compact == null || compact.isEmpty() || compact.length() < 2) {
            return null;
        }
        String[] tokens = compact.split(" ");
        String best = null;
        int bestScore = 0;
        for (var entry : COMPACT_TO_CANONICAL.entrySet()) {
            String aliasCompact = entry.getKey();
            int score = 0;
            if (aliasCompact.length() >= 3) {
                if (compact.contains(aliasCompact)) {
                    score = aliasCompact.length();
                } else if (compact.length() >= 3 && aliasCompact.contains(compact)) {
                    score = compact.length();
                } else if (aliasCompact.startsWith(compact) || compact.startsWith(aliasCompact)) {
                    int common = Math.min(aliasCompact.length(), compact.length());
                    if (common >= 3) {
                        score = common;
                    }
                }
            }
            for (String t : tokens) {
                if (t.length() >= 3 && aliasCompact.contains(t)) {
                    score = Math.max(score, t.length());
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = entry.getValue();
            }
        }
        return bestScore >= 3 ? best : null;
    }
}