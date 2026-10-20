package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "equipment")
public class EquipmentEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long propertyId;
    public String nombre;
    public String marca;
    public String modelo;
    public String serial;
    public String tipo;
    public String installDate;
    public Long ultimoServicio;
    public String notas;

    // Technical identity / specifications.
    // Nullable for backwards compatibility with legacy equipment records.
    public Double tons;
    public String refrigerante;
    public String voltaje;
    public String fase;
    public Double seer;
    public String manufactureDate;
    public String ubicacion;
    public Boolean heatPump;

    // System topology / lifecycle.
    // systemRole: INDOOR | OUTDOOR | null
    public String systemRole;
    public Long pairedEquipmentId;

    // lifecycleStatus: ACTIVE | REPLACED | RETIRED | null for legacy rows
    public String lifecycleStatus;
    public Long replacedByEquipmentId;

    // scanStatus: MANUAL | PENDING_CONFIRMATION | CONFIRMED
    // OCR must never silently overwrite confirmed equipment identity.
    public String scanStatus;

    public long createdAt;
    public long updatedAt;

    // v33 — Equipment Lifecycle
    public Long proximoMantenimiento;
    public int intervaloMantenimientoDias = 365;
    public Long ultimoServicioJobId;
    public String estado;  // ACTIVO | REPARACION | REEMPLAZO | INACTIVO
}
