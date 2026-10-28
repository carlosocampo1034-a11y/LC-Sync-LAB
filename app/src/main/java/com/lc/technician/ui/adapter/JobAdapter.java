package com.lc.technician.ui.adapter;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.R;
import com.lc.technician.model.Job;
import java.util.List;

public class JobAdapter extends RecyclerView.Adapter<JobAdapter.VH> {

    public interface OnJobClick { void onJobClick(Job job); }

    private final List<Job> jobs;
    private final OnJobClick listener;
    private String currentTechId = null;

    public JobAdapter(List<Job> jobs, OnJobClick listener) {
        this.jobs = jobs;
        this.listener = listener;
    }

    public void setCurrentTechId(String techId) {
        this.currentTechId = techId;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_job, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final Job job = jobs.get(position);
        h.cliente.setText(job.cliente);
        h.direccion.setText(job.direccion);
        h.hora.setText(job.horaConFecha());
        h.descripcion.setText(job.descripcion);
        h.estado.setText(com.lc.technician.sync.Listas.etiqueta(h.estado.getContext(), "order_states.list", job.estado, job.estado));

        int color;
        switch (job.estado != null ? job.estado : "") {
            case Job.STATUS_DONE:               color = 0xFF137333; break;  // Verde
            case Job.STATUS_ARRIVED:            color = 0xFF171717; break;  // Azul oscuro
            case Job.STATUS_EN_ROUTE:           color = 0xFF171717; break;  // Azul claro
            case Job.STATUS_EN_REPARACION:      color = 0xFFE91605; break;  // Naranja
            case Job.STATUS_PART_HOLD:          color = 0xFF62666A; break;  // Ámbar
            case Job.STATUS_AWAITING_APPROVAL:  color = 0xFF171717; break;  // Azul oscuro
            case Job.STATUS_ON_HOLD:            color = 0xFF62666A; break;  // Ámbar
            default:                            color = 0xFF62666A; break;  // Naranja suave (PENDIENTE)
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(6f);
        h.estado.setBackground(bg);

        // Badge "OTRO TÉCNICO": visible cuando el job pertenece a otro técnico y hay sesión activa
        if (h.otroTecnico != null) {
            boolean esMio = currentTechId == null || currentTechId.isEmpty()
                    || job.technicianId == null || job.technicianId.isEmpty()
                    || job.technicianId.equals(currentTechId);
            h.otroTecnico.setVisibility(esMio ? View.GONE : View.VISIBLE);
        }

        h.itemView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (listener != null) listener.onJobClick(job);
            }
        });
    }

    @Override public int getItemCount() { return jobs.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView cliente, direccion, hora, descripcion, estado, otroTecnico;
        VH(@NonNull View v) {
            super(v);
            cliente     = v.findViewById(R.id.cliente);
            direccion   = v.findViewById(R.id.direccion);
            hora        = v.findViewById(R.id.hora);
            descripcion = v.findViewById(R.id.descripcion);
            estado      = v.findViewById(R.id.estado);
            otroTecnico = v.findViewById(R.id.tvOtroTecnico);
        }
    }
}
