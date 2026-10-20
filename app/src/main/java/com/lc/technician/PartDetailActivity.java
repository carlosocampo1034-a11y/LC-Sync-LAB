package com.lc.technician;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.PartEntity;
import com.lc.technician.model.StockMovementEntity;
import com.lc.technician.model.TruckStockEntity;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PartDetailActivity extends AppCompatActivity {
    public static final String EXTRA_PART_ID = "part_id";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long partId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_part_detail);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));
        partId = getIntent().getLongExtra(EXTRA_PART_ID, -1);
        if (partId == -1) { finish(); return; }
        findViewById(R.id.btnAtras).setOnClickListener(v -> finish());
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
            PartEntity part = db.partDao().getById(partId);
            List<TruckStockEntity> stocks = db.truckStockDao().getByPart(partId);
            List<StockMovementEntity> movs = db.stockMovementDao().getByPart(partId);
            mainHandler.post(() -> mostrarDatos(part, stocks, movs));
        });
    }

    private void mostrarDatos(PartEntity part, List<TruckStockEntity> stocks, List<StockMovementEntity> movs) {
        if (part == null) { finish(); return; }
        SimpleDateFormat sdf = new SimpleDateFormat("MM/dd HH:mm", Locale.US);

        ((TextView) findViewById(R.id.tvPartNombre)).setText(part.nombre != null ? part.nombre : "—");
        ((TextView) findViewById(R.id.tvPartSku)).setText("SKU: " + (part.sku != null ? part.sku : "—"));
        ((TextView) findViewById(R.id.tvPartCategoria)).setText("Categoría: " + (part.categoria != null ? part.categoria : "—"));
        ((TextView) findViewById(R.id.tvPartCosto)).setText("Costo: $" + String.format("%.2f", part.costo));
        ((TextView) findViewById(R.id.tvPartPrecio)).setText("Precio: $" + String.format("%.2f", part.precio));
        ((TextView) findViewById(R.id.tvPartStock)).setText("Stock: " + part.stockActual);

        StringBuilder sb = new StringBuilder();
        if (stocks.isEmpty()) sb.append("Sin stock en camiones");
        else for (TruckStockEntity s : stocks)
            sb.append(s.ubicacion).append(": ").append(s.cantidad).append("\n");
        ((TextView) findViewById(R.id.tvTruckStock)).setText(sb.toString().trim());

        StringBuilder movSb = new StringBuilder();
        if (movs.isEmpty()) {
            movSb.append("Sin movimientos");
        } else {
            for (StockMovementEntity m : movs) {
                movSb.append(m.movementType)
                     .append(" ")
                     .append(m.quantity)
                     .append(" — ")
                     .append(sdf.format(new Date(m.timestamp)))
                     .append("\n");
            }
        }
        ((TextView) findViewById(R.id.tvMovimientos)).setText(movSb.toString().trim());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
