package com.lc.technician;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.data.JobRepository;
import com.lc.technician.model.Job;
import com.lc.technician.ui.adapter.JobAdapter;
import java.util.ArrayList;
import java.util.List;

public class JobCompletedActivity extends AppCompatActivity {

    private JobAdapter adapter;
    private final List<Job> completados = new ArrayList<>();
    private TextView tvContador;
    private TextView tvSinCompletados;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_job_completed);
        // Edge-to-edge (Android 15+): cabecera bajo la barra de estado, último trabajo visible sobre la navegación
        InsetsHelper.marginTop(findViewById(R.id.header));
        InsetsHelper.padBottom(findViewById(R.id.listaCompletados));

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        tvContador = findViewById(R.id.tvContadorCompletados);
        tvSinCompletados = findViewById(R.id.tvSinCompletados);

        RecyclerView lista = findViewById(R.id.listaCompletados);
        lista.setLayoutManager(new LinearLayoutManager(this));

        adapter = new JobAdapter(completados, job -> {
            Intent i = new Intent(this, JobDetailActivity.class);
            i.putExtra("job_id", job.id);
            startActivity(i);
        });
        lista.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        completados.clear();
        for (Job j : JobRepository.get().getTodayJobs()) {
            if (Job.esCompletado(j.estado)) completados.add(j);
        }
        adapter.notifyDataSetChanged();

        int n = completados.size();
        tvContador.setText(n + (n == 1 ? " trabajo completado" : " trabajos completados"));
        tvSinCompletados.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
    }
}
