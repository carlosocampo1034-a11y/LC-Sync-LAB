package com.lc.technician.sync;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Sesión con el backend de LABORATORIO (sync v2). El token vive solo en las preferencias privadas
 * de la app (no se registra en logs). Sin token, la app usa el flujo histórico.
 */
public final class LabSession {

    private static final String PREFS = "lc_lab_session";
    public static final String LAB_BASE_URL = com.lc.technician.BuildConfig.SUPABASE_URL + "/";
    /** Prueba con dos teléfonos: HTTPS por la Wi-Fi privada, con la CA de laboratorio (network_security_config). */
    public static final String LAB_LAN_URL = "https://10.0.0.121:5443/api/";

    /** Servidor elegido. Permite override explícito vía "lab_http_url" para pruebas locales. */
    public static String urlServidor(Context c) {
        String override = p(c).getString("lab_http_url", null);
        if (override != null && !override.isEmpty()) return override;
        if ("com.lc.technician.dev".equals(c.getPackageName())) {
            return com.lc.technician.BuildConfig.SUPABASE_URL + "/";
        }
        return "WIFI".equals(p(c).getString("servidor", "LOCAL")) ? LAB_LAN_URL : LAB_BASE_URL;
    }

    /** Configura el override de URL HTTP para backend LAB local. null para limpiar. */
    public static void setLabHttpUrl(Context c, String url) {
        if (url == null || url.isEmpty()) p(c).edit().remove("lab_http_url").apply();
        else p(c).edit().putString("lab_http_url", url).apply();
    }

    public static boolean servidorWifi(Context c) { return "WIFI".equals(p(c).getString("servidor", "LOCAL")); }

    public static void setServidorWifi(Context c, boolean wifi) {
        p(c).edit().putString("servidor", wifi ? "WIFI" : "LOCAL").apply();
    }

    private LabSession() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean activa(Context c) {
        return p(c).getString("token", null) != null;
    }

    public static String authHeader(Context c) {
        String t = p(c).getString("token", null);
        return t == null ? null : "Bearer " + t;
    }

    public static void guardar(Context c, SyncModels.LoginResponse r, String usuario) {
        SharedPreferences.Editor e = p(c).edit().putString("token", r.token).putString("usuario", usuario)
                .putString("technicianId", r.technicianId).putString("nombre", r.displayName);
        if (r.idBase != null && r.idLimite != null) e.putLong("idBase", r.idBase).putLong("idLimite", r.idLimite);
        e.apply();
    }

    /** Conflictos pendientes por orden, según la última respuesta del servidor (solo el número, sin valores). */
    public static void setConflictos(Context c, long jobId, int n) {
        SharedPreferences.Editor e = p(c).edit();
        if (n > 0) e.putInt("conf_" + jobId, n); else e.remove("conf_" + jobId);
        e.apply();
    }

    public static int conflictos(Context c, long jobId) { return p(c).getInt("conf_" + jobId, 0); }

    /** Descuento autorizado por despacho para una orden (lo informa el servidor en el pull). */
    public static void setDescuento(Context c, long jobId, double v) {
        SharedPreferences.Editor e = p(c).edit();
        if (v > 0) e.putFloat("desc_" + jobId, (float) v); else e.remove("desc_" + jobId);
        e.apply();
    }

    public static double descuento(Context c, long jobId) { return p(c).getFloat("desc_" + jobId, 0f); }

    public static void guardarEspacio(Context c, long base, long limite) {
        if (base > 0 && limite > base) p(c).edit().putLong("idBase", base).putLong("idLimite", limite).apply();
    }

    /** Espacio de ids de este dispositivo asignado por el servidor; null si no hay (sin sesión o servidor viejo). */
    public static long[] espacioIds(Context c) {
        long base = p(c).getLong("idBase", 0), lim = p(c).getLong("idLimite", 0);
        return base > 0 && lim > base ? new long[]{base, lim} : null;
    }

    /**
     * Cierra la sesión (o la invalida tras un 401) borrando solo la identidad y el cursor de descarga. Se conservan
     * los datos de sincronización del dispositivo: marcas de fotos/eventos confirmados (evitan reenvíos), borrados
     * pendientes (no se pierden), espacio de ids del dispositivo y avisos de conflicto. El cursor vuelve a 0 para que
     * quien inicie sesión reciba todas sus órdenes (la descarga es idempotente).
     */
    public static void cerrar(Context c) {
        p(c).edit().remove("token").remove("usuario").remove("technicianId").remove("nombre").remove("cursor")
                .apply();
    }

    public static String usuario(Context c) { return p(c).getString("usuario", null); }
    public static String technicianId(Context c) { return p(c).getString("technicianId", null); }

    public static long cursor(Context c) { return p(c).getLong("cursor", 0); }
    public static void setCursor(Context c, long v) { p(c).edit().putLong("cursor", v).apply(); }

    /** Fotos/eventos ya confirmados por el servidor (no se reenvían). */
    public static java.util.Set<String> confirmados(Context c, String tipo) {
        return new java.util.HashSet<>(p(c).getStringSet("ok_" + tipo, new java.util.HashSet<>()));
    }

    public static void quitarConfirmados(Context c, String tipo, java.util.Collection<String> ids) {
        java.util.Set<String> s = confirmados(c, tipo);
        s.removeAll(ids);
        p(c).edit().putStringSet("ok_" + tipo, s).apply();
    }

    public static void agregarConfirmados(Context c, String tipo, java.util.Collection<String> ids) {
        java.util.Set<String> s = confirmados(c, tipo);
        s.addAll(ids);
        p(c).edit().putStringSet("ok_" + tipo, s).apply();
    }
}
