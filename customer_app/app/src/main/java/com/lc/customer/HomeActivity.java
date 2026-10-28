package com.lc.customer;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/** «Mi hogar»: Inicio · Solicitudes · Seguimiento · Perfil. Solo muestra lo que el servidor confirma. */
public class HomeActivity extends AppCompatActivity {

    private static final String[] TABS = {"Inicio", "Solicitudes", "Seguimiento", "Perfil"};
    private static final String[] ICONOS = {"⌂", "☰", "◷", "●"};

    private Ui.Pantalla p;
    private final TextView[] botones = new TextView[4];
    private int tab = 0;
    private int filtro = 0;                       // 0 activas · 1 todas · 2 completadas
    private JSONArray reqs = null;                // null = aún sin datos
    private String errorCarga = null;
    private final java.util.Map<Long, JSONObject> detalles = new java.util.HashMap<>();
    private String novedades = "";
    private JSONArray avisos = new JSONArray();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) { tab = b.getInt("tab", 0); filtro = b.getInt("filtro", 0); }
        p = Ui.pantalla(this, "Mi hogar", Api.nombre(this).isEmpty() ? null : Api.nombre(this), false);
        p.inferior.setPadding(0, 0, 0, 0);
        View linea = new View(this);
        linea.setBackgroundColor(getResources().getColor(R.color.lc_borde));
        p.inferior.addView(linea, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
        LinearLayout barra = new LinearLayout(this);
        barra.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = Ui.texto(this, ICONOS[i] + "\n" + TABS[i], 12, R.color.lc_sec, true);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(Ui.dp(this, 60));
            t.setOnClickListener(v -> { tab = idx; pintar(); });
            botones[i] = t;
            barra.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        p.inferior.addView(barra);
        pintar();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        out.putInt("filtro", filtro);
    }

    private final android.os.Handler hh = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean visible = false;
    private final Runnable refresco = new Runnable() {
        @Override public void run() { if (visible) { cargar(); hh.postDelayed(this, 8000); } }
    };

    @Override
    protected void onResume() {
        super.onResume();
        if (!Api.conSesion(this)) { startActivity(new Intent(this, LoginActivity.class)); finish(); return; }
        visible = true;
        hh.removeCallbacks(refresco);
        hh.post(refresco);   // lista y seguimiento se actualizan solos mientras la pantalla está abierta
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        hh.removeCallbacks(refresco);
    }

    private void cargar() {
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.get(this, "/customer/requests");
            JSONArray lista = r.ok() ? r.cuerpo.optJSONArray("requests") : null;
            java.util.Map<Long, JSONObject> det = new java.util.HashMap<>();
            // detalle de las solicitudes activas (máx. 3) para el seguimiento: técnico, propuestas, factura
            for (int i = 0, n = 0; lista != null && i < lista.length() && n < 3; i++) {
                JSONObject j = lista.optJSONObject(i);
                if (!activa(j.optString("estado"))) continue;
                n++;
                Api.Respuesta d = Api.get(this, "/customer/requests/" + j.optLong("id"));
                if (d.ok()) det.put(j.optLong("id"), d.cuerpo);
            }
            Api.Respuesta av = Api.get(this, "/customer/notifications");
            JSONArray avl = av.ok() ? av.cuerpo.optJSONArray("avisos") : null;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (avl != null) avisos = avl;
                if (r.ok()) { reqs = lista == null ? new JSONArray() : lista; errorCarga = null; calcularNovedades(); }
                else errorCarga = r.error();
                detalles.clear();
                detalles.putAll(det);
                pintar();
            });
        });
    }

    private static boolean activa(String e) { return !"COMPLETADO".equals(e) && !"CANCELADO".equals(e); }

    /** Avisos: cambios de estado desde la última visita (el push FCM los complementa cuando existe). */
    private void calcularNovedades() {
        SharedPreferences vistos = getSharedPreferences("estados_vistos", MODE_PRIVATE);
        SharedPreferences.Editor ed = vistos.edit();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < reqs.length(); i++) {
            JSONObject j = reqs.optJSONObject(i);
            String id = String.valueOf(j.optLong("id"));
            String est = j.optString("estado");
            String antes = vistos.getString(id, null);
            if (antes != null && !antes.equals(est)) {
                sb.append("• Nº ").append(id).append(": ").append(Ui.estadoCliente(est)).append("\n");
            }
            ed.putString(id, est);
        }
        ed.apply();
        novedades = sb.toString().trim();
    }

    private void pintar() {
        for (int i = 0; i < 4; i++) {
            botones[i].setTextColor(getResources().getColor(i == tab ? R.color.lc_rojo : R.color.lc_sec));
        }
        p.contenido.removeAllViews();
        if (tab == 3) { pintarPerfil(); return; }
        if (reqs == null) {
            p.contenido.addView(Ui.texto(this, errorCarga != null ? errorCarga + "\nVuelve a abrir esta pantalla para reintentar."
                    : "Cargando…", 15, errorCarga != null ? R.color.lc_rojo : R.color.lc_sec, false));
            return;
        }
        if (errorCarga != null) {
            p.contenido.addView(Ui.texto(this, "Sin conexión: mostrando lo último guardado.", 13, R.color.lc_rojo, false));
        }
        if (tab == 0) pintarInicio();
        else if (tab == 1) pintarSolicitudes();
        else pintarSeguimiento();
    }

    // ── Inicio ───────────────────────────────────────────────────────────────────────────────────

    private void pintarInicio() {
        LinearLayout c = p.contenido;
        String n = primerNombre(Api.nombre(this));
        LinearLayout hero = Ui.tarjeta(this, c);
        hero.getBackground().mutate().setTint(getResources().getColor(R.color.lc_texto));
        hero.addView(Ui.texto(this, n.isEmpty() ? "Hola" : "Hola, " + n, 22, android.R.color.white, true));
        hero.addView(Ui.texto(this, "Revisa tus solicitudes y el estado de cada visita.", 14, android.R.color.white, false));
        android.widget.Button sol = Ui.boton(this, c, "Solicitar servicio", true);
        sol.setOnClickListener(v -> startActivity(new Intent(this, NewRequestActivity.class)));
        android.widget.Button props = Ui.boton(this, c, "Mis propiedades y equipos", false);
        props.setOnClickListener(v -> startActivity(new Intent(this, PropertiesActivity.class)));
        Ui.seccion(this, c, "Avisos");
        if (avisos.length() == 0 && novedades.isEmpty()) {
            c.addView(Ui.texto(this, "Sin avisos por ahora.", 15, R.color.lc_sec, false));
        }
        for (int i = 0; i < avisos.length() && i < 4; i++) {
            JSONObject a = avisos.optJSONObject(i);
            LinearLayout t = Ui.tarjeta(this, c);
            t.addView(Ui.texto(this, a.optString("titulo"), 15, R.color.lc_texto, true));
            if (!a.optString("cuerpo").isEmpty() && !"null".equals(a.optString("cuerpo"))) {
                t.addView(Ui.texto(this, a.optString("cuerpo"), 14, R.color.lc_sec, false));
            }
            long jid = a.optLong("job_id", 0);
            if (jid > 0) t.setOnClickListener(v -> startActivity(new Intent(this, RequestDetailActivity.class).putExtra("id", jid)));
        }
        if (avisos.length() == 0 && !novedades.isEmpty()) c.addView(Ui.texto(this, novedades, 15, R.color.lc_texto, true));
        Ui.seccion(this, c, "Solicitudes activas");
        int n2 = tarjetas(c, 0);
        if (n2 == 0) c.addView(Ui.texto(this, "No tienes solicitudes activas.", 15, R.color.lc_sec, false));
    }

    // ── Solicitudes ──────────────────────────────────────────────────────────────────────────────

    private void pintarSolicitudes() {
        LinearLayout c = p.contenido;
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        String[] nombres = {"Activas", "Todas", "Completadas"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = Ui.texto(this, nombres[i], 14, i == filtro ? android.R.color.white : R.color.lc_texto, true);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(Ui.dp(this, 44));
            t.setBackgroundResource(i == filtro ? R.drawable.bg_boton : R.drawable.bg_boton_sec);
            t.setOnClickListener(v -> { filtro = idx; pintar(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = Ui.dp(this, 6);
            fila.addView(t, lp);
        }
        c.addView(fila);
        if (tarjetas(c, filtro) == 0) {
            c.addView(Ui.texto(this, "No hay solicitudes en esta lista.", 15, R.color.lc_sec, false));
        }
    }

    /** modo 0 activas · 1 todas · 2 completadas/canceladas. Devuelve cuántas dibujó. */
    private int tarjetas(LinearLayout dest, int modo) {
        int n = 0;
        for (int i = 0; i < reqs.length(); i++) {
            JSONObject j = reqs.optJSONObject(i);
            boolean act = activa(j.optString("estado"));
            if ((modo == 0 && !act) || (modo == 2 && act)) continue;
            n++;
            LinearLayout card = Ui.tarjeta(this, dest);
            LinearLayout cab = new LinearLayout(this);
            cab.setOrientation(LinearLayout.HORIZONTAL);
            cab.addView(Ui.texto(this, "Nº " + j.optLong("id"), 16, R.color.lc_texto, true),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            cab.addView(Ui.badge(this, Ui.estadoCliente(j.optString("estado")).toUpperCase()));
            card.addView(cab);
            TextView d = Ui.texto(this, limpiarDescripcion(j.optString("descripcion")), 15, R.color.lc_texto, false);
            d.setPadding(0, Ui.dp(this, 6), 0, 0);
            card.addView(d);
            long cita = j.optLong("programadoPara", 0);
            String citaTxt = cita > 0 ? "Cita: " + Ui.fecha(cita) : act ? "Cita por confirmar" : "";
            String dir = j.optString("direccion");
            card.addView(Ui.texto(this, citaTxt + (citaTxt.isEmpty() || dir.isEmpty() ? "" : " · ") + dir, 13, R.color.lc_sec, false));
            final long jid = j.optLong("id");
            card.setOnClickListener(v -> {
                Intent it = new Intent(this, RequestDetailActivity.class);
                it.putExtra("id", jid);
                startActivity(it);
            });
        }
        return n;
    }

    /** La descripción guarda la preferencia de fecha en líneas extra; en la lista solo se ve el problema. */
    static String limpiarDescripcion(String d) {
        int i = d.indexOf('\n');
        return i > 0 ? d.substring(0, i) : d;
    }

    // ── Seguimiento: solo etapas respaldadas por datos del servidor ───────────────────────────────

    private void pintarSeguimiento() {
        LinearLayout c = p.contenido;
        int n = 0;
        for (int i = 0; i < reqs.length(); i++) {
            JSONObject j = reqs.optJSONObject(i);
            if (!activa(j.optString("estado"))) continue;
            n++;
            JSONObject d = detalles.containsKey(j.optLong("id")) ? detalles.get(j.optLong("id")) : j;
            Ui.seccion(this, c, "Nº " + j.optLong("id") + " · " + limpiarDescripcion(j.optString("descripcion")));
            LinearLayout card = Ui.tarjeta(this, c);
            for (String[] e : etapas(d)) {
                boolean hecho = "1".equals(e[1]);
                TextView t = Ui.texto(this, (hecho ? "✓  " : "○  ") + e[0], 15,
                        hecho ? R.color.lc_texto : R.color.lc_sec, hecho);
                t.setPadding(0, Ui.dp(this, 5), 0, Ui.dp(this, 5));
                card.addView(t);
            }
            final long jid = j.optLong("id");
            card.setOnClickListener(v -> {
                Intent it = new Intent(this, RequestDetailActivity.class);
                it.putExtra("id", jid);
                startActivity(it);
            });
        }
        if (n == 0) c.addView(Ui.texto(this, "Cuando tengas una solicitud activa verás aquí cada etapa.", 15, R.color.lc_sec, false));
    }

    /** Etapas {texto, "1"/"0"} calculadas solo con campos reales; nada se inventa. */
    static List<String[]> etapas(JSONObject d) {
        String est = d.optString("estado");
        List<String[]> l = new ArrayList<>();
        l.add(new String[]{"Solicitud recibida", "1"});
        l.add(new String[]{"Cita confirmada", d.optLong("programadoPara", 0) > 0 ? "1" : "0"});
        l.add(new String[]{"Técnico asignado", d.optString("tecnicoNombre").isEmpty() ? "0" : "1"});
        boolean enRuta = "EN RUTA".equals(est) || "LLEGUÉ".equals(est) || "EN REPARACIÓN".equals(est)
                || "ESPERANDO APROBACIÓN".equals(est) || "PART HOLD".equals(est) || "COMPLETADO".equals(est);
        l.add(new String[]{"Técnico en camino", enRuta ? "1" : "0"});
        boolean enSitio = "LLEGUÉ".equals(est) || "EN REPARACIÓN".equals(est) || "ESPERANDO APROBACIÓN".equals(est)
                || "PART HOLD".equals(est) || "PAUSADO".equals(est) || "COMPLETADO".equals(est);
        l.add(new String[]{"Técnico en el lugar", enSitio ? "1" : "0"});
        boolean trabajo = "EN REPARACIÓN".equals(est) || "ESPERANDO APROBACIÓN".equals(est)
                || "PART HOLD".equals(est) || "PAUSADO".equals(est) || "COMPLETADO".equals(est);
        l.add(new String[]{"Trabajo en progreso", trabajo ? "1" : "0"});
        JSONArray pr = d.optJSONArray("propuestas");
        boolean pend = false;
        for (int i = 0; pr != null && i < pr.length(); i++) {
            if ("PENDIENTE".equals(pr.optJSONObject(i).optString("estado"))) pend = true;
        }
        if (pend || "ESPERANDO APROBACIÓN".equals(est)) l.add(new String[]{"Propuesta pendiente de tu aprobación", "1"});
        l.add(new String[]{"Trabajo completado", "COMPLETADO".equals(est) ? "1" : "0"});
        l.add(new String[]{"Factura y pago", d.optJSONObject("factura") != null ? "1" : "0"});
        return l;
    }

    // ── Perfil ───────────────────────────────────────────────────────────────────────────────────

    private void pintarPerfil() {
        LinearLayout c = p.contenido;
        LinearLayout card = Ui.tarjeta(this, c);
        card.addView(Ui.texto(this, "Cuenta", 13, R.color.lc_sec, false));
        card.addView(Ui.texto(this, Api.nombre(this).isEmpty() ? "—" : Api.nombre(this), 18, R.color.lc_texto, true));
        android.widget.Button props = Ui.boton(this, c, "Mis propiedades y equipos", false);
        props.setOnClickListener(v -> startActivity(new Intent(this, PropertiesActivity.class)));
        if (!CloudApi.activo()) {   // herramienta técnica solo en el laboratorio con servidor propio
            android.widget.Button diag = Ui.boton(this, c, "Diagnóstico de conexión", false);
            diag.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticoActivity.class)));
        }
        android.widget.Button salir = Ui.boton(this, c, "Cerrar sesión", false);
        salir.setOnClickListener(v -> {
            Api.cerrarSesion(this);
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
    }

    private static String primerNombre(String n) {
        if (n == null || n.trim().isEmpty()) return "";
        return n.trim().split("\\s+")[0];
    }
}
