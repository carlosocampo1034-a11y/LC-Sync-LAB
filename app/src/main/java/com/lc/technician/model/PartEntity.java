package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "parts")
public class PartEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String sku;
    public String nombre;
    public String categoria;
    public double costo;
    public double precio;
    public int stockActual;
    public int stockMinimo;
    public String proveedor;
    public String notas;
    public long createdAt;
    public long updatedAt;

    // Added in v31
    public String marca;
    public String modelo;
    public String unidad;    // Each, Ft, Lb, etc.
    public boolean activo;
}
