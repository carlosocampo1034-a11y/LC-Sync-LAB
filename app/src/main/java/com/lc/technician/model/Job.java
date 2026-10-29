package com.lc.technician.model;

public class Job {

    public JobEntity toEntity() {
        JobEntity e = new JobEntity();
        e.id = id;
        e.cliente = cliente;
        e.telefono = telefono != null ? telefono : "";
        e.direccion = direccion != null ? direccion : "";
        e.hora = hora != null ? hora : "";
        e.descripcion = descripcion != null ? descripcion : "";
        e.equipo = equipo != null ? equipo : "";
        e.estado = estado != null ? estado : STATUS_PENDING;
        e.notas = notas != null ? notas : "";
        e.tipoPropiedad = tipoPropiedad != null ? tipoPropiedad : "";
        e.clientePresente = clientePresente;
        e.tipoAcceso = tipoAcceso != null ? tipoAcceso : "";
        e.codigoAcceso = codigoAcceso != null ? codigoAcceso : "";
        e.ubicacionLlave = ubicacionLlave != null ? ubicacionLlave : "";
        e.instrucciones = instrucciones != null ? instrucciones : "";
        e.mascota = mascota != null ? mascota : "";
        e.contactoRespaldo = contactoRespaldo != null ? contactoRespaldo : "";
        e.notasSeguridad = notasSeguridad != null ? notasSeguridad : "";
        e.updatedAt = updatedAt;
        e.customerId = customerId;
        e.propertyId = propertyId;
        e.equipmentId = equipmentId;
        e.technicianId = technicianId;
        e.coverageTypeId = coverageTypeId;
        e.coverageApprovedAmount = coverageApprovedAmount;
        e.coverageAuthNumber = coverageAuthNumber;
        // Antes no se copiaban: reescribir la fila (p. ej. al asignar cobertura) los borraba
        e.prioridad = prioridad;
        e.programadoPara = programadoPara;
        e.estadoDispatch = estadoDispatch;
        e.latitud = latitud;
        e.longitud = longitud;
        e.ubicacionFuente = ubicacionFuente;
        e.serverVersion = serverVersion;
        return e;
    }
    public static final String STATUS_PENDING            = "PENDIENTE";
    public static final String STATUS_EN_ROUTE           = "EN RUTA";
    public static final String STATUS_ARRIVED            = "LLEGUÉ";
    public static final String STATUS_AWAITING_APPROVAL  = "ESPERANDO APROBACIÓN";
    public static final String STATUS_EN_REPARACION      = "EN REPARACIÓN";
    public static final String STATUS_PART_HOLD          = "PART HOLD";
    public static final String STATUS_ON_HOLD            = "PAUSADO";
    public static final String STATUS_DONE               = "COMPLETADO";
    // Orden 54: estados nuevos (el servidor los acepta tras la migración 20261029100000)
    public static final String STATUS_PAID               = "PAGADO";
    public static final String STATUS_MANAGER            = "REQUIERE GERENTE";
    public static final String STATUS_PARTS_PRELOADED    = "REPUESTO PRECARGADO";
    public static final String STATUS_NOT_HOME           = "CLIENTE NO ESTÁ";
    public static final String STATUS_CANCELLED          = "CANCELADO";

    /** Trabajo terminado: completado o ya pagado. */
    public static boolean esCompletado(String estado) {
        return STATUS_DONE.equals(estado) || STATUS_PAID.equals(estado);
    }

    public final long   id;
    public final String cliente;
    public final String telefono;
    public final String direccion;
    public final String hora;
    public final String descripcion;
    public final String equipo;
    public       String estado;
    public       String notas;
    public       Long   updatedAt;
    public       Long   customerId;
    public       Long   propertyId;
    public       Long   equipmentId;
    public       String technicianId;
    public       Long   coverageTypeId;
    public       Double coverageApprovedAmount;
    public       String coverageAuthNumber;
    // v34 — Dispatch
    public       String prioridad;
    public       Long   programadoPara;
    public       Double latitud;
    public       Double longitud;
    public       String ubicacionFuente;
    public       Integer serverVersion;

    /** Hora tal cual; si la cita programada no es hoy, antepone la fecha (ej. "jue 15 oct · 7:00 AM"). */
    public String horaConFecha() {
        String h = hora != null ? hora : "";
        if (programadoPara == null || programadoPara <= 0) return h;
        java.util.Calendar hoy = java.util.Calendar.getInstance();
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(programadoPara);
        if (hoy.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR)
                && hoy.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)) return h;
        String fecha = new java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.forLanguageTag("es"))
                .format(new java.util.Date(programadoPara));
        return h.isEmpty() ? fecha : fecha + " · " + h;
    }
    public       String estadoDispatch;

    public final String tipoPropiedad;
    public final boolean clientePresente;
    public final String tipoAcceso;
    public final String codigoAcceso;
    public final String ubicacionLlave;
    public final String instrucciones;
    public final String mascota;
    public final String contactoRespaldo;
    public final String notasSeguridad;

    public Job(long id, String cliente, String telefono, String direccion,
               String hora, String descripcion, String equipo, String estado,
               String tipoPropiedad, boolean clientePresente, String tipoAcceso,
               String codigoAcceso, String ubicacionLlave, String instrucciones,
               String mascota, String contactoRespaldo, String notasSeguridad) {
        this.id = id;
        this.cliente = cliente;
        this.telefono = telefono;
        this.direccion = direccion;
        this.hora = hora;
        this.descripcion = descripcion;
        this.equipo = equipo;
        this.estado = estado;
        this.notas = "";
        this.tipoPropiedad = tipoPropiedad;
        this.clientePresente = clientePresente;
        this.tipoAcceso = tipoAcceso;
        this.codigoAcceso = codigoAcceso;
        this.ubicacionLlave = ubicacionLlave;
        this.instrucciones = instrucciones;
        this.mascota = mascota;
        this.contactoRespaldo = contactoRespaldo;
        this.notasSeguridad = notasSeguridad;
    }
}
