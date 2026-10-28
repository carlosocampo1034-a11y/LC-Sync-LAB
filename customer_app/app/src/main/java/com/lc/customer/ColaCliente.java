package com.lc.customer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Cola persistente de envíos críticos del cliente (crear solicitud, pagar, decidir una propuesta). Si la red falla incluso tras los
 * reintentos, el envío se guarda y se reintenta solo cuando vuelve la conexión. Cada envío lleva su identificador único
 * (request_id de la solicitud, clave de idempotencia del pago), así que reenviar no duplica nada en el servidor.
 */
final class ColaCliente {

    private static final String PREFS = "lc_cliente_cola";

    private ColaCliente() {}

    private static SharedPreferences p(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static JSONArray leer(Context c) {
        try { return new JSONArray(p(c).getString("items", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    static synchronized void agregar(Context c, String metodo, String ruta, JSONObject body) {
        try {
            JSONArray a = leer(c);
            a.put(new JSONObject().put("metodo", metodo).put("ruta", ruta).put("cuerpo", body == null ? new JSONObject() : body)
                    .put("creado", System.currentTimeMillis()));
            p(c).edit().putString("items", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    static int pendientes(Context c) { return leer(c).length(); }

    static synchronized void borrarTodo(Context c) { p(c).edit().remove("items").apply(); }

    /** Envía lo pendiente en orden. Se detiene en el primer fallo de red. Llamar fuera del hilo principal. */
    static synchronized int vaciar(Context c) {
        if (Api.prefs(c).getString("token", null) == null) return 0;   // sin sesión no se envía nada
        JSONArray a = leer(c);
        if (a.length() == 0) return 0;
        JSONArray resto = new JSONArray();
        int enviados = 0;
        boolean cortado = false;
        for (int i = 0; i < a.length(); i++) {
            JSONObject it = a.optJSONObject(i);
            if (it == null) continue;
            if (cortado) { resto.put(it); continue; }
            try {
                Api.Respuesta r = CloudApi.reenviar(c, it.optString("metodo"), it.optString("ruta"), it.optJSONObject("cuerpo"));
                if (r.codigo == -1 || r.codigo == 401) { resto.put(it); cortado = true; continue; }   // sigue sin red o sin sesión
                if (r.ok()) {
                    enviados++;
                    avisar(c, it, r);
                }                                                      // aceptado o rechazo definitivo (4xx): sale de la cola
            } catch (Exception e) { resto.put(it); cortado = true; }
        }
        p(c).edit().putString("items", resto.toString()).apply();
        return enviados;
    }

    private static void avisar(Context c, JSONObject it, Api.Respuesta r) {
        final String t = "/orders".equals(it.optString("ruta")) ? "Tu solicitud Nº " + (r.cuerpo == null ? "" : r.cuerpo.optLong("id")) + " ya fue enviada."
                : it.optString("ruta").contains("pay-test") ? "Tu pago de prueba quedó registrado."
                : "Tu decisión quedó registrada.";
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(c.getApplicationContext(), t, Toast.LENGTH_LONG).show());
    }
}
