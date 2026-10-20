package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.lc.technician.model.JobEntity;
import java.util.List;

@Dao
public interface JobDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(JobEntity job);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertAll(List<JobEntity> jobs);

    @Query("SELECT * FROM jobs ORDER BY id ASC")
    List<JobEntity> getAll();

    @Query("SELECT COUNT(*) FROM jobs")
    int count();

    @Query("UPDATE jobs SET estado = :estado, synced = 0 WHERE id = :id")
    void updateEstado(long id, String estado);

    @Query("UPDATE jobs SET estado = :estado, updatedAt = :updatedAt, synced = 0 WHERE id = :id")
    void updateEstadoAndTime(long id, String estado, long updatedAt);

    @Query("UPDATE jobs SET notas = :notas, synced = 0 WHERE id = :id")
    void updateNotas(long id, String notas);

    /** Notas editadas sobre una versión ya superada: se envían con la base que vio el técnico (el servidor decide). */
    @Query("UPDATE jobs SET notas = :notas, synced = 0, updatedAt = :updatedAt, serverVersion = :base WHERE id = :id")
    void updateNotasConBase(long id, String notas, long updatedAt, Integer base);

    @Query("SELECT COALESCE(MAX(id), 0) FROM jobs WHERE id < 8000000000000")
    long getMaxJobId();  // solo ids locales: desde 8e12 el rango es del servidor (sync v2)

    @Query("SELECT * FROM jobs WHERE customerId IS NULL OR customerId = 0")
    List<JobEntity> getJobsSinCustomer();

    @Query("UPDATE jobs SET customerId = :cid, synced = 0 WHERE id = :jobId")
    void updateCustomerId(long jobId, long cid);

    @Query("DELETE FROM jobs WHERE id IN (:ids)")
    void deleteByIds(java.util.List<Long> ids);

    @Query("SELECT * FROM jobs WHERE customerId = :customerId ORDER BY updatedAt DESC")
    List<JobEntity> getJobsByCustomer(long customerId);

    @Query("SELECT * FROM jobs WHERE estado IN ('PENDIENTE', 'EN RUTA') ORDER BY hora ASC")
    List<JobEntity> getJobsPendientes();

    @Query("SELECT * FROM jobs WHERE id = :id LIMIT 1")
    JobEntity getJobById(long id);

    @Query("UPDATE jobs SET technicianId = :technicianId, synced = 0 WHERE id = :jobId")
    void updateTechnicianId(long jobId, String technicianId);

    @Query("SELECT * FROM jobs WHERE equipmentId = :equipmentId AND id != :currentJobId ORDER BY updatedAt DESC LIMIT 10")
    List<JobEntity> getPreviousJobsForEquipment(long equipmentId, long currentJobId);

    @Query("SELECT * FROM jobs WHERE technicianId = :technicianId OR technicianId IS NULL OR technicianId = '' ORDER BY id ASC")
    List<JobEntity> getJobsForTechnician(String technicianId);

    @Query("SELECT * FROM jobs WHERE synced = 0 ORDER BY id ASC LIMIT 50")
    List<JobEntity> getUnsynced();

    @Query("UPDATE jobs SET synced = 1, lastSyncedAt = :ts WHERE id = :id")
    void markSynced(long id, long ts);

    // ── Sincronización segura ──────────────────────────────────────────────
    // synced: 0 = pendiente, 1 = confirmado por el servidor, 2 = en tránsito (enviado, sin confirmar).
    // Toda escritura local vuelve a 0; así un cambio hecho durante el envío no se da por confirmado.

    /** Marca como en tránsito los trabajos del lote que siguen pendientes. */
    @Query("UPDATE jobs SET synced = 2 WHERE id IN (:ids) AND synced = 0")
    int marcarEnTransito(java.util.List<Long> ids);

    /** Confirma solo los ids aceptados que no se modificaron mientras estaban en tránsito. */
    @Query("UPDATE jobs SET synced = 1, lastSyncedAt = :ts WHERE id IN (:ids) AND synced = 2")
    int confirmarSincronizados(java.util.List<Long> ids, long ts);

    /** Sync v2: confirma exactamente la versión enviada (mismo updatedAt) y guarda la versión del servidor. */
    @Query("UPDATE jobs SET synced = 1, lastSyncedAt = :ts, serverVersion = :version "
            + "WHERE id = :id AND synced = 2 AND COALESCE(updatedAt, id) = :clientUpdatedAt")
    int confirmarVersion(long id, long clientUpdatedAt, int version, long ts);

    /** Sync v2: el servidor rechazó el trabajo. Se conserva (synced = 3) y no se reenvía hasta editarlo. */
    @Query("UPDATE jobs SET synced = 3 WHERE id = :id AND synced = 2")
    int marcarRechazado(long id);

    @Query("SELECT COUNT(*) FROM jobs WHERE synced = 3")
    int countRechazados();

    @Query("SELECT COUNT(*) FROM jobs WHERE synced = 0")
    int countPendientesSinEnviar();

    @Query("SELECT COALESCE(MAX(id), 0) FROM jobs WHERE id >= :desde AND id < :hasta")
    long getMaxIdEnRango(long desde, long hasta);

    /** Lo que quedó en tránsito sin confirmar vuelve a pendiente (error, timeout, rechazo, cierre). */
    @Query("UPDATE jobs SET synced = 0 WHERE synced = 2")
    int liberarEnTransito();

    @Query("SELECT COUNT(*) FROM jobs WHERE synced != 1")
    int countUnsynced();
}
