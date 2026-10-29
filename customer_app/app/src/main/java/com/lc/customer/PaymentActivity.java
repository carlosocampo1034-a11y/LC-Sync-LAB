package com.lc.customer;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;

/**
 * Pago del servicio (maqueta C11): pantalla propia con la propuesta aprobada, el total y el método.
 * Mismas reglas de siempre: solo se cobra si la factura está EMITIDA y el modo de pagos es «simulated»
 * (pago de prueba, `/customer/requests/{id}/pay-test`); el efectivo lo confirma el técnico. Termina en la confirmación.
 */
public class PaymentActivity extends AppCompatActivity {

    private long id;
    private Ui.Pantalla p;
    private LinearLayout dinamico;
    private int metodoPago = 0;                       // 0 Tarjeta (prueba) · 1 Efectivo (solo informativo)
    private TextView[] fichas;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        id = getIntent().getLongExtra("id", -1);
        p = Ui.pantalla(this, "Pago del servicio", "Solicitud Nº " + id, true);
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
        p.inferior.removeAllViews();
        if (!r.ok()) { c.addView(Ui.texto(this, r.error(), 15, R.color.lc_rojo, false)); return; }
        JSONObject j = r.cuerpo;
        boolean aprobada = false;
        JSONArray props = j.optJSONArray("propuestas");
        for (int q = 0; props != null && q < props.length(); q++) if ("APROBADA".equals(props.optJSONObject(q).optString("estado"))) aprobada = true;
        if (aprobada) banner(c, "✓  Propuesta aprobada", R.color.lc_ok, 0xFFE8F5EC);
        JSONObject f = j.optJSONObject("factura");
        if (f == null) {
            c.addView(Ui.texto(this, "Todavía no hay una factura para pagar. El pago se habilita cuando el técnico completa el trabajo y se emite la factura.", 15, R.color.lc_sec, false));
            return;
        }
        final double aPagar = f.optDouble("saldo", f.optDouble("total"));
        if ("PAGADA".equals(f.optString("estado"))) { confirmacion(c, f.optDouble("total"), j.optJSONArray("pagos")); return; }
        LinearLayout tj = Ui.tarjeta(this, c);
        tj.addView(Ui.texto(this, "Total a pagar", 16, R.color.lc_texto, false));
        tj.addView(Ui.texto(this, String.format(java.util.Locale.US, "$%.2f", aPagar), 34, R.color.lc_texto, true));
        if (!"EMITIDA".equals(f.optString("estado")) || !"simulated".equals(Ajustes.texto(this, "payments.mode", "off"))) {
            c.addView(Ui.texto(this, "El pago en línea no está disponible por ahora. Coordina el pago con tu técnico.", 14, R.color.lc_sec, false));
            return;
        }
        c.addView(Ui.texto(this, "Selecciona un método de pago", 18, R.color.lc_texto, true));
        LinearLayout metodos = new LinearLayout(this);
        metodos.setOrientation(LinearLayout.HORIZONTAL);
        // Solo métodos con respaldo real: tarjeta de prueba y efectivo (Zelle y «Otro» no existen en el backend)
        String[] mn = {"Tarjeta", "Efectivo"};
        fichas = new TextView[2];
        for (int q = 0; q < 2; q++) {
            final int mi = q;
            TextView t = Ui.texto(this, mn[q], 16, R.color.lc_texto, true);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(Ui.dp(this, 88));
            fichas[q] = t;
            t.setOnClickListener(v -> { metodoPago = mi; pintar(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.topMargin = Ui.dp(this, 8);
            lp.rightMargin = q == 0 ? Ui.dp(this, 8) : 0;
            metodos.addView(t, lp);
        }
        c.addView(metodos);
        pintar();
        c.addView(Ui.texto(this, "Modo de prueba: no se cobra ningún método real; el pago queda registrado como prueba.", 12, R.color.lc_sec, false));
        Ui.boton(this, p.inferior, "Procesar pago", true).setOnClickListener(v -> procesar(aPagar));
    }

    private void procesar(final double aPagar) {
        if (metodoPago == 1) {   // Efectivo: lo confirma el técnico al recibir el dinero; aquí no se registra nada
            new AlertDialog.Builder(this)
                    .setTitle("Pago en efectivo")
                    .setMessage(String.format(java.util.Locale.US, "Entrega $%.2f en efectivo a tu técnico. Él confirma el cobro en su app y la factura quedará pagada; no se registra nada hasta entonces.", aPagar))
                    .setPositiveButton("Entendido", null).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Pagar (prueba)")
                .setMessage(String.format(java.util.Locale.US, "Se registrará un pago de prueba de $%.2f.\nNo se cobra nada a tu tarjeta.", aPagar))
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Pagar (prueba)", (d, w) -> Executors.newSingleThreadExecutor().execute(() -> {
                    JSONObject cuerpo = new JSONObject();
                    try { cuerpo.put("idempotencyKey", "pago-" + java.util.UUID.randomUUID()); } catch (Exception ignored) { }   // un id por intento: no cobra dos veces
                    Api.Respuesta pr = Api.post(this, "/customer/requests/" + id + "/pay-test", cuerpo);
                    final boolean enCola = pr.codigo == 202 && pr.cuerpo != null && pr.cuerpo.optBoolean("enCola");
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        if (enCola) Toast.makeText(this, "Sin conexión por ahora: tu pago quedó guardado y se enviará solo al volver la red.", Toast.LENGTH_LONG).show();
                        else if (!pr.ok()) Toast.makeText(this, pr.error(), Toast.LENGTH_LONG).show();
                        cargar();   // si quedó pagada, se muestra la confirmación
                    });
                })).show();
    }

    private void pintar() {
        if (fichas == null) return;
        for (int q = 0; q < fichas.length; q++) {
            boolean on = q == metodoPago;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(this, 14));
            g.setColor(on ? 0xFFFDECEA : 0xFFF5F5F7);
            g.setStroke(Ui.dp(this, on ? 2 : 1), ContextCompat.getColor(this, on ? R.color.lc_rojo : R.color.lc_borde));
            fichas[q].setBackground(g);
            fichas[q].setTextColor(ContextCompat.getColor(this, on ? R.color.lc_rojo : R.color.lc_texto));
        }
    }

    private void banner(LinearLayout c, String texto, int color, int fondo) {
        TextView t = Ui.texto(this, texto, 15, color, true);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(Ui.dp(this, 12));
        g.setColor(fondo);
        t.setBackground(g);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        c.addView(t);
    }

    /** Confirmación (la factura ya está pagada): total, método y regreso al detalle de la solicitud. */
    private void confirmacion(LinearLayout c, double total, JSONArray pagos) {
        c.removeAllViews();
        TextView ok = Ui.texto(this, "✓", 56, R.color.lc_ok, true);
        ok.setGravity(Gravity.CENTER);
        c.addView(ok);
        TextView t = Ui.texto(this, "¡Pago confirmado!", 26, R.color.lc_texto, true);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        String met = "";
        for (int k = 0; pagos != null && k < pagos.length(); k++) {
            JSONObject pg = pagos.optJSONObject(k);
            if (!"CONFIRMADO".equals(pg.optString("estado"))) continue;
            String m = pg.optString("metodo");
            met = "EFECTIVO".equals(m) ? "Efectivo" : "PRUEBA".equals(m) ? "Tarjeta (prueba)" : "STRIPE".equals(m) ? "Tarjeta" : m;
        }
        TextView d = Ui.texto(this, String.format(java.util.Locale.US, "$%.2f", total) + (met.isEmpty() ? "" : " · " + met), 16, R.color.lc_sec, false);
        d.setGravity(Gravity.CENTER);
        c.addView(d);
        Ui.boton(this, p.inferior, "Ver mi solicitud", true).setOnClickListener(v -> {
            Intent it = new Intent(this, RequestDetailActivity.class);
            it.putExtra("id", id);
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(it);
            finish();
        });
    }
}
