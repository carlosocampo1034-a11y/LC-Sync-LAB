package com.lc.technician;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.data.JobRepository;
import com.lc.technician.model.EquipmentEntity;
import com.lc.technician.ocr.NameplateCandidates;
import com.lc.technician.ocr.NameplateOcrProcessor;
import com.lc.technician.ocr.NameplateParser;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class EquipmentActivity extends AppCompatActivity {

    private EquipmentAdapter adapter;
    private final List<EquipmentEntity> equipos = new ArrayList<>();

    // OCR / camera state
    private File tempNameplateFile;
    private EquipmentEntity pendingOcrEquipment;
    /** EditText fields of the currently-open edit dialog — updated after OCR confirm */
    private EditText dialogEtMarca, dialogEtModelo, dialogEtSerial, dialogEtTons, dialogEtSeer, dialogEtManufDate;
    private Spinner dialogSpRefrigerante, dialogSpVoltaje, dialogSpFase;

    private final ActivityResultLauncher<String> launcherCameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) launchNameplateCamera();
                else Toast.makeText(this, "Permiso de cámara requerido", Toast.LENGTH_SHORT).show();
            });

    private final ActivityResultLauncher<Uri> launcherNameplateCamera =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (Boolean.TRUE.equals(success) && tempNameplateFile != null && tempNameplateFile.exists()) {
                    runNameplateOcr(tempNameplateFile);
                }
            });

    private final ActivityResultLauncher<Intent> launcherNameplateReview =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && pendingOcrEquipment != null) {
                    // Reload confirmed equipment and refresh dialog fields
                    JobRepository.get().getEquipmentByIdAsync(pendingOcrEquipment.id, eq -> {
                        if (eq == null) return;
                        pendingOcrEquipment = eq;
                        runOnUiThread(() -> refreshDialogFields(eq));
                    });
                }
            });

    // ── Spinner option arrays ────────────────────────────────────────────────

    private static final String[] REFRIGERANTES =
            {"— Sin especificar —", "R-410A", "R-22", "R-32", "R-454B", "Otro"};
    private static final String[] VOLTAJES =
            {"— Sin especificar —", "115V", "208/230V", "220V", "230V", "460V", "Otro"};
    private static final String[] FASES =
            {"— Sin especificar —", "Monofásico", "Trifásico"};
    private static final String[] SYSTEM_ROLES =
            {"Sin asignar", "INDOOR", "OUTDOOR"};
    private static final String[] LIFECYCLE_STATUSES =
            {"ACTIVE", "REPLACED", "RETIRED"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equipment);
        // Edge-to-edge (Android 15+): cabecera bajo la barra de estado, último equipo visible sobre la navegación
        InsetsHelper.padTop(findViewById(R.id.header));
        InsetsHelper.padBottom(findViewById(R.id.recyclerEquipment));

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        RecyclerView recycler = findViewById(R.id.recyclerEquipment);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new EquipmentAdapter(equipos, this::mostrarDialogoEditar, this::confirmarEliminar);
        recycler.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cargar();
    }

    private void cargar() {
        JobRepository.get().getAllEquipmentAsync(result -> {
            equipos.clear();
            equipos.addAll(result);
            adapter.notifyDataSetChanged();
            TextView tvConteo = findViewById(R.id.tvConteo);
            tvConteo.setText(result.size() + " equipos");
        });
    }

    // ── Dialog ───────────────────────────────────────────────────────────────

    private void mostrarDialogoEditar(EquipmentEntity eq) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_equipment_form, null);

        // Identidad básica
        EditText etNombre = view.findViewById(R.id.etEqNombre);
        EditText etMarca  = view.findViewById(R.id.etEqMarca);
        EditText etModelo = view.findViewById(R.id.etEqModelo);
        EditText etSerial = view.findViewById(R.id.etEqSerial);
        EditText etTipo   = view.findViewById(R.id.etEqTipo);
        EditText etNotas  = view.findViewById(R.id.etEqNotas);

        // Especificaciones técnicas
        EditText etTons          = view.findViewById(R.id.etEqTons);
        EditText etSeer          = view.findViewById(R.id.etEqSeer);
        Spinner  spRefrigerante  = view.findViewById(R.id.spinnerRefrigerante);
        Spinner  spVoltaje       = view.findViewById(R.id.spinnerVoltaje);
        Spinner  spFase          = view.findViewById(R.id.spinnerFase);
        EditText etManufDate     = view.findViewById(R.id.etEqManufactureDate);
        EditText etUbicacion     = view.findViewById(R.id.etEqUbicacion);
        CheckBox cbHeatPump      = view.findViewById(R.id.cbEqHeatPump);

        // Topología / lifecycle
        Spinner  spSystemRole    = view.findViewById(R.id.spinnerSystemRole);
        Spinner  spLifecycle     = view.findViewById(R.id.spinnerLifecycle);
        TextView tvScanStatus    = view.findViewById(R.id.tvScanStatus);

        // Keep references for post-OCR refresh
        dialogEtMarca       = etMarca;
        dialogEtModelo      = etModelo;
        dialogEtSerial      = etSerial;
        dialogEtTons        = etTons;
        dialogEtSeer        = etSeer;
        dialogEtManufDate   = etManufDate;
        dialogSpRefrigerante = spRefrigerante;
        dialogSpVoltaje     = spVoltaje;
        dialogSpFase        = spFase;
        pendingOcrEquipment = eq;

        // Inicializar spinners
        initSpinner(spRefrigerante, REFRIGERANTES);
        initSpinner(spVoltaje, VOLTAJES);
        initSpinner(spFase, FASES);
        initSpinner(spSystemRole, SYSTEM_ROLES);
        initSpinner(spLifecycle, LIFECYCLE_STATUSES);

        // Poblar campos con datos existentes
        etNombre.setText(eq.nombre);
        etMarca.setText(eq.marca);
        etModelo.setText(eq.modelo);
        etSerial.setText(eq.serial);
        etTipo.setText(eq.tipo);
        etNotas.setText(eq.notas);

        if (eq.tons != null) etTons.setText(String.valueOf(eq.tons));
        if (eq.seer != null) etSeer.setText(String.valueOf(eq.seer));
        if (eq.manufactureDate != null) etManufDate.setText(eq.manufactureDate);
        if (eq.ubicacion != null) etUbicacion.setText(eq.ubicacion);
        cbHeatPump.setChecked(Boolean.TRUE.equals(eq.heatPump));

        setSpinnerValue(spRefrigerante, REFRIGERANTES, eq.refrigerante);
        setSpinnerValue(spVoltaje, VOLTAJES, eq.voltaje);
        setSpinnerValue(spFase, FASES, eq.fase);
        setSpinnerValue(spSystemRole, SYSTEM_ROLES, eq.systemRole);
        setSpinnerValue(spLifecycle, LIFECYCLE_STATUSES, eq.lifecycleStatus);

        String scanLabel = eq.scanStatus != null ? eq.scanStatus : "MANUAL";
        tvScanStatus.setText(scanLabel);

        // Escanear placa button
        Button btnScan = view.findViewById(R.id.btnScanNameplate);
        btnScan.setOnClickListener(v -> {
            pendingOcrEquipment = eq;
            launcherCameraPermission.launch(Manifest.permission.CAMERA);
        });

        new AlertDialog.Builder(this)
                .setTitle("Editar equipo")
                .setView(view)
                .setPositiveButton("Guardar", (d, w) -> {
                    eq.nombre  = etNombre.getText().toString().trim();
                    eq.marca   = etMarca.getText().toString().trim();
                    eq.modelo  = etModelo.getText().toString().trim();
                    eq.serial  = etSerial.getText().toString().trim();
                    eq.tipo    = etTipo.getText().toString().trim();
                    eq.notas   = etNotas.getText().toString().trim();

                    String tonsStr = etTons.getText().toString().trim();
                    eq.tons = tonsStr.isEmpty() ? null : safeDouble(tonsStr);

                    String seerStr = etSeer.getText().toString().trim();
                    eq.seer = seerStr.isEmpty() ? null : safeDouble(seerStr);

                    eq.refrigerante  = spinnerValue(spRefrigerante, REFRIGERANTES);
                    eq.voltaje       = spinnerValue(spVoltaje, VOLTAJES);
                    eq.fase          = spinnerValue(spFase, FASES);
                    eq.manufactureDate = etManufDate.getText().toString().trim();
                    if (eq.manufactureDate.isEmpty()) eq.manufactureDate = null;
                    eq.ubicacion     = emptyToNull(etUbicacion.getText().toString().trim());
                    eq.heatPump      = cbHeatPump.isChecked();
                    eq.systemRole    = spinnerValueOrNull(spSystemRole, SYSTEM_ROLES, "Sin asignar");
                    eq.lifecycleStatus = spinnerValue(spLifecycle, LIFECYCLE_STATUSES);
                    if (eq.scanStatus == null) eq.scanStatus = "MANUAL";

                    eq.updatedAt = System.currentTimeMillis();
                    JobRepository.get().updateEquipmentAsync(eq, this::cargar);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ── Nameplate OCR helpers ────────────────────────────────────────────────

    private void launchNameplateCamera() {
        File dir = getExternalFilesDir("fotos");
        if (dir != null) dir.mkdirs();
        try {
            tempNameplateFile = File.createTempFile("nameplate_", ".jpg", dir);
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", tempNameplateFile);
            launcherNameplateCamera.launch(uri);
        } catch (IOException e) {
            Toast.makeText(this, "Error al crear archivo de foto", Toast.LENGTH_SHORT).show();
        }
    }

    private void runNameplateOcr(File imageFile) {
        Uri uri = FileProvider.getUriForFile(this,
                getPackageName() + ".fileprovider", imageFile);

        // Set scanStatus to PENDING_CONFIRMATION immediately
        if (pendingOcrEquipment != null) {
            pendingOcrEquipment.scanStatus = "PENDING_CONFIRMATION";
            pendingOcrEquipment.updatedAt = System.currentTimeMillis();
            JobRepository.get().updateEquipmentAsync(pendingOcrEquipment, null);
        }

        Toast.makeText(this, "Procesando placa…", Toast.LENGTH_SHORT).show();

        new NameplateOcrProcessor(this).processUri(uri, new NameplateOcrProcessor.OcrCallback() {
            @Override
            public void onSuccess(String rawText) {
                NameplateCandidates candidates = NameplateParser.parse(rawText);
                candidates = NameplateParser.normalize(candidates);
                launchNameplateReview(candidates);
            }

            @Override
            public void onFailure(Exception e) {
                Toast.makeText(EquipmentActivity.this,
                        "Error de OCR: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void launchNameplateReview(NameplateCandidates c) {
        if (pendingOcrEquipment == null) return;
        Intent intent = new Intent(this, NameplateReviewActivity.class);
        intent.putExtra(NameplateReviewActivity.EXTRA_EQUIPMENT_ID, pendingOcrEquipment.id);
        intent.putExtra(NameplateReviewActivity.EXTRA_MANUFACTURER, c.manufacturer);
        intent.putExtra(NameplateReviewActivity.EXTRA_MODEL,        c.model);
        intent.putExtra(NameplateReviewActivity.EXTRA_SERIAL,       c.serial);
        intent.putExtra(NameplateReviewActivity.EXTRA_TONS,         c.tons);
        intent.putExtra(NameplateReviewActivity.EXTRA_REFRIGERANT,  c.refrigerant);
        intent.putExtra(NameplateReviewActivity.EXTRA_VOLTAGE,      c.voltage);
        intent.putExtra(NameplateReviewActivity.EXTRA_PHASE,        c.phase);
        intent.putExtra(NameplateReviewActivity.EXTRA_SEER,         c.seer);
        intent.putExtra(NameplateReviewActivity.EXTRA_MANUF_DATE,   c.manufactureDate);
        intent.putExtra(NameplateReviewActivity.EXTRA_RAW_TEXT,     c.rawText);
        // Pass photo file path so NameplateReviewActivity can persist it on CONFIRM
        if (tempNameplateFile != null) {
            intent.putExtra(NameplateReviewActivity.EXTRA_PHOTO_PATH,
                    tempNameplateFile.getAbsolutePath());
        }
        launcherNameplateReview.launch(intent);
    }

    /** Refreshes the currently-open edit dialog fields after OCR confirmation. */
    private void refreshDialogFields(EquipmentEntity eq) {
        if (dialogEtMarca  != null && eq.marca  != null) dialogEtMarca.setText(eq.marca);
        if (dialogEtModelo != null && eq.modelo != null) dialogEtModelo.setText(eq.modelo);
        if (dialogEtSerial != null && eq.serial != null) dialogEtSerial.setText(eq.serial);
        if (dialogEtTons   != null && eq.tons   != null) dialogEtTons.setText(String.valueOf(eq.tons));
        if (dialogEtSeer   != null && eq.seer   != null) dialogEtSeer.setText(String.valueOf(eq.seer));
        if (dialogEtManufDate != null && eq.manufactureDate != null)
            dialogEtManufDate.setText(eq.manufactureDate);
        if (dialogSpRefrigerante != null && eq.refrigerante != null)
            setSpinnerValue(dialogSpRefrigerante, REFRIGERANTES, eq.refrigerante);
        if (dialogSpVoltaje != null && eq.voltaje != null)
            setSpinnerValue(dialogSpVoltaje, VOLTAJES, eq.voltaje);
        if (dialogSpFase != null && eq.fase != null)
            setSpinnerValue(dialogSpFase, FASES, eq.fase);
    }

    private void confirmarEliminar(EquipmentEntity eq) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar equipo")
                .setMessage("¿Eliminar \"" + eq.nombre + "\"? Esta acción no se puede deshacer.")
                .setPositiveButton("Eliminar", (d, w) ->
                        JobRepository.get().deleteEquipmentAsync(eq, this::cargar))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ── Spinner helpers ──────────────────────────────────────────────────────

    private void initSpinner(Spinner spinner, String[] items) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void setSpinnerValue(Spinner spinner, String[] items, String value) {
        if (value == null) return;
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(value)) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    /** Returns the selected spinner value, or null if it equals the first "empty" option. */
    private String spinnerValue(Spinner spinner, String[] items) {
        int pos = spinner.getSelectedItemPosition();
        if (pos <= 0) return null;
        return items[pos];
    }

    /** Returns null when selected item matches the sentinel string. */
    private String spinnerValueOrNull(Spinner spinner, String[] items, String nullSentinel) {
        int pos = spinner.getSelectedItemPosition();
        String val = items[pos];
        return val.equals(nullSentinel) ? null : val;
    }

    private static Double safeDouble(String s) {
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    interface OnEquipClick { void onClick(EquipmentEntity eq); }

    static class EquipmentAdapter extends RecyclerView.Adapter<EquipmentAdapter.VH> {
        private final List<EquipmentEntity> list;
        private final OnEquipClick onEdit;
        private final OnEquipClick onDelete;

        EquipmentAdapter(List<EquipmentEntity> list, OnEquipClick onEdit, OnEquipClick onDelete) {
            this.list = list;
            this.onEdit = onEdit;
            this.onDelete = onDelete;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_equipment, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            EquipmentEntity eq = list.get(position);

            h.tvNombre.setText(eq.nombre != null ? eq.nombre : "");

            String marcaModelo = (eq.marca != null ? eq.marca : "") +
                    (eq.modelo != null && !eq.modelo.isEmpty() ? "  ·  " + eq.modelo : "");
            h.tvMarcaModelo.setText(marcaModelo);
            h.tvMarcaModelo.setVisibility(marcaModelo.isEmpty() ? View.GONE : View.VISIBLE);

            String serial = eq.serial != null ? eq.serial : "";
            h.tvSerial.setText(serial.isEmpty() ? "" : "S/N: " + serial);
            h.tvSerial.setVisibility(serial.isEmpty() ? View.GONE : View.VISIBLE);

            String tipo = eq.tipo != null ? eq.tipo : "";
            h.tvTipo.setText(tipo);
            h.tvTipo.setVisibility(tipo.isEmpty() ? View.GONE : View.VISIBLE);

            h.tvLinked.setVisibility(eq.propertyId > 0 ? View.VISIBLE : View.GONE);

            // Fila de specs técnicas
            boolean hasTons = eq.tons != null;
            boolean hasRef  = eq.refrigerante != null && !eq.refrigerante.isEmpty();
            boolean hasRole = eq.systemRole != null && !eq.systemRole.isEmpty();
            boolean showSpecs = hasTons || hasRef || hasRole;

            h.rowSpecs.setVisibility(showSpecs ? View.VISIBLE : View.GONE);

            if (hasTons) {
                String tonsLabel = eq.tons % 1 == 0
                        ? String.valueOf((int) eq.tons.doubleValue()) + " tons"
                        : eq.tons + " tons";
                h.tvTons.setText(tonsLabel);
                h.tvTons.setVisibility(View.VISIBLE);
            } else {
                h.tvTons.setVisibility(View.GONE);
            }

            if (hasRef) {
                h.tvRefrigerante.setText(eq.refrigerante);
                h.tvRefrigerante.setVisibility(View.VISIBLE);
            } else {
                h.tvRefrigerante.setVisibility(View.GONE);
            }

            if (hasRole) {
                h.tvSystemRole.setText(eq.systemRole);
                h.tvSystemRole.setVisibility(View.VISIBLE);
            } else {
                h.tvSystemRole.setVisibility(View.GONE);
            }

            h.itemView.setOnClickListener(v -> onEdit.onClick(eq));
            h.itemView.setOnLongClickListener(v -> { onDelete.onClick(eq); return true; });
        }

        @Override
        public int getItemCount() { return list.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvNombre, tvMarcaModelo, tvSerial, tvTipo, tvLinked;
            LinearLayout rowSpecs;
            TextView tvTons, tvRefrigerante, tvSystemRole;

            VH(View v) {
                super(v);
                tvNombre      = v.findViewById(R.id.tvEqNombre);
                tvMarcaModelo = v.findViewById(R.id.tvEqMarcaModelo);
                tvSerial      = v.findViewById(R.id.tvEqSerial);
                tvTipo        = v.findViewById(R.id.tvEqTipo);
                tvLinked      = v.findViewById(R.id.tvEqLinked);
                rowSpecs      = v.findViewById(R.id.rowEqSpecs);
                tvTons        = v.findViewById(R.id.tvEqTons);
                tvRefrigerante = v.findViewById(R.id.tvEqRefrigerante);
                tvSystemRole  = v.findViewById(R.id.tvEqSystemRole);
            }
        }
    }
}
