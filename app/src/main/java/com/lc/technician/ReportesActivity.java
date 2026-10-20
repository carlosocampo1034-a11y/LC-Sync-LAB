package com.lc.technician;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.lc.technician.data.JobRepository;
import com.lc.technician.model.CustomerEntity;
import com.lc.technician.model.JobEntity;
import com.lc.technician.model.MarcaCount;
import java.util.Date;
import java.util.List;

public class ReportesActivity extends AppCompatActivity {

    private LinearLayout llClientes, llMarcas;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reportes);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        llClientes = findViewById(R.id.llClientes);
        llMarcas   = findViewById(R.id.llMarcas);

        findViewById(R.id.btnExportarRepuestos).setOnClickListener(v -> exportarRepuestos());
        findViewById(R.id.btnExportarEquipos).setOnClickListener(v -> exportarEquipos());

        cargarClientes();
        cargarMarcas();
    }

    // ── Historial por cliente ──────────────────────────────────────────────────

    private void cargarClientes() {
        JobRepository.get().getAllCustomersAsync(clientes -> {
            llClientes.removeAllViews();
            if (clientes.isEmpty()) {
                llClientes.addView(makeEmpty("Sin clientes registrados"));
                return;
            }
            for (CustomerEntity c : clientes) {
                llClientes.addView(makeClienteCard(c));
            }
        });
    }

    private View makeClienteCard(CustomerEntity c) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(getDrawable(R.drawable.bg_surface));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(lp);

        TextView tvNombre = new TextView(this);
        tvNombre.setText(c.nombre != null ? c.nombre : "—");
        tvNombre.setTextColor(0xFF191919);
        tvNombre.setTextSize(15);
        tvNombre.setTypeface(null, android.graphics.Typeface.BOLD);

        TextView tvTelefono = new TextView(this);
        tvTelefono.setText(c.telefono != null ? c.telefono : "Sin teléfono");
        tvTelefono.setTextColor(0xFF767676);
        tvTelefono.setTextSize(12);
        tvTelefono.setPadding(0, dp(2), 0, 0);

        card.addView(tvNombre);
        card.addView(tvTelefono);

        card.setOnClickListener(v -> mostrarJobsDeCliente(c));
        return card;
    }

    private void mostrarJobsDeCliente(CustomerEntity c) {
        JobRepository.get().getJobsByCustomerAsync(c.id, jobs -> {
            StringBuilder sb = new StringBuilder();
            if (jobs.isEmpty()) {
                sb.append("Sin trabajos registrados.");
            } else {
                for (JobEntity j : jobs) {
                    String fecha = j.updatedAt != null
                            ? DateFormat.format("d MMM yyyy", new Date(j.updatedAt)).toString()
                            : j.hora != null ? j.hora : "—";
                    String monto = "";
                    sb.append("• #").append(j.id).append("  ").append(fecha)
                      .append("\n  Estado: ").append(j.estado != null ? j.estado : "—")
                      .append("\n  ").append(j.descripcion != null ? j.descripcion : "")
                      .append("\n\n");
                }
            }
            new AlertDialog.Builder(this)
                    .setTitle(c.nombre)
                    .setMessage(sb.toString().trim())
                    .setPositiveButton("Cerrar", null)
                    .show();
        });
    }

    // ── Equipos por marca ──────────────────────────────────────────────────────

    private void cargarMarcas() {
        JobRepository.get().getEquiposPorMarcaAsync(marcas -> {
            llMarcas.removeAllViews();
            if (marcas.isEmpty()) {
                llMarcas.addView(makeEmpty("Sin equipos registrados"));
                return;
            }
            for (MarcaCount mc : marcas) {
                llMarcas.addView(makeMarcaCard(mc));
            }
        });
    }

    private View makeMarcaCard(MarcaCount mc) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(getDrawable(R.drawable.bg_surface));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8));
        card.setLayoutParams(lp);

        TextView tvMarca = new TextView(this);
        tvMarca.setText(mc.marca != null ? mc.marca : "Sin marca");
        tvMarca.setTextColor(0xFF191919);
        tvMarca.setTextSize(14);
        tvMarca.setTypeface(null, android.graphics.Typeface.BOLD);
        tvMarca.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvTotal = new TextView(this);
        tvTotal.setText(mc.total + (mc.total == 1 ? " equipo" : " equipos"));
        tvTotal.setTextColor(0xFFEB1700);
        tvTotal.setTextSize(13);
        tvTotal.setTypeface(null, android.graphics.Typeface.BOLD);

        card.addView(tvMarca);
        card.addView(tvTotal);
        return card;
    }

    // ── Export ────────────────────────────────────────────────────────────────

    private void exportarRepuestos() {
        Toast.makeText(this, "Generando CSV…", Toast.LENGTH_SHORT).show();
        JobRepository.get().exportarRepuestosCSV(this, path -> {
            if (path.startsWith("ERROR")) {
                new AlertDialog.Builder(this)
                        .setTitle("Error al exportar")
                        .setMessage(path)
                        .setPositiveButton("OK", null).show();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("✓ Repuestos exportados")
                        .setMessage("Archivo guardado en:\n" + path)
                        .setPositiveButton("OK", null).show();
            }
        });
    }

    private void exportarEquipos() {
        Toast.makeText(this, "Generando CSV…", Toast.LENGTH_SHORT).show();
        JobRepository.get().exportarEquiposCSV(this, path -> {
            if (path.startsWith("ERROR")) {
                new AlertDialog.Builder(this)
                        .setTitle("Error al exportar")
                        .setMessage(path)
                        .setPositiveButton("OK", null).show();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("✓ Equipos exportados")
                        .setMessage("Archivo guardado en:\n" + path)
                        .setPositiveButton("OK", null).show();
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private View makeEmpty(String msg) {
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextColor(0xFF9A9A9A);
        tv.setTextSize(13);
        tv.setPadding(dp(4), dp(8), dp(4), dp(8));
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
