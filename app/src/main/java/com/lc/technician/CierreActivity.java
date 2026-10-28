package com.lc.technician;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.lc.technician.sync.SupabaseClient;
import com.lc.technician.ui.SignatureView;
import com.lc.technician.worker.SyncWorker;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Cierre del trabajo (Orden 21). Para completar o pedir repuesto el técnico necesita: diagnóstico de al menos 50 caracteres,
 * al menos 1 foto y la firma del cliente (nombre completo + trazo) o el registro de que no quiso firmar. El servidor repite
 * la validación (lc_patch_order), así que este candado no se puede saltar desde otra pantalla. La firma y el nombre se envían
 * al servidor y NO se guardan en el teléfono. Paleta oficial: rojo #E91605, negro #171717, gris #62666A.
 */
public class CierreActivity extends AppCompatActivity {

    static final String EXTRA_JOB = "jobId", EXTRA_DIAG = "diagLen", EXTRA_FOTOS = "fotos", RESULT_ACCION = "accion";
    static final int MIN_DIAGNOSTICO = 50;
    private static final int ROJO = 0xFFE91605, NEGRO = 0xFF171717, GRIS = 0xFF62666A, CLARO = 0xFFF1F2F3, BORDE = 0xFFE5E5E5;

    private long jobId;
    private int diagLen, fotos;
    private boolean cierreRegistrado, publicando;
    private String resumenCierre = "";

    private LinearLayout checklist, panelFirma, panelListo;
    private TextView aviso, estadoCierre, bloqueo;
    private EditText etNombre;
    private SignatureView firma;
    private Button btnGuardar, btnNegativa, btnCompletar, btnRepuesto;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        jobId = getIntent().getLongExtra(EXTRA_JOB, -1);
        diagLen = getIntent().getIntExtra(EXTRA_DIAG, 0);
        fotos = getIntent().getIntExtra(EXTRA_FOTOS, 0);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(0xFFFFFFFF);
        LinearLayout raiz = col();
        int barra = getResources().getIdentifier("status_bar_height", "dimen", "android");
        int nav = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        raiz.setPadding(dp(20), dp(20) + (barra > 0 ? getResources().getDimensionPixelSize(barra) : dp(24)), dp(20),
                dp(28) + (nav > 0 ? getResources().getDimensionPixelSize(nav) : dp(48)));   // el botón final no queda bajo la barra
        sv.addView(raiz);
        setContentView(sv);

        TextView volver = texto("← Volver", 15, ROJO, true);
        volver.setOnClickListener(v -> finish());
        raiz.addView(volver);
        raiz.addView(texto("Cerrar trabajo", 28, NEGRO, true), margen(12, 0));
        raiz.addView(texto("Orden Nº " + jobId + " · para cerrar hacen falta diagnóstico, foto y firma", 14, GRIS, false), margen(2, 14));

        checklist = col();
        checklist.setBackground(caja(0xFFFFFFFF, BORDE, 14));
        checklist.setPadding(dp(16), dp(12), dp(16), dp(12));
        raiz.addView(checklist);

        bloqueo = texto("", 15, ROJO, true);
        raiz.addView(bloqueo, margen(10, 0));

        panelFirma = col();
        raiz.addView(panelFirma, margen(16, 0));
        panelFirma.addView(texto("Firma del cliente", 18, NEGRO, true));
        etNombre = new EditText(this);
        etNombre.setHint("Nombre completo de quien firma");
        etNombre.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        etNombre.setSingleLine(true);
        etNombre.setTextColor(NEGRO);
        etNombre.setHintTextColor(GRIS);
        etNombre.setBackground(caja(CLARO, CLARO, 12));
        etNombre.setPadding(dp(14), dp(12), dp(14), dp(12));
        panelFirma.addView(etNombre, margen(8, 0));
        firma = new SignatureView(this);
        firma.setBackground(caja(0xFFFFFFFF, BORDE, 12));
        LinearLayout.LayoutParams lpFirma = new LinearLayout.LayoutParams(-1, dp(190));
        lpFirma.topMargin = dp(10);
        panelFirma.addView(firma, lpFirma);
        TextView limpiar = texto("Limpiar firma", 14, ROJO, true);
        limpiar.setPadding(0, dp(10), 0, dp(10));
        limpiar.setOnClickListener(v -> firma.limpiar());
        panelFirma.addView(limpiar);
        btnGuardar = boton("GUARDAR FIRMA", true);
        btnGuardar.setOnClickListener(v -> guardarFirma());
        panelFirma.addView(btnGuardar, margen(4, 0));
        btnNegativa = boton("El cliente no quiso firmar / Customer refused to sign", false);
        btnNegativa.setOnClickListener(v -> confirmarNegativa());
        panelFirma.addView(btnNegativa, margen(10, 0));

