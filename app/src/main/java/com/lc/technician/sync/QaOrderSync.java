package com.lc.technician.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.JobEntity;
import com.lc.technician.util.DeviceIdManager;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Variante QA (BuildConfig.CLOUD_LOGIN): sube los cambios del técnico a lc-api con control de versión.
 *
 * - Base por orden: lo último confirmado por el servidor (versión + estado + notas), guardado en cada pull.
 *   Solo se envían los campos que el técnico cambió respecto de esa base; así un campo que tocó despacho
 *   no se pisa ni produce un conflicto falso (merge de 3 vías en lc_patch_order).
 * - Idempotente: (dispositivo, updatedAt del cambio) es la clave del recibo; un reintento tras perder la
 *   respuesta devuelve el mismo resultado sin reaplicar.
 * - Sin red o con error del servidor el cambio queda en la cola (synced = 0) para el siguiente intento.
 */
public final class QaOrderSync {

    private static final String TAG = "LcQaSync";
    private static final String BASES = "lc_qa_bases";

    public static final class Resultado {
        public int aceptados;
        public int conflictos;
        public int rechazados;
        public boolean reintentar;
        /** Tras un conflicto o rechazo hay que traer el estado completo del servidor. */
        public boolean pullCompleto;
    }

    private QaOrderSync() {}

    private static SharedPreferences bases(Context c) {
        return c.getApplicationContext().getSharedPreferences(BASES, Context.MODE_PRIVATE);
    }

    /** Llamar al guardar una fila recibida del servidor. */
    public static void guardarBase(Context c, SupabaseModels.JobRow row) {
        if (row.version == null) return;
        try {
            JSONObject b = new JSONObject().put("v", row.version)
                    .put("estado", row.estado == null ? JSONObject.NULL : row.estado)
                    .put("notas", row.notas == null ? JSONObject.NULL : row.notas);
            bases(c).edit().putString(String.valueOf(row.id), b.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "base no guardada " + row.id);
        }
    }

    private static String texto(JSONObject b, String k) {
        return b.isNull(k) ? null : b.optString(k);
    }

    public static Resultado subir(Context c, LcDatabase db, SupabaseClient sb, List<JobEntity> lote) {
        Resultado res = new Resultado();
        String device = DeviceIdManager.getDeviceId(c);
        long now = System.currentTimeMillis();
        for (JobEntity j : lote) {
            String raw = bases(c).getString(String.valueOf(j.id), null);
            if (raw == null) {
                // Orden creada en el teléfono: se da de alta en lc-api (idempotente) y su id local pasa al del servidor.
                crearEnServidor(c, db, sb, j, res);
                if (res.reintentar) break;
                continue;
            }
            try {
                JSONObject base = new JSONObject(raw);
                Map<String, Object> body = new HashMap<>();
                if (!Objects.equals(j.estado, texto(base, "estado"))) body.put("estado", j.estado);
                if (!Objects.equals(j.notas, texto(base, "notas"))) body.put("notas", j.notas);
                // misma marca que usa confirmarVersion: COALESCE(updatedAt, id)
                long marca = j.updatedAt != null ? j.updatedAt : j.id;
                if (body.isEmpty()) {
                    db.jobDao().confirmarVersion(j.id, marca, base.optInt("v"), now);
                    continue;
                }
                body.put("version", base.optInt("v"));
                body.put("device", device);
                body.put("updatedAt", marca);

                retrofit2.Response<okhttp3.ResponseBody> r = sb.api().patchOrder(sb.authHeader(), j.id, body).execute();
                String txt = r.isSuccessful() ? (r.body() != null ? r.body().string() : "{}")
                        : (r.errorBody() != null ? r.errorBody().string() : "{}");
                JSONObject o = txt.trim().startsWith("{") ? new JSONObject(txt) : new JSONObject();
                int code = r.code();
                if (code == 200 && "ACCEPTED".equals(o.optString("status"))) {
                    int v = o.optInt("version");
                    if (db.jobDao().confirmarVersion(j.id, marca, v, now) > 0) {
                        base.put("v", v).put("estado", j.estado == null ? JSONObject.NULL : j.estado)
                            .put("notas", j.notas == null ? JSONObject.NULL : j.notas);
                        bases(c).edit().putString(String.valueOf(j.id), base.toString()).apply();
                    }
                    res.aceptados++;
                } else if (code == 409) {
                    // El servidor gana; el conflicto queda registrado para despacho (job_conflicts).
                    db.jobDao().confirmarVersion(j.id, marca, o.optInt("version", base.optInt("v")), now);
                    res.conflictos++;
                    res.pullCompleto = true;
                    Log.w(TAG, "orden " + j.id + " CONFLICT " + o.optString("conflictFields", o.optString("motivo")));
                } else if (code == 401 || code == 429 || code >= 500) {
                    res.reintentar = true;  // vuelve a pendiente al liberar el lote
                    Log.w(TAG, "orden " + j.id + " HTTP " + code + "; se reintentará");
                } else {
                    // 403 / 404 / 422: no se reenvía hasta que el técnico la edite de nuevo
                    db.jobDao().marcarRechazado(j.id);
                    res.rechazados++;
                    res.pullCompleto = true;
                    Log.w(TAG, "orden " + j.id + " rechazada HTTP " + code + " " + o.optString("error"));
                }
            } catch (Exception e) {
                res.reintentar = true;  // sin red: el cambio sigue en cola
                Log.w(TAG, "orden " + j.id + " sin conexión: " + e.getClass().getSimpleName());
                break;
            }
        }
        return res;
    }

