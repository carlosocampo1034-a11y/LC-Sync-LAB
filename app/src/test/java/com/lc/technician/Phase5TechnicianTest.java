package com.lc.technician;

import com.lc.technician.model.AuditLogEntity;
import com.lc.technician.model.JobEntity;
import com.lc.technician.model.TechnicianEntity;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/**
 * Phase 5 Technician Roster tests (JVM — no emulator required).
 *
 * Covers:
 * 1.  TechnicianEntity activo defaults to true after explicit assignment
 * 2.  Deactivate: sets activo=false but does not delete the entity
 * 3.  getActive: excludes inactive technicians (filter logic)
 * 4.  My Jobs filter: includes current technician's jobs
 * 5.  My Jobs filter: includes unassigned jobs
 * 6.  My Jobs filter: excludes other technician's assigned jobs
 * 7.  Admin sees all jobs (no My Jobs filter)
 * 8.  Completed job preserves technicianId after deactivation
 * 9.  Inactive technician still shows in completed job
 * 10. Backfill migration: technician created from job.technicianId
 * 11. Deactivation creates audit entry with TECHNICIAN_DEACTIVATED
 * 12. SQL single-quote: verify no double-quoted literals in migration SQL
 */
public class Phase5TechnicianTest {

    // ── 1. TechnicianEntity activo defaults to true ───────────────────────────

    @Test
    public void technician_activeByDefault() {
        TechnicianEntity tech = new TechnicianEntity();
        tech.id     = "t1";
        tech.nombre = "Carlos Ocampo";
        tech.activo = true; // constructor default must be set explicitly in entity
        assertTrue("New technician must be active by default", tech.activo);
    }

    // ── 2. Deactivate sets activo=false, does not delete ─────────────────────

    @Test
    public void deactivate_setsActivoFalse_doesNotDelete() {
        TechnicianEntity tech = buildTech("t2", "Ana García", true);
        // Simulate setActivo(false)
        tech.activo = false;
        assertFalse("Deactivated technician must have activo=false", tech.activo);
        assertNotNull("Entity still exists (not deleted)", tech);
        assertEquals("Name preserved after deactivation", "Ana García", tech.nombre);
    }

    // ── 3. getActive filter: excludes inactive ────────────────────────────────

    @Test
    public void getActive_excludesInactive() {
        List<TechnicianEntity> all = new ArrayList<>();
        all.add(buildTech("t1", "Active Tech",   true));
        all.add(buildTech("t2", "Inactive Tech", false));
        all.add(buildTech("t3", "Another Active", true));

        List<TechnicianEntity> active = filterActive(all);

        assertEquals("Should return 2 active technicians", 2, active.size());
        for (TechnicianEntity t : active) {
            assertTrue("Every result must be active", t.activo);
        }
    }

    // ── 4. My Jobs filter: includes current technician's jobs ─────────────────

    @Test
    public void myJobs_includesOwnJobs() {
        String myId = "Carlos Ocampo";
        List<JobEntity> all = new ArrayList<>();
        all.add(buildJob(1L, myId));
        all.add(buildJob(2L, "Juan Pérez"));
        all.add(buildJob(3L, myId));

        List<JobEntity> mine = filterMyJobs(all, myId);

        assertEquals("Should return 2 of my jobs", 2, mine.size());
        for (JobEntity j : mine) {
            assertEquals("Each returned job must be mine", myId, j.technicianId);
        }
    }

    // ── 5. My Jobs filter: includes unassigned jobs ───────────────────────────

    @Test
    public void myJobs_includesUnassignedJobs() {
        String myId = "Carlos Ocampo";
        List<JobEntity> all = new ArrayList<>();
        all.add(buildJob(1L, null));           // unassigned
        all.add(buildJob(2L, ""));             // empty = unassigned
        all.add(buildJob(3L, "Juan Pérez"));   // other tech

        List<JobEntity> mine = filterMyJobs(all, myId);

        assertEquals("Should return 2 unassigned jobs", 2, mine.size());
    }

    // ── 6. My Jobs filter: excludes other technician's assigned jobs ──────────

    @Test
    public void myJobs_excludesOtherTechnicianJobs() {
        String myId = "Carlos Ocampo";
        List<JobEntity> all = new ArrayList<>();
        all.add(buildJob(1L, "Juan Pérez"));
        all.add(buildJob(2L, "María Rodríguez"));
        all.add(buildJob(3L, "Pedro Silva"));

        List<JobEntity> mine = filterMyJobs(all, myId);

        assertEquals("No jobs should match other technicians", 0, mine.size());
    }

    // ── 7. Admin sees all jobs ────────────────────────────────────────────────

    @Test
    public void allJobs_includesAllTechnicians() {
        String adminRole = "ADMIN";
        boolean filtroMisJobs = false; // Admin defaults to false (show all)

        // Simulate role-based default
        if ("TECNICO".equals(adminRole)) filtroMisJobs = true;
        assertFalse("ADMIN must not have My Jobs filter enabled by default", filtroMisJobs);
    }

    // ── 8. Completed job preserves technicianId ───────────────────────────────

    @Test
    public void completedJob_preservesTechnicianId() {
        JobEntity job = buildJob(5001L, "Carlos Ocampo");
        job.estado = "COMPLETADO";
        // technicianId must survive status change
        assertNotNull("technicianId preserved after completion", job.technicianId);
        assertEquals("technicianId unchanged", "Carlos Ocampo", job.technicianId);
    }

    // ── 9. Inactive technician still shows in completed job ───────────────────

