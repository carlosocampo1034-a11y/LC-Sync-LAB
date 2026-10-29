package com.lc.customer;

import android.content.ActivityNotFoundException;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.Executors;

/**
 * Registrar equipo (orden 73): «Escanear etiqueta» (foto o galería + OCR local con ML Kit, sin enviar la imagen a ningún
 * servidor) o «Ingresar manualmente». Los dos caminos terminan en el mismo formulario para revisar, corregir y guardar;
 * nada se guarda solo. También edita un equipo existente conservando su id y su propiedad.
 */
public class EquipoActivity extends AppCompatActivity {

    private long pid;
    private long equipoId = 0;                  // 0 = equipo nuevo
    private Ui.Pantalla p;
    private LinearLayout cuerpo;
    private EditText fTipo, fMarca, fModelo, fSerie, fCapacidad;
    private TextView aviso;
    private boolean formularioVisible = false;
    private final String[] ultimoOcr = new String[5];   // lo último que puso el OCR en cada campo (para no pisar lo que el cliente corrigió)
    private File fotoTemp;
    private ActivityResultLauncher<Uri> tomarFoto;
    private ActivityResultLauncher<String> elegirImagen;
    private Button guardar;
    private TextView escaneando;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pid = getIntent().getLongExtra("pid", 0);
        JSONObject existente = null;
        try {
            String js = getIntent().getStringExtra("equipo");
            if (js != null) existente = new JSONObject(js);
        } catch (Exception ignored) { }
        equipoId = existente == null ? 0 : existente.optLong("id");
        boolean editar = existente != null;
        p = Ui.pantalla(this, editar ? "Equipo" : "Registrar equipo", editar ? "Revisa o corrige sus datos" : null, true);
        cuerpo = new LinearLayout(this);
        cuerpo.setOrientation(LinearLayout.VERTICAL);
        p.contenido.addView(cuerpo);

