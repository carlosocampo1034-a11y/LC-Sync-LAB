package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "customers")
public class CustomerEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public String nombre;
    public String telefono;
    public String email;
    public String notas;
    public long createdAt;
    public long updatedAt;
}
