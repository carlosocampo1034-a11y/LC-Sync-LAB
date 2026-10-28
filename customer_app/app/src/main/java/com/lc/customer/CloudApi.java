package com.lc.customer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Variante QA: traduce las rutas del contrato v2 que usan las pantallas a Supabase LC-Sync-QA
 * (Supabase Auth + Edge Function lc-api). Las pantallas no cambian.
 * - Sesión por cliente (access + refresh token); nunca hay contraseñas en la APK.
 * - Propiedades y equipos van a Supabase (lc-api /properties) con copia local y cola de altas sin conexión.
 * - Lo que lc-api aún no ofrece (fotos, propuestas, PDF) responde 501 con un mensaje claro.
 * Llamar SIEMPRE fuera del hilo principal.
 */
final class CloudApi {

    private static final String LOCAL = "lc_customer_qa_local";

    private CloudApi() {}

    static boolean activo() { return !BuildConfig.SUPABASE_URL.isEmpty(); }

    static String base() { return BuildConfig.SUPABASE_URL + "/functions/v1/lc-api"; }

    static Api.Respuesta llamar(Context c, String metodo, String ruta, JSONObject body) {
        try {
            if ("POST".equals(metodo) && "/auth/login".equals(ruta)) return login(c, body);
            if ("POST".equals(metodo) && "/auth/register".equals(ruta)) return registrar(c, body);
            if ("/customer/properties".equals(ruta)) {
                return "GET".equals(metodo) ? propiedades(c) : nuevaPropiedad(c, body);
            }
            if ("POST".equals(metodo) && ruta.matches("/customer/properties/\\d+/equipment")) {
                return nuevoEquipo(c, Long.parseLong(ruta.split("/")[3]), body);
            }
            if ("GET".equals(metodo) && "/customer/requests".equals(ruta)) {
                Api.Respuesta r = lcApi(c, "GET", "/orders", null);
                if (!r.ok()) return r;
                JSONArray in = r.cuerpo.optJSONArray("items");
                JSONArray outs = new JSONArray();
                for (int i = 0; in != null && i < in.length(); i++) outs.put(aContrato(in.optJSONObject(i)));
                return new Api.Respuesta(200, new JSONObject().put("requests", outs));
            }
            if ("GET".equals(metodo) && ruta.matches("/customer/requests/\\d+")) {
                Api.Respuesta r = lcApi(c, "GET", "/orders/" + ruta.substring(ruta.lastIndexOf('/') + 1), null);
                return r.ok() ? new Api.Respuesta(200, aContrato(r.cuerpo)) : r;
            }
            if ("POST".equals(metodo) && "/customer/requests".equals(ruta)) return crearSolicitud(c, body);
            // Mensajes, fotos y avisos de la orden (lc-api qa-8)
            if (ruta.matches("/customer/requests/\\d+/(messages|photos)(\\?since=\\d+)?")) {
                String[] parts = ruta.split("/");
                String tipo = parts[4].replaceAll("\\?.*", "");
                String q = ruta.contains("?") ? ruta.substring(ruta.indexOf('?')) : "";
                Api.Respuesta r = lcApi(c, metodo, "/orders/" + parts[3] + "/" + tipo + q, body);
                if (!r.ok() || !"GET".equals(metodo)) return r;
                return new Api.Respuesta(200, new JSONObject().put("messages".equals(tipo) ? "mensajes" : "fotos", r.cuerpo.optJSONArray("items")));
            }
            if ("GET".equals(metodo) && "/customer/notifications".equals(ruta)) {
                Api.Respuesta r = lcApi(c, "GET", "/notifications", null);
                return r.ok() ? new Api.Respuesta(200, new JSONObject().put("avisos", r.cuerpo.optJSONArray("items"))) : r;
            }
            // La propuesta del técnico se decide (aprobar con firma o rechazar) en lc-api; el servidor valida todo
            if ("POST".equals(metodo) && ruta.matches("/customer/proposals/\\d+/decide")) return lcApi(c, "POST", ruta, body);
        } catch (Exception e) {
            return new Api.Respuesta(-1, null);
        }
        return noDisponible();
    }

