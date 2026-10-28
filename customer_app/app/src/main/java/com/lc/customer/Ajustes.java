package com.lc.customer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Configuración dinámica (tabla settings en Supabase QA): precios y listas de los desplegables.
 * Se descarga al abrir sesión y se refresca sola cada minuto; queda en caché local (SharedPreferences) para trabajar sin
 * conexión. Si una clave aún no se descargó, se usa el valor de respaldo, que coincide con el valor inicial de la tabla.
 */
final class Ajustes {

    static final class Opcion {
        final String id, label, clienteLabel, grupo;
        final boolean activa;
        Opcion(String id, String label, String clienteLabel, String grupo, boolean activa) {
            this.id = id; this.label = label; this.clienteLabel = clienteLabel; this.grupo = grupo; this.activa = activa;
        }
    }

    private static final String PREFS = "lc_ajustes";
    private static final long MIN_ENTRE_DESCARGAS_MS = 30_000;
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static long ultimaDescarga = 0;
    private static boolean descargando = false;

    private Ajustes() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject datos(Context c) {
        try { return new JSONObject(prefs(c).getString("datos", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    /** Descarga los cambios (solo lo modificado desde la última vez). alTerminar corre en el hilo principal. */
    static void refrescar(Context c, boolean forzar, Runnable alTerminar) {
        final Context app = c.getApplicationContext();
        synchronized (Ajustes.class) {
            if (descargando || (!forzar && System.currentTimeMillis() - ultimaDescarga < MIN_ENTRE_DESCARGAS_MS)) return;
            descargando = true;
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean cambio = false;
            try {
                long desde = prefs(app).getLong("desde", 0);
                Api.Respuesta r = Api.get(app, "/customer/settings" + (desde > 0 ? "?since=" + desde : ""));
                if (r.ok() && r.cuerpo != null) {
                    JSONArray filas = r.cuerpo.optJSONArray("settings");
                    JSONObject d = datos(app);
                    long max = desde;
                    for (int i = 0; filas != null && i < filas.length(); i++) {
                        JSONObject f = filas.optJSONObject(i);
                        d.put(f.optString("key"), new JSONObject().put("v", f.opt("value")).put("ver", f.optInt("version")));
                        max = Math.max(max, f.optLong("updated_at"));
                        cambio = true;
                    }
                    if (cambio) prefs(app).edit().putString("datos", d.toString()).putLong("desde", max).apply();
                }
            } catch (Exception ignored) {
            } finally {
                synchronized (Ajustes.class) { descargando = false; ultimaDescarga = System.currentTimeMillis(); }
            }
            final boolean huboCambio = cambio;
            if (alTerminar != null && huboCambio) UI.post(alTerminar);
        });
    }

    static void limpiar(Context c) { prefs(c).edit().clear().apply(); }

    /** Valor de texto de una setting (p. ej. payments.mode). */
    static String texto(Context c, String key, String respaldo) {
        JSONObject f = datos(c).optJSONObject(key);
        return f == null || f.isNull("v") ? respaldo : f.optString("v", respaldo);
    }

    static double numero(Context c, String key, double respaldo) {
        JSONObject f = datos(c).optJSONObject(key);
        return f == null ? respaldo : f.optDouble("v", respaldo);
    }

    /** Opciones de una lista; {id, label, clienteLabel, grupo}. soloActivas=false incluye las desactivadas (para mostrar historial). */
    static List<Opcion> lista(Context c, String key, String[][] respaldo, boolean soloActivas) {
        List<Opcion> out = new ArrayList<>();
        JSONObject f = datos(c).optJSONObject(key);
        JSONArray a = f == null ? null : f.optJSONArray("v");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                boolean activa = o.optBoolean("active", true);
                if (soloActivas && !activa) continue;
                out.add(new Opcion(o.optString("id"), o.optString("label"), o.optString("customer_label", ""),
                        o.optString("price_group", "general"), activa));
            }
            if (!out.isEmpty()) return out;
        }
        for (String[] r : respaldo) {
            out.add(new Opcion(r[0], r[1], r.length > 2 ? r[2] : "", r.length > 3 ? r[3] : "general", true));
        }
        return out;
    }

    static String etiqueta(Context c, String key, String[][] respaldo, String id) {
        for (Opcion o : lista(c, key, respaldo, false)) if (o.id.equals(id)) return o.label;
        return id;
    }

    // ── Respaldos = valores iniciales de la tabla settings (deben coincidir; ver supabase/migrations/20261028160000_settings.sql) ──
    static final String[][] CATEGORIAS = {   // las 4 categorías oficiales del negocio, en este orden
            {"PLOMERIA", "Plomería", "", "plumbing"}, {"ELECTRICIDAD", "Electricidad básica", "", "electricity"},
            {"HVAC", "Aire Acondicionado (A/C)", "", "hvac"}, {"ELECTRODOMESTICOS", "Appliances / Electrodomésticos", "", "general"}};
    static final String[][] TIPOS_PROPIEDAD = {{"Casa", "Casa"}, {"Apartamento", "Apartamento"}, {"Oficina", "Oficina"}, {"Hotel", "Hotel"}, {"Otro", "Otro"}};
    static final String[][] ACCESOS = {{"Ninguno", "Ninguno"}, {"Código puerta", "Código puerta"}, {"Garaje", "Garaje"},
            {"Lockbox", "Lockbox"}, {"Vecino", "Vecino"}, {"Llave escondida", "Llave escondida"}};
    static final String[][] ESTADOS = {
            {"PENDIENTE", "Pendiente", "Recibida"}, {"EN RUTA", "En ruta", "Tu técnico va en camino"}, {"LLEGUÉ", "Llegué", "Técnico en el lugar"},
            {"EN REPARACIÓN", "En reparación", "En reparación"}, {"ESPERANDO APROBACIÓN", "Esperando aprobación", "Esperando tu aprobación"},
            {"PART HOLD", "Falta repuesto", "Esperando repuesto"}, {"PAUSADO", "Pausado", "Pausada"},
            {"COMPLETADO", "Completado", "Trabajo completado"}, {"CANCELADO", "Cancelado", "Cancelada"}};

    /** Texto del estado para el cliente (editable en Control → Listas editables → Estados de la orden). */
    static String estadoCliente(Context c, String estado) {
        if (estado == null) return "—";
        for (Opcion o : lista(c, "order_states.list", ESTADOS, false)) {
            if (o.id.equals(estado)) return o.clienteLabel.isEmpty() ? o.label : o.clienteLabel;
        }
        return estado;
    }

    /** Precio de referencia de un grupo: pricing.<grupo>.<concepto>. */
    static double precio(Context c, String grupo, String concepto) {
        double resp = concepto.equals("emergency_multiplier") ? 1.0 : 50.0;
        switch (grupo + "." + concepto) {
            case "hvac.diagnostic": resp = 90.0; break;
            case "hvac.hourly": resp = 85.0; break;
            case "hvac.emergency_multiplier": resp = 1.5; break;
            case "plumbing.diagnostic": case "electricity.diagnostic": resp = 65.0; break;
            case "plumbing.hourly": case "electricity.hourly": resp = 50.0; break;
            case "plumbing.emergency_multiplier": case "electricity.emergency_multiplier": resp = 1.5; break;
            default: break;
        }
        return numero(c, "pricing." + grupo + "." + concepto, resp);
    }
}
