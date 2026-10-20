package com.lc.customer;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

/**
 * Diagnóstico de la conexión con el servidor de laboratorio desde Android (TLS del sistema + network_security_config):
 * certificado correcto, certificado ajeno, HTTP en claro, sin sesión y sin permisos. No muestra tokens ni contraseñas.
 */
public class DiagnosticoActivity extends AppCompatActivity {

    private TextView salida;
    private final StringBuilder log = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.Pantalla p = Ui.pantalla(this, "Diagnóstico", "Conexión con el laboratorio", true);
        salida = Ui.texto(this, "Ejecutando…", 15, R.color.lc_texto, false);
        p.contenido.addView(salida);
        Executors.newSingleThreadExecutor().execute(this::ejecutar);
    }

    /** Código HTTP o el nombre de la excepción (p. ej. SSLHandshakeException, UnknownServiceException). */
    private String probar(String url, String token) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(8000);
            if (token != null) con.setRequestProperty("Authorization", "Bearer " + token);
            return String.valueOf(con.getResponseCode());
        } catch (Exception e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            return e.getClass().getSimpleName() + (m.isEmpty() ? "" : ": " + (m.length() > 90 ? m.substring(0, 90) + "…" : m));
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private void linea(String prueba, String obtenido, boolean ok) {
        log.append(ok ? "✓ " : "✗ ").append(prueba).append(" → ").append(obtenido).append("\n\n");
        runOnUiThread(() -> salida.setText(log.toString()));
    }

    private void ejecutar() {
        String base = Api.base(this);
        log.append("Servidor: ").append(base).append("\n\n");
        String r = probar(base + "/health", null);
        linea("1. HTTPS con el certificado de laboratorio", r, "200".equals(r));
        if (base.startsWith("https://10.0.0.121:5443")) {
            r = probar("https://10.0.0.121:5444/api/v2/health", null);
            linea("2. Servidor con certificado ajeno (puerto 5444)", r, r.contains("SSL") || r.contains("Certificate"));
            r = probar("http://10.0.0.121:5443/api/v2/health", null);
            linea("3. HTTP sin cifrar", r, !r.matches("\\d+"));
        }
        r = probar(base + "/customer/requests", null);
        linea("4. Sin sesión (sin token)", r, "401".equals(r));
        String tok = Api.prefs(this).getString("token", null);
        if (tok != null) {
            r = probar(base + "/dispatch/conflicts", tok);
            linea("5. Cliente en una ruta de despacho", r, "403".equals(r));
            r = probar(base + "/customer/requests", tok);
            linea("6. Cliente en sus propias solicitudes", r, "200".equals(r));
        } else {
            linea("5-6. Permisos con sesión", "inicia sesión y vuelve a ejecutar", true);
        }
    }
}
