package com.lc.technician;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.lc.technician.sync.SupabaseClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Conversación de una orden con su cliente. Misma ruta que usa la app Cliente (lc-api /orders/:id/messages). */
public class ChatActivity extends AppCompatActivity {

    private long jobId;
    private LinearLayout burbujas;
    private ScrollView sv;
    private EditText entrada;
    private long ultimoId = 0;
    private boolean visible;
    private String pendienteId;   // clientMsgId del mensaje que aún no se confirmó (idempotente al reintentar)
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Runnable refresco = new Runnable() {
        @Override public void run() { if (visible) { cargar(); h.postDelayed(this, 6000); } }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        jobId = getIntent().getLongExtra("job", 0);
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(0xFFFFFFFF);
        TextView atras = t("← Volver", 15, 0xFFE91605, true);
        atras.setPadding(dp(20), dp(16), dp(20), dp(4));
        atras.setOnClickListener(v -> finish());
        TextView titulo = t(getIntent().getStringExtra("cliente"), 22, 0xFF0A0A0A, true);
        titulo.setPadding(dp(20), 0, dp(20), dp(2));
        TextView sub = t("Orden #" + jobId, 13, 0xFF62666A, false);
        sub.setPadding(dp(20), 0, dp(20), dp(12));
        raiz.addView(atras); raiz.addView(titulo); raiz.addView(sub);
        sv = new ScrollView(this);
        burbujas = new LinearLayout(this);
        burbujas.setOrientation(LinearLayout.VERTICAL);
        burbujas.setPadding(dp(16), dp(8), dp(16), dp(8));
        sv.addView(burbujas);
        raiz.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout barra = new LinearLayout(this);
        barra.setOrientation(LinearLayout.HORIZONTAL);
        barra.setGravity(Gravity.CENTER_VERTICAL);
        barra.setPadding(dp(12), dp(8), dp(12), dp(8));
        entrada = new EditText(this);
        entrada.setHint("Escribe un mensaje");
        entrada.setBackgroundResource(R.drawable.bg_input);
        entrada.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView enviar = t("ENVIAR", 14, 0xFFFFFFFF, true);
        enviar.setGravity(Gravity.CENTER);
        enviar.setBackgroundResource(R.drawable.bg_button_primary);
        enviar.setPadding(dp(18), 0, dp(18), 0);
        enviar.setOnClickListener(v -> enviar());
        barra.addView(entrada, new LinearLayout.LayoutParams(0, dp(48), 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48));
        lp.leftMargin = dp(8);
        barra.addView(enviar, lp);
        raiz.addView(barra);
        setContentView(raiz);
        InsetsHelper.padSystemBarsAndIme(raiz);
    }

    @Override protected void onResume() { super.onResume(); visible = true; h.removeCallbacks(refresco); h.post(refresco); }
    @Override protected void onPause() { super.onPause(); visible = false; h.removeCallbacks(refresco); }

    private void cargar() {
        final String auth = SupabaseClient.get(this).authHeader();
        if (auth == null) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                retrofit2.Response<okhttp3.ResponseBody> r = SupabaseClient.get(this).api().getMessages(auth, jobId, String.valueOf(ultimoId)).execute();
                if (!r.isSuccessful() || r.body() == null) return;
                final JSONArray a = new JSONArray(r.body().string());
                if (a.length() == 0) return;
                runOnUiThread(() -> {
                    for (int i = 0; i < a.length(); i++) {
                        JSONObject m = a.optJSONObject(i);
                        if (m == null) continue;
                        ultimoId = Math.max(ultimoId, m.optLong("id"));
                        burbuja(m.optString("texto"), m.optBoolean("propio"), m.optString("de"));
                    }
                    sv.post(() -> sv.fullScroll(android.view.View.FOCUS_DOWN));
                });
            } catch (Exception ignored) { }
        });
    }

    private void enviar() {
        final String texto = entrada.getText().toString().trim();
        if (texto.isEmpty()) return;
        final String auth = SupabaseClient.get(this).authHeader();
        if (auth == null) return;
        if (pendienteId == null) pendienteId = UUID.randomUUID().toString();
        final String cmid = pendienteId;
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("texto", texto);
                body.put("clientMsgId", cmid);
                retrofit2.Response<okhttp3.ResponseBody> r = SupabaseClient.get(this).api().postMessage(auth, jobId, body).execute();
                if (r.isSuccessful()) {
                    runOnUiThread(() -> { entrada.setText(""); pendienteId = null; cargar(); });
                }
            } catch (Exception ignored) { }   // sin red: el mismo clientMsgId se reintenta al pulsar otra vez
        });
    }

    private void burbuja(String texto, boolean propio, String de) {
        TextView v = t(texto, 15, propio ? 0xFFFFFFFF : 0xFF0A0A0A, false);
        v.setPadding(dp(14), dp(10), dp(14), dp(10));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(propio ? 0xFFE91605 : 0xFFF5F5F7);
        v.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(8);
        lp.gravity = propio ? Gravity.END : Gravity.START;
        lp.leftMargin = propio ? dp(48) : 0;
        lp.rightMargin = propio ? 0 : dp(48);
        burbujas.addView(v, lp);
    }

    private TextView t(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
