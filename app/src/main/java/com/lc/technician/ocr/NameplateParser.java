package com.lc.technician.ocr;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure function: raw OCR text → NameplateCandidates.
 *
 * Rules:
 *  - Only extract if the pattern clearly matches an explicit label on the plate.
 *  - Never fabricate a value — null means "not found".
 *  - Never modify Equipment directly — just returns candidates.
 *  - rawText is always set (even on empty / garbage input).
 *
 * Regex patterns adapted from BFS data-plate-parser.ts
 * (read-only reference, not copied).
 */
public class NameplateParser {

    // ── Label extraction helper ──────────────────────────────────────────────

    /**
     * Looks for label in upper-case text, then grabs the value that follows
     * on the same line (after optional ":"/"-"/"#") or on the very next line.
     *
     * @param lines    array of trimmed lines from the OCR text
     * @param labels   candidate label strings (searched longest-first)
     * @param valueRx  pattern to extract the value from the text after the label
     * @return         first match found, or null
     */
    private static String extractByLabel(String[] lines, String[] labels, Pattern valueRx) {
        // Try longest labels first so "MODEL NUMBER" beats bare "MODEL"
        java.util.Arrays.sort(labels, (a, b) -> b.length() - a.length());

        for (int i = 0; i < lines.length; i++) {
            String upper = lines[i].toUpperCase();
            for (String label : labels) {
                int idx = upper.indexOf(label);
                if (idx == -1) continue;

                // Text on the same line after the label
                String afterLabel = lines[i].substring(idx + label.length())
                        .replaceAll("^[\\s:\\-#]+", "");

                Matcher m = valueRx.matcher(afterLabel);
                if (m.find()) {
                    return m.group().trim();
                }

                // Label alone on its line — try next line
                if (afterLabel.trim().isEmpty() && i + 1 < lines.length) {
                    Matcher m2 = valueRx.matcher(lines[i + 1]);
                    if (m2.find()) {
                        return m2.group().trim();
                    }
                }
            }
        }
        return null;
    }

    /** Scans the entire text for a self-identifying pattern (no label required). */
    private static String extractByPattern(String rawText, Pattern pattern) {
        Matcher m = pattern.matcher(rawText.toUpperCase());
        return m.find() ? m.group().trim() : null;
    }

    // ── Plausibility guards ──────────────────────────────────────────────────

    /** Model / serial numbers: at least 4 chars, at least one digit. */
    private static boolean isPlausibleCode(String value) {
        if (value == null) return false;
        String cleaned = value.replaceAll("[.\\-\\s]", "");
        return cleaned.length() >= 4 && cleaned.matches(".*\\d.*");
    }

    /** Voltage sanity: all numbers found must be in 100–600 range. */
    private static boolean isPlausibleVoltage(String value) {
        if (value == null) return false;
        Pattern nums = Pattern.compile("\\d{2,3}");
        Matcher m = nums.matcher(value);
        boolean found = false;
        while (m.find()) {
            found = true;
            int n = Integer.parseInt(m.group());
            if (n < 100 || n > 600) return false;
        }
        return found;
    }

    // ── Value patterns ───────────────────────────────────────────────────────

    private static final Pattern ALNUM_CODE     = Pattern.compile("[A-Za-z0-9][A-Za-z0-9./_-]{2,}");
    private static final Pattern NUMBER          = Pattern.compile("\\d+(\\.\\d+)?");
    private static final Pattern VOLTAGE_PATTERN = Pattern.compile(
            "\\d{2,3}\\s*[/\\-]\\s*\\d{2,3}\\s*[Vv]?|\\d{2,3}\\s*[Vv]\\b");
    private static final Pattern REFRIGERANT_LABEL_PATTERN = Pattern.compile("R[-\\s]?\\d{2,4}[A-Za-z]?");
    private static final Pattern REFRIGERANT_SELF_PATTERN  = Pattern.compile(
            "\\bR[-\\s]?(22|32|407C|410A|454B|454A|452A|290)\\b");
    private static final Pattern DATE_PATTERN    = Pattern.compile(
            "[0-9]{1,4}[/\\-][0-9]{1,4}([/\\-][0-9]{1,4})?");

    // ── Main parse entry point ───────────────────────────────────────────────

