package com.lc.technician;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.lc.technician.data.JobRepository;
import com.lc.technician.model.Job;
import com.lc.technician.sync.SupabaseClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/** Mensajes: una conversación por orden con cliente (canal real de lc-api, el mismo que ve el cliente en su app). */
public class MensajesActivity extends AppCompatActivity {

    private LinearLayout lista;
    private final List<Job> jobs = new ArrayList<>();
    private String filtro = "";
    private java.util.Map<Long, String[]> ultimosCache = new java.util.HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(0xFFFFFFFF);
        TextView titulo = t("Mensajes", 28, 0xFF0A0A0A, true);
        titulo.setPadding(dp(20), dp(24), dp(20), dp(12));
        raiz.addView(titulo);
        android.widget.EditText buscar = new android.widget.EditText(this);
        buscar.setHint("Buscar mensajes…");
        buscar.setSingleLine(true);
        buscar.setBackgroundResource(R.drawable.bg_input);
        buscar.setPadding(dp(16), dp(12), dp(16), dp(12));
        buscar.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
            @Override public void afterTextChanged(android.text.Editable e) { filtro = e.toString().trim().toLowerCase(java.util.Locale.ROOT); pintar(ultimosCache); }
        });
        LinearLayout.LayoutParams lpb = new LinearLayout.LayoutParams(-1, dp(48));
        lpb.setMargins(dp(16), 0, dp(16), dp(4));
        raiz.addView(buscar, lpb);
        ScrollView sv = new ScrollView(this);
        lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        lista.setPadding(dp(16), 0, dp(16), dp(16));
        sv.addView(lista);
        raiz.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(raiz);
        InsetsHelper.padTop(titulo);
        BottomNav.attach(this, BottomNav.MENSAJES);
    }

    @Override
    protected void onResume() {
        super.onResume();
        jobs.clear();
        for (Job j : JobRepository.get().getTodayJobs()) {
            if (j.cliente != null && !j.cliente.isEmpty()) jobs.add(j);   // las cerradas ya no guardan datos del cliente
        }
        pintar(new java.util.HashMap<>());
        final String auth = SupabaseClient.get(this).authHeader();
        if (auth == null) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            java.util.Map<Long, String[]> ultimos = new java.util.HashMap<>();
            for (Job j : jobs) {
                try {
                    retrofit2.Response<okhttp3.ResponseBody> r = SupabaseClient.get(this).api().getMessages(auth, j.id, "0").execute();
                    if (!r.isSuccessful() || r.body() == null) continue;
                    JSONArray a = new JSONArray(r.body().string());
                    if (a.length() == 0) continue;
                    JSONObject m = a.getJSONObject(a.length() - 1);
                    ultimos.put(j.id, new String[]{m.optString("texto"), m.optBoolean("propio") ? "1" : "0", String.valueOf(a.length())});
                } catch (Exception ignored) { }
            }
            runOnUiThread(() -> { if (!isFinishing()) pintar(ultimos); });
        });
    }

    private void pintar(java.util.Map<Long, String[]> ultimos) {
        ultimosCache = ultimos;
        lista.removeAllViews();
        if (jobs.isEmpty()) {
            TextView v = t("Cuando tengas órdenes con cliente verás aquí sus conversaciones.", 15, 0xFF62666A, false);
            v.setPadding(dp(4), dp(16), dp(4), 0);
            lista.addView(v);
            return;
        }
        for (final Job j : jobs) {
            if (!filtro.isEmpty() && !(j.cliente + " " + j.descripcion).toLowerCase(java.util.Locale.ROOT).contains(filtro)) continue;
            LinearLayout fila = new LinearLayout(this);
            fila.setOrientation(LinearLayout.HORIZONTAL);
            fila.setGravity(Gravity.CENTER_VERTICAL);
            fila.setBackgroundResource(R.drawable.bg_card);
            fila.setPadding(dp(14), dp(12), dp(14), dp(12));
            TextView av = t(j.cliente.substring(0, 1).toUpperCase(), 18, 0xFFFFFFFF, true);
            av.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable c = new android.graphics.drawable.GradientDrawable();
            c.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            c.setColor(0xFF62666A);
            av.setBackground(c);
            fila.addView(av, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(dp(12), 0, 0, 0);
            col.addView(t(j.cliente, 16, 0xFF0A0A0A, true));
            String[] u = ultimos.get(j.id);
            TextView sub = t(u == null ? "Orden #" + j.id + " · " + (j.descripcion == null ? "" : j.descripcion)
                    : (u[1].equals("1") ? "Tú: " : "") + u[0], 13, 0xFF62666A, false);
            sub.setMaxLines(1);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(sub);
            fila.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            if (u != null) {
                TextView n = t(u[2], 12, 0xFFFFFFFF, true);
                n.setGravity(Gravity.CENTER);
                n.setPadding(dp(8), dp(2), dp(8), dp(2));
                n.setBackgroundResource(R.drawable.bg_chip_active);
                n.getBackground().mutate().setTint(0xFFE91605);
                fila.addView(n);
            }
            fila.setOnClickListener(v -> startActivity(new Intent(this, ChatActivity.class)
                    .putExtra("job", j.id).putExtra("cliente", j.cliente)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(10);
            lista.addView(fila, lp);
        }
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
