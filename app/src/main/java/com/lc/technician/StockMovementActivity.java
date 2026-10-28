package com.lc.technician;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.lc.technician.data.LcDatabase;
import com.lc.technician.data.PartDao;
import com.lc.technician.data.StockMovementDao;
import com.lc.technician.model.PartEntity;
import com.lc.technician.model.StockMovementEntity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class StockMovementActivity extends AppCompatActivity {

    public static final String EXTRA_PART_ID = "part_id";
    private long partId;
    private StockMovementDao movementDao;
    private PartDao partDao;
    private RecyclerView rvMovements;
    private TextView tvEmpty, tvPartInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stock_movement);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        partId = getIntent().getLongExtra(EXTRA_PART_ID, 0);
        movementDao = LcDatabase.getInstance(this).stockMovementDao();
        partDao = LcDatabase.getInstance(this).partDao();

        ImageView btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        tvPartInfo = findViewById(R.id.tvPartInfo);
        tvEmpty = findViewById(R.id.tvEmpty);
        rvMovements = findViewById(R.id.rvMovements);
        rvMovements.setLayoutManager(new LinearLayoutManager(this));

        Button btnIn = findViewById(R.id.btnIn);
        Button btnOut = findViewById(R.id.btnOut);
        Button btnAdjust = findViewById(R.id.btnAdjust);

        btnIn.setOnClickListener(v -> addMovement(StockMovementEntity.TYPE_IN, 1));
        btnOut.setOnClickListener(v -> addMovement(StockMovementEntity.TYPE_OUT, -1));
        btnAdjust.setOnClickListener(v -> mostrarDialogoAjuste());

        loadPartInfo();
        loadMovements();
    }

    private void loadPartInfo() {
        new Thread(() -> {
            PartEntity p = partDao.getById(partId);
            runOnUiThread(() -> {
                if (p != null) {
                    String sku = p.sku == null ? "-" : p.sku;
                    tvPartInfo.setText(p.nombre + " · SKU " + sku + "\nStock: " + p.stockActual + " · Min: " + p.stockMinimo);
                }
            });
        }).start();
    }

    private void loadMovements() {
        new Thread(() -> {
            List<StockMovementEntity> list = movementDao.getByPart(partId);
            runOnUiThread(() -> {
                if (list.isEmpty()) {
                    rvMovements.setVisibility(View.GONE);
                    tvEmpty.setVisibility(View.VISIBLE);
                } else {
                    rvMovements.setVisibility(View.VISIBLE);
                    tvEmpty.setVisibility(View.GONE);
                    rvMovements.setAdapter(new MovementAdapter(list));
                }
            });
        }).start();
    }

    private void mostrarDialogoAjuste() {
        EditText et = new EditText(this);
        et.setHint("Ej: +5 o -3");
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        et.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle("Ajuste de inventario")
                .setMessage("Ingresa la cantidad a ajustar (positivo = entrada, negativo = salida)")
                .setView(et)
                .setPositiveButton("Aplicar", (d, w) -> {
                    String raw = et.getText().toString().trim();
                    if (raw.isEmpty()) {
                        Toast.makeText(this, "Ingresa una cantidad", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        int qty = Integer.parseInt(raw);
                        if (qty == 0) {
                            Toast.makeText(this, "La cantidad no puede ser 0", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        addMovementAdjust(qty);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Cantidad inválida", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void addMovementAdjust(int qty) {
        new Thread(() -> {
            StockMovementEntity m = new StockMovementEntity();
            m.partId = partId;
            m.locationId = 1;
            m.movementType = StockMovementEntity.TYPE_ADJUSTMENT;
            m.quantity = qty;
            m.referenceType = StockMovementEntity.REF_MANUAL;
            m.notes = "Ajuste manual: " + (qty > 0 ? "+" : "") + qty;
            m.timestamp = System.currentTimeMillis();
            movementDao.insert(m);
            long ts = System.currentTimeMillis();
            if (qty > 0) {
                partDao.incrementStock(partId, qty, ts);
            } else {
                partDao.decrementStock(partId, -qty, ts);
            }
            runOnUiThread(() -> {
                loadPartInfo();
                loadMovements();
            });
        }).start();
    }

    private void addMovement(String type, int sign) {
        new Thread(() -> {
            StockMovementEntity m = new StockMovementEntity();
            m.partId = partId;
            m.locationId = 1;
            m.movementType = type;
            m.quantity = sign == 0 ? 1 : sign * 5;
            m.referenceType = StockMovementEntity.REF_MANUAL;
            m.notes = "Movimiento manual desde app";
            movementDao.insert(m);
            runOnUiThread(this::loadMovements);
        }).start();
    }

    private class MovementAdapter extends RecyclerView.Adapter<MovementAdapter.VH> {
        private final List<StockMovementEntity> items;
        MovementAdapter(List<StockMovementEntity> items) { this.items = items; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.item_stock_movement, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            StockMovementEntity m = items.get(position);
            h.tvType.setText(m.movementType);
            int color;
            switch (m.movementType) {
                case StockMovementEntity.TYPE_IN: color = 0xFF171717; break;
                case StockMovementEntity.TYPE_OUT: color = 0xFFE91605; break;
                case StockMovementEntity.TYPE_TRANSFER: color = 0xFF62666A; break;
                default: color = 0xFF62666A;
            }
            h.tvType.setBackgroundColor(color);
            h.tvQuantity.setText((m.quantity >= 0 ? "+" : "") + m.quantity);
            String fecha = new SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(new Date(m.timestamp));
            String detalle = (m.referenceType != null ? m.referenceType : "MANUAL") + " · " + fecha;
            h.tvDetails.setText(detalle);
        }

        @Override
        public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvType, tvQuantity, tvDetails;
            VH(@NonNull View v) {
                super(v);
                tvType = v.findViewById(R.id.tvType);
                tvQuantity = v.findViewById(R.id.tvQuantity);
                tvDetails = v.findViewById(R.id.tvDetails);
            }
        }
    }
}
