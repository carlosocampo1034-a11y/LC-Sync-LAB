package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import com.lc.technician.model.AuditLogEntity;
import java.util.List;

@Dao
public interface AuditLogDao {
    @Insert
    void insert(AuditLogEntity entry);

    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC")
    List<AuditLogEntity> getAll();

    @Query("SELECT * FROM audit_log WHERE jobId = :jobId ORDER BY timestamp DESC")
    List<AuditLogEntity> getByJob(long jobId);

    @Query("SELECT * FROM audit_log WHERE entityType = :type ORDER BY timestamp DESC LIMIT :limit")
    List<AuditLogEntity> getByType(String type, int limit);

    @Query("DELETE FROM audit_log WHERE timestamp < :cutoff")
    int deleteOlderThan(long cutoff);
}
