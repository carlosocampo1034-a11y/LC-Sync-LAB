package com.lc.technician.model;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "technicians")
public class TechnicianEntity {

    public static final String ROLE_ADMIN      = "ADMIN";
    public static final String ROLE_TECH       = "TECNICO";
    public static final String ROLE_DISPATCHER = "DISPATCHER";

    public static final String STATUS_ACTIVE   = "ACTIVE";
    public static final String STATUS_INACTIVE = "INACTIVE";
    public static final String STATUS_ON_ROUTE = "ON_ROUTE";

    @PrimaryKey
    @NonNull
    public String id = "";

    public String nombre;
    public String email;
    public String telefono;
    public String rol;       // TECNICO, ADMIN, DISPATCHER
    public boolean activo;   // true = active, false = inactive (soft deactivation)
    public long createdAt;
    public long updatedAt;

    // v34 — Extended fields
    public String zona;
    public String ubicacionActual;

    // v36 — Dispatch + status fields
    public String status;        // ACTIVE | INACTIVE | ON_ROUTE
    public Long currentJobId;    // nullable
    public double jobsCompleted; // total jobs completed
    public double rating;        // 0..5

    // v37 — Sync flags
    public int synced;           // 0=pending, 1=synced
    public Long lastSyncedAt;    // timestamp of last successful sync
}