    /** Foto de la solicitud: JSON con base64 (≤5 MB). clientPhotoId>0 evita duplicados al reintentar. */
    static Api.Respuesta subirFoto(Context c, long jobId, byte[] datos, String mime, String categoria, long clientPhotoId) {
        try {
            JSONObject b = new JSONObject().put("mime", mime).put("categoria", categoria)
                    .put("datosBase64", android.util.Base64.encodeToString(datos, android.util.Base64.NO_WRAP));
            if (clientPhotoId > 0) b.put("clientPhotoId", clientPhotoId);
            return lcApi(c, "POST", "/orders/" + jobId + "/photos", b);
        } catch (Exception e) {
            return new Api.Respuesta(-1, null);
        }
    }

    static Api.Respuesta noDisponible() {
        try {
            return new Api.Respuesta(501, new JSONObject().put("error", "Función no disponible en la versión QA"));
        } catch (Exception e) {
            return new Api.Respuesta(501, new JSONObject());
        }
    }

    // ── Auth ────────────────────────────────────────────────────────────────────────────────────

    private static Api.Respuesta login(Context c, JSONObject body) throws Exception {
        JSONObject cred = new JSONObject().put("email", body.optString("username").trim())
                .put("password", body.optString("password"));
        Api.Respuesta r = http("POST", BuildConfig.SUPABASE_URL + "/auth/v1/token?grant_type=password", null, cred);
        if (!r.ok()) return new Api.Respuesta(r.codigo == -1 ? -1 : 401, new JSONObject().put("error",
                r.codigo == -1 ? "Sin conexión con L&C" : "Correo o contraseña incorrectos"));
        return sesionDesde(c, r.cuerpo, body.optString("username").trim());
    }

    private static Api.Respuesta registrar(Context c, JSONObject body) throws Exception {
        JSONObject datos = new JSONObject().put("email", body.optString("email").trim())
                .put("password", body.optString("password"))
                .put("data", new JSONObject().put("nombre", body.optString("nombre")).put("telefono", body.optString("telefono")));
        Api.Respuesta r = http("POST", BuildConfig.SUPABASE_URL + "/auth/v1/signup", null, datos);
        if (!r.ok()) {
            String m = r.cuerpo != null ? (r.cuerpo.has("msg") ? r.cuerpo.optString("msg") : r.cuerpo.optString("message")) : "";
            String ml = m.toLowerCase(java.util.Locale.ROOT);
            String texto = r.codigo == -1 ? "Sin conexión con L&C"
                    : ml.contains("invalid") && ml.contains("email") ? "Ese correo no es válido. Revísalo o usa otro."
                    : ml.contains("already") || ml.contains("registered") ? "Ese correo ya tiene una cuenta. Prueba a iniciar sesión."
                    : ml.contains("password") ? "La contraseña no cumple los requisitos (mínimo 8 caracteres)."
                    : ml.contains("rate") || r.codigo == 429 ? "Demasiados intentos. Espera unos minutos."
                    : "No se pudo crear la cuenta. Inténtalo de nuevo.";
            return new Api.Respuesta(r.codigo == -1 ? -1 : r.codigo, new JSONObject().put("error", texto));
        }
        if (!r.cuerpo.has("access_token")) {
            return new Api.Respuesta(409, new JSONObject().put("error", "Revisa tu correo para confirmar la cuenta y luego entra."));
        }
        local(c).edit().putString("telefono", body.optString("telefono")).apply();
        return sesionDesde(c, r.cuerpo, body.optString("nombre"));
    }

    /** Guarda la sesión de Supabase y devuelve lo que LoginActivity espera: token, displayName, role. */
    private static Api.Respuesta sesionDesde(Context c, JSONObject tok, String nombre) throws Exception {
        String access = tok.optString("access_token");
        Api.prefs(c).edit().putString("token", access).putString("refresh", tok.optString("refresh_token")).apply();
        Api.Respuesta me = http("GET", base() + "/auth/me", access, null);
        if (!me.ok()) {
            Api.prefs(c).edit().remove("token").remove("refresh").apply();
            return me;
        }
        JSONObject meta = tok.optJSONObject("user") != null ? tok.optJSONObject("user").optJSONObject("user_metadata") : null;
        String visible = meta != null && !meta.optString("nombre").isEmpty() ? meta.optString("nombre") : nombre;
        return new Api.Respuesta(200, new JSONObject().put("token", access).put("displayName", visible)
                .put("role", me.cuerpo.optString("role")));
    }

