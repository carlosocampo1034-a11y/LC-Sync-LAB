package com.lc.technician;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.data.JobRepository;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.FieldMeasurementEntity;
import com.lc.technician.model.FindingEntity;
import com.lc.technician.model.InspectionItemEntity;
import com.lc.technician.model.Job;
import com.lc.technician.model.WorkItemEntity;
import java.util.*;
import java.util.concurrent.Executors;

public class InspectionActivity extends AppCompatActivity {

    public static final String EXTRA_JOB_ID = "job_id";

    // Indoor items
    private static final String[] INDOOR_ITEMS = {
        "Thermostat", "Air Handler", "Filters", "Drain", "Cabinet", "Coil", "Airflow"
    };
    // Outdoor items
    private static final String[] OUTDOOR_ITEMS = {
        "Condenser", "Electrical", "Refrigeration"
    };

    // Listas editables desde Control (settings inspection_*.list); el respaldo coincide con el valor inicial de la tabla
    private com.lc.technician.sync.Listas.Lista TIPOS, UNIDADES, RESOLUCIONES;

    private void cargarListas() {
        TIPOS = com.lc.technician.sync.Listas.lista(this, "inspection_types.list", new String[][]{{"VOLTAGE", "Voltaje"}, {"AMPERAGE", "Amperaje"},
                {"TEMPERATURE", "Temperatura"}, {"PRESSURE", "Presi\u00f3n"}, {"SUPERHEAT", "Sobrecalentamiento"}, {"SUPERCOOL", "Subenfriamiento"},
                {"STATIC_PRESSURE", "Presi\u00f3n est\u00e1tica"}}, null);
        UNIDADES = com.lc.technician.sync.Listas.lista(this, "inspection_units.list", new String[][]{{"V", "V"}, {"A", "A"}, {"\u00b0F", "\u00b0F"}, {"PSI", "PSI"}}, null);
        RESOLUCIONES = com.lc.technician.sync.Listas.lista(this, "inspection_resolutions.list", new String[][]{{"Repair", "Reparar"}, {"Replace", "Reemplazar"},
                {"Part Required", "Requiere repuesto"}, {"Order Part", "Pedir repuesto"}, {"Proposal", "Propuesta"}, {"No Fault Found", "Sin falla"}}, "\u2014");
    }

    private long jobId;
    // Maps: itemName -> InspectionItemEntity (in memory)
    private final Map<String, InspectionItemEntity> itemMap = new LinkedHashMap<>();
    // Mediciones already persisted
    private final List<FieldMeasurementEntity> mediciones = new ArrayList<>();
    private MedicionAdapter medicionAdapter;

    // Findings (Phase 8)
    private final List<FindingEntity> hallazgos = new ArrayList<>();
    private LinearLayout layoutHallazgos;

