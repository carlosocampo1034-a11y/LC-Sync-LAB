package com.lc.technician;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.data.JobRepository;
import com.lc.technician.model.AuditLogEntity;
import com.lc.technician.ui.adapter.AuditLogAdapter;
import java.util.ArrayList;
import java.util.List;

public class AuditLogActivity extends AppCompatActivity {

    public static final String EXTRA_JOB_ID = "job_id";

    private long jobId;
    private RecyclerView rv;
    private TextView tvConteo, tvSin;
    private boolean filtrarSoloJobs = false;
    private TextView chipTodos, chipJobs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audit_log);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        jobId    = getIntent().getLongExtra(EXTRA_JOB_ID, -1);
        rv       = findViewById(R.id.recyclerAudit);
        tvConteo = findViewById(R.id.tvConteo);
        tvSin    = findViewById(R.id.tvSinAudit);
        chipTodos = findViewById(R.id.chipTodos);
        chipJobs  = findViewById(R.id.chipJobs);

        if (chipTodos != null) {
            chipTodos.setOnClickListener(v -> { filtrarSoloJobs = false; actualizarChips(); cargar(); });
            chipJobs.setOnClickListener(v  -> { filtrarSoloJobs = true;  actualizarChips(); cargar(); });
            actualizarChips();
        }

        rv.setLayoutManager(new LinearLayoutManager(this));
    }

    @Override
    protected void onResume() {
        super.onResume();
        cargar();
    }

    private void actualizarChips() {
        if (chipTodos == null) return;
        chipTodos.setBackgroundResource(filtrarSoloJobs ? R.drawable.bg_chip_unselected : R.drawable.bg_chip_selected);
        chipJobs.setBackgroundResource(filtrarSoloJobs  ? R.drawable.bg_chip_selected   : R.drawable.bg_chip_unselected);
        chipTodos.setTextColor(filtrarSoloJobs ? 0xFF666666 : 0xFFFFFFFF);
        chipJobs.setTextColor(filtrarSoloJobs  ? 0xFFFFFFFF : 0xFF666666);
    }

    private void cargar() {
        if (jobId >= 0) {
            JobRepository.get().getAuditForJobAsync(jobId, this::mostrar);
        } else if (filtrarSoloJobs) {
            JobRepository.get().getAllAuditAsync(all -> {
                List<AuditLogEntity> filtered = new ArrayList<>();
                for (AuditLogEntity e : all) { if (e.jobId > 0) filtered.add(e); }
                mostrar(filtered);
            });
        } else {
            JobRepository.get().getAllAuditAsync(this::mostrar);
        }
    }

    private void mostrar(List<AuditLogEntity> entries) {
        tvConteo.setText(entries.size() + " entradas");
        if (entries.isEmpty()) {
            tvSin.setVisibility(View.VISIBLE);
            rv.setVisibility(View.GONE);
        } else {
            tvSin.setVisibility(View.GONE);
            rv.setVisibility(View.VISIBLE);
            rv.setAdapter(new AuditLogAdapter(entries));
        }
    }
}
