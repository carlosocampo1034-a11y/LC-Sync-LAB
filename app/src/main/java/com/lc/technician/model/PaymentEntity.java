package com.lc.technician.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "payments")
public class PaymentEntity {

    @PrimaryKey
    @NonNull
    public String id = "";

    public long jobId;
    public double amount;
    /** CASH, CHECK, CARD, ZELLE, OTHER */
    public String method;
    /** PENDING, PAID */
    public String status;
    public long timestamp;
    /** Check number, transaction reference, etc. */
    public String reference;
    public String cliente;
    public String notes;

    /** Sync v2: pago REPORTADO al servidor; solo despacho lo confirma (no se marca pagado sin confirmación). */
    @androidx.room.ColumnInfo(defaultValue = "1") public int syncState = 1;
    @androidx.room.ColumnInfo(defaultValue = "0") public long syncLocalId;
    public Long serverId;
    public String serverEstado;
    public String motivo;
}
