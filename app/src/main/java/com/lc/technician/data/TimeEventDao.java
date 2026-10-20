package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import com.lc.technician.model.TimeEventEntity;
import java.util.List;

@Dao
public interface TimeEventDao {

    @Query("SELECT * FROM time_events WHERE jobId = :jobId ORDER BY timestamp ASC")
    List<TimeEventEntity> getForJob(long jobId);

    @Query("SELECT * FROM time_events WHERE jobId = :jobId AND tipo = :tipo ORDER BY timestamp DESC LIMIT 1")
    TimeEventEntity getLatestOfType(long jobId, String tipo);

    @Insert
    long insert(TimeEventEntity e);

    @Delete
    void delete(TimeEventEntity e);

    @Query("DELETE FROM time_events WHERE jobId = :jobId AND tipo = :tipo")
    void deleteOfType(long jobId, String tipo);
}
