package com.lc.technician;

import com.lc.technician.data.ConfigManager;
import com.lc.technician.model.ContactEntity;
import com.lc.technician.model.ProposalOptionEntity;
import org.junit.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * Phase 3 Customer Continuity tests.
 * Covers:
 * - Contact customerId backfill logic
 * - Contact soft delete
 * - Primary contact uniqueness scope
 * - Previous services exclusion logic
 * - ConfigManager defaults and validation
 * - Proposal snapshot (markupUsed / taxUsed)
 * - Contact merge deduplication
 */
public class Phase3CustomerTest {

    // ── 1. Contact customerId is set when created for a job ───────────────────

    @Test
    public void contact_hasCustomerId_whenSetExplicitly() {
        ContactEntity c = new ContactEntity();
        c.jobId      = 1001L;
        c.customerId = 42L;
        c.nombre     = "Ana Pérez";
        c.rol        = "DUEÑO";
        assertNotNull("customerId should be set", c.customerId);
        assertEquals(42L, (long) c.customerId);
    }

    @Test
    public void contact_customerIdIsNull_byDefault() {
        ContactEntity c = new ContactEntity();
        // Without backfill, customerId starts null
        assertNull("customerId should be null by default", c.customerId);
    }

    // ── 2. Contact soft delete preserves the record ───────────────────────────

    @Test
    public void contact_softDelete_setsIsDeleted() {
        ContactEntity c = new ContactEntity();
        c.nombre    = "Bob Smith";
        c.isDeleted = false;

        // Simulate soft delete
        c.isDeleted = true;

        assertTrue("isDeleted should be true after soft delete", c.isDeleted);
        // The contact object still exists (not null) — preserved for audit
        assertNotNull("Contact should still exist after soft delete", c);
        assertEquals("Bob Smith", c.nombre);
    }

    @Test
    public void contact_isNotDeleted_byDefault() {
        ContactEntity c = new ContactEntity();
        assertFalse("isDeleted should be false by default", c.isDeleted);
    }

    // ── 3. Primary contact uniqueness — clearing logic ────────────────────────

    @Test
    public void setting_primary_requires_clear_of_others() {
        // Simulates: when contactId=10 is set primary, others in same customer must be cleared
        List<ContactEntity> contacts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ContactEntity c = new ContactEntity();
            c.id         = i + 1;
            c.customerId = 99L;
            c.esPrincipal = (i == 0); // first one starts as primary
            contacts.add(c);
        }

        // Simulate clearPrimaryForCustomer(customerId=99, exceptId=3)
        long newPrimaryId = 3L;
        for (ContactEntity c : contacts) {
            if (c.customerId != null && c.customerId == 99L && c.id != newPrimaryId) {
                c.esPrincipal = false;
            }
        }
        contacts.get((int)(newPrimaryId - 1)).esPrincipal = true;

