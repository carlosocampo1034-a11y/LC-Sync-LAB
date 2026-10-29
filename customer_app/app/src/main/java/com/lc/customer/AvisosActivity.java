package com.lc.customer;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;

/**
 * Avisos: lista completa de notificaciones del cliente. Se abre con la campana del Inicio
 * (antes el Inicio mostraba un bloque «Avisos» en el cuerpo). Misma fuente de siempre: `/customer/notifications`.
 */
public class AvisosActivity extends AppCompatActivity {

    private Ui.Pantalla p;
    private LinearLayout dinamico;
    private String novedades = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String nv = getIntent().getStringExtra("novedades");
        novedades = nv == null ? "" : nv;
        p = Ui.pantalla(this, "Avisos", "Tus notificaciones", true);
        dinamico = new LinearLayout(this);
        dinamico.setOrientation(LinearLayout.VERTICAL);
        p.contenido.addView(dinamico);
        dinamico.addView(Ui.texto(this, "Cargando…", 15, R.color.lc_sec, false));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.get(this, "/customer/notifications");
            runOnUiThread(() -> { if (!isFinishing()) mostrar(r); });
        });
    }

    private void mostrar(Api.Respuesta r) {
        LinearLayout c = dinamico;
        c.removeAllViews();
        JSONArray lista = r.ok() ? r.cuerpo.optJSONArray("avisos") : null;
        if (!r.ok()) { c.addView(Ui.texto(this, r.error(), 15, R.color.lc_rojo, false)); }
        if (!novedades.isEmpty()) {
            Ui.seccion(this, c, "Cambios desde tu última visita");
            LinearLayout t = Ui.tarjeta(this, c);
            t.addView(Ui.texto(this, novedades, 15, R.color.lc_texto, false));
        }
        if (lista == null || lista.length() == 0) {
            if (r.ok() && novedades.isEmpty()) c.addView(Ui.texto(this, "Sin avisos por ahora.", 15, R.color.lc_sec, false));
            return;
        }
        Ui.seccion(this, c, lista.length() == 1 ? "1 aviso" : lista.length() + " avisos");
        for (int i = 0; i < lista.length(); i++) {
            JSONObject a = lista.optJSONObject(i);
            LinearLayout t = Ui.tarjeta(this, c);
            t.addView(Ui.texto(this, a.optString("titulo"), 15, R.color.lc_texto, true));
            String cuerpo = a.optString("cuerpo");
            if (!cuerpo.isEmpty() && !"null".equals(cuerpo)) t.addView(Ui.texto(this, cuerpo, 14, R.color.lc_sec, false));
            long ts = a.optLong("created_at", 0);
            if (ts > 0) t.addView(Ui.texto(this, Ui.fechaCorta(ts), 12, R.color.lc_sec, false));
            final long jid = a.optLong("job_id", 0);
            if (jid > 0) t.setOnClickListener(v -> startActivity(new Intent(this, RequestDetailActivity.class).putExtra("id", jid)));
        }
    }
}
