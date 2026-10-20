package com.lc.technician;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.MembershipCardEntity;
import com.lc.technician.model.MembershipCheckInEntity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MembershipScanActivity extends AppCompatActivity {

    public static final String EXTRA_CARD_NUMBER = "card_number";

    private PreviewView previewView;
    private ExecutorService cameraExecutor;
    private final AtomicBoolean resultSent = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_membership_scan);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        previewView = findViewById(R.id.previewView);
        ImageView btnCerrar = findViewById(R.id.btnCerrar);
        Button btnManual = findViewById(R.id.btnManual);

        btnCerrar.setOnClickListener(v -> finish());
        btnManual.setOnClickListener(v -> mostrarDialogoManual());

        cameraExecutor = Executors.newSingleThreadExecutor();
        startCamera();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                BarcodeScannerOptions opts = new BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(
                                Barcode.FORMAT_CODE_128,
                                Barcode.FORMAT_CODE_39,
                                Barcode.FORMAT_QR_CODE,
                                Barcode.FORMAT_EAN_13,
                                Barcode.FORMAT_EAN_8,
                                Barcode.FORMAT_UPC_A,
                                Barcode.FORMAT_UPC_E,
                                Barcode.FORMAT_DATA_MATRIX)
                        .build();
                BarcodeScanner scanner = BarcodeScanning.getClient(opts);

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                analysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (resultSent.get()) { imageProxy.close(); return; }
                    processImage(imageProxy, scanner);
                });

                provider.unbindAll();
                provider.bindToLifecycle(this,
                        CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Error de cámara: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @androidx.camera.core.ExperimentalGetImage
    @SuppressWarnings("UnsafeOptInUsageError")
    private void processImage(ImageProxy imageProxy, BarcodeScanner scanner) {
        android.media.Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) { imageProxy.close(); return; }
        InputImage image = InputImage.fromMediaImage(
                mediaImage, imageProxy.getImageInfo().getRotationDegrees());
        scanner.process(image)
                .addOnSuccessListener(barcodes -> {
                    for (Barcode bc : barcodes) {
                        String value = bc.getRawValue();
                        if (value != null && !value.isEmpty()) {
                            if (resultSent.compareAndSet(false, true)) {
                                runOnUiThread(() -> devolverResultado(value));
                            }
                            break;
                        }
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void devolverResultado(String cardNumber) {
        Toast.makeText(this, "Código: " + cardNumber, Toast.LENGTH_SHORT).show();

        // Auto check-in: register check-in on QR scan (Fase 42)
        registrarCheckInAutomatico(cardNumber);

        Intent result = new Intent();
        result.putExtra(EXTRA_CARD_NUMBER, cardNumber);
        setResult(RESULT_OK, result);
        finish();
    }

    /**
     * Fase 42: Auto check-in from QR scan.
     * Looks up the card by cardNumber and creates a MembershipCheckInEntity.
     * Runs on a background thread to avoid blocking the UI.
     */
    private void registrarCheckInAutomatico(String cardNumber) {
        new Thread(() -> {
            try {
                LcDatabase db = LcDatabase.getInstance(getApplicationContext());
                MembershipCardEntity card = db.membershipCardDao().getByCardNumber(cardNumber);
                if (card == null) {
                    android.util.Log.w("MembershipScan", "Auto check-in: tarjeta no encontrada: " + cardNumber);
                    return;
                }

                // Get jobId from Intent extra if present (when launched from JobDetailActivity)
                Long jobId = null;
                Intent launchIntent = getIntent();
                if (launchIntent != null && launchIntent.hasExtra("job_id")) {
                    jobId = launchIntent.getLongExtra("job_id", 0);
                    if (jobId == 0) jobId = null;
                }

                // Get technician name from session
                String techName = SessionManager.get(getApplicationContext()).getTechnicianName();
                if (techName == null || techName.isEmpty()) techName = "Técnico";

                long now = System.currentTimeMillis();
                MembershipCheckInEntity checkIn = new MembershipCheckInEntity();
                checkIn.cardId = card.id;
                checkIn.jobId = jobId;
                checkIn.checkInDate = now;
                checkIn.technicianName = techName;
                checkIn.notes = "Check-in desde QR";
                checkIn.createdAt = now;

                db.membershipCheckInDao().insert(checkIn);
                android.util.Log.d("MembershipScan", "Auto check-in registrado para tarjeta: " + cardNumber);

                // Also log to AuditLog
                try {
                    com.lc.technician.model.AuditLogEntity audit = new com.lc.technician.model.AuditLogEntity();
                    audit.action = "CHECK_IN";
                    audit.entityType = "membership_card";
                    audit.entityId = card.id;
                    audit.actor = techName;
                    audit.extra = "Check-in QR: " + cardNumber;
                    audit.timestamp = now;
                    db.auditLogDao().insert(audit);
                } catch (Exception eAudit) {
                    android.util.Log.w("MembershipScan", "AuditLog no disponible: " + eAudit.getMessage());
                }
            } catch (Exception e) {
                android.util.Log.e("MembershipScan", "Error en auto check-in", e);
            }
        }).start();
    }

    private void mostrarDialogoManual() {
        EditText et = new EditText(this);
        et.setHint("Ej: LC-GOLD-000123");
        et.setPadding(48, 24, 48, 24);
        new AlertDialog.Builder(this)
                .setTitle("Ingresar código")
                .setView(et)
                .setPositiveButton("Buscar", (d, w) -> {
                    String txt = et.getText().toString().trim();
                    if (!txt.isEmpty()) devolverResultado(txt);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
