package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "stock_locations")
public class StockLocationEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;

    public String type;
    public String name;
    public Long technicianId;
    public boolean isActive;
    public long createdAt;

    public StockLocationEntity() {
        this.isActive = true;
        this.createdAt = System.currentTimeMillis();
    }

    public static final String TYPE_WAREHOUSE = "WAREHOUSE";
    public static final String TYPE_TRUCK = "TRUCK";
}
