package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "job_events")
public class JobEventEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long jobId;
    public String tipo;
    public String descripcion;
    public long timestamp;
}
