package com.lc.technician.ui.adapter;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.R;
import com.lc.technician.model.AuditLogEntity;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class AuditLogAdapter extends RecyclerView.Adapter<AuditLogAdapter.VH> {

    private static final SimpleDateFormat FMT_DATE =
            new SimpleDateFormat("d MMM", Locale.forLanguageTag("es"));
    private static final SimpleDateFormat FMT_TIME =
            new SimpleDateFormat("h:mm a", Locale.forLanguageTag("es"));

    private final List<AuditLogEntity> entries;

    public AuditLogAdapter(List<AuditLogEntity> entries) {
        this.entries = entries;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_audit, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        h.bind(entries.get(pos));
    }

    @Override public int getItemCount() { return entries.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvTimestamp, tvActionChip, tvEntityField, tvValues, tvJobRef;

        VH(View v) {
            super(v);
            tvTimestamp   = v.findViewById(R.id.tvTimestamp);
            tvActionChip  = v.findViewById(R.id.tvActionChip);
            tvEntityField = v.findViewById(R.id.tvEntityField);
            tvValues      = v.findViewById(R.id.tvValues);
            tvJobRef      = v.findViewById(R.id.tvJobRef);
        }

        void bind(AuditLogEntity e) {
            Date d = new Date(e.timestamp);
            tvTimestamp.setText(FMT_DATE.format(d) + "\n" + FMT_TIME.format(d));

            // Action chip color
            int chipColor;
            switch (e.action != null ? e.action : "") {
                case "CREATE": chipColor = 0xFF171717; break;
                case "DELETE": chipColor = 0xFFE91605; break;
                default:       chipColor = 0xFF62666A; break; // UPDATE
            }
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(12f);
            bg.setColor(chipColor);
            tvActionChip.setBackground(bg);
            tvActionChip.setText(e.action != null ? e.action : "—");

            // Entity · field
            String entity = e.entityType != null ? e.entityType : "—";
            String field  = (e.field != null && !e.field.isEmpty()) ? " · " + e.field : "";
            tvEntityField.setText(entity + field);

            // Values
            boolean hasOld = e.oldValue != null && !e.oldValue.isEmpty();
            boolean hasNew = e.newValue != null && !e.newValue.isEmpty();
            if (hasOld && hasNew) {
                tvValues.setText(e.oldValue + " → " + e.newValue);
                tvValues.setVisibility(View.VISIBLE);
            } else if (hasNew) {
                tvValues.setText(e.newValue);
                tvValues.setVisibility(View.VISIBLE);
            } else if (hasOld) {
                tvValues.setText(e.oldValue);
                tvValues.setVisibility(View.VISIBLE);
            } else {
                tvValues.setVisibility(View.GONE);
            }

            // Job ref
            if (e.jobId > 0) {
                tvJobRef.setText("Trabajo #" + e.jobId);
                tvJobRef.setVisibility(View.VISIBLE);
            } else {
                tvJobRef.setVisibility(View.GONE);
            }
        }
    }
}