        panelListo = col();
        raiz.addView(panelListo, margen(16, 0));
        estadoCierre = texto("", 16, NEGRO, true);
        panelListo.addView(estadoCierre);
        btnCompletar = boton("COMPLETAR TRABAJO", true);
        btnCompletar.setOnClickListener(v -> terminar("COMPLETE"));
        panelListo.addView(btnCompletar, margen(14, 0));
        btnRepuesto = boton("ORDENAR REPUESTO", false);
        btnRepuesto.setOnClickListener(v -> terminar("PART_HOLD"));
        panelListo.addView(btnRepuesto, margen(10, 0));

        aviso = texto("", 13, GRIS, false);
        raiz.addView(aviso, margen(10, 0));
        pintar();
        consultarCierre();
    }

    // ── estado ───────────────────────────────────────────────────────────────

    private boolean evidenciaOk() { return diagLen >= MIN_DIAGNOSTICO && fotos >= 1; }

    private void pintar() {
        checklist.removeAllViews();
        fila("Diagnóstico (" + diagLen + "/" + MIN_DIAGNOSTICO + " caracteres)", diagLen >= MIN_DIAGNOSTICO);
        fila("Foto de evidencia (" + fotos + ")", fotos >= 1);
        fila(cierreRegistrado ? resumenCierre : "Firma del cliente o negativa registrada", cierreRegistrado);
        boolean todo = evidenciaOk() && cierreRegistrado;
        bloqueo.setText(todo ? "" : "Falta evidencia, diagnóstico o firma. No puedes cerrar.");
        panelFirma.setVisibility(evidenciaOk() && !cierreRegistrado ? View.VISIBLE : View.GONE);
        panelListo.setVisibility(View.VISIBLE);
        estadoCierre.setText(todo ? "✓ Todo listo para cerrar" : "");
        btnCompletar.setEnabled(todo);
        btnRepuesto.setEnabled(todo);
        btnCompletar.setAlpha(todo ? 1f : 0.35f);
        btnRepuesto.setAlpha(todo ? 1f : 0.35f);
        if (!evidenciaOk()) aviso.setText("Vuelve a la orden, escribe el diagnóstico (mín. 50 caracteres) y agrega al menos una foto.");
        else if (!cierreRegistrado) aviso.setText("Pídele al cliente su nombre completo y que firme con el dedo, o registra que no quiso firmar.");
        else aviso.setText("");
    }

    private void fila(String texto, boolean ok) {
        LinearLayout f = new LinearLayout(this);
        f.setGravity(Gravity.CENTER_VERTICAL);
        f.setPadding(0, dp(6), 0, dp(6));
        TextView marca = texto(ok ? "✓" : "✕", 18, ok ? NEGRO : ROJO, true);
        marca.setMinWidth(dp(28));
        f.addView(marca);
        f.addView(texto(texto, 15, ok ? NEGRO : GRIS, false));
        checklist.addView(f);
    }

    private void consultarCierre() {
        if (jobId < 0) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                SupabaseClient sb = SupabaseClient.get(this);
                if (sb.ensureAuthSync() != null) return;
                retrofit2.Response<okhttp3.ResponseBody> r = sb.api().closureGet(sb.authHeader(), jobId).execute();
                if (!r.isSuccessful() || r.body() == null) return;
                JSONObject o = new JSONObject(r.body().string());
                if (o.optBoolean("existe")) runOnUiThread(() -> registrado(o));
            } catch (Exception ignored) { /* sin red: se vuelve a intentar al guardar */ }
        });
    }

    private void registrado(JSONObject o) {
        cierreRegistrado = true;
        resumenCierre = "FIRMA".equals(o.optString("kind"))
                ? "Firmó " + o.optString("firmante")
                : "El cliente no quiso firmar · " + o.optString("negativaAt", "").replace('T', ' ').replaceAll("\\..*$", "");
        pintar();
        String correo = o.optString("correo", "");
        if (!correo.isEmpty()) aviso.setText("Correo al cliente: " + textoCorreo(correo));   // después de pintar(), que limpia el aviso
    }

    private static String textoCorreo(String c) {
        switch (c) {
            case "ENVIADO": return "enviado";
            case "PENDIENTE_SIN_PROVEEDOR": return "pendiente (servicio de correo sin configurar)";
            case "SIN_CORREO_CLIENTE": return "el cliente no tiene correo registrado";
            case "ERROR": return "error al enviar";
            default: return "en proceso";
        }
    }

    // ── guardar firma / negativa ───────────────────────────────────────────────

    private void guardarFirma() {
        String nombre = etNombre.getText().toString().trim();
        if (nombre.length() < 5 || !nombre.contains(" ")) {
            Toast.makeText(this, "Escribe el nombre completo de quien firma.", Toast.LENGTH_LONG).show();
            return;
        }
        if (firma.estaVacio()) {
            Toast.makeText(this, "Falta la firma del cliente.", Toast.LENGTH_LONG).show();
            return;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "FIRMA");
        body.put("nombre", nombre);
        body.put("firmaBase64", png(firma.getBitmap()));
        enviarCierre(body);
    }

    private void confirmarNegativa() {
        new AlertDialog.Builder(this)
                .setTitle("El cliente no quiso firmar")
                .setMessage("Se registrará la negativa con la fecha y hora de ahora y se enviará el reporte al cliente.\n\n"
                        + "The customer's refusal will be recorded with today's date and time.")
                .setNegativeButton("Cancelar / Cancel", null)
                .setPositiveButton("Registrar / Record", (d, w) -> {
                    Map<String, Object> body = new HashMap<>();
                    body.put("kind", "NEGATIVA");
                    body.put("motivo", "El cliente no quiso firmar");
                    enviarCierre(body);
                }).show();
    }

    /** Sube primero los cambios pendientes (el servidor valida el diagnóstico que ya recibió) y luego registra el cierre. */
    private void enviarCierre(Map<String, Object> body) {
        btnGuardar.setEnabled(false);
        btnNegativa.setEnabled(false);
        aviso.setText("Sincronizando con el servidor…");
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SyncWorker.class).build();
        WorkManager wm = WorkManager.getInstance(this);
        wm.enqueue(req);
        final androidx.lifecycle.LiveData<WorkInfo> estadoTrabajo = wm.getWorkInfoByIdLiveData(req.getId());
        final Runnable seguir = () -> {
            if (publicando) return;
            publicando = true;   // un solo envío aunque el observador se entregue otra vez o venza el tiempo
            estadoTrabajo.removeObservers(this);
            Executors.newSingleThreadExecutor().execute(() -> publicar(body));
        };
        estadoTrabajo.observe(this, info -> { if (info != null && info.getState().isFinished()) seguir.run(); });
        // Si la sincronización queda en reintento (p. ej. mientras se renueva la sesión) no se espera para siempre
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(seguir, 8000);
    }

    private void publicar(Map<String, Object> body) {
        String error = null;
        JSONObject ok = null;
        try {
            SupabaseClient sb = SupabaseClient.get(this);
            String fallo = sb.ensureAuthSync();
            if (fallo != null) { Thread.sleep(2500); fallo = sb.ensureAuthSync(); }   // la sesión puede estar renovándose
            if (fallo != null) error = "Sin sesión. Inicia sesión de nuevo.";
            else {
                retrofit2.Response<okhttp3.ResponseBody> r = sb.api().closurePost(sb.authHeader(), jobId, body).execute();
                String txt = r.isSuccessful() && r.body() != null ? r.body().string()
                        : r.errorBody() != null ? r.errorBody().string() : "";
                JSONObject o = txt.isEmpty() ? new JSONObject() : new JSONObject(txt);
                if (r.isSuccessful()) ok = o;
                else error = o.optString("mensaje", o.optString("error", "No se pudo registrar (" + r.code() + ")"));
            }
        } catch (Exception e) {
            error = "Sin conexión con L&C. Tus datos siguen aquí; intenta de nuevo.";
        }
        final String msg = error;
        final boolean fueFirma = "FIRMA".equals(body.get("kind"));
        runOnUiThread(() -> {
            publicando = false;
            btnGuardar.setEnabled(true);
            btnNegativa.setEnabled(true);
            if (msg != null) { aviso.setText(msg); return; }
            cierreRegistrado = true;
            resumenCierre = fueFirma ? "Firmó " + body.get("nombre") : "El cliente no quiso firmar · registrado ahora";
            aviso.setText("");
            pintar();
            consultarCierre();   // trae el estado del correo
        });
    }

    private void terminar(String accion) {
        setResult(RESULT_OK, new Intent().putExtra(RESULT_ACCION, accion));
        finish();
    }

    // ── utilidades de interfaz ─────────────────────────────────────────────────

    private static String png(Bitmap bm) {
        Bitmap s = bm.getWidth() > 700 ? Bitmap.createScaledBitmap(bm, 700, Math.max(1, bm.getHeight() * 700 / bm.getWidth()), true) : bm;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        s.compress(Bitmap.CompressFormat.PNG, 100, o);
        return Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private LinearLayout col() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }

    private TextView texto(String t, int sp, int color, boolean negrita) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (negrita) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private LinearLayout.LayoutParams margen(int arriba, int abajo) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(arriba);
        lp.bottomMargin = dp(abajo);
        return lp;
    }

    private GradientDrawable caja(int relleno, int borde, int radio) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(relleno);
        g.setStroke(dp(1), borde);
        g.setCornerRadius(dp(radio));
        return g;
    }

    private Button boton(String t, boolean principal) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(principal);
        b.setTextSize(principal ? 16 : 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(principal ? 0xFFFFFFFF : NEGRO);
        b.setBackground(principal ? caja(ROJO, ROJO, 28) : caja(CLARO, BORDE, 28));
        b.setMinHeight(dp(52));
        return b;
    }
}
