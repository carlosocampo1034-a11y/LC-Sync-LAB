package com.lc.technician;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.data.JobRepository;
import com.lc.technician.model.PartEntity;
import java.util.ArrayList;
import java.util.List;

public class PartsActivity extends AppCompatActivity {

    private PartAdapter adapter;
    private final List<PartEntity> partes = new ArrayList<>();
    private TextView tvStockAlerta;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parts);
        // Edge-to-edge (Android 15+): cabecera bajo la barra de estado, último repuesto visible sobre la navegación
        InsetsHelper.padTop(findViewById(R.id.header));
        InsetsHelper.padBottom(findViewById(R.id.recyclerParts));

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        tvStockAlerta = findViewById(R.id.tvStockAlerta);

        RecyclerView recycler = findViewById(R.id.recyclerParts);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PartAdapter(partes, this::abrirDetalle, this::confirmarEliminar, this::abrirMovimientos);
        recycler.setAdapter(adapter);

        findViewById(R.id.btnNuevoPart).setOnClickListener(v -> mostrarDialogoNuevo());
    }

    @Override
    protected void onResume() {
        super.onResume();
        cargarPartes();
    }

    private void cargarPartes() {
        JobRepository.get().getAllPartsAsync(result -> {
            partes.clear();
            partes.addAll(result);
            adapter.notifyDataSetChanged();
            actualizarAlerta(result);
        });
    }

    private void actualizarAlerta(List<PartEntity> list) {
        // BUG-003 FIX: mirror PartDao.getStockBajo() condition — only count
        // parts where stockMinimo > 0 AND stockActual < stockMinimo.
        long stockBajo = 0;
        for (PartEntity p : list) {
            if (p.stockMinimo > 0 && p.stockActual < p.stockMinimo) stockBajo++;
        }
        if (stockBajo > 0) {
            tvStockAlerta.setText("⚠  " + stockBajo + " repuesto(s) con stock bajo");
            tvStockAlerta.setVisibility(View.VISIBLE);
        } else {
            tvStockAlerta.setVisibility(View.GONE);
        }
    }

    private void mostrarDialogoNuevo() {
        mostrarDialogoPart(null);
    }

    private void mostrarDialogoEditar(PartEntity part) {
        mostrarDialogoPart(part);
    }

    private void mostrarDialogoPart(PartEntity existing) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_part_form, null);
        EditText etNombre    = view.findViewById(R.id.etPartNombre);
        EditText etSku       = view.findViewById(R.id.etPartSku);
        EditText etCategoria = view.findViewById(R.id.etPartCategoria);
        EditText etCosto     = view.findViewById(R.id.etPartCosto);
        EditText etPrecio    = view.findViewById(R.id.etPartPrecio);
        EditText etStock     = view.findViewById(R.id.etPartStock);
        EditText etStockMin  = view.findViewById(R.id.etPartStockMin);
        EditText etProveedor = view.findViewById(R.id.etPartProveedor);
        EditText etNotas     = view.findViewById(R.id.etPartNotas);

        boolean editando = existing != null;
        if (editando) {
            etNombre.setText(existing.nombre);
            etSku.setText(existing.sku);
            etCategoria.setText(existing.categoria);
            etCosto.setText(String.valueOf(existing.costo));
            etPrecio.setText(String.valueOf(existing.precio));
            etStock.setText(String.valueOf(existing.stockActual));
            etStockMin.setText(String.valueOf(existing.stockMinimo));
            etProveedor.setText(existing.proveedor);
            etNotas.setText(existing.notas);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(editando ? "Editar repuesto" : "Nuevo repuesto")
                .setView(view)
                .setPositiveButton("Guardar", (d, w) -> {
                    String nombre = etNombre.getText().toString().trim();
                    if (nombre.isEmpty()) {
                        Toast.makeText(this, "El nombre es requerido", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    PartEntity p = editando ? existing : new PartEntity();
                    p.nombre    = nombre;
                    p.sku       = etSku.getText().toString().trim();
                    p.categoria = etCategoria.getText().toString().trim();
                    p.costo     = parseDouble(etCosto.getText().toString());
                    p.precio    = parseDouble(etPrecio.getText().toString());
                    p.stockActual  = parseInt(etStock.getText().toString());
                    p.stockMinimo  = parseInt(etStockMin.getText().toString());
                    p.proveedor = etProveedor.getText().toString().trim();
                    p.notas     = etNotas.getText().toString().trim();

                    if (editando) {
                        JobRepository.get().updatePartAsync(p, this::cargarPartes);
                    } else {
                        JobRepository.get().addPartAsync(p, id -> cargarPartes());
                    }
                })
                .setNegativeButton("Cancelar", null)
                .create();
        // El formulario es un ScrollView: con ADJUST_RESIZE se encoge sobre el teclado y los botones siguen visibles
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
    }

    private void confirmarEliminar(PartEntity part) {
        new AlertDialog.Builder(this)
                .setTitle("Eliminar repuesto")
                .setMessage("¿Eliminar \"" + part.nombre + "\"? Esta acción no se puede deshacer.")
                .setPositiveButton("Eliminar", (d, w) ->
                        JobRepository.get().deletePartAsync(part, this::cargarPartes))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void abrirDetalle(PartEntity p) {
        Intent i = new Intent(this, PartDetailActivity.class);
        i.putExtra(PartDetailActivity.EXTRA_PART_ID, p.id);
        startActivity(i);
    }

    private void abrirMovimientos(PartEntity p) {
        Intent i = new Intent(this, StockMovementActivity.class);
        i.putExtra(StockMovementActivity.EXTRA_PART_ID, p.id);
        startActivity(i);
    }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s.replace(",", ".")); } catch (Exception e) { return 0; }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    interface OnPartClick { void onClick(PartEntity part); }

    static class PartAdapter extends RecyclerView.Adapter<PartAdapter.VH> {
        private final List<PartEntity> list;
        private final OnPartClick onEdit;
        private final OnPartClick onDelete;
        private final OnPartClick onMovements;

        PartAdapter(List<PartEntity> list, OnPartClick onEdit, OnPartClick onDelete, OnPartClick onMovements) {
            this.list = list;
            this.onEdit = onEdit;
            this.onDelete = onDelete;
            this.onMovements = onMovements;
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_part, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            PartEntity p = list.get(position);
            h.tvNombre.setText(p.nombre != null ? p.nombre : "");
            h.tvSku.setText(p.sku != null && !p.sku.isEmpty() ? "SKU: " + p.sku : "");
            h.tvSku.setVisibility(p.sku != null && !p.sku.isEmpty() ? View.VISIBLE : View.GONE);
            h.tvPrecio.setText(String.format(java.util.Locale.US, "$%.2f", p.precio));
            h.tvStock.setText("Stock: " + p.stockActual);

            // BUG-003 FIX: only highlight red when stockMinimo is set and stock is below it
            boolean stockBajo = p.stockMinimo > 0 && p.stockActual < p.stockMinimo;
            h.tvStock.setTextColor(stockBajo ? 0xFFE91605 : 0xFF171717);

            h.itemView.setOnClickListener(v -> onEdit.onClick(p));
            h.tvMovimientos.setOnClickListener(v -> onMovements.onClick(p));
            h.itemView.setOnLongClickListener(v -> { onDelete.onClick(p); return true; });
        }

        @Override
        public int getItemCount() { return list.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvNombre, tvSku, tvPrecio, tvStock, tvMovimientos;
            VH(View v) {
                super(v);
                tvNombre = v.findViewById(R.id.tvPartNombre);
                tvSku    = v.findViewById(R.id.tvPartSku);
                tvPrecio = v.findViewById(R.id.tvPartPrecio);
                tvStock  = v.findViewById(R.id.tvPartStock);
                tvMovimientos = v.findViewById(R.id.tvPartMovimientos);
            }
        }
    }
}
