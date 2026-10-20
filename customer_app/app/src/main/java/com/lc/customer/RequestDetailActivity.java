package com.lc.customer;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;

/** Estado de una solicitud: cita, técnico, historial, aprobaciones con firma y factura. */
public class RequestDetailActivity extends AppCompatActivity {

    private long id;
    private Ui.Pantalla p;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        id = getIntent().getLongExtra("id", -1);
        p = Ui.pantalla(this, "Tu solicitud", "Nº " + id, true);
        Ui.boton(this, p.inferior, "Actualizar", false).setOnClickListener(v -> cargar());
    }

    @Override
    protected void onResume() {
        super.onResume();
        cargar();
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

    private void mostrar(Api.Respuesta r) {
        LinearLayout c = p.contenido;
        c.removeAllViews();
        if (!r.ok()) { c.addView(Ui.texto(this, r.error(), 15, R.color.lc_rojo, false)); return; }
        JSONObject j = r.cuerpo;
        TextView est = Ui.badge(this, Ui.estadoCliente(j.optString("estado")).toUpperCase());
        c.addView(est);
        LinearLayout card = Ui.tarjeta(this, c);
        fila(card, "Problema", j.optString("descripcion"));
        fila(card, "Equipo", j.optString("equipo"));
        long cita = j.optLong("programadoPara", 0);
        fila(card, "Cita", cita > 0 ? Ui.fecha(cita) : "Por programar · L&C te confirmará la hora");
        fila(card, "Técnico", j.optString("tecnicoNombre"));
        fila(card, "Dirección", j.optString("direccion"));
        JSONArray fotos = j.optJSONArray("fotos");
        fila(card, "Fotos", fotos != null ? String.valueOf(fotos.length()) : "0");

        // Fase 6: propuestas del técnico (Good/Better/Best). Se decide sobre la versión exacta mostrada (sha).
        JSONArray props = j.optJSONArray("propuestas");
        for (int i = 0; props != null && i < props.length(); i++) {
            JSONObject pr = props.optJSONObject(i);
            String estP = pr.optString("estado");
            if ("SUPERADA".equals(estP)) continue;           // versiones reemplazadas: no se muestran para decidir
            Ui.seccion(this, c, "Propuesta del técnico · versión " + pr.optInt("revision"));
            LinearLayout pc = Ui.tarjeta(this, c);
            JSONArray ops = pr.optJSONArray("opciones");
            for (int k = 0; ops != null && k < ops.length(); k++) {
                JSONObject o = ops.optJSONObject(k);
                fila(pc, etiquetaOpcion(ops, o.optString("nivel")) + " — " + o.optString("titulo"),
                        (o.optString("descripcion").isEmpty() ? "" : o.optString("descripcion") + "\n")
                        + detalle(o, "equipoPropuesto", "Equipo propuesto") + detalle(o, "especificaciones", "Especificaciones")
                        + detalle(o, "garantia", "Garantía")
                        + String.format(java.util.Locale.US, "Precio: $%.2f", o.optDouble("total")));
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
                Ui.boton(this, pc, "Revisar y firmar propuesta", true).setOnClickListener(v -> decidirPropuesta(prf));
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
            if (f.optBoolean("tienePdf")) {
                final long jid = j.optLong("id");
                Ui.boton(this, fc, "Ver factura (PDF)", false).setOnClickListener(v -> {
                    android.content.Intent it = new android.content.Intent(this, PdfViewerActivity.class);
                    it.putExtra("jobId", jid);
                    startActivity(it);
                });
            }
        }
        Ui.seccion(this, c, "Historial");
        JSONArray h = j.optJSONArray("historial");
        for (int i = 0; h != null && i < h.length(); i++) {
            JSONObject e = h.optJSONObject(i);
            String valor = e.optString("valor");
            if ("Estado".equals(e.optString("campo"))) valor = Ui.estadoCliente(valor);
            else if ("Cita".equals(e.optString("campo"))) {
                try { valor = Ui.fecha(Long.parseLong(valor)); } catch (Exception ignored) {}
            } else if ("Técnico asignado".equals(e.optString("campo"))) valor = "Asignado";
            c.addView(Ui.texto(this, Ui.fecha(e.optLong("ts")) + " · " + e.optString("campo") + ": " + valor, 14,
                    R.color.lc_texto, false));
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
                .setPositiveButton("Aprobar", null).setNegativeButton("Rechazar", null).setNeutralButton("Cerrar", null)
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
