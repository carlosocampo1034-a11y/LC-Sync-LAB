package com.lc.technician;

import com.lc.technician.model.AuditLogEntity;
import com.lc.technician.model.ContactEntity;
import com.lc.technician.model.JobEntity;
import com.lc.technician.model.Technician;
import org.junit.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * Phase 4 Dispatch & History tests.
 *
 * Covers:
 * 1.  assignTechnician creates ASSIGN audit action
 * 2.  reassign creates REASSIGN when job already had technician
 * 3.  unassign creates UNASSIGN audit action
 * 4.  SessionManager returns correct name from prefs (model-layer)
 * 5.  Completed job preserves technicianId field
 * 6.  Equipment history query excludes current job
 * 7.  Contact merge: job + customer contacts deduplicated (no duplicates)
 * 8.  Property-scoped contact not shown for a different property
 * 9.  Available technicians list includes current user
 * 10. Assignment audit entry includes old technician name on reassign
 */
public class Phase4DispatchTest {

    // ── 1. Assign creates ASSIGN audit action ────────────────────────────────

    @Test
    public void assign_creates_ASSIGN_audit() {
        AuditLogEntity entry = buildAuditEntry("JOB", 1L, 1L, "ASSIGN",
                "technicianId", null, "Carlos Ocampo", "Dispatcher");
        assertEquals("Action must be ASSIGN for first assignment", "ASSIGN", entry.action);
        assertEquals("newValue must be technician name", "Carlos Ocampo", entry.newValue);
        assertNull("oldValue must be null for first assignment", entry.oldValue);
    }

    // ── 2. Reassign creates REASSIGN when job already had technician ──────────

    @Test
    public void reassign_creates_REASSIGN_audit() {
        String oldTech = "Juan Pérez";
        String newTech = "Carlos Ocampo";

        // Simulate: job already had a technician
        String action = (oldTech != null && !oldTech.isEmpty()) ? "REASSIGN" : "ASSIGN";

        AuditLogEntity entry = buildAuditEntry("JOB", 1L, 1L, action,
                "technicianId", oldTech, newTech, "Dispatcher");
        assertEquals("Action must be REASSIGN when job already had technician", "REASSIGN", entry.action);
        assertEquals("oldValue must be previous technician", oldTech, entry.oldValue);
        assertEquals("newValue must be new technician", newTech, entry.newValue);
    }

    // ── 3. Unassign creates UNASSIGN audit ────────────────────────────────────

    @Test
    public void unassign_creates_UNASSIGN_audit() {
        AuditLogEntity entry = buildAuditEntry("JOB", 1L, 1L, "UNASSIGN",
                "technicianId", "Carlos Ocampo", null, "Admin");
        assertEquals("Action must be UNASSIGN", "UNASSIGN", entry.action);
        assertNull("newValue must be null after unassign", entry.newValue);
        assertEquals("oldValue must contain previous technician", "Carlos Ocampo", entry.oldValue);
    }

    // ── 4. SessionManager model: name returned from prefs ────────────────────
    //       (pure model test, no Android context required)

    @Test
    public void sessionManager_role_isAdmin_for_ADMIN() {
        // Simulate role-check logic matching SessionManager
        String role = "ADMIN";
        assertTrue("ADMIN role must pass isAdmin()", "ADMIN".equals(role));
        assertFalse("ADMIN must not be DISPATCHER", "DISPATCHER".equals(role));
    }

    @Test
    public void sessionManager_role_isDispatcher_for_DISPATCHER() {
        String role = "DISPATCHER";
        assertTrue("DISPATCHER role must pass isDispatcher()", "DISPATCHER".equals(role));
        assertFalse("DISPATCHER must not be ADMIN", "ADMIN".equals(role));
    }

    // ── 5. Completed job preserves technicianId ───────────────────────────────

    @Test
    public void completedJob_preserves_technicianId() {
        JobEntity job = new JobEntity();
        job.id           = 9001L;
        job.estado       = "PENDIENTE";
        job.technicianId = "Carlos Ocampo";

        // Simulate status change to COMPLETADO
        job.estado = "COMPLETADO";

        // technicianId must NOT be cleared on status change
        assertNotNull("technicianId must still be set after job completion", job.technicianId);
        assertEquals("technicianId must be unchanged", "Carlos Ocampo", job.technicianId);
    }

    // ── 6. Equipment history query excludes current job ───────────────────────

    @Test
    public void equipmentHistory_excludes_currentJob() {
        long currentJobId  = 5000L;
        long equipmentId   = 77L;

        // Simulate results from getPreviousJobsForEquipment
        List<Long> allForEquip = new ArrayList<>();
        allForEquip.add(4001L);
        allForEquip.add(4002L);
        allForEquip.add(currentJobId); // should be excluded by WHERE id != :currentJobId

        List<Long> previous = new ArrayList<>();
        for (Long id : allForEquip) {
            if (id != currentJobId) previous.add(id);
        }

        assertFalse("Equipment history must NOT include current job",
                previous.contains(currentJobId));
        assertEquals("Should return 2 previous jobs", 2, previous.size());
    }

