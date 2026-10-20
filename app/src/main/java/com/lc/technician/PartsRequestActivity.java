package com.lc.technician;

import android.os.Bundle;
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

import com.lc.technician.data.LcDatabase;
import com.lc.technician.data.PartDao;
import com.lc.technician.data.PartsRequestDao;
import com.lc.technician.data.StockMovementDao;
import com.lc.technician.model.PartsRequestEntity;
import com.lc.technician.model.StockMovementEntity;
import com.lc.technician.util.NotificationHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PartsRequestActivity extends AppCompatActivity {

    private PartsRequestDao dao;
    private RecyclerView rvRequests;
    private TextView tvEmpty, tvResumen;
    private final List<PartsRequestEntity> lista = new ArrayList<>();
    private RequestAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parts_requests);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        dao = LcDatabase.getInstance(this).partsRequestDao();

        ImageView btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        tvResumen = findViewById(R.id.tvResumen);
        tvEmpty = findViewById(R.id.tvEmpty);
        rvRequests = findViewById(R.id.rvRequests);
        rvRequests.setLayoutManager(new LinearLayoutManager(this));

        adapter = new RequestAdapter();
        rvRequests.setAdapter(adapter);

        findViewById(R.id.btnNueva).setOnClickListener(v -> {
            Toast.makeText(this, "Las solicitudes se crean desde el detalle del Job", Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        cargarSolicitudes();
    }

    private void cargarSolicitudes() {
        new Thread(() -> {
            List<PartsRequestEntity> data = dao.getAll();
            int pendientes = dao.countPending();
            runOnUiThread(() -> {
                lista.clear();
                lista.addAll(data);
                adapter.notifyDataSetChanged();
                tvResumen.setText(data.size() + " solicitudes · " + pendientes + " pendientes");
                if (data.isEmpty()) {
                    rvRequests.setVisibility(View.GONE);
                    tvEmpty.setVisibility(View.VISIBLE);
                } else {
                    rvRequests.setVisibility(View.VISIBLE);
                    tvEmpty.setVisibility(View.GONE);
                }
            });
        }).start();
    }

    private void mostrarDialogoAprobacion(PartsRequestEntity req) {
        if (!PartsRequestEntity.STATUS_PENDING.equals(req.status)) {
            Toast.makeText(this, "Esta solicitud ya fue procesada", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Solicitud #" + req.id)
            .setMessage("Estado: " + req.status + "\nTotal estimado: $" + String.format(Locale.US, "%.2f", req.totalEstimated))
            .setPositiveButton("Aprobar", (d, w) -> {
                req.status = PartsRequestEntity.STATUS_APPROVED;
                req.approvedAt = System.currentTimeMillis();
                req.updatedAt = System.currentTimeMillis();
                new Thread(() -> {
                    dao.update(req);
                    // FASE 22: Notificar solicitud aprobada
                    try {
                        NotificationHelper.notificarSolicitudResuelta(PartsRequestActivity.this, req.id, "APROBADA", "Job #" + req.jobId);
                    } catch (Exception exNotif) {
                        android.util.Log.e("PartsReq", "Error notificando", exNotif);
                    }
                    // FASE 13: Generar StockMovement OUT por cada item aprobado
                    try {
                        LcDatabase db = LcDatabase.getInstance(this);
                        StockMovementDao smDao = db.stockMovementDao();
                        PartDao partDao = db.partDao();
                        if (req.itemsJson != null && !req.itemsJson.isEmpty()) {
                            JSONArray arr = new JSONArray(req.itemsJson);
                            long now = System.currentTimeMillis();
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject item = arr.getJSONObject(i);
                                long partId = item.getLong("partId");
                                int qty = item.getInt("qty");
                                StockMovementEntity sm = new StockMovementEntity();
                                sm.partId = partId;
                                sm.locationId = 1;
                                sm.movementType = StockMovementEntity.TYPE_OUT;
                                sm.quantity = -qty;
                                sm.referenceType = "REQUEST";
                                sm.referenceId = req.id;
                                sm.notes = "Aprobación solicitud #" + req.id;
                                smDao.insert(sm);
                                partDao.decrementStock(partId, qty, now);
                            }
                        }
                    } catch (Exception e) {
                        android.util.Log.e("PartsReq", "Error generando OUT al aprobar", e);
                    }
                    runOnUiThread(this::cargarSolicitudes);
                }).start();
            })
            .setNeutralButton("Rechazar", (d, w) -> {
                req.status = PartsRequestEntity.STATUS_REJECTED;
                req.rejectionReason = "Rechazado por admin";
                req.updatedAt = System.currentTimeMillis();
                new Thread(() -> {
                    dao.update(req);
                    // FASE 22: Notificar solicitud rechazada
                    try {
                        NotificationHelper.notificarSolicitudResuelta(PartsRequestActivity.this, req.id, "RECHAZADA", "Job #" + req.jobId);
                    } catch (Exception exNotif) {
                        android.util.Log.e("PartsReq", "Error notificando", exNotif);
                    }
                    runOnUiThread(this::cargarSolicitudes);
                }).start();
            })
            .setNegativeButton("Cerrar", null)
            .show();
    }

    private class RequestAdapter extends RecyclerView.Adapter<RequestAdapter.VH> {
        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_parts_request, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            PartsRequestEntity r = lista.get(position);
            h.tvTitle.setText("Solicitud #" + r.id + " · Job #" + r.jobId);
            String fecha = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(new Date(r.createdAt));
            h.tvDetail.setText("Creada: " + fecha + " · Técnico #" + r.technicianId);
            h.tvTotal.setText("$" + String.format(Locale.US, "%.2f", r.totalEstimated));
            h.tvStatus.setText(r.status);
            int color;
            switch (r.status) {
                case PartsRequestEntity.STATUS_APPROVED: color = 0xFF4CAF50; break;
                case PartsRequestEntity.STATUS_REJECTED: color = 0xFFF44336; break;
                case PartsRequestEntity.STATUS_FULFILLED: color = 0xFF2196F3; break;
                case PartsRequestEntity.STATUS_CANCELLED: color = 0xFF9E9E9E; break;
                default: color = 0xFFFF9800;
            }
            h.tvStatus.setBackgroundColor(color);
            h.itemView.setOnClickListener(v -> mostrarDialogoAprobacion(r));
        }

        @Override
        public int getItemCount() { return lista.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvTitle, tvDetail, tvTotal, tvStatus;
            VH(View v) {
                super(v);
                tvTitle  = v.findViewById(R.id.tvReqTitle);
                tvDetail = v.findViewById(R.id.tvReqDetail);
                tvTotal  = v.findViewById(R.id.tvReqTotal);
                tvStatus = v.findViewById(R.id.tvReqStatus);
            }
        }
    }
}