    /** Las órdenes locales rechazadas antes de que existiera el alta vuelven a la cola una sola vez. */
    public static void reencolarSinBase(Context c, LcDatabase db) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences(BASES, Context.MODE_PRIVATE);
        if (p.getBoolean("requeue_alta_v1", false)) return;
        for (JobEntity j : db.jobDao().getRechazados()) {
            if (p.getString(String.valueOf(j.id), null) == null && db.jobDao().reencolar(j.id) > 0) {
                Log.i(TAG, "orden local " + j.id + " vuelve a la cola para darse de alta");
            }
        }
        p.edit().putBoolean("requeue_alta_v1", true).apply();
    }

    private static void poner(Map<String, Object> b, String k, String v) {
        if (v != null && !v.trim().isEmpty()) b.put(k, v.trim());
    }

    private static void crearEnServidor(Context c, LcDatabase db, SupabaseClient sb, JobEntity j, Resultado res) {
        Map<String, Object> body = new HashMap<>();
        body.put("request_id", "tech-" + DeviceIdManager.getDeviceId(c) + "-" + j.id);
        poner(body, "cliente", j.cliente);
        poner(body, "descripcion", j.descripcion);
        poner(body, "direccion", j.direccion);
        poner(body, "telefono", j.telefono);
        poner(body, "equipo", "Por asignar".equals(j.equipo) ? null : j.equipo);
        poner(body, "tipo_propiedad", j.tipoPropiedad);
        poner(body, "tipo_acceso", j.tipoAcceso);
        poner(body, "instrucciones", j.instrucciones);
        poner(body, "mascota", j.mascota);
        poner(body, "contacto_respaldo", j.contactoRespaldo);
        if (j.programadoPara != null) body.put("programado_para", j.programadoPara);
        try {
            retrofit2.Response<okhttp3.ResponseBody> r = sb.api().createOrder(sb.authHeader(), body).execute();
            String txt = r.isSuccessful() ? (r.body() != null ? r.body().string() : "{}")
                    : (r.errorBody() != null ? r.errorBody().string() : "{}");
            JSONObject o = txt.trim().startsWith("{") ? new JSONObject(txt) : new JSONObject();
            int code = r.code();
            if ((code == 200 || code == 201) && o.optLong("id", 0) > 0) {
                long nuevo = o.getLong("id");
                int v = o.optInt("version", 1);
                long marca = j.updatedAt != null ? j.updatedAt : j.id;
                reasignarId(db, j.id, nuevo, v, System.currentTimeMillis());
                try {
                    bases(c).edit().putString(String.valueOf(nuevo), new JSONObject().put("v", v)
                            .put("estado", j.estado == null ? JSONObject.NULL : j.estado)
                            .put("notas", j.notas == null ? JSONObject.NULL : j.notas).toString()).apply();
                } catch (Exception ignored) { }
                res.aceptados++;
                res.pullCompleto = true;
                Log.i(TAG, "orden local " + j.id + " dada de alta como " + nuevo + " (marca " + marca + ")");
            } else if (code == 401 || code == 429 || code >= 500) {
                res.reintentar = true;
                Log.w(TAG, "alta de " + j.id + " HTTP " + code + "; se reintentará");
            } else {
                db.jobDao().marcarRechazado(j.id);
                res.rechazados++;
                Log.w(TAG, "alta de " + j.id + " rechazada HTTP " + code + " " + o.optString("error"));
            }
        } catch (Exception e) {
            res.reintentar = true;
            Log.w(TAG, "alta de " + j.id + " sin conexión: " + e.getClass().getSimpleName());
        }
    }

    /** Cambia el id local por el del servidor en la orden y en toda tabla con columna jobId (sin duplicar). */
    private static void reasignarId(LcDatabase db, long viejo, long nuevo, int version, long ts) {
        db.runInTransaction(() -> {
            androidx.sqlite.db.SupportSQLiteDatabase w = db.getOpenHelper().getWritableDatabase();
            java.util.List<String> tablas = new java.util.ArrayList<>();
            try (android.database.Cursor t = w.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                    + " AND name NOT IN ('android_metadata','room_master_table','jobs')")) {
                while (t.moveToNext()) tablas.add(t.getString(0));
            }
            for (String tn : tablas) {
                boolean tiene = false;
                try (android.database.Cursor cols = w.query("PRAGMA table_info(`" + tn + "`)")) {
                    while (cols.moveToNext()) if ("jobId".equals(cols.getString(1))) tiene = true;
                }
                if (tiene) w.execSQL("UPDATE `" + tn + "` SET jobId = ? WHERE jobId = ?", new Object[]{nuevo, viejo});
            }
            w.execSQL("UPDATE jobs SET id = ?, synced = 1, lastSyncedAt = ?, serverVersion = ? WHERE id = ?",
                    new Object[]{nuevo, ts, version, viejo});
        });
    }
}
