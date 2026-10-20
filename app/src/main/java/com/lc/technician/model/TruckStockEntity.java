package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "truck_stock")
public class TruckStockEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    public long partId;
    public String ubicacion;   // truck ID or location name
    public double cantidad;
    public long updatedAt;
}
