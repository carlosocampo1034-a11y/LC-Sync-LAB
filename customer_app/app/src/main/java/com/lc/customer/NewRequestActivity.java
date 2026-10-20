package com.lc.customer;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Solicitar servicio: propiedad, equipo, descripción, fecha preferida y foto. */
public class NewRequestActivity extends AppCompatActivity {

    static final String[] CATEGORIAS = {"ELECTRODOMESTICOS", "HVAC", "PLOMERIA", "ELECTRICIDAD", "OTRO"};

    private final List<JSONObject> props = new ArrayList<>();
    private Spinner spProp, spEquipo, spCat;
    private EditText desc, ventana;
    private TextView tvFecha, tvFoto, error;
    private Button enviar;
    private long fechaPreferida = 0;
    private Uri foto;
    private String fotoMime;
    private boolean enviando = false;
    private String clientRequestId;
    private ActivityResultLauncher<String> elegirFoto;
    private ActivityResultLauncher<Uri> tomarFoto;
    private File tempFotoFile;
    /** Fotos de prueba generadas en la app (laboratorio) y su número para reintentos sin duplicar. */
    private final List<byte[]> fotosPrueba = new ArrayList<>();
    private final List<Long> idsFotosPrueba = new ArrayList<>();
    private final java.util.Set<Long> fotosSubidas = new java.util.HashSet<>();
    private long solicitudCreada = 0;
    private TextView tvFotoPrueba;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        clientRequestId = b != null ? b.getString("crid") : null;
        if (clientRequestId == null) clientRequestId = UUID.randomUUID().toString();  // mismo id en reintentos
        if (b != null) fechaPreferida = b.getLong("fecha", 0);
        Ui.Pantalla p = Ui.pantalla(this, "Solicitar servicio", "Cuéntanos qué necesitas", true);
        Ui.seccion(this, p.contenido, "¿Dónde?");
        spProp = new Spinner(this);
        p.contenido.addView(spProp);
        Ui.seccion(this, p.contenido, "¿Qué equipo?");
        spEquipo = new Spinner(this);
        p.contenido.addView(spEquipo);
        spCat = new Spinner(this);
        spCat.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, CATEGORIAS));
        p.contenido.addView(spCat);
        Ui.seccion(this, p.contenido, "Describe el problema");
        desc = Ui.campo(this, p.contenido, "Ej. La nevera no enfría desde ayer (mín. 10 caracteres)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        desc.setMinLines(3);
        Ui.seccion(this, p.contenido, "Disponibilidad");
        tvFecha = Ui.texto(this, "Elegir fecha y hora preferidas (opcional)", 15, R.color.lc_rojo, true);
        tvFecha.setMinHeight(Ui.dp(this, 48));
        tvFecha.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tvFecha.setOnClickListener(v -> elegirFecha());
        p.contenido.addView(tvFecha);
        if (fechaPreferida > 0) tvFecha.setText("Preferencia: " + Ui.fecha(fechaPreferida));
        ventana = Ui.campo(this, p.contenido, "Otra disponibilidad (ej. tardes después de las 3)");
        p.contenido.addView(Ui.texto(this, "La cita la confirma L&C; te avisaremos.", 13, R.color.lc_sec, false));
        Ui.seccion(this, p.contenido, "Foto (opcional)");
        tvFoto = Ui.texto(this, "Adjuntar una foto del equipo", 15, R.color.lc_rojo, true);
        tvFoto.setMinHeight(Ui.dp(this, 48));
        tvFoto.setGravity(android.view.Gravity.CENTER_VERTICAL);
        p.contenido.addView(tvFoto);
        elegirFoto = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            String mime = getContentResolver().getType(uri);
            if (!"image/jpeg".equals(mime) && !"image/png".equals(mime)) {
                tvFoto.setText("Solo fotos JPG o PNG");
                return;
            }
            foto = uri;
            fotoMime = mime;
            tvFoto.setText("✓ Foto adjunta (toca para cambiar)");
        });
        tomarFoto = registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
            if (!ok || tempFotoFile == null || !tempFotoFile.exists() || tempFotoFile.length() == 0) return;
            foto = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", tempFotoFile);
            fotoMime = "image/jpeg";
            tvFoto.setText("✓ Foto adjunta (toca para cambiar)");
        });
        tvFoto.setOnClickListener(v -> mostrarMenuFoto());
        tvFotoPrueba = Ui.texto(this, "+ Agregar foto de prueba (laboratorio)", 15, R.color.lc_rojo, true);
        tvFotoPrueba.setMinHeight(Ui.dp(this, 48));
        tvFotoPrueba.setGravity(android.view.Gravity.CENTER_VERTICAL);
        p.contenido.addView(tvFotoPrueba);
        tvFotoPrueba.setOnClickListener(v -> agregarFotoPrueba());
        error = Ui.texto(this, "", 14, R.color.lc_rojo, false);
        p.contenido.addView(error);
        enviar = Ui.boton(this, p.inferior, "Enviar solicitud", true);
        enviar.setOnClickListener(v -> enviar());
        spProp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> a, android.view.View v, int pos, long id) {
                llenarEquipos(pos);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> a) {}
        });
        cargarPropiedades();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("crid", clientRequestId);
        out.putLong("fecha", fechaPreferida);
    }

    private void cargarPropiedades() {
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.get(this, "/customer/properties");
            runOnUiThread(() -> {
                props.clear();
                List<String> nombres = new ArrayList<>();
                JSONArray ps = r.ok() ? r.cuerpo.optJSONArray("properties") : null;
                for (int i = 0; ps != null && i < ps.length(); i++) {
                    props.add(ps.optJSONObject(i));
                    nombres.add(ps.optJSONObject(i).optString("direccion"));
                }
                if (nombres.isEmpty()) {
                    error.setText(r.ok() ? "Primero agrega una propiedad en «Mis propiedades»." : r.error());
                    enviar.setEnabled(false);
                }
                spProp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, nombres));
            });
        });
    }

    private void llenarEquipos(int pos) {
        List<String> eq = new ArrayList<>();
        eq.add("Sin especificar");
        JSONArray a = props.get(pos).optJSONArray("equipos");
        for (int i = 0; a != null && i < a.length(); i++) eq.add(a.optJSONObject(i).optString("nombre"));
        spEquipo.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, eq));
    }

    private void elegirFecha() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, 1);
        DatePickerDialog dp = new DatePickerDialog(this, (v, y, m, d) -> {
            c.set(y, m, d);
            new TimePickerDialog(this, (t, h, min) -> {
                c.set(Calendar.HOUR_OF_DAY, h);
                c.set(Calendar.MINUTE, min);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                fechaPreferida = c.getTimeInMillis();
                tvFecha.setText("Preferencia: " + Ui.fecha(fechaPreferida));
            }, 10, 0, false).show();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dp.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
        dp.show();
    }

    private void enviar() {
        if (enviando || props.isEmpty()) return;
        String d = desc.getText().toString().trim();
        if (d.length() < 10) { desc.setError("Describe el problema (mín. 10 caracteres)"); desc.requestFocus(); return; }
        enviando = true;
        enviar.setEnabled(false);
        enviar.setText("ENVIANDO…");
        error.setText("");
        JSONObject prop = props.get(spProp.getSelectedItemPosition());
        JSONObject body = new JSONObject();
        try {
            body.put("propertyId", prop.optLong("id")).put("descripcion", d)
                    .put("categoria", spCat.getSelectedItem()).put("clientRequestId", clientRequestId)
                    .put("zonaHoraria", java.util.TimeZone.getDefault().getID());
            int ie = spEquipo.getSelectedItemPosition();
            JSONArray eqs = prop.optJSONArray("equipos");
            if (ie > 0 && eqs != null) body.put("equipmentId", eqs.optJSONObject(ie - 1).optLong("id"));
            if (fechaPreferida > 0) body.put("fechaPreferida", fechaPreferida);
            String vent = ventana.getText().toString().trim();
            if (!vent.isEmpty()) body.put("ventanaPreferida", vent);
        } catch (Exception ignored) {}
        Executors.newSingleThreadExecutor().execute(() -> {
            // mismo clientRequestId en cada reintento: el servidor devuelve la misma solicitud, no otra
            Api.Respuesta r = Api.post(this, "/customer/requests", body);
            String fotoMsg = "";
            if (r.ok() && foto != null) {
                byte[] datos = leer(foto);
                Api.Respuesta rf = datos == null ? null : Api.subirFoto(this, r.cuerpo.optLong("id"), datos, fotoMime, "BEFORE");
                fotoMsg = rf != null && rf.ok() ? "\nFoto adjunta." : "\nLa foto no se pudo subir; puedes intentarlo desde el detalle.";
            }
            int fallidas = 0;
            if (r.ok()) {
                solicitudCreada = r.cuerpo.optLong("id");
                for (int i = 0; i < fotosPrueba.size(); i++) {
                    long cpid = idsFotosPrueba.get(i);
                    if (fotosSubidas.contains(cpid)) continue;
                    Api.Respuesta rf = Api.subirFoto(this, solicitudCreada, fotosPrueba.get(i), "image/png", "BEFORE", cpid);
                    if (rf.ok()) fotosSubidas.add(cpid); else fallidas++;
                }
                if (!fotosPrueba.isEmpty()) fotoMsg += "\nFotos de prueba adjuntas: " + fotosSubidas.size() + " de " + fotosPrueba.size() + ".";
            }
            final String fm = fotoMsg;
            final int fallos = fallidas;
            runOnUiThread(() -> {
                enviando = false;
                if (r.ok() && fallos > 0) {
                    // la solicitud quedó registrada; las fotos que faltan se reintentan sin duplicar
                    enviar.setEnabled(true);
                    enviar.setText("REINTENTAR FOTOS");
                    error.setText("Solicitud Nº " + solicitudCreada + " registrada. No se pudieron subir " + fallos
                            + " foto(s); toca para reintentar.");
                } else if (r.ok()) {
                    enviar.setText("SOLICITUD ENVIADA ✓");
                    new AlertDialog.Builder(this).setTitle("Solicitud enviada")
                            .setMessage("Nº " + r.cuerpo.optLong("id") + "\nEstado: Recibida por L&C." + fm
                                    + "\n\nTe avisaremos cuando se programe la visita.")
                            .setCancelable(false).setPositiveButton("Aceptar", (x, y) -> finish()).show();
                } else {
                    enviar.setEnabled(true);
                    enviar.setText("ENVIAR SOLICITUD");
                    error.setText(r.error() + (r.codigo == -1 ? " · tus datos siguen aquí; reintenta." : ""));
                }
            });
        });
    }

    /** Imagen PNG generada en la app y rotulada como prueba: no se usa la galería ni la cámara. */
    private void agregarFotoPrueba() {
        if (fotosPrueba.size() >= 5) { tvFotoPrueba.setText("Máximo 5 fotos de prueba"); return; }
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(960, 720, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
        cv.drawColor(fotosPrueba.size() % 2 == 0 ? 0xFF0D47A1 : 0xFF00695C);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(56f);
        cv.drawText("FOTO DE PRUEBA · CLIENTE LAB", 40, 160, p);
        p.setTextSize(44f);
        cv.drawText("Foto n.º " + (fotosPrueba.size() + 1), 40, 300, p);
        cv.drawText(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()), 40, 380, p);
        p.setTextSize(32f);
        cv.drawText("Imagen sintética generada por L&C Customer (laboratorio)", 40, 660, p);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bo);
        bmp.recycle();
        fotosPrueba.add(bo.toByteArray());
        idsFotosPrueba.add(1 + Math.floorMod(new java.security.SecureRandom().nextLong(), 4_000_000_000_000_000_000L));
        tvFotoPrueba.setText("✓ " + fotosPrueba.size() + " foto(s) de prueba · toca para agregar otra");
    }

    private void mostrarMenuFoto() {
        new AlertDialog.Builder(this)
                .setTitle("Adjuntar foto")
                .setItems(new String[]{"Tomar foto con cámara", "Elegir de galería"}, (d, which) -> {
                    if (which == 0) abrirCamara();
                    else elegirFoto.launch("image/*");
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void abrirCamara() {
        try {
            File dir = getExternalFilesDir("fotos");
            if (dir != null) dir.mkdirs();
            tempFotoFile = File.createTempFile("foto_cliente_", ".jpg", dir);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", tempFotoFile);
            tomarFoto.launch(uri);
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir la cámara", Toast.LENGTH_SHORT).show();
        }
    }

    private byte[] leer(Uri u) {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > 5 * 1024 * 1024) return null;   // límite del servidor
                bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
