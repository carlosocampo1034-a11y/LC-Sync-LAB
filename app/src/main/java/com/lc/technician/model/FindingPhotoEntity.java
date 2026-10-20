package com.lc.technician.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;

@Entity(tableName = "finding_photos")
public class FindingPhotoEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "finding_id")
    public long findingId;

    @NonNull
    @ColumnInfo(name = "file_path")
    public String filePath = "";

    @ColumnInfo(name = "category")
    public String category;  // BEFORE/AFTER/DAMAGE/CONTEXT

    @ColumnInfo(name = "timestamp")
    public long timestamp;
}
