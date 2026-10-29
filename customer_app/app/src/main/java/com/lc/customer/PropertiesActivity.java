package com.lc.customer;

import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;

/** Propiedades del cliente y sus equipos del hogar. */
public class PropertiesActivity extends AppCompatActivity {

    private LinearLayout lista;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.Pantalla p = Ui.pantalla(this, "Mis propiedades", "Dónde damos servicio y qué equipos tienes", true);
        lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        p.contenido.addView(lista);
        Ui.boton(this, p.inferior, "Agregar propiedad", true).setOnClickListener(v -> dialogoPropiedad());
    }

    @Override
    protected void onResume() {
        super.onResume();
        cargar();   // también al volver de registrar o editar un equipo
    }

    private void cargar() {
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.get(this, "/customer/properties");
            runOnUiThread(() -> {
                lista.removeAllViews();
                if (!r.ok()) { lista.addView(Ui.texto(this, r.error(), 14, R.color.lc_rojo, false)); return; }
                JSONArray ps = r.cuerpo.optJSONArray("properties");
                if (ps == null || ps.length() == 0) {
                    lista.addView(Ui.texto(this, "Agrega tu primera propiedad para poder solicitar servicio.", 15,
                            R.color.lc_sec, false));
                    return;
                }
                for (int i = 0; i < ps.length(); i++) {
                    JSONObject pr = ps.optJSONObject(i);
                    LinearLayout c = Ui.tarjeta(this, lista);
                    c.addView(Ui.texto(this, pr.optString("direccion"), 16, R.color.lc_texto, true));
                    c.addView(Ui.texto(this, Ajustes.etiqueta(this, "property_types.list", Ajustes.TIPOS_PROPIEDAD, pr.optString("tipoPropiedad", "")) + " · acceso: " + Ajustes.etiqueta(this, "access_types.list", Ajustes.ACCESOS, pr.optString("tipoAcceso", "—"))
                            + (pr.optBoolean("tieneCodigoAcceso") ? " (código guardado)" : ""), 13, R.color.lc_sec, false));
                    JSONArray eq = pr.optJSONArray("equipos");
                    for (int k = 0; eq != null && k < eq.length(); k++) {
                        JSONObject e = eq.optJSONObject(k);
                        StringBuilder linea = new StringBuilder("• ").append(e.optString("nombre"));
                        for (String campo : new String[]{"marca", "modelo"}) {   // marca y modelo solo si existen
                            String v = e.isNull(campo) ? "" : e.optString(campo).trim();
                            if (!v.isEmpty()) linea.append(" · ").append(v);
                        }
                        final long pidEq = pr.optLong("id");
                        final JSONObject eqf = e;
                        LinearLayout fila = new LinearLayout(this);
                        fila.setOrientation(LinearLayout.HORIZONTAL);
                        fila.setGravity(android.view.Gravity.CENTER_VERTICAL);
                        fila.setMinimumHeight(Ui.dp(this, 48));
                        fila.addView(Ui.texto(this, linea.toString(), 14, R.color.lc_texto, false), new LinearLayout.LayoutParams(0, -2, 1f));
                        android.widget.ImageView ch = new android.widget.ImageView(this);
                        ch.setImageResource(R.drawable.ic_chevron);
                        ch.setColorFilter(0xFF62666A);
                        fila.addView(ch, new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20)));
                        fila.setOnClickListener(v -> startActivity(new android.content.Intent(this, EquipoActivity.class)
                                .putExtra("pid", pidEq).putExtra("equipo", eqf.toString())));   // abre y edita el equipo (mismo id)
                        c.addView(fila);
                    }
                    long pid = pr.optLong("id");
                    TextView add = Ui.texto(this, "+ Agregar equipo", 14, R.color.lc_rojo, true);
                    add.setPadding(0, Ui.dp(this, 8), 0, 0);
                    add.setMinHeight(Ui.dp(this, 44));
                    add.setOnClickListener(v -> startActivity(new android.content.Intent(this, EquipoActivity.class).putExtra("pid", pid)));
                    c.addView(add);
                }
            });
        });
    }

    private void dialogoPropiedad() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        EditText dir = Ui.campo(this, f, "Dirección completa",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS);
        // listas editables en Control (settings property_types.list / access_types.list)
        final java.util.List<Ajustes.Opcion> tiposOp = Ajustes.lista(this, "property_types.list", Ajustes.TIPOS_PROPIEDAD, true);
        final java.util.List<Ajustes.Opcion> accesosOp = Ajustes.lista(this, "access_types.list", Ajustes.ACCESOS, true);
        Spinner tipo = new Spinner(this);
        java.util.List<String> tiposTxt = new java.util.ArrayList<>();
        for (Ajustes.Opcion o : tiposOp) tiposTxt.add(o.label);
        tipo.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, tiposTxt));
        f.addView(tipo);
        Spinner acceso = new Spinner(this);
        java.util.List<String> accesosTxt = new java.util.ArrayList<>();
        for (Ajustes.Opcion o : accesosOp) accesosTxt.add(o.label);
        acceso.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, accesosTxt));
        f.addView(acceso);
        EditText codigo = Ui.campo(this, f, "Código de acceso (opcional, se guarda cifrado)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText instr = Ui.campo(this, f, "Instrucciones de acceso (opcional)");
        EditText masc = Ui.campo(this, f, "Mascotas (opcional)");
        EditText resp = Ui.campo(this, f, "Contacto de respaldo (opcional)");
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(f);
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Nueva propiedad").setView(sv)
                .setPositiveButton("Guardar", null).setNegativeButton("Cancelar", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (dir.getText().toString().trim().length() < 5) { dir.setError("Dirección demasiado corta"); return; }
            v.setEnabled(false);
            JSONObject body = new JSONObject();
            try {
                body.put("direccion", dir.getText().toString().trim()).put("tipoPropiedad", tiposOp.get(tipo.getSelectedItemPosition()).id)
                        .put("tipoAcceso", accesosOp.get(acceso.getSelectedItemPosition()).id).put("codigoAcceso", codigo.getText().toString().trim())
                        .put("instrucciones", instr.getText().toString().trim()).put("mascota", masc.getText().toString().trim())
                        .put("contactoRespaldo", resp.getText().toString().trim());
            } catch (Exception ignored) {}
            Executors.newSingleThreadExecutor().execute(() -> {
                Api.Respuesta r = Api.post(this, "/customer/properties", body);
                runOnUiThread(() -> {
                    v.setEnabled(true);
                    if (r.ok()) { d.dismiss(); cargar(); } else Toast.makeText(this, r.error(), Toast.LENGTH_LONG).show();
                });
            });
        }));
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
    }
}
