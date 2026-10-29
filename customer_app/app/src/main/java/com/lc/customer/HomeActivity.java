package com.lc.customer;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
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
    private static final int[] ICONOS = {R.drawable.ic_home, R.drawable.ic_list, R.drawable.ic_clock, R.drawable.ic_person};
    private final ImageView[] iconosNav = new ImageView[4];

    private Ui.Pantalla p;
    private final TextView[] botones = new TextView[4];
    private int tab = 0;
    private int filtro = 1;                       // 0 en curso · 1 todas · 2 completadas
    private int nProps = -1;                      // -1 = aún sin saber
    private JSONArray reqs = null;                // null = aún sin datos
    private String errorCarga = null;
    private final java.util.Map<Long, JSONObject> detalles = new java.util.HashMap<>();
    private String novedades = "";
    private JSONArray avisos = new JSONArray();
    private TextView tituloAvisos;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) { tab = b.getInt("tab", 0); filtro = b.getInt("filtro", 1); }
        else aplicarExtras(getIntent());
        p = Ui.pantalla(this, "Mi hogar", Api.nombre(this).isEmpty() ? null : Api.nombre(this), false);
        p.inferior.setPadding(0, 0, 0, 0);
        View linea = new View(this);
        linea.setBackgroundColor(getResources().getColor(R.color.lc_borde));
        p.inferior.addView(linea, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
        LinearLayout barra = new LinearLayout(this);
        barra.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setMinimumHeight(Ui.dp(this, 60));
            ImageView iv = new ImageView(this);
            iv.setImageResource(ICONOS[i]);
            item.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
            TextView t = Ui.texto(this, TABS[i], 12, R.color.lc_sec, true);
            t.setGravity(Gravity.CENTER);
            item.addView(t);
            item.setOnClickListener(v -> { tab = idx; pintar(); });
            botones[i] = t;
            iconosNav[i] = iv;
            barra.addView(item, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        p.inferior.addView(barra);
        pintar();
    }

    private void aplicarExtras(Intent it) {
        if (it == null) return;
        if (it.hasExtra("tab")) tab = Math.max(0, Math.min(3, it.getIntExtra("tab", 0)));
        if (it.hasExtra("filtro")) filtro = Math.max(0, Math.min(2, it.getIntExtra("filtro", 1)));
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        aplicarExtras(it);
        if (p != null) pintar();
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
        if (ColaCliente.pendientes(this) > 0) Red.vaciarColasYa();   // envíos que quedaron esperando red
        if (!Api.conSesion(this)) { startActivity(new Intent(this, LoginActivity.class)); finish(); return; }
        visible = true;
        Ajustes.refrescar(this, true, this::pintar);   // precios y listas editables (Control)
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
        Ajustes.refrescar(this, false, this::pintar);   // como máximo cada 30 s
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
            Api.Respuesta pr = Api.get(this, "/customer/properties");
            final int cuentaProps = pr.ok() && pr.cuerpo.optJSONArray("properties") != null ? pr.cuerpo.optJSONArray("properties").length() : -1;
            Api.Respuesta av = Api.get(this, "/customer/notifications");
            JSONArray avl = av.ok() ? av.cuerpo.optJSONArray("avisos") : null;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (avl != null) avisos = avl;
                if (cuentaProps >= 0) nProps = cuentaProps;
                if (r.ok()) { reqs = lista == null ? new JSONArray() : lista; errorCarga = null; calcularNovedades(); }
                else errorCarga = r.error();
                detalles.clear();
                detalles.putAll(det);
                pintar();
            });
        });
    }

    private static boolean activa(String e) { return !"COMPLETADO".equals(e) && !"CANCELADO".equals(e) && !"PAGADO".equals(e); }

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
                sb.append("• Nº ").append(id).append(": ").append(Ui.estadoCliente(this, est)).append("\n");
            }
            ed.putString(id, est);
        }
        ed.apply();
        novedades = sb.toString().trim();
    }

    private void pintar() {
        for (int i = 0; i < 4; i++) {
            botones[i].setTextColor(getResources().getColor(i == tab ? R.color.lc_rojo : R.color.lc_sec));
            iconosNav[i].setColorFilter(i == tab ? 0xFFE91605 : 0xFF62666A);
        }
        armarCabecera();
        p.contenido.removeAllViews();
        if (tab == 3) { pintarPerfil(); return; }
        if (reqs == null) {
            p.contenido.addView(Ui.texto(this, errorCarga != null ? errorCarga + "\nVuelve a abrir esta pantalla para reintentar."
                    : "Cargando…", 15, errorCarga != null ? R.color.lc_rojo : R.color.lc_sec, false));
            return;
        }
        if (errorCarga != null) {
            p.contenido.addView(Ui.texto(this, "Reconectando… mostrando lo último que se descargó.", 13, R.color.lc_rojo, false));
        }
        if (tab == 0) pintarInicio();
        else if (tab == 1) pintarSolicitudes();
        else pintarSeguimiento();
    }

    /** Inicio: logo L&C y campana como la maqueta; las demás pestañas llevan su título. */
    private void armarCabecera() {
        p.cabecera.removeAllViews();
        if (tab == 0) {
            LinearLayout fila = new LinearLayout(this);
            fila.setOrientation(LinearLayout.HORIZONTAL);
            fila.setGravity(Gravity.CENTER_VERTICAL);
            ImageView logo = new ImageView(this);
            logo.setImageResource(R.drawable.ic_launcher);
            logo.setContentDescription("L&C");
            fila.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
            LinearLayout tx = new LinearLayout(this);
            tx.setOrientation(LinearLayout.VERTICAL);
            tx.addView(Ui.texto(this, "L&C", 26, R.color.lc_texto, true));
            tx.addView(Ui.texto(this, "HOME SERVICES", 11, R.color.lc_sec, true));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, -2, 1f);
            tlp.leftMargin = Ui.dp(this, 10);
            fila.addView(tx, tlp);
            // campana: lleva a los avisos; punto rojo si hay alguno
            android.widget.FrameLayout campana = new android.widget.FrameLayout(this);
            campana.setContentDescription("Avisos");
            ImageView bell = new ImageView(this);
            bell.setImageResource(R.drawable.ic_bell);
            bell.setColorFilter(0xFF0A0A0A);
            campana.addView(bell, new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26), Gravity.CENTER));
            if (avisos.length() > 0 || !novedades.isEmpty()) {
                View punto = new View(this);
                android.graphics.drawable.GradientDrawable pg = new android.graphics.drawable.GradientDrawable();
                pg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                pg.setColor(0xFFE91605);
                punto.setBackground(pg);
                android.widget.FrameLayout.LayoutParams plp = new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 9), Ui.dp(this, 9), Gravity.TOP | Gravity.END);
                plp.setMargins(0, Ui.dp(this, 9), Ui.dp(this, 9), 0);
                campana.addView(punto, plp);
            }
            campana.setOnClickListener(v -> {
                if (tituloAvisos != null) ((android.widget.ScrollView) p.contenido.getParent()).smoothScrollTo(0, tituloAvisos.getTop());
            });
            fila.addView(campana, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
            p.cabecera.addView(fila);
        } else {
            String[] t = {"", "Mis solicitudes", "Seguimiento", "Perfil"};
            p.cabecera.addView(Ui.texto(this, t[tab], 28, R.color.lc_texto, true));
        }
    }

    // ── Inicio ───────────────────────────────────────────────────────────────────────────────────

    private void pintarInicio() {
        LinearLayout c = p.contenido;
        String n = primerNombre(Api.nombre(this));
        int activas = 0;
        for (int i = 0; i < reqs.length(); i++) if (activa(reqs.optJSONObject(i).optString("estado"))) activas++;
        // Saludo sin tarjeta, como la maqueta
        c.addView(Ui.texto(this, n.isEmpty() ? "Hola" : "Hola, " + n, 26, R.color.lc_texto, true));
        c.addView(Ui.texto(this, "¿En qué podemos ayudarte hoy?", 15, R.color.lc_sec, false));
        android.widget.Button solicitar = Ui.boton(this, c, "Solicitar Servicio   →", true);
        solicitar.setAllCaps(false);
        solicitar.setTextSize(17f);
        solicitar.setOnClickListener(v -> startActivity(new Intent(this, NewRequestActivity.class)));
        LinearLayout fila1 = filaAccesos(c);
        acceso(fila1, R.drawable.ic_home, 0xFF62666A, "Mis Propiedades", nProps < 0 ? "Ver y administrar" : nProps + (nProps == 1 ? " propiedad" : " propiedades"),
                v -> startActivity(new Intent(this, PropertiesActivity.class)));
        acceso(fila1, R.drawable.ic_doc, 0xFF0A0A0A, "Mis Solicitudes", activas + (activas == 1 ? " activa" : " activas"),
                v -> { tab = 1; filtro = 0; pintar(); });
        // Banner de confianza: texto y categorías de la maqueta (la foto del técnico no tiene recurso todavía)
        LinearLayout banner = Ui.tarjeta(this, c);
        banner.getBackground().mutate().setTint(0xFF0A0A0A);
        banner.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        banner.addView(Ui.texto(this, "Servicio confiable\npara tu hogar", 20, android.R.color.white, true));
        TextView cats = Ui.texto(this, "HVAC · Plomería · Electricidad\nElectrodomésticos y más", 12, android.R.color.white, false);
        cats.setPadding(0, Ui.dp(this, 8), 0, 0);
        banner.addView(cats);
        // Asistente IA · Ofertas · Avisos
        LinearLayout fila2 = filaAccesos(c);
        acceso(fila2, R.drawable.ic_assistant, 0xFFE91605, "Asistente IA", null, v -> startActivity(new Intent(this, AsistenteActivity.class)));
        acceso(fila2, R.drawable.ic_offer, 0xFFE91605, "Ofertas", null, v -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Ofertas")
                .setMessage("Aquí verás las ofertas y promociones de L&C cuando estén disponibles.")
                .setPositiveButton("Entendido", null).show());
        final int nAvisos = avisos.length() + (novedades.isEmpty() ? 0 : 1);
        acceso(fila2, R.drawable.ic_bell, 0xFFE91605, nAvisos == 0 ? "Avisos" : "Avisos (" + nAvisos + ")", null, v -> {
            if (tituloAvisos != null) ((android.widget.ScrollView) p.contenido.getParent()).smoothScrollTo(0, tituloAvisos.getTop());
        });
        tituloAvisos = Ui.seccion(this, c, "Avisos");
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

    private LinearLayout filaAccesos(LinearLayout dest) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 10);
        lp.leftMargin = -Ui.dp(this, 5);    // las tarjetas llevan 5 dp por lado: así quedan al ras del hero
        lp.rightMargin = -Ui.dp(this, 5);
        dest.addView(f, lp);
        return f;
    }

    /** Tarjeta de acceso rápido: ícono vectorial, título y, si hay, subtítulo. */
    private void acceso(LinearLayout fila, int icono, int colorIcono, String texto, String sub, View.OnClickListener ir) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setMinimumHeight(Ui.dp(this, 96));
        t.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
        t.setBackground(getDrawable(R.drawable.bg_card));
        ImageView i = new ImageView(this);
        i.setImageResource(icono);
        i.setColorFilter(colorIcono);
        t.addView(i, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        TextView x = Ui.texto(this, texto, 14, R.color.lc_texto, true);
        x.setGravity(Gravity.CENTER);
        x.setPadding(0, Ui.dp(this, 6), 0, 0);
        t.addView(x);
        if (sub != null) {
            TextView y = Ui.texto(this, sub, 12, R.color.lc_sec, false);
            y.setGravity(Gravity.CENTER);
            t.addView(y);
        }
        t.setOnClickListener(ir);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.rightMargin = Ui.dp(this, 5);
        lp.leftMargin = Ui.dp(this, 5);
        fila.addView(t, lp);
    }

    // ── Solicitudes ──────────────────────────────────────────────────────────────────────────────

    private void pintarSolicitudes() {
        LinearLayout c = p.contenido;
        int total = reqs.length(), enCurso = 0;
        for (int i = 0; i < reqs.length(); i++) if (activa(reqs.optJSONObject(i).optString("estado"))) enCurso++;
        int[] cuenta = {total, enCurso, total - enCurso};
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        String[] nombres = {"Todas", "En curso", "Completadas"};
        int[] modo = {1, 0, 2};   // cada chip → filtro (0 en curso · 1 todas · 2 completadas)
        for (int i = 0; i < 3; i++) {
            final int m = modo[i];
            boolean on = filtro == m;
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER);
            chip.setMinimumHeight(Ui.dp(this, 44));
            chip.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(this, 22));
            bg.setColor(on ? 0xFFE91605 : 0xFFF5F5F7);
            chip.setBackground(bg);
            chip.addView(Ui.texto(this, nombres[i], 14, on ? android.R.color.white : R.color.lc_texto, true));
            TextView num = Ui.texto(this, String.valueOf(cuenta[i]), 12, on ? R.color.lc_rojo : R.color.lc_sec, true);
            num.setGravity(Gravity.CENTER);
            num.setMinWidth(Ui.dp(this, 22));
            num.setMinHeight(Ui.dp(this, 22));
            GradientDrawable cg = new GradientDrawable();
            cg.setShape(GradientDrawable.OVAL);
            cg.setColor(on ? 0xFFFFFFFF : 0xFFE5E5E8);
            num.setBackground(cg);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
            nlp.leftMargin = Ui.dp(this, 6);
            chip.addView(num, nlp);
            chip.setOnClickListener(v -> { filtro = m; pintar(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = Ui.dp(this, 6);
            fila.addView(chip, lp);
        }
        c.addView(fila);
        if (tarjetas(c, filtro) == 0) {
            c.addView(Ui.texto(this, "No hay solicitudes en esta lista.", 15, R.color.lc_sec, false));
        }
    }

    private String etiquetaCategoria(String id) {
        for (Ajustes.Opcion o : Ajustes.lista(this, "categories.list", Ajustes.CATEGORIAS, true)) {
            if (o.id.equals(id)) return o.label;
        }
        return id == null || id.isEmpty() || "null".equals(id) ? "Servicio" : id;
    }

    /** modo 0 activas · 1 todas · 2 completadas/canceladas. Devuelve cuántas dibujó. */
    private int tarjetas(LinearLayout dest, int modo) {
        int n = 0;
        for (int i = 0; i < reqs.length(); i++) {
            JSONObject j = reqs.optJSONObject(i);
            boolean act = activa(j.optString("estado"));
            if ((modo == 0 && !act) || (modo == 2 && act)) continue;
            n++;
            final long jid = j.optLong("id");
            String estado = j.optString("estado");
            boolean asignado = !j.optString("tecnicoNombre").isEmpty() && !"null".equals(j.optString("tecnicoNombre"));
            LinearLayout card = Ui.tarjeta(this, dest);
            // Fila 1: insignia de estado · #ID a su derecha · fecha/hora arriba a la derecha
            LinearLayout cab = new LinearLayout(this);
            cab.setOrientation(LinearLayout.HORIZONTAL);
            cab.setGravity(Gravity.CENTER_VERTICAL);
            String textoEstado = "PENDIENTE".equals(estado) && asignado ? "Asignada" : Ui.estadoCliente(this, estado);
            cab.addView(Ui.badgeCliente(this, estado, textoEstado, asignado));
            TextView idv = Ui.texto(this, "#" + jid, 12, R.color.lc_sec, false);
            idv.setPadding(Ui.dp(this, 8), 0, 0, 0);
            cab.addView(idv);
            cab.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            long cita = j.optLong("programadoPara", 0);
            cab.addView(Ui.texto(this, cita > 0 ? Ui.fechaCorta(cita) : "Por confirmar", 12, R.color.lc_texto, cita > 0));
            card.addView(cab);
            // Fila 2: título, dirección y descripción a la izquierda · foto 80×80 y flecha a la derecha
            LinearLayout cuerpo = new LinearLayout(this);
            cuerpo.setOrientation(LinearLayout.HORIZONTAL);
            cuerpo.setGravity(Gravity.CENTER_VERTICAL);
            cuerpo.setPadding(0, Ui.dp(this, 8), 0, 0);
            LinearLayout izq = new LinearLayout(this);
            izq.setOrientation(LinearLayout.VERTICAL);
            izq.addView(Ui.texto(this, etiquetaCategoria(j.optString("categoria")), 17, R.color.lc_texto, true));
            String dir = j.optString("direccion");
            if (!dir.isEmpty() && !"null".equals(dir)) izq.addView(filaIcono(R.drawable.ic_pin, 0xFF62666A, dir, 13, R.color.lc_sec, 1));
            izq.addView(filaIcono(R.drawable.ic_doc, 0xFF62666A, limpiarDescripcion(j.optString("descripcion")), 14, R.color.lc_texto, 2));
            cuerpo.addView(izq, new LinearLayout.LayoutParams(0, -2, 1f));
            ImageView foto = new ImageView(this);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(Ui.dp(this, 80), Ui.dp(this, 80));
            flp.leftMargin = Ui.dp(this, 10);
            cuerpo.addView(foto, flp);
            Ui.miniaturaServicio(this, foto, jid, j.optString("categoria"), 80);
            foto.setLayoutParams(flp);
            ImageView ch = new ImageView(this);
            ch.setImageResource(R.drawable.ic_chevron);
            ch.setColorFilter(0xFF0A0A0A);
            cuerpo.addView(ch, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
            card.addView(cuerpo);
            card.setOnClickListener(v -> {
                Intent it = new Intent(this, RequestDetailActivity.class);
                it.putExtra("id", jid);
                startActivity(it);
            });
        }
        return n;
    }

    /** Línea con ícono pequeño a la izquierda y texto (máx. {@code lineas} líneas). */
    private LinearLayout filaIcono(int icono, int colorIcono, String texto, int sp, int colorTexto, int lineas) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setGravity(Gravity.TOP);
        f.setPadding(0, Ui.dp(this, 3), 0, 0);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icono);
        iv.setColorFilter(colorIcono);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(this, 16), Ui.dp(this, 16));
        ilp.topMargin = Ui.dp(this, 2);
        f.addView(iv, ilp);
        TextView t = Ui.texto(this, texto, sp, colorTexto, false);
        t.setMaxLines(lineas);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(Ui.dp(this, 6), 0, 0, 0);
        f.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        return f;
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
            final long jid = j.optLong("id");
            Ui.seccion(this, c, "Solicitud #" + jid);
            c.addView(Ui.texto(this, limpiarDescripcion(j.optString("descripcion")), 14, R.color.lc_sec, false));
            LinearLayout card = Ui.tarjeta(this, c);
            List<String[]> etapas = lineaTiempo(d);
            Ui.lineaTiempo(this, card, etapas);
            String tec = d.optString("tecnicoNombre");
            if (!tec.isEmpty() && !"null".equals(tec)) {
                LinearLayout tc = Ui.tarjeta(this, c);
                LinearLayout fila = new LinearLayout(this);
                fila.setOrientation(LinearLayout.HORIZONTAL);
                fila.setGravity(Gravity.CENTER_VERTICAL);
                TextView av = Ui.texto(this, tec.substring(0, 1).toUpperCase(), 20, android.R.color.white, true);
                av.setGravity(Gravity.CENTER);
                GradientDrawable ag = new GradientDrawable();
                ag.setShape(GradientDrawable.OVAL);
                ag.setColor(getResources().getColor(R.color.lc_rojo));
                av.setBackground(ag);
                fila.addView(av, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
                LinearLayout tx = new LinearLayout(this);
                tx.setOrientation(LinearLayout.VERTICAL);
                tx.setPadding(Ui.dp(this, 12), 0, 0, 0);
                tx.addView(Ui.texto(this, tec, 16, R.color.lc_texto, true));
                tx.addView(Ui.texto(this, "Técnico asignado · L&C", 13, R.color.lc_sec, false));
                fila.addView(tx);
                tc.addView(fila);
                LinearLayout bs = new LinearLayout(this);
                bs.setOrientation(LinearLayout.HORIZONTAL);
                android.widget.Button llamar = Ui.boton(this, bs, "Llamar", false);
                llamar.setAllCaps(false);
                llamar.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:+17869227383"))));
                android.widget.Button msg = Ui.boton(this, bs, "Mensaje", true);
                msg.setAllCaps(false);
                msg.setOnClickListener(v -> startActivity(new Intent(this, RequestDetailActivity.class).putExtra("id", jid)));
                for (int q = 0; q < bs.getChildCount(); q++) {
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) bs.getChildAt(q).getLayoutParams();
                    lp.width = 0;
                    lp.weight = 1f;
                    lp.rightMargin = q == 0 ? Ui.dp(this, 8) : 0;
                }
                tc.addView(bs);
            }
            card.setOnClickListener(v -> startActivity(new Intent(this, RequestDetailActivity.class).putExtra("id", jid)));
        }
        if (n == 0) c.addView(Ui.texto(this, "Cuando tengas una solicitud activa verás aquí cada etapa.", 15, R.color.lc_sec, false));
    }

    /** Línea de tiempo del cliente: {texto, done|current|todo}; solo con campos reales del servidor. */
    static List<String[]> lineaTiempo(JSONObject d) {
        String est = d.optString("estado");
        boolean asignado = !d.optString("tecnicoNombre").isEmpty() && !"null".equals(d.optString("tecnicoNombre"));
        boolean enRevision = asignado || d.optLong("programadoPara", 0) > 0 || !"PENDIENTE".equals(est);
        boolean enRuta = "EN RUTA".equals(est) || "LLEGUÉ".equals(est) || "EN REPARACIÓN".equals(est) || "ESPERANDO APROBACIÓN".equals(est)
                || "PART HOLD".equals(est) || "PAUSADO".equals(est) || "REQUIERE GERENTE".equals(est) || "REPUESTO PRECARGADO".equals(est)
                || "COMPLETADO".equals(est) || "PAGADO".equals(est) || "CLIENTE NO ESTÁ".equals(est);
        boolean enSitio = enRuta && !"EN RUTA".equals(est);
        boolean reparacion = "EN REPARACIÓN".equals(est) || "ESPERANDO APROBACIÓN".equals(est) || "PART HOLD".equals(est) || "PAUSADO".equals(est)
                || "REQUIERE GERENTE".equals(est) || "REPUESTO PRECARGADO".equals(est) || "COMPLETADO".equals(est) || "PAGADO".equals(est);
        boolean fin = "COMPLETADO".equals(est) || "PAGADO".equals(est);
        String[] nombres = {"Solicitada", "En revisión", "Asignada", "En camino", "En el lugar", "En reparación", "Completado"};
        boolean[] hecho = {true, enRevision, asignado, enRuta, enSitio, reparacion, fin};
        List<String[]> l = new ArrayList<>();
        boolean actualMarcada = false;
        for (int i = 0; i < nombres.length; i++) {
            String st = hecho[i] ? "done" : (!actualMarcada ? "current" : "todo");
            if (!hecho[i]) actualMarcada = true;
            l.add(new String[]{nombres[i], st});
        }
        return l;
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
        String nombre = Api.nombre(this).isEmpty() ? "—" : Api.nombre(this);
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.HORIZONTAL);
        cab.setGravity(Gravity.CENTER_VERTICAL);
        ImageView av = new ImageView(this);
        av.setImageResource(R.drawable.ic_avatar_placeholder_suave);   // sin foto de perfil en el servidor: placeholder
        av.setScaleType(ImageView.ScaleType.CENTER_CROP);
        av.setContentDescription("Foto de perfil");
        cab.addView(av, new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)));
        LinearLayout nombres = new LinearLayout(this);
        nombres.setOrientation(LinearLayout.VERTICAL);
        nombres.setPadding(Ui.dp(this, 16), 0, 0, 0);
        nombres.addView(Ui.texto(this, nombre, 22, R.color.lc_texto, true));
        nombres.addView(Ui.texto(this, "Cliente", 15, R.color.lc_sec, false));
        cab.addView(nombres, new LinearLayout.LayoutParams(0, -2, 1f));
        c.addView(cab, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = Ui.dp(this, 20);
        c.addView(menu, mlp);
        filaMenu(menu, R.drawable.ic_home, "Mis propiedades", v -> startActivity(new Intent(this, PropertiesActivity.class)));
        filaMenu(menu, R.drawable.ic_list, "Mis solicitudes", v -> { filtro = 1; tab = 1; pintar(); });
        filaMenu(menu, R.drawable.ic_card, "Métodos de pago", v -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Métodos de pago")
                .setMessage("Por ahora los pagos son de prueba y se hacen desde cada solicitud con «Pagar (prueba)». "
                        + "Aquí podrás guardar tus tarjetas cuando se active el cobro real.")
                .setPositiveButton("Entendido", null).show());
        filaMenu(menu, R.drawable.ic_bell, "Notificaciones", v -> startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())));
        filaMenu(menu, R.drawable.ic_help, "Soporte", v -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Soporte L&C")
                .setItems(new String[]{"Llamar · 786-922-7383", "Escribir un correo"}, (dlg, which) -> {
                    if (which == 0) startActivity(new Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:+17869227383")));
                    else startActivity(new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:Carlosocampo1034@gmail.com"))
                            .putExtra(Intent.EXTRA_SUBJECT, "Soporte L&C Cliente"));
                })
                .setNegativeButton("Cerrar", null).show());
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        final String verF = ver;
        filaMenu(menu, R.drawable.ic_pin, "Acerca de", v -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("L&C Cliente").setMessage("Versión " + verF + "\nL&C Home Services")
                .setPositiveButton("Cerrar", null).show());
        if (!CloudApi.activo()) {   // herramienta técnica solo en el laboratorio con servidor propio
            filaMenu(menu, R.drawable.ic_list, "Diagnóstico de conexión", v -> startActivity(new Intent(this, DiagnosticoActivity.class)));
        }
        TextView salir = Ui.texto(this, "Cerrar sesión", 16, R.color.lc_rojo, true);
        salir.setGravity(Gravity.CENTER);
        salir.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_logout, 0, 0, 0);
        salir.setCompoundDrawablePadding(Ui.dp(this, 10));
        GradientDrawable sb = new GradientDrawable();
        sb.setCornerRadius(Ui.dp(this, 12));
        sb.setColor(0xFFFFFFFF);
        sb.setStroke(Ui.dp(this, 1), getResources().getColor(R.color.lc_rojo));
        salir.setBackground(sb);
        salir.setOnClickListener(v -> {
            Api.cerrarSesion(this);
            ColaCliente.borrarTodo(this);   // la cola pertenece al usuario que cerró sesión: no se envía con otra cuenta
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
        slp.topMargin = Ui.dp(this, 20);
        c.addView(salir, slp);
    }

    /** Fila de menú: ícono, texto y flecha, con línea divisoria. */
    private void filaMenu(LinearLayout dest, int icono, String texto, View.OnClickListener ir) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setGravity(Gravity.CENTER_VERTICAL);
        f.setMinimumHeight(Ui.dp(this, 56));
        ImageView ic = new ImageView(this);
        ic.setImageResource(icono);
        ic.setColorFilter(0xFF62666A);
        f.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        TextView t = Ui.texto(this, texto, 16, R.color.lc_texto, false);
        t.setPadding(Ui.dp(this, 14), 0, 0, 0);
        f.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView ch = new ImageView(this);
        ch.setImageResource(R.drawable.ic_chevron);
        ch.setColorFilter(0xFF9A9DA0);
        f.addView(ch, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
        f.setOnClickListener(ir);
        dest.addView(f);
        View l = new View(this);
        l.setBackgroundColor(getResources().getColor(R.color.lc_borde));
        dest.addView(l, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
    }

    private static String primerNombre(String n) {
        if (n == null || n.trim().isEmpty()) return "";
        return n.trim().split("\\s+")[0];
    }
}
