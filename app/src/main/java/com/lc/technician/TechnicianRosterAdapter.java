package com.lc.technician;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.model.TechnicianEntity;
import java.util.Locale;
import java.util.List;

public class TechnicianRosterAdapter extends RecyclerView.Adapter<TechnicianRosterAdapter.VH> {

    public interface OnLongPressListener {
        void onLongPress(TechnicianEntity tech);
    }

    private final List<TechnicianEntity> data;
    private final OnLongPressListener longPressListener;

    public TechnicianRosterAdapter(List<TechnicianEntity> data, OnLongPressListener longPressListener) {
        this.data = data;
        this.longPressListener = longPressListener;
    }

    @Override
    public VH onCreateViewHolder(ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_technician, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(VH h, int pos) {
        TechnicianEntity tech = data.get(pos);
        String nombre = tech.nombre != null ? tech.nombre : "—";
        h.tvNombre.setText(nombre);
        h.tvAvatar.setText(nombre.isEmpty() ? "?" : nombre.substring(0, 1).toUpperCase());
        h.tvRol.setText(tech.rol != null ? tech.rol : "TECNICO");

        String status = tech.status;
        if (status == null) status = tech.activo ? TechnicianEntity.STATUS_ACTIVE : TechnicianEntity.STATUS_INACTIVE;
        switch (status) {
            case TechnicianEntity.STATUS_ON_ROUTE:
                h.tvEstado.setText("En ruta");
                h.tvEstado.setBackgroundColor(0xFF171717);
                break;
            case TechnicianEntity.STATUS_INACTIVE:
                h.tvEstado.setText("Inactivo");
                h.tvEstado.setBackgroundColor(0xFF9A9A9A);
                break;
            default:
                h.tvEstado.setText("Activo");
                h.tvEstado.setBackgroundColor(0xFF171717);
        }
        String meta = String.format(java.util.Locale.US, "%d trabajos · %.1f★",
                (int) tech.jobsCompleted, tech.rating == 0 ? 5.0 : tech.rating);
        h.tvRol.setText((tech.rol != null ? tech.rol : "TECNICO") + " · " + meta);

        h.itemView.setOnLongClickListener(v -> {
            if (longPressListener != null) longPressListener.onLongPress(tech);
            return true;
        });
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvAvatar, tvNombre, tvRol, tvEstado;
        VH(View v) {
            super(v);
            tvAvatar  = v.findViewById(R.id.tvAvatar);
            tvNombre  = v.findViewById(R.id.tvNombre);
            tvRol     = v.findViewById(R.id.tvRol);
            tvEstado  = v.findViewById(R.id.tvEstado);
        }
    }
}
