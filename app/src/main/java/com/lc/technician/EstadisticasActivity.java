package com.lc.technician;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.lc.technician.data.JobRepository;
import com.lc.technician.model.Job;
import com.lc.technician.model.SignatureEntity;
import com.lc.technician.model.TimeStats;
import com.lc.technician.model.WorkItemEntity;
import com.lc.technician.ui.BarChartView;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class EstadisticasActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_estadisticas);
        simplificarVista();
        BottomNav.attach(this, BottomNav.ESTADISTICAS);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        new Thread(() -> {
            final Datos d = calcular();
            runOnUiThread(() -> renderizar(d));
        }).start();

        findViewById(R.id.btnExportarCSV).setOnClickListener(v -> exportarCSV());
    }

    // ── Cálculo en background ─────────────────────────────────────────────────

    private Datos calcular() {
        Datos d = new Datos();
        List<Job> jobs = JobRepository.get().getTodayJobs();

        Calendar ahora = Calendar.getInstance();
        int hoyAnio  = ahora.get(Calendar.YEAR);
        int hoyDia   = ahora.get(Calendar.DAY_OF_YEAR);
        int hoySemana = ahora.get(Calendar.WEEK_OF_YEAR);
        int hoyMes   = ahora.get(Calendar.MONTH);

        // SECCIÓN 1 — contadores
        for (Job j : jobs) {
            if (j.updatedAt != null) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(j.updatedAt);
                if (c.get(Calendar.YEAR) == hoyAnio) {
                    if (c.get(Calendar.DAY_OF_YEAR) == hoyDia)    d.jobsHoy++;
                    if (c.get(Calendar.WEEK_OF_YEAR) == hoySemana) d.jobsSemana++;
                    if (c.get(Calendar.MONTH) == hoyMes)           d.jobsMes++;
                }
            }
            if (Job.STATUS_DONE.equals(j.estado)) d.jobsCompletados++;
        }

        // SECCIÓN 2 — ingresos
        for (Job j : jobs) {
            SignatureEntity firma = JobRepository.get().getSignatureForJob(j.id);
            if (firma != null) {
                d.ingresosTotales += firma.totalAceptado;
                d.trabajosFirmados++;
            }
        }
        d.promedio = d.trabajosFirmados > 0 ? d.ingresosTotales / d.trabajosFirmados : 0;

        // SECCIÓN 3 — por estado
        String[] estados = {
            Job.STATUS_PENDING, Job.STATUS_EN_ROUTE, Job.STATUS_ARRIVED,
            Job.STATUS_AWAITING_APPROVAL, Job.STATUS_EN_REPARACION,
            Job.STATUS_PART_HOLD, Job.STATUS_ON_HOLD, Job.STATUS_DONE
        };
        d.conteoEstados = new int[estados.length];
        d.nombresEstados = new String[]{
            "Pendiente", "En ruta", "Llegué", "Aprobación",
            "Reparación", "Part Hold", "Pausado", "Completado"
        };
        d.coloresEstados = new int[]{
            0xFF62666A, 0xFF171717, 0xFF171717, 0xFF171717,
            0xFFE91605, 0xFF62666A, 0xFF62666A, 0xFF137333
        };
        for (Job j : jobs) {
            for (int i = 0; i < estados.length; i++) {
                if (estados[i].equals(j.estado)) { d.conteoEstados[i]++; break; }
            }
        }

        // SECCIÓN 4 — últimos 7 días
        d.valoresDias = new int[7];
        String[] diasLabels = {"", "", "", "", "", "", ""};
        for (int i = 0; i < 7; i++) {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, -(6 - i));
            int dow = c.get(Calendar.DAY_OF_WEEK); // 1=Dom…7=Sab
            String[] nombres = {"D", "L", "M", "M", "J", "V", "S"};
            diasLabels[i] = nombres[dow - 1];
        }
        d.labelsDias = diasLabels;
        for (Job j : jobs) {
            if (j.updatedAt == null) continue;
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(j.updatedAt);
            long diffMs = ahora.getTimeInMillis() - c.getTimeInMillis();
            long diffDays = diffMs / (1000L * 60 * 60 * 24);
            if (diffDays >= 0 && diffDays < 7) {
                d.valoresDias[6 - (int) diffDays]++;
            }
        }

        // SECCIÓN 5 — tiempos promedio
        long sumaViaje = 0, sumaSitio = 0, sumaTotal = 0;
        int countTiempos = 0;
        for (Job j : jobs) {
            TimeStats ts = JobRepository.get().calcularTiempos(j.id);
            if (ts.tiempoTotalMinutos >= 0) {
                if (ts.tiempoViajeMinutos >= 0)   sumaViaje += ts.tiempoViajeMinutos;
                if (ts.tiempoEnSitioMinutos >= 0) sumaSitio += ts.tiempoEnSitioMinutos;
                sumaTotal += ts.tiempoTotalMinutos;
                countTiempos++;
            }
        }
        d.promViaje   = countTiempos > 0 ? sumaViaje / countTiempos : -1;
        d.promSitio   = countTiempos > 0 ? sumaSitio / countTiempos : -1;
        d.promTotal   = countTiempos > 0 ? sumaTotal / countTiempos : -1;

        // SECCIÓN 6 — top clientes por conteo
        Map<String, Integer> mapa = new HashMap<>();
        for (Job j : jobs) {
            String nombre = (j.cliente != null && !j.cliente.isEmpty()) ? j.cliente : "Sin nombre";
            mapa.put(nombre, mapa.containsKey(nombre) ? mapa.get(nombre) + 1 : 1);
        }
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(mapa.entrySet());
        Collections.sort(sorted, (a, b) -> b.getValue() - a.getValue());
        int top = Math.min(5, sorted.size());
        d.topClientes = new String[top];
        d.topCounts   = new int[top];
        for (int i = 0; i < top; i++) {
            d.topClientes[i] = sorted.get(i).getKey();
            d.topCounts[i]   = sorted.get(i).getValue();
        }

        // SECCIÓN 7 — top clientes por facturación (work items)
        Map<String, Double> mapaMontos = new HashMap<>();
        Map<String, Long>   mapaUltimo = new HashMap<>();
        for (Job j : jobs) {
            if (!Job.STATUS_DONE.equals(j.estado)) continue;
            String nombre = (j.cliente != null && !j.cliente.isEmpty()) ? j.cliente : "Sin nombre";
            List<WorkItemEntity> wi = JobRepository.get().getWorkItemsForJob(j.id);
            double total = 0;
            for (WorkItemEntity w : wi) total += (w.total != null ? w.total : 0.0);
            mapaMontos.put(nombre, (mapaMontos.containsKey(nombre) ? mapaMontos.get(nombre) : 0.0) + total);
            long ts = j.updatedAt != null ? j.updatedAt : 0L;
            if (!mapaUltimo.containsKey(nombre) || ts > mapaUltimo.get(nombre))
                mapaUltimo.put(nombre, ts);
        }
        List<Map.Entry<String, Double>> sortedMontos = new ArrayList<>(mapaMontos.entrySet());
        Collections.sort(sortedMontos, (a, b) -> Double.compare(b.getValue(), a.getValue()));
        int topM = Math.min(5, sortedMontos.size());
        d.topClientesMonto  = new String[topM];
        d.topClientesMontos = new double[topM];
        d.topClientesUltimo = new long[topM];
        for (int i = 0; i < topM; i++) {
            d.topClientesMonto[i]  = sortedMontos.get(i).getKey();
            d.topClientesMontos[i] = sortedMontos.get(i).getValue();
            Long u = mapaUltimo.get(sortedMontos.get(i).getKey());
            d.topClientesUltimo[i] = u != null ? u : 0L;
        }

        // SECCIÓN 8 — ingresos últimas 4 semanas
        d.semanasIngresos = new double[4];
        d.semanasLabels   = new String[4];
        SimpleDateFormat semFmt = new SimpleDateFormat("d MMM", new Locale("es"));
        for (int i = 0; i < 4; i++) {
            Calendar ini = Calendar.getInstance();
            ini.add(Calendar.WEEK_OF_YEAR, -(3 - i));
            ini.set(Calendar.DAY_OF_WEEK, ini.getFirstDayOfWeek());
            ini.set(Calendar.HOUR_OF_DAY, 0); ini.set(Calendar.MINUTE, 0);
            d.semanasLabels[i] = semFmt.format(ini.getTime());
        }
        for (Job j : jobs) {
            if (!Job.STATUS_DONE.equals(j.estado) || j.updatedAt == null) continue;
            Calendar jc = Calendar.getInstance();
            jc.setTimeInMillis(j.updatedAt);
            int semOffset = -1;
            for (int i = 0; i < 4; i++) {
                Calendar ini = Calendar.getInstance();
                ini.add(Calendar.WEEK_OF_YEAR, -(3 - i));
                ini.set(Calendar.DAY_OF_WEEK, ini.getFirstDayOfWeek());
                ini.set(Calendar.HOUR_OF_DAY, 0); ini.set(Calendar.MINUTE, 0);
                Calendar fin = (Calendar) ini.clone();
                fin.add(Calendar.WEEK_OF_YEAR, 1);
                if (!jc.before(ini) && jc.before(fin)) { semOffset = i; break; }
            }
            if (semOffset >= 0) {
                List<WorkItemEntity> wi = JobRepository.get().getWorkItemsForJob(j.id);
                for (WorkItemEntity w : wi) d.semanasIngresos[semOffset] += (w.total != null ? w.total : 0.0);
            }
        }

        // SECCIÓN 9 — equipos más atendidos
        Map<String, Integer> mapaEquipos = new HashMap<>();
        for (Job j : jobs) {
            if (j.equipo == null || j.equipo.isEmpty()) continue;
            String equipo = j.equipo.contains("·") ? j.equipo.split("·")[0].trim() : j.equipo.trim();
            if (equipo.length() > 30) equipo = equipo.substring(0, 30);
            mapaEquipos.put(equipo, (mapaEquipos.containsKey(equipo) ? mapaEquipos.get(equipo) : 0) + 1);
        }
        List<Map.Entry<String, Integer>> sortedEquipos = new ArrayList<>(mapaEquipos.entrySet());
        Collections.sort(sortedEquipos, (a, b) -> b.getValue() - a.getValue());
        int topE = Math.min(5, sortedEquipos.size());
        d.topEquipos       = new String[topE];
        d.topEquiposCounts = new int[topE];
        for (int i = 0; i < topE; i++) {
            d.topEquipos[i]       = sortedEquipos.get(i).getKey();
            d.topEquiposCounts[i] = sortedEquipos.get(i).getValue();
        }

        // SECCIÓN 0 — KPIs Dasher (calculados al final con todos los datos disponibles)
        int totalJobs = jobs.size();
        d.kpiTasaExito = totalJobs > 0
                ? (int) Math.round((d.jobsCompletados * 100.0) / totalJobs) : 0;
        d.kpiTiempo = d.promTotal;
        // BUG-001 FIX: count recurring customers across ALL jobs (not just top-5).
        // A recurring customer = any customer who appears in more than 1 job.
        // Use the same 'mapa' built in SECCIÓN 6 (keyed by cliente name).
        d.kpiRecurrentes = 0;
        for (Map.Entry<String, Integer> entry : mapa.entrySet()) {
            if (entry.getValue() > 1) d.kpiRecurrentes++;
        }
        d.kpiFacturacionMes = 0;
        for (Job j : jobs) {
            if (!Job.STATUS_DONE.equals(j.estado) || j.updatedAt == null) continue;
            Calendar jc = Calendar.getInstance();
            jc.setTimeInMillis(j.updatedAt);
            if (jc.get(Calendar.YEAR) == hoyAnio && jc.get(Calendar.MONTH) == hoyMes) {
                List<WorkItemEntity> wi = JobRepository.get().getWorkItemsForJob(j.id);
                for (WorkItemEntity w : wi)
                    d.kpiFacturacionMes += (w.total != null ? w.total : 0.0);
            }
        }

        return d;
    }

    // ── Render en UI thread ───────────────────────────────────────────────────

    private void renderizar(Datos d) {
        // SECCIÓN 0: KPIs Dasher (FASE 22)
        TextView kpiTasaExitoTv = findViewById(R.id.kpiTasaExito);
        TextView kpiTasaExitoChip = findViewById(R.id.kpiTasaExitoChip);
        TextView kpiTiempoTv = findViewById(R.id.kpiTiempo);
        TextView kpiTiempoChip = findViewById(R.id.kpiTiempoChip);
        TextView kpiRecurrentesTv = findViewById(R.id.kpiRecurrentes);
        TextView kpiRecurrentesChip = findViewById(R.id.kpiRecurrentesChip);
        TextView kpiFacturacionTv = findViewById(R.id.kpiFacturacion);
        TextView kpiFacturacionChip = findViewById(R.id.kpiFacturacionChip);

        if (kpiTasaExitoTv != null) {
            kpiTasaExitoTv.setText(d.kpiTasaExito + "%");
            kpiTasaExitoChip.setText(d.kpiTasaExito >= 80 ? "Alta" : d.kpiTasaExito >= 50 ? "Media" : "Baja");
            kpiTasaExitoChip.setBackgroundColor(d.kpiTasaExito >= 80 ? 0xFF171717 : d.kpiTasaExito >= 50 ? 0xFF62666A : 0xFF62666A);

            kpiTiempoTv.setText(d.kpiTiempo > 0 ? d.kpiTiempo + "m" : "—");
            kpiTiempoChip.setText(d.kpiTiempo > 0 && d.kpiTiempo <= 60 ? "Óptimo" : "Revisar");
            kpiTiempoChip.setBackgroundColor(d.kpiTiempo > 0 && d.kpiTiempo <= 60 ? 0xFF171717 : 0xFF62666A);

            kpiRecurrentesTv.setText(String.valueOf(d.kpiRecurrentes));
            kpiRecurrentesChip.setText(d.kpiRecurrentes >= 5 ? "Alta" : d.kpiRecurrentes >= 2 ? "Media" : "Baja");
            kpiRecurrentesChip.setBackgroundColor(d.kpiRecurrentes >= 5 ? 0xFF171717 : d.kpiRecurrentes >= 2 ? 0xFF62666A : 0xFF62666A);

            kpiFacturacionTv.setText(String.format(java.util.Locale.US, "$%,.0f", d.kpiFacturacionMes));
            kpiFacturacionChip.setText(d.kpiFacturacionMes > 0 ? "Activo" : "Sin datos");
            kpiFacturacionChip.setBackgroundColor(d.kpiFacturacionMes > 0 ? 0xFF171717 : 0xFF9A9A9A);
        }

        // SECCIÓN 1
        ((TextView) findViewById(R.id.statHoy)).setText(String.valueOf(d.jobsHoy));
        ((TextView) findViewById(R.id.statSemana)).setText(String.valueOf(d.jobsSemana));
        ((TextView) findViewById(R.id.statMes)).setText(String.valueOf(d.jobsMes));
        ((TextView) findViewById(R.id.statCompletados)).setText(String.valueOf(d.jobsCompletados));

        // SECCIÓN 2
        ((TextView) findViewById(R.id.tvIngresosTotal))
                .setText(String.format(java.util.Locale.US, "$%,.2f", d.ingresosTotales));
        ((TextView) findViewById(R.id.tvIngresosDetalle))
                .setText(d.trabajosFirmados + " trabajos firmados · Promedio "
                        + String.format(java.util.Locale.US, "$%,.0f", d.promedio));

        // SECCIÓN 3
        LinearLayout layoutEstados = findViewById(R.id.layoutEstados);
        layoutEstados.removeAllViews();
        int maxCount = 0;
        for (int c : d.conteoEstados) if (c > maxCount) maxCount = c;
        if (maxCount == 0) maxCount = 1;
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < d.nombresEstados.length; i++) {
            View row = getLayoutInflater().inflate(
                    android.R.layout.simple_list_item_1, layoutEstados, false);
            // Build row manually
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            rowLayout.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = (int) (8 * density);
            rowLayout.setLayoutParams(rowParams);

            // Nombre del estado
            TextView tvNombre = new TextView(this);
            tvNombre.setText(d.nombresEstados[i]);
            tvNombre.setTextSize(12f);
            tvNombre.setTextColor(0xFF555555);
            LinearLayout.LayoutParams npParams = new LinearLayout.LayoutParams(
                    (int) (90 * density), LinearLayout.LayoutParams.WRAP_CONTENT);
            tvNombre.setLayoutParams(npParams);

            // Barra de color
            View barra = new View(this);
            float barraFrac = d.conteoEstados[i] / (float) maxCount;
            int barraMaxW = (int) (150 * density);
            int barraW = Math.max((int) (barraFrac * barraMaxW), d.conteoEstados[i] > 0 ? (int)(4*density) : 0);
            GradientDrawable barBg = new GradientDrawable();
            barBg.setColor(d.coloresEstados[i]);
            barBg.setCornerRadius(3 * density);
            barra.setBackground(barBg);
            LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                    barraW, (int) (16 * density));
            barParams.leftMargin = (int) (8 * density);
            barra.setLayoutParams(barParams);

            // Cantidad
            TextView tvCantidad = new TextView(this);
            tvCantidad.setText(String.valueOf(d.conteoEstados[i]));
            tvCantidad.setTextSize(12f);
            tvCantidad.setTextColor(0xFF333333);
            tvCantidad.setTypeface(null, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams cParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            cParams.leftMargin = (int) (8 * density);
            tvCantidad.setLayoutParams(cParams);

            rowLayout.addView(tvNombre);
            rowLayout.addView(barra);
            rowLayout.addView(tvCantidad);
            layoutEstados.addView(rowLayout);
        }

        // SECCIÓN 4
        BarChartView barChart = findViewById(R.id.barChart);
        barChart.setData(d.valoresDias, d.labelsDias);

        // SECCIÓN 5
        ((TextView) findViewById(R.id.tvPromViaje)).setText(fmtDur(d.promViaje));
        ((TextView) findViewById(R.id.tvPromSitio)).setText(fmtDur(d.promSitio));
        ((TextView) findViewById(R.id.tvPromTotal)).setText(fmtDur(d.promTotal));

        // SECCIÓN 6
        LinearLayout layoutTop = findViewById(R.id.layoutTopClientes);
        layoutTop.removeAllViews();
        if (d.topClientes == null || d.topClientes.length == 0) {
            TextView tv = new TextView(this);
            tv.setText("Sin datos");
            tv.setTextColor(0xFF999999);
            tv.setTextSize(13f);
            layoutTop.addView(tv);
        } else {
            for (int i = 0; i < d.topClientes.length; i++) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = (int) (10 * density);
                row.setLayoutParams(rp);

                TextView tvRank = new TextView(this);
                tvRank.setText((i + 1) + ". ");
                tvRank.setTextColor(0xFF999999);
                tvRank.setTextSize(13f);

                TextView tvName = new TextView(this);
                tvName.setText(d.topClientes[i]);
                tvName.setTextColor(0xFF222222);
                tvName.setTextSize(13f);
                LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tvName.setLayoutParams(np);

                TextView tvCount = new TextView(this);
                tvCount.setText(d.topCounts[i] + " trabajo" + (d.topCounts[i] != 1 ? "s" : ""));
                tvCount.setTextColor(0xFF999999);
                tvCount.setTextSize(12f);

                row.addView(tvRank);
                row.addView(tvName);
                row.addView(tvCount);
                layoutTop.addView(row);
            }
        }

        // SECCIÓN 7 — top clientes por facturación
        LinearLayout layoutTopMonto = findViewById(R.id.layoutTopClientesMonto);
        layoutTopMonto.removeAllViews();
        if (d.topClientesMonto == null || d.topClientesMonto.length == 0) {
            TextView tv = new TextView(this);
            tv.setText("Sin datos de facturación");
            tv.setTextColor(0xFF999999); tv.setTextSize(13f);
            layoutTopMonto.addView(tv);
        } else {
            SimpleDateFormat sdf = new SimpleDateFormat("d MMM yyyy", new Locale("es"));
            for (int i = 0; i < d.topClientesMonto.length; i++) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rp.bottomMargin = dp(12);
                row.setLayoutParams(rp);

                LinearLayout top = new LinearLayout(this);
                top.setOrientation(LinearLayout.HORIZONTAL);
                top.setGravity(Gravity.CENTER_VERTICAL);

                TextView tvN = new TextView(this);
                tvN.setText((i+1) + ". " + d.topClientesMonto[i]);
                tvN.setTextColor(0xFF191919); tvN.setTextSize(14f);
                tvN.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tvN.setLayoutParams(wp);

                TextView tvM = new TextView(this);
                tvM.setText(String.format(Locale.US, "$%,.0f", d.topClientesMontos[i]));
                tvM.setTextColor(0xFF171717); tvM.setTextSize(15f);
                tvM.setTypeface(null, android.graphics.Typeface.BOLD);

                top.addView(tvN); top.addView(tvM);

                TextView tvU = new TextView(this);
                String ultimoStr = d.topClientesUltimo[i] > 0 ? "Último servicio: " + sdf.format(new Date(d.topClientesUltimo[i])) : "";
                tvU.setText(ultimoStr);
                tvU.setTextColor(0xFF9A9A9A); tvU.setTextSize(11f);
                LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                up.topMargin = dp(2);
                tvU.setLayoutParams(up);

                row.addView(top); row.addView(tvU);
                layoutTopMonto.addView(row);

                if (i < d.topClientesMonto.length - 1) {
                    View div = new View(this);
                    div.setBackgroundColor(0xFFEEEEEE);
                    LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
                    dlp.bottomMargin = dp(12);
                    layoutTopMonto.addView(div, dlp);
                }
            }
        }

        // SECCIÓN 8 — ingresos últimas 4 semanas
        LinearLayout layoutSemanas = findViewById(R.id.layoutSemanas);
        layoutSemanas.removeAllViews();
        double maxSem = 0;
        for (double v : d.semanasIngresos) if (v > maxSem) maxSem = v;
        if (maxSem == 0) {
            TextView tvNoSem = new TextView(this);
            tvNoSem.setText("Sin ingresos registrados en las últimas 4 semanas");
            tvNoSem.setTextColor(0xFF9A9A9A); tvNoSem.setTextSize(13f);
            layoutSemanas.addView(tvNoSem);
        } else {
            for (int i = 0; i < 4; i++) {
                LinearLayout rowS = new LinearLayout(this);
                rowS.setOrientation(LinearLayout.HORIZONTAL);
                rowS.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rsp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rsp.bottomMargin = dp(10);
                rowS.setLayoutParams(rsp);

                TextView tvLbl = new TextView(this);
                tvLbl.setText(d.semanasLabels[i]);
                tvLbl.setTextSize(12f); tvLbl.setTextColor(0xFF767676);
                LinearLayout.LayoutParams lblP = new LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.WRAP_CONTENT);
                tvLbl.setLayoutParams(lblP);

                View bar = new View(this);
                GradientDrawable bd = new GradientDrawable();
                bd.setColor(0xFFE91605); bd.setCornerRadius(dp(4));
                bar.setBackground(bd);
                int barW = d.semanasIngresos[i] > 0 ? (int)((d.semanasIngresos[i] / maxSem) * dp(160)) : 0;
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(Math.max(barW, dp(4)), dp(18));
                bp.setMarginStart(dp(8));
                bar.setLayoutParams(bp);

                TextView tvVal = new TextView(this);
                tvVal.setText(d.semanasIngresos[i] > 0 ? String.format(Locale.US, "$%,.0f", d.semanasIngresos[i]) : "$0");
                tvVal.setTextSize(12f); tvVal.setTextColor(0xFF191919);
                tvVal.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                vp.setMarginStart(dp(8));
                tvVal.setLayoutParams(vp);

                rowS.addView(tvLbl); rowS.addView(bar); rowS.addView(tvVal);
                layoutSemanas.addView(rowS);
            }
        }

        // SECCIÓN 9 — equipos más atendidos
        LinearLayout layoutEquipos = findViewById(R.id.layoutTopEquipos);
        layoutEquipos.removeAllViews();
        if (d.topEquipos == null || d.topEquipos.length == 0) {
            TextView tvNoEq = new TextView(this);
            tvNoEq.setText("Sin datos de equipos");
            tvNoEq.setTextColor(0xFF999999); tvNoEq.setTextSize(13f);
            layoutEquipos.addView(tvNoEq);
        } else {
            int maxEq = 0;
            for (int c : d.topEquiposCounts) if (c > maxEq) maxEq = c;
            if (maxEq == 0) maxEq = 1;
            for (int i = 0; i < d.topEquipos.length; i++) {
                LinearLayout rowE = new LinearLayout(this);
                rowE.setOrientation(LinearLayout.HORIZONTAL);
                rowE.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams rep = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rep.bottomMargin = dp(10);
                rowE.setLayoutParams(rep);

                TextView tvELbl = new TextView(this);
                tvELbl.setText((i+1) + ". " + d.topEquipos[i]);
                tvELbl.setTextSize(13f); tvELbl.setTextColor(0xFF191919);
                LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tvELbl.setLayoutParams(elp);

                View barE = new View(this);
                GradientDrawable bde = new GradientDrawable();
                bde.setColor(0xFF171717); bde.setCornerRadius(dp(3));
                barE.setBackground(bde);
                int barEW = (int)((d.topEquiposCounts[i] / (float) maxEq) * dp(80));
                LinearLayout.LayoutParams bep = new LinearLayout.LayoutParams(Math.max(barEW, dp(4)), dp(14));
                bep.setMarginStart(dp(8));
                barE.setLayoutParams(bep);

                TextView tvEC = new TextView(this);
                tvEC.setText(String.valueOf(d.topEquiposCounts[i]));
                tvEC.setTextSize(13f); tvEC.setTextColor(0xFF333333);
                tvEC.setTypeface(null, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams ecp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                ecp.setMarginStart(dp(8));
                tvEC.setLayoutParams(ecp);

                rowE.addView(tvELbl); rowE.addView(barE); rowE.addView(tvEC);
                layoutEquipos.addView(rowE);
            }
        }
    }

    private void exportarCSV() {
        Toast.makeText(this, "Generando CSV…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                List<Job> jobs = JobRepository.get().getTodayJobs();
                Map<String, Double> montoMap  = new HashMap<>();
                Map<String, Integer> countMap = new HashMap<>();
                Map<String, Long> ultimoMap   = new HashMap<>();
                for (Job j : jobs) {
                    if (!Job.STATUS_DONE.equals(j.estado)) continue;
                    String nombre = (j.cliente != null && !j.cliente.isEmpty()) ? j.cliente : "Sin nombre";
                    List<WorkItemEntity> wi = JobRepository.get().getWorkItemsForJob(j.id);
                    double total = 0;
                    for (WorkItemEntity w : wi) total += (w.total != null ? w.total : 0.0);
                    montoMap.put(nombre,  (montoMap.containsKey(nombre)  ? montoMap.get(nombre)  : 0.0) + total);
                    countMap.put(nombre,  (countMap.containsKey(nombre)  ? countMap.get(nombre)  : 0)   + 1);
                    long ts = j.updatedAt != null ? j.updatedAt : 0L;
                    if (!ultimoMap.containsKey(nombre) || ts > ultimoMap.get(nombre)) ultimoMap.put(nombre, ts);
                }

                List<String> clientes = new ArrayList<>(montoMap.keySet());
                Collections.sort(clientes, (a, b) -> Double.compare(montoMap.getOrDefault(b, 0.0), montoMap.getOrDefault(a, 0.0)));

                File dir = new File(getExternalFilesDir(null), "reportes");
                dir.mkdirs();
                String fecha = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
                File csv = new File(dir, "lc_estadisticas_" + fecha + ".csv");

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                FileWriter fw = new FileWriter(csv);
                fw.write("Cliente,Jobs Completados,Total Facturado,Ultimo Servicio\n");
                for (String c : clientes) {
                    double monto = montoMap.getOrDefault(c, 0.0);
                    int cnt = countMap.getOrDefault(c, 0);
                    long ult = ultimoMap.getOrDefault(c, 0L);
                    String ultStr = ult > 0 ? sdf.format(new Date(ult)) : "";
                    String line = "\"" + c.replace("\"", "\"\"") + "\","
                            + cnt + ","
                            + String.format(Locale.US, "%.2f", monto) + ","
                            + ultStr + "\n";
                    fw.write(line);
                }
                fw.close();

                runOnUiThread(() -> {
                    Toast.makeText(this, "CSV guardado: " + csv.getName(), Toast.LENGTH_SHORT).show();
                    try {
                        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", csv);
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("text/csv");
                        share.putExtra(Intent.EXTRA_STREAM, uri);
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(share, "Exportar estadísticas"));
                    } catch (Exception e) {
                        Toast.makeText(this, "Guardado: " + csv.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String fmtDur(long minutes) {
        if (minutes < 0) return "—";
        if (minutes < 60) return minutes + " min";
        long h = minutes / 60;
        long m = minutes % 60;
        return m == 0 ? (h + "h") : (h + "h " + m + "min");
    }

    // ── DTO de datos calculados ───────────────────────────────────────────────

    private static class Datos {
        int jobsHoy, jobsSemana, jobsMes, jobsCompletados;
        double ingresosTotales, promedio;
        int trabajosFirmados;
        int[] conteoEstados;
        String[] nombresEstados;
        int[] coloresEstados;
        int[] valoresDias;
        String[] labelsDias;
        long promViaje = -1, promSitio = -1, promTotal = -1;
        String[] topClientes;
        int[] topCounts;
        // FASE 8 additions
        String[] topClientesMonto;
        double[] topClientesMontos;
        long[]   topClientesUltimo;
        double[] semanasIngresos;
        String[] semanasLabels;
        String[] topEquipos;
        int[]    topEquiposCounts;
        // FASE 22 - KPIs Dasher
        int kpiTasaExito;
        long kpiTiempo;
        int kpiRecurrentes;
        double kpiFacturacionMes;
    }

    /** Vista inicial corta: KPIs, resumen y gráfica de 7 días; el resto (ingresos, tiempos, tops, CSV) se abre con «Ver detalle completo». */
    private void simplificarVista() {
        android.widget.ScrollView sv = encontrarScroll(findViewById(android.R.id.content));
        if (sv == null || !(sv.getChildAt(0) instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout col = (android.widget.LinearLayout) sv.getChildAt(0);
        java.util.Set<String> detalle = new java.util.HashSet<>(java.util.Arrays.asList(
                "INGRESOS ESTIMADOS", "TRABAJOS POR ESTADO", "TIEMPOS PROMEDIO", "TOP CLIENTES",
                "TOP CLIENTES POR FACTURACIÓN", "INGRESOS ÚLTIMAS 4 SEMANAS", "EQUIPOS MÁS ATENDIDOS"));
        final java.util.List<android.view.View> ocultos = new java.util.ArrayList<>();
        boolean enDetalle = false;
        int primero = -1;
        for (int i = 0; i < col.getChildCount(); i++) {
            android.view.View v = col.getChildAt(i);
            if (v instanceof android.widget.TextView && v.getId() == android.view.View.NO_ID) {
                String t = ((android.widget.TextView) v).getText().toString();
                if (detalle.contains(t)) enDetalle = true;
                else if (t.equals(t.toUpperCase(java.util.Locale.ROOT)) && t.length() > 8) enDetalle = false;
            }
            if (enDetalle || v.getId() == R.id.btnExportarCSV) {
                if (primero < 0) primero = i;
                ocultos.add(v);
                v.setVisibility(android.view.View.GONE);
            }
        }
        if (primero < 0) return;
        final android.widget.TextView btn = new android.widget.TextView(this);
        btn.setText("Ver detalle completo  ▾");
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setTextColor(0xFFE91605);
        btn.setTextSize(14f);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        btn.setPadding(pad, pad, pad, pad);
        btn.setOnClickListener(v -> {
            boolean abrir = ocultos.get(0).getVisibility() != android.view.View.VISIBLE;
            for (android.view.View o : ocultos) o.setVisibility(abrir ? android.view.View.VISIBLE : android.view.View.GONE);
            btn.setText(abrir ? "Ocultar detalle  ▴" : "Ver detalle completo  ▾");
        });
        col.addView(btn, primero);
    }

    private android.widget.ScrollView encontrarScroll(android.view.View v) {
        if (v instanceof android.widget.ScrollView) return (android.widget.ScrollView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.ScrollView r = encontrarScroll(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }
}
