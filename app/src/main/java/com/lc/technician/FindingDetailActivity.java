package com.lc.technician;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
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
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.FindingEntity;
import com.lc.technician.model.FindingPhotoEntity;
import com.lc.technician.model.WorkItemEntity;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class FindingDetailActivity extends AppCompatActivity {

    public static final String EXTRA_JOB_ID = "EXTRA_JOB_ID";
    public static final String EXTRA_FINDING_ID = "EXTRA_FINDING_ID";

    // Closed enum for area
    // Áreas del hallazgo: lista editable desde Control (settings finding_areas.list); el respaldo coincide con la tabla
    private com.lc.technician.sync.Listas.Lista AREAS;

    private void cargarListas() {
        AREAS = com.lc.technician.sync.Listas.lista(this, "finding_areas.list", new String[][]{{"HVAC_INDOOR", "HVAC Interior"},
                {"HVAC_OUTDOOR", "HVAC Exterior"}, {"ELECTRICAL", "El\u00e9ctrico"}, {"PLUMBING", "Plomer\u00eda"}, {"APPLIANCE", "Electrodom\u00e9stico"},
                {"DUCTWORK", "Ductos"}, {"CONTROLS", "Controles"}, {"OTHER", "Otro"}}, null);
    }

    private long jobId;
    private long findingId;
    private FindingEntity existingFinding;

    private Spinner spinnerArea;
    private EditText etComponent, etProblem, etDiagnosis, etResolution, etTechnicianNotes;
    private TextView chipCritical, chipHigh, chipMedium, chipLow;
    private TextView tvPhotoCount;
    private LinearLayout photoRow;

    private String selectedSeverity = "MEDIUM";
    private File tempPhotoFile;
    private final List<String> addedPhotoPaths = new ArrayList<>();

    private final ActivityResultLauncher<String> cameraPermLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) launchCamera();
                else Toast.makeText(this, "Permiso de cámara requerido", Toast.LENGTH_SHORT).show();
            });

    private final ActivityResultLauncher<Uri> cameraLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (Boolean.TRUE.equals(success) && tempPhotoFile != null && tempPhotoFile.exists()) {
                    addedPhotoPaths.add(tempPhotoFile.getAbsolutePath());
                    updatePhotoCount();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        com.lc.technician.sync.Listas.refrescar(this, true, null);
        cargarListas();
        setContentView(R.layout.activity_finding_detail);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        jobId = getIntent().getLongExtra(EXTRA_JOB_ID, 0);
        findingId = getIntent().getLongExtra(EXTRA_FINDING_ID, 0);

        if (jobId == 0) {
            Toast.makeText(this, "Error: jobId requerido", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        bindViews();
        setupArea();
        setupSeverityChips();
        setupButtons();

        if (findingId > 0) {
            loadExistingFinding();
        }
    }

    private void bindViews() {
        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());
        spinnerArea = findViewById(R.id.spinnerArea);
        etComponent = findViewById(R.id.etComponent);
        etProblem = findViewById(R.id.etProblem);
        etDiagnosis = findViewById(R.id.etDiagnosis);
        etResolution = findViewById(R.id.etResolution);
        etTechnicianNotes = findViewById(R.id.etTechnicianNotes);
        chipCritical = findViewById(R.id.chipCritical);
        chipHigh = findViewById(R.id.chipHigh);
        chipMedium = findViewById(R.id.chipMedium);
        chipLow = findViewById(R.id.chipLow);
        tvPhotoCount = findViewById(R.id.tvPhotoCount);
        photoRow = findViewById(R.id.photoRow);
    }

    private void setupArea() {
        ArrayAdapter<String> areaAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, AREAS.labels);
        areaAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerArea.setAdapter(areaAdapter);
    }

    private void setupSeverityChips() {
        // Default: MEDIUM selected
        setSeverity("MEDIUM");

        chipCritical.setOnClickListener(v -> setSeverity("CRITICAL"));
        chipHigh.setOnClickListener(v -> setSeverity("HIGH"));
        chipMedium.setOnClickListener(v -> setSeverity("MEDIUM"));
        chipLow.setOnClickListener(v -> setSeverity("LOW"));
    }

    private void setSeverity(String severity) {
        selectedSeverity = severity;
        // Reset all chips to unselected
        chipCritical.setBackgroundResource(R.drawable.bg_chip_unselected);
        chipHigh.setBackgroundResource(R.drawable.bg_chip_unselected);
        chipMedium.setBackgroundResource(R.drawable.bg_chip_unselected);
        chipLow.setBackgroundResource(R.drawable.bg_chip_unselected);
        // Highlight selected
        switch (severity) {
            case "CRITICAL": chipCritical.setBackgroundResource(R.drawable.bg_chip_selected); break;
            case "HIGH":     chipHigh.setBackgroundResource(R.drawable.bg_chip_selected); break;
            case "LOW":      chipLow.setBackgroundResource(R.drawable.bg_chip_selected); break;
            default:         chipMedium.setBackgroundResource(R.drawable.bg_chip_selected); break;
        }
    }

    private void setupButtons() {
        findViewById(R.id.btnCancelar).setOnClickListener(v -> finish());
        findViewById(R.id.btnGuardar).setOnClickListener(v -> guardar());
        findViewById(R.id.btnAddPhoto).setOnClickListener(v ->
                cameraPermLauncher.launch(Manifest.permission.CAMERA));
        // Convert to WorkItem button (visible only in edit mode, after prefill)
        findViewById(R.id.btnConvertirTrabajoDetail).setOnClickListener(v -> convertirAWorkItem());
    }

    private void loadExistingFinding() {
        ((TextView) findViewById(R.id.tvTitulo)).setText("Editar Hallazgo");
        Executors.newSingleThreadExecutor().execute(() -> {
            existingFinding = LcDatabase.getInstance(this).findingDao().getFindingByIdSync(findingId);
            if (existingFinding != null) {
                runOnUiThread(this::prefill);
            }
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

    private void convertirAWorkItem() {
        FindingEntity f = existingFinding;
        if (f == null) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            LcDatabase db = LcDatabase.getInstance(this);
            WorkItemEntity wi = new WorkItemEntity();
            wi.jobId = f.jobId;
            wi.findingId = f.id;
            wi.tipo = "LABOR";
            wi.descripcion = areaToLabel(f.area) + " — " + f.component + ": " + f.problem;
            wi.cantidad = 1.0;
            wi.precioUnitario = 0.0;
            wi.total = 0.0;
            wi.timestamp = System.currentTimeMillis();
            db.workItemDao().insert(wi);

            // Update finding status
            f.status = "IN_PROGRESS";
            f.updatedAt = System.currentTimeMillis();
            db.findingDao().update(f);

            runOnUiThread(() -> {
                Toast.makeText(this, "Trabajo creado desde hallazgo", Toast.LENGTH_SHORT).show();
                // Hide button after conversion
                findViewById(R.id.btnConvertirTrabajoDetail).setVisibility(View.GONE);
                setResult(RESULT_OK);
            });
        });
    }

    private void prefill() {
        FindingEntity f = existingFinding;
        // Area
        int ai = AREAS.indexOf(f.area);
        if (ai >= 0) spinnerArea.setSelection(ai);
        etComponent.setText(f.component);
        etProblem.setText(f.problem);
        if (f.diagnosis != null) etDiagnosis.setText(f.diagnosis);
        if (f.resolution != null) etResolution.setText(f.resolution);
        if (f.technicianNotes != null) etTechnicianNotes.setText(f.technicianNotes);
        setSeverity(f.severity);
        // Show convert button only if not yet resolved/dismissed
        boolean canConvert = "OPEN".equals(f.status) || "IN_PROGRESS".equals(f.status);
        if (canConvert) {
            findViewById(R.id.btnConvertirTrabajoDetail).setVisibility(View.VISIBLE);
        }
    }

    private void guardar() {
        String component = etComponent.getText().toString().trim();
        String problem   = etProblem.getText().toString().trim();
        int areaPos      = spinnerArea.getSelectedItemPosition();

        if (component.isEmpty()) {
            etComponent.setError("Campo requerido");
            etComponent.requestFocus();
            return;
        }
        if (problem.isEmpty()) {
            etProblem.setError("Campo requerido");
            etProblem.requestFocus();
            return;
        }
        if (areaPos < 0 || areaPos >= AREAS.ids.length) {
            Toast.makeText(this, "Seleccione un área", Toast.LENGTH_SHORT).show();
            return;
        }

        String area       = AREAS.ids[areaPos];
        String diagnosis  = etDiagnosis.getText().toString().trim();
        String resolution = etResolution.getText().toString().trim();
        String notes      = etTechnicianNotes.getText().toString().trim();
        long now          = System.currentTimeMillis();

        List<String> photos = new ArrayList<>(addedPhotoPaths);

        Executors.newSingleThreadExecutor().execute(() -> {
            LcDatabase db = LcDatabase.getInstance(this);
            long targetFindingId = findingId;

            if (findingId == 0) {
                // New finding
                FindingEntity f = new FindingEntity();
                f.jobId = jobId;
                f.area = area;
                f.component = component;
                f.problem = problem;
                f.diagnosis = diagnosis.isEmpty() ? null : diagnosis;
                f.resolution = resolution.isEmpty() ? null : resolution;
                f.severity = selectedSeverity;
                f.status = "OPEN";
                f.technicianNotes = notes.isEmpty() ? null : notes;
                f.createdAt = now;
                f.updatedAt = now;
                targetFindingId = db.findingDao().insert(f);
            } else {
                // Update existing
                FindingEntity f = existingFinding;
                if (f != null) {
                    f.area = area;
                    f.component = component;
                    f.problem = problem;
                    f.diagnosis = diagnosis.isEmpty() ? null : diagnosis;
                    f.resolution = resolution.isEmpty() ? null : resolution;
                    f.severity = selectedSeverity;
                    f.technicianNotes = notes.isEmpty() ? null : notes;
                    f.updatedAt = now;
                    db.findingDao().update(f);
                }
            }

            // Save photos
            for (String path : photos) {
                FindingPhotoEntity photo = new FindingPhotoEntity();
                photo.findingId = targetFindingId;
                photo.filePath = path;
                photo.category = "CONTEXT";
                photo.timestamp = now;
                db.findingPhotoDao().insert(photo);
            }

            runOnUiThread(() -> {
                Toast.makeText(this, "Hallazgo guardado", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        });
    }

    private void launchCamera() {
        File photosDir = new File(getExternalFilesDir("fotos_hallazgos"), "");
        if (!photosDir.exists()) photosDir.mkdirs();
        try {
            tempPhotoFile = File.createTempFile("finding_", ".jpg", photosDir);
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", tempPhotoFile);
            cameraLauncher.launch(uri);
        } catch (IOException e) {
            Toast.makeText(this, "Error creando archivo de foto", Toast.LENGTH_SHORT).show();
        }
    }

    private void updatePhotoCount() {
        int count = addedPhotoPaths.size();
        tvPhotoCount.setText(count + (count == 1 ? " foto" : " fotos"));
    }
}
