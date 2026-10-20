package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "audit_log")
public class AuditLogEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String entityType;  // "JOB", "CONTACT", "PHOTO", "INSPECTION", "MEASUREMENT"
    public long entityId;
    public long jobId;
    public String action;      // "CREATE", "UPDATE", "DELETE"
    public String field;       // nullable
    public String oldValue;    // nullable
    public String newValue;    // nullable
    public String actor;       // nullable
    public long timestamp;
    public String extra;       // nullable
}
