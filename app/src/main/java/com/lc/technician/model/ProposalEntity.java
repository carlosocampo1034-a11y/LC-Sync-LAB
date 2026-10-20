package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "proposals")
public class ProposalEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long jobId;
    public String estado;          // "DRAFT","SENT","ACCEPTED","REJECTED"
    public String opcionAceptada;  // nullable: "GOOD","BETTER","BEST"
    public String notas;           // nullable
    public long createdAt;
    public long updatedAt;
    public boolean isLocked;   // true after signature
    public long lockedAt;      // timestamp of lock
    public int version;        // 1 at creation, +1 on re-edit before signing

    // ── Sync v2 (Fase 6): la propuesta viaja al servidor y el cliente decide en L&C Customer ──
    /** 1 = nada que enviar · 0 = pendiente de envío · 2 = en tránsito · 3 = rechazada por el servidor */
    @androidx.room.ColumnInfo(defaultValue = "1") public int syncState = 1;
    public Long serverId;
    public Integer serverRevision;
    /** PENDIENTE | APROBADA | RECHAZADA | VENCIDA | SUPERADA (según el servidor) */
    public String serverEstado;
    public String contenidoSha;
    public String firmanteServidor;
    public String opcionServidor;
    public Double totalServidor;
    public Long decididaAt;
    public Long venceAt;
    public String motivoRechazo;
    public Long enviadaAt;
}
