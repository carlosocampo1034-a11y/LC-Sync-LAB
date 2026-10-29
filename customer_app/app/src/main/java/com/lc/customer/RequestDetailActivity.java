package com.lc.customer;

import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;

/** Estado de una solicitud: cita, técnico, historial, aprobaciones con firma y factura. */
public class RequestDetailActivity extends AppCompatActivity {

    private long id;
    private Ui.Pantalla p;
    private LinearLayout dinamico, chat, listaMsgs;
    private EditText cajaMsg;
    private String msgPendienteId = null;
    private boolean activa = false;
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sondeo = new Runnable() {
        @Override public void run() { if (activa) { cargarMensajes(); revisarEstado(); h.postDelayed(this, 6000); } }
    };
    private androidx.activity.result.ActivityResultLauncher<String> elegirFoto;
    private View secFotos, secEstado, secPropuesta;
    private int metodoPago = 0;                       // 0 Tarjeta · 1 Zelle · 2 Efectivo · 3 Otro (prueba: no cambia el cobro)
    private TextView[] chipsMetodo;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        id = getIntent().getLongExtra("id", -1);
        p = Ui.pantalla(this, "Tu solicitud", "Nº " + id, true);
        dinamico = new LinearLayout(this);
        dinamico.setOrientation(LinearLayout.VERTICAL);
        p.contenido.addView(dinamico);
        chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        p.contenido.addView(chat);
        construirChat();
        elegirFoto = registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) subirFotoDetalle(uri);
        });
        Ui.boton(this, p.inferior, "Actualizar", false).setOnClickListener(v -> { cargar(); cargarMensajes(); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        activa = true;
        cargar();
        h.post(sondeo);
    }

    @Override
    protected void onPause() {
        super.onPause();
        activa = false;
        h.removeCallbacks(sondeo);
    }

    private String firmaEstado = null;

    /** Seguimiento en vivo: si cambió el estado, la cita o el técnico, se recarga la pantalla sola (sin tocar nada). */
    private void revisarEstado() {
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.get(this, "/customer/requests/" + id);
            if (!r.ok()) return;
            String f = r.cuerpo.optString("estado") + "|" + r.cuerpo.optLong("programadoPara", 0) + "|" + r.cuerpo.optString("tecnicoNombre");
            runOnUiThread(() -> {
                if (isFinishing()) return;
                boolean cambio = firmaEstado != null && !firmaEstado.equals(f);
                firmaEstado = f;
                if (cambio) mostrar(r);
            });
        });
    }

    // ── Mensajes con L&C (asociados a esta orden; reintentar no duplica) ──────────────────────────
    private void construirChat() {
        Ui.seccion(this, chat, "Mensajes con L&C");
        listaMsgs = new LinearLayout(this);
        listaMsgs.setOrientation(LinearLayout.VERTICAL);
        chat.addView(listaMsgs);
        cajaMsg = Ui.campo(this, chat, "Escribe tu mensaje",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        Button enviar = Ui.boton(this, chat, "Enviar mensaje", true);
        enviar.setOnClickListener(v -> enviarMensaje(enviar));
    }

    private void cargarMensajes() {
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.get(this, "/customer/requests/" + id + "/messages");
            runOnUiThread(() -> {
                if (isFinishing()) return;
                listaMsgs.removeAllViews();
                JSONArray ms = r.ok() ? r.cuerpo.optJSONArray("mensajes") : null;
                if (!r.ok()) { listaMsgs.addView(Ui.texto(this, r.error(), 14, R.color.lc_rojo, false)); return; }
                if (ms == null || ms.length() == 0) {
                    listaMsgs.addView(Ui.texto(this, "Aún no hay mensajes. Escríbenos aquí sobre esta solicitud.", 14, R.color.lc_sec, false));
                    return;
                }
                for (int i = 0; i < ms.length(); i++) {
                    JSONObject x = ms.optJSONObject(i);
                    boolean mio = x.optBoolean("propio");
                    LinearLayout burbuja = Ui.tarjeta(this, listaMsgs);
                    burbuja.setGravity(mio ? android.view.Gravity.END : android.view.Gravity.START);
                    if (mio) burbuja.getBackground().mutate().setTint(0xFFF1F2F3);
                    burbuja.addView(Ui.texto(this, (mio ? "Tú" : "L&C") + " · " + Ui.fecha(x.optLong("ts")), 12, R.color.lc_sec, true));
                    burbuja.addView(Ui.texto(this, x.optString("texto"), 15, R.color.lc_texto, false));
                }
            });
        });
    }

    private void enviarMensaje(Button boton) {
        String t = cajaMsg.getText().toString().trim();
        if (t.isEmpty()) { cajaMsg.setError("Escribe un mensaje"); return; }
        if (msgPendienteId == null) msgPendienteId = java.util.UUID.randomUUID().toString();   // mismo id al reintentar
        final String cmid = msgPendienteId;
        boton.setEnabled(false);
        JSONObject body = new JSONObject();
        try { body.put("texto", t).put("clientMsgId", cmid); } catch (Exception ignored) {}
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.post(this, "/customer/requests/" + id + "/messages", body);
            runOnUiThread(() -> {
                boton.setEnabled(true);
                if (r.ok()) { cajaMsg.setText(""); msgPendienteId = null; cargarMensajes(); }
                else cajaMsg.setError(r.error() + " · tu mensaje sigue aquí; vuelve a tocar «Enviar»");
            });
        });
    }

    // ── Fotos de la solicitud ───────────────────────────────────────────────────────────────────
    private void seccionFotos(LinearLayout c) {
        secFotos = Ui.seccion(this, c, "Fotos");
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        LinearLayout tira = new LinearLayout(this);
        tira.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(tira);
        c.addView(hs);
        TextView estado = Ui.texto(this, "Cargando fotos…", 14, R.color.lc_sec, false);
        c.addView(estado);
        Button agregar = Ui.boton(this, c, "Agregar foto", false);
        agregar.setOnClickListener(v -> elegirFoto.launch("image/*"));
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.get(this, "/customer/requests/" + id + "/photos");
            JSONArray fs = r.ok() ? r.cuerpo.optJSONArray("fotos") : null;
            java.util.List<android.graphics.Bitmap> bmps = new java.util.ArrayList<>();
            java.util.List<String> urls = new java.util.ArrayList<>();
            for (int i = 0; fs != null && i < fs.length(); i++) {
                String u = fs.optJSONObject(i).optString("url");
                if (u.isEmpty() || "null".equals(u)) continue;
                try {
                    java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                    con.setConnectTimeout(10000); con.setReadTimeout(20000);
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 4;
                    try (java.io.InputStream in = con.getInputStream()) { bmps.add(android.graphics.BitmapFactory.decodeStream(in, null, o)); }
                    urls.add(u);
                } catch (Exception ignored) {}
            }
            runOnUiThread(() -> {
                if (isFinishing()) return;
                estado.setText(!r.ok() ? r.error() : urls.isEmpty() ? "Sin fotos todavía." : urls.size() + " foto(s) · toca una para ampliarla");
                for (int i = 0; i < bmps.size(); i++) {
                    if (bmps.get(i) == null) continue;
                    ImageView iv = new ImageView(this);
                    iv.setImageBitmap(bmps.get(i));
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 110), Ui.dp(this, 110));
                    lp.rightMargin = Ui.dp(this, 8);
                    final String u = urls.get(i);
                    iv.setOnClickListener(v -> startActivity(new android.content.Intent(this, FotoActivity.class).putExtra("url", u)));
                    tira.addView(iv, lp);
                }
            });
        });
    }

    private void subirFotoDetalle(android.net.Uri uri) {
        Executors.newSingleThreadExecutor().execute(() -> {
            String mime = getContentResolver().getType(uri);
            byte[] datos = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) { total += n; if (total > 5 * 1024 * 1024) { bo = null; break; } bo.write(buf, 0, n); }
                if (bo != null) datos = bo.toByteArray();
            } catch (Exception ignored) {}
            if (datos == null || (!"image/jpeg".equals(mime) && !"image/png".equals(mime))) {
                runOnUiThread(() -> Toast.makeText(this, "Elige una foto JPG o PNG de hasta 5 MB", Toast.LENGTH_LONG).show());
                return;
            }
            long cpid = 1 + Math.floorMod(new java.security.SecureRandom().nextLong(), 4_000_000_000_000_000_000L);
            Api.Respuesta r = Api.subirFoto(this, id, datos, mime, "OTHER", cpid);
            runOnUiThread(() -> {
                Toast.makeText(this, r.ok() ? "Foto agregada" : r.error(), Toast.LENGTH_LONG).show();
                if (r.ok()) cargar();
            });
        });
    }

    private void cargar() {
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.get(this, "/customer/requests/" + id);
            runOnUiThread(() -> mostrar(r));
        });
    }

    private void fila(LinearLayout c, String k, String v) {
        if (v == null || v.isEmpty() || "null".equals(v)) return;
        c.addView(Ui.texto(this, k, 13, R.color.lc_sec, false));
        TextView tv = Ui.texto(this, v, 16, R.color.lc_texto, false);
        tv.setPadding(0, 0, 0, Ui.dp(this, 8));
        c.addView(tv);
    }

    // ── Accesos rápidos, pestañas y desplazamiento a secciones (maqueta C8) ───────────────────────────
    private int topEnScroll(View v) {
        int y = 0;
        View x = v;
        while (x != null && x != p.contenido) {
            y += x.getTop();
            android.view.ViewParent par = x.getParent();
            x = par instanceof View ? (View) par : null;
        }
        return y;
    }

    private void irA(View v) {
        if (v == null) return;
        ((android.widget.ScrollView) p.contenido.getParent()).smoothScrollTo(0, Math.max(0, topEnScroll(v) - Ui.dp(this, 8)));
    }

    private void abrirMapa(String dir) {
        if (dir == null || dir.isEmpty() || "null".equals(dir)) { Toast.makeText(this, "Esta solicitud no tiene dirección", Toast.LENGTH_SHORT).show(); return; }
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(dir))));
        } catch (Exception e) {
            Toast.makeText(this, "No hay una app de mapas instalada", Toast.LENGTH_SHORT).show();
        }
    }

    private void accesoRapido(LinearLayout fila, int icono, String texto, View.OnClickListener ir) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12));
        t.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_card));
        ImageView i = new ImageView(this);
        i.setImageResource(icono);
        t.addView(i, new LinearLayout.LayoutParams(Ui.dp(this, 24), Ui.dp(this, 24)));
        TextView x = Ui.texto(this, texto, 13, R.color.lc_texto, true);
        x.setGravity(Gravity.CENTER);
        x.setPadding(0, Ui.dp(this, 4), 0, 0);
        t.addView(x);
        t.setOnClickListener(ir);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = Ui.dp(this, 4);
        lp.rightMargin = Ui.dp(this, 4);
        fila.addView(t, lp);
    }

    private void agregarAccesos(LinearLayout c, JSONObject j) {
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = Ui.dp(this, 12);
        c.addView(fila, flp);
        final String dir = j.optString("direccion");
        accesoRapido(fila, R.drawable.ic_phone, "Llamar", v -> startActivity(new android.content.Intent(android.content.Intent.ACTION_DIAL,
                android.net.Uri.parse("tel:+17869227383"))));
        accesoRapido(fila, R.drawable.ic_chat, "Mensajes", v -> irA(chat));
        accesoRapido(fila, R.drawable.ic_nav, "GPS", v -> abrirMapa(dir));
        accesoRapido(fila, R.drawable.ic_help, "Soporte", v -> Ui.dialogoSoporte(this));
    }

    private android.graphics.drawable.Drawable subrayado(boolean activa) {
        GradientDrawable linea = new GradientDrawable();
        linea.setColor(ContextCompat.getColor(this, activa ? R.color.lc_rojo : R.color.lc_borde));
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(0x00000000);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{fondo, linea});
        ld.setLayerGravity(1, Gravity.BOTTOM);
        ld.setLayerHeight(1, Ui.dp(this, activa ? 3 : 1));
        return ld;
    }

    /** Pestañas Resumen · Fotos · Estado · Propuesta: llevan a la sección correspondiente de esta pantalla. */
    private void agregarPestanas(LinearLayout c) {
        final LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        final String[] nombres = {"Resumen", "Fotos", "Estado", "Propuesta"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            TextView t = Ui.texto(this, nombres[i], 14, i == 0 ? R.color.lc_rojo : R.color.lc_sec, i == 0);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            t.setBackground(subrayado(i == 0));
            t.setOnClickListener(v -> {
                for (int k = 0; k < tabs.getChildCount(); k++) {
                    TextView o = (TextView) tabs.getChildAt(k);
                    o.setTextColor(ContextCompat.getColor(this, k == idx ? R.color.lc_rojo : R.color.lc_sec));
                    o.setTypeface(null, k == idx ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
                    o.setBackground(subrayado(k == idx));
                }
                if (idx == 0) ((android.widget.ScrollView) p.contenido.getParent()).smoothScrollTo(0, 0);
                else if (idx == 1) irA(secFotos);
                else if (idx == 2) irA(secEstado);
                else if (secPropuesta != null) irA(secPropuesta);
                else Toast.makeText(this, "Todavía no hay una propuesta del técnico", Toast.LENGTH_SHORT).show();
            });
            tabs.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 12);
        c.addView(tabs, lp);
    }

    /** Dos botones lado a lado: «Ver ubicación» y «Contactar técnico». */
    private void filaBotones(LinearLayout dest, String a, View.OnClickListener ia, String b, View.OnClickListener ib) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        Button x = Ui.boton(this, f, a, false);
        Button y = Ui.boton(this, f, b, true);
        x.setAllCaps(false);
        y.setAllCaps(false);
        x.setOnClickListener(ia);
        y.setOnClickListener(ib);
        for (int q = 0; q < f.getChildCount(); q++) {
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) f.getChildAt(q).getLayoutParams();
            lp.width = 0;
            lp.weight = 1f;
            lp.rightMargin = q == 0 ? Ui.dp(this, 8) : 0;
        }
        dest.addView(f);
    }

    private void filaMonto(LinearLayout dest, String etiqueta, double monto, boolean fuerte) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        f.addView(Ui.texto(this, etiqueta, fuerte ? 16 : 14, fuerte ? R.color.lc_texto : R.color.lc_sec, fuerte),
                new LinearLayout.LayoutParams(0, -2, 1f));
        f.addView(Ui.texto(this, String.format(java.util.Locale.US, "$%.2f", monto), fuerte ? 16 : 14, R.color.lc_texto, fuerte));
        dest.addView(f);
    }

    /** Desglose de una opción de la propuesta (solo los conceptos con importe) y su total. */
    private void desglose(LinearLayout dest, JSONObject o) {
        String[][] k = {{"costoManoObra", "Mano de obra"}, {"costoMateriales", "Materiales"}, {"costoEquipo", "Equipo"},
                {"costoPermiso", "Permiso"}, {"costoRemocion", "Remoción"}, {"costoAccesorios", "Accesorios"}};
        for (String[] par : k) {
            double v = o.optDouble(par[0], 0);
            if (v > 0) filaMonto(dest, par[1], v, false);
        }
        double desc = o.optDouble("descuentoMonto", 0);
        if (desc > 0) filaMonto(dest, "Descuento", -desc, false);
        if (o.has("total")) filaMonto(dest, "Total", o.optDouble("total"), true);
    }

    private void mostrar(Api.Respuesta r) {
        secPropuesta = null;
        secEstado = null;
        if (r.ok()) firmaEstado = r.cuerpo.optString("estado") + "|" + r.cuerpo.optLong("programadoPara", 0) + "|" + r.cuerpo.optString("tecnicoNombre");
        LinearLayout c = dinamico;
        c.removeAllViews();
        if (!r.ok()) { c.addView(Ui.texto(this, r.error(), 15, R.color.lc_rojo, false)); return; }
        JSONObject j = r.cuerpo;
        ((TextView) p.cabecera.getChildAt(1)).setText("#" + id);
        ((TextView) p.cabecera.getChildAt(2)).setText(Ajustes.etiqueta(this, "categories.list", Ajustes.CATEGORIAS, j.optString("categoria")));
        TextView est = Ui.badgeEstado(this, j.optString("estado"), Ui.estadoCliente(this, j.optString("estado")));
        c.addView(est);
        agregarAccesos(c, j);
        agregarPestanas(c);
        LinearLayout card = Ui.tarjeta(this, c);
        fila(card, "Problema", j.optString("descripcion"));
        fila(card, "Equipo", j.optString("equipo"));
        fila(card, "Servicio", Ajustes.etiqueta(this, "categories.list", Ajustes.CATEGORIAS, j.optString("categoria")));
        long cita = j.optLong("programadoPara", 0);
        fila(card, "Cita", cita > 0 ? Ui.fecha(cita) : "Por programar · L&C te confirmará la hora");
        fila(card, "Técnico", j.optString("tecnicoNombre"));
        fila(card, "Dirección", j.optString("direccion"));
        final String dirResumen = j.optString("direccion");
        filaBotones(card, "Ver ubicación", v -> abrirMapa(dirResumen), "Contactar técnico", v -> irA(chat));
        String diag = j.optString("diagnostico");
        if (!diag.isEmpty() && !"null".equals(diag)) {   // lo que anotó el técnico
            Ui.seccion(this, c, "Diagnóstico del técnico");
            LinearLayout dc = Ui.tarjeta(this, c);
            dc.addView(Ui.texto(this, diag, 15, R.color.lc_texto, false));
        }
        seccionFotos(c);

        // Fase 6: propuestas del técnico (Good/Better/Best). Se decide sobre la versión exacta mostrada (sha).
        JSONArray props = j.optJSONArray("propuestas");
        for (int i = 0; props != null && i < props.length(); i++) {
            JSONObject pr = props.optJSONObject(i);
            String estP = pr.optString("estado");
            if ("SUPERADA".equals(estP)) continue;           // versiones reemplazadas: no se muestran para decidir
            View secP = Ui.seccion(this, c, "Propuesta del técnico · versión " + pr.optInt("revision"));
            if (secPropuesta == null) secPropuesta = secP;
            LinearLayout pc = Ui.tarjeta(this, c);
            JSONArray ops = pr.optJSONArray("opciones");
            for (int k = 0; ops != null && k < ops.length(); k++) {
                JSONObject o = ops.optJSONObject(k);
                fila(pc, etiquetaOpcion(ops, o.optString("nivel")) + " — " + o.optString("titulo"),
                        (o.optString("descripcion").isEmpty() ? "" : o.optString("descripcion") + "\n")
                        + detalle(o, "equipoPropuesto", "Equipo propuesto") + detalle(o, "especificaciones", "Especificaciones")
                        + detalle(o, "garantia", "Garantía")
                        + String.format(java.util.Locale.US, "Precio: $%.2f", o.optDouble("total")));
                desglose(pc, o);
            }
            fila(pc, "Estado", estadoPropuesta(estP));
            if ("APROBADA".equals(estP)) {
                fila(pc, "Aprobada", etiquetaOpcion(ops, pr.optString("opcionElegida")) + " · "
                        + String.format(java.util.Locale.US, "$%.2f", pr.optDouble("totalAceptado")) + " · firmó "
                        + pr.optString("firmante") + " · " + Ui.fecha(pr.optLong("decidedAt")));
            } else if ("RECHAZADA".equals(estP)) {
                fila(pc, "Rechazada", Ui.fecha(pr.optLong("decidedAt")));
            } else if ("PENDIENTE".equals(estP)) {
                fila(pc, "Vence", Ui.fecha(pr.optLong("expiresAt")));
                final JSONObject prf = pr;
                Ui.boton(this, pc, "Aprobar y firmar", true).setOnClickListener(v -> decidirPropuesta(prf));
            }
        }

        // Aprobación adicional: la factura difiere de la propuesta aprobada (nunca se ajusta sola)
        JSONArray vars = j.optJSONArray("variaciones");
        for (int i = 0; vars != null && i < vars.length(); i++) {
            JSONObject va = vars.optJSONObject(i);
            String ev = va.optString("estado");
            if ("SUPERADA".equals(ev)) continue;
            boolean mayor = "MAYOR".equals(va.optString("tipo"));
            Ui.seccion(this, c, "PENDIENTE".equals(ev) ? "⚠ Aprobación adicional requerida" : "Aprobación adicional");
            LinearLayout vc = Ui.tarjeta(this, c);
            double dif = va.optDouble("diferencia");
            TextView aviso = Ui.texto(this, mayor
                    ? String.format(java.util.Locale.US, "El importe final supera lo que aprobaste en $%.2f", dif)
                    : String.format(java.util.Locale.US, "El trabajo cambió de alcance: $%.2f menos de lo aprobado", -dif),
                    16, R.color.lc_rojo, true);
            vc.addView(aviso);
            fila(vc, "Aprobado / final", String.format(java.util.Locale.US, "$%.2f → $%.2f", va.optDouble("aprobado"),
                    va.optDouble("facturado")));
            JSONArray its = va.optJSONArray("items");
            for (int k = 0; its != null && k < its.length(); k++) {
                JSONObject it = its.optJSONObject(k);
                fila(vc, it.optString("descripcion"), String.format(java.util.Locale.US, "%s × $%.2f = $%.2f",
                        it.optString("cantidad"), it.optDouble("precio"), it.optDouble("importe")));
            }
            fila(vc, "Estado", "PENDIENTE".equals(ev) ? "Esperando tu decisión" : ev);
            if ("PENDIENTE".equals(ev)) {
                final JSONObject vf = va;
                Ui.boton(this, vc, "Revisar y firmar la diferencia", true).setOnClickListener(v -> decidirVariacion(vf));
            }
        }

        JSONArray aps = j.optJSONArray("aprobaciones");
        for (int i = 0; aps != null && i < aps.length(); i++) {
            JSONObject a = aps.optJSONObject(i);
            Ui.seccion(this, c, "Aprobación: " + a.optString("tipo").toLowerCase());
            LinearLayout ac = Ui.tarjeta(this, c);
            fila(ac, "Detalle", a.optString("descripcion"));
            if (!a.isNull("monto")) fila(ac, "Monto", String.format(java.util.Locale.US, "$%.2f", a.optDouble("monto")));
            fila(ac, "Estado", a.optString("estado"));
            if ("PENDIENTE".equals(a.optString("estado"))) {
                long aid = a.optLong("id");
                Ui.boton(this, ac, "Revisar y firmar", true).setOnClickListener(v -> firmar(aid));
            }
        }
        JSONArray trabajo = j.optJSONArray("trabajoRealizado");
        if (trabajo != null && trabajo.length() > 0) {
            Ui.seccion(this, c, "Resultado del servicio");
            LinearLayout tc = Ui.tarjeta(this, c);
            double suma = 0;
            for (int i = 0; i < trabajo.length(); i++) {
                JSONObject w = trabajo.optJSONObject(i);
                fila(tc, w.optString("descripcion"), String.format(java.util.Locale.US, "%s × $%.2f = $%.2f",
                        w.optString("cantidad"), w.optDouble("precioUnitario"), w.optDouble("total")));
                suma += w.optDouble("total");
            }
            fila(tc, "Total del trabajo", String.format(java.util.Locale.US, "$%.2f", suma));
        }
        JSONObject f = j.optJSONObject("factura");
        if (f != null) {
            Ui.seccion(this, c, "Factura");
            LinearLayout fc = Ui.tarjeta(this, c);
            fila(fc, "Nº", f.optString("numero"));
            JSONObject cmp = f.optJSONObject("comparacion");
            if (cmp != null && !"IGUAL".equals(cmp.optString("tipo"))) {
                double d = cmp.optDouble("diferencia");
                boolean conAprob = !f.isNull("variacionId");
                String t = d > 0 ? String.format(java.util.Locale.US, "⚠ $%.2f por encima de lo aprobado ($%.2f)", d, cmp.optDouble("aprobado"))
                        : String.format(java.util.Locale.US, "ℹ $%.2f por debajo de lo aprobado ($%.2f)", -d, cmp.optDouble("aprobado"));
                if (d > 0) t += conAprob ? " · aprobaste la diferencia" : " · emitida sin aprobación adicional";
                fc.addView(Ui.texto(this, t, 15, d > 0 && !conAprob ? R.color.lc_rojo : R.color.lc_sec, true));
            }
            JSONArray its = f.optJSONArray("items");
            for (int k = 0; its != null && k < its.length(); k++) {
                JSONObject it = its.optJSONObject(k);
                fila(fc, it.optString("descripcion"), String.format(java.util.Locale.US, "%s × $%.2f = $%.2f",
                        it.optString("cantidad"), it.optDouble("precio"), it.optDouble("importe",
                                it.optDouble("cantidad") * it.optDouble("precio"))));
            }
            if (f.optDouble("descuento", 0) > 0) {
                fila(fc, "Descuento autorizado", String.format(java.util.Locale.US, "−$%.2f", f.optDouble("descuento")));
            }
            fila(fc, "Subtotal", String.format(java.util.Locale.US, "$%.2f", f.optDouble("subtotal")));
            fila(fc, "Impuesto", String.format(java.util.Locale.US, "$%.2f", f.optDouble("impuesto")));
            fila(fc, "Total", String.format(java.util.Locale.US, "$%.2f", f.optDouble("total")));
            fila(fc, "Estado", "PAGADA".equals(f.optString("estado")) ? "PAGADA (pago confirmado)" : f.optString("estado"));
            if (!f.isNull("pagado")) {
                fila(fc, "Pagado / saldo", String.format(java.util.Locale.US, "$%.2f / $%.2f",
                        f.optDouble("pagado"), f.optDouble("saldo")));
            }
            JSONArray pagos = j.optJSONArray("pagos");
            for (int k = 0; pagos != null && k < pagos.length(); k++) {
                JSONObject pg = pagos.optJSONObject(k);
                String estPago = pg.optString("estado");
                fila(fc, "Pago " + pg.optString("metodo").toLowerCase(), String.format(java.util.Locale.US, "$%.2f · ",
                        pg.optDouble("monto")) + ("CONFIRMADO".equals(estPago) ? "confirmado"
                        : "RECHAZADO".equals(estPago) ? "no aceptado" : "en revisión por L&C"));
            }
            if ("EMITIDA".equals(f.optString("estado")) && "simulated".equals(Ajustes.texto(this, "payments.mode", "off"))) {
                final double aPagar = f.optDouble("saldo", f.optDouble("total"));
                boolean aprobada = false;
                for (int q = 0; props != null && q < props.length(); q++) if ("APROBADA".equals(props.optJSONObject(q).optString("estado"))) aprobada = true;
                if (aprobada) fc.addView(Ui.texto(this, "✓ Propuesta aprobada", 15, R.color.lc_ok, true));
                filaMonto(fc, "Total a pagar", aPagar, true);
                fc.addView(Ui.texto(this, "Método de pago", 13, R.color.lc_sec, true));
                LinearLayout metodos = new LinearLayout(this);
                metodos.setOrientation(LinearLayout.HORIZONTAL);
                String[] mn = {"Tarjeta", "Zelle", "Efectivo", "Otro"};
                chipsMetodo = new TextView[4];
                for (int q = 0; q < 4; q++) {
                    final int mi = q;
                    TextView chip = Ui.texto(this, mn[q], 13, R.color.lc_texto, true);
                    chip.setGravity(Gravity.CENTER);
                    chip.setMinHeight(Ui.dp(this, 40));
                    chipsMetodo[q] = chip;
                    chip.setOnClickListener(v -> { metodoPago = mi; pintarMetodos(); });
                    LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0, -2, 1f);
                    mlp.rightMargin = Ui.dp(this, 6);
                    metodos.addView(chip, mlp);
                }
                fc.addView(metodos);
                pintarMetodos();
                fc.addView(Ui.texto(this, "Modo de prueba: no se cobra ningún método real; el pago queda registrado como prueba.", 12, R.color.lc_sec, false));
                Ui.boton(this, fc, "Procesar pago", true).setOnClickListener(v -> new AlertDialog.Builder(this)
                        .setTitle("Pagar (prueba)")
                        .setMessage(String.format(java.util.Locale.US, "Se registrará un pago de prueba de $%.2f.\nNo se cobra nada a tu tarjeta.", aPagar))
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Pagar (prueba)", (d, w) -> Executors.newSingleThreadExecutor().execute(() -> {
                            JSONObject cuerpoPago = new JSONObject();
                            try { cuerpoPago.put("idempotencyKey", "pago-" + java.util.UUID.randomUUID()); } catch (Exception ignored) { }   // un id por intento: no cobra dos veces
                            Api.Respuesta pr = Api.post(this, "/customer/requests/" + id + "/pay-test", cuerpoPago);
                            final boolean enCola = pr.codigo == 202 && pr.cuerpo != null && pr.cuerpo.optBoolean("enCola");
                            runOnUiThread(() -> {
                                Toast.makeText(this, enCola ? "Sin conexión por ahora: tu pago quedó guardado y se enviará solo al volver la red."
                                        : pr.ok() ? "Pago de prueba confirmado" : pr.error(), Toast.LENGTH_LONG).show();
                                cargar();
                            });
                        })).show());
            } else if ("PAGADA".equals(f.optString("estado"))) {
                fc.addView(Ui.texto(this, "✓ Pagada", 16, R.color.lc_texto, true));   // verde solo para «Completado»
            }
            if (f.optBoolean("tienePdf")) {
                final long jid = j.optLong("id");
                Ui.boton(this, fc, "Ver factura (PDF)", false).setOnClickListener(v -> {
                    android.content.Intent it = new android.content.Intent(this, PdfViewerActivity.class);
                    it.putExtra("jobId", jid);
                    startActivity(it);
                });
            }
        }
        secEstado = Ui.seccion(this, c, "Estado del servicio");
        LinearLayout tl = Ui.tarjeta(this, c);
        Ui.lineaTiempo(this, tl, HomeActivity.lineaTiempo(j));
        Ui.seccion(this, c, "Historial");
        JSONArray h = j.optJSONArray("historial");
        for (int i = 0; h != null && i < h.length(); i++) {
            JSONObject e = h.optJSONObject(i);
            String valor = e.optString("valor");
            if ("Estado".equals(e.optString("campo"))) valor = Ui.estadoCliente(this, valor);
            else if ("Cita".equals(e.optString("campo"))) {
                try { valor = Ui.fecha(Long.parseLong(valor)); } catch (Exception ignored) {}
            } else if ("Técnico asignado".equals(e.optString("campo"))) valor = "Asignado";
            c.addView(Ui.texto(this, Ui.fecha(e.optLong("ts")) + " · " + e.optString("campo") + ": " + valor, 14,
                    R.color.lc_texto, false));
        }
    }

    private void pintarMetodos() {
        if (chipsMetodo == null) return;
        for (int q = 0; q < chipsMetodo.length; q++) {
            boolean on = q == metodoPago;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(this, 10));
            g.setColor(0xFFFFFFFF);
            g.setStroke(Ui.dp(this, on ? 2 : 1), ContextCompat.getColor(this, on ? R.color.lc_rojo : R.color.lc_borde));
            chipsMetodo[q].setBackground(g);
        }
    }

    private void firmar(long aid) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        EditText nombre = Ui.campo(this, f, "Tu nombre completo",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        f.addView(Ui.texto(this, "Firma aquí:", 14, R.color.lc_sec, false));
        FirmaView firma = new FirmaView(this);
        f.addView(firma, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 160)));
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Aprobar").setView(f)
                .setPositiveButton("Aprobar", null).setNegativeButton("Rechazar", null).setNeutralButton("Cerrar", null)
                .create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (nombre.getText().toString().trim().length() < 3) { nombre.setError("Escribe tu nombre"); return; }
                if (firma.vacia()) { Toast.makeText(this, "Falta tu firma", Toast.LENGTH_SHORT).show(); return; }
                decidir(d, aid, "APROBADA", nombre.getText().toString().trim(), firma.pngBase64());
            });
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v ->
                    decidir(d, aid, "RECHAZADA", nombre.getText().toString().trim(), null));
        });
        d.show();
    }

    private void decidir(AlertDialog d, long aid, String decision, String firmante, String png) {
        JSONObject body = new JSONObject();
        try {
            body.put("decision", decision).put("firmante", firmante);
            if (png != null) body.put("firmaPngBase64", png);
        } catch (Exception ignored) {}
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.post(this, "/approvals/" + aid + "/decide", body);
            runOnUiThread(() -> {
                if (r.ok()) { d.dismiss(); cargar(); } else Toast.makeText(this, r.error(), Toast.LENGTH_LONG).show();
            });
        });
    }

    private static String estadoPropuesta(String e) {
        switch (e) {
            case "PENDIENTE": return "Esperando tu decisión";
            case "APROBADA": return "APROBADA";
            case "RECHAZADA": return "RECHAZADA";
            case "VENCIDA": return "Vencida (pide una nueva)";
            default: return e;
        }
    }

    /** "OPCIÓN n" fija por casilla (GOOD=1, BETTER=2, BEST=3), igual que en la app técnica; el id interno no cambia. */
    static String etiquetaOpcion(JSONArray ops, String nivel) {
        int i = java.util.Arrays.asList("GOOD", "BETTER", "BEST").indexOf(nivel);
        return i < 0 ? "OPCIÓN" : "OPCIÓN " + (i + 1);      // misma numeración que ve el técnico
    }

    private static String detalle(JSONObject o, String clave, String etiqueta) {
        String v = o.optString(clave, "").trim();
        return v.isEmpty() ? "" : etiqueta + ": " + v + "\n";
    }

    /** Elegir opción + nombre + firma (aprobar) o rechazar. Se envía el sha de la versión que se está viendo. */
    private void decidirPropuesta(JSONObject pr) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        android.widget.RadioGroup grupo = new android.widget.RadioGroup(this);
        JSONArray ops = pr.optJSONArray("opciones");
        for (int k = 0; ops != null && k < ops.length(); k++) {
            JSONObject o = ops.optJSONObject(k);
            android.widget.RadioButton rb = new android.widget.RadioButton(this);
            rb.setText(etiquetaOpcion(ops, o.optString("nivel")) + " — " + o.optString("titulo")
                    + String.format(java.util.Locale.US, " · $%.2f", o.optDouble("total")));
            rb.setTag(o.optString("nivel"));
            rb.setId(android.view.View.generateViewId());
            grupo.addView(rb);
        }
        f.addView(grupo);
        EditText nombre = Ui.campo(this, f, "Tu nombre completo",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        f.addView(Ui.texto(this, "Firma aquí:", 14, R.color.lc_sec, false));
        FirmaView firma = new FirmaView(this);
        f.addView(firma, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 160)));
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Propuesta · versión " + pr.optInt("revision")).setView(f)
                .setPositiveButton("Aprobar y firmar", null).setNegativeButton("Rechazar", null).setNeutralButton("Cerrar", null)
                .create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                android.view.View sel = grupo.findViewById(grupo.getCheckedRadioButtonId());
                if (sel == null) { Toast.makeText(this, "Elige una opción", Toast.LENGTH_SHORT).show(); return; }
                if (nombre.getText().toString().trim().length() < 3) { nombre.setError("Escribe tu nombre"); return; }
                if (firma.vacia()) { Toast.makeText(this, "Falta tu firma", Toast.LENGTH_SHORT).show(); return; }
                enviarDecision(d, pr, "APROBADA", (String) sel.getTag(), nombre.getText().toString().trim(), firma.pngBase64());
            });
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v ->
                    enviarDecision(d, pr, "RECHAZADA", null, nombre.getText().toString().trim(), null));
        });
        d.show();
    }

    private void enviarDecision(AlertDialog d, JSONObject pr, String decision, String opcion, String firmante, String png) {
        JSONObject body = new JSONObject();
        try {
            body.put("decision", decision).put("firmante", firmante).put("contenidoSha256", pr.optString("contenidoSha256"));
            if (opcion != null) body.put("opcion", opcion);
            if (png != null) body.put("firmaPngBase64", png);
        } catch (Exception ignored) {}
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.post(this, "/customer/proposals/" + pr.optLong("id") + "/decide", body);
            runOnUiThread(() -> {
                if (r.codigo == 202 && r.cuerpo != null && r.cuerpo.optBoolean("enCola")) {
                    d.dismiss();
                    Toast.makeText(this, "Sin conexión por ahora: tu decisión quedó guardada y se enviará sola al volver la red.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (r.ok()) { d.dismiss(); cargar(); }
                else {
                    // p. ej. 409: el técnico cambió la propuesta → se recarga para ver la versión nueva
                    Toast.makeText(this, r.error(), Toast.LENGTH_LONG).show();
                    if (r.codigo == 409) { d.dismiss(); cargar(); }
                }
            });
        });
    }

    private void decidirVariacion(JSONObject va) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        f.addView(Ui.texto(this, String.format(java.util.Locale.US, "Aprobado $%.2f · final $%.2f · diferencia $%.2f",
                va.optDouble("aprobado"), va.optDouble("facturado"), va.optDouble("diferencia")), 14, R.color.lc_texto, true));
        EditText nombre = Ui.campo(this, f, "Tu nombre completo",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        f.addView(Ui.texto(this, "Firma aquí:", 14, R.color.lc_sec, false));
        FirmaView firma = new FirmaView(this);
        f.addView(firma, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 160)));
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Aprobar la diferencia").setView(f)
                .setPositiveButton("Aprobar", null).setNegativeButton("Rechazar", null).setNeutralButton("Cerrar", null).create();
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (nombre.getText().toString().trim().length() < 3) { nombre.setError("Escribe tu nombre"); return; }
                if (firma.vacia()) { Toast.makeText(this, "Falta tu firma", Toast.LENGTH_SHORT).show(); return; }
                enviarVariacion(d, va, "APROBADA", nombre.getText().toString().trim(), firma.pngBase64());
            });
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v ->
                    enviarVariacion(d, va, "RECHAZADA", nombre.getText().toString().trim(), null));
        });
        d.show();
    }

    private void enviarVariacion(AlertDialog d, JSONObject va, String decision, String firmante, String png) {
        JSONObject body = new JSONObject();
        try {
            body.put("decision", decision).put("firmante", firmante).put("itemsSha256", va.optString("itemsSha256"));
            if (png != null) body.put("firmaPngBase64", png);
        } catch (Exception ignored) {}
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.post(this, "/customer/variances/" + va.optLong("id") + "/decide", body);
            runOnUiThread(() -> {
                if (r.ok() || r.codigo == 409) { d.dismiss(); cargar(); }
                if (!r.ok()) Toast.makeText(this, r.error(), Toast.LENGTH_LONG).show();
            });
        });
    }
}
