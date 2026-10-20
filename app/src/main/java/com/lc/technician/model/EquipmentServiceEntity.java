package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "equipment_services")
public class EquipmentServiceEntity {
    @PrimaryKey(autoGenerate = true) public long id;
    public long equipmentId;
    public Long jobId;
    public String tipoServicio;  // MANTENIMIENTO | REPARACION | INSTALACION | INSPECCION
    public String descripcion;
    public double costoTotal;
    public long timestamp;
}
