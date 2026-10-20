package com.lc.technician;

import com.lc.technician.ocr.NameplateCandidates;
import com.lc.technician.ocr.NameplateParser;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Unit tests for NameplateParser — pure function, no Android context needed.
 */
public class NameplateParserTest {

    // ── Test 1: Full-word labels ───────────────────────────────────────────

    @Test
    public void test01_fullLabels_modelAndSerial() {
        String text = "MODEL: XYZ-100\nSERIAL: ABC123456";
        NameplateCandidates c = NameplateParser.parse(text);
        assertEquals("XYZ-100",   c.model);
        assertEquals("ABC123456", c.serial);
    }

    // ── Test 2: Short-form labels ──────────────────────────────────────────

    @Test
    public void test02_shortLabels_modelAndSerial() {
        String text = "M/N XYZ-100\nS/N ABC123456";
        NameplateCandidates c = NameplateParser.parse(text);
        assertEquals("XYZ-100",   c.model);
        assertEquals("ABC123456", c.serial);
    }

    // ── Test 3: Refrigerant with label ────────────────────────────────────

    @Test
    public void test03_refrigerant_withLabel() {
        String text = "REFRIGERANT R-410A\nVOLTS 208/230";
        NameplateCandidates c = NameplateParser.parse(text);
        assertNotNull(c.refrigerant);
        assertTrue("Expected refrigerant to contain 410A: " + c.refrigerant,
                c.refrigerant.contains("410A") || c.refrigerant.contains("410a"));
    }

    // ── Test 4: Refrigerant self-identifying (no explicit label) ──────────

    @Test
    public void test04_refrigerant_selfIdentifying() {
        String text = "R410A 12 oz factory charge";
        NameplateCandidates c = NameplateParser.parse(text);
        assertNotNull("R410A should be detected without explicit label", c.refrigerant);
        assertTrue(c.refrigerant.toUpperCase().contains("410A"));
    }

    // ── Test 5: Voltage detection ─────────────────────────────────────────

    @Test
    public void test05_voltage() {
        String text = "VOLTAGE 208/230V\nPHASE 1";
        NameplateCandidates c = NameplateParser.parse(text);
        assertNotNull(c.voltage);
        assertTrue("Expected voltage to contain 208 or 230: " + c.voltage,
                c.voltage.contains("208") || c.voltage.contains("230"));
    }

    // ── Test 6: SEER value ────────────────────────────────────────────────

    @Test
    public void test06_seer() {
        String text = "MODEL ABC1234\nSEER 16.0\nVOLTS 230";
        NameplateCandidates c = NameplateParser.parse(text);
        assertNotNull("SEER should be detected", c.seer);
        assertEquals("16.0", c.seer);
    }

    // ── Test 7: Tons with explicit label ──────────────────────────────────

    @Test
    public void test07_tons_withLabel() {
        String text = "MODEL NO. ABC1234\nTONS 3\nSERIAL NO. XYZ99887";
        NameplateCandidates c = NameplateParser.parse(text);
        assertNotNull("TONS should be detected when labeled", c.tons);
        assertEquals("3", c.tons);
    }

    // ── Test 8: Empty string → all fields null ────────────────────────────

    @Test
    public void test08_emptyInput_allNull() {
        NameplateCandidates c = NameplateParser.parse("");
        assertNull(c.model);
        assertNull(c.serial);
        assertNull(c.manufacturer);
        assertNull(c.refrigerant);
        assertNull(c.voltage);
        assertNull(c.seer);
        assertNull(c.tons);
        assertNull(c.phase);
        assertNull(c.manufactureDate);
        // rawText must always be set (even to empty string, not null)
        assertNotNull(c.rawText);
    }

    // ── Test 9: Garbage text → all null, rawText preserved ────────────────

    @Test
    public void test09_garbageInput_allNullRawPreserved() {
        String garbage = "!@#$% asdf zxcv 123 ??? ---";
        NameplateCandidates c = NameplateParser.parse(garbage);
        assertNull(c.model);
        assertNull(c.serial);
        assertNull(c.tons);
        assertEquals(garbage, c.rawText);
    }

    // ── Test 10: Parser never modifies Equipment ──────────────────────────

    @Test
    public void test10_parserDoesNotTouchEquipment() {
        // NameplateParser only produces NameplateCandidates.
        // This test verifies the return type and that calling parse() twice
        // with the same input produces independent objects (no shared state).
        String text = "MODEL: ABC1234\nSEERIAL: XYZ9876";
        NameplateCandidates a = NameplateParser.parse(text);
        NameplateCandidates b = NameplateParser.parse(text);

        // They are separate instances
        assertNotSame(a, b);
        // Neither is null
        assertNotNull(a);
        assertNotNull(b);
        // rawText preserved in both
        assertEquals(text, a.rawText);
        assertEquals(text, b.rawText);
    }

