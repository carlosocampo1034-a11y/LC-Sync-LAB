package com.lc.customer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Cliente del backend de laboratorio (contrato v2). Llamar SIEMPRE fuera del hilo principal. */
final class Api {

    static final class Respuesta {
        final int codigo;
        final JSONObject cuerpo;
        Respuesta(int c, JSONObject b) { codigo = c; cuerpo = b; }
        boolean ok() { return codigo >= 200 && codigo < 300; }
        String error() {
            if (codigo == -1) return "Sin conexión con L&C";
            String e = cuerpo != null ? cuerpo.optString("error", "") : "";
            return e.isEmpty() ? "Error " + codigo : e;
        }
    }

    private static final String PREFS = "lc_customer_session";

    private Api() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Prueba con dos teléfonos: HTTP por la Wi-Fi privada (el servidor Flask de laboratorio es HTTP). */
    static final String API_WIFI = "http://10.0.0.121:5055/api/v2";

    /** URL activa: custom > WIFI hardcoded. Se conserva al cerrar sesión. */
    static String base(Context c) {
        if (CloudApi.activo()) return CloudApi.base();
        String cu = urlCustom(c);
        if (cu != null) return cu;
        return API_WIFI;
    }

    static boolean servidorWifi(Context c) { return "WIFI".equals(prefs(c).getString("servidor", "LOCAL")); }

    static void setServidorWifi(Context c, boolean wifi) {
        prefs(c).edit().putString("servidor", wifi ? "WIFI" : "LOCAL").apply();
    }

    static String urlCustom(Context c) { return prefs(c).getString("url_custom", null); }

    static void setUrlCustom(Context c, String url) {
        if (url == null || url.trim().isEmpty()) prefs(c).edit().remove("url_custom").apply();
        else prefs(c).edit().putString("url_custom", url.trim()).apply();
    }

    static boolean conSesion(Context c) { return prefs(c).getString("token", null) != null; }

    static void guardarSesion(Context c, JSONObject r) {
        prefs(c).edit().putString("token", r.optString("token")).putString("nombre", r.optString("displayName"))
                .apply();
    }

    static void cerrarSesion(Context c) { prefs(c).edit().remove("token").remove("refresh").remove("nombre").apply(); }

    static String nombre(Context c) { return prefs(c).getString("nombre", ""); }

    static Respuesta get(Context c, String ruta) { return llamar(c, "GET", ruta, null); }

    static Respuesta post(Context c, String ruta, JSONObject body) { return llamar(c, "POST", ruta, body); }

    private static Respuesta llamar(Context c, String metodo, String ruta, JSONObject body) {
        if (CloudApi.activo()) return CloudApi.llamar(c, metodo, ruta, body);
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(base(c) + ruta).openConnection();
            con.setRequestMethod(metodo);
            con.setConnectTimeout(8000);
            con.setReadTimeout(15000);
            con.setRequestProperty("Accept", "application/json");
            String tok = prefs(c).getString("token", null);
            if (tok != null) con.setRequestProperty("Authorization", "Bearer " + tok);
            if (body != null) {
                byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                con.setDoOutput(true);
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = con.getOutputStream()) { os.write(b); }
            }
            return leer(c, con);
        } catch (Exception e) {
            return new Respuesta(-1, null);
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Descarga binaria con la sesión (p. ej. el PDF de la factura). null si falla o no es un PDF. */
    static byte[] descargarPdf(Context c, String ruta) {
        if (CloudApi.activo()) return null;  // lc-api QA aún no genera facturas
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(base(c) + ruta).openConnection();
            con.setConnectTimeout(8000);
            con.setReadTimeout(20000);
            String tok = prefs(c).getString("token", null);
            if (tok != null) con.setRequestProperty("Authorization", "Bearer " + tok);
            if (con.getResponseCode() != 200) return null;
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 6 * 1024 * 1024) return null;
                    bo.write(buf, 0, n);
                }
                byte[] b = bo.toByteArray();
                return b.length > 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' ? b : null;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Foto (JPEG/PNG) como multipart/form-data, campo "foto". */
    static Respuesta subirFoto(Context c, long jobId, byte[] datos, String mime, String categoria) {
        return subirFoto(c, jobId, datos, mime, categoria, 0);
    }

    /** clientPhotoId > 0: el servidor no duplica la foto si se reintenta con el mismo número. */
    static Respuesta subirFoto(Context c, long jobId, byte[] datos, String mime, String categoria, long clientPhotoId) {
        if (CloudApi.activo()) return CloudApi.noDisponible();  // lc-api QA aún no recibe fotos
        HttpURLConnection con = null;
        String limite = "----lc" + UUID.randomUUID();
        try {
            con = (HttpURLConnection) new URL(base(c) + "/jobs/" + jobId + "/photos").openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(8000);
            con.setReadTimeout(30000);
            con.setDoOutput(true);
            con.setRequestProperty("Authorization", "Bearer " + prefs(c).getString("token", ""));
            con.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + limite);
            String ext = "image/png".equals(mime) ? "png" : "jpg";
            try (OutputStream os = con.getOutputStream()) {
                os.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"categoria\"\r\n\r\n"
                        + categoria + "\r\n").getBytes(StandardCharsets.UTF_8));
                if (clientPhotoId > 0) {
                    os.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"clientPhotoId\"\r\n\r\n"
                            + clientPhotoId + "\r\n").getBytes(StandardCharsets.UTF_8));
                }
                os.write(("--" + limite + "\r\nContent-Disposition: form-data; name=\"foto\"; filename=\"foto." + ext
                        + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(datos);
                os.write(("\r\n--" + limite + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            return leer(c, con);
        } catch (Exception e) {
            return new Respuesta(-1, null);
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static Respuesta leer(Context c, HttpURLConnection con) throws Exception {
        int code = con.getResponseCode();
        InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
        String txt = "";
        if (in != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            txt = bo.toString("UTF-8");
        }
        if (code == 401) cerrarSesion(c);
        JSONObject j;
        try { j = txt.isEmpty() ? new JSONObject() : new JSONObject(txt); } catch (Exception e) { j = new JSONObject(); }
        return new Respuesta(code, j);
    }
}
