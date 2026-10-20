package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "stock_movements")
public class StockMovementEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public long partId;
    public long locationId;
    public String movementType;
    public int quantity;
    public Long fromLocationId;
    public Long toLocationId;
    public String referenceType;
    public Long referenceId;
    public String notes;
    public Long technicianId;
    public long timestamp;

    public StockMovementEntity() {
        this.timestamp = System.currentTimeMillis();
    }

    public static final String TYPE_IN = "IN";
    public static final String TYPE_OUT = "OUT";
    public static final String TYPE_ADJUSTMENT = "ADJUSTMENT";
    public static final String TYPE_TRANSFER = "TRANSFER";

    public static final String REF_JOB = "JOB";
    public static final String REF_PURCHASE = "PURCHASE";
    public static final String REF_MANUAL = "MANUAL";
    public static final String REF_TRANSFER = "TRANSFER";
}
