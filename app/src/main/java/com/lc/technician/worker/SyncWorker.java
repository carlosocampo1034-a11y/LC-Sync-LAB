package com.lc.technician.worker;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.JobEntity;
import com.lc.technician.model.SyncStateEntity;
import com.lc.technician.sync.SupabaseClient;
import com.lc.technician.sync.SupabaseModels;
import java.util.ArrayList;
import java.util.List;

public class SyncWorker extends Worker {

    public static final String TAG = "LcSyncWorker";

    // Output data keys readable by ConfiguracionActivity observers
    public static final String KEY_PULLED_JOBS  = "pulled_jobs";
    public static final String KEY_PUSHED_COUNT = "pushed_count";

    /** Una sola sincronización a la vez en el proceso (periódica, inmediata, manual o de seguimiento). */
    private static final long PRESUPUESTO_MS = 60_000L;
    static final java.util.concurrent.locks.ReentrantLock EN_CURSO = new java.util.concurrent.locks.ReentrantLock();

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        if (!EN_CURSO.tryLock()) {
            // otra sincronización está en curso; lo que quede pendiente lo sube su ejecución de seguimiento
            android.util.Log.i(TAG, "sync omitida: ya hay una en curso");
            return Result.success();
        }
        try {
            return doWorkExclusivo();
        } finally {
            EN_CURSO.unlock();
        }
    }

    private Result doWorkExclusivo() {
        boolean isSilent = getInputData().getBoolean("silent", true);

        // Check connectivity before sync
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
            getApplicationContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo netInfo = cm != null ? cm.getActiveNetworkInfo() : null;
        if (netInfo == null || !netInfo.isConnected()) {
            android.util.Log.i(TAG, "Offline: queuing sync for later");
            getApplicationContext().getSharedPreferences("lc_sync_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putLong("pending_sync_since", System.currentTimeMillis()).apply();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(getApplicationContext(),
                    "Modo offline · cambios en cola", android.widget.Toast.LENGTH_SHORT).show());
            return Result.success();
        }
        getApplicationContext().getSharedPreferences("lc_sync_prefs", android.content.Context.MODE_PRIVATE)
            .edit().remove("pending_sync_since").apply();

        // Ensure Supabase auth session
        SupabaseClient sb = SupabaseClient.get(getApplicationContext());
        String authErr = sb.ensureAuthSync();
        if (authErr != null) {
            android.util.Log.w(TAG, "supabase auth failed: " + authErr);
            return Result.retry();
        }

        LcDatabase dbRef = null;
        try {
            LcDatabase db = LcDatabase.getInstance(getApplicationContext());
            dbRef = db;
            long now = System.currentTimeMillis();
            // Presupuesto de la sincronización: pasado este tiempo se corta y se reintenta, para no tener ocupado
            // el cerrojo EN_CURSO (que bloquea a las demás) cuando el servidor responde lento.
            final long limiteSync = now + PRESUPUESTO_MS;

            // Recuperación: si un envío anterior se interrumpió, sus trabajos vuelven a pendiente
            db.jobDao().liberarEnTransito();

            if (com.lc.technician.BuildConfig.CLOUD_LOGIN) {
                com.lc.technician.sync.QaOrderSync.reencolarSinBase(getApplicationContext(), db);
            }
            // ── PUSH: unsynced jobs → Supabase upsert ─────────────────────────
            List<JobEntity> unsyncedJobs = db.runInTransaction(() -> {
                List<JobEntity> lote = db.jobDao().getUnsynced();
                List<Long> ids = new ArrayList<>();
                for (JobEntity j : lote) ids.add(j.id);
                if (!ids.isEmpty()) db.jobDao().marcarEnTransito(ids);
                return lote;
            });

            int pushed = 0;
            boolean pullCompleto = false;
            boolean reintentar = false;
            if (com.lc.technician.BuildConfig.CLOUD_LOGIN && !unsyncedJobs.isEmpty()) {
                // QA: cada cambio va a lc-api con versión base (sin upsert directo, que RLS no permite)
                com.lc.technician.sync.QaOrderSync.Resultado qr =
                        com.lc.technician.sync.QaOrderSync.subir(getApplicationContext(), db, sb, unsyncedJobs, limiteSync);
                db.jobDao().liberarEnTransito();  // lo no confirmado vuelve a la cola
                pushed = qr.aceptados;
                pullCompleto = qr.pullCompleto;
                reintentar = qr.reintentar;
                android.util.Log.i(TAG, "push QA: " + qr.aceptados + " aceptados, " + qr.conflictos
                        + " conflictos, " + qr.rechazados + " rechazados");
            } else if (!unsyncedJobs.isEmpty()) {
                List<SupabaseModels.JobRow> rows = jobsToRows(unsyncedJobs);
                boolean allOk = true;
                for (int i = 0; i < rows.size(); i += 50) {
                    List<SupabaseModels.JobRow> batch = rows.subList(i, Math.min(i + 50, rows.size()));
                    try {
                        retrofit2.Response<okhttp3.ResponseBody> r =
                                sb.api().upsertJobs(sb.authHeader(), batch).execute();
                        if (!r.isSuccessful()) {
                            String errBody = "";
                            try { errBody = r.errorBody() != null ? r.errorBody().string() : ""; }
                            catch (Exception ignored) {}
                            android.util.Log.w(TAG, "upsert HTTP " + r.code() + ": " + errBody);
                            allOk = false;
                            break;
                        }
                    } catch (Exception e) {
                        android.util.Log.w(TAG, "upsert error: " + e.getMessage());
                        allOk = false;
                        break;
                    }
                }
                if (allOk) {
                    List<Long> ids = new ArrayList<>();
                    for (JobEntity j : unsyncedJobs) ids.add(j.id);
                    pushed = db.jobDao().confirmarSincronizados(ids, now);
                    upsertSyncState(db, "JOB", now);
                    upsertSyncState(db, "WORK_ITEM", now);
                    android.util.Log.i(TAG, "push: " + pushed + " jobs → Supabase");
                } else {
                    db.jobDao().liberarEnTransito();
                    return Result.retry();
                }
            }

            if (System.currentTimeMillis() > limiteSync) {
                android.util.Log.w(TAG, "sync: presupuesto de tiempo agotado antes del pull; se reintenta");
                return Result.retry();
            }
            // ── PULL: Supabase → Room (last-write-wins) ────────────────────────
            SyncStateEntity pullState = db.syncStateDao().getByType("PULL_JOB");
            long since = (pullState != null && pullState.lastSyncAt > 0) ? pullState.lastSyncAt - 60_000L : 0;
            if (pullCompleto) since = 0;

            int pulledJobs = 0;
            try {
                retrofit2.Response<List<SupabaseModels.JobRow>> pullResp =
                        sb.api().getJobsSince(sb.authHeader(), "gt." + since, "*", 1000).execute();
                if (pullResp.isSuccessful() && pullResp.body() != null) {
                    pulledJobs = mergeJobRows(db, pullResp.body(), now);
                    upsertSyncState(db, "PULL_JOB", now);
                    android.util.Log.i(TAG, "pull: " + pulledJobs + " jobs ← Supabase");
                    if (com.lc.technician.BuildConfig.CLOUD_LOGIN) pulledJobs += marcarReasignadas(db, sb);
                    if (com.lc.technician.BuildConfig.CLOUD_LOGIN) {
                        try {   // evidencias nuevas → Storage (el cliente y el Control las ven); nunca borra nada local
                            int fotos = com.lc.technician.sync.PhotoSync.sincronizarNuevas(getApplicationContext(), db, sb);
                            if (fotos > 0) android.util.Log.i(TAG, "fotos subidas: " + fotos);
                        } catch (Exception fe) { android.util.Log.w(TAG, "fotos: " + fe.getMessage()); }
                    }
                    // Igual que SyncV2: sin esto lo bajado (p. ej. una asignación del Control) no aparecía en la lista
                    if (pulledJobs > 0) com.lc.technician.data.JobRepository.get().recargarCacheAsync(null);
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "pull error: " + e.getMessage());
            }

            // QA: propuestas Good/Better/Best (envío y decisión del cliente) una vez confirmadas las órdenes
            if (com.lc.technician.BuildConfig.CLOUD_LOGIN && System.currentTimeMillis() > limiteSync) {
                reintentar = true;
                android.util.Log.w(TAG, "sync: presupuesto agotado antes de las propuestas; se reintenta");
            } else if (com.lc.technician.BuildConfig.CLOUD_LOGIN) QaProposalSync.sincronizar(getApplicationContext(), db, sb);

            getApplicationContext().getSharedPreferences("lc_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putLong("last_sync_time", now).apply();

            androidx.work.Data out = new androidx.work.Data.Builder()
                    .putInt(KEY_PUSHED_COUNT, pushed)
                    .putInt(KEY_PULLED_JOBS, pulledJobs)
                    .putBoolean("silent", isSilent)
                    .build();
            if (reintentar) return Result.retry();  // cambios en cola: WorkManager reintenta con backoff
            return Result.success(out);

        } catch (Exception e) {
            try {
                LcDatabase db = LcDatabase.getInstance(getApplicationContext());
                String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                db.syncStateDao().markError("WORK_ITEM", err);
            } catch (Exception ignored) {}
            return Result.retry();
        } finally {
            if (dbRef != null) {
                try { dbRef.jobDao().liberarEnTransito(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * QA: si el Control reasigna una orden a otro técnico, RLS deja de devolverla y el pull incremental
     * nunca trae el cambio; la copia local seguía en "Mis Jobs". Se comparan los ids visibles y las órdenes
     * propias ya sincronizadas que desaparecieron se marcan REASIGNADA (sin borrar fotos ni historial).
     */
    private int marcarReasignadas(LcDatabase db, com.lc.technician.sync.SupabaseClient sb) {
        try {
            retrofit2.Response<List<SupabaseModels.JobRow>> r =
                    sb.api().getJobsSince(sb.authHeader(), "gte.0", "id", 1000).execute();
            if (!r.isSuccessful() || r.body() == null || r.body().size() >= 1000) return 0;
            java.util.Set<Long> visibles = new java.util.HashSet<>();
            for (SupabaseModels.JobRow row : r.body()) visibles.add(row.id);
            String yo = com.lc.technician.SessionManager.get(getApplicationContext()).getTechnicianName();
            int n = 0;
            for (JobEntity e : db.jobDao().getAll()) {
                if (e.synced == 1 && yo != null && yo.equals(e.technicianId) && !visibles.contains(e.id)) {
                    e.technicianId = "REASIGNADA";
                    e.estadoDispatch = "REASIGNADA";
                    db.jobDao().insert(e);
                    android.util.Log.i(TAG, "job " + e.id + " ya no es de " + yo + ": REASIGNADA");
                    n++;
                }
            }
            return n;
        } catch (Exception ex) {
            android.util.Log.w(TAG, "reasignadas: " + ex.getMessage());
            return 0;
        }
    }

    // ── Supabase merge helpers ────────────────────────────────────────────────

    private int mergeJobRows(LcDatabase db, List<SupabaseModels.JobRow> rows, long now) {
        if (rows == null) return 0;
        int count = 0;
        for (SupabaseModels.JobRow row : rows) {
            JobEntity existing = db.jobDao().getJobById(row.id);
            // Don't overwrite a local pending change
            if (existing != null && existing.synced != 1) {
                android.util.Log.w(TAG, "pull: job " + row.id + " pendiente local; se conserva");
                continue;
            }
            long localUpdated = (existing != null && existing.updatedAt != null) ? existing.updatedAt : 0;
            long rowUpdated   = row.updatedAt != null ? row.updatedAt : 0;
            // QA: el servidor manda por versión (no por reloj del teléfono, que puede ir adelantado)
            boolean aplicar = com.lc.technician.BuildConfig.CLOUD_LOGIN
                    ? existing == null || existing.serverVersion == null || row.version == null
                      || row.version >= existing.serverVersion
                    : existing == null || rowUpdated >= localUpdated;
            if (aplicar) {
                JobEntity e = existing != null ? existing : new JobEntity();
                e.id              = row.id;
                e.cliente         = row.cliente;
                e.hora            = row.hora;
                e.estado          = JobEntity.normalizeEstado(row.estado);
                e.estadoDispatch  = row.estadoDispatch;
                e.prioridad       = row.prioridad;
                e.technicianId    = row.technicianCode;
                e.telefono        = row.telefono;
                e.direccion       = row.direccion;
                e.latitud         = row.lat;
                e.longitud        = row.lng;
                e.ubicacionFuente = row.ubicacionFuente;
                e.programadoPara  = row.programadoPara;
                e.descripcion     = row.descripcion;
                e.equipo          = row.equipo;
                e.tipoPropiedad   = row.tipoPropiedad;
                e.clientePresente = row.clientePresente != null && row.clientePresente != 0;
                e.instrucciones   = row.instrucciones;
                e.mascota         = row.mascota;
                e.contactoRespaldo = row.contactoRespaldo;
                e.notasSeguridad  = row.notasSeguridad;
                e.notas           = row.notas;
                e.updatedAt       = rowUpdated > 0 ? rowUpdated : now;
                if (row.version != null) e.serverVersion = row.version;
                e.synced          = 1;
                e.lastSyncedAt    = now;
                db.jobDao().insert(e);
                if (com.lc.technician.BuildConfig.CLOUD_LOGIN) {
                    com.lc.technician.sync.QaOrderSync.guardarBase(getApplicationContext(), row);
                }
                count++;
            }
        }
        return count;
    }

    private List<SupabaseModels.JobRow> jobsToRows(List<JobEntity> jobs) {
        List<SupabaseModels.JobRow> rows = new ArrayList<>(jobs.size());
        for (JobEntity j : jobs) {
            SupabaseModels.JobRow r = new SupabaseModels.JobRow();
            r.id              = j.id;
            r.version         = j.serverVersion;
            r.estado          = j.estado;
            r.estadoDispatch  = j.estadoDispatch;
            r.prioridad       = j.prioridad;
            r.technicianCode  = j.technicianId;
            r.cliente         = j.cliente;
            r.telefono        = j.telefono;
            r.direccion       = j.direccion;
            r.lat             = j.latitud;
            r.lng             = j.longitud;
            r.ubicacionFuente = j.ubicacionFuente;
            r.hora            = j.hora;
            r.programadoPara  = j.programadoPara;
            r.descripcion     = j.descripcion;
            r.equipo          = j.equipo;
            r.tipoPropiedad   = j.tipoPropiedad;
            r.clientePresente = j.clientePresente ? 1 : 0;
            r.instrucciones   = j.instrucciones;
            r.mascota         = j.mascota;
            r.contactoRespaldo = j.contactoRespaldo;
            r.notasSeguridad  = j.notasSeguridad;
            r.notas           = j.notas;
            r.updatedAt       = j.updatedAt;
            rows.add(r);
        }
        return rows;
    }

    private void upsertSyncState(LcDatabase db, String type, long ts) {
        if (db.syncStateDao().countByType(type) == 0) {
            SyncStateEntity s = new SyncStateEntity();
            s.entityType = type;
            s.lastSyncAt = ts;
            db.syncStateDao().insert(s);
        } else {
            db.syncStateDao().markSuccess(type, ts);
        }
    }
}