    /**
     * Parses raw OCR text and returns a NameplateCandidates POJO.
     * Never returns null — all fields may be null inside the POJO.
     */
    public static NameplateCandidates parse(String rawText) {
        NameplateCandidates out = new NameplateCandidates();
        out.rawText = rawText;

        if (rawText == null || rawText.trim().isEmpty()) {
            return out;
        }

        String[] lines = rawText.split("\\r?\\n");
        // Trim each line, remove blanks
        java.util.List<String> trimmed = new java.util.ArrayList<>();
        for (String l : lines) {
            String t = l.trim();
            if (!t.isEmpty()) trimmed.add(t);
        }
        String[] cleanLines = trimmed.toArray(new String[0]);

        // Model
        String model = extractByLabel(cleanLines,
                new String[]{"MODEL NUMBER", "MODEL NO", "MOD NO", "MODEL", "M/N", "MOD."},
                ALNUM_CODE);
        out.model = isPlausibleCode(model) ? model : null;

        // Serial
        String serial = extractByLabel(cleanLines,
                new String[]{"SERIAL NUMBER", "SERIAL NO", "SER NO", "SERIAL", "S/N", "SER."},
                ALNUM_CODE);
        out.serial = isPlausibleCode(serial) ? serial : null;

        // Manufacturer — at least 3 non-punctuation chars
        String mfr = extractByLabel(cleanLines,
                new String[]{"MANUFACTURED BY", "MANUFACTURER", "MADE BY", "MFG BY", "MFR"},
                Pattern.compile("[A-Za-z][A-Za-z\\s&.,\\-]{1,40}"));
        if (mfr != null && mfr.trim().replaceAll("\\.+$", "").length() >= 3) {
            out.manufacturer = mfr.trim();
        }

        // Refrigerant — try label first, then self-identifying R-code
        String ref = extractByLabel(cleanLines,
                new String[]{"REFRIGERANT", "REFRIG", "FACTORY CHARGE OF"},
                REFRIGERANT_LABEL_PATTERN);
        if (ref == null) {
            ref = extractByPattern(rawText, REFRIGERANT_SELF_PATTERN);
        }
        out.refrigerant = ref;

        // Voltage
        String voltage = extractByLabel(cleanLines,
                new String[]{"VOLTAGE", "VOLTS", "VOLT"},
                VOLTAGE_PATTERN);
        out.voltage = isPlausibleVoltage(voltage) ? voltage : null;

        // Phase
        out.phase = extractByLabel(cleanLines,
                new String[]{"PHASE", "PH"},
                Pattern.compile("\\b(1|3)\\s*(PH|~)?\\b|\\bSINGLE\\s*PHASE\\b|\\bTHREE\\s*PHASE\\b",
                        Pattern.CASE_INSENSITIVE));

        // SEER — only on lines that don't also contain SEER2
        String seer = null;
        for (String line : cleanLines) {
            String up = line.toUpperCase();
            if (up.contains("SEER") && !up.contains("SEER2") && !up.contains("SEER 2")) {
                Matcher m = NUMBER.matcher(line.substring(up.indexOf("SEER") + 4));
                if (m.find()) { seer = m.group().trim(); break; }
            }
        }
        out.seer = seer;

        // Tons — must have explicit label, just a number isn't enough
        out.tons = extractByLabel(cleanLines,
                new String[]{"NOMINAL TONS", "TONS", "TON"},
                NUMBER);

        // Manufacture date
        out.manufactureDate = extractByLabel(cleanLines,
                new String[]{"DATE OF MFG", "MANUFACTURE DATE", "MFG DATE", "DATE CODE", "MFD"},
                DATE_PATTERN);

        return out;
    }

    // ── Normalization ────────────────────────────────────────────────────────

    /**
     * Normalizes parsed candidates to canonical display values.
     *
     * Rules:
     *  - rawText is NEVER modified — only the typed fields (refrigerant, phase, voltage)
     *  - Only normalize when the match is unambiguous
     *  - Ambiguous values (e.g., bare "1" without context) are left as-is
     *
     * @param candidates  result of parse() — may be mutated in-place
     * @return the same candidates object (for chaining convenience)
     */
    public static NameplateCandidates normalize(NameplateCandidates candidates) {
        if (candidates == null) return null;

        candidates.refrigerant = normalizeRefrigerant(candidates.refrigerant);
        candidates.phase        = normalizePhase(candidates.phase);
        candidates.voltage      = normalizeVoltage(candidates.voltage);
        // rawText is intentionally untouched
        return candidates;
    }

    private static String normalizeRefrigerant(String value) {
        if (value == null) return null;
        String up = value.trim().toUpperCase().replaceAll("\\s+", "");
        // Remove existing dash to normalise comparison
        String noDash = up.replace("-", "");
        if (noDash.equals("R410A") || up.equals("410A")) return "R-410A";
        if (noDash.equals("R22")   || up.equals("22"))    return "R-22";
        if (noDash.equals("R32")   || up.equals("32"))    return "R-32";
        if (noDash.equals("R134A") || up.equals("134A"))  return "R-134A";
        if (noDash.equals("R407C") || up.equals("407C"))  return "R-407C";
        if (noDash.equals("R454B") || up.equals("454B"))  return "R-454B";
        if (noDash.equals("R454A") || up.equals("454A"))  return "R-454A";
        if (noDash.equals("R452A") || up.equals("452A"))  return "R-452A";
        // Already in canonical form or unrecognised — return as-is
        return value;
    }

    private static String normalizePhase(String value) {
        if (value == null) return null;
        String up = value.trim().toUpperCase().replaceAll("\\s+", " ").replaceAll("-", " ").trim();
        // Three-phase patterns
        if (up.equals("3") || up.equals("3 PH") || up.equals("3PH")
                || up.equals("THREE PHASE") || up.equals("3 PHASE")
                || up.equals("TRIFASICO") || up.equals("TRIFÁSICO")) {
            return "Trifásico";
        }
        // Single-phase patterns
        if (up.equals("1") || up.equals("1 PH") || up.equals("1PH")
                || up.equals("SINGLE PHASE") || up.equals("1 PHASE")
                || up.equals("MONOFASICO") || up.equals("MONOFÁSICO")) {
            return "Monofásico";
        }
        // Return as-is if unrecognised (e.g., tilde "~" annotations)
        return value;
    }

    private static String normalizeVoltage(String value) {
        if (value == null) return null;
        // Collapse spaces around slashes/dashes and append V if missing
        String v = value.trim().toUpperCase()
                .replaceAll("\\s*[/]\\s*", "/")
                .replaceAll("\\s*[-]\\s*", "/")  // treat 208-230 like 208/230
                .replaceAll("[Vv]$", "");          // strip trailing V before we re-add
        // Normalise known dual-voltage strings
        if (v.equals("208/230") || v.equals("208/240")) return v + "V";
        // Known single voltages
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{2,3})$").matcher(v);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(1));
            if (n == 115 || n == 208 || n == 220 || n == 230 || n == 240 || n == 460 || n == 480) {
                return n + "V";
            }
        }
        // Already has V suffix or unrecognised — return original trimmed
        return value.trim();
    }
}
