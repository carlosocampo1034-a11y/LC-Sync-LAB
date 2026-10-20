package com.lc.technician.sync;

import android.content.Context;
import android.util.Log;

import com.lc.technician.BuildConfig;
import com.lc.technician.data.JobRepository;
import com.lc.technician.model.JobEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Puente entre la sincronización Flask (fuente de verdad offline-first)
 * y Supabase (fuente central de datos en la nube).
 *
 * Flujo de datos:
 *
 *   Android ──push──► Flask LAB
 *                         │ (FASE 3: Flask replica a Supabase en el servidor)
 *   Android ◄──pull──── Flask LAB
 *       │
 *       └──replicateToSupabase()──► Supabase PostgreSQL
 *                                         │
 *   Control / Customer ──────────────────►│◄── Realtime
 *                                         │
 *   Android ◄──onJobChanged()─────────────┘
 *       └── solicitarSyncInmediata() → Flask pull → estado canónico
 *
 * Anti-bucle: el bridge solo escribe a Supabase después de que Flask
 * confirma los datos (no reenvía lo que recibe de Realtime).
 */
public class SupabaseSyncBridge {

    private static final String TAG       = "SupabaseSyncBridge";
    private static final int    BATCH_MAX = 50;

    private static SupabaseSyncBridge sInstance;

    private final Context                ctx;
    private final SupabaseRealtimeClient realtime = new SupabaseRealtimeClient();
    private       boolean                realtimeActive;

    private SupabaseSyncBridge(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static synchronized SupabaseSyncBridge get(Context ctx) {
        if (sInstance == null) sInstance = new SupabaseSyncBridge(ctx);
        return sInstance;
    }

    // ── Réplica hacia Supabase ───────────────────────────────────────────────

    /**
     * Después de cada sync Flask exitosa: replica las órdenes aceptadas a Supabase.
     * Llamar en background (ya viene del hilo de SyncV2).
     *
     * @param jobs Lista de JobEntity con synced==1 (confirmadas por Flask).
     */
    public void replicateToSupabase(List<JobEntity> jobs) {
        if (!BuildConfig.SUPABASE_BRIDGE_ENABLED) return;
        if (jobs == null || jobs.isEmpty()) return;
        replicateRows(toRows(jobs));
    }

    private void replicateRows(List<SupabaseModels.JobRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        SupabaseClient sb = SupabaseClient.get(ctx);
        sb.refreshIfNeeded();
        for (int i = 0; i < rows.size(); i += BATCH_MAX) {
            List<SupabaseModels.JobRow> batch =
                    rows.subList(i, Math.min(i + BATCH_MAX, rows.size()));
            try {
                retrofit2.Response<okhttp3.ResponseBody> r =
                        sb.api().upsertJobs(sb.authHeader(), batch).execute();
                if (r.isSuccessful()) {
                    Log.i(TAG, "replicados " + batch.size() + " jobs a Supabase");
                } else {
                    String err = "";
                    try { err = r.errorBody() != null ? r.errorBody().string() : ""; }
                    catch (Exception ignored) {}
                    Log.w(TAG, "upsert HTTP " + r.code() + ": " + err);
                }
            } catch (Exception e) {
                Log.w(TAG, "upsert error: " + e.getMessage());
            }
        }
    }

    // ── Realtime ────────────────────────────────────────────────────────────

    /**
     * Inicia la suscripción Realtime a la tabla `jobs`.
     * Llamar una vez al iniciar la sesión (MainActivity / SyncWorker).
     *
     * @param accessToken Token de Supabase Auth del usuario (null → usa anon key).
     */
    public void startRealtime(String accessToken) {
        if (!BuildConfig.SUPABASE_BRIDGE_ENABLED) return;
        if (realtimeActive) return;
        realtimeActive = true;

        realtime.start(accessToken, (jobId, version, eventType) -> {
            // Recibimos un cambio remoto (de Control o Customer) en Supabase.
            // No modificamos Room directamente para evitar bucle; pedimos a Flask
            // que nos dé el estado canónico en el próximo pull.
            Log.i(TAG, "Realtime " + eventType + " job=" + jobId + " v=" + version
                    + " → solicitando sync Flask");
            JobRepository.get().solicitarSyncInmediata();
        });
    }

    public void stopRealtime() {
        realtime.stop();
        realtimeActive = false;
    }

    /**
     * Replica desde los DTOs del pull (evita round-trip a Room).
     * Llamar en background, inmediatamente después de LabSession.setCursor().
     */
    public void replicateDtos(List<com.lc.technician.sync.SyncModels.JobV2Dto> dtos) {
        if (!BuildConfig.SUPABASE_BRIDGE_ENABLED) return;
        if (dtos == null || dtos.isEmpty()) return;
        List<SupabaseModels.JobRow> rows = new ArrayList<>(dtos.size());
        for (com.lc.technician.sync.SyncModels.JobV2Dto d : dtos) {
            SupabaseModels.JobRow r = new SupabaseModels.JobRow();
            r.id              = d.id;
            r.version         = d.version;
            r.estado          = d.estado;
            r.estadoDispatch  = d.estadoDispatch;
            r.prioridad       = d.prioridad;
            r.technicianCode  = d.technicianId;
            r.cliente         = d.cliente;
            r.telefono        = d.telefono;
            r.direccion       = d.direccion;
            r.lat             = d.latitud;
            r.lng             = d.longitud;
            r.ubicacionFuente = d.ubicacionFuente;
            r.hora            = d.hora;
            r.programadoPara  = d.programadoPara;
            r.descripcion     = d.descripcion;
            r.equipo          = d.equipo;
            r.tipoPropiedad   = d.tipoPropiedad;
            r.clientePresente = d.clientePresente;
            r.instrucciones   = d.instrucciones;
            r.mascota         = d.mascota;
            r.contactoRespaldo = d.contactoRespaldo;
            r.notasSeguridad  = d.notasSeguridad;
            r.notas           = d.notas;
            r.updatedAt       = d.updatedAt;
            rows.add(r);
        }
        replicateRows(rows);
    }

    // ── Conversión ──────────────────────────────────────────────────────────

    private List<SupabaseModels.JobRow> toRows(List<JobEntity> jobs) {
        List<SupabaseModels.JobRow> rows = new ArrayList<>(jobs.size());
        for (JobEntity e : jobs) {
            SupabaseModels.JobRow r = new SupabaseModels.JobRow();
            r.id              = e.id;
            r.version         = e.serverVersion;
            r.estado          = e.estado;
            r.estadoDispatch  = e.estadoDispatch;
            r.prioridad       = e.prioridad;
            r.technicianCode  = e.technicianId;
            r.cliente         = e.cliente;
            r.telefono        = e.telefono;
            r.direccion       = e.direccion;
            r.lat             = e.latitud;
            r.lng             = e.longitud;
            r.ubicacionFuente = e.ubicacionFuente;
            r.hora            = e.hora;
            r.programadoPara  = e.programadoPara;
            r.descripcion     = e.descripcion;
            r.equipo          = e.equipo;
            r.tipoPropiedad   = e.tipoPropiedad;
            r.clientePresente = e.clientePresente;
            r.instrucciones   = e.instrucciones;
            r.mascota         = e.mascota;
            r.contactoRespaldo = e.contactoRespaldo;
            r.notasSeguridad  = e.notasSeguridad;
            r.notas           = e.notas;
            r.updatedAt       = e.updatedAt;
            rows.add(r);
        }
        return rows;
    }
}
