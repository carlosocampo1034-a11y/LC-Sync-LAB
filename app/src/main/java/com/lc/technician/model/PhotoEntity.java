package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "photos")
public class PhotoEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long jobId;
    public String path;
    public String tipo;
    public long timestamp;
    public String categoria;  // ANTES, DESPUÉS, EQUIPO, NAMEPLATE — nullable para fotos viejas
}
