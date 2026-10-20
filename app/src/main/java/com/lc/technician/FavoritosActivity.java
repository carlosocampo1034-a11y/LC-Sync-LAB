package com.lc.technician;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class FavoritosActivity extends AppCompatActivity {

    private static final String PREFS = "lc_favoritos";
    private LinearLayout layout;
    private TextView tvVacio;
    private TextView tvContadorFav;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_favoritos);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());
        findViewById(R.id.btnAgregar).setOnClickListener(v -> mostrarDialogoAgregar());

        layout        = findViewById(R.id.layoutFavoritos);
        tvVacio       = findViewById(R.id.tvSinFavoritos);
        tvContadorFav = findViewById(R.id.tvContadorFav);
    }

    @Override
    protected void onResume() {
        super.onResume();
        recargar();
    }

    private void recargar() {
        layout.removeAllViews();
        java.util.Map<String, ?> todos = prefs.getAll();
        tvContadorFav.setText(todos.isEmpty() ? "" : todos.size() + " favoritos");

        if (todos.isEmpty()) {
            tvVacio.setVisibility(View.VISIBLE);
            layout.setVisibility(View.GONE);
            return;
        }

        tvVacio.setVisibility(View.GONE);
        layout.setVisibility(View.VISIBLE);
        int dp16 = Math.round(16 * getResources().getDisplayMetrics().density);

        for (final String cliente : todos.keySet()) {
            TextView card = new TextView(this);
            card.setText(cliente);
            card.setTextSize(16f);
            card.setPadding(dp16, dp16, dp16, dp16);
            card.setBackgroundResource(R.drawable.bg_surface);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp16 / 2;
            card.setLayoutParams(lp);

            card.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Eliminar favorito")
                        .setMessage("¿Quitar a " + cliente + " de favoritos?")
                        .setPositiveButton("Eliminar", (d, w) -> {
                            prefs.edit().remove(cliente).apply();
                            Toast.makeText(this, "Eliminado", Toast.LENGTH_SHORT).show();
                            recargar();
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
                return true;
            });

            layout.addView(card);
        }
    }

    private void mostrarDialogoAgregar() {
        EditText input = new EditText(this);
        input.setHint("Nombre del cliente");
        int dp16 = Math.round(16 * getResources().getDisplayMetrics().density);
        input.setPadding(dp16, dp16, dp16, dp16);

        new AlertDialog.Builder(this)
                .setTitle("Agregar favorito")
                .setView(input)
                .setPositiveButton("Guardar", (d, w) -> {
                    String nombre = input.getText().toString().trim();
                    if (nombre.isEmpty()) {
                        Toast.makeText(this, "Escribí un nombre", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    prefs.edit().putString(nombre, "1").apply();
                    Toast.makeText(this, "Agregado: " + nombre, Toast.LENGTH_SHORT).show();
                    recargar();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }
}
