package com.lc.customer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.Executors;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

public class GuestRequestActivity extends AppCompatActivity {

    private EditText nombre, telefono, email, direccion, descripcion;
    private ImageView preview;
    private Uri fotoUri;
    private byte[] fotoBytes;
    private boolean openCameraOnStart;
    private boolean en;

    private ActivityResultLauncher<Uri> tomarFotoLauncher;
    private ActivityResultLauncher<String> galeria;
    private ActivityResultLauncher<String[]> permisoLauncher;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        en = Prefs.en(this);
        openCameraOnStart = getIntent().getBooleanExtra("open_camera", false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Prefs.fondo(this));

        // Cabecera
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.VERTICAL);
        cab.setBackgroundColor(Prefs.fondo(this));
        cab.setPadding(dp(20), dp(20), dp(20), dp(12));

        TextView volver = tv(en ? "← Back" : "← Volver", 14, Prefs.ROJO, true);
        volver.setMinHeight(dp(44));
        volver.setGravity(Gravity.CENTER_VERTICAL);
        volver.setOnClickListener(v -> finish());
        cab.addView(volver);

        cab.addView(tv(en ? "Request Service" : "Solicitar Servicio", 22, Prefs.texto(this), true));
        cab.addView(tv(en ? "Fill out the form. We'll contact you to confirm."
                : "Completa el formulario. Te contactaremos para confirmar.",
                13, Prefs.sec(this), false));
        raiz.addView(cab);

        android.view.View linea = new android.view.View(this);
        linea.setBackgroundColor(Prefs.borde(this));
        raiz.addView(linea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Contenido
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Prefs.fondo(this));
        LinearLayout cont = new LinearLayout(this);
        cont.setOrientation(LinearLayout.VERTICAL);
        cont.setBackgroundColor(Prefs.fondo(this));
        cont.setPadding(dp(20), dp(16), dp(20), dp(32));
        sv.addView(cont);
        raiz.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Sección: datos de contacto
        cont.addView(seccion(en ? "Your contact info" : "Tus datos de contacto"));
        nombre    = campo(cont, en ? "Full name *" : "Nombre completo *");
        telefono  = campo(cont, en ? "Phone *" : "Teléfono *");
        telefono.setInputType(InputType.TYPE_CLASS_PHONE);
        email     = campo(cont, en ? "Email" : "Correo electrónico");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        // Sección: dirección
        cont.addView(seccion(en ? "Service address" : "Dirección del servicio"));
        direccion = campo(cont, en ? "Street, number, city, state *" : "Calle, número, ciudad, estado *");
        direccion.setMinLines(2);
        direccion.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);

        // Sección: descripción
        cont.addView(seccion(en ? "What do you need?" : "¿Qué necesitas?"));
        descripcion = campo(cont, en
                ? "Describe the problem or service you need *"
                : "Describe el problema o servicio que necesitas *");
        descripcion.setMinLines(3);
        descripcion.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        // Sección: foto
        cont.addView(seccion(en ? "Problem photo (optional)" : "Foto del problema (opcional)"));
        cont.addView(tv(en ? "A photo helps us diagnose faster and prepare the visit."
                : "Una foto ayuda a diagnosticar mejor y prepara la visita.", 13, Prefs.sec(this), false));

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(Prefs.fondoSuave(this));
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180));
        prevLp.topMargin = dp(10);
        preview.setLayoutParams(prevLp);
        preview.setVisibility(android.view.View.GONE);
        cont.addView(preview);

        // Botones foto
        LinearLayout botonesFoto = new LinearLayout(this);
        botonesFoto.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bfLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bfLp.topMargin = dp(10);
        botonesFoto.setLayoutParams(bfLp);

        Button btnCamara = botonIcono(R.drawable.ic_camera, en ? "Camera" : "Cámara");
        btnCamara.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        botonesFoto.addView(btnCamara);

        android.view.View sep2 = new android.view.View(this);
        sep2.setLayoutParams(new LinearLayout.LayoutParams(dp(8), ViewGroup.LayoutParams.MATCH_PARENT));
        botonesFoto.addView(sep2);

        Button btnGaleria = botonIcono(R.drawable.ic_gallery, en ? "Gallery" : "Galería");
        btnGaleria.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        botonesFoto.addView(btnGaleria);
        cont.addView(botonesFoto);

        // Botón enviar
        cont.addView(espacio(24));
        btnEnviar = new Button(this);
        Button enviar = btnEnviar;
        enviar.setText(en ? "SEND REQUEST" : "ENVIAR SOLICITUD");
        enviar.setAllCaps(true);
        enviar.setTypeface(Typeface.DEFAULT_BOLD);
        enviar.setTextColor(Color.WHITE);
        GradientDrawable gdEnviar = new GradientDrawable();
        gdEnviar.setCornerRadius(dp(12));
        gdEnviar.setColor(Prefs.ROJO);
        enviar.setBackground(gdEnviar);
        enviar.setStateListAnimator(null);
        enviar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        cont.addView(enviar);

        cont.addView(espacio(8));
        cont.addView(tv(en
                ? "By submitting, you agree to L&C contacting you to coordinate the service."
                : "Al enviar, aceptas que L&C te contacte para coordinar el servicio.",
                12, Prefs.sec(this), false));

        setContentView(raiz);

        ViewCompat.setOnApplyWindowInsetsListener(raiz, (view, ins) -> {
            Insets sys = ins.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return ins;
        });

        // Launchers
        tomarFotoLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(), ok -> {
                    if (ok && fotoUri != null) mostrarFoto(fotoUri);
                });

        galeria = registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    if (uri != null) mostrarFoto(uri);
                });

        permisoLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), r -> {
                    if (Boolean.TRUE.equals(r.get(Manifest.permission.CAMERA))) abrirCamara();
                });

        btnCamara.setOnClickListener(v -> verificarPermisoCamara());
        btnGaleria.setOnClickListener(v -> galeria.launch("image/*"));
        enviar.setOnClickListener(v -> enviar());

        if (openCameraOnStart) {
            raiz.post(this::verificarPermisoCamara);
        }

        // Prefill desde intent
        String prefill = getIntent().getStringExtra("prefill_descripcion");
        if (prefill != null && !prefill.isEmpty()) {
            descripcion.setText(prefill);
        }
    }

    // ── Cámara ───────────────────────────────────────────────────────────────

    private void verificarPermisoCamara() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            abrirCamara();
        } else {
            permisoLauncher.launch(new String[]{Manifest.permission.CAMERA});
        }
    }

    private void abrirCamara() {
        try {
            File f = File.createTempFile("lc_", ".jpg",
                    getExternalFilesDir(Environment.DIRECTORY_PICTURES));
            fotoUri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", f);
            tomarFotoLauncher.launch(fotoUri);
        } catch (Exception e) {
            Toast.makeText(this,
                    en ? "Could not open camera" : "No se pudo abrir la cámara",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void mostrarFoto(Uri uri) {
        try {
            fotoUri = uri;
            InputStream is = getContentResolver().openInputStream(uri);
            Bitmap bm = BitmapFactory.decodeStream(is);
            preview.setImageBitmap(bm);
            preview.setVisibility(android.view.View.VISIBLE);
            Bitmap scaled = escalar(bm, 800);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, bo);
            fotoBytes = bo.toByteArray();
        } catch (Exception e) {
            Toast.makeText(this,
                    en ? "Error loading image" : "Error al cargar imagen",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap escalar(Bitmap src, int maxPx) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxPx && h <= maxPx) return src;
        float r = Math.min((float) maxPx / w, (float) maxPx / h);
        return Bitmap.createScaledBitmap(src, Math.round(w * r), Math.round(h * r), true);
    }

    // ── Envío ─────────────────────────────────────────────────────────────────

    private Button btnEnviar;

    private void enviar() {
        String nom  = nombre.getText().toString().trim();
        String tel  = telefono.getText().toString().trim();
        String dir  = direccion.getText().toString().trim();
        String desc = descripcion.getText().toString().trim();

        if (nom.isEmpty() || tel.isEmpty() || dir.isEmpty() || desc.isEmpty()) {
            Toast.makeText(this,
                    en ? "Please fill in all required fields (*)"
                       : "Completa los campos obligatorios (*)",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (Api.conSesion(this)) {
            Intent it = new Intent(this, NewRequestActivity.class);
            it.putExtra("prefill_descripcion", desc);
            it.putExtra("prefill_direccion", dir);
            startActivity(it);
            return;
        }

        // Disable button while the request is in flight
        btnEnviar.setEnabled(false);
        btnEnviar.setText(en ? "SENDING…" : "ENVIANDO…");

        String emailVal = email.getText().toString().trim();
        Handler main = new Handler(Looper.getMainLooper());
        Executors.newSingleThreadExecutor().execute(() -> {
            Api.Respuesta r;
            try {
                org.json.JSONObject body = new org.json.JSONObject();
                body.put("nombre", nom);
                body.put("telefono", tel);
                body.put("email", emailVal.isEmpty() ? null : emailVal);
                body.put("direccion", dir);
                body.put("descripcion", desc);
                r = Api.post(this, "/guest/quote", body);
            } catch (Exception e) {
                r = new Api.Respuesta(-1, null);
            }
            final Api.Respuesta resultado = r;
            main.post(() -> {
                btnEnviar.setEnabled(true);
                btnEnviar.setText(en ? "SEND REQUEST" : "ENVIAR SOLICITUD");
                if (resultado.codigo == 201) {
                    String ref = resultado.cuerpo != null
                            ? resultado.cuerpo.optString("referencia", "") : "";
                    mostrarConfirmacion(nom, ref);
                } else {
                    String err = resultado.error();
                    Toast.makeText(this,
                            en ? "Could not send request: " + err
                               : "No se pudo enviar la solicitud: " + err,
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void mostrarConfirmacion(String nomCliente, String referencia) {
        String primer = nomCliente.trim().split("\\s+")[0];
        String refLine = referencia.isEmpty() ? "" :
                (en ? "\n\nReference number: " + referencia
                    : "\n\nNúmero de referencia: " + referencia);
        String titulo = en ? "Request received!" : "¡Solicitud recibida!";
        String msg = en
            ? "Thank you, " + primer + ". Your request has been registered." + refLine + "\n\n"
              + "An L&C advisor will contact you to confirm the visit.\n\n"
              + "📞 You can also call us directly."
            : "Gracias, " + primer + ". Tu solicitud fue registrada." + refLine + "\n\n"
              + "Un asesor de L&C se pondrá en contacto contigo para confirmar la visita.\n\n"
              + "📞 También puedes llamarnos directamente.";
        String ok = en ? "Got it" : "Entendido";

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(msg)
                .setPositiveButton(ok, (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    // ── Constructores de vistas ──────────────────────────────────────────────

    private TextView seccion(String t) {
        TextView tvS = tv(t, 16, Prefs.texto(this), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        tvS.setLayoutParams(lp);
        return tvS;
    }

    private EditText campo(LinearLayout dest, String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setTextColor(Prefs.texto(this));
        et.setHintTextColor(Prefs.sec(this));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(Prefs.inputBg(this));
        bg.setStroke(dp(1), Prefs.inputBorde(this));
        et.setBackground(bg);

        et.setPadding(dp(16), dp(14), dp(16), dp(14));
        et.setMinHeight(dp(52));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        et.setLayoutParams(lp);
        dest.addView(et);
        return et;
    }

    private Button botonSecundario(String t) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(Prefs.texto(this));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Prefs.fondoCard(this));
        bg.setStroke(dp(1), Prefs.borde(this));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        return b;
    }

    private Button botonIcono(int iconRes, String t) {
        Button b = botonSecundario(t);
        b.setGravity(Gravity.CENTER);
        b.setCompoundDrawablePadding(dp(6));
        Drawable icon = ContextCompat.getDrawable(this, iconRes).mutate();
        icon.setColorFilter(Prefs.texto(this), PorterDuff.Mode.SRC_IN);
        int sz = dp(18);
        icon.setBounds(0, 0, sz, sz);
        b.setCompoundDrawables(icon, null, null, null);
        return b;
    }

    private TextView tv(String t, int sp, int color, boolean bold) {
        TextView tvV = new TextView(this);
        tvV.setText(t);
        tvV.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tvV.setTextColor(color);
        if (bold) tvV.setTypeface(Typeface.DEFAULT_BOLD);
        return tvV;
    }

    private android.view.View espacio(int dp) {
        android.view.View v = new android.view.View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(dp)));
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
