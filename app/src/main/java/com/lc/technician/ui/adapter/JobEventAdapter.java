package com.lc.technician.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.R;
import com.lc.technician.model.JobEventEntity;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class JobEventAdapter extends RecyclerView.Adapter<JobEventAdapter.VH> {

    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("d MMM\nh:mm a", Locale.forLanguageTag("es"));

    private final List<JobEventEntity> events;

    public JobEventAdapter(List<JobEventEntity> events) {
        this.events = events;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_job_event, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        JobEventEntity ev = events.get(position);
        h.timestamp.setText(FMT.format(new Date(ev.timestamp)));
        h.descripcion.setText(ev.descripcion);
    }

    @Override public int getItemCount() { return events.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView timestamp, descripcion;
        VH(@NonNull View v) {
            super(v);
            timestamp   = v.findViewById(R.id.eventTimestamp);
            descripcion = v.findViewById(R.id.eventDescripcion);
        }
    }
}
