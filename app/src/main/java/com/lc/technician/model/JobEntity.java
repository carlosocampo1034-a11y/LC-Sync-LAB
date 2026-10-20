package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "jobs")
public class JobEntity {
    @PrimaryKey
    public long id;
    public String cliente;
    public String telefono;
    public String direccion;
    public String hora;
    public String descripcion;
    public String equipo;
    public String estado;
    public String notas;
    public String tipoPropiedad;
    public boolean clientePresente;
    public String tipoAcceso;
    public String codigoAcceso;
    public String ubicacionLlave;
    public String instrucciones;
    public String mascota;
    public String contactoRespaldo;
    public String notasSeguridad;
    public Long updatedAt;
    public Long customerId;
    public Long propertyId;
    public Long equipmentId;
    /** Technician assigned to this job — persisted to DB (nullable = unassigned). */
    public String technicianId;
    public Long coverageTypeId;         // FK to coverage_types.id, nullable
    public Double coverageApprovedAmount; // approved dollar amount by coverage, nullable
    public String coverageAuthNumber;    // authorization number from warranty/coverage, nullable

    // v34 — Dispatch
    public String prioridad;       // NORMAL | ALTA | URGENTE
    public Long programadoPara;    // timestamp — scheduled for
    public String estadoDispatch;  // QUEUE | ASSIGNED | IN_PROGRESS | DONE

    // v37 — Sync flags
    public int synced;             // 0=pending, 1=synced
    public Long lastSyncedAt;      // timestamp of last successful sync

    // Geolocalización de la dirección (v42). null = dirección escrita sin verificar.
    public Double latitud;
    public Double longitud;
    public String ubicacionFuente;  // GEOCODER | null

    // v43: versión del registro en el servidor (sync v2). null = nunca confirmado por el servidor.
    public Integer serverVersion;

    /** Maps English estado values from server to Spanish used throughout the app. */
    public static String normalizeEstado(String estado) {
        if (estado == null) return "PENDIENTE";
        switch (estado.toUpperCase()) {
            case "PENDING":    return "PENDIENTE";
            case "COMPLETED":  return "COMPLETADO";
            case "IN_ROUTE":
            case "EN_ROUTE":   return "EN RUTA";
            case "CANCELLED":  return "CANCELADO";
            default:           return estado;
        }
    }
}
