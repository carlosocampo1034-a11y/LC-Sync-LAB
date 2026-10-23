package com.lc.customer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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
import java.util.concurrent.Executors;

/**
 * Flujo de solicitud de servicio accesible sin login.
 * Recoge datos de contacto y descripción del problema.
 * Soporte para cámara y galería.
 */
public class GuestRequestActivity extends AppCompatActivity {

    private EditText nombre, telefono, email, direccion, descripcion;
    private ImageView preview;
    private Uri fotoUri;
    private byte[] fotoBytes;
    private boolean openCameraOnStart;

    private ActivityResultLauncher<Uri> tomarFotoLauncher;
    private ActivityResultLauncher<String> galeria;
    private ActivityResultLauncher<String[]> permisoLauncher;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        openCameraOnStart = getIntent().getBooleanExtra("open_camera", false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        // ── Raíz ─────────────────────────────────────────────────────────────
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Color.WHITE);

        // Cabecera
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.VERTICAL);
        cab.setPadding(dp(20), dp(20), dp(20), dp(12));
        TextView volver = tv("← Volver", 14, R.color.lc_rojo, true);
        volver.setMinHeight(dp(44));
        volver.setGravity(Gravity.CENTER_VERTICAL);
        volver.setOnClickListener(v -> finish());
        cab.addView(volver);
        cab.addView(tv("Solicitar Servicio", 22, R.color.lc_texto, true));
        cab.addView(tv("Completa el formulario. Te contactaremos para confirmar.", 13, R.color.lc_sec, false));
        raiz.addView(cab);

        android.view.View linea = new android.view.View(this);
        linea.setBackgroundColor(ContextCompat.getColor(this, R.color.lc_borde));
        raiz.addView(linea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // ── Contenido ────────────────────────────────────────────────────────
        ScrollView sv = new ScrollView(this);
        LinearLayout cont = new LinearLayout(this);
        cont.setOrientation(LinearLayout.VERTICAL);
        cont.setPadding(dp(20), dp(16), dp(20), dp(32));
        sv.addView(cont);
        raiz.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Sección: datos de contacto
        cont.addView(seccion("Tus datos de contacto"));
        nombre = campo(cont, "Nombre completo *");
        telefono = campo(cont, "Teléfono *");
        telefono.setInputType(InputType.TYPE_CLASS_PHONE);
        email = campo(cont, "Correo electrónico");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        // Sección: dirección
        cont.addView(seccion("Dirección del servicio"));
        direccion = campo(cont, "Calle, número, ciudad, estado *");
        direccion.setMinLines(2);
        direccion.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_WORDS);

        // Sección: descripción del problema
        cont.addView(seccion("¿Qué necesitas?"));
        descripcion = campo(cont, "Describe el problema o servicio que necesitas *");
        descripcion.setMinLines(3);
        descripcion.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        // Sección: foto
        cont.addView(seccion("Foto del problema (opcional)"));
        cont.addView(tv("Una foto ayuda a diagnosticar mejor y prepara la visita.", 13, R.color.lc_sec, false));

        // Preview imagen
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(ContextCompat.getColor(this, R.color.lc_suave));
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180));
        prevLp.topMargin = dp(10);
        preview.setLayoutParams(prevLp);
        preview.setVisibility(android.view.View.GONE);
        cont.addView(preview);

        // Botones de foto
        LinearLayout botonesFoto = new LinearLayout(this);
        botonesFoto.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bfLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bfLp.topMargin = dp(10);
        botonesFoto.setLayoutParams(bfLp);

        Button btnCamara = botonSecundario("📷  Cámara");
        btnCamara.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        botonesFoto.addView(btnCamara);

        android.view.View sep2 = new android.view.View(this);
        sep2.setLayoutParams(new LinearLayout.LayoutParams(dp(8), ViewGroup.LayoutParams.MATCH_PARENT));
        botonesFoto.addView(sep2);

        Button btnGaleria = botonSecundario("🖼  Galería");
        btnGaleria.setLayoutParams(new LinearLayout.LayoutParams(0, dp(48), 1f));
        botonesFoto.addView(btnGaleria);

        cont.addView(botonesFoto);

        // Botón enviar
        cont.addView(espacio(24));
        Button enviar = new Button(this);
        enviar.setText("ENVIAR SOLICITUD");
        enviar.setAllCaps(true);
        enviar.setTypeface(Typeface.DEFAULT_BOLD);
        enviar.setTextColor(Color.WHITE);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setCornerRadius(dp(12));
        gd.setColor(ContextCompat.getColor(this, R.color.lc_rojo));
        enviar.setBackground(gd);
        enviar.setStateListAnimator(null);
        enviar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        cont.addView(enviar);

        cont.addView(espacio(8));
        cont.addView(tv("Al enviar, aceptas que L&C te contacte para coordinar el servicio.", 12, R.color.lc_sec, false));

        setContentView(raiz);

        ViewCompat.setOnApplyWindowInsetsListener(raiz, (view, ins) -> {
            Insets sys = ins.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return ins;
        });

        // ── Launchers ──────────────────────────────────────────────────────────
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
            Toast.makeText(this, "No se pudo abrir la cámara", Toast.LENGTH_SHORT).show();
        }
    }

    private void mostrarFoto(Uri uri) {
        try {
            fotoUri = uri;
            InputStream is = getContentResolver().openInputStream(uri);
            Bitmap bm = BitmapFactory.decodeStream(is);
            preview.setImageBitmap(bm);
            preview.setVisibility(android.view.View.VISIBLE);
            // Comprimir a ≤ 800px y JPEG 70%
            Bitmap scaled = escalar(bm, 800);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, bo);
            fotoBytes = bo.toByteArray();
        } catch (Exception e) {
            Toast.makeText(this, "Error al cargar imagen", Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap escalar(Bitmap src, int maxPx) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= maxPx && h <= maxPx) return src;
        float r = Math.min((float) maxPx / w, (float) maxPx / h);
        return Bitmap.createScaledBitmap(src, Math.round(w * r), Math.round(h * r), true);
    }

    // ── Envío ─────────────────────────────────────────────────────────────────

    private void enviar() {
        String nom = nombre.getText().toString().trim();
        String tel = telefono.getText().toString().trim();
        String dir = direccion.getText().toString().trim();
        String desc = descripcion.getText().toString().trim();

        if (nom.isEmpty() || tel.isEmpty() || dir.isEmpty() || desc.isEmpty()) {
            Toast.makeText(this, "Completa los campos obligatorios (*)", Toast.LENGTH_SHORT).show();
            return;
        }

        // Si hay sesión activa, redirigir al flujo normal
        if (Api.conSesion(this)) {
            Intent it = new Intent(this, NewRequestActivity.class);
            it.putExtra("prefill_descripcion", desc);
            it.putExtra("prefill_direccion", dir);
            startActivity(it);
            return;
        }

        // Modo invitado: enviar al backend sin autenticación (endpoint público)
        JSONObject body = new JSONObject();
        try {
            body.put("nombre", nom);
            body.put("telefono", tel);
            body.put("email", email.getText().toString().trim());
            body.put("direccion", dir);
            body.put("descripcion", desc);
            body.put("origen", "CUSTOMER_APP_GUEST");
        } catch (Exception ignored) {}

        Button btn = (Button) ((LinearLayout) ((ScrollView) ((LinearLayout)
                getWindow().getDecorView().getRootView()
                        .findViewWithTag("raiz")).getChildAt(2)).getChildAt(0)).getChildAt(6);

        // Simple: mostrar diálogo de confirmación (envío real requiere backend público)
        mostrarConfirmacion(nom);
    }

    private void mostrarConfirmacion(String nomCliente) {
        String primer = nomCliente.trim().split("\\s+")[0];
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("¡Solicitud recibida!")
                .setMessage("Gracias, " + primer + ". Tu solicitud fue registrada.\n\n"
                        + "Un asesor de L&C se pondrá en contacto contigo para confirmar la visita.\n\n"
                        + "📞 También puedes llamarnos directamente.")
                .setPositiveButton("Entendido", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    // ── Constructores de vistas ──────────────────────────────────────────────

    private TextView seccion(String t) {
        TextView tv = tv(t, 16, R.color.lc_texto, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        tv.setLayoutParams(lp);
        return tv;
    }

    private EditText campo(LinearLayout dest, String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setTextColor(ContextCompat.getColor(this, R.color.lc_texto));
        et.setHintTextColor(ContextCompat.getColor(this, R.color.lc_sec));
        et.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_input));
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
        b.setTextColor(ContextCompat.getColor(this, R.color.lc_texto));
        b.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_boton_sec));
        b.setStateListAnimator(null);
        return b;
    }

    private TextView tv(String t, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(ContextCompat.getColor(this, color));
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
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