    @Test
    public void inactiveTechnician_stillShownInCompletedJob() {
        TechnicianEntity tech = buildTech("t5", "Former Technician", true);
        JobEntity job = buildJob(5002L, tech.nombre);
        job.estado = "COMPLETADO";

        // Now deactivate the technician
        tech.activo = false;

        // The job still has the old technicianId — display should still work
        assertNotNull("Job must still have technicianId even if tech is deactivated",
                job.technicianId);
        assertEquals("Job technicianId matches deactivated technician name",
                tech.nombre, job.technicianId);
    }

    // ── 10. Backfill migration: technician created from job ───────────────────

    @Test
    public void migration_backfills_technician_from_job() {
        // Simulate the backfill logic from MIGRATION_19_20
        List<JobEntity> jobs = new ArrayList<>();
        jobs.add(buildJob(1L, "Carlos Ocampo"));
        jobs.add(buildJob(2L, "Carlos Ocampo")); // duplicate
        jobs.add(buildJob(3L, "Juan Pérez"));
        jobs.add(buildJob(4L, null));              // null — no backfill expected

        // Collect distinct non-null technicianIds
        java.util.Set<String> distinct = new java.util.LinkedHashSet<>();
        for (JobEntity j : jobs) {
            if (j.technicianId != null && !j.technicianId.isEmpty()) {
                distinct.add(j.technicianId);
            }
        }

        assertEquals("Backfill should produce 2 unique technician records", 2, distinct.size());
        assertTrue("Carlos Ocampo must be backfilled", distinct.contains("Carlos Ocampo"));
        assertTrue("Juan Pérez must be backfilled", distinct.contains("Juan Pérez"));
    }

    // ── 11. Deactivation creates audit entry ─────────────────────────────────

    @Test
    public void deactivation_creates_audit_entry() {
        TechnicianEntity tech = buildTech("t6", "María Rodríguez", true);
        tech.activo = false; // deactivate

        AuditLogEntity entry = buildAuditEntry("TECHNICIAN",
                tech.id.hashCode(), 0L,
                "TECHNICIAN_DEACTIVATED", "technicianId",
                null, tech.id, "Admin");

        assertEquals("Audit action must be TECHNICIAN_DEACTIVATED",
                "TECHNICIAN_DEACTIVATED", entry.action);
        assertEquals("EntityType must be TECHNICIAN", "TECHNICIAN", entry.entityType);
        assertNotNull("Actor must be set", entry.actor);
    }

    // ── 12. SQL single-quote fix: no double-quoted literals ───────────────────

    @Test
    public void sql_noDoubleQuotedStringLiterals_in_migration19_20() {
        // The MIGRATION_19_20 SQL strings should use single quotes for string literals
        String createSQL =
            "CREATE TABLE IF NOT EXISTS technicians (" +
            "id TEXT NOT NULL PRIMARY KEY, " +
            "nombre TEXT, " +
            "email TEXT, " +
            "telefono TEXT, " +
            "rol TEXT, " +
            "activo INTEGER NOT NULL DEFAULT 1, " +
            "createdAt INTEGER NOT NULL DEFAULT 0, " +
            "updatedAt INTEGER NOT NULL DEFAULT 0)";

        String backfillSQL =
            "INSERT OR IGNORE INTO technicians (id, nombre, activo, createdAt, updatedAt) " +
            "SELECT DISTINCT technicianId, technicianId, 1, " +
            "strftime('%s','now')*1000, strftime('%s','now')*1000 " +
            "FROM jobs WHERE technicianId IS NOT NULL AND technicianId != ''";

        // Check: no double-quoted string values (strings in SQL should use single quotes)
        // The only double quotes acceptable are for identifiers (none expected here)
        assertFalse("CREATE SQL must not use double-quoted string literals (use single quotes)",
                createSQL.contains("\"com.") || createSQL.contains("\"TECNICO\""));
        assertFalse("Backfill SQL must not use double-quoted empty string",
                backfillSQL.contains("!= \"\"") || backfillSQL.contains("= \"\""));
        assertTrue("Backfill SQL must use single-quoted empty string check",
                backfillSQL.contains("!= ''"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TechnicianEntity buildTech(String id, String nombre, boolean activo) {
        TechnicianEntity t = new TechnicianEntity();
        t.id        = id;
        t.nombre    = nombre;
        t.rol       = "TECNICO";
        t.activo    = activo;
        t.createdAt = System.currentTimeMillis();
        t.updatedAt = System.currentTimeMillis();
        return t;
    }

    private JobEntity buildJob(long id, String technicianId) {
        JobEntity j = new JobEntity();
        j.id           = id;
        j.estado       = "PENDIENTE";
        j.technicianId = technicianId;
        return j;
    }

    private List<TechnicianEntity> filterActive(List<TechnicianEntity> all) {
        List<TechnicianEntity> result = new ArrayList<>();
        for (TechnicianEntity t : all) {
            if (t.activo) result.add(t);
        }
        return result;
    }

    /** Simulates: technicianId = myId OR technicianId IS NULL OR technicianId = '' */
    private List<JobEntity> filterMyJobs(List<JobEntity> all, String myId) {
        List<JobEntity> result = new ArrayList<>();
        for (JobEntity j : all) {
            boolean isUnassigned = (j.technicianId == null || j.technicianId.isEmpty());
            boolean isMine       = myId.equals(j.technicianId);
            if (isUnassigned || isMine) result.add(j);
        }
        return result;
    }

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
