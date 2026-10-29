package com.lc.technician.sync;

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
 * Listas editables de los desplegables (tabla settings, filas *.list). Caché local en SharedPreferences (sin Room: evita
 * migrar el esquema en teléfonos con datos). Se refresca sola cada ≥30 s al abrir pantallas y tras cada sincronización.
 * Los valores de respaldo coinciden con los iniciales de la tabla.
 */
public final class Listas {

    /** ids = lo que se guarda; labels = lo que se muestra. Una opción vacía (id "") hace de «sin especificar». */
    public static final class Lista {
        public final String[] ids;
        public final String[] labels;
        Lista(List<String> i, List<String> l) { ids = i.toArray(new String[0]); labels = l.toArray(new String[0]); }
        public int indexOf(String id) { for (int k = 0; k < ids.length; k++) if (ids[k].equals(id)) return k; return -1; }
    }

    private static final String PREFS = "lc_listas";
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static long ultima = 0;
    private static boolean descargando = false;

    private Listas() {}

    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static JSONObject datos(Context c) {
        try { return new JSONObject(prefs(c).getString("datos", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    public static void refrescar(Context c, boolean forzar, Runnable alCambiar) {
        final Context app = c.getApplicationContext();
        synchronized (Listas.class) {
            if (descargando || (!forzar && System.currentTimeMillis() - ultima < 30_000)) return;
            descargando = true;
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean cambio = false;
            try {
                SupabaseClient sb = SupabaseClient.get(app);
                if (sb.hasUserSession()) {
                    long desde = prefs(app).getLong("desde", 0);
                    retrofit2.Response<okhttp3.ResponseBody> r = sb.api().settings(sb.authHeader(), desde > 0 ? String.valueOf(desde) : null).execute();
                    if (r.isSuccessful() && r.body() != null) {
                        JSONArray filas = new JSONObject(r.body().string()).optJSONArray("settings");
                        JSONObject d = datos(app);
                        long max = desde;
                        for (int i = 0; filas != null && i < filas.length(); i++) {
                            JSONObject f = filas.optJSONObject(i);
                            d.put(f.optString("key"), f.opt("value"));
                            max = Math.max(max, f.optLong("updated_at"));
                            cambio = true;
                        }
                        if (cambio) prefs(app).edit().putString("datos", d.toString()).putLong("desde", max).apply();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                synchronized (Listas.class) { descargando = false; ultima = System.currentTimeMillis(); }
            }
            if (cambio && alCambiar != null) UI.post(alCambiar);
        });
    }

    /**
     * @param respaldo pares {id, label} usados si la clave aún no se descargó
     * @param vacio    texto de la opción «sin especificar» (id ""), o null si la lista no la lleva
     */
    public static Lista lista(Context c, String key, String[][] respaldo, String vacio) {
        List<String> ids = new ArrayList<>(), labels = new ArrayList<>();
        if (vacio != null) { ids.add(""); labels.add(vacio); }
        JSONArray a = datos(c).optJSONArray(key);
        boolean hay = false;
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || !o.optBoolean("active", true)) continue;
                ids.add(o.optString("id")); labels.add(o.optString("label")); hay = true;
            }
        }
        if (!hay) for (String[] p : respaldo) { ids.add(p[0]); labels.add(p[1]); }
        return new Lista(ids, labels);
    }

    /** Opciones activas de una lista con todos sus campos (p. ej. plantillas con sus 3 opciones). */
    public static List<JSONObject> activos(Context c, String key) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray a = datos(c).optJSONArray(key);
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.optBoolean("active", true)) out.add(o);
        }
        return out;
    }

    /** Valor numérico de una setting (precios, márgenes); respaldo si aún no se descargó. */
    public static double numero(Context c, String key, double respaldo) {
        JSONObject d = datos(c);
        return d.has(key) ? d.optDouble(key, respaldo) : respaldo;
    }

    /** La categoría de cada orden se recuerda aparte para no cambiar el esquema de Room. */
    public static void guardarCategoria(Context c, long jobId, String categoria) {
        if (categoria != null) c.getApplicationContext().getSharedPreferences("lc_job_cat", Context.MODE_PRIVATE).edit().putString(String.valueOf(jobId), categoria).apply();
    }

    public static String categoriaDe(Context c, long jobId) {
        return c.getApplicationContext().getSharedPreferences("lc_job_cat", Context.MODE_PRIVATE).getString(String.valueOf(jobId), null);
    }

    /** Grupo de precios (hvac, plumbing, electricity, general) de una categoría, según categories.list → price_group. */
    public static String grupoPrecio(Context c, String categoriaId) {
        JSONArray a = datos(c).optJSONArray("categories.list");
        for (int i = 0; a != null && categoriaId != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && categoriaId.equals(o.optString("id"))) return o.optString("price_group", "general");
        }
        switch (categoriaId == null ? "" : categoriaId) {   // respaldo = valor inicial de la tabla
            case "HVAC": return "hvac";
            case "PLOMERIA": return "plumbing";
            case "ELECTRICIDAD": return "electricity";
            default: return "general";
        }
    }

    /** Precio de referencia pricing.<grupo>.<concepto>. */
    public static double precio(Context c, String grupo, String concepto) {
        double resp = concepto.equals("emergency_multiplier") ? 1.0 : 50.0;
        switch (grupo + "." + concepto) {
            case "hvac.diagnostic": resp = 90.0; break;
            case "hvac.hourly": resp = 85.0; break;
            case "hvac.emergency_multiplier": case "plumbing.emergency_multiplier": case "electricity.emergency_multiplier": resp = 1.5; break;
            case "plumbing.diagnostic": case "electricity.diagnostic": resp = 65.0; break;
            default: break;
        }
        return numero(c, "pricing." + grupo + "." + concepto, resp);
    }

    /** Texto de un id (incluye opciones desactivadas, para mostrar datos históricos). */
    /** Texto de un estado en pantalla: «EN RUTA» se muestra «En camino» como la maqueta (el dato del servidor no cambia). */
    public static String etiquetaEstado(Context c, String id) {
        if ("EN RUTA".equals(id)) return "En camino";
        String e = id == null ? "" : id;
        return etiqueta(c, "order_states.list", e, e);
    }

    public static String etiqueta(Context c, String key, String id, String respaldo) {
        JSONArray a = datos(c).optJSONArray(key);
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && id != null && id.equals(o.optString("id"))) return o.optString("label", respaldo);
        }
        return respaldo;
    }
}
