package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;
import com.lc.technician.model.WorkItemEntity;
import java.util.List;

@Dao
public interface WorkItemDao {

    /** Solo los vigentes (los anulados se conservan, pero no se muestran ni suman). */
    @Query("SELECT * FROM work_items WHERE jobId = :jobId AND anulado = 0 ORDER BY timestamp ASC")
    List<WorkItemEntity> getByJob(long jobId);

    @Query("SELECT COALESCE(SUM(total), 0) FROM work_items WHERE jobId = :jobId AND anulado = 0")
    double getTotalByJob(long jobId);

    @Insert
    long insert(WorkItemEntity w);

    @Update
    void update(WorkItemEntity w);

    @Delete
    void delete(WorkItemEntity w);

    @Query("DELETE FROM work_items WHERE jobId = :jobId")
    void deleteAllByJob(long jobId);

    @Query("SELECT * FROM work_items WHERE synced = 0 ORDER BY updatedAt ASC")
    List<WorkItemEntity> getUnsynced();

    @Query("UPDATE work_items SET synced = 1, lastSyncedAt = :ts WHERE id = :id")
    void markSynced(long id, long ts);

    /** Pendientes de subir: sin enviar (0), en tránsito (2) o rechazados por el servidor (3). */
    @Query("SELECT COUNT(*) FROM work_items WHERE synced != 1")
    int countUnsynced();

    // ── Sync v2 (mismo esquema que jobs: 0 pendiente, 2 en tránsito, 1 confirmado, 3 rechazado) ──
    @Query("UPDATE work_items SET synced = 2 WHERE id IN (:ids) AND synced = 0")
    int marcarEnTransito(java.util.List<Long> ids);

    @Query("UPDATE work_items SET synced = 1, lastSyncedAt = :ts WHERE id IN (:ids) AND synced = 2")
    int confirmar(java.util.List<Long> ids, long ts);

    @Query("UPDATE work_items SET synced = 3 WHERE id IN (:ids) AND synced = 2")
    int marcarRechazados(java.util.List<Long> ids);

    @Query("UPDATE work_items SET synced = 0 WHERE synced = 2")
    int liberarEnTransito();

    @Query("SELECT COUNT(*) FROM work_items WHERE synced = 0")
    int countPendientesSinEnviar();

    @Query("SELECT COUNT(*) FROM work_items WHERE synced = 3")
    int countRechazados();
}
