package com.lc.technician;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.JobEntity;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * Instrumented tests for technicianId assignment persistence in Room.
 *
 * Requires a connected device or emulator:
 * ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4.class)
public class AssignmentPersistenceTest {

    private LcDatabase db;

    @Before
    public void createDb() {
        db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(), LcDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void closeDb() {
        if (db != null) db.close();
    }

    @Test
    public void assignTechnicianPersists() {
        // Insert a job with no technician
        JobEntity job = new JobEntity();
        job.id          = 9001L;
        job.cliente     = "Test Cliente";
        job.estado      = "PENDIENTE";
        job.technicianId = null;
        db.jobDao().insert(job);

        // Assign technician
        db.jobDao().updateTechnicianId(9001L, "Carlos Ocampo");

        // Verify persistence
        JobEntity loaded = db.jobDao().getJobById(9001L);
        assertNotNull("Job should exist after insert", loaded);
        assertEquals("technicianId must be persisted", "Carlos Ocampo", loaded.technicianId);
    }

    @Test
    public void unassignLeavesNullTechnicianId() {
        // Insert a job with a technician
        JobEntity job = new JobEntity();
        job.id          = 9002L;
        job.cliente     = "Test Cliente 2";
        job.estado      = "PENDIENTE";
        job.technicianId = "Carlos Ocampo";
        db.jobDao().insert(job);

        // Unassign
        db.jobDao().updateTechnicianId(9002L, null);

        // Verify
        JobEntity loaded = db.jobDao().getJobById(9002L);
        assertNotNull("Job should still exist after unassign", loaded);
        assertNull("technicianId should be null after unassign", loaded.technicianId);
    }

    @Test
    public void reassignOverwritesPreviousTechnician() {
        // Insert with first technician
        JobEntity job = new JobEntity();
        job.id          = 9003L;
        job.estado      = "PENDIENTE";
        job.technicianId = "Juan Pérez";
        db.jobDao().insert(job);

        // Reassign to different technician
        db.jobDao().updateTechnicianId(9003L, "María Rodríguez");

        JobEntity loaded = db.jobDao().getJobById(9003L);
        assertEquals("technicianId must reflect reassignment",
                "María Rodríguez", loaded.technicianId);
    }

    @Test
    public void technicianIdSurvivesJobStatusChange() {
        JobEntity job = new JobEntity();
        job.id          = 9004L;
        job.estado      = "PENDIENTE";
        job.technicianId = "Carlos Ocampo";
        db.jobDao().insert(job);

        // Change status (should not affect technicianId)
        db.jobDao().updateEstado(9004L, "COMPLETADO");

        JobEntity loaded = db.jobDao().getJobById(9004L);
        assertEquals("technicianId must survive status change",
                "Carlos Ocampo", loaded.technicianId);
    }
}