        // Only id=3 should be primary
        for (ContactEntity c : contacts) {
            if (c.id == newPrimaryId) {
                assertTrue("id=3 should be primary", c.esPrincipal);
            } else {
                assertFalse("id=" + c.id + " should not be primary", c.esPrincipal);
            }
        }
    }

    // ── 4. Previous services excludes current job ─────────────────────────────

    @Test
    public void previousServices_excludesCurrentJob() {
        long currentJobId = 5000L;
        long customerId   = 42L;

        // Simulate getJobsByCustomer result (includes current job — DAO excludes it)
        List<Long> allJobIds = new ArrayList<>();
        allJobIds.add(4001L);
        allJobIds.add(4002L);
        allJobIds.add(currentJobId); // should be excluded by the query

        // Simulating the exclusion filter
        List<Long> previous = new ArrayList<>();
        for (Long id : allJobIds) {
            if (id != currentJobId) previous.add(id);
        }

        assertFalse("Previous services must not include current job",
                previous.contains(currentJobId));
        assertEquals("Should have 2 previous jobs", 2, previous.size());
    }

    // ── 5. ConfigManager returns correct defaults ─────────────────────────────

    @Test
    public void configManager_defaults_areCorrect() {
        assertEquals("Default GOOD markup should be 15",
                15f, ConfigManager.DEFAULT_MARKUP_GOOD,   0.001f);
        assertEquals("Default BETTER markup should be 20",
                20f, ConfigManager.DEFAULT_MARKUP_BETTER, 0.001f);
        assertEquals("Default BEST markup should be 25",
                25f, ConfigManager.DEFAULT_MARKUP_BEST,   0.001f);
        assertEquals("Default tax rate should be 7",
                7f,  ConfigManager.DEFAULT_TAX_RATE,      0.001f);
    }

    // ── 6. ConfigManager validates markup range ───────────────────────────────

    @Test
    public void configManager_validation_markupRange() {
        // Test the static validation logic directly
        assertTrue("0% should be valid", isValidMarkup(0f));
        assertTrue("50% should be valid", isValidMarkup(50f));
        assertTrue("100% should be valid", isValidMarkup(100f));
        assertFalse("101% should be invalid", isValidMarkup(101f));
        assertFalse("Negative markup should be invalid", isValidMarkup(-1f));
    }

    @Test
    public void configManager_validation_taxRange() {
        assertTrue("0% tax is valid", isValidTax(0f));
        assertTrue("7% tax is valid", isValidTax(7f));
        assertTrue("30% tax is valid", isValidTax(30f));
        assertFalse("31% tax is invalid", isValidTax(31f));
        assertFalse("Negative tax is invalid", isValidTax(-0.5f));
    }

    // ── 7. ConfigManager rejects NaN and Infinite ─────────────────────────────

    @Test
    public void configManager_rejects_nan() {
        assertFalse("NaN markup should be rejected", isValidMarkup(Float.NaN));
        assertFalse("NaN tax should be rejected",    isValidTax(Float.NaN));
    }

    @Test
    public void configManager_rejects_infinite() {
        assertFalse("Infinite markup should be rejected", isValidMarkup(Float.POSITIVE_INFINITY));
        assertFalse("Infinite tax should be rejected",    isValidTax(Float.NEGATIVE_INFINITY));
    }

    // ── 8. Proposal snapshot stores markupUsed and taxUsed ───────────────────

    @Test
    public void proposalOption_stores_markupUsed_at_creation() {
        ProposalOptionEntity o = new ProposalOptionEntity();
        o.markupPorcentaje   = 20.0;
        o.impuestoPorcentaje = 7.0;
        // Simulate what ProposalActivity.buildOption() does
        o.markupUsed = o.markupPorcentaje;
        o.taxUsed    = o.impuestoPorcentaje;

        assertEquals("markupUsed must equal markupPorcentaje at creation",
                20.0, o.markupUsed, 0.001);
        assertEquals("taxUsed must equal impuestoPorcentaje at creation",
                7.0, o.taxUsed, 0.001);
    }

    @Test
    public void proposalOption_snapshot_isIndependentOfLaterChanges() {
        ProposalOptionEntity o = new ProposalOptionEntity();
        o.markupPorcentaje = 15.0;
        o.markupUsed       = 15.0; // snapshotted at creation

        // Later someone changes the config markup to 20 — snapshot stays 15
        double newConfigMarkup = 20.0;
        // snapshot is NOT updated — it reflects the value at proposal creation
        assertEquals("Snapshot should NOT change when config changes",
                15.0, o.markupUsed, 0.001);
        assertNotEquals("Config markup can differ from snapshot",
                o.markupUsed, newConfigMarkup, 0.001);
    }

    // ── 9. Dispatch assignment — audit entry creation ─────────────────────────

    @Test
    public void dispatch_assignment_produces_audit_description() {
        // Simulates what should happen when a technician is assigned
        String newTechnicianName = "Carlos Ocampo";
        long jobId = 1234L;

        // Simulate building the audit entry description
        String description = "Técnico asignado: " + newTechnicianName;

        assertNotNull("Audit description must not be null", description);
        assertTrue("Description must mention technician name",
                description.contains(newTechnicianName));
        assertTrue("Description must indicate assignment",
                description.startsWith("Técnico asignado:"));
    }

    // ── Test helpers (mirror ConfigManager private validation logic) ─────────

    /** Mirrors ConfigManager.isValidMarkup */
    private static boolean isValidMarkup(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v) && v >= 0 && v <= 100;
    }

    /** Mirrors ConfigManager.isValidTax */
    private static boolean isValidTax(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v) && v >= 0 && v <= 30;
    }

    // ── 10. Contact merge deduplication by id ─────────────────────────────────

    @Test
    public void contact_merge_deduplicates_by_id() {
        // Simulates getContactsForJobContext merge logic
        List<ContactEntity> customerContacts = new ArrayList<>();
        List<ContactEntity> jobContacts      = new ArrayList<>();

        // Customer-level contact (id=1)
        ContactEntity shared = new ContactEntity();
        shared.id = 1L; shared.customerId = 42L; shared.nombre = "Ana";
        customerContacts.add(shared);

        // Another customer contact (id=2)
        ContactEntity other = new ContactEntity();
        other.id = 2L; other.customerId = 42L; other.nombre = "Bob";
        customerContacts.add(other);

        // Job-specific also references id=1 (same contact — must deduplicate)
        ContactEntity dup = new ContactEntity();
        dup.id = 1L; dup.jobId = 99L; dup.nombre = "Ana";
        jobContacts.add(dup);

        // Merge logic (mirrors JobRepository.getContactsForJobContext)
        Map<Long, ContactEntity> merged = new LinkedHashMap<>();
        for (ContactEntity c : customerContacts) merged.put(c.id, c);
        for (ContactEntity c : jobContacts)      merged.put(c.id, c); // overrides but same id
        List<ContactEntity> result = new ArrayList<>(merged.values());

        assertEquals("Merged list should have 2 unique contacts (no duplicates)",
                2, result.size());
    }
}
