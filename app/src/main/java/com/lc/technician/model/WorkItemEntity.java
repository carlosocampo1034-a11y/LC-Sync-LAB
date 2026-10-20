package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "work_items")
public class WorkItemEntity {

    public static final String TYPE_LABOR   = "LABOR";
    public static final String TYPE_PART    = "PART";
    public static final String TYPE_SERVICE = "SERVICE";
    public static final String TYPE_FEE     = "FEE";
    public static final String TYPE_OTHER   = "OTHER";

    @PrimaryKey(autoGenerate = true) public long id;
    public long jobId;
    public String tipo;              // LABOR | PART | SERVICE | FEE | OTHER
    public String itemCode;
    public String descripcion;
    public Double cantidad;
    public String unidad;            // Hour, Each, Ft
    public Double precioUnitario;
    public Double total;
    public Long partId;
    public String nota;
    public long timestamp;
    public Long findingId;   // FK → findings, nullable
    public Long proposalId;  // FK → proposals, nullable
    public Double totalLinea; // computed: cantidad × precioUnitario
    public long createdAt;   // v35
    public long updatedAt;   // v35
    public int synced;           // v37: 0=pending, 1=synced
    public Long lastSyncedAt;    // v37: timestamp of last successful sync

    /** Anulación lógica (1): el registro se conserva para auditoría y para sincronizar la anulación. */
    @androidx.room.ColumnInfo(defaultValue = "0") public int anulado;
    public Long anuladoAt;
}
