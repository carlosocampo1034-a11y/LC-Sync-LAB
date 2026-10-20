package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "inspection_items")
public class InspectionItemEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long jobId;
    public String area;      // "INDOOR" or "OUTDOOR"
    public String nombre;    // item name
    public String estado;    // "PASS", "ISSUE", "NOT_TESTED", "NA"
    public String hallazgo;  // nullable
    public String resolucion; // nullable
}
