package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.lc.technician.model.JobPartEntity;
import java.util.List;

@Dao
public interface JobPartDao {

    @androidx.room.Update
    void update(JobPartEntity jp);

    /** Solo los vigentes (los anulados se conservan para auditoría y sincronización). */
    @Query("SELECT * FROM job_parts WHERE jobId = :jobId AND anulado = 0 ORDER BY timestamp ASC")
    List<JobPartEntity> getByJob(long jobId);

    @Query("SELECT * FROM job_parts WHERE syncState = 0")
    List<JobPartEntity> pendientes();

    @Query("UPDATE job_parts SET syncState = 2 WHERE id IN (:ids) AND syncState = 0")
    int marcarEnTransito(List<Long> ids);

    @Query("UPDATE job_parts SET syncState = 1 WHERE id IN (:ids) AND syncState = 2")
    int confirmar(List<Long> ids);

    @Query("UPDATE job_parts SET syncState = 3 WHERE id IN (:ids) AND syncState = 2")
    int rechazar(List<Long> ids);

    @Query("UPDATE job_parts SET syncState = 0 WHERE syncState = 2")
    int liberarEnTransito();

    @Query("SELECT COUNT(*) FROM job_parts WHERE syncState = 0")
    int countPendientes();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(JobPartEntity jobPart);

    @Delete
    void delete(JobPartEntity jobPart);

    @Query("DELETE FROM job_parts WHERE jobId IN (:jobIds)")
    void deleteByJobIds(java.util.List<Long> jobIds);
}
