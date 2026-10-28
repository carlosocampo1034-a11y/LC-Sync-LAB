package com.lc.technician;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.data.JobRepository;
import com.lc.technician.model.PaymentEntity;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

public class PagosActivity extends AppCompatActivity {

    private static final String PREFS_INVOICES = "lc_invoices";
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("es"));

    private final List<PagoItem> pagos = new ArrayList<>();
    private PagosAdapter adapter;
    private TextView tvTotalMes;
    private TextView tvContadorPagos;
    private TextView tvSinPagos;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pagos);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        tvTotalMes      = findViewById(R.id.tvTotalMes);
        tvContadorPagos = findViewById(R.id.tvContadorPagos);
        tvSinPagos      = findViewById(R.id.tvSinPagos);

        RecyclerView recycler = findViewById(R.id.recyclerPagos);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PagosAdapter(pagos);
        recycler.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cargarPagos();
    }

    private void cargarPagos() {
        // BUG-002 FIX: Migrate legacy SharedPreferences payments into Room so they
        // appear in the RecyclerView. Previously, payments recorded before the Room
        // migration (v16→17) lived only in SharedPreferences. We insert them into
        // Room once so getAllPaidPaymentsAsync returns a complete history.
        migrarPagosLegadosARoom();

        pagos.clear();
        // Load from Room (primary storage)
        JobRepository.get().getAllPaidPaymentsAsync(payments -> {
            long ahora = System.currentTimeMillis();
            long inicioMes = inicioDelMes(ahora);
            double totalMes = 0;

            for (PaymentEntity p : payments) {
                if (!"PAID".equals(p.status)) continue;
                PagoItem item = new PagoItem();
                item.jobId   = p.jobId;
                item.cliente = p.cliente != null ? p.cliente : "Cliente";
                item.total   = (float) p.amount;
                item.metodo  = p.method != null ? p.method : "—";
                item.fecha   = p.timestamp;
                pagos.add(item);
                if (p.timestamp >= inicioMes) totalMes += p.amount;
            }

            pagos.sort((a, b) -> Long.compare(b.fecha, a.fecha));
            adapter.notifyDataSetChanged();

            final double tm = totalMes;
            tvTotalMes.setText(String.format(Locale.US, "$%.2f", tm));
            tvContadorPagos.setText(String.valueOf(pagos.size()));
            tvSinPagos.setVisibility(pagos.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    /**
     * One-time migration: reads legacy SharedPreferences payment records and
     * inserts any not yet present in Room. This runs async so it does not block
     * the UI thread. After insertion the next cargarPagos() call (or onResume)
     * will pick up the full history from Room.
     */
    private void migrarPagosLegadosARoom() {
        SharedPreferences prefs = getSharedPreferences(PREFS_INVOICES, MODE_PRIVATE);
        java.util.Set<String> legacyIds =
                new java.util.HashSet<>(prefs.getStringSet("pagos_ids", new java.util.HashSet<>()));
        if (legacyIds.isEmpty()) return;

        Executors.newSingleThreadExecutor().execute(() -> {
            for (String idStr : legacyIds) {
                try {
                    long jobId = Long.parseLong(idStr);
                    boolean pagado = prefs.getBoolean("invoice_" + jobId + "_pagado", false);
                    if (!pagado) continue;
                    // Skip if already in Room (avoid duplicate inserts)
                    com.lc.technician.data.LcDatabase db =
                            com.lc.technician.data.LcDatabase.getInstance(getApplicationContext());
                    PaymentEntity existing = db.paymentDao().getPaidForJob(jobId);
                    if (existing != null) continue;
                    // Insert legacy record into Room
                    float total   = prefs.getFloat("invoice_" + jobId + "_total", 0f);
                    String metodo = prefs.getString("invoice_" + jobId + "_metodo", "Efectivo");
                    long fecha    = prefs.getLong("invoice_" + jobId + "_fecha", System.currentTimeMillis());
                    String cliente = prefs.getString("invoice_" + jobId + "_cliente", "");
                    PaymentEntity p = new PaymentEntity();
                    p.id        = "pay_legacy_" + jobId;
                    p.jobId     = jobId;
                    p.amount    = total;
                    p.method    = metodo;
                    p.status    = "PAID";
                    p.timestamp = fecha;
                    p.cliente   = cliente;
                    p.notes     = "";
                    db.paymentDao().insert(p);
                } catch (Exception ignored) { /* skip malformed entries */ }
            }
        });
    }

    private static long inicioDelMes(long ts) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(ts);
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    // ── Data model ───────────────────────────────────────────────────────────

    static class PagoItem {
        long jobId;
        String cliente;
        float total;
        String metodo;
        long fecha;
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    class PagosAdapter extends RecyclerView.Adapter<PagosAdapter.VH> {

        private final List<PagoItem> data;

        PagosAdapter(List<PagoItem> data) { this.data = data; }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_dispatch, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            PagoItem p = data.get(pos);
            h.tvCliente.setText(p.cliente);
            h.tvHora.setText(p.fecha > 0 ? FMT.format(new Date(p.fecha)) : "—");
            h.tvDireccion.setText("Método: " + p.metodo);
            h.tvEstado.setText(String.format(Locale.US, "$%.2f", p.total));
            h.tvEstado.setTextColor(0xFF171717);
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(PagosActivity.this, InvoiceActivity.class);
                i.putExtra(InvoiceActivity.EXTRA_JOB_ID, p.jobId);
                startActivity(i);
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvCliente, tvHora, tvDireccion, tvEstado;
            VH(View v) {
                super(v);
                tvCliente   = v.findViewById(R.id.tvDispCliente);
                tvHora      = v.findViewById(R.id.tvDispHora);
                tvDireccion = v.findViewById(R.id.tvDispDireccion);
                tvEstado    = v.findViewById(R.id.tvDispEstado);
            }
        }
    }
}
