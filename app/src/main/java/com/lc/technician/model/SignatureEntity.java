package com.lc.technician.model;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "signatures")
public class SignatureEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long proposalId;
    public long jobId;
    public String nombreFirmante;
    public String opcionAceptada;
    public double totalAceptado;
    public String imagePath;
    public long timestamp;
    public String notasFirma;

    /** Sync v2: 1 = nada que enviar · 0 = pendiente · 2 = en tránsito · 3 = rechazada por el servidor */
    @androidx.room.ColumnInfo(defaultValue = "1") public int syncState = 1;
    public Long serverId;
    public String motivoRechazo;
}
