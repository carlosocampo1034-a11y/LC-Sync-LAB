package com.lc.technician;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.lc.technician.data.ConfigManager;
import com.lc.technician.data.JobRepository;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.CoverageTypeEntity;
import com.lc.technician.model.Job;
import com.lc.technician.model.ProposalEntity;
import com.lc.technician.model.ProposalOptionEntity;
import com.lc.technician.util.CoverageCalculator;
import java.util.*;

public class ProposalActivity extends AppCompatActivity {

    public static final String EXTRA_JOB_ID = "job_id";

    private long jobId;
    private ProposalEntity proposal;

    // Views for each option card (indexed 0=GOOD, 1=BETTER, 2=BEST)
    private static final String[] NIVELES  = {"GOOD", "BETTER", "BEST"};
    /** Sin título inventado: el técnico escribe la solución real (el servidor exige título si hay importe). */
    private static final String[] DEFAULTS = {"", "", ""};
    /** Número que ven el cliente, despacho y el técnico: fijo por casilla (GOOD=1, BETTER=2, BEST=3). */
    static String etiqueta(int i) { return "OPCIÓN " + (i + 1); }
    // Loaded from ConfigManager in onCreate — allows user to customize defaults
    private double[] defMarkup = {
        ConfigManager.DEFAULT_MARKUP_GOOD,
        ConfigManager.DEFAULT_MARKUP_BETTER,
        ConfigManager.DEFAULT_MARKUP_BEST
    };
    private double defTax = ConfigManager.DEFAULT_TAX_RATE;
    private static final int[] CHIP_COLORS = {0xFF137333, 0xFF1B71C9, 0xFFFF8F00};

    // FASE 16: Coverage breakdown views and loaded coverage type
    private final android.widget.LinearLayout[] layoutCoverageBreakdown = new android.widget.LinearLayout[3];
    private final TextView[] tvCovTotalTrabajo = new TextView[3];
    private final TextView[] tvCovDeducible    = new TextView[3];
    private final TextView[] tvCovInsurer      = new TextView[3];
    private final TextView[] tvCovClientePaga  = new TextView[3];
    private CoverageTypeEntity coverageType = null; // loaded after job is known

