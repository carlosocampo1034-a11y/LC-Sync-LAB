package com.lc.technician;

import android.os.Bundle;
import android.graphics.Bitmap;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.lc.technician.data.JobRepository;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.SignatureEntity;
import com.lc.technician.ui.SignatureView;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SignatureActivity extends AppCompatActivity {

    public static final String EXTRA_JOB_ID      = "job_id";
    public static final String EXTRA_PROPOSAL_ID  = "proposal_id";
    public static final String EXTRA_OPCION       = "opcion";
    public static final String EXTRA_TOTAL        = "total";
    public static final String EXTRA_TITULO       = "titulo_opcion";

    private long jobId, proposalId;
    private String opcion, tituloOpcion;
    private double total;

    private SignatureView signaturePad;
    private EditText etNombreFirmante, etNotasFirma;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_signature);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        jobId       = getIntent().getLongExtra(EXTRA_JOB_ID, -1);
        proposalId  = getIntent().getLongExtra(EXTRA_PROPOSAL_ID, -1);
        opcion      = getIntent().getStringExtra(EXTRA_OPCION);
        total       = getIntent().getDoubleExtra(EXTRA_TOTAL, 0.0);
        tituloOpcion = getIntent().getStringExtra(EXTRA_TITULO);

        if (jobId < 0 || opcion == null) { finish(); return; }

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        signaturePad     = findViewById(R.id.signaturePad);
        etNombreFirmante = findViewById(R.id.etNombreFirmante);
        etNotasFirma     = findViewById(R.id.etNotasFirma);

        // Summary
        String titulo = tituloOpcion != null && !tituloOpcion.isEmpty() ? tituloOpcion : opcion;
        ((TextView) findViewById(R.id.tvResumenFirma))
                .setText("Acepto la propuesta: " + titulo);
        ((TextView) findViewById(R.id.tvTotalFirma))
                .setText(String.format(Locale.US, "Total: $%,.2f", total));

        // Timestamp under signature pad
        String ts = new SimpleDateFormat("d MMM yyyy · h:mm a", Locale.forLanguageTag("es"))
                .format(new Date());
        ((TextView) findViewById(R.id.tvTimestampFirma)).setText(ts);

        findViewById(R.id.btnLimpiar).setOnClickListener(v -> {
            signaturePad.limpiar();
            // Refresh timestamp on retry
            String tsNow = new SimpleDateFormat("d MMM yyyy · h:mm a", Locale.forLanguageTag("es"))
                    .format(new Date());
            ((TextView) findViewById(R.id.tvTimestampFirma)).setText(tsNow);
        });
        findViewById(R.id.btnGuardarFirma).setOnClickListener(v -> guardarFirma());
    }

    private void guardarFirma() {
        String nombre = etNombreFirmante.getText().toString().trim();
        if (nombre.isEmpty()) {
            Toast.makeText(this, "Ingresa el nombre del firmante", Toast.LENGTH_SHORT).show();
            return;
        }
        if (signaturePad.estaVacio()) {
            Toast.makeText(this, "Firma requerida", Toast.LENGTH_SHORT).show();
            return;
        }

        Button btnGuardar = findViewById(R.id.btnGuardarFirma);
        btnGuardar.setEnabled(false);
        btnGuardar.setText("Guardando...");

        Bitmap bmp = signaturePad.getBitmap();

        new Thread(() -> {
            try {
                // Save PNG
                File dir = new File(getExternalFilesDir(null), "firmas");
                dir.mkdirs();
                long ts = System.currentTimeMillis();
                File file = new File(dir, "firma_" + jobId + "_" + ts + ".png");
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }

                // Build entity
                SignatureEntity firma = new SignatureEntity();
                firma.proposalId    = proposalId;
                firma.jobId         = jobId;
                firma.nombreFirmante = nombre;
                firma.opcionAceptada = opcion;
                firma.totalAceptado  = total;
                firma.imagePath      = file.getAbsolutePath();
                firma.timestamp      = ts;
                String notas = etNotasFirma.getText().toString().trim();
                firma.notasFirma = notas.isEmpty() ? null : notas;

                JobRepository.get().guardarFirma(firma);
                if (proposalId > 0) {
                    JobRepository.get().aceptarOpcion(proposalId, jobId, opcion);
                    // FASE 14: Lock proposal after signature
                    try {
                        LcDatabase.getInstance(this).proposalDao().lockProposal(proposalId, ts);
                    } catch (Exception eLock) {
                        android.util.Log.e("SignatureActivity", "Error locking proposal", eLock);
                    }
                }

                runOnUiThread(() -> {
                    Toast.makeText(this, "Firma guardada. Propuesta aceptada.", Toast.LENGTH_LONG).show();
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnGuardar.setEnabled(true);
                    btnGuardar.setText("GUARDAR FIRMA");
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