    // ── Test 11: SEER2 should not bleed into SEER ────────────────────────

    @Test
    public void test11_seer2_doesNotContaminateSeer() {
        // Only a SEER2 label present — no plain "SEER" line on its own
        String text = "MODEL NO. COOL5000\nSEER2 15.2\nSERIAL NO. ABCD1234";
        NameplateCandidates c = NameplateParser.parse(text);
        // SEER field should be null because there's no plain SEER label
        // (SEER2 lines must not match the SEER extractor)
        assertNull("SEER should be null when only SEER2 is present", c.seer);
    }

    // ── Test 12: Manufacture date ─────────────────────────────────────────

    @Test
    public void test12_manufactureDate() {
        String text = "MODEL NO. XA1000\nS/N 12345678\nMFG DATE 2019-06";
        NameplateCandidates c = NameplateParser.parse(text);
        assertNotNull("Manufacture date should be detected", c.manufactureDate);
        assertTrue(c.manufactureDate.contains("2019"));
    }

    // ── Normalization Tests ────────────────────────────────────────────────

    @Test
    public void test13_normalize_refrigerant_R410A() {
        NameplateCandidates c = new NameplateCandidates();
        c.refrigerant = "R410A";
        c.rawText = "REFRIGERANT R410A";
        NameplateParser.normalize(c);
        assertEquals("R-410A", c.refrigerant);
        // rawText must be unchanged
        assertEquals("REFRIGERANT R410A", c.rawText);
    }

    @Test
    public void test14_normalize_refrigerant_withSpace() {
        NameplateCandidates c = new NameplateCandidates();
        c.refrigerant = "R 410A";
        c.rawText = "R 410A";
        NameplateParser.normalize(c);
        assertEquals("R-410A", c.refrigerant);
        assertEquals("R 410A", c.rawText);
    }

    @Test
    public void test15_normalize_phase_singlePhrase() {
        NameplateCandidates c = new NameplateCandidates();
        c.phase = "SINGLE PHASE";
        c.rawText = "PHASE SINGLE PHASE";
        NameplateParser.normalize(c);
        assertEquals("Monofásico", c.phase);
        assertEquals("PHASE SINGLE PHASE", c.rawText);
    }

    @Test
    public void test16_normalize_phase_threePhrase() {
        NameplateCandidates c = new NameplateCandidates();
        c.phase = "THREE PHASE";
        c.rawText = "PHASE THREE PHASE";
        NameplateParser.normalize(c);
        assertEquals("Trifásico", c.phase);
        assertEquals("PHASE THREE PHASE", c.rawText);
    }

    @Test
    public void test17_normalize_phase_3PH() {
        NameplateCandidates c = new NameplateCandidates();
        c.phase = "3 PH";
        c.rawText = "PHASE 3 PH";
        NameplateParser.normalize(c);
        assertEquals("Trifásico", c.phase);
    }

    @Test
    public void test18_normalize_voltage_dualVoltage() {
        NameplateCandidates c = new NameplateCandidates();
        c.voltage = "208/230";
        c.rawText = "VOLTS 208/230";
        NameplateParser.normalize(c);
        assertEquals("208/230V", c.voltage);
        assertEquals("VOLTS 208/230", c.rawText);
    }

    @Test
    public void test19_normalize_voltage_230() {
        NameplateCandidates c = new NameplateCandidates();
        c.voltage = "230";
        c.rawText = "VOLTS 230";
        NameplateParser.normalize(c);
        assertEquals("230V", c.voltage);
    }

    @Test
    public void test20_normalize_alreadyCanonical_refrigerant() {
        // Already correct — should not be double-processed
        NameplateCandidates c = new NameplateCandidates();
        c.refrigerant = "R-410A";
        c.rawText = "REFRIGERANT R-410A";
        NameplateParser.normalize(c);
        // After normalization, R-410A -> noDash = "R410A" -> returns "R-410A" — still correct
        assertEquals("R-410A", c.refrigerant);
        assertEquals("REFRIGERANT R-410A", c.rawText);
    }

    @Test
    public void test21_normalize_nullInputs_noException() {
        NameplateCandidates c = new NameplateCandidates();
        c.refrigerant = null;
        c.phase = null;
        c.voltage = null;
        c.rawText = "some text";
        NameplateParser.normalize(c);
        assertNull(c.refrigerant);
        assertNull(c.phase);
        assertNull(c.voltage);
        assertEquals("some text", c.rawText);
    }

    @Test
    public void test22_normalize_rawText_neverModified() {
        // Parse then normalize — rawText must survive intact
        String originalRaw = "MODEL XYZ\nR410A\nVOLTS 208/230\nPHASE 1";
        NameplateCandidates c = NameplateParser.parse(originalRaw);
        NameplateParser.normalize(c);
        assertEquals("rawText must not be modified by normalize()", originalRaw, c.rawText);
    }
}
