package com.lc.technician.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;

/**
 * Room entity for equipment_photos table (created in MIGRATION_21_22).
 *
 * Column nullability must exactly match the raw CREATE TABLE SQL used in
 * MIGRATION_21_22:
 *   id              INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL
 *   equipment_id    INTEGER NOT NULL
 *   file_path       TEXT NOT NULL
 *   timestamp       INTEGER NOT NULL
 *   raw_ocr_text    TEXT          (nullable)
 */
@Entity(tableName = "equipment_photos")
public class EquipmentPhotoEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    @ColumnInfo(name = "equipment_id")
    public long equipmentId;

    @NonNull
    @ColumnInfo(name = "file_path")
    public String filePath = "";

    @ColumnInfo(name = "timestamp")
    public long timestamp;

    @ColumnInfo(name = "raw_ocr_text")
    public String rawOcrText;
}
