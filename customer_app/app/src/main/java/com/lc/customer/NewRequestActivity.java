package com.lc.customer;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
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

/**
 * Solicitar servicio en 4 pasos (como la maqueta): categoría · problema, fotos y equipo · dirección · revisar y enviar.
 * Al terminar muestra la confirmación con el número de solicitud.
 */
public class NewRequestActivity extends AppCompatActivity {

    private static final int PASOS = 4;
    private static final int MAX_FOTOS = 4;
    private static final int MAX_DESC = 500;

    private final List<JSONObject> props = new ArrayList<>();
    private int propSel = 0;
    private int catSel = -1;                                     // -1 = aún sin elegir
    private Spinner spEquipo;
    private List<Ajustes.Opcion> cats = new ArrayList<>();       // categorías: lista editable (settings categories.list)
    private final List<LinearLayout> tarjetasCat = new ArrayList<>();
    private LinearLayout gridCat, listaProps, filaFotos, resumenBox, filaTerminos;
    private TextView tvPrecio, tvContador, tvTitulo, tvSubtitulo, tvProgreso;
    private final View[] segmentos = new View[PASOS];
    private EditText desc, ventana, marca;
    private TextView tvFecha, error, errCat, errProp, errDesc, tvFotoPrueba, tvMapaDir;
    private CheckBox terminos;
    private Button enviar, siguiente, atras;
    private Ui.Pantalla pan;
    private final LinearLayout[] paso = new LinearLayout[PASOS];
    private int actual = 0;
    private long fechaPreferida = 0;
    private final List<Uri> fotos = new ArrayList<>();
    private final List<String> fotoMimes = new ArrayList<>();
    private final java.util.Set<Integer> fotosRealesSubidas = new java.util.HashSet<>();
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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        clientRequestId = b != null ? b.getString("crid") : null;
        if (clientRequestId == null) clientRequestId = UUID.randomUUID().toString();  // mismo id en reintentos
        if (b != null) { fechaPreferida = b.getLong("fecha", 0); actual = b.getInt("paso", 0); catSel = b.getInt("cat", -1); propSel = b.getInt("prop", 0); }
        pan = Ui.pantalla(this, "Solicitar servicio", null, true);
        final Ui.Pantalla p = pan;
        // Cabecera: título del paso, subtítulo y barra de progreso «n de 4»
        tvTitulo = (TextView) p.cabecera.getChildAt(1);
        tvSubtitulo = Ui.texto(this, "", 14, R.color.lc_sec, false);
        p.cabecera.addView(tvSubtitulo);
        LinearLayout prog = new LinearLayout(this);
        prog.setOrientation(LinearLayout.HORIZONTAL);
        prog.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout barra = new LinearLayout(this);
        barra.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < PASOS; i++) {
            segmentos[i] = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 4), 1f);
            lp.rightMargin = Ui.dp(this, 4);
            barra.addView(segmentos[i], lp);
        }
        prog.addView(barra, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvProgreso = Ui.texto(this, "", 13, R.color.lc_sec, true);
        tvProgreso.setPadding(Ui.dp(this, 10), 0, 0, 0);
        prog.addView(tvProgreso);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
        plp.topMargin = Ui.dp(this, 10);
        p.cabecera.addView(prog, plp);

        for (int i = 0; i < PASOS; i++) {
            paso[i] = new LinearLayout(this);
            paso[i].setOrientation(LinearLayout.VERTICAL);
            p.contenido.addView(paso[i]);
        }

        // Paso 1: categoría (tarjetas con ícono; la lista viene de Control)
        gridCat = new LinearLayout(this);
        gridCat.setOrientation(LinearLayout.VERTICAL);
        paso[0].addView(gridCat);
        errCat = errorCampo(paso[0]);
        tvPrecio = Ui.texto(this, "", 13, R.color.lc_sec, false);
        tvPrecio.setPadding(0, Ui.dp(this, 10), 0, 0);
        paso[0].addView(tvPrecio);
        cargarCategorias();

        // Paso 2: problema (con contador), fotos (hasta 4), equipo y marca
        desc = Ui.campo(this, paso[1], "Ej. El aire acondicionado no enfría y hace mucho ruido.",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        desc.setMinLines(4);
        desc.setGravity(Gravity.TOP | Gravity.START);
        desc.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_DESC)});
        tvContador = Ui.texto(this, "0/" + MAX_DESC, 12, R.color.lc_sec, false);
        tvContador.setGravity(Gravity.END);
        paso[1].addView(tvContador);
        desc.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable s) { tvContador.setText(s.length() + "/" + MAX_DESC); }
        });
        errDesc = errorCampo(paso[1]);
        Ui.seccion(this, paso[1], "Fotos (opcional)");
        filaFotos = new LinearLayout(this);
        filaFotos.setOrientation(LinearLayout.HORIZONTAL);
        paso[1].addView(filaFotos);
        tvFotoPrueba = Ui.texto(this, "+ Agregar foto de prueba (laboratorio)", 15, R.color.lc_rojo, true);
        if (!CloudApi.activo()) {   // las fotos sintéticas son herramienta de laboratorio, no del cliente
            tvFotoPrueba.setMinHeight(Ui.dp(this, 48));
            tvFotoPrueba.setGravity(Gravity.CENTER_VERTICAL);
            tvFotoPrueba.setOnClickListener(v -> agregarFotoPrueba());
            paso[1].addView(tvFotoPrueba);
        }
        elegirFoto = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri == null) return;
            String mime = getContentResolver().getType(uri);
            if (!"image/jpeg".equals(mime) && !"image/png".equals(mime)) {
                Toast.makeText(this, "Solo fotos JPG o PNG", Toast.LENGTH_SHORT).show();
                return;
            }
            agregarFoto(uri, mime);
        });
        tomarFoto = registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
            if (!ok || tempFotoFile == null || !tempFotoFile.exists() || tempFotoFile.length() == 0) return;
            agregarFoto(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", tempFotoFile), "image/jpeg");
        });
        pintarFotos();
        Ui.seccion(this, paso[1], "Equipo");
        spEquipo = new Spinner(this);
        paso[1].addView(spEquipo);
        llenarEquipos(propSel);
        marca = Ui.campo(this, paso[1], "Marca (opcional)");

        // Paso 3: dirección (propiedades del cliente), mapa y disponibilidad
        listaProps = new LinearLayout(this);
        listaProps.setOrientation(LinearLayout.VERTICAL);
        paso[2].addView(listaProps);
        errProp = errorCampo(paso[2]);
        TextView nueva = Ui.texto(this, "+  Agregar nueva dirección", 15, R.color.lc_rojo, true);
        nueva.setGravity(Gravity.CENTER);
        nueva.setMinHeight(Ui.dp(this, 52));
        GradientDrawable punteado = new GradientDrawable();
        punteado.setCornerRadius(Ui.dp(this, 12));
        punteado.setColor(0xFFFFFFFF);
        punteado.setStroke(Ui.dp(this, 1), ContextCompat.getColor(this, R.color.lc_rojo), Ui.dp(this, 6), Ui.dp(this, 4));
        nueva.setBackground(punteado);
        nueva.setOnClickListener(v -> startActivity(new Intent(this, PropertiesActivity.class)));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.topMargin = Ui.dp(this, 10);
        paso[2].addView(nueva, nlp);
        // Mapa: marcador de posición con el pin hasta contar con la clave de Google Maps (pendiente documentado)
        FrameLayout mapa = new FrameLayout(this);
        GradientDrawable mapaBg = new GradientDrawable();
        mapaBg.setCornerRadius(Ui.dp(this, 12));
        mapaBg.setColor(0xFFE8EAED);
        mapa.setBackground(mapaBg);
        ImageView pin = new ImageView(this);
        pin.setImageResource(R.drawable.ic_pin);
        mapa.addView(pin, new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.CENTER));
        tvMapaDir = Ui.texto(this, "", 12, R.color.lc_sec, false);
        tvMapaDir.setSingleLine(true);
        tvMapaDir.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 8));
        mapa.addView(tvMapaDir, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 140));
        mlp.topMargin = Ui.dp(this, 14);
        paso[2].addView(mapa, mlp);
        Ui.seccion(this, paso[2], "Disponibilidad (opcional)");
        tvFecha = Ui.texto(this, "Elegir fecha y hora preferidas", 15, R.color.lc_rojo, true);
        tvFecha.setMinHeight(Ui.dp(this, 48));
        tvFecha.setGravity(Gravity.CENTER_VERTICAL);
        tvFecha.setOnClickListener(v -> elegirFecha());
        paso[2].addView(tvFecha);
        if (fechaPreferida > 0) tvFecha.setText("Preferencia: " + Ui.fecha(fechaPreferida));
        ventana = Ui.campo(this, paso[2], "Otra disponibilidad (ej. tardes después de las 3)");
        paso[2].addView(Ui.texto(this, "La fecha es solo una preferencia: la cita queda confirmada cuando L&C la apruebe.", 13, R.color.lc_sec, false));

        // Paso 4: resumen y términos
        resumenBox = new LinearLayout(this);
        resumenBox.setOrientation(LinearLayout.VERTICAL);
        paso[3].addView(resumenBox);
        filaTerminos = new LinearLayout(this);
        filaTerminos.setOrientation(LinearLayout.HORIZONTAL);
        filaTerminos.setGravity(Gravity.CENTER_VERTICAL);
        terminos = new CheckBox(this);
        terminos.setButtonTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.lc_rojo)));
        filaTerminos.addView(terminos);
        TextView tt = Ui.texto(this, "Acepto los términos y condiciones", 14, R.color.lc_texto, false);
        tt.setPaintFlags(tt.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        tt.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Términos y condiciones")
                .setMessage("Autorizo a L&C Home Services a revisar mi solicitud y a contactarme para confirmar la cita. "
                        + "El técnico confirma el precio antes de trabajar y no se cobra sin tu aprobación.\n\n"
                        + "(Texto provisional: el texto legal definitivo lo aporta L&C.)")
                .setPositiveButton("Entendido", null).show());
        filaTerminos.addView(tt);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = Ui.dp(this, 14);
        paso[3].addView(filaTerminos, tlp);
        error = Ui.texto(this, "", 14, R.color.lc_rojo, true);
        paso[3].addView(error);

        // Botones: « ← Atrás » | « Siguiente → » (en el último paso, « Enviar solicitud → »)
        p.inferior.removeAllViews();
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        atras = botonPaso("←  Atrás", false);
        siguiente = botonPaso("Siguiente  →", true);
        enviar = botonPaso("Enviar solicitud  →", true);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f);
        l1.rightMargin = Ui.dp(this, 8);
        fila.addView(atras, l1);
        fila.addView(siguiente, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f));
        fila.addView(enviar, new LinearLayout.LayoutParams(0, Ui.dp(this, 52), 1f));
        p.inferior.addView(fila);
        atras.setOnClickListener(v -> { if (actual > 0) mostrarPaso(actual - 1); });
        siguiente.setOnClickListener(v -> siguientePaso());
        enviar.setOnClickListener(v -> enviar());
        terminos.setOnCheckedChangeListener((v, on) -> { if (!enviando) enviar.setEnabled(on); });
        enviar.setEnabled(false);
        mostrarPaso(actual);
        cargarPropiedades();
        // la lista y los precios pueden haber cambiado en Control: se actualizan sin recompilar
        Ajustes.refrescar(this, true, () -> { if (!isFinishing()) cargarCategorias(); });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (solicitudCreada == 0 && !props.isEmpty()) cargarPropiedades();   // por si agregó una dirección nueva
    }

    private Button botonPaso(String texto, boolean principal) {
        Button b = new Button(this);
        b.setText(texto);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setTextColor(principal ? 0xFFFFFFFF : ContextCompat.getColor(this, R.color.lc_rojo));
        b.setBackground(ContextCompat.getDrawable(this, principal ? R.drawable.bg_boton : R.drawable.bg_boton_sec));
        b.setStateListAnimator(null);
        return b;
    }

    // ── Categorías ───────────────────────────────────────────────────────────────────────────────

    private static int iconoCategoria(String id) {
        switch (id == null ? "" : id) {
            case "HVAC": return R.drawable.ic_cat_hvac;
            case "PLOMERIA": return R.drawable.ic_cat_plomeria;
            case "ELECTRICIDAD": return R.drawable.ic_cat_electricidad;
            case "ELECTRODOMESTICOS": return R.drawable.ic_cat_electro;
            case "CALENTADOR": return R.drawable.ic_cat_calentador;
            default: return R.drawable.ic_cat_otros;
        }
    }

    private void cargarCategorias() {
        String idSel = catSel >= 0 && catSel < cats.size() ? cats.get(catSel).id : null;
        cats = Ajustes.lista(this, "categories.list", Ajustes.CATEGORIAS, true);
        catSel = -1;
        for (int i = 0; i < cats.size(); i++) if (cats.get(i).id.equals(idSel)) catSel = i;
        gridCat.removeAllViews();
        tarjetasCat.clear();
        LinearLayout fila = null;
        for (int i = 0; i < cats.size(); i++) {
            if (i % 2 == 0) {
                fila = new LinearLayout(this);
                fila.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
                flp.topMargin = Ui.dp(this, 10);
                gridCat.addView(fila, flp);
            }
            final int idx = i;
            Ajustes.Opcion o = cats.get(i);
            LinearLayout t = new LinearLayout(this);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setGravity(Gravity.CENTER);
            t.setMinimumHeight(Ui.dp(this, 112));
            t.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
            ImageView ic = new ImageView(this);
            ic.setImageResource(iconoCategoria(o.id));
            t.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36)));
            TextView x = Ui.texto(this, o.label, 14, R.color.lc_texto, true);
            x.setGravity(Gravity.CENTER);
            x.setPadding(0, Ui.dp(this, 8), 0, 0);
            t.addView(x);
            t.setOnClickListener(v -> { catSel = idx; mostrarError(errCat, ""); pintarCategorias(); actualizarPrecio(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.leftMargin = Ui.dp(this, 5);
            lp.rightMargin = Ui.dp(this, 5);
            fila.addView(t, lp);
            tarjetasCat.add(t);
        }
        if (fila != null && cats.size() % 2 == 1) {
            fila.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        pintarCategorias();
        actualizarPrecio();
    }

    private void pintarCategorias() {
        for (int i = 0; i < tarjetasCat.size(); i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(this, 16));
            g.setColor(0xFFF5F5F7);
            boolean on = i == catSel;
            g.setStroke(Ui.dp(this, on ? 2 : 1), ContextCompat.getColor(this, on ? R.color.lc_rojo : R.color.lc_borde));
            tarjetasCat.get(i).setBackground(g);
        }
    }

    private Ajustes.Opcion categoriaSel() {
        return catSel >= 0 && catSel < cats.size() ? cats.get(catSel) : null;
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

    // ── Fotos ────────────────────────────────────────────────────────────────────────────────────

    private void agregarFoto(Uri uri, String mime) {
        if (fotos.size() >= MAX_FOTOS) { Toast.makeText(this, "Máximo " + MAX_FOTOS + " fotos", Toast.LENGTH_SHORT).show(); return; }
        fotos.add(uri);
        fotoMimes.add(mime);
        pintarFotos();
    }

    private void pintarFotos() {
        filaFotos.removeAllViews();
        int lado = Ui.dp(this, 72);
        for (int i = 0; i < fotos.size(); i++) {
            final int idx = i;
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(this, 12));
            bg.setColor(0xFFE5E5E8);
            iv.setBackground(bg);
            iv.setClipToOutline(true);
            iv.setImageURI(fotos.get(i));
            iv.setContentDescription("Foto " + (i + 1) + ", toca para quitar");
            iv.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("¿Quitar esta foto?")
                    .setPositiveButton("Quitar", (d, w) -> {
                        fotos.remove(idx);
                        fotoMimes.remove(idx);
                        pintarFotos();
                    }).setNegativeButton("Conservar", null).show());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(lado, lado);
            lp.rightMargin = Ui.dp(this, 8);
            filaFotos.addView(iv, lp);
        }
        if (fotos.size() < MAX_FOTOS) {
            TextView mas = Ui.texto(this, "+\nAgregar foto", 12, R.color.lc_rojo, true);
            mas.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(Ui.dp(this, 12));
            bg.setColor(0xFFFFFFFF);
            bg.setStroke(Ui.dp(this, 1), ContextCompat.getColor(this, R.color.lc_rojo), Ui.dp(this, 6), Ui.dp(this, 4));
            mas.setBackground(bg);
            mas.setOnClickListener(v -> mostrarMenuFoto());
            filaFotos.addView(mas, new LinearLayout.LayoutParams(lado, lado));
        }
    }

    // ── Propiedades y equipos ────────────────────────────────────────────────────────────────────

    private TextView errorCampo(LinearLayout dest) {
        TextView t = Ui.texto(this, "", 13, R.color.lc_rojo, true);
        t.setVisibility(View.GONE);
        dest.addView(t);
        return t;
    }

    private void mostrarError(TextView t, String msg) {
        t.setText(msg);
        t.setVisibility(msg == null || msg.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void cargarPropiedades() {
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r = Api.get(this, "/customer/properties");
            runOnUiThread(() -> {
                if (isFinishing()) return;
                props.clear();
                JSONArray ps = r.ok() ? r.cuerpo.optJSONArray("properties") : null;
                for (int i = 0; ps != null && i < ps.length(); i++) props.add(ps.optJSONObject(i));
                if (propSel >= props.size()) propSel = 0;
                if (props.isEmpty()) {
                    mostrarError(errProp, r.ok() ? "Primero agrega una dirección con «Agregar nueva dirección»." : r.error());
                } else {
                    mostrarError(errProp, "");
                }
                pintarPropiedades();
                llenarEquipos(propSel);
            });
        });
    }

    private void pintarPropiedades() {
        listaProps.removeAllViews();
        for (int i = 0; i < props.size(); i++) {
            final int idx = i;
            JSONObject pr = props.get(i);
            boolean on = i == propSel;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(Ui.dp(this, 12));
            g.setColor(0xFFFFFFFF);
            g.setStroke(Ui.dp(this, on ? 2 : 1), ContextCompat.getColor(this, on ? R.color.lc_rojo : R.color.lc_borde));
            card.setBackground(g);
            LinearLayout textos = new LinearLayout(this);
            textos.setOrientation(LinearLayout.VERTICAL);
            String tipo = Ajustes.etiqueta(this, "property_types.list", Ajustes.TIPOS_PROPIEDAD, pr.optString("tipoPropiedad", ""));
            textos.addView(Ui.texto(this, tipo.isEmpty() ? "Propiedad" : tipo, 15, R.color.lc_texto, true));
            textos.addView(Ui.texto(this, pr.optString("direccion"), 13, R.color.lc_sec, false));
            card.addView(textos, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView radio = Ui.texto(this, on ? "●" : "○", 22, on ? R.color.lc_rojo : R.color.lc_sec, true);
            card.addView(radio);
            card.setOnClickListener(v -> {
                propSel = idx;
                mostrarError(errProp, "");
                pintarPropiedades();
                llenarEquipos(propSel);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = Ui.dp(this, 10);
            listaProps.addView(card, lp);
        }
        tvMapaDir.setText(propSel < props.size() ? props.get(propSel).optString("direccion") : "");
    }

    private void llenarEquipos(int pos) {
        if (spEquipo == null) return;
        List<String> eq = new ArrayList<>();
        eq.add("Sin especificar");
        JSONArray a = pos >= 0 && pos < props.size() ? props.get(pos).optJSONArray("equipos") : null;
        for (int i = 0; a != null && i < a.length(); i++) eq.add(a.optJSONObject(i).optString("nombre"));
        spEquipo.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, eq));
    }

    // ── Pasos ────────────────────────────────────────────────────────────────────────────────────

    private void mostrarPaso(int n) {
        actual = n;
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View foco = getCurrentFocus();
        if (imm != null && foco != null) { imm.hideSoftInputFromWindow(foco.getWindowToken(), 0); foco.clearFocus(); }
        for (int i = 0; i < PASOS; i++) paso[i].setVisibility(i == n ? View.VISIBLE : View.GONE);
        String[] t = {"Selecciona una categoría", "Describe el problema", "¿Dónde es el servicio?", "Revisa y envía"};
        String[] s = {"¿Qué servicio necesitas?", "Cuéntanos más sobre lo que sucede.", "Selecciona una propiedad o agrega una nueva.",
                "Confirma la información de tu solicitud."};
        tvTitulo.setText(t[n]);
        tvTitulo.setTextSize(24f);
        tvSubtitulo.setText(s[n]);
        tvProgreso.setText((n + 1) + " de " + PASOS);
        for (int i = 0; i < PASOS; i++) {
            segmentos[i].setBackgroundColor(ContextCompat.getColor(this, i <= n ? R.color.lc_rojo : R.color.lc_borde));
        }
        siguiente.setVisibility(n < PASOS - 1 ? View.VISIBLE : View.GONE);
        enviar.setVisibility(n == PASOS - 1 ? View.VISIBLE : View.GONE);
        atras.setVisibility(n > 0 && solicitudCreada == 0 ? View.VISIBLE : View.GONE);
        if (n == PASOS - 1) pintarResumen();
    }

    /** Valida solo el paso actual; el error se muestra junto al campo y los datos se conservan. */
    private void siguientePaso() {
        if (actual == 0) {
            if (catSel < 0) { mostrarError(errCat, "Elige una categoría para continuar."); return; }
            mostrarError(errCat, "");
        } else if (actual == 1) {
            String d = desc.getText().toString().trim();
            if (d.length() < 10) {
                mostrarError(errDesc, "Describe el problema con al menos 10 caracteres (llevas " + d.length() + ").");
                desc.requestFocus();
                return;
            }
            mostrarError(errDesc, "");
        } else if (actual == 2) {
            if (props.isEmpty()) { mostrarError(errProp, "Primero agrega una dirección con «Agregar nueva dirección»."); return; }
            mostrarError(errProp, "");
        }
        mostrarPaso(actual + 1);
    }

    private void filaResumen(String etiqueta, String valor) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        TextView e = Ui.texto(this, etiqueta, 13, R.color.lc_sec, true);
        f.addView(e, new LinearLayout.LayoutParams(Ui.dp(this, 92), -2));
        f.addView(Ui.texto(this, valor, 14, R.color.lc_texto, false), new LinearLayout.LayoutParams(0, -2, 1f));
        resumenBox.addView(f);
        View l = new View(this);
        l.setBackgroundColor(ContextCompat.getColor(this, R.color.lc_borde));
        resumenBox.addView(l, new LinearLayout.LayoutParams(-1, Ui.dp(this, 1)));
    }

    private void pintarResumen() {
        resumenBox.removeAllViews();
        Ajustes.Opcion cs = categoriaSel();
        filaResumen("Categoría", cs == null ? "—" : cs.label);
        filaResumen("Problema", desc.getText().toString().trim());
        filaResumen("Dirección", propSel < props.size() ? props.get(propSel).optString("direccion") : "—");
        int nf = fotos.size() + fotosPrueba.size();
        filaResumen("Fotos", nf == 0 ? "Sin fotos" : nf + (nf == 1 ? " foto adjunta" : " fotos adjuntas"));
        Object eq = spEquipo.getSelectedItem();
        String m = marca.getText().toString().trim();
        filaResumen("Equipo", (eq == null ? "Sin especificar" : eq.toString()) + (m.isEmpty() ? "" : " · " + m));
        filaResumen("Fecha", fechaPreferida > 0 ? Ui.fecha(fechaPreferida) + " (por confirmar)" : "Sin fecha preferida");
        String v = ventana.getText().toString().trim();
        if (!v.isEmpty()) filaResumen("Disponibilidad", v);
        TextView precio = Ui.texto(this, textoPrecio(), 13, R.color.lc_sec, false);
        precio.setPadding(0, Ui.dp(this, 10), 0, 0);
        resumenBox.addView(precio);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("crid", clientRequestId);
        out.putLong("fecha", fechaPreferida);
        out.putInt("paso", actual);
        out.putInt("cat", catSel);
        out.putInt("prop", propSel);
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

    // ── Envío ────────────────────────────────────────────────────────────────────────────────────

    private void enviar() {
        if (enviando || props.isEmpty() || !terminos.isChecked()) return;
        String d = desc.getText().toString().trim();
        if (d.length() < 10) {          // defensa: normalmente ya se validó en el paso 2
            mostrarPaso(1);
            mostrarError(errDesc, "Describe el problema con al menos 10 caracteres (llevas " + d.length() + ").");
            return;
        }
        enviando = true;
        enviar.setEnabled(false);
        atras.setVisibility(View.GONE);
        enviar.setText("Enviando…");
        error.setText("");
        JSONObject prop = props.get(propSel);
        JSONObject body = new JSONObject();
        try {
            String mk = marca.getText().toString().trim();
            body.put("propertyId", prop.optLong("id")).put("descripcion", mk.isEmpty() ? d : d + "\nMarca: " + mk)
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
                    enviar.setText("Solicitud guardada ✓");
                    new AlertDialog.Builder(this).setTitle("Solicitud guardada")
                            .setMessage("Ahora mismo no hay conexión. Tu solicitud quedó guardada en el teléfono y se enviará sola "
                                    + "cuando vuelva la red; no tienes que hacer nada más.\n\nLas fotos puedes agregarlas después desde el detalle.")
                            .setCancelable(false).setPositiveButton("Aceptar", (x, y) -> finish()).show();
                });
                return;
            }
            int fallidas = 0;
            if (r.ok()) {
                solicitudCreada = r.cuerpo.optLong("id");
                for (int i = 0; i < fotos.size(); i++) {
                    if (fotosRealesSubidas.contains(i)) continue;
                    byte[] datos = leer(fotos.get(i));
                    Api.Respuesta rf = datos == null ? null : Api.subirFoto(this, solicitudCreada, datos, fotoMimes.get(i), "BEFORE");
                    if (rf != null && rf.ok()) fotosRealesSubidas.add(i); else fallidas++;
                }
                for (int i = 0; i < fotosPrueba.size(); i++) {
                    long cpid = idsFotosPrueba.get(i);
                    if (fotosSubidas.contains(cpid)) continue;
                    Api.Respuesta rf = Api.subirFoto(this, solicitudCreada, fotosPrueba.get(i), "image/png", "BEFORE", cpid);
                    if (rf.ok()) fotosSubidas.add(cpid); else fallidas++;
                }
            }
            final int fallos = fallidas;
            runOnUiThread(() -> {
                enviando = false;
                if (r.ok() && fallos > 0) {
                    // la solicitud quedó registrada; las fotos que faltan se reintentan sin duplicar
                    enviar.setEnabled(true);
                    enviar.setText("Reintentar fotos");
                    error.setText("Solicitud Nº " + solicitudCreada + " registrada. No se pudieron subir " + fallos
                            + " foto(s); toca para reintentar.");
                } else if (r.ok()) {
                    mostrarConfirmacion(r.cuerpo.optLong("id"));
                } else {
                    enviar.setEnabled(terminos.isChecked());
                    enviar.setText("Enviar solicitud  →");
                    atras.setVisibility(View.VISIBLE);
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

    /** Confirmación: check verde, número de solicitud y los dos caminos siguientes. */
    private void mostrarConfirmacion(long id) {
        tvTitulo.setText("");
        tvTitulo.setVisibility(View.GONE);
        tvSubtitulo.setVisibility(View.GONE);
        ((View) tvProgreso.getParent()).setVisibility(View.GONE);
        pan.cabecera.getChildAt(0).setVisibility(View.GONE);   // «← Volver»: ya no hay nada que volver a editar
        pan.contenido.removeAllViews();
        pan.inferior.removeAllViews();
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(0, Ui.dp(this, 48), 0, 0);
        TextView check = Ui.texto(this, "✓", 56, android.R.color.white, true);
        check.setGravity(Gravity.CENTER);
        GradientDrawable verde = new GradientDrawable();
        verde.setShape(GradientDrawable.OVAL);
        verde.setColor(ContextCompat.getColor(this, R.color.lc_ok));
        check.setBackground(verde);
        c.addView(check, new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 112)));
        TextView t1 = Ui.texto(this, "¡Solicitud enviada!", 26, R.color.lc_texto, true);
        t1.setGravity(Gravity.CENTER);
        t1.setPadding(0, Ui.dp(this, 24), 0, 0);
        c.addView(t1);
        TextView t2 = Ui.texto(this, "Solicitud #" + id, 18, R.color.lc_rojo, true);
        t2.setGravity(Gravity.CENTER);
        t2.setPadding(0, Ui.dp(this, 8), 0, 0);
        c.addView(t2);
        TextView t3 = Ui.texto(this, "Te notificaremos cuando sea asignada.", 15, R.color.lc_sec, false);
        t3.setGravity(Gravity.CENTER);
        t3.setPadding(0, Ui.dp(this, 12), 0, 0);
        c.addView(t3);
        TextView t4 = Ui.texto(this, "La fecha que elegiste es solo una preferencia: la cita se confirma cuando L&C la apruebe.", 13, R.color.lc_sec, false);
        t4.setGravity(Gravity.CENTER);
        t4.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), 0);
        c.addView(t4);
        pan.contenido.addView(c);
        Button ver = botonPaso("Ver mis solicitudes", true);
        ver.setOnClickListener(v -> {
            startActivity(new Intent(this, HomeActivity.class).putExtra("tab", 1).putExtra("filtro", 1)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
        Button otra = botonPaso("Crear otra solicitud", false);
        otra.setOnClickListener(v -> { startActivity(new Intent(this, NewRequestActivity.class)); finish(); });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
        bl.topMargin = Ui.dp(this, 10);
        pan.inferior.addView(ver, bl);
        pan.inferior.addView(otra, bl);
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
