package com.lc.technician.worker;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.lc.technician.data.LcDatabase;
import android.util.Base64;
import com.lc.technician.model.AuditLogEntity;
import com.lc.technician.model.JobEntity;
import com.lc.technician.model.MembershipCardEntity;
import com.lc.technician.model.MembershipCheckInEntity;
import com.lc.technician.model.PhotoEntity;
import com.lc.technician.model.SyncStateEntity;
import com.lc.technician.model.TechnicianEntity;
import com.lc.technician.model.WorkItemEntity;
import java.io.File;
import java.io.FileInputStream;
import com.lc.technician.sync.ApiClient;
import com.lc.technician.sync.SyncAckPolicy;
import com.lc.technician.sync.SyncApi;
import com.lc.technician.sync.SyncModels;
import com.lc.technician.util.DeviceIdManager;
import java.util.ArrayList;
import java.util.List;

public class SyncWorker extends Worker {

    public static final String TAG = "LcSyncWorker";

    // Output data keys readable by ConfiguracionActivity observers
    public static final String KEY_PULLED_JOBS  = "pulled_jobs";
    public static final String KEY_PUSHED_COUNT = "pushed_count";

    /** Una sola sincronización a la vez en el proceso (periódica, inmediata, manual o de seguimiento). */
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

        // Phase 58: Check connectivity before sync
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
            getApplicationContext().getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo netInfo = cm != null ? cm.getActiveNetworkInfo() : null;
        boolean isConnected = netInfo != null && netInfo.isConnected();
        if (!isConnected) {
            android.util.Log.i("SyncWorker", "Offline: queuing sync for later");
            getApplicationContext().getSharedPreferences("lc_sync_prefs", android.content.Context.MODE_PRIVATE)
                .edit().putLong("pending_sync_since", System.currentTimeMillis()).apply();
            // Show offline toast on main thread
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(getApplicationContext(),
                    "Modo offline · cambios en cola", android.widget.Toast.LENGTH_SHORT).show()
            );
            return Result.success(); // Don't fail, just skip
        }
        // Clear pending flag if we reach here (we're connected)
        getApplicationContext().getSharedPreferences("lc_sync_prefs", android.content.Context.MODE_PRIVATE)
            .edit().remove("pending_sync_since").apply();

        // Sesión con el backend de laboratorio → contrato v2 (confirmación por trabajo y versión)
        if (com.lc.technician.sync.LabSession.activa(getApplicationContext())) {
            try {
                LcDatabase db = LcDatabase.getInstance(getApplicationContext());
                SyncV2.Resultado r = SyncV2.ejecutar(getApplicationContext(), db,
                        ApiClient.get(getApplicationContext()));
                long tsV2 = System.currentTimeMillis();
                getApplicationContext().getSharedPreferences("lc_prefs", android.content.Context.MODE_PRIVATE)
                        .edit().putLong("last_sync_time", tsV2).apply();
                // Actualizar contadores visibles en Ajustes (antes nunca se actualizaban en v2)
                upsertSyncState(db, "PULL_JOB", tsV2);
                if (r.confirmados > 0 || r.enviados > 0) upsertSyncState(db, "WORK_ITEM", tsV2);
                androidx.work.Data out = new androidx.work.Data.Builder()
                        .putInt(KEY_PUSHED_COUNT, r.confirmados)
                        .putInt(KEY_PULLED_JOBS, r.recibidos)
                        .putInt("rechazados", r.rechazados)
                        .putInt("conflictos", r.conflictos)
                        .putBoolean("silent", isSilent)
                        .build();
                // lo enviado sin respuesta válida sigue pendiente: reintentar más tarde
                return r.sinRespuesta > 0 ? Result.retry() : Result.success(out);
            } catch (Exception e) {
                android.util.Log.w(TAG, "sync v2 falló: " + e.getClass().getSimpleName());
                // Bridge directo: replica los jobs locales a Supabase aunque el servidor Lab no responda
                if (com.lc.technician.BuildConfig.SUPABASE_BRIDGE_ENABLED) {
                    try {
                        LcDatabase db2 = LcDatabase.getInstance(getApplicationContext());
                        List<com.lc.technician.model.JobEntity> allJobs = db2.jobDao().getAll();
                        com.lc.technician.sync.SupabaseSyncBridge.get(getApplicationContext())
                                .replicateToSupabase(allJobs);
                        android.util.Log.i(TAG, "supabase direct push: " + allJobs.size() + " jobs");
                    } catch (Exception ex2) {
                        android.util.Log.w(TAG, "supabase direct push falló: " + ex2.getMessage());
                    }
                }
                return Result.retry();
            }
        }

        LcDatabase dbRef = null;
        try {
            LcDatabase db = LcDatabase.getInstance(getApplicationContext());
            dbRef = db;
            SyncApi api = ApiClient.get(getApplicationContext());
            long now = System.currentTimeMillis();

            // Recuperación: si un envío anterior se interrumpió (proceso cerrado), sus trabajos vuelven a pendiente
            db.jobDao().liberarEnTransito();

            // ── PUSH ──────────────────────────────────────────────────────────
            SyncModels.SyncPushRequest req = new SyncModels.SyncPushRequest();
            req.work_items = buildWorkItemDtos(db);
            req.technicians = buildTechnicianDtos(db);
            // Lote de trabajos: leer y marcar "en tránsito" en la misma transacción, para que ninguna
            // escritura se cuele entre la lectura y la marca (su cambio se perdería al confirmar)
            req.jobs = db.runInTransaction(() -> {
                List<SyncModels.JobDto> lote = buildJobDtos(db);
                List<Long> ids = new ArrayList<>();
                for (SyncModels.JobDto d : lote) ids.add(d.id);
                if (!ids.isEmpty()) db.jobDao().marcarEnTransito(ids);
                return lote;
            });
            List<Long> jobsEnviados = new ArrayList<>();
            for (SyncModels.JobDto d : req.jobs) jobsEnviados.add(d.id);
            req.memberships = buildMembershipDtos(db);
            req.audit_logs = buildAuditLogDtos(db);
            req.membership_check_ins = buildMembershipCheckInDtos(db);
            req.photos = buildPhotoDtos(db);
            req.deviceId = DeviceIdManager.getDeviceId(getApplicationContext());
            req.deviceName = DeviceIdManager.getDeviceName(getApplicationContext());

            int pushed = req.work_items.size() + req.technicians.size() + req.jobs.size()
                    + req.audit_logs.size() + req.membership_check_ins.size()
                    + req.photos.size();
            // El servidor cuenta en "accepted" todos los elementos que recibe, membresías incluidas
            int totalElementos = pushed + req.memberships.size();
            long syncTs = now;

            if (pushed > 0) {
                retrofit2.Response<SyncModels.SyncPushResponse> resp = api.push(req).execute();
                SyncModels.SyncPushResponse body = resp.body();
                if (!resp.isSuccessful() || body == null) {
                    String err = "HTTP " + resp.code();
                    db.syncStateDao().markError("WORK_ITEM", err);
                    return Result.retry();  // finally: los trabajos en tránsito vuelven a pendiente
                }
                syncTs = body.timestamp > 0 ? body.timestamp : now;

                // Un 2xx no confirma por sí solo: se confirma solo lo que el servidor acepta
                boolean loteCompleto = SyncAckPolicy.loteCompletoAceptado(true, body, totalElementos);
                if (loteCompleto) {
                    for (SyncModels.WorkItemDto wi : req.work_items) db.workItemDao().markSynced(wi.id, syncTs);
                    for (SyncModels.TechnicianDto t : req.technicians) db.technicianDao().markSynced(t.id, syncTs);
                    upsertSyncState(db, "WORK_ITEM", syncTs);
                    upsertSyncState(db, "TECHNICIAN", syncTs);
                }
                java.util.Set<Long> confirmados = SyncAckPolicy.jobsConfirmados(
                        jobsEnviados, true, body, totalElementos);
                int marcados = confirmados.isEmpty() ? 0
                        : db.jobDao().confirmarSincronizados(SyncAckPolicy.comoLista(confirmados), syncTs);
                android.util.Log.i(TAG, "push: jobs enviados=" + jobsEnviados.size()
                        + " confirmados=" + marcados + " accepted=" + body.accepted
                        + " rejected=" + body.rejected);
                if (marcados == jobsEnviados.size() && loteCompleto) {
                    upsertSyncState(db, "JOB", syncTs);
                } else {
                    // Confirmación parcial o sin ids: lo no confirmado sigue pendiente y se reintenta
                    db.syncStateDao().markError("JOB", "Confirmados " + marcados + " de "
                            + jobsEnviados.size() + "; el resto sigue pendiente");
                    db.jobDao().liberarEnTransito();
                    return Result.retry();
                }
            }

            // ── PULL ──────────────────────────────────────────────────────────
            SyncStateEntity jobState = db.syncStateDao().getByType("JOB");
            long since = (jobState != null && jobState.lastSyncAt > 0) ? jobState.lastSyncAt - 60000 : 0;

            retrofit2.Response<SyncModels.SyncPullResponse> pullResp = api.pull(since).execute();
            int pulledJobs = 0;
            if (pullResp.isSuccessful() && pullResp.body() != null) {
                SyncModels.SyncPullResponse body = pullResp.body();

                pulledJobs += mergeJobs(db, body.jobs, now);
                mergeWorkItems(db, body.work_items);

                upsertSyncState(db, "PULL_JOB", body.server_time > 0 ? body.server_time : now);
            }

            // Save last sync time to SharedPreferences
            getApplicationContext().getSharedPreferences("lc_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putLong("last_sync_time", System.currentTimeMillis()).apply();

            // Build output data so observers can display counts
            androidx.work.Data out = new androidx.work.Data.Builder()
                    .putInt(KEY_PUSHED_COUNT, pushed)
                    .putInt(KEY_PULLED_JOBS, pulledJobs)
                    .putBoolean("silent", isSilent)
                    .build();
            return Result.success(out);

        } catch (Exception e) {
            try {
                LcDatabase db = LcDatabase.getInstance(getApplicationContext());
                String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                db.syncStateDao().markError("WORK_ITEM", err);
                // Bridge directo: replica los jobs locales a Supabase aunque el servidor no responda
                if (com.lc.technician.BuildConfig.SUPABASE_BRIDGE_ENABLED) {
                    try {
                        List<com.lc.technician.model.JobEntity> allJobs = db.jobDao().getAll();
                        com.lc.technician.sync.SupabaseSyncBridge.get(getApplicationContext())
                                .replicateToSupabase(allJobs);
                        android.util.Log.i(TAG, "supabase direct push (legacy path): " + allJobs.size() + " jobs");
                    } catch (Exception ex2) {
                        android.util.Log.w(TAG, "supabase direct push falló: " + ex2.getMessage());
                    }
                }
            } catch (Exception ignored) {}
            return Result.retry();
        } finally {
            // Red caída, timeout, rechazo o excepción: nada queda "en tránsito" para siempre
            if (dbRef != null) {
                try { dbRef.jobDao().liberarEnTransito(); } catch (Exception ignored) {}
            }
        }
    }

    // ── Merge helpers (last-write-wins by updatedAt) ──────────────────────────

    private int mergeJobs(LcDatabase db, List<SyncModels.JobDto> dtos, long now) {
        if (dtos == null) return 0;
        int count = 0;
        for (SyncModels.JobDto dto : dtos) {
            JobEntity existing = db.jobDao().getJobById(dto.id);
            long localUpdated = (existing != null && existing.updatedAt != null) ? existing.updatedAt : 0;
            // Un cambio local aún no confirmado no se pisa con la copia del servidor (se perdería)
            if (existing != null && existing.synced != 1) {
                android.util.Log.w(TAG, "pull: job " + dto.id + " pendiente local; se conserva");
                continue;
            }
            if (existing == null || dto.updatedAt >= localUpdated) {
                JobEntity e = existing != null ? existing : new JobEntity();
                e.id = dto.id;
                e.cliente = dto.cliente;
                e.hora = dto.hora;
                e.estado = JobEntity.normalizeEstado(dto.estado);
                e.technicianId = dto.technicianId;
                e.updatedAt = dto.updatedAt > 0 ? dto.updatedAt : now;
                e.synced = 1;
                e.lastSyncedAt = now;
                if (existing == null) {
                    db.jobDao().insert(e);
                } else {
                    db.jobDao().insert(e); // REPLACE strategy
                }
                count++;
            }
        }
        return count;
    }

    private void mergeWorkItems(LcDatabase db, List<SyncModels.WorkItemDto> dtos) {
        if (dtos == null) return;
        for (SyncModels.WorkItemDto dto : dtos) {
            WorkItemEntity e = new WorkItemEntity();
            e.id = dto.id;
            e.jobId = dto.jobId;
            e.tipo = dto.tipo;
            e.descripcion = dto.descripcion;
            e.cantidad = dto.cantidad;
            e.precioUnitario = dto.precioUnitario;
            e.totalLinea = dto.subtotal;
            e.updatedAt = dto.updatedAt;
            e.synced = 1;
            db.workItemDao().insert(e); // REPLACE strategy
        }
    }

    // ── DTO builders ──────────────────────────────────────────────────────────

    private List<SyncModels.WorkItemDto> buildWorkItemDtos(LcDatabase db) {
        List<WorkItemEntity> items = db.workItemDao().getUnsynced();
        List<SyncModels.WorkItemDto> dtos = new ArrayList<>();
        for (WorkItemEntity w : items) {
            SyncModels.WorkItemDto dto = new SyncModels.WorkItemDto();
            dto.id = w.id;
            dto.jobId = w.jobId;
            dto.tipo = w.tipo;
            dto.descripcion = w.descripcion;
            dto.cantidad = w.cantidad != null ? w.cantidad : 0;
            dto.precioUnitario = w.precioUnitario != null ? w.precioUnitario : 0;
            dto.subtotal = (w.totalLinea != null && w.totalLinea > 0)
                    ? w.totalLinea
                    : (w.cantidad != null && w.precioUnitario != null ? w.cantidad * w.precioUnitario : 0);
            dto.updatedAt = w.updatedAt;
            dtos.add(dto);
        }
        return dtos;
    }

    private List<SyncModels.TechnicianDto> buildTechnicianDtos(LcDatabase db) {
        List<TechnicianEntity> techs = db.technicianDao().getUnsynced();
        List<SyncModels.TechnicianDto> dtos = new ArrayList<>();
        for (TechnicianEntity t : techs) {
            SyncModels.TechnicianDto dto = new SyncModels.TechnicianDto();
            dto.id = t.id;
            dto.nombre = t.nombre;
            dto.email = t.email;
            dto.rol = t.rol;
            dto.status = t.status;
            dto.activo = t.activo;
            dto.updatedAt = t.updatedAt;
            dtos.add(dto);
        }
        return dtos;
    }

    private List<SyncModels.JobDto> buildJobDtos(LcDatabase db) {
        List<JobEntity> jobs = db.jobDao().getUnsynced();
        List<SyncModels.JobDto> dtos = new ArrayList<>();
        for (JobEntity j : jobs) {
            SyncModels.JobDto dto = new SyncModels.JobDto();
            dto.id = j.id;
            dto.cliente = j.cliente;
            dto.hora = j.hora;
            dto.estado = j.estado;
            dto.technicianId = j.technicianId;
            dto.updatedAt = j.updatedAt != null ? j.updatedAt : 0;
            dtos.add(dto);
        }
        return dtos;
    }

    private List<SyncModels.MembershipDto> buildMembershipDtos(LcDatabase db) {
        List<MembershipCardEntity> cards = db.membershipCardDao().getAll();
        List<SyncModels.MembershipDto> dtos = new ArrayList<>();
        for (MembershipCardEntity c : cards) {
            SyncModels.MembershipDto dto = new SyncModels.MembershipDto();
            dto.id = c.id;
            dto.cardNumber = c.cardNumber;
            dto.customerId = c.customerId;
            dto.coverageTypeId = c.coverageTypeId;
            dto.status = c.status;
            dto.paymentStatus = c.paymentStatus;
            dto.issueDate = c.issueDate;
            dto.expiryDate = c.expiryDate;
            dto.lastServiceDate = c.lastServiceDate;
            dto.nextServiceDate = c.nextServiceDate;
            dto.notes = c.notes;
            dto.updatedAt = c.updatedAt;
            dtos.add(dto);
        }
        return dtos;
    }

    private List<SyncModels.AuditLogDto> buildAuditLogDtos(LcDatabase db) {
        List<AuditLogEntity> entries = db.auditLogDao().getAll();
        List<SyncModels.AuditLogDto> dtos = new ArrayList<>();
        for (AuditLogEntity e : entries) {
            SyncModels.AuditLogDto dto = new SyncModels.AuditLogDto();
            dto.id = e.id;
            dto.action = e.action;
            dto.entityType = e.entityType;
            dto.entityId = e.entityId;
            dto.jobId = e.jobId;
            dto.field = e.field;
            dto.oldValue = e.oldValue;
            dto.newValue = e.newValue;
            dto.actor = e.actor;
            dto.timestamp = e.timestamp;
            dto.extra = e.extra;
            dto.updatedAt = e.timestamp;
            dtos.add(dto);
        }
        return dtos;
    }

    private List<SyncModels.MembershipCheckInDto> buildMembershipCheckInDtos(LcDatabase db) {
        List<MembershipCheckInEntity> items = db.membershipCheckInDao().getAll();
        List<SyncModels.MembershipCheckInDto> dtos = new ArrayList<>();
        for (MembershipCheckInEntity ci : items) {
            SyncModels.MembershipCheckInDto dto = new SyncModels.MembershipCheckInDto();
            dto.id = ci.id;
            dto.cardId = ci.cardId;
            dto.jobId = ci.jobId;
            dto.checkInDate = ci.checkInDate;
            dto.technicianName = ci.technicianName;
            dto.notes = ci.notes;
            dto.createdAt = ci.createdAt;
            dto.updatedAt = ci.createdAt;
            dtos.add(dto);
        }
        return dtos;
    }

    private List<SyncModels.PhotoDto> buildPhotoDtos(LcDatabase db) {
        List<PhotoEntity> photos = db.photoDao().getAll();
        List<SyncModels.PhotoDto> dtos = new ArrayList<>();
        for (PhotoEntity p : photos) {
            SyncModels.PhotoDto dto = new SyncModels.PhotoDto();
            dto.id = p.id;
            dto.jobId = p.jobId;
            dto.timestamp = p.timestamp;
            dto.updatedAt = p.timestamp;
            dto.mimeType = "image/jpeg";
            if (p.path != null) {
                File f = new File(p.path);
                dto.filename = f.getName();
                dto.sizeBytes = f.exists() ? f.length() : null;
                if (f.exists() && f.length() < 500 * 1024) {
                    try {
                        FileInputStream fis = new FileInputStream(f);
                        byte[] bytes = new byte[(int) f.length()];
                        fis.read(bytes);
                        fis.close();
                        dto.base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP);
                    } catch (Exception ignored) {}
                }
            }
            dtos.add(dto);
        }
        return dtos;
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
