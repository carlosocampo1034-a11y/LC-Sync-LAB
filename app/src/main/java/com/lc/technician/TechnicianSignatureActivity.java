package com.lc.technician;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.data.SignatureDao;
import com.lc.technician.model.SignatureEntity;
import com.lc.technician.ui.SignatureView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.Executors;

public class TechnicianSignatureActivity extends AppCompatActivity {

    public static final String EXTRA_JOB_ID = "jobId";
    private SignatureView signatureView;
    private EditText etSignerName;
    private TextView signHint;
    private long jobId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_technician_signature);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        jobId = getIntent().getLongExtra(EXTRA_JOB_ID, -1);
        if (jobId < 0) {
            Toast.makeText(this, "Error: jobId invalido", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        signatureView = findViewById(R.id.signatureView);
        etSignerName = findViewById(R.id.etSignerName);
        signHint = findViewById(R.id.signHint);
        Button btnClear = findViewById(R.id.btnClear);
        Button btnSave = findViewById(R.id.btnSaveSignature);

        btnClear.setOnClickListener(v -> {
            signatureView.limpiar();
            if (signHint != null) signHint.setVisibility(View.VISIBLE);
        });

        signatureView.setOnTouchListener((v, event) -> {
            if (signHint != null) signHint.setVisibility(View.GONE);
            v.onTouchEvent(event);
            return true;
        });

        btnSave.setOnClickListener(v -> saveSignature());
    }

    private void saveSignature() {
        if (signatureView.estaVacio()) {
            Toast.makeText(this, "Por favor dibuje la firma primero", Toast.LENGTH_SHORT).show();
            return;
        }

        String signerName = etSignerName.getText().toString().trim();
        if (signerName.isEmpty()) signerName = "Tecnico";

        Bitmap bmp = signatureView.getBitmap();
        String finalName = signerName;

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                File dir = new File(getFilesDir(), "signatures");
                dir.mkdirs();
                File file = new File(dir, "techsig_job" + jobId + "_" + System.currentTimeMillis() + ".png");
                FileOutputStream fos = new FileOutputStream(file);
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();

                // Save as SignatureEntity with type SIGNATURE_TECHNICIAN
                SignatureDao dao = LcDatabase.getInstance(this).signatureDao();
                SignatureEntity sig = new SignatureEntity();
                sig.jobId = jobId;
                sig.proposalId = -1L;
                sig.nombreFirmante = finalName;
                sig.opcionAceptada = "TECHNICIAN_SIGN";
                sig.totalAceptado = 0.0;
                sig.imagePath = file.getAbsolutePath();
                sig.timestamp = System.currentTimeMillis();
                sig.notasFirma = "Firma del tecnico: " + finalName;
                boolean lab = com.lc.technician.sync.LabSession.activa(this);
                if (lab) sig.syncState = 0;          // sync v2: la firma viaja al servidor como evidencia
                dao.insert(sig);
                if (lab) com.lc.technician.data.JobRepository.get().solicitarSyncInmediata();

                runOnUiThread(() -> {
                    Toast.makeText(this, "\u2705 Firma del t\u00e9cnico guardada", Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                    Toast.makeText(this, "Error al guardar: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }
}
