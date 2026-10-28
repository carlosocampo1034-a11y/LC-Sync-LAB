package com.lc.customer;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
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

    private static final int PASOS = 4;

    private final List<JSONObject> props = new ArrayList<>();
    private Spinner spProp, spEquipo, spCat;
    private List<Ajustes.Opcion> cats = new ArrayList<>();   // categorías: lista editable (settings categories.list)
    private TextView tvPrecio;
    private EditText desc, ventana;
    private TextView tvFecha, tvFoto, error, errProp, errDesc, tvPaso, resumen;
    private Button enviar, siguiente, atras;
    private final LinearLayout[] paso = new LinearLayout[PASOS];
    private int actual = 0;
    private boolean cargado = false;
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
        if (b != null) { fechaPreferida = b.getLong("fecha", 0); actual = b.getInt("paso", 0); }
        Ui.Pantalla p = Ui.pantalla(this, "Solicitar servicio", null, true);
        tvPaso = Ui.texto(this, "", 14, R.color.lc_sec, true);
        p.cabecera.addView(tvPaso);
        for (int i = 0; i < PASOS; i++) {
            paso[i] = new LinearLayout(this);
            paso[i].setOrientation(LinearLayout.VERTICAL);
            p.contenido.addView(paso[i]);
        }
        // Paso 1: propiedad y equipo
        Ui.seccion(this, paso[0], "¿Dónde es el servicio?");
        spProp = new Spinner(this);
        paso[0].addView(spProp);
        errProp = errorCampo(paso[0]);
        Ui.seccion(this, paso[0], "¿Qué equipo?");
        spEquipo = new Spinner(this);
        paso[0].addView(spEquipo);
        // Paso 2: servicio y problema
        Ui.seccion(this, paso[1], "Tipo de servicio");
        spCat = new Spinner(this);
        paso[1].addView(spCat);
        tvPrecio = Ui.texto(this, "", 13, R.color.lc_sec, false);
        tvPrecio.setPadding(0, Ui.dp(this, 6), 0, 0);
        paso[1].addView(tvPrecio);
        spCat.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> a, android.view.View v, int pos, long id) { actualizarPrecio(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> a) {}
        });
        cargarCategorias(null);
        Ui.seccion(this, paso[1], "Describe el problema");
        desc = Ui.campo(this, paso[1], "Ej. La nevera no enfría desde ayer",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        desc.setMinLines(3);
        errDesc = errorCampo(paso[1]);
        paso[1].addView(Ui.texto(this, "Mínimo 10 caracteres.", 13, R.color.lc_sec, false));
        // Paso 3: disponibilidad y fotos (opcionales)
        Ui.seccion(this, paso[2], "Disponibilidad (opcional)");
        tvFecha = Ui.texto(this, "Elegir fecha y hora preferidas", 15, R.color.lc_rojo, true);
        tvFecha.setMinHeight(Ui.dp(this, 48));
        tvFecha.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tvFecha.setOnClickListener(v -> elegirFecha());
        paso[2].addView(tvFecha);
        if (fechaPreferida > 0) tvFecha.setText("Preferencia: " + Ui.fecha(fechaPreferida));
        ventana = Ui.campo(this, paso[2], "Otra disponibilidad (ej. tardes después de las 3)");
        paso[2].addView(Ui.texto(this, "La fecha es solo una preferencia: la cita queda confirmada cuando L&C la apruebe.", 13, R.color.lc_sec, false));
        tvFoto = Ui.texto(this, "Adjuntar una foto del equipo", 15, R.color.lc_rojo, true);
        tvFotoPrueba = Ui.texto(this, "+ Agregar foto de prueba (laboratorio)", 15, R.color.lc_rojo, true);
        Ui.seccion(this, paso[2], "Foto (opcional)");
        tvFoto.setMinHeight(Ui.dp(this, 48));
        tvFoto.setGravity(android.view.Gravity.CENTER_VERTICAL);
        paso[2].addView(tvFoto);
        if (!CloudApi.activo()) {   // las fotos sintéticas son herramienta de laboratorio, no del cliente
            tvFotoPrueba.setMinHeight(Ui.dp(this, 48));
            tvFotoPrueba.setGravity(android.view.Gravity.CENTER_VERTICAL);
            paso[2].addView(tvFotoPrueba);
        }
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
        tvFotoPrueba.setOnClickListener(v -> agregarFotoPrueba());
        // Paso 4: revisar y enviar
        Ui.seccion(this, paso[3], "Revisa tu solicitud");
        resumen = Ui.texto(this, "", 15, R.color.lc_texto, false);
        paso[3].addView(resumen);
        error = Ui.texto(this, "", 14, R.color.lc_rojo, true);
        paso[3].addView(error);
        // Botones: Siguiente / Enviar y Atrás
        siguiente = Ui.boton(this, p.inferior, "Siguiente", true);
        siguiente.setOnClickListener(v -> siguientePaso());
        enviar = Ui.boton(this, p.inferior, "Enviar solicitud", true);
        enviar.setOnClickListener(v -> enviar());
        atras = Ui.boton(this, p.inferior, "Atrás", false);
        atras.setOnClickListener(v -> { if (actual > 0) mostrarPaso(actual - 1); });
        spProp.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> a, android.view.View v, int pos, long id) {
                llenarEquipos(pos);   // equipos vinculados a la propiedad elegida
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> a) {}
        });
        mostrarPaso(actual);
        cargarPropiedades();
        // la lista y los precios pueden haber cambiado en Control: se actualizan sin recompilar
        Ajustes.refrescar(this, true, () -> { if (!isFinishing()) cargarCategorias(idCategoriaSel()); });
    }

    private void cargarCategorias(String idSel) {
        cats = Ajustes.lista(this, "categories.list", Ajustes.CATEGORIAS, true);
        List<String> nombres = new ArrayList<>();
        int sel = 0;
        for (int i = 0; i < cats.size(); i++) {
            nombres.add(cats.get(i).label);
            if (idSel != null && cats.get(i).id.equals(idSel)) sel = i;
        }
        spCat.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, nombres));
        spCat.setSelection(sel);
        actualizarPrecio();
    }

    private Ajustes.Opcion categoriaSel() {
        if (cats.isEmpty()) return null;
        return cats.get(Math.max(0, Math.min(spCat.getSelectedItemPosition(), cats.size() - 1)));
    }

    private String idCategoriaSel() { Ajustes.Opcion o = categoriaSel(); return o == null ? "OTRO" : o.id; }

    /** Precio de referencia del grupo de precios de la categoría elegida (settings pricing.*). */
    private String textoPrecio() {
        Ajustes.Opcion o = categoriaSel();
        if (o == null) return "";
        String g = o.grupo == null || o.grupo.isEmpty() ? "general" : o.grupo;
        double diag = Ajustes.precio(this, g, "diagnostic"), hora = Ajustes.precio(this, g, "hourly"), mult = Ajustes.precio(this, g, "emergency_multiplier");
        String t = String.format(java.util.Locale.US, "Precio de referencia: visita de diagnóstico $%.2f · por hora $%.2f", diag, hora);
        if (mult > 1.0) t += String.format(java.util.Locale.US, " · emergencias ×%.1f", mult);
        return t + ". El técnico confirma el precio antes de trabajar.";
    }

    private void actualizarPrecio() { if (tvPrecio != null) tvPrecio.setText(textoPrecio()); }

    private TextView errorCampo(LinearLayout dest) {
        TextView t = Ui.texto(this, "", 13, R.color.lc_rojo, true);
        t.setVisibility(android.view.View.GONE);
        dest.addView(t);
        return t;
    }

    private void mostrarError(TextView t, String msg) {
        t.setText(msg);
        t.setVisibility(msg == null || msg.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private void mostrarPaso(int n) {
        actual = n;
        for (int i = 0; i < PASOS; i++) paso[i].setVisibility(i == n ? android.view.View.VISIBLE : android.view.View.GONE);
        String[] t = {"Propiedad y equipo", "Servicio y problema", "Disponibilidad y fotos", "Revisar y enviar"};
        tvPaso.setText("Paso " + (n + 1) + " de " + PASOS + " · " + t[n]);
        siguiente.setVisibility(n < PASOS - 1 ? android.view.View.VISIBLE : android.view.View.GONE);
        enviar.setVisibility(n == PASOS - 1 ? android.view.View.VISIBLE : android.view.View.GONE);
        atras.setVisibility(n > 0 && solicitudCreada == 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        if (n == PASOS - 1) resumen.setText(textoResumen());
    }

    /** Valida solo el paso actual; el error se muestra junto al campo y los datos se conservan. */
    private void siguientePaso() {
        if (actual == 0) {
            if (props.isEmpty()) { mostrarError(errProp, "Primero agrega una propiedad en «Mis propiedades y equipos»."); return; }
            mostrarError(errProp, "");
        } else if (actual == 1) {
            String d = desc.getText().toString().trim();
            if (d.length() < 10) {
                mostrarError(errDesc, "Describe el problema con al menos 10 caracteres (llevas " + d.length() + ").");
                desc.requestFocus();
                return;
            }
            mostrarError(errDesc, "");
        }
        mostrarPaso(actual + 1);
    }

    private String textoResumen() {
        StringBuilder sb = new StringBuilder();
        if (!props.isEmpty() && spProp.getSelectedItemPosition() >= 0) {
            sb.append("Dirección: ").append(props.get(spProp.getSelectedItemPosition()).optString("direccion")).append("\n");
        }
        Object eq = spEquipo.getSelectedItem();
        sb.append("Equipo: ").append(eq == null ? "Sin especificar" : eq).append("\n");
        Ajustes.Opcion cs = categoriaSel();
        sb.append("Servicio: ").append(cs == null ? "—" : cs.label).append("\n");
        sb.append(textoPrecio()).append("\n");
        sb.append("Problema: ").append(desc.getText().toString().trim()).append("\n");
        sb.append(fechaPreferida > 0 ? "Fecha preferida: " + Ui.fecha(fechaPreferida) + " (por confirmar)" : "Sin fecha preferida");
        String v = ventana.getText().toString().trim();
        if (!v.isEmpty()) sb.append("\nDisponibilidad: ").append(v);
        return sb.toString();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("crid", clientRequestId);
        out.putLong("fecha", fechaPreferida);
        out.putInt("paso", actual);
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
                cargado = true;
                if (nombres.isEmpty()) {
                    mostrarError(errProp, r.ok() ? "Primero agrega una propiedad en «Mis propiedades y equipos»." : r.error());
                }
                spProp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, nombres));
            });
        });
    }

    private void llenarEquipos(int pos) {
        List<String> eq = new ArrayList<>();
        eq.add("Sin especificar");
        JSONArray a = pos >= 0 && pos < props.size() ? props.get(pos).optJSONArray("equipos") : null;
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
        if (d.length() < 10) {          // defensa: normalmente ya se validó en el paso 2
            mostrarPaso(1);
            mostrarError(errDesc, "Describe el problema con al menos 10 caracteres (llevas " + d.length() + ").");
            return;
        }
        enviando = true;
        enviar.setEnabled(false);
        atras.setVisibility(android.view.View.GONE);
        enviar.setText("ENVIANDO…");
        error.setText("");
        JSONObject prop = props.get(spProp.getSelectedItemPosition());
        JSONObject body = new JSONObject();
        try {
            body.put("propertyId", prop.optLong("id")).put("descripcion", d)
                    .put("categoria", idCategoriaSel()).put("clientRequestId", clientRequestId)
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
            if (r.codigo == 202 && r.cuerpo != null && r.cuerpo.optBoolean("enCola")) {
                // sin red incluso tras los reintentos: la solicitud quedó guardada y se envía sola al volver la conexión
                runOnUiThread(() -> {
                    enviando = false;
                    enviar.setText("SOLICITUD GUARDADA ✓");
                    new AlertDialog.Builder(this).setTitle("Solicitud guardada")
                            .setMessage("Ahora mismo no hay conexión. Tu solicitud quedó guardada en el teléfono y se enviará sola "
                                    + "cuando vuelva la red; no tienes que hacer nada más.\n\nLas fotos puedes agregarlas después desde el detalle.")
                            .setCancelable(false).setPositiveButton("Aceptar", (x, y) -> finish()).show();
                });
                return;
            }
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
                                    + "\n\nLa fecha que elegiste es solo una preferencia. Te avisaremos cuando L&C confirme la cita.")
                            .setCancelable(false).setPositiveButton("Aceptar", (x, y) -> finish()).show();
                } else {
                    enviar.setEnabled(true);
                    enviar.setText("ENVIAR SOLICITUD");
                    atras.setVisibility(android.view.View.VISIBLE);
                    if (r.codigo == 422 || r.codigo == 400) {
                        // el servidor rechazó un dato: se explica junto al campo y se conserva todo lo escrito
                        String m = r.error();
                        if (m.contains("descrip")) { mostrarPaso(1); mostrarError(errDesc, "Describe el problema con al menos 10 caracteres."); }
                        else error.setText(m);
                    } else {
                        error.setText(r.error() + (r.codigo == -1 ? " · tus datos siguen aquí; toca «Enviar» para reintentar sin duplicar." : ""));
                    }
                }
            });
        });
    }

    /** Imagen PNG generada en la app y rotulada como prueba: no se usa la galería ni la cámara. */
    private void agregarFotoPrueba() {
        if (fotosPrueba.size() >= 5) { tvFotoPrueba.setText("Máximo 5 fotos de prueba"); return; }
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(960, 720, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
        cv.drawColor(fotosPrueba.size() % 2 == 0 ? 0xFF171717 : 0xFF62666A);
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
