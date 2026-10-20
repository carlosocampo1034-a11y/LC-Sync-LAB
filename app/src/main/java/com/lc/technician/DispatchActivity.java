package com.lc.technician;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.data.JobRepository;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.JobEntity;
import com.lc.technician.model.TechnicianEntity;
import com.lc.technician.util.NotificationHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DispatchActivity extends AppCompatActivity {

    private DispatchAdapter adapter;
    private final List<JobEntity> jobs = new ArrayList<>();
    private TextView tvContador;
    private TextView tvEmpty;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dispatch);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        tvContador = findViewById(R.id.tvContador);
        tvEmpty    = findViewById(R.id.tvEmpty);

        RecyclerView recycler = findViewById(R.id.recyclerDispatch);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DispatchAdapter(jobs, this::abrirDetalle, this::mostrarDialogoAsignar);
        recycler.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        cargarJobs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void cargarJobs() {
        JobRepository.get().getJobsPendientesAsync(result -> {
            jobs.clear();
            jobs.addAll(result);
            adapter.notifyDataSetChanged();
            tvContador.setText(result.size() + " jobs");
            tvEmpty.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
        });
    }

    private void abrirDetalle(JobEntity job) {
        Intent i = new Intent(this, JobDetailActivity.class);
        i.putExtra("job_id", job.id);
        startActivity(i);
    }

    private void mostrarDialogoAsignar(JobEntity job) {
        String actor = SessionManager.get(this).getTechnicianName();
        executor.execute(() -> {
            List<TechnicianEntity> activeTechs =
                    LcDatabase.getInstance(this).technicianDao().getActive();

            // Fallback: if roster is empty, include current user + seed tech
            if (activeTechs.isEmpty()) {
                String currentUser = actor;
                TechnicianEntity fallback = new TechnicianEntity();
                fallback.id     = currentUser;
                fallback.nombre = currentUser;
                fallback.activo = true;
                activeTechs.add(fallback);
                if (!com.lc.technician.model.Technician.NOMBRE.equals(currentUser)) {
                    TechnicianEntity seed = new TechnicianEntity();
                    seed.id     = com.lc.technician.model.Technician.NOMBRE;
                    seed.nombre = com.lc.technician.model.Technician.NOMBRE;
                    seed.activo = true;
                    activeTechs.add(seed);
                }
            }

            final List<TechnicianEntity> finalTechs = activeTechs;
            List<String> nombres = new ArrayList<>();
            nombres.add("Sin asignar");
            for (TechnicianEntity t : finalTechs) nombres.add(t.nombre);

            mainHandler.post(() -> {
                new AlertDialog.Builder(this)
                    .setTitle("Asignar técnico")
                    .setItems(nombres.toArray(new CharSequence[0]), (dialog, which) -> {
                        if (which == 0) {
                            // Desasignar
                            JobRepository.get().unassignTechnician(job.id, actor, () -> {
                                job.technicianId = null;
                                adapter.notifyDataSetChanged();
                                Toast.makeText(this, "Desasignado", Toast.LENGTH_SHORT).show();
                            });
                        } else {
                            TechnicianEntity selected = finalTechs.get(which - 1);
                            JobRepository.get().assignTechnician(job.id, selected.nombre, actor, () -> {
                                job.technicianId = selected.nombre;
                                // FASE 22: Notificar al tecnico asignado
                                try {
                                    NotificationHelper.notificarJobAsignado(
                                        DispatchActivity.this,
                                        job.id,
                                        job.cliente != null ? job.cliente : "",
                                        job.hora != null ? job.hora : "",
                                        false
                                    );
                                } catch (Exception exNotif) {
                                    android.util.Log.e("Dispatch", "Error notificando", exNotif);
                                }
                                adapter.notifyDataSetChanged();
                                Toast.makeText(this, "Asignado a " + selected.nombre, Toast.LENGTH_SHORT).show();
                            });
                        }
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            });
        });
    }

    // ── Adapter ─────────────────────────────────────────────────────────────────

    static class DispatchAdapter extends RecyclerView.Adapter<DispatchAdapter.VH> {

        interface OnClick        { void onClick(JobEntity job); }
        interface OnAsignarClick { void onClick(JobEntity job); }

        private final List<JobEntity> data;
        private final OnClick        listener;
        private final OnAsignarClick asignarListener;

        DispatchAdapter(List<JobEntity> data, OnClick listener, OnAsignarClick asignarListener) {
            this.data            = data;
            this.listener        = listener;
            this.asignarListener = asignarListener;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_dispatch, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int pos) {
            JobEntity job = data.get(pos);
            h.tvCliente.setText(job.cliente != null ? job.cliente : "—");
            h.tvHora.setText(job.hora != null && !job.hora.isEmpty() ? job.hora : "Sin hora");
            h.tvDireccion.setText(job.direccion != null ? job.direccion : "");
            h.tvEstado.setText(job.estado != null ? job.estado : "");
            colorEstado(h.tvEstado, job.estado);

            if (job.technicianId != null && !job.technicianId.isEmpty()) {
                h.tvTecnico.setText("Técnico: " + job.technicianId);
                h.tvTecnico.setTextColor(0xFF1B71C9);
                h.btnAsignar.setText("Reasignar");
            } else {
                h.tvTecnico.setText("Sin asignar");
                h.tvTecnico.setTextColor(0xFF9A9A9A);
                h.btnAsignar.setText("Asignar");
            }

            h.itemView.setOnClickListener(v -> listener.onClick(job));
            h.btnAsignar.setOnClickListener(v -> asignarListener.onClick(job));
        }

        @Override public int getItemCount() { return data.size(); }

        private void colorEstado(TextView tv, String estado) {
            if (estado == null) return;
            int color;
            switch (estado) {
                case "PENDIENTE":   color = 0xFFE65100; break;
                case "EN RUTA":     color = 0xFF1565C0; break;
                default:            color = 0xFF757575; break;
            }
            tv.setTextColor(color);
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvCliente, tvHora, tvDireccion, tvEstado, tvTecnico, btnAsignar;
            VH(View v) {
                super(v);
                tvCliente   = v.findViewById(R.id.tvDispCliente);
                tvHora      = v.findViewById(R.id.tvDispHora);
                tvDireccion = v.findViewById(R.id.tvDispDireccion);
                tvEstado    = v.findViewById(R.id.tvDispEstado);
                tvTecnico   = v.findViewById(R.id.tvDispTecnico);
                btnAsignar  = v.findViewById(R.id.btnDispAsignar);
            }
        }
    }
}
