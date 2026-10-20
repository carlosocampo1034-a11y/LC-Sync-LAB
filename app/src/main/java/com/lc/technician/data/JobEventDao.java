package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.lc.technician.model.JobEventEntity;
import java.util.List;

@Dao
public interface JobEventDao {

    @Insert
    void insert(JobEventEntity event);

    @Query("SELECT * FROM job_events WHERE jobId = :jobId ORDER BY timestamp ASC")
    List<JobEventEntity> getEventsForJob(long jobId);

    @Query("DELETE FROM job_events WHERE id NOT IN (" +
           "SELECT MAX(id) FROM job_events GROUP BY jobId, tipo, descripcion" +
           ")")
    void deleteDuplicatesKeepingLatest();

    @Query("DELETE FROM job_events WHERE jobId IN (:jobIds)")
    void deleteByJobIds(java.util.List<Long> jobIds);
}