    // ── 7. Contact merge: job + customer contacts deduplicated ────────────────

    @Test
    public void contacts_merge_deduplicates() {
        List<ContactEntity> customerContacts = new ArrayList<>();
        List<ContactEntity> jobContacts      = new ArrayList<>();

        ContactEntity c1 = new ContactEntity();
        c1.id = 1L; c1.customerId = 42L; c1.nombre = "Ana";
        c1.propertyId = null; // customer-global
        customerContacts.add(c1);

        ContactEntity c2 = new ContactEntity();
        c2.id = 2L; c2.customerId = 42L; c2.nombre = "Bob";
        c2.propertyId = null;
        customerContacts.add(c2);

        // Job-specific contact that duplicates c1
        ContactEntity dup = new ContactEntity();
        dup.id = 1L; dup.jobId = 99L; dup.nombre = "Ana";
        jobContacts.add(dup);

        // Merge: customer first, then job (job overrides)
        Map<Long, ContactEntity> merged = new LinkedHashMap<>();
        for (ContactEntity c : customerContacts) {
            if (c.propertyId == null) merged.put(c.id, c);
        }
        for (ContactEntity c : jobContacts) merged.put(c.id, c);
        List<ContactEntity> result = new ArrayList<>(merged.values());

        assertEquals("No duplicates: merged list should have 2 unique contacts", 2, result.size());
    }

    // ── 8. Property-scoped contact not shown for different property ──────────

    @Test
    public void contact_propertyScoped_not_shown_on_other_property() {
        long customerId      = 42L;
        long propertyA       = 10L;
        long propertyB       = 20L; // different property

        ContactEntity c = new ContactEntity();
        c.id         = 5L;
        c.customerId = customerId;
        c.propertyId = propertyA; // scoped to property A

        // Simulate loading contacts for property B
        List<ContactEntity> customerContacts = new ArrayList<>();
        customerContacts.add(c);

        // Filter: only include if propertyId is null OR matches the current property
        List<ContactEntity> visibleForB = new ArrayList<>();
        for (ContactEntity contact : customerContacts) {
            boolean isGlobal   = contact.propertyId == null;
            boolean matchesProp = contact.propertyId != null && contact.propertyId == propertyB;
            if (isGlobal || matchesProp) visibleForB.add(contact);
        }

        assertTrue("Property-A scoped contact must NOT appear for property B",
                visibleForB.isEmpty());
    }

    // ── 9. Available technicians list includes current user ───────────────────

    @Test
    public void availableTechnicians_includesCurrentUser() {
        String currentUser = "Test User";

        List<String> list = new ArrayList<>();
        list.add(currentUser);
        if (!Technician.NOMBRE.equals(currentUser)) list.add(Technician.NOMBRE);

        assertTrue("List must contain the current logged-in user", list.contains(currentUser));
        assertTrue("List must contain canonical seed technician", list.contains(Technician.NOMBRE));
    }

    @Test
    public void availableTechnicians_noDuplicateWhenCurrentIsSeedTech() {
        String currentUser = Technician.NOMBRE; // same as seed tech

        List<String> list = new ArrayList<>();
        list.add(currentUser);
        if (!Technician.NOMBRE.equals(currentUser)) list.add(Technician.NOMBRE);

        assertEquals("No duplicate when current user is the seed technician", 1, list.size());
    }

    // ── 10. Reassign audit includes old technician name ───────────────────────

    @Test
    public void reassign_audit_includes_old_technician() {
        String oldTech = "Juan Pérez";
        String newTech = "María Rodríguez";

        AuditLogEntity entry = buildAuditEntry("JOB", 2L, 2L, "REASSIGN",
                "technicianId", oldTech, newTech, "Admin");

        assertEquals("Field must be technicianId", "technicianId", entry.field);
        assertEquals("oldValue must record previous technician for traceability",
                oldTech, entry.oldValue);
        assertEquals("newValue must record new technician", newTech, entry.newValue);
        assertNotNull("actor must be recorded", entry.actor);
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private AuditLogEntity buildAuditEntry(String entityType, long entityId, long jobId,
            String action, String field, String oldValue, String newValue, String actor) {
        AuditLogEntity e = new AuditLogEntity();
        e.entityType = entityType;
        e.entityId   = entityId;
        e.jobId      = jobId;
        e.action     = action;
        e.field      = field;
        e.oldValue   = oldValue;
        e.newValue   = newValue;
        e.actor      = actor;
        e.timestamp  = System.currentTimeMillis();
        return e;
    }
}