        tomarFoto = registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
            if (ok && fotoTemp != null) leerEtiqueta(Uri.fromFile(fotoTemp));
            else if (!ok) Toast.makeText(this, "No se tomó la foto", Toast.LENGTH_SHORT).show();
        });
        elegirImagen = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> { if (uri != null) leerEtiqueta(uri); });

        if (editar) {
            construirFormulario(existente.optString("nombre"), existente.optString("marca"), existente.optString("modelo"),
                    existente.optString("serial"), existente.optString("capacidad"));
        } else {
            construirOpciones();
        }
    }

    // ── Elegir cómo registrar ────────────────────────────────────────────────────────────────────

    private void construirOpciones() {
        cuerpo.removeAllViews();
        p.inferior.removeAllViews();
        formularioVisible = false;
        cuerpo.addView(Ui.texto(this, "Elige cómo quieres registrarlo", 16, R.color.lc_sec, false));
        LinearLayout escanear = new LinearLayout(this);
        escanear.setOrientation(LinearLayout.HORIZONTAL);
        escanear.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFE91605);
        g.setCornerRadius(Ui.dp(this, 16));
        escanear.setBackground(g);
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_camera);
        escanear.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        TextView t = Ui.texto(this, "Escanear etiqueta", 18, android.R.color.white, true);
        t.setPadding(Ui.dp(this, 10), 0, 0, 0);
        escanear.addView(t);
        escanear.setOnClickListener(v -> menuEscaneo());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 64));
        lp.topMargin = Ui.dp(this, 16);
        cuerpo.addView(escanear, lp);
        TextView ayuda = Ui.texto(this, "Toma una foto de la etiqueta de tu equipo y completaremos los datos automáticamente", 14, R.color.lc_sec, false);
        ayuda.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 20));
        cuerpo.addView(ayuda);
        Button manual = Ui.boton(this, cuerpo, "Ingresar manualmente", false);
        manual.setAllCaps(false);
        manual.setOnClickListener(v -> construirFormulario("", "", "", "", ""));
    }

    // ── Formulario (único para escaneo, manual y edición) ────────────────────────────────────────

    private void construirFormulario(String tipo, String marca, String modelo, String serie, String capacidad) {
        cuerpo.removeAllViews();
        p.inferior.removeAllViews();
        formularioVisible = true;
        aviso = Ui.texto(this, "", 14, R.color.lc_texto, false);
        aviso.setVisibility(View.GONE);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFF1F2F3);
        g.setCornerRadius(Ui.dp(this, 12));
        aviso.setBackground(g);
        aviso.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        cuerpo.addView(aviso);
        escaneando = Ui.texto(this, "Leyendo la etiqueta…", 14, R.color.lc_rojo, true);
        escaneando.setVisibility(View.GONE);
        cuerpo.addView(escaneando);
        fTipo = campoEtiquetado("Tipo de equipo", "Ej. Aire acondicionado, Nevera", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        fMarca = campoEtiquetado("Marca (opcional)", "Marca del fabricante", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        fModelo = campoEtiquetado("Modelo (opcional)", "Número de modelo", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        fSerie = campoEtiquetado("Número de serie (opcional)", "Número de serie", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        fCapacidad = campoEtiquetado("Capacidad o especificaciones (opcional)", "Ej. 3 TON · 36,000 BTU/H", InputType.TYPE_CLASS_TEXT);
        fTipo.setText(tipo); fMarca.setText(marca); fModelo.setText(modelo); fSerie.setText(serie); fCapacidad.setText(capacidad);
        TextView otra = Ui.texto(this, "Escanear etiqueta de nuevo", 15, R.color.lc_rojo, true);
        otra.setMinHeight(Ui.dp(this, 48));
        otra.setGravity(Gravity.CENTER_VERTICAL);
        otra.setOnClickListener(v -> menuEscaneo());
        cuerpo.addView(otra);
        guardar = Ui.boton(this, p.inferior, equipoId > 0 || equipoId < 0 ? "Guardar cambios" : "Guardar equipo", true);
        guardar.setAllCaps(false);
        guardar.setOnClickListener(v -> guardarEquipo());
    }

    /** Etiqueta fija sobre el campo: el texto no desaparece cuando el campo se llena (a mano o con el OCR). */
    private EditText campoEtiquetado(String etiqueta, String hint, int tipo) {
        TextView t = Ui.texto(this, etiqueta, 13, R.color.lc_sec, true);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), 0, 0);
        cuerpo.addView(t);
        return Ui.campo(this, cuerpo, hint, tipo);
    }

    private EditText[] campos() { return new EditText[]{fTipo, fMarca, fModelo, fSerie, fCapacidad}; }

    // ── Cámara / galería / OCR ───────────────────────────────────────────────────────────────────

    private void menuEscaneo() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Escanear etiqueta")
                .setItems(new String[]{"Tomar foto con la cámara", "Elegir una foto de la galería"}, (d, which) -> {
                    if (which == 0) abrirCamara(); else elegirImagen.launch("image/*");
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void abrirCamara() {
        try {
            File dir = getExternalFilesDir("fotos");
            if (dir != null) dir.mkdirs();
            fotoTemp = File.createTempFile("etiqueta_", ".jpg", dir);
            tomarFoto.launch(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", fotoTemp));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Este teléfono no tiene una cámara disponible. Elige una foto de la galería o escribe los datos.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "No se pudo abrir la cámara. Puedes elegir una foto de la galería o escribir los datos.", Toast.LENGTH_LONG).show();
        }
    }

    /** OCR local: la imagen no sale del teléfono. */
    private void leerEtiqueta(Uri uri) {
        if (!formularioVisible) construirFormulario("", "", "", "", "");
        escaneando.setVisibility(View.VISIBLE);
        guardar.setEnabled(false);
        final TextRecognizer reconocedor = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try {
            InputImage img = InputImage.fromFilePath(this, uri);
            reconocedor.process(img)
                    .addOnSuccessListener(texto -> { reconocedor.close(); aplicarResultado(EtiquetaParser.analizar(texto.getText())); })
                    .addOnFailureListener(e -> { reconocedor.close(); falloLectura("No se pudo leer la etiqueta. Prueba con más luz y la etiqueta de frente, o escribe los datos."); });
        } catch (Exception e) {
            reconocedor.close();
            falloLectura("No se pudo abrir la foto. Prueba con otra o escribe los datos.");
        }
    }

    private void falloLectura(String msg) {
        escaneando.setVisibility(View.GONE);
        guardar.setEnabled(true);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    /** Rellena solo lo identificado y solo donde el cliente no escribió algo distinto del último resultado del OCR. */
    private void aplicarResultado(EtiquetaParser.Resultado r) {
        escaneando.setVisibility(View.GONE);
        guardar.setEnabled(true);
        if (r.vacio()) {
            mostrarAviso("No pudimos identificar datos en la foto. Prueba de nuevo con más luz o escribe los datos manualmente.");
            return;
        }
        String[] valores = {r.tipo, r.marca, r.modelo, r.serial, r.capacidad};
        String[] nombres = {"tipo de equipo", "marca", "modelo", "número de serie", "capacidad"};
        EditText[] cs = campos();
        StringBuilder faltan = new StringBuilder();
        int llenos = 0;
        for (int i = 0; i < 5; i++) {
            if (valores[i] == null) { if (faltan.length() > 0) faltan.append(", "); faltan.append(nombres[i]); continue; }
            String actual = cs[i].getText().toString().trim();
            boolean sinTocar = actual.isEmpty() || actual.equals(ultimoOcr[i]);
            if (sinTocar) { cs[i].setText(valores[i]); ultimoOcr[i] = valores[i]; llenos++; }
        }
        mostrarAviso("Revisa los datos: completamos " + llenos + (llenos == 1 ? " campo" : " campos") + " con lo que se lee en la etiqueta."
                + (faltan.length() > 0 ? "\nNo se identificó: " + faltan + ". Complétalo si lo necesitas." : ""));
    }

    private void mostrarAviso(String msg) {
        aviso.setText(msg);
        aviso.setVisibility(View.VISIBLE);
    }

    // ── Guardar (nada se guarda solo) ────────────────────────────────────────────────────────────

    private void guardarEquipo() {
        String tipo = fTipo.getText().toString().trim();
        if (tipo.length() < 2) { fTipo.setError("Escribe el tipo de equipo"); fTipo.requestFocus(); return; }
        final JSONObject body = new JSONObject();
        try {
            body.put("nombre", tipo).put("marca", fMarca.getText().toString().trim()).put("modelo", fModelo.getText().toString().trim())
                    .put("serial", fSerie.getText().toString().trim()).put("capacidad", fCapacidad.getText().toString().trim());
        } catch (Exception ignored) { }
        guardar.setEnabled(false);
        final String ruta = equipoId != 0 ? "/customer/equipment/" + equipoId + "/update" : "/customer/properties/" + pid + "/equipment";
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.post(this, ruta, body);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                guardar.setEnabled(true);
                if (r.ok()) {
                    Toast.makeText(this, equipoId != 0 ? "Equipo actualizado" : "Equipo guardado", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                } else Toast.makeText(this, r.error(), Toast.LENGTH_LONG).show();
            });
        });
    }
}
