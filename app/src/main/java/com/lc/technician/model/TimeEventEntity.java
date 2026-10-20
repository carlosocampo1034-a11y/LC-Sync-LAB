package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "time_events")
public class TimeEventEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long jobId;
    public String tipo;
    public long timestamp;
    public String nota;
}
