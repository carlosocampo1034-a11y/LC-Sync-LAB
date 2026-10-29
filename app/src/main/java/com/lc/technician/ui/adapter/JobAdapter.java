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
        // Las órdenes cerradas ya no guardan datos del cliente en el teléfono (PrivacidadLocal): se muestra el número
        boolean sinCliente = job.cliente == null || job.cliente.isEmpty();
        h.cliente.setText(sinCliente ? "Orden Nº " + job.id + " · cerrada" : job.cliente);
        h.cliente.setTextColor(sinCliente ? 0xFF62666A : 0xFF171717);
        h.direccion.setText(job.direccion);
        h.idOrden.setText("#" + job.id);
        // Miniatura: primera foto de evidencia de la orden (dato real); sin fotos, no se muestra
        String foto = primeraFoto(job.id);
        if (foto != null) {
            android.graphics.Bitmap bm = miniatura(foto);
            if (bm != null) { h.miniatura.setImageBitmap(bm); h.miniatura.setVisibility(View.VISIBLE); }
            else h.miniatura.setVisibility(View.GONE);
        } else h.miniatura.setVisibility(View.GONE);
        h.hora.setText(job.horaConFecha());
        h.descripcion.setText(job.descripcion);
        boolean hayEq = job.equipo != null && !job.equipo.trim().isEmpty();
        boolean hayPr = job.prioridad != null && !job.prioridad.trim().isEmpty() && !"NORMAL".equalsIgnoreCase(job.prioridad.trim());
        h.filaTags.setVisibility(hayEq || hayPr ? View.VISIBLE : View.GONE);
        h.tagEquipo.setVisibility(hayEq ? View.VISIBLE : View.GONE);
        if (hayEq) h.tagEquipo.setText(job.equipo.trim());
        h.tagPrioridad.setVisibility(hayPr ? View.VISIBLE : View.GONE);
        if (hayPr) h.tagPrioridad.setText("Prioridad " + job.prioridad.trim().toLowerCase(java.util.Locale.ROOT));
        h.estado.setText(com.lc.technician.sync.Listas.etiqueta(h.estado.getContext(), "order_states.list", job.estado, job.estado));

        int color;
        switch (job.estado != null ? job.estado : "") {
            case Job.STATUS_DONE:               color = 0xFF16A34A; break;
            case Job.STATUS_ARRIVED:            color = 0xFF171717; break;
            case Job.STATUS_EN_ROUTE:           color = 0xFFF59E0B; break;
            case Job.STATUS_EN_REPARACION:      color = 0xFFE91605; break;
            case Job.STATUS_PART_HOLD:          color = 0xFF62666A; break;
            case Job.STATUS_AWAITING_APPROVAL:  color = 0xFF171717; break;
            case Job.STATUS_ON_HOLD:            color = 0xFF62666A; break;
            default:                            color = 0xFF62666A; break;
        }

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(60f);
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

    private final java.util.Map<Long, String> cacheFoto = new java.util.HashMap<>();
    private final java.util.Map<String, android.graphics.Bitmap> cacheBmp = new java.util.HashMap<>();

    private String primeraFoto(long jobId) {
        if (cacheFoto.containsKey(jobId)) return cacheFoto.get(jobId);
        String path = null;
        try {
            java.util.List<com.lc.technician.model.PhotoEntity> l = com.lc.technician.data.JobRepository.get().getPhotosForJob(jobId);
            for (com.lc.technician.model.PhotoEntity p : l) {
                if (p.path != null && new java.io.File(p.path).exists()) { path = p.path; break; }
            }
        } catch (Exception ignored) { }
        cacheFoto.put(jobId, path);
        return path;
    }

    private android.graphics.Bitmap miniatura(String path) {
        android.graphics.Bitmap b = cacheBmp.get(path);
        if (b != null) return b;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, o);
            int ss = 1;
            while (o.outWidth / (ss * 2) >= 160) ss *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = ss;
            b = android.graphics.BitmapFactory.decodeFile(path, o);
            if (b != null) cacheBmp.put(path, b);
        } catch (Exception ignored) { }
        return b;
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView cliente, direccion, hora, descripcion, estado, otroTecnico, tagEquipo, tagPrioridad, idOrden;
        final View filaTags;
        final android.widget.ImageView miniatura;
        VH(@NonNull View v) {
            super(v);
            cliente     = v.findViewById(R.id.cliente);
            direccion   = v.findViewById(R.id.direccion);
            hora        = v.findViewById(R.id.hora);
            idOrden     = v.findViewById(R.id.idOrden);
            descripcion = v.findViewById(R.id.descripcion);
            estado      = v.findViewById(R.id.estado);
            otroTecnico = v.findViewById(R.id.tvOtroTecnico);
            tagEquipo   = v.findViewById(R.id.tagEquipo);
            tagPrioridad = v.findViewById(R.id.tagPrioridad);
            filaTags    = v.findViewById(R.id.filaTags);
            miniatura   = v.findViewById(R.id.miniatura);
        }
    }
}
