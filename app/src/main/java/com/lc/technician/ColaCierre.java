package com.lc.technician;

import android.content.Context;
import android.util.Log;

import com.lc.technician.sync.SupabaseClient;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Cola persistente del cierre del trabajo (firma o negativa). Si la red falla (incluso tras los reintentos), el envío se guarda en
 * el almacenamiento PRIVADO de la app y se reintenta solo cuando vuelve la conexión: no se pierde la firma del cliente. El archivo
 * se borra en cuanto el servidor lo acepta (o lo rechaza de forma definitiva) y al cerrar sesión; el servidor es idempotente
 * (una fila por orden), así que reenviar no duplica nada.
 */
public final class ColaCierre {

    private static final String TAG = "LcColaCierre";

    private ColaCierre() {}

    private static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), "cola_cierre");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static synchronized void agregar(Context c, long jobId, Map<String, Object> body) {
        try (FileOutputStream o = new FileOutputStream(new File(dir(c), jobId + ".json"))) {
            JSONObject j = new JSONObject(body);
            j.put("jobId", jobId);
            o.write(j.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "no se pudo guardar el envío: " + e.getClass().getSimpleName());
        }
    }

    public static int pendientes(Context c) {
        String[] n = dir(c).list();
        return n == null ? 0 : n.length;
    }

    public static synchronized void borrarTodo(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    /** Intenta enviar lo pendiente. Devuelve cuántos se enviaron. Llamar fuera del hilo principal. */
    public static synchronized int vaciar(Context c) {
        File[] fs = dir(c).listFiles();
        if (fs == null || fs.length == 0) return 0;
        int enviados = 0;
        try {
            SupabaseClient sb = SupabaseClient.get(c);
            if (sb.ensureAuthSync() != null) return 0;   // sin sesión: se reintenta más tarde
            for (File f : fs) {
                JSONObject j;
                try (FileInputStream in = new FileInputStream(f)) {
                    j = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                } catch (Exception corrupto) { //noinspection ResultOfMethodCallIgnored
                    f.delete(); continue; }
                long jobId = j.optLong("jobId");
                j.remove("jobId");
                Map<String, Object> body = new java.util.HashMap<>();
                for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) { String k = it.next(); body.put(k, j.get(k)); }
                retrofit2.Response<okhttp3.ResponseBody> r = sb.api().closurePost(sb.authHeader(), jobId, body).execute();
                if (r.isSuccessful()) {                                              // aceptado: sale de la cola
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    enviados++;
                } else if (r.code() >= 400 && r.code() < 500) {
                    String cuerpo = r.errorBody() != null ? r.errorBody().string() : "";
                    // el diagnóstico o la foto todavía no llegaron al servidor (se suben con la sincronización): NO se borra la firma,
                    // se vuelve a intentar cuando terminen de subirse
                    boolean faltaPrevio = r.code() == 422 && (cuerpo.contains("diagnostico_corto") || cuerpo.contains("sin_fotos"));
                    if (!faltaPrevio) {   // rechazo definitivo (p. ej. la orden ya no está en reparación)
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "se reintentará: " + e.getClass().getSimpleName());   // sin red: el archivo se conserva
        }
        return enviados;
    }
}