    /** Renueva el access token con el refresh token. false: hay que volver a entrar. */
    private static synchronized boolean renovar(Context c) throws Exception {
        String refresh = Api.prefs(c).getString("refresh", null);
        if (refresh == null || refresh.isEmpty()) return false;
        Api.Respuesta r = http("POST", BuildConfig.SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token", null,
                new JSONObject().put("refresh_token", refresh));
        if (!r.ok()) return false;
        Api.prefs(c).edit().putString("token", r.cuerpo.optString("access_token"))
                .putString("refresh", r.cuerpo.optString("refresh_token")).apply();
        return true;
    }

    /** lc-api con la sesión del cliente; si caducó, se renueva una vez y se reintenta. */
    private static Api.Respuesta lcApi(Context c, String metodo, String ruta, JSONObject body) throws Exception {
        Api.Respuesta r = http(metodo, base() + ruta, Api.prefs(c).getString("token", null), body);
        if (r.codigo == 401 && renovar(c)) r = http(metodo, base() + ruta, Api.prefs(c).getString("token", null), body);
        if (r.codigo == 401) Api.cerrarSesion(c);
        return r;
    }

    // ── Solicitudes ─────────────────────────────────────────────────────────────────────────────

    private static Api.Respuesta crearSolicitud(Context c, JSONObject body) throws Exception {
        JSONObject prop = buscarPropiedad(c, body.optLong("propertyId"));
        if (prop == null) return new Api.Respuesta(422, new JSONObject().put("error", "Elige una propiedad"));
        StringBuilder desc = new StringBuilder(body.optString("descripcion"));
        if (body.has("fechaPreferida")) desc.append("\nPreferencia de fecha: ").append(Ui.fecha(body.optLong("fechaPreferida")));
        if (!body.optString("ventanaPreferida").isEmpty()) desc.append("\nVentana: ").append(body.optString("ventanaPreferida"));
        String nombre = Api.nombre(c).isEmpty() ? "Cliente" : Api.nombre(c);
        JSONObject orden = new JSONObject()
                .put("request_id", body.optString("clientRequestId"))  // mismo id en cada reintento: no duplica
                .put("cliente", nombre)
                .put("descripcion", desc.toString())
                .put("direccion", prop.optString("direccion"))
                .put("categoria", body.optString("categoria"));
        // con la propiedad ya en la nube, el Control ve tipo, acceso, instrucciones y mascotas de la orden
        if (prop.optBoolean("nube") && prop.optLong("id") > 0) orden.put("property_id", prop.optLong("id"));
        if (!body.optString("ventanaPreferida").isEmpty()) orden.put("ventana_preferida", body.optString("ventanaPreferida"));
        if (body.has("fechaPreferida")) orden.put("ventana_preferida", String.valueOf(body.optLong("fechaPreferida")));
        String tel = local(c).getString("telefono", "");
        if (!tel.isEmpty()) orden.put("telefono", tel);
        Api.Respuesta r = lcApi(c, "POST", "/orders", orden);
        return r.ok() ? new Api.Respuesta(r.codigo, new JSONObject().put("id", r.cuerpo.optLong("id"))) : r;
    }

