package com.lc.technician.data;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import com.lc.technician.model.SyncStateEntity;

@Dao
public interface SyncStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(SyncStateEntity state);

    @Query("SELECT * FROM sync_state WHERE entityType = :type LIMIT 1")
    SyncStateEntity getByType(String type);

    @Query("UPDATE sync_state SET lastSyncAt = :ts, lastError = NULL WHERE entityType = :type")
    void markSuccess(String type, long ts);

    @Query("UPDATE sync_state SET lastError = :error WHERE entityType = :type")
    void markError(String type, String error);

    @Query("SELECT COUNT(*) FROM sync_state WHERE entityType = :type")
    int countByType(String type);
}
