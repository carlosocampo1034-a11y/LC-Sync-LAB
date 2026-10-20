package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "job_parts")
public class JobPartEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long jobId;
    public long partId;
    public int cantidad;
    public double costoUnitario;
    public double precioUnitario;
    public String nota;
    public long timestamp;

    /** Sync v2: 1 = nada que enviar · 0 = pendiente · 2 = en tránsito · 3 = rechazado. Quitar = anulación lógica. */
    @androidx.room.ColumnInfo(defaultValue = "1") public int syncState = 1;
    @androidx.room.ColumnInfo(defaultValue = "0") public int anulado;
    @androidx.room.ColumnInfo(defaultValue = "0") public long updatedAt;
}
