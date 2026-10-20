package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "properties")
public class PropertyEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long customerId;
    public String direccion;
    public String tipoPropiedad;
    public boolean clientePresente;
    public String tipoAcceso;
    public String codigoAcceso;
    public String ubicacionLlave;
    public String instrucciones;
    public String mascota;
    public String contactoRespaldo;
    public String notasSeguridad;
    public long createdAt;
    public long updatedAt;

    // Geolocalización de la dirección (v42). null = dirección escrita sin verificar.
    public Double latitud;
    public Double longitud;
    public String ubicacionFuente;  // GEOCODER | null
}
