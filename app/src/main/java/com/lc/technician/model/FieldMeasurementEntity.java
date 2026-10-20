package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "field_measurements")
public class FieldMeasurementEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long jobId;
    public String tipo;      // "VOLTAGE", "AMPERAGE", "TEMPERATURE", "PRESSURE", "SUPERHEAT", "SUPERCOOL", "STATIC_PRESSURE"
    public String ubicacion;
    public double valor;
    public String unidad;    // "V", "A", "°F", "PSI"
    public String nota;      // nullable
    public Long findingId;   // FK → findings, nullable — Phase 8
}
