package com.lc.customer;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;

public class HomeActivity extends AppCompatActivity {

    private Ui.Pantalla p;
    private LinearLayout lista;
    private TextView aviso;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = Ui.pantalla(this, "Hola, " + primerNombre(Api.nombre(this)), "Tus servicios con L&C", false);
        aviso = Ui.texto(this, "", 14, R.color.lc_texto, true);
        aviso.setBackgroundResource(R.drawable.bg_input);
        aviso.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        aviso.setVisibility(android.view.View.GONE);
        p.contenido.addView(aviso);
        Ui.seccion(this, p.contenido, "Mis solicitudes");
        lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        p.contenido.addView(lista);
        Button solicitar = Ui.boton(this, p.inferior, "Solicitar servicio", true);
        solicitar.setOnClickListener(v -> startActivity(new Intent(this, NewRequestActivity.class)));
        Button props = Ui.boton(this, p.inferior, "Mis propiedades y equipos", false);
        props.setOnClickListener(v -> startActivity(new Intent(this, PropertiesActivity.class)));
        TextView salir = Ui.texto(this, "Cerrar sesión", 14, R.color.lc_sec, false);
        salir.setPadding(0, Ui.dp(this, 10), 0, 0);
        salir.setOnClickListener(v -> {
            Api.cerrarSesion(this);
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
        p.inferior.addView(salir);
        TextView diag = Ui.texto(this, "Diagnóstico de conexión", 14, R.color.lc_sec, false);
        diag.setPadding(0, Ui.dp(this, 10), 0, 0);
        diag.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticoActivity.class)));
        p.inferior.addView(diag);
    }

    private static String primerNombre(String n) {
        if (n == null || n.trim().isEmpty()) return "";
        return n.trim().split("\\s+")[0];
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Api.conSesion(this)) { startActivity(new Intent(this, LoginActivity.class)); finish(); return; }
        cargar();
    }

    private void cargar() {
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.get(this, "/customer/requests");
            runOnUiThread(() -> mostrar(r));
        });
    }

    private void mostrar(Api.Respuesta r) {
        lista.removeAllViews();
        if (!r.ok()) {
            lista.addView(Ui.texto(this, r.error() + " · desliza o vuelve a abrir para reintentar", 14, R.color.lc_rojo, false));
            return;
        }
        JSONArray reqs = r.cuerpo.optJSONArray("requests");
        if (reqs == null || reqs.length() == 0) {
            lista.addView(Ui.texto(this, "Aún no tienes solicitudes. Pulsa «Solicitar servicio».", 15, R.color.lc_sec, false));
            aviso.setVisibility(android.view.View.GONE);
            return;
        }
        // Avisos: cambios de estado desde la última vez que el cliente abrió la app (sin push todavía)
        SharedPreferences vistos = getSharedPreferences("estados_vistos", MODE_PRIVATE);
        SharedPreferences.Editor ed = vistos.edit();
        StringBuilder cambios = new StringBuilder();
        for (int i = 0; i < reqs.length(); i++) {
            JSONObject j = reqs.optJSONObject(i);
            String id = String.valueOf(j.optLong("id"));
            String est = j.optString("estado");
            String antes = vistos.getString(id, null);
            if (antes != null && !antes.equals(est)) {
                cambios.append("• ").append(j.optString("descripcion")).append(": ")
                        .append(Ui.estadoCliente(est)).append("\n");
            }
            ed.putString(id, est);
            LinearLayout card = Ui.tarjeta(this, lista);
            LinearLayout cab = new LinearLayout(this);
            cab.setOrientation(LinearLayout.HORIZONTAL);
            long cita = j.optLong("programadoPara", 0);
            TextView fecha = Ui.texto(this, cita > 0 ? Ui.fecha(cita) : "Por programar", 15, R.color.lc_texto, true);
            cab.addView(fecha, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            cab.addView(Ui.badge(this, Ui.estadoCliente(est).toUpperCase()));
            card.addView(cab);
            TextView d = Ui.texto(this, j.optString("descripcion"), 16, R.color.lc_texto, false);
            d.setPadding(0, Ui.dp(this, 6), 0, 0);
            card.addView(d);
            card.addView(Ui.texto(this, j.optString("direccion"), 13, R.color.lc_sec, false));
            long jid = j.optLong("id");
            card.setOnClickListener(v -> {
                Intent it = new Intent(this, RequestDetailActivity.class);
                it.putExtra("id", jid);
                startActivity(it);
            });
        }
        ed.apply();
        if (cambios.length() > 0) {
            aviso.setText("Novedades\n" + cambios.toString().trim());
            aviso.setVisibility(android.view.View.VISIBLE);
        } else {
            aviso.setVisibility(android.view.View.GONE);
        }
    }
}
