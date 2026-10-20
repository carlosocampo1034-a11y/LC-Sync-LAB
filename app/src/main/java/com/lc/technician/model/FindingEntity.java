package com.lc.technician.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;

@Entity(tableName = "findings")
public class FindingEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "job_id")
    public long jobId;

    @ColumnInfo(name = "equipment_id")
    public Long equipmentId;  // nullable

    @ColumnInfo(name = "inspection_item_id")
    public Long inspectionItemId;  // nullable

    @NonNull
    @ColumnInfo(name = "area")
    public String area = "";  // closed enum: HVAC_INDOOR/HVAC_OUTDOOR/ELECTRICAL/PLUMBING/APPLIANCE/DUCTWORK/CONTROLS/OTHER

    @NonNull
    @ColumnInfo(name = "component")
    public String component = "";

    @NonNull
    @ColumnInfo(name = "problem")
    public String problem = "";

    @ColumnInfo(name = "diagnosis")
    public String diagnosis;

    @ColumnInfo(name = "resolution")
    public String resolution;

    @NonNull
    @ColumnInfo(name = "severity")
    public String severity = "MEDIUM";  // CRITICAL/HIGH/MEDIUM/LOW

    @NonNull
    @ColumnInfo(name = "status")
    public String status = "OPEN";  // OPEN/IN_PROGRESS/RESOLVED/DISMISSED

    @ColumnInfo(name = "technician_notes")
    public String technicianNotes;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    @ColumnInfo(name = "updated_at")
    public long updatedAt;
}
