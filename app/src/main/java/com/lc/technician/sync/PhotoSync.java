package com.lc.technician.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.PhotoEntity;

import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Variante QA: sube las fotos de evidencia a Supabase Storage (por lc-api) y recuerda cuáles ya están en el servidor.
 *
 * - Idempotente: clientPhotoId = id local de la foto; un reintento devuelve la misma foto, no otra.
 * - Nunca borra una foto del teléfono. Una foto que no se puede subir se conserva.
 * - Cuarentena: una foto cuya orden ya no existe en el servidor (o es anterior a esa orden: ids reutilizados tras limpiar la
 *   base) pasa a jobId negativo; sigue en el teléfono pero no se muestra bajo otra orden ni se sube a la equivocada.
 */
public final class PhotoSync {

    private static final String TAG = "LcPhotoSync";
    private static final String PREFS = "lc_qa_photos_up";
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    public static final class Resultado {
        public int subidas;
        public int enCuarentena;
        /** Órdenes con fotos que no se pudieron subir: no deben purgarse. */
        public final Set<Long> ordenesConFotosPendientes = new HashSet<>();
    }

    private PhotoSync() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean subida(Context c, long photoId) {
        return prefs(c).getBoolean("up_" + photoId, false);
    }

    /** Solo las fotos hechas desde esta versión se suben solas; las anteriores solo por la resincronización verificada. */
    private static long desde(Context c) {
        SharedPreferences p = prefs(c);
        long t = p.getLong("auto_desde", 0);
        if (t == 0) { t = System.currentTimeMillis(); p.edit().putLong("auto_desde", t).apply(); }
        return t;
    }

    private static String categoria(String c) {
        if (c == null) return "OTHER";
        String n = c.toUpperCase(java.util.Locale.ROOT);
        if (n.startsWith("ANTES")) return "BEFORE";
        if (n.startsWith("DESPU")) return "AFTER";
        return "OTHER";
    }

    /** 1 = en el servidor, 0 = no se pudo (red/servidor), -1 = la orden no es accesible (404) o la foto no es subible. */
    static int subir(Context c, SupabaseClient sb, PhotoEntity p) {
        try {
            File f = new File(p.path == null ? "" : p.path);
            if (!f.isFile() || f.length() < 100 || f.length() > MAX_BYTES) return -1;
            byte[] datos;
            try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                datos = bo.toByteArray();
            }
            boolean jpg = (datos[0] & 0xff) == 0xff && (datos[1] & 0xff) == 0xd8;
            boolean png = (datos[0] & 0xff) == 0x89 && datos[1] == 0x50;
            if (!jpg && !png) return -1;
            Map<String, Object> body = new HashMap<>();
            body.put("mime", jpg ? "image/jpeg" : "image/png");
            body.put("categoria", categoria(p.categoria));
            body.put("clientPhotoId", p.id);
            body.put("datosBase64", Base64.encodeToString(datos, Base64.NO_WRAP));
            retrofit2.Response<okhttp3.ResponseBody> r = sb.api().uploadPhoto(sb.authHeader(), p.jobId, body).execute();
            if (r.code() == 200 || r.code() == 201) {
                prefs(c).edit().putBoolean("up_" + p.id, true).apply();
                return 1;
            }
            Log.w(TAG, "foto " + p.id + " orden " + p.jobId + " HTTP " + r.code());
            return r.code() == 404 || r.code() == 403 || r.code() == 422 || r.code() == 400 ? -1 : 0;
        } catch (Exception e) {
            Log.w(TAG, "foto " + p.id + " sin conexión: " + e.getClass().getSimpleName());
            return 0;
        }
    }

    /** Sincronización normal: sube las fotos nuevas de órdenes que el servidor ya conoce. */
    public static int sincronizarNuevas(Context c, LcDatabase db, SupabaseClient sb) {
        long corte = desde(c);
        int n = 0;
        for (PhotoEntity p : db.photoDao().getAll()) {
            if (p.jobId <= 0 || p.timestamp < corte || subida(c, p.id)) continue;
            com.lc.technician.model.JobEntity j = db.jobDao().getJobById(p.jobId);
            if (j == null || j.synced != 1) continue;   // la orden aún no está confirmada en el servidor
            if (subir(c, sb, p) == 1) n++;
        }
        return n;
    }

    /**
     * Antes de purgar: sube lo subible, pone en cuarentena lo que no pertenece a una orden del servidor y devuelve
     * qué órdenes tienen fotos pendientes (esas no se deben borrar).
     * @param visibles id de orden → created_at (ms) de las órdenes que el servidor devuelve a este técnico
     */
    public static Resultado procesarParaPurga(Context c, LcDatabase db, SupabaseClient sb, Map<Long, Long> visibles) {
        Resultado res = new Resultado();
        for (PhotoEntity p : db.photoDao().getAll()) {
            if (p.jobId <= 0) continue;
            Long creada = visibles.get(p.jobId);
            boolean esDeEstaOrden = creada != null && (creada == 0 || p.timestamp >= creada);
            if (!esDeEstaOrden) {
                db.photoDao().moverAJob(p.id, -Math.abs(p.jobId));
                res.enCuarentena++;
                continue;
            }
            if (subida(c, p.id)) continue;
            int r = subir(c, sb, p);
            if (r == 1) res.subidas++;
            else res.ordenesConFotosPendientes.add(p.jobId);   // no se pudo: la orden y sus fotos se conservan
        }
        return res;
    }
}