    private final EditText[] etTitulo      = new EditText[3];
    private final EditText[] etDescripcion = new EditText[3];
    private final EditText[] etEquipo      = new EditText[3];
    private final EditText[] etManoObra    = new EditText[3];
    private final EditText[] etMateriales  = new EditText[3];
    private final EditText[] etPermiso     = new EditText[3];
    private final EditText[] etRemocion    = new EditText[3];
    private final EditText[] etAccesorios  = new EditText[3];
    private final EditText[] etMarkup      = new EditText[3];
    private final EditText[] etDescuento   = new EditText[3];
    private final EditText[] etImpuesto    = new EditText[3];
    private final TextView[] tvTotal       = new TextView[3];
    private final TextView[] tvAceptadaChip = new TextView[3];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_proposal);
        // Edge-to-edge (targetSdk 36): cabecera bajo la barra de estado y botones sobre la de navegación
        InsetsHelper.padTop(findViewById(R.id.header));
        InsetsHelper.padBottom(findViewById(R.id.bottomBar));

        jobId = getIntent().getLongExtra(EXTRA_JOB_ID, -1);
        if (jobId < 0) { finish(); return; }

        // Load markup/tax defaults from ConfigManager (user-customizable)
        ConfigManager config = new ConfigManager(this);
        defMarkup = config.getMarkups();
        defTax    = config.getTaxRate();

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        // Bind card views first (before async load)
        int[] cardIds = {R.id.cardGood, R.id.cardBetter, R.id.cardBest};
        for (int i = 0; i < 3; i++) {
            View card = findViewById(cardIds[i]);
            etTitulo[i]       = card.findViewById(R.id.etTitulo);
            etTitulo[i].setHint("Título de la solución (ej. Cambiar capacitor)");
            etDescripcion[i]  = card.findViewById(R.id.etDescripcion);
            etEquipo[i]       = card.findViewById(R.id.etEquipo);
            etManoObra[i]     = card.findViewById(R.id.etManoObra);
            etMateriales[i]   = card.findViewById(R.id.etMateriales);
            etPermiso[i]      = card.findViewById(R.id.etPermiso);
            etRemocion[i]     = card.findViewById(R.id.etRemocion);
            etAccesorios[i]   = card.findViewById(R.id.etAccesorios);
            etMarkup[i]       = card.findViewById(R.id.etMarkup);
            etDescuento[i]    = card.findViewById(R.id.etDescuento);
            etImpuesto[i]     = card.findViewById(R.id.etImpuesto);
            tvTotal[i]        = card.findViewById(R.id.tvTotal);
            tvAceptadaChip[i] = card.findViewById(R.id.tvAceptadaChip);
            // FASE 16: coverage breakdown
            layoutCoverageBreakdown[i] = card.findViewById(R.id.layoutCoverageBreakdown);
            tvCovTotalTrabajo[i] = card.findViewById(R.id.tvCovTotalTrabajo);
            tvCovDeducible[i]    = card.findViewById(R.id.tvCovDeducible);
            tvCovInsurer[i]      = card.findViewById(R.id.tvCovInsurer);
            tvCovClientePaga[i]  = card.findViewById(R.id.tvCovClientePaga);

            TextView chip = card.findViewById(R.id.tvNivelChip);
            chip.setText(etiqueta(i));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dpToPx(12));
            bg.setColor(CHIP_COLORS[i]);
            chip.setBackground(bg);

            android.graphics.drawable.GradientDrawable abg = new android.graphics.drawable.GradientDrawable();
            abg.setCornerRadius(dpToPx(10));
            abg.setColor(0xFF137333);
            tvAceptadaChip[i].setBackground(abg);

            final int idx = i;
            TextWatcher tw = new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                public void onTextChanged(CharSequence s, int st, int b, int c) {}
                public void afterTextChanged(Editable s) { recalcular(idx); }
            };
            etEquipo[i].addTextChangedListener(tw);
            etManoObra[i].addTextChangedListener(tw);
            etMateriales[i].addTextChangedListener(tw);
            etPermiso[i].addTextChangedListener(tw);
            etRemocion[i].addTextChangedListener(tw);
            etAccesorios[i].addTextChangedListener(tw);
            etMarkup[i].addTextChangedListener(tw);
            etDescuento[i].addTextChangedListener(tw);
            etImpuesto[i].addTextChangedListener(tw);
        }

        findViewById(R.id.btnUsarPlantilla).setOnClickListener(v -> mostrarDialogoPlantillas());

        // Load (or create) proposal asynchronously
        JobRepository.get().loadOrCreateProposalAsync(jobId, (loadedProposal, existing) -> {
            proposal = loadedProposal;

            if (!existing.isEmpty()) {
                Map<String, ProposalOptionEntity> map = new HashMap<>();
                for (ProposalOptionEntity o : existing) map.put(o.nivel, o);
                for (int i = 0; i < 3; i++) {
                    ProposalOptionEntity o = map.get(NIVELES[i]);
                    if (o != null) populateCard(i, o);
                    else           populateDefaults(i);
                }
            } else {
                for (int i = 0; i < 3; i++) populateDefaults(i);
            }

            Button btnAceptarGood   = findViewById(R.id.btnAceptarGood);
            Button btnAceptarBetter = findViewById(R.id.btnAceptarBetter);
            Button btnAceptarBest   = findViewById(R.id.btnAceptarBest);

            if (proposal.isLocked) {
                // FASE 14: Proposal is locked — show immutable banner, hide all edit/reopen actions
                findViewById(R.id.tvAceptadaBanner).setVisibility(View.VISIBLE);
                ((android.widget.TextView) findViewById(R.id.tvAceptadaBanner))
                    .setText("PROPUESTA FIRMADA (BLOQUEADA)");
                for (int i = 0; i < 3; i++) {
                    if (NIVELES[i].equals(proposal.opcionAceptada)) {
                        tvAceptadaChip[i].setVisibility(View.VISIBLE);
                    }
                    setCardEnabled(i, false);
                }
                btnAceptarGood.setVisibility(View.GONE);
                btnAceptarBetter.setVisibility(View.GONE);
                btnAceptarBest.setVisibility(View.GONE);
                Button btnGuardar = findViewById(R.id.btnGuardarPropuesta);
                if (proposal.lockedAt > 0) {
                    String fechaLock = new java.text.SimpleDateFormat("d MMM yyyy · h:mm a",
                        java.util.Locale.forLanguageTag("es")).format(new java.util.Date(proposal.lockedAt));
                    btnGuardar.setText("Firmada: " + fechaLock);
                } else {
                    btnGuardar.setText("PROPUESTA FIRMADA");
                }
                btnGuardar.setEnabled(false);
            } else if ("ACCEPTED".equals(proposal.estado) && proposal.opcionAceptada != null) {
                findViewById(R.id.tvAceptadaBanner).setVisibility(View.VISIBLE);
                for (int i = 0; i < 3; i++) {
                    if (NIVELES[i].equals(proposal.opcionAceptada)) {
                        tvAceptadaChip[i].setVisibility(View.VISIBLE);
                    }
                    setCardEnabled(i, false);
                }
                btnAceptarGood.setVisibility(View.GONE);
                btnAceptarBetter.setVisibility(View.GONE);
                btnAceptarBest.setVisibility(View.GONE);
                Button btn = findViewById(R.id.btnGuardarPropuesta);
                btn.setText("REABRIR PROPUESTA");
                btn.setOnClickListener(v -> reabrirPropuesta());
            } else {
                findViewById(R.id.btnGuardarPropuesta).setOnClickListener(v -> guardarPropuesta());
                btnAceptarGood.setOnClickListener(v -> abrirFirma(0));
                btnAceptarBetter.setOnClickListener(v -> abrirFirma(1));
                btnAceptarBest.setOnClickListener(v -> abrirFirma(2));
            }
            // FASE 16: Load coverage type and refresh breakdown
            cargarCoverage();
            configurarModoLaboratorio();
        });
    }

    // ── Fase 6: envío al cliente por el servidor (solo con sesión de laboratorio) ──
    private TextView tvEstadoServidor;
    private Button btnEnviarCliente;
    private final android.os.Handler refresco = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tickEstado = new Runnable() {
        @Override public void run() { pintarEstadoServidor(); refresco.postDelayed(this, 3000); }
    };

    private void configurarModoLaboratorio() {
        if (!com.lc.technician.sync.LabSession.conServidor(this) || proposal == null) return;
        // Aceptación en persona: la firma del cliente en este teléfono viaja al servidor como evidencia
        // (la propuesta se envía antes para que el servidor sepa exactamente qué versión se firmó)
        android.widget.LinearLayout barra = findViewById(R.id.bottomBar);
        if (tvEstadoServidor == null) {
            tvEstadoServidor = new TextView(this);
            tvEstadoServidor.setTextSize(14);
            tvEstadoServidor.setPadding(0, 0, 0, (int) (10 * getResources().getDisplayMetrics().density));
            barra.addView(tvEstadoServidor, 1);
        }
        if (btnEnviarCliente == null && !proposal.isLocked) {
            btnEnviarCliente = new Button(this);
            btnEnviarCliente.setText("ENVIAR AL CLIENTE");
            btnEnviarCliente.setTextColor(0xFFFFFFFF);
            btnEnviarCliente.setBackgroundResource(R.drawable.bg_button_primary);
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (int) (54 * getResources().getDisplayMetrics().density));
            lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
            barra.addView(btnEnviarCliente, lp);
            btnEnviarCliente.setOnClickListener(v -> confirmarEnvio());
        }
        pintarEstadoServidor();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (com.lc.technician.sync.LabSession.conServidor(this)) refresco.post(tickEstado);
    }

    @Override
    protected void onPause() {
        super.onPause();
        refresco.removeCallbacks(tickEstado);
    }

    private void confirmarEnvio() {
        List<ProposalOptionEntity> opciones = new ArrayList<>();
        StringBuilder resumen = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            ProposalOptionEntity o = buildOption(i);
            opciones.add(o);
            if (o.totalCalculado > 0) {
                if (o.titulo == null || o.titulo.trim().isEmpty()) {
                    Toast.makeText(this, etiqueta(i) + " tiene importe pero no título", Toast.LENGTH_LONG).show();
                    return;
                }
                resumen.append(etiqueta(i)).append(" — ").append(o.titulo.trim()).append(": ")
                       .append(String.format(java.util.Locale.US, "$%,.2f", o.totalCalculado)).append("\n");
            }
        }
        if (resumen.length() == 0) {
            Toast.makeText(this, "Ninguna opción tiene importe", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Enviar propuesta al cliente")
                .setMessage(resumen + "\nEl cliente la revisará y firmará en L&C Customer.")
                .setPositiveButton("ENVIAR", (d, w) -> JobRepository.get().enviarPropuestaAsync(proposal, opciones, err -> {
                    if (err != null) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); return; }
                    Toast.makeText(this, "Guardada en el teléfono; se enviará al servidor", Toast.LENGTH_SHORT).show();
                    pintarEstadoServidor();
                }))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void pintarEstadoServidor() {
        JobRepository.get().getProposalForJobAsync(jobId, p -> {
            if (p == null || tvEstadoServidor == null) return;
            proposal.syncState = p.syncState;
            tvEstadoServidor.setText(textoEstado(p));
            tvEstadoServidor.setTextColor("APROBADA".equals(p.serverEstado) && p.syncState == 1 ? 0xFF137333
                    : p.syncState == 3 || "RECHAZADA".equals(p.serverEstado) ? 0xFFC62828 : 0xFF9E9E9E);
            if (p.isLocked && btnEnviarCliente != null) btnEnviarCliente.setVisibility(View.GONE);
        });
    }

    public static String textoEstado(ProposalEntity p) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMM · h:mm a", java.util.Locale.forLanguageTag("es"));
        if (p.syncState == 0 || p.syncState == 2) {
            return "Guardada en el teléfono · pendiente de envío (sin confirmación del servidor)";
        }
        if (p.syncState == 3) return "El servidor rechazó la propuesta: " + p.motivoRechazo;
        if (p.isLocked && !"APROBADA".equals(p.serverEstado)) {
            return "Firmada en persona por el cliente · pendiente de confirmación del servidor";
        }
        if (p.serverEstado == null) return "Aún no enviada al cliente";
        if (p.motivoRechazo != null && "PENDIENTE".equals(p.serverEstado)) {
            return "⚠ " + p.motivoRechazo + " · la propuesta sigue pendiente (rev " + p.serverRevision + ")";
        }
        String rev = " (rev " + p.serverRevision + ")";
        switch (p.serverEstado) {
            case "PENDIENTE":
                return "Enviada al cliente" + rev + " · esperando respuesta"
                        + (p.venceAt != null ? " · vence " + f.format(new java.util.Date(p.venceAt)) : "");
            case "APROBADA":
                return "APROBADA por " + p.firmanteServidor + " · " + p.opcionServidor + " · "
                        + String.format(java.util.Locale.US, "$%,.2f", p.totalServidor == null ? 0 : p.totalServidor)
                        + (p.decididaAt != null ? " · " + f.format(new java.util.Date(p.decididaAt)) : "") + rev;
            case "RECHAZADA":
                return "Rechazada por el cliente" + rev
                        + (p.decididaAt != null ? " · " + f.format(new java.util.Date(p.decididaAt)) : "");
            case "VENCIDA": return "Vencida sin respuesta del cliente" + rev + " · envíala de nuevo";
            case "SUPERADA": return "Reemplazada por una versión más nueva" + rev;
            default: return p.serverEstado + rev;
        }
    }

    private void cargarCoverage() {
        new Thread(() -> {
            Job job = JobRepository.get().getById(jobId);
            CoverageTypeEntity cov = null;
            if (job != null && job.coverageTypeId != null && job.coverageTypeId > 0) {
                cov = LcDatabase.getInstance(this).coverageDao().getById(job.coverageTypeId);
            }
            final CoverageTypeEntity covFinal = cov;
            runOnUiThread(() -> {
                coverageType = covFinal;
                // Refresh all 3 cards with coverage breakdown
                for (int i = 0; i < 3; i++) recalcular(i);
            });
        }).start();
    }

    private void populateCard(int i, ProposalOptionEntity o) {
        etTitulo[i].setText(o.titulo != null ? o.titulo : DEFAULTS[i]);
        etDescripcion[i].setText(o.descripcion != null ? o.descripcion : "");
        etEquipo[i].setText(fmt(o.costoEquipo));
        etManoObra[i].setText(fmt(o.costoManoObra));
        etMateriales[i].setText(fmt(o.costoMateriales));
        etPermiso[i].setText(fmt(o.costoPermiso));
        etRemocion[i].setText(fmt(o.costoRemocion));
        etAccesorios[i].setText(fmt(o.costoAccesorios));
        etMarkup[i].setText(fmt(o.markupPorcentaje));
        etDescuento[i].setText(fmt(o.descuentoMonto));
        etImpuesto[i].setText(fmt(o.impuestoPorcentaje));
        recalcular(i);
    }

    private void populateDefaults(int i) {
        etTitulo[i].setText(DEFAULTS[i]);
        etMarkup[i].setText(fmt(defMarkup[i]));
        etImpuesto[i].setText(fmt(defTax));
        recalcular(i);
    }

    private void recalcular(int i) {
        ProposalOptionEntity tmp = buildOption(i);
        tmp.totalCalculado = tmp.calcularTotal();
        tvTotal[i].setText(String.format(java.util.Locale.US, "$%,.0f", tmp.totalCalculado));
        // FASE 16: Coverage breakdown
        if (layoutCoverageBreakdown[i] != null) {
            CoverageCalculator.Result r = CoverageCalculator.calcular(coverageType, tmp.totalCalculado);
            if (r.hasCoverage) {
                tvCovTotalTrabajo[i].setText("Total trabajo: " + String.format(java.util.Locale.US, "$%,.2f", r.totalOriginal));
                tvCovDeducible[i].setText("Deducible: " + String.format(java.util.Locale.US, "$%,.2f", r.deductible));
                tvCovInsurer[i].setText(String.format(java.util.Locale.US, "Cubierto por seguro (%.0f%%): $%,.2f", r.coveragePct, r.totalInsurer));
                tvCovClientePaga[i].setText("CLIENTE PAGA: " + String.format(java.util.Locale.US, "$%,.2f", r.totalCliente));
                layoutCoverageBreakdown[i].setVisibility(View.VISIBLE);
            } else {
                layoutCoverageBreakdown[i].setVisibility(View.GONE);
            }
        }
    }

    private ProposalOptionEntity buildOption(int i) {
        ProposalOptionEntity o = new ProposalOptionEntity();
        o.proposalId        = proposal != null ? proposal.id : 0;
        o.nivel             = NIVELES[i];
        o.titulo            = etTitulo[i].getText().toString().trim();
        o.descripcion       = etDescripcion[i].getText().toString().trim();
        o.costoEquipo       = parseD(etEquipo[i]);
        o.costoManoObra     = parseD(etManoObra[i]);
        o.costoMateriales   = parseD(etMateriales[i]);
        o.costoPermiso      = parseD(etPermiso[i]);
        o.costoRemocion     = parseD(etRemocion[i]);
        o.costoAccesorios   = parseD(etAccesorios[i]);
        o.markupPorcentaje  = parseD(etMarkup[i]);
        o.descuentoMonto    = parseD(etDescuento[i]);
        o.impuestoPorcentaje = parseD(etImpuesto[i]);
        o.recomendada       = (i == 2); // Best is recommended
        o.totalCalculado    = o.calcularTotal();
        // Snapshot: store the markup/tax used at this moment for audit trail
        o.markupUsed = o.markupPorcentaje;
        o.taxUsed    = o.impuestoPorcentaje;
        return o;
    }

    private void abrirFirma(int idx) {
        if (com.lc.technician.sync.LabSession.conServidor(this) && proposal != null && !proposal.isLocked) {
            ProposalOptionEntity opt = buildOption(idx);
            if (opt.totalCalculado <= 0 || opt.titulo == null || opt.titulo.trim().isEmpty()) {
                Toast.makeText(this, "La opción necesita título e importe", Toast.LENGTH_LONG).show();
                return;
            }
            List<ProposalOptionEntity> opciones = new ArrayList<>();
            for (int i = 0; i < 3; i++) opciones.add(buildOption(i));
            JobRepository.get().enviarPropuestaAsync(proposal, opciones, err -> {
                if (err != null) { Toast.makeText(this, err, Toast.LENGTH_LONG).show(); return; }
                doAbrirFirma(idx, opt);
            });
            return;
        }
        ProposalOptionEntity opt = buildOption(idx);
        if (opt.totalCalculado <= 0) {
            // Require explicit confirmation for $0 proposals
            new AlertDialog.Builder(this)
                .setTitle("Propuesta sin costo")
                .setMessage("El total es $0.00. ¿Confirmar como trabajo sin costo?")
                .setPositiveButton("Confirmar", (d, w) -> doAbrirFirma(idx, opt))
                .setNegativeButton("Cancelar", null)
                .show();
            return;
        }
        doAbrirFirma(idx, opt);
    }

    private void doAbrirFirma(int idx, ProposalOptionEntity opt) {
        Intent intent = new Intent(this, SignatureActivity.class);
        intent.putExtra(SignatureActivity.EXTRA_JOB_ID, jobId);
        intent.putExtra(SignatureActivity.EXTRA_PROPOSAL_ID, proposal != null ? proposal.id : -1L);
        intent.putExtra(SignatureActivity.EXTRA_OPCION, NIVELES[idx]);
        intent.putExtra(SignatureActivity.EXTRA_TOTAL, opt.totalCalculado);
        intent.putExtra(SignatureActivity.EXTRA_TITULO, opt.titulo != null ? opt.titulo : DEFAULTS[idx]);
        startActivity(intent);
    }

    private void guardarPropuesta() {
        if (proposal == null) return;
        List<ProposalOptionEntity> opciones = new ArrayList<>();
        for (int i = 0; i < 3; i++) opciones.add(buildOption(i));
        JobRepository.get().guardarPropuesta(proposal, opciones, () -> {
            Toast.makeText(this, "Propuesta guardada", Toast.LENGTH_SHORT).show();
            finish();
        });
    }

    private void reabrirPropuesta() {
        if (proposal == null) return;
        proposal.estado = "DRAFT";
        proposal.opcionAceptada = null;
        JobRepository.get().guardarPropuesta(proposal, null, () -> {
            Toast.makeText(this, "Propuesta reabierta", Toast.LENGTH_SHORT).show();
            recreate();
        });
    }

    private void setCardEnabled(int i, boolean enabled) {
        etTitulo[i].setEnabled(enabled);
        etDescripcion[i].setEnabled(enabled);
        etEquipo[i].setEnabled(enabled);
        etManoObra[i].setEnabled(enabled);
        etMateriales[i].setEnabled(enabled);
        etPermiso[i].setEnabled(enabled);
        etRemocion[i].setEnabled(enabled);
        etAccesorios[i].setEnabled(enabled);
        etMarkup[i].setEnabled(enabled);
        etDescuento[i].setEnabled(enabled);
        etImpuesto[i].setEnabled(enabled);
    }

    private void mostrarDialogoPlantillas() {
        String[] nombres = {
            "Mantenimiento Preventivo",
            "Reemplazo de Compresor",
            "Limpieza de Ductos"
        };
        new AlertDialog.Builder(this)
                .setTitle("Seleccionar plantilla")
                .setItems(nombres, (d, which) -> aplicarPlantilla(which))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private static final String[][] TMPL_TITULO = {
        {"Mantenimiento Preventivo - Good", "Mantenimiento Preventivo - Better", "Mantenimiento Preventivo - Best"},
        {"Reemplazo de Compresor - Good",   "Reemplazo de Compresor - Better",   "Reemplazo de Compresor - Best"},
        {"Limpieza de Ductos - Good",        "Limpieza de Ductos - Better",        "Limpieza de Ductos - Best"}
    };
    private static final String[][] TMPL_DESC = {
        {
            "Revisión básica de filtros y limpieza superficial de unidad",
            "Inspección completa + limpieza profunda + ajuste de refrigerante",
            "Mantenimiento total + limpieza de bobinas + revisión eléctrica completa"
        },
        {
            "Reemplazo de compresor estándar con garantía básica",
            "Reemplazo de compresor eficiencia media + mano de obra premium",
            "Reemplazo de compresor alta eficiencia + garantía extendida 2 años"
        },
        {
            "Limpieza básica de ductos principales",
            "Limpieza completa de ductos + tratamiento antimicrobial",
            "Limpieza profunda + sellado + tratamiento antimicrobial + certificación"
        }
    };
    // [plantilla][opcion] = {manoObra, materiales}
    private static final double[][] TMPL_MANO = {
        {150, 200, 250},
        {800, 1200, 1800},
        {200, 300, 450}
    };
    private static final double[][] TMPL_MAT = {
        {50, 100, 150},
        {200, 300, 400},
        {0, 50, 100}
    };

    private void aplicarPlantilla(int tipo) {
        for (int i = 0; i < 3; i++) {
            etTitulo[i].setText(TMPL_TITULO[tipo][i]);
            etDescripcion[i].setText(TMPL_DESC[tipo][i]);
            etManoObra[i].setText(fmt(TMPL_MANO[tipo][i]));
            etMateriales[i].setText(fmt(TMPL_MAT[tipo][i]));
            etEquipo[i].setText("0");
            etPermiso[i].setText("0");
            etRemocion[i].setText("0");
            etAccesorios[i].setText("0");
            etMarkup[i].setText(fmt(defMarkup[i]));
            etDescuento[i].setText("0");
            etImpuesto[i].setText(fmt(defTax));
            recalcular(i);
        }
        Toast.makeText(this, "Plantilla aplicada", Toast.LENGTH_SHORT).show();
    }

    private static double parseD(EditText et) {
        try { return Double.parseDouble(et.getText().toString().trim()); }
        catch (NumberFormatException e) { return 0.0; }
    }

    private static String fmt(double v) {
        if (v == 0.0) return "0";
        return v == (long) v ? String.valueOf((long) v) : String.valueOf(v);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
