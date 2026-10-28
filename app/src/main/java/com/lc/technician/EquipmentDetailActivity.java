package com.lc.technician;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.EquipmentEntity;
import com.lc.technician.model.EquipmentServiceEntity;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class EquipmentDetailActivity extends AppCompatActivity {
    public static final String EXTRA_EQUIPMENT_ID = "equipment_id";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long equipmentId;
    private EquipmentEntity equipment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_equipment_detail);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));
        equipmentId = getIntent().getLongExtra(EXTRA_EQUIPMENT_ID, -1);
        if (equipmentId == -1) { finish(); return; }
        findViewById(R.id.btnAtras).setOnClickListener(v -> finish());
        findViewById(R.id.btnRegistrarServicio).setOnClickListener(v -> mostrarDialogoServicio());
        cargarDatos();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cargarDatos();
    }

    private void cargarDatos() {
        executor.execute(() -> {
            LcDatabase db = LcDatabase.getInstance(this);
            EquipmentEntity eq = db.equipmentDao().getById(equipmentId);
            List<EquipmentServiceEntity> servicios = db.equipmentServiceDao().getForEquipment(equipmentId);
            mainHandler.post(() -> mostrarDatos(eq, servicios));
        });
    }

    private void mostrarDatos(EquipmentEntity eq, List<EquipmentServiceEntity> servicios) {
        if (eq == null) { finish(); return; }
        equipment = eq;
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd/yyyy", Locale.US);

        ((TextView) findViewById(R.id.tvEqNombre)).setText(eq.nombre != null ? eq.nombre : "—");
        String marcaModelo = (eq.marca != null ? eq.marca : "—") + " " + (eq.modelo != null ? eq.modelo : "");
        ((TextView) findViewById(R.id.tvEqMarca)).setText(marcaModelo.trim());
        ((TextView) findViewById(R.id.tvEqSerial)).setText("Serial: " + (eq.serial != null ? eq.serial : "—"));
        ((TextView) findViewById(R.id.tvEqTipo)).setText("Tipo: " + (eq.tipo != null ? eq.tipo : "—"));

        String estado = eq.estado != null ? eq.estado : "ACTIVO";
        TextView tvEstado = findViewById(R.id.tvEqEstado);
        tvEstado.setText(estado);
        int color;
        switch (estado) {
            case "REPARACION": color = 0xFF62666A; break;
            case "REEMPLAZO":  color = 0xFFE91605; break;
            case "INACTIVO":   color = 0xFF757575; break;
            default:           color = 0xFF171717; break;
        }
        tvEstado.setTextColor(color);

        String proxMant = eq.proximoMantenimiento != null
            ? sdf.format(new Date(eq.proximoMantenimiento)) : "No programado";
        ((TextView) findViewById(R.id.tvProxMantenimiento)).setText("Próximo mantenimiento: " + proxMant);

        // Build servicios list
        LinearLayout layout = findViewById(R.id.layoutServicios);
        layout.removeAllViews();
        if (servicios.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Sin servicios registrados");
            tv.setTextColor(0xFF9E9E9E);
            tv.setPadding(0, 8, 0, 8);
            layout.addView(tv);
        } else {
            for (EquipmentServiceEntity s : servicios) {
                View row = LayoutInflater.from(this).inflate(R.layout.item_equipment_service, layout, false);
                ((TextView) row.findViewById(R.id.tvServTipo)).setText(s.tipoServicio != null ? s.tipoServicio : "—");
                ((TextView) row.findViewById(R.id.tvServDesc)).setText(s.descripcion != null ? s.descripcion : "");
                ((TextView) row.findViewById(R.id.tvServFecha)).setText(sdf.format(new Date(s.timestamp)));
                ((TextView) row.findViewById(R.id.tvServCosto)).setText(
                    s.costoTotal > 0 ? "$" + String.format("%.2f", s.costoTotal) : "");
                layout.addView(row);
            }
        }
    }

    private void mostrarDialogoServicio() {
        String[] tipos = {"MANTENIMIENTO", "REPARACION", "INSTALACION", "INSPECCION"};
        final String[] selTipo = {tipos[0]};
        EditText etDesc = new EditText(this);
        etDesc.setHint("Descripción del servicio");

        new AlertDialog.Builder(this)
            .setTitle("Registrar servicio")
            .setSingleChoiceItems(tipos, 0, (d, which) -> selTipo[0] = tipos[which])
            .setView(etDesc)
            .setPositiveButton("Registrar", (d, w) -> {
                String desc = etDesc.getText().toString().trim();
                if (desc.isEmpty()) desc = selTipo[0];
                String tipoFinal = selTipo[0];
                String descFinal = desc;
                executor.execute(() -> {
                    LcDatabase db = LcDatabase.getInstance(this);
                    EquipmentServiceEntity sv = new EquipmentServiceEntity();
                    sv.equipmentId = equipmentId;
                    sv.tipoServicio = tipoFinal;
                    sv.descripcion = descFinal;
                    sv.timestamp = System.currentTimeMillis();
                    db.equipmentServiceDao().insert(sv);
                    // Update equipment: record service timestamp + adjust estado
                    if (equipment != null) {
                        equipment.ultimoServicio = sv.timestamp;
                        equipment.estado = tipoFinal.equals("REPARACION") ? "REPARACION" : "ACTIVO";
                        db.equipmentDao().update(equipment);
                    }
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Servicio registrado", Toast.LENGTH_SHORT).show();
                        cargarDatos();
                    });
                });
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
