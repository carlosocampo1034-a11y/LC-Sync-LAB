package com.lc.technician.sync;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.lc.technician.data.JobRepository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Oferta automática de trabajo (variante QA): mientras la pantalla principal está abierta consulta a lc-api cada pocos
 * segundos y muestra «NUEVA OFERTA DE TRABAJO» con ACEPTAR / RECHAZAR. El servidor decide quién se queda la orden:
 * si otro técnico la tomó antes, aceptar responde 409 y aquí solo se avisa. Sin red no pasa nada (se reintenta).
 */
public final class OfertasPoller {

    private static final String TAG = "LcOfertas";
    private static final long CADA_MS = 6_000L;

    private final Activity act;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean activo;
    private boolean consultando;
    private AlertDialog dialogo;
    private long ofertaMostrada = -1;
    private long expiraMs;
    private long desfaseReloj;   // reloj del servidor − reloj local

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!activo) return;
            consultar();
            ui.postDelayed(this, CADA_MS);
        }
    };

    private final Runnable cuenta = new Runnable() {
        @Override public void run() {
            if (!activo || dialogo == null) return;
            long resta = expiraMs - (System.currentTimeMillis() + desfaseReloj);
            if (resta <= 0) { cerrar(); return; }
            dialogo.setMessage(mensajeBase + String.format(Locale.US, "\nResponde en %d:%02d", resta / 60000, (resta / 1000) % 60));
            ui.postDelayed(this, 1000);
        }
    };

    private String mensajeBase = "";

    public OfertasPoller(Activity act) { this.act = act; }

    public void iniciar() {
        if (activo) return;
        activo = true;
        ui.post(tick);
    }

    public void detener() {
        activo = false;
        ui.removeCallbacksAndMessages(null);
        cerrar();
    }

    private void cerrar() {
        if (dialogo != null) { try { dialogo.dismiss(); } catch (Exception ignored) { } }
        dialogo = null;
        ofertaMostrada = -1;
    }

    private void consultar() {
        if (consultando) return;
        consultando = true;
        io.execute(() -> {
            try {
                SupabaseClient sb = SupabaseClient.get(act);
                if (sb.ensureAuthSync() != null) return;
                retrofit2.Response<okhttp3.ResponseBody> r = sb.api().offers(sb.authHeader()).execute();
                if (!r.isSuccessful() || r.body() == null) return;
                JSONObject o = new JSONObject(r.body().string());
                desfaseReloj = o.optLong("serverNow", System.currentTimeMillis()) - System.currentTimeMillis();
                JSONArray arr = o.optJSONArray("offers");
                JSONObject primera = arr != null && arr.length() > 0 ? arr.getJSONObject(0) : null;
                ui.post(() -> mostrar(primera));
            } catch (Exception e) {
                Log.w(TAG, "consulta de ofertas: " + e.getClass().getSimpleName());
            } finally {
                consultando = false;
            }
        });
    }

    private void mostrar(JSONObject of) {
        if (!activo || act.isFinishing()) return;
        if (of == null) {
            if (dialogo != null) {   // la oferta mostrada venció o se la llevó otro técnico
                cerrar();
                Toast.makeText(act, "La oferta ya no está disponible", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        long id = of.optLong("id");
        if (dialogo != null && ofertaMostrada == id) return;
        cerrar();
        ofertaMostrada = id;
        expiraMs = of.optLong("expiresAt");
        JSONObject j = of.optJSONObject("job");
        StringBuilder m = new StringBuilder();
        if (j != null) {
            linea(m, "Servicio", j.optString("categoria", ""));
            linea(m, "Problema", j.optString("descripcion", ""));
            linea(m, "Dirección", j.optString("direccion", ""));
            linea(m, "Propiedad", j.optString("tipo_propiedad", ""));
            linea(m, "Ventana", j.optString("ventana_preferida", ""));
        }
        mensajeBase = m.toString();
        dialogo = new AlertDialog.Builder(act)
                .setTitle("NUEVA OFERTA DE TRABAJO")
                .setMessage(mensajeBase)
                .setCancelable(false)
                .setPositiveButton("ACEPTAR", (d, w) -> responder(id, true))
                .setNegativeButton("RECHAZAR", (d, w) -> responder(id, false))
                .create();
        dialogo.show();
        ui.removeCallbacks(cuenta);
        ui.post(cuenta);
    }

    private static void linea(StringBuilder b, String k, String v) {
        if (v != null && !v.trim().isEmpty() && !"null".equals(v)) b.append(k).append(": ").append(v.trim()).append('\n');
    }

    private void responder(long id, boolean aceptar) {
        ofertaMostrada = -1;
        dialogo = null;
        ui.removeCallbacks(cuenta);
        io.execute(() -> {
            String msg;
            boolean ok = false;
            try {
                SupabaseClient sb = SupabaseClient.get(act);
                if (sb.ensureAuthSync() != null) throw new java.io.IOException("sin sesión");
                HashMap<String, Object> body = new HashMap<>();
                retrofit2.Response<okhttp3.ResponseBody> r = aceptar
                        ? sb.api().acceptOffer(sb.authHeader(), id, body).execute()
                        : sb.api().rejectOffer(sb.authHeader(), id, body).execute();
                String txt = r.isSuccessful() ? (r.body() != null ? r.body().string() : "{}")
                        : (r.errorBody() != null ? r.errorBody().string() : "{}");
                JSONObject o = txt.trim().startsWith("{") ? new JSONObject(txt) : new JSONObject();
                ok = r.code() == 200;
                msg = ok ? (aceptar ? "Orden asignada a ti" : "Oferta rechazada")
                         : o.optString("mensaje", "No se pudo completar (" + r.code() + ")");
            } catch (Exception e) {
                msg = "Sin conexión; vuelve a intentarlo";
            }
            final String texto = msg;
            final boolean exito = ok;
            ui.post(() -> {
                Toast.makeText(act, texto, Toast.LENGTH_LONG).show();
                if (exito && aceptar) JobRepository.get().solicitarSyncInmediata();   // la orden llega a «Mis Jobs»
                if (activo) consultar();
            });
        });
    }
}
