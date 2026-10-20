package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "sync_state")
public class SyncStateEntity {

    @PrimaryKey(autoGenerate = true)
    public int id;
    public String entityType;    // WORK_ITEM | TECHNICIAN | JOB
    public Long lastSyncAt;      // timestamp of last successful sync for this type
    public String lastError;     // last error message, null if no error
}
