package com.lc.customer;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;

/**
 * Propuesta de servicio (maqueta C10): pantalla propia con las opciones del técnico, la firma del cliente y
 * Rechazar / Aprobar y firmar. Usa las mismas llamadas de siempre (`/customer/proposals/{id}/decide`).
 * Al aprobar abre el Pago (PaymentActivity). Se abre desde «Ver propuesta» del detalle.
 */
public class ProposalActivity extends AppCompatActivity {

    private long id;
    private Ui.Pantalla p;
    private LinearLayout dinamico;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        id = getIntent().getLongExtra("id", -1);
        p = Ui.pantalla(this, "Propuesta de servicio", "Solicitud Nº " + id, true);
        dinamico = new LinearLayout(this);
        dinamico.setOrientation(LinearLayout.VERTICAL);
        p.contenido.addView(dinamico);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cargar();
    }

    private void cargar() {
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.get(this, "/customer/requests/" + id);
            runOnUiThread(() -> { if (!isFinishing()) mostrar(r); });
        });
    }

    private void mostrar(Api.Respuesta r) {
        LinearLayout c = dinamico;
        c.removeAllViews();
        if (!r.ok()) { c.addView(Ui.texto(this, r.error(), 15, R.color.lc_rojo, false)); return; }
        JSONArray props = r.cuerpo.optJSONArray("propuestas");
        JSONObject pr = null;
        for (int i = 0; props != null && i < props.length(); i++) {
            JSONObject x = props.optJSONObject(i);
            if (!"SUPERADA".equals(x.optString("estado"))) { pr = x; break; }   // versiones reemplazadas: no se muestran
        }
        if (pr == null) { c.addView(Ui.texto(this, "Todavía no hay una propuesta del técnico.", 15, R.color.lc_sec, false)); return; }
        final JSONObject prf = pr;
        String est = pr.optString("estado");
        banner(c, est);
        JSONArray ops = pr.optJSONArray("opciones");
        final java.util.List<android.widget.RadioButton> radios = new java.util.ArrayList<>();
        boolean pendiente = "PENDIENTE".equals(est);
        for (int k = 0; ops != null && k < ops.length(); k++) {
            JSONObject o = ops.optJSONObject(k);
            LinearLayout tj = Ui.tarjeta(this, c);
            if (pendiente) {
                final android.widget.RadioButton rb = new android.widget.RadioButton(this);
                rb.setText(RequestDetailActivity.etiquetaOpcion(ops, o.optString("nivel")) + " — " + o.optString("titulo"));
                rb.setTextSize(16f);
                rb.setTypeface(null, android.graphics.Typeface.BOLD);
                rb.setTag(o.optString("nivel"));
                radios.add(rb);
                rb.setOnClickListener(v -> { for (android.widget.RadioButton x : radios) x.setChecked(x == rb); });
                tj.addView(rb);
            } else {
                tj.addView(Ui.texto(this, RequestDetailActivity.etiquetaOpcion(ops, o.optString("nivel")) + " — " + o.optString("titulo"), 16, R.color.lc_texto, true));
            }
            String desc = o.optString("descripcion");
            if (!desc.isEmpty()) tj.addView(Ui.texto(this, desc, 14, R.color.lc_sec, false));
            linea(tj, "equipoPropuesto", "Equipo propuesto", o);
            linea(tj, "especificaciones", "Especificaciones", o);
            linea(tj, "garantia", "Garantía", o);
            desglose(tj, o);
        }
        if (pendiente) {
            c.addView(Ui.texto(this, "Firma del cliente", 18, R.color.lc_texto, true));
            final EditText nombre = Ui.campo(this, c, "Tu nombre completo", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            final FirmaView firma = new FirmaView(this);
            GradientDrawable fg = new GradientDrawable();
            fg.setCornerRadius(Ui.dp(this, 12));
            fg.setColor(0xFFFFFFFF);
            fg.setStroke(Ui.dp(this, 1), ContextCompat.getColor(this, R.color.lc_borde));
            firma.setBackground(fg);
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, Ui.dp(this, 180));
            fl.topMargin = Ui.dp(this, 8);
            c.addView(firma, fl);
            TextView limpiar = Ui.texto(this, "Limpiar firma", 14, R.color.lc_rojo, true);
            limpiar.setGravity(Gravity.END);
            limpiar.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            limpiar.setOnClickListener(v -> firma.limpiar());
            c.addView(limpiar);
            c.addView(Ui.texto(this, "Vence: " + Ui.fecha(prf.optLong("expiresAt")), 12, R.color.lc_sec, false));
            LinearLayout fila = new LinearLayout(this);
            fila.setOrientation(LinearLayout.HORIZONTAL);
            Button rech = Ui.boton(this, fila, "Rechazar", false);
            Button apr = Ui.boton(this, fila, "Aprobar y firmar", true);
            rech.setAllCaps(false);
            apr.setAllCaps(false);
            for (int q = 0; q < 2; q++) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) fila.getChildAt(q).getLayoutParams();
                lp.width = 0; lp.weight = 1f; lp.rightMargin = q == 0 ? Ui.dp(this, 8) : 0;
            }
            c.addView(fila);
            apr.setOnClickListener(v -> {
                android.widget.RadioButton sel = null;
                for (android.widget.RadioButton x : radios) if (x.isChecked()) sel = x;
                if (sel == null) { Toast.makeText(this, "Elige una opción", Toast.LENGTH_SHORT).show(); return; }
                if (nombre.getText().toString().trim().length() < 3) { nombre.setError("Escribe tu nombre"); return; }
                if (firma.vacia()) { Toast.makeText(this, "Falta tu firma", Toast.LENGTH_SHORT).show(); return; }
                enviar(prf, "APROBADA", (String) sel.getTag(), nombre.getText().toString().trim(), firma.pngBase64());
            });
            rech.setOnClickListener(v -> enviar(prf, "RECHAZADA", null, nombre.getText().toString().trim(), null));
        } else if ("APROBADA".equals(est)) {
            c.addView(Ui.texto(this, "Aprobada · " + RequestDetailActivity.etiquetaOpcion(ops, prf.optString("opcionElegida")) + " · "
                    + String.format(java.util.Locale.US, "$%.2f", prf.optDouble("totalAceptado")) + " · firmó " + prf.optString("firmante")
                    + " · " + Ui.fecha(prf.optLong("decidedAt")), 14, R.color.lc_sec, false));
            Ui.boton(this, c, "Pagar", true).setOnClickListener(v -> irAPago());
        } else if ("RECHAZADA".equals(est)) {
            c.addView(Ui.texto(this, "Rechazada · " + Ui.fecha(prf.optLong("decidedAt")), 14, R.color.lc_sec, false));
        }
    }

    private void banner(LinearLayout c, String est) {
        boolean ok = "APROBADA".equals(est), pend = "PENDIENTE".equals(est);
        TextView t = Ui.texto(this, ok ? "✓  Propuesta aprobada" : pend ? "Pendiente de aprobación"
                : "RECHAZADA".equals(est) ? "Propuesta rechazada" : "Vencida (pide una nueva)", 15,
                ok ? R.color.lc_ok : pend ? R.color.lc_texto : R.color.lc_sec, true);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 12));
        g.setColor(ok ? 0xFFE8F5EC : pend ? 0xFFFFF1DD : 0xFFF1F2F3);
        t.setBackground(g);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        c.addView(t);
    }

    private void linea(LinearLayout dest, String clave, String etiqueta, JSONObject o) {
        String v = o.optString(clave, "").trim();
        if (!v.isEmpty()) dest.addView(Ui.texto(this, etiqueta + ": " + v, 13, R.color.lc_sec, false));
    }

    private void fila(LinearLayout dest, String etiqueta, double monto, boolean fuerte) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
        f.addView(Ui.texto(this, etiqueta, fuerte ? 16 : 14, fuerte ? R.color.lc_texto : R.color.lc_sec, fuerte), new LinearLayout.LayoutParams(0, -2, 1f));
        f.addView(Ui.texto(this, String.format(java.util.Locale.US, "$%.2f", monto), fuerte ? 16 : 14, R.color.lc_texto, fuerte));
        dest.addView(f);
    }

    private void desglose(LinearLayout dest, JSONObject o) {
        String[][] k = {{"costoManoObra", "Mano de obra"}, {"costoMateriales", "Materiales"}, {"costoEquipo", "Equipo"},
                {"costoPermiso", "Permiso"}, {"costoRemocion", "Remoción"}, {"costoAccesorios", "Accesorios"}};
        for (String[] par : k) {
            double v = o.optDouble(par[0], 0);
            if (v > 0) fila(dest, par[1], v, false);
        }
        double desc = o.optDouble("descuentoMonto", 0);
        if (desc > 0) fila(dest, "Descuento", -desc, false);
        if (o.has("total")) fila(dest, "Total", o.optDouble("total"), true);
    }

    private void irAPago() {
        Intent it = new Intent(this, PaymentActivity.class);
        it.putExtra("id", id);
        startActivity(it);
    }

    /** Se envía el sha de la versión que se está viendo; sin conexión la decisión queda en cola (igual que antes). */
    private void enviar(JSONObject pr, String decision, String opcion, String firmante, String png) {
        JSONObject body = new JSONObject();
        try {
            body.put("decision", decision).put("firmante", firmante).put("contenidoSha256", pr.optString("contenidoSha256"));
            if (opcion != null) body.put("opcion", opcion);
            if (png != null) body.put("firmaPngBase64", png);
        } catch (Exception ignored) { }
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.post(this, "/customer/proposals/" + pr.optLong("id") + "/decide", body);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (r.codigo == 202 && r.cuerpo != null && r.cuerpo.optBoolean("enCola")) {
                    Toast.makeText(this, "Sin conexión por ahora: tu decisión quedó guardada y se enviará sola al volver la red.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (r.ok()) {
                    cargar();
                    if ("APROBADA".equals(decision)) irAPago();
                } else {
                    Toast.makeText(this, r.error(), Toast.LENGTH_LONG).show();
                    if (r.codigo == 409) cargar();   // el técnico cambió la propuesta: se recarga la versión nueva
                }
            });
        });
    }
}
