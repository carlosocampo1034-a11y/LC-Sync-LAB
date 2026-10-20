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

    static final String[] TIPOS = {"Casa", "Apartamento", "Oficina", "Hotel", "Otro"};
    static final String[] ACCESOS = {"Ninguno", "Código puerta", "Garaje", "Lockbox", "Vecino", "Llave escondida"};
    private LinearLayout lista;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.Pantalla p = Ui.pantalla(this, "Mis propiedades", "Dónde damos servicio y qué equipos tienes", true);
        lista = new LinearLayout(this);
        lista.setOrientation(LinearLayout.VERTICAL);
        p.contenido.addView(lista);
        Ui.boton(this, p.inferior, "Agregar propiedad", true).setOnClickListener(v -> dialogoPropiedad());
        cargar();
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
                    c.addView(Ui.texto(this, pr.optString("tipoPropiedad", "") + " · acceso: " + pr.optString("tipoAcceso", "—")
                            + (pr.optBoolean("tieneCodigoAcceso") ? " (código guardado)" : ""), 13, R.color.lc_sec, false));
                    JSONArray eq = pr.optJSONArray("equipos");
                    for (int k = 0; eq != null && k < eq.length(); k++) {
                        JSONObject e = eq.optJSONObject(k);
                        c.addView(Ui.texto(this, "• " + e.optString("nombre") + (e.isNull("modelo") ? "" : " · " + e.optString("modelo")),
                                14, R.color.lc_texto, false));
                    }
                    long pid = pr.optLong("id");
                    TextView add = Ui.texto(this, "+ Agregar equipo", 14, R.color.lc_rojo, true);
                    add.setPadding(0, Ui.dp(this, 8), 0, 0);
                    add.setMinHeight(Ui.dp(this, 44));
                    add.setOnClickListener(v -> dialogoEquipo(pid));
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
        Spinner tipo = new Spinner(this);
        tipo.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, TIPOS));
        f.addView(tipo);
        Spinner acceso = new Spinner(this);
        acceso.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ACCESOS));
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
                body.put("direccion", dir.getText().toString().trim()).put("tipoPropiedad", tipo.getSelectedItem())
                        .put("tipoAcceso", acceso.getSelectedItem()).put("codigoAcceso", codigo.getText().toString().trim())
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

    private void dialogoEquipo(long pid) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        f.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 20), 0);
        EditText nombre = Ui.campo(this, f, "Equipo (ej. Nevera, Lavadora, Aire acondicionado)");
        EditText marca = Ui.campo(this, f, "Marca (opcional)");
        EditText modelo = Ui.campo(this, f, "Modelo (opcional)");
        EditText serial = Ui.campo(this, f, "Serie (opcional)");
        AlertDialog d = new AlertDialog.Builder(this).setTitle("Nuevo equipo").setView(f)
                .setPositiveButton("Guardar", null).setNegativeButton("Cancelar", null).create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (nombre.getText().toString().trim().length() < 2) { nombre.setError("Nombre requerido"); return; }
            v.setEnabled(false);
            JSONObject body = new JSONObject();
            try {
                body.put("nombre", nombre.getText().toString().trim()).put("marca", marca.getText().toString().trim())
                        .put("modelo", modelo.getText().toString().trim()).put("serial", serial.getText().toString().trim());
            } catch (Exception ignored) {}
            Executors.newSingleThreadExecutor().execute(() -> {
                Api.Respuesta r = Api.post(this, "/customer/properties/" + pid + "/equipment", body);
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
