package com.lc.technician;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.lc.technician.data.JobRepository;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.EquipmentEntity;
import com.lc.technician.model.EquipmentPhotoEntity;
import com.lc.technician.ocr.NameplateCandidates;
import java.util.concurrent.Executors;

/**
 * Shows detected nameplate fields to the technician for review.
 * On CONFIRM: writes non-null fields to Equipment and sets scanStatus=CONFIRMED.
 * On CANCEL: no changes to Equipment.
 *
 * L&C visual style: white background, red #EB1700 primary action.
 */
public class NameplateReviewActivity extends AppCompatActivity {

    public static final String EXTRA_EQUIPMENT_ID = "equipment_id";
    public static final String EXTRA_MANUFACTURER  = "np_manufacturer";
    public static final String EXTRA_MODEL         = "np_model";
    public static final String EXTRA_SERIAL        = "np_serial";
    public static final String EXTRA_TONS          = "np_tons";
    public static final String EXTRA_REFRIGERANT   = "np_refrigerant";
    public static final String EXTRA_VOLTAGE       = "np_voltage";
    public static final String EXTRA_PHASE         = "np_phase";
    public static final String EXTRA_SEER          = "np_seer";
    public static final String EXTRA_MANUF_DATE    = "np_manuf_date";
    public static final String EXTRA_RAW_TEXT      = "np_raw_text";
    public static final String EXTRA_PHOTO_PATH    = "np_photo_path";

    private long equipmentId;
    private EditText etMarca, etModelo, etSerial, etTons, etRefrigerante,
                     etVoltaje, etFase, etSeer, etManufDate;
    private TextView tvRawOcr;
    private LinearLayout sectionRaw;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nameplate_review);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        Intent intent = getIntent();
        equipmentId = intent.getLongExtra(EXTRA_EQUIPMENT_ID, -1);
        if (equipmentId == -1) {
            Toast.makeText(this, "Error: equipo no especificado", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        etMarca       = findViewById(R.id.etNpMarca);
        etModelo      = findViewById(R.id.etNpModelo);
        etSerial      = findViewById(R.id.etNpSerial);
        etTons        = findViewById(R.id.etNpTons);
        etRefrigerante = findViewById(R.id.etNpRefrigerante);
        etVoltaje     = findViewById(R.id.etNpVoltaje);
        etFase        = findViewById(R.id.etNpFase);
        etSeer        = findViewById(R.id.etNpSeer);
        etManufDate   = findViewById(R.id.etNpManufDate);
        tvRawOcr      = findViewById(R.id.tvRawOcr);
        sectionRaw    = findViewById(R.id.sectionRaw);

        // Pre-fill with detected values
        setField(etMarca,       intent.getStringExtra(EXTRA_MANUFACTURER));
        setField(etModelo,      intent.getStringExtra(EXTRA_MODEL));
        setField(etSerial,      intent.getStringExtra(EXTRA_SERIAL));
        setField(etTons,        intent.getStringExtra(EXTRA_TONS));
        setField(etRefrigerante, intent.getStringExtra(EXTRA_REFRIGERANT));
        setField(etVoltaje,     intent.getStringExtra(EXTRA_VOLTAGE));
        setField(etFase,        intent.getStringExtra(EXTRA_PHASE));
        setField(etSeer,        intent.getStringExtra(EXTRA_SEER));
        setField(etManufDate,   intent.getStringExtra(EXTRA_MANUF_DATE));

        String rawText = intent.getStringExtra(EXTRA_RAW_TEXT);
        tvRawOcr.setText(rawText != null ? rawText : "(sin texto)");

        // Collapsible raw OCR section
        TextView tvToggleRaw = findViewById(R.id.tvToggleRaw);
        sectionRaw.setVisibility(View.GONE);
        tvToggleRaw.setOnClickListener(v -> {
            if (sectionRaw.getVisibility() == View.VISIBLE) {
                sectionRaw.setVisibility(View.GONE);
                tvToggleRaw.setText("▶ Ver texto OCR completo");
            } else {
                sectionRaw.setVisibility(View.VISIBLE);
                tvToggleRaw.setText("▼ Ocultar texto OCR");
            }
        });

        Button btnCancel  = findViewById(R.id.btnNpCancelar);
        Button btnConfirm = findViewById(R.id.btnNpConfirmar);

        btnCancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        btnConfirm.setOnClickListener(v -> confirmar());
    }

    private void setField(EditText et, String value) {
        if (value != null && !value.isEmpty()) {
            et.setText(value);
        }
    }

    private void confirmar() {
        JobRepository.get().getEquipmentByIdAsync(equipmentId, eq -> {
            if (eq == null) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error: equipo no encontrado", Toast.LENGTH_SHORT).show();
                    finish();
                });
                return;
            }

            // Only overwrite fields where the technician actually entered something
            String marca = etMarca.getText().toString().trim();
            if (!marca.isEmpty()) eq.marca = marca;

            String modelo = etModelo.getText().toString().trim();
            if (!modelo.isEmpty()) eq.modelo = modelo;

            String serial = etSerial.getText().toString().trim();
            if (!serial.isEmpty()) eq.serial = serial;

            String tonsStr = etTons.getText().toString().trim();
            if (!tonsStr.isEmpty()) {
                try { eq.tons = Double.parseDouble(tonsStr); } catch (NumberFormatException ignored) {}
            }

            String ref = etRefrigerante.getText().toString().trim();
            if (!ref.isEmpty()) eq.refrigerante = ref;

            String voltaje = etVoltaje.getText().toString().trim();
            if (!voltaje.isEmpty()) eq.voltaje = voltaje;

            String fase = etFase.getText().toString().trim();
            if (!fase.isEmpty()) eq.fase = fase;

            String seerStr = etSeer.getText().toString().trim();
            if (!seerStr.isEmpty()) {
                try { eq.seer = Double.parseDouble(seerStr); } catch (NumberFormatException ignored) {}
            }

            String manufDate = etManufDate.getText().toString().trim();
            if (!manufDate.isEmpty()) eq.manufactureDate = manufDate;

            eq.scanStatus = "CONFIRMED";
            eq.updatedAt = System.currentTimeMillis();

            // Persist photo to equipment_photos on CONFIRM
            String photoPath = getIntent().getStringExtra(EXTRA_PHOTO_PATH);
            String rawOcrText = getIntent().getStringExtra(EXTRA_RAW_TEXT);
            if (photoPath != null && !photoPath.isEmpty()) {
                final long eqId = eq.id;
                Executors.newSingleThreadExecutor().execute(() -> {
                    EquipmentPhotoEntity photo = new EquipmentPhotoEntity();
                    photo.equipmentId = eqId;
                    photo.filePath = photoPath;
                    photo.timestamp = System.currentTimeMillis();
                    photo.rawOcrText = rawOcrText;
                    LcDatabase.getInstance(NameplateReviewActivity.this).equipmentPhotoDao().insert(photo);
                });
            }

            JobRepository.get().updateEquipmentAsync(eq, () -> runOnUiThread(() -> {
                setResult(RESULT_OK);
                finish();
            }));
        });
    }
}