    /** Fila de jobs → forma del contrato v2 que leen HomeActivity y RequestDetailActivity. */
    private static JSONObject aContrato(JSONObject j) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", j.optLong("id"));
        o.put("estado", j.optString("estado"));
        o.put("descripcion", j.optString("descripcion"));
        o.put("direccion", j.optString("direccion"));
        o.put("equipo", j.optString("categoria"));
        o.put("tecnicoNombre", !j.isNull("tecnico_nombre") ? j.optString("tecnico_nombre")
                : j.isNull("technician_code") ? "" : j.optString("technician_code"));
        if (j.optJSONArray("historial") != null) o.put("historial", j.optJSONArray("historial"));
        o.put("fotosCount", j.optInt("fotos_count", 0));
        if (!j.isNull("programado_para")) o.put("programadoPara", j.optLong("programado_para"));
        o.put("updatedAt", j.optLong("updated_at"));
        if (j.optJSONArray("propuestas") != null) o.put("propuestas", j.optJSONArray("propuestas"));   // Good/Better/Best
        return o;
    }

    // ── Propiedades y equipos: Supabase (lc-api) con copia local para trabajar sin conexión ──────────
    // "properties" guarda la última lista conocida (id local = id de la nube) y las altas pendientes de
    // subir (pendiente=true, request_id estable): al volver la conexión se suben sin duplicar.

    private static SharedPreferences local(Context c) {
        return c.getApplicationContext().getSharedPreferences(LOCAL, Context.MODE_PRIVATE);
    }

    private static JSONArray listaPropiedades(Context c) throws Exception {
        return new JSONArray(local(c).getString("properties", "[]"));
    }

    private static void guardar(Context c, JSONArray ps) {
        local(c).edit().putString("properties", ps.toString()).apply();
    }

    private static JSONObject buscarPropiedad(Context c, long id) throws Exception {
        JSONArray ps = listaPropiedades(c);
        for (int i = 0; i < ps.length(); i++) if (ps.optJSONObject(i).optLong("id") == id) return ps.optJSONObject(i);
        return null;
    }

    /** Fila de customer_properties (+ customer_equipment) → forma que pinta PropertiesActivity. */
    private static JSONObject desdeNube(JSONObject r) throws Exception {
        JSONObject p = new JSONObject().put("id", r.optLong("id")).put("nube", true)
                .put("direccion", r.optString("direccion"))
                .put("tipoPropiedad", r.isNull("tipo") ? "" : r.optString("tipo"))
                .put("tipoAcceso", r.isNull("tipo_acceso") ? "" : r.optString("tipo_acceso"))
                .put("instrucciones", r.isNull("instrucciones") ? "" : r.optString("instrucciones"))
                .put("mascota", r.isNull("mascota") ? "" : r.optString("mascota"))
                .put("contactoRespaldo", r.isNull("contacto_respaldo") ? "" : r.optString("contacto_respaldo"));
        JSONArray eq = new JSONArray(), in = r.optJSONArray("customer_equipment");
        for (int i = 0; in != null && i < in.length(); i++) {
            JSONObject e = in.optJSONObject(i);
            eq.put(new JSONObject().put("id", e.optLong("id")).put("nombre", e.optString("tipo"))
                    .put("marca", e.isNull("marca") ? "" : e.optString("marca"))
                    .put("modelo", e.isNull("modelo") ? "" : e.optString("modelo")));
        }
        return p.put("equipos", eq);
    }

    private static JSONObject aNube(JSONObject p) throws Exception {
        return new JSONObject().put("request_id", p.optString("request_id"))
                .put("direccion", p.optString("direccion").trim()).put("tipo", p.optString("tipoPropiedad"))
                .put("tipo_acceso", p.optString("tipoAcceso")).put("instrucciones", p.optString("instrucciones"))
                .put("mascota", p.optString("mascota")).put("contacto_respaldo", p.optString("contactoRespaldo"));
    }

    private static JSONObject equipoANube(JSONObject e) throws Exception {
        String serie = e.optString("serial").trim();
        return new JSONObject().put("request_id", e.optString("request_id")).put("tipo", e.optString("nombre"))
                .put("marca", e.optString("marca")).put("modelo", e.optString("modelo"))
                .put("notas", serie.isEmpty() ? "" : "Serie: " + serie);
    }

    /** Sube lo pendiente (propiedades de versiones anteriores incluidas) y devuelve la lista de la nube. */
    private static Api.Respuesta sincronizarPropiedades(Context c) throws Exception {
        JSONArray ps = listaPropiedades(c);
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p.optBoolean("nube")) {
                // equipos añadidos sin conexión a una propiedad que ya está en la nube
                JSONArray eq = p.optJSONArray("equipos");
                for (int k = 0; eq != null && k < eq.length(); k++) {
                    JSONObject e = eq.optJSONObject(k);
                    if (!e.optBoolean("pendiente")) continue;
                    Api.Respuesta r = lcApi(c, "POST", "/properties/" + p.optLong("id") + "/equipment", equipoANube(e));
                    if (r.codigo <= 0) return null;
                }
                continue;
            }
            if (p.optString("request_id").isEmpty()) p.put("request_id", "legacy-" + p.optLong("id"));
            Api.Respuesta r = lcApi(c, "POST", "/properties", aNube(p));
            if (r.codigo <= 0) return null;                 // sin conexión: se reintenta en la próxima carga
            if (!r.ok()) continue;
            long nube = r.cuerpo.optLong("id");
            JSONArray eq = p.optJSONArray("equipos");
            for (int k = 0; eq != null && k < eq.length(); k++) {
                JSONObject e = eq.optJSONObject(k);
                if (e.optString("request_id").isEmpty()) e.put("request_id", "legacy-" + e.optLong("id"));
                lcApi(c, "POST", "/properties/" + nube + "/equipment", equipoANube(e));
            }
        }
        Api.Respuesta r = lcApi(c, "GET", "/properties", null);
        if (!r.ok()) return r.codigo <= 0 ? null : r;
        JSONArray in = r.cuerpo.optJSONArray("items"), outs = new JSONArray();
        for (int i = 0; in != null && i < in.length(); i++) outs.put(desdeNube(in.optJSONObject(i)));
        guardar(c, outs);
        return new Api.Respuesta(200, new JSONObject().put("properties", outs));
    }

    private static Api.Respuesta propiedades(Context c) throws Exception {
        Api.Respuesta r = sincronizarPropiedades(c);
        if (r != null) return r;
        return new Api.Respuesta(200, new JSONObject().put("properties", listaPropiedades(c)).put("sinConexion", true));
    }

    private static Api.Respuesta nuevaPropiedad(Context c, JSONObject body) throws Exception {
        if (body == null || body.optString("direccion").trim().isEmpty()) {
            return new Api.Respuesta(422, new JSONObject().put("error", "Escribe la dirección"));
        }
        JSONObject p = new JSONObject(body.toString()).put("id", -System.currentTimeMillis())
                .put("request_id", "prop-" + java.util.UUID.randomUUID()).put("pendiente", true).put("equipos", new JSONArray());
        p.remove("codigoAcceso");  // el código de acceso no se guarda en claro en el teléfono ni viaja
        JSONArray ps = listaPropiedades(c);
        ps.put(p);
        guardar(c, ps);
        Api.Respuesta r = lcApi(c, "POST", "/properties", aNube(p));
        if (r.ok()) sincronizarPropiedades(c);
        return new Api.Respuesta(201, p);
    }

    private static Api.Respuesta nuevoEquipo(Context c, long pid, JSONObject body) throws Exception {
        JSONArray ps = listaPropiedades(c);
        for (int i = 0; i < ps.length(); i++) {
            JSONObject p = ps.optJSONObject(i);
            if (p.optLong("id") != pid) continue;
            JSONObject e = new JSONObject(body.toString()).put("id", -System.currentTimeMillis())
                    .put("request_id", "eq-" + java.util.UUID.randomUUID()).put("pendiente", true);
            p.optJSONArray("equipos").put(e);
            guardar(c, ps);
            if (p.optBoolean("nube")) {
                Api.Respuesta r = lcApi(c, "POST", "/properties/" + pid + "/equipment", equipoANube(e));
                if (r.ok()) sincronizarPropiedades(c);
            }
            return new Api.Respuesta(201, e);
        }
        return new Api.Respuesta(404, new JSONObject().put("error", "Propiedad no encontrada"));
    }

    // ── HTTP ────────────────────────────────────────────────────────────────────────────────────

    /** Respuestas JSON; un array se entrega como {"items": [...]}. */
    private static Api.Respuesta http(String metodo, String url, String token, JSONObject body) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod(metodo);
            con.setConnectTimeout(10000);
            con.setReadTimeout(20000);
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
            if (token != null) con.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = con.getOutputStream()) { os.write(b); }
            }
            int code = con.getResponseCode();
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            String txt = "";
            if (in != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                txt = bo.toString("UTF-8").trim();
            }
            JSONObject j;
            if (txt.startsWith("[")) j = new JSONObject().put("items", new JSONArray(txt));
            else {
                try { j = txt.isEmpty() ? new JSONObject() : new JSONObject(txt); } catch (Exception e) { j = new JSONObject(); }
            }
            return new Api.Respuesta(code, j);
        } catch (Exception e) {
            return new Api.Respuesta(-1, null);
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