    private final ActivityResultLauncher<Intent> findingLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK) {
                    recargarHallazgos();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.lc.technician.sync.Listas.refrescar(this, true, null);
        cargarListas();
        setContentView(R.layout.activity_inspection);

        jobId = getIntent().getLongExtra(EXTRA_JOB_ID, -1);
        if (jobId < 0) { finish(); return; }

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        // Fix REG-001 pattern: prevent bottom bar from being hidden behind nav bar
        View bottomBar = findViewById(R.id.bottomBar);
        final int origPad = bottomBar.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(bottomBar, (v, insets) -> {
            int nav = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), origPad + nav);
            return insets;
        });

        // Build item map with defaults
        for (String n : INDOOR_ITEMS) {
            InspectionItemEntity e = new InspectionItemEntity();
            e.jobId = jobId; e.area = "INDOOR"; e.nombre = n; e.estado = "NOT_TESTED";
            itemMap.put(n, e);
        }
        for (String n : OUTDOOR_ITEMS) {
            InspectionItemEntity e = new InspectionItemEntity();
            e.jobId = jobId; e.area = "OUTDOOR"; e.nombre = n; e.estado = "NOT_TESTED";
            itemMap.put(n, e);
        }

        JobRepository.get().getInspectionDataAsync(jobId, (existing, medicionesDb) -> {
            for (InspectionItemEntity e : existing) {
                InspectionItemEntity mem = itemMap.get(e.nombre);
                if (mem != null) {
                    mem.id = e.id;
                    mem.estado = e.estado;
                    mem.hallazgo = e.hallazgo;
                    mem.resolucion = e.resolucion;
                }
            }
            mediciones.addAll(medicionesDb);

            buildChecklist((LinearLayout) findViewById(R.id.layoutIndoor), INDOOR_ITEMS);
            buildChecklist((LinearLayout) findViewById(R.id.layoutOutdoor), OUTDOOR_ITEMS);
            setupMediciones();

            ((TextView) findViewById(R.id.btnAgregarMedicion)).setOnClickListener(v -> mostrarDialogoMedicion());
            findViewById(R.id.btnGuardarInspeccion).setOnClickListener(v -> guardarInspeccion());

            // Phase 8: Hallazgos section
            layoutHallazgos = findViewById(R.id.layoutHallazgos);
            ((TextView) findViewById(R.id.btnNuevoHallazgo)).setOnClickListener(v -> abrirNuevoHallazgo());
            recargarHallazgos();
        });
    }

    // ── Checklist ──────────────────────────────────────────────

    private void buildChecklist(LinearLayout container, String[] names) {
        container.removeAllViews();
        for (String name : names) {
            InspectionItemEntity item = itemMap.get(name);
            View row = buildItemRow(name, item);
            container.addView(row);
        }
    }

    private View buildItemRow(String name, InspectionItemEntity item) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(0, 0, 0, dpToPx(4));

        // Row: name + chips
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextColor(getResources().getColor(R.color.lc_text, getTheme()));
        tvName.setTextSize(15f);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvName.setLayoutParams(nameParams);
        row.addView(tvName);

        // PASS chip
        TextView btnPass = makeChip("PASS");
        // ISSUE chip
        TextView btnIssue = makeChip("ISSUE");
        // N/A chip
        TextView btnNa = makeChip("N/A");

        row.addView(btnPass);
        row.addView(btnIssue);
        row.addView(btnNa);

        // Issue detail area (hallazgo + resolucion)
        LinearLayout detailArea = new LinearLayout(this);
        detailArea.setOrientation(LinearLayout.VERTICAL);
        detailArea.setPadding(0, dpToPx(8), 0, dpToPx(4));
        detailArea.setVisibility("ISSUE".equals(item.estado) ? View.VISIBLE : View.GONE);

        EditText etHallazgo = new EditText(this);
        etHallazgo.setHint("Describe el hallazgo...");
        etHallazgo.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etHallazgo.setMinLines(2);
        etHallazgo.setTextSize(14f);
        etHallazgo.setTextColor(getResources().getColor(R.color.lc_text, getTheme()));
        etHallazgo.setHintTextColor(getResources().getColor(R.color.lc_text_tertiary, getTheme()));
        etHallazgo.setBackgroundResource(R.drawable.bg_surface);
        etHallazgo.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        if (item.hallazgo != null) etHallazgo.setText(item.hallazgo);

        Spinner spResolucion = new Spinner(this);
        ArrayAdapter<String> resAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, RESOLUCIONES.labels);
        resAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spResolucion.setAdapter(resAdapter);
        if (item.resolucion != null) {
            int ri = RESOLUCIONES.indexOf(item.resolucion);
            if (ri >= 0) spResolucion.setSelection(ri);
        }
        LinearLayout.LayoutParams spParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        spParams.topMargin = dpToPx(8);
        spResolucion.setLayoutParams(spParams);

        detailArea.addView(etHallazgo);
        detailArea.addView(spResolucion);

        // Divider
        View divider = new View(this);
        divider.setBackgroundColor(getResources().getColor(R.color.lc_border, getTheme()));
        LinearLayout.LayoutParams divParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        divParams.topMargin = dpToPx(12);
        divider.setLayoutParams(divParams);

        wrapper.addView(row);
        wrapper.addView(detailArea);
        wrapper.addView(divider);

        // Apply initial chip state
        applyChipState(btnPass, btnIssue, btnNa, item.estado);

        // Click listeners
        btnPass.setOnClickListener(v -> {
            item.estado = "PASS";
            applyChipState(btnPass, btnIssue, btnNa, "PASS");
            detailArea.setVisibility(View.GONE);
        });
        btnIssue.setOnClickListener(v -> {
            item.estado = "ISSUE";
            applyChipState(btnPass, btnIssue, btnNa, "ISSUE");
            detailArea.setVisibility(View.VISIBLE);
        });
        btnNa.setOnClickListener(v -> {
            item.estado = "NA";
            applyChipState(btnPass, btnIssue, btnNa, "NA");
            detailArea.setVisibility(View.GONE);
        });

        // Sync hallazgo/resolucion into item on save (stored via reference capture)
        // We'll read etHallazgo and spResolucion when saving
        wrapper.setTag(new Object[]{ etHallazgo, spResolucion, name });

        return wrapper;
    }

    private TextView makeChip(String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11f);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMarginStart(dpToPx(6));
        tv.setLayoutParams(p);
        return tv;
    }

    private void applyChipState(TextView btnPass, TextView btnIssue, TextView btnNa, String estado) {
        styleChip(btnPass, "PASS".equals(estado), 0xFF137333, 0xFF137333);
        styleChip(btnIssue, "ISSUE".equals(estado), 0xFFEB1700, 0xFFEB1700);
        styleChip(btnNa, "NA".equals(estado), 0xFF767676, 0xFF767676);
    }

    private void styleChip(TextView tv, boolean active, int activeColor, int borderColor) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dpToPx(12));
        if (active) {
            bg.setColor(activeColor);
            tv.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0x00000000);
            bg.setStroke(dpToPx(1), 0xFFCCCCCC);
            tv.setTextColor(getResources().getColor(R.color.lc_text_secondary, getTheme()));
        }
        tv.setBackground(bg);
    }

    // ── Mediciones ────────────────────────────────────────────

    private void setupMediciones() {
        RecyclerView rv = findViewById(R.id.recyclerMediciones);
        rv.setLayoutManager(new LinearLayoutManager(this));
        medicionAdapter = new MedicionAdapter(mediciones, id -> {
            JobRepository.get().deleteFieldMeasurement(id);
            mediciones.removeIf(m -> m.id == id);
            medicionAdapter.notifyDataSetChanged();
            actualizarVisibilidadMediciones();
        });
        rv.setAdapter(medicionAdapter);
        actualizarVisibilidadMediciones();
    }

    private void actualizarVisibilidadMediciones() {
        findViewById(R.id.tvSinMediciones).setVisibility(mediciones.isEmpty() ? View.VISIBLE : View.GONE);
        findViewById(R.id.recyclerMediciones).setVisibility(mediciones.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void mostrarDialogoMedicion() {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_medicion, null);
        Spinner spTipo = v.findViewById(R.id.spTipo);
        EditText etUbicacion = v.findViewById(R.id.etUbicacion);
        EditText etValor = v.findViewById(R.id.etValor);
        Spinner spUnidad = v.findViewById(R.id.spUnidad);
        EditText etNota = v.findViewById(R.id.etNota);

        ArrayAdapter<String> tipoAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, TIPOS.labels);
        tipoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTipo.setAdapter(tipoAdapter);

        ArrayAdapter<String> unidadAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, UNIDADES.labels);
        unidadAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spUnidad.setAdapter(unidadAdapter);

        new AlertDialog.Builder(this)
                .setTitle("Agregar medici\u00f3n")
                .setView(v)
                .setPositiveButton("Guardar", (d, w) -> {
                    String valorStr = etValor.getText().toString().trim();
                    if (valorStr.isEmpty()) {
                        Toast.makeText(this, "Ingresa un valor", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    double valor;
                    try { valor = Double.parseDouble(valorStr); }
                    catch (NumberFormatException e) {
                        Toast.makeText(this, "Valor inv\u00e1lido", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    FieldMeasurementEntity m = new FieldMeasurementEntity();
                    m.jobId = jobId;
                    m.tipo = TIPOS.ids[spTipo.getSelectedItemPosition()];
                    m.ubicacion = etUbicacion.getText().toString().trim();
                    m.valor = valor;
                    m.unidad = UNIDADES.ids[spUnidad.getSelectedItemPosition()];
                    m.nota = etNota.getText().toString().trim();
                    if (m.nota.isEmpty()) m.nota = null;
                    JobRepository.get().addFieldMeasurementAsync(m, newId -> {
                        m.id = newId;
                        mediciones.add(m);
                        medicionAdapter.notifyDataSetChanged();
                        actualizarVisibilidadMediciones();
                    });
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ── Save ──────────────────────────────────────────────────

    private void guardarInspeccion() {
        // Harvest hallazgo/resolucion from views
        LinearLayout indoor = findViewById(R.id.layoutIndoor);
        LinearLayout outdoor = findViewById(R.id.layoutOutdoor);
        harvestFromContainer(indoor);
        harvestFromContainer(outdoor);

        List<InspectionItemEntity> toSave = new ArrayList<>(itemMap.values());
        JobRepository.get().guardarInspectionItems(jobId, toSave);

        long pass = 0, issues = 0;
        for (InspectionItemEntity e : toSave) {
            if ("PASS".equals(e.estado)) pass++;
            else if ("ISSUE".equals(e.estado)) issues++;
        }
        String resumen = pass + " OK \u00b7 " + issues + " issue(s) \u00b7 " + mediciones.size() + " mediciones";
        JobRepository.get().agregarEvento(jobId, "INSPECCION", "Inspecci\u00f3n guardada (" + resumen + ")");

        Toast.makeText(this, "Inspecci\u00f3n guardada", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void harvestFromContainer(LinearLayout container) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            Object tag = child.getTag();
            if (tag instanceof Object[]) {
                Object[] arr = (Object[]) tag;
                EditText etHallazgo = (EditText) arr[0];
                Spinner spResolucion = (Spinner) arr[1];
                String name = (String) arr[2];
                InspectionItemEntity item = itemMap.get(name);
                if (item != null && "ISSUE".equals(item.estado)) {
                    item.hallazgo = etHallazgo.getText().toString().trim();
                    int rp = spResolucion.getSelectedItemPosition();
                    String res = rp >= 0 && rp < RESOLUCIONES.ids.length ? RESOLUCIONES.ids[rp] : "";
                    item.resolucion = res.isEmpty() ? null : res;
                }
            }
        }
    }

    // ── Inner adapter for mediciones ──────────────────────────

    private static class MedicionAdapter extends RecyclerView.Adapter<MedicionAdapter.VH> {
        interface OnDelete { void onDelete(long id); }
        private final List<FieldMeasurementEntity> list;
        private final OnDelete onDelete;
        MedicionAdapter(List<FieldMeasurementEntity> list, OnDelete onDelete) {
            this.list = list; this.onDelete = onDelete;
        }
        @Override public VH onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dpToPx(parent.getContext(), 10), 0, dpToPx(parent.getContext(), 10));
            return new VH(row);
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            FieldMeasurementEntity m = list.get(pos);
            h.bind(m, onDelete);
        }
        @Override public int getItemCount() { return list.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final LinearLayout row;
            VH(LinearLayout row) { super(row); this.row = row; }
            void bind(FieldMeasurementEntity m, OnDelete onDelete) {
                row.removeAllViews();
                TextView tvInfo = new TextView(row.getContext());
                String label = m.tipo + " \u00b7 " + (m.ubicacion != null ? m.ubicacion : "") +
                               " \u00b7 " + m.valor + " " + m.unidad;
                if (m.nota != null && !m.nota.isEmpty()) label += "\n" + m.nota;
                tvInfo.setText(label);
                tvInfo.setTextSize(13f);
                tvInfo.setTextColor(0xFF333333);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                tvInfo.setLayoutParams(p);
                row.addView(tvInfo);

                TextView btnDel = new TextView(row.getContext());
                btnDel.setText("\u2715");
                btnDel.setTextSize(16f);
                btnDel.setTextColor(0xFFEB1700);
                btnDel.setPadding(dpToPx(row.getContext(), 12), 0, 0, 0);
                btnDel.setOnClickListener(v -> onDelete.onDelete(m.id));
                row.addView(btnDel);
            }
            static int dpToPx(android.content.Context ctx, int dp) {
                return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
            }
        }
        static int dpToPx(android.content.Context ctx, int dp) {
            return Math.round(dp * ctx.getResources().getDisplayMetrics().density);
        }
    }

    // ── Hallazgos (Phase 8) ───────────────────────────────────────────────────

    private void abrirNuevoHallazgo() {
        Intent intent = new Intent(this, FindingDetailActivity.class);
        intent.putExtra(FindingDetailActivity.EXTRA_JOB_ID, jobId);
        findingLauncher.launch(intent);
    }

    private void recargarHallazgos() {
        Executors.newSingleThreadExecutor().execute(() -> {
            List<FindingEntity> findings =
                    LcDatabase.getInstance(this).findingDao().getFindingsForJobSync(jobId);
            runOnUiThread(() -> {
                hallazgos.clear();
                hallazgos.addAll(findings);
                renderHallazgos();
            });
        });
    }

    private void renderHallazgos() {
        if (layoutHallazgos == null) return;
        layoutHallazgos.removeAllViews();
        TextView tvSin = findViewById(R.id.tvSinHallazgos);

        if (hallazgos.isEmpty()) {
            tvSin.setVisibility(View.VISIBLE);
            layoutHallazgos.setVisibility(View.GONE);
            return;
        }

        tvSin.setVisibility(View.GONE);
        layoutHallazgos.setVisibility(View.VISIBLE);

        for (FindingEntity f : hallazgos) {
            View row = LayoutInflater.from(this).inflate(R.layout.item_finding, layoutHallazgos, false);

            TextView tvSeverity = row.findViewById(R.id.tvSeverityBadge);
            TextView tvAreaComp = row.findViewById(R.id.tvAreaComponent);
            TextView tvProblem  = row.findViewById(R.id.tvProblem);
            TextView tvStatus   = row.findViewById(R.id.tvStatus);
            TextView btnConvert = row.findViewById(R.id.btnConvertirWorkItem);

            // Severity badge
            String sev = f.severity != null ? f.severity : "MEDIUM";
            String sevLabel;
            switch (sev) {
                case "CRITICAL": sevLabel = "🔴 CRÍTICO"; break;
                case "HIGH":     sevLabel = "🟠 ALTO"; break;
                case "LOW":      sevLabel = "🟢 BAJO"; break;
                default:         sevLabel = "🟡 MEDIO"; break;
            }
            tvSeverity.setText(sevLabel);

            // Area + component
            String areaLabel = areaToLabel(f.area);
            tvAreaComp.setText(areaLabel + " · " + f.component);
            tvProblem.setText(f.problem);

            // Status
            String statusLabel;
            switch (f.status != null ? f.status : "OPEN") {
                case "IN_PROGRESS": statusLabel = "EN PROCESO"; break;
                case "RESOLVED":    statusLabel = "RESUELTO"; break;
                case "DISMISSED":   statusLabel = "DESCARTADO"; break;
                default:            statusLabel = "ABIERTO"; break;
            }
            tvStatus.setText(statusLabel);

            // Hide convert button if already resolved or dismissed
            boolean canConvert = "OPEN".equals(f.status) || "IN_PROGRESS".equals(f.status);
            btnConvert.setVisibility(canConvert ? View.VISIBLE : View.GONE);

            final FindingEntity finding = f;
            row.setOnClickListener(v -> {
                Intent intent = new Intent(this, FindingDetailActivity.class);
                intent.putExtra(FindingDetailActivity.EXTRA_JOB_ID, jobId);
                intent.putExtra(FindingDetailActivity.EXTRA_FINDING_ID, finding.id);
                findingLauncher.launch(intent);
            });

            btnConvert.setOnClickListener(v -> convertirAWorkItem(finding));

            layoutHallazgos.addView(row);
        }
    }

    private void convertirAWorkItem(FindingEntity finding) {
        Executors.newSingleThreadExecutor().execute(() -> {
            LcDatabase db = LcDatabase.getInstance(this);

            // Create WorkItem from finding
            WorkItemEntity wi = new WorkItemEntity();
            wi.jobId = finding.jobId;
            wi.findingId = finding.id;
            wi.tipo = "LABOR";
            wi.descripcion = areaToLabel(finding.area) + " — " + finding.component + ": " + finding.problem;
            wi.cantidad = 1.0;
            wi.precioUnitario = 0.0;
            wi.total = 0.0;
            wi.timestamp = System.currentTimeMillis();
            db.workItemDao().insert(wi);

            // Update finding status to IN_PROGRESS
            finding.status = "IN_PROGRESS";
            finding.updatedAt = System.currentTimeMillis();
            db.findingDao().update(finding);

            runOnUiThread(() -> {
                Toast.makeText(this, "Trabajo creado desde hallazgo", Toast.LENGTH_SHORT).show();
                recargarHallazgos();
            });
        });
    }

    private String areaToLabel(String area) {
        if (area == null) return "Otro";
        switch (area) {
            case "HVAC_INDOOR":  return "HVAC Interior";
            case "HVAC_OUTDOOR": return "HVAC Exterior";
            case "ELECTRICAL":   return "Eléctrico";
            case "PLUMBING":     return "Plomería";
            case "APPLIANCE":    return "Electrodoméstico";
            case "DUCTWORK":     return "Ductos";
            case "CONTROLS":     return "Controles";
            default:             return "Otro";
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
