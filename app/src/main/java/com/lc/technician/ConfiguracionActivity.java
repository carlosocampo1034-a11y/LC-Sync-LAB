package com.lc.technician;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.lc.technician.data.MembershipCardDao;
import com.lc.technician.model.CoverageTypeEntity;
import com.lc.technician.model.MembershipCardEntity;
import java.util.List;
import java.util.Locale;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import android.os.Handler;
import android.os.Looper;
import android.widget.Switch;
import androidx.appcompat.widget.SwitchCompat;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.lc.technician.data.ConfigManager;
import com.lc.technician.data.JobRepository;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.SyncStateEntity;
import com.lc.technician.sync.ApiClient;
import com.lc.technician.worker.SyncWorker;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ConfiguracionActivity extends AppCompatActivity {

    private static final String PREFS = "lc_prefs";
    private static final SimpleDateFormat FMT =
            new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.forLanguageTag("es"));
    private static final SimpleDateFormat BACKUP_FMT =
            new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US);

    private Button btnHacerBackup;
    private TextView tvEstadoBackup;
    private TextView tvUltimoBackup;

    private Button btnExportarBackup;
    private Button btnRestaurarBackup;
    private Button btnCompartirBackup;
    private TextView tvUltimoBackupDB;
    private TextView tvEstadoBackupDB;

    private ActivityResultLauncher<String[]> pickFileLauncher;

    private TextView tvRamUsada;
    private TextView tvRamMax;
    private TextView tvCacheJobs;

    private EditText etMarkupGood;
    private EditText etMarkupBetter;
    private EditText etMarkupBest;
    private EditText etTaxRate;
    private LinearLayout layoutCoberturas;
    private LinearLayout layoutMemberships;

    // Sync section views
    private TextView tvUltimaSync;
    private TextView tvPendientesSync;
    private TextView tvLastSync;
    private EditText etSyncUrl;
    private SwitchCompat switchSyncAuto;
    private static final String PREFS_SYNC = "lc_sync_prefs";
    private static final String KEY_SYNC_AUTO = "sync_auto_enabled";

    // Administrar datos locales
    private TextView tvDatosLocalCuenta;
    private TextView tvDatosLocalStats;
    private TextView tvEstadoVaciar;

    // Handler for "Última sync: hace X min" refresh
    private final Handler syncTimeHandler = new Handler(Looper.getMainLooper());
    private final Runnable updateSyncTime = new Runnable() {
        @Override
        public void run() {
            SharedPreferences p = getSharedPreferences("lc_prefs", MODE_PRIVATE);
            long lastSyncTs = p.getLong("last_sync_time", 0);
            if (tvLastSync != null) {
                if (lastSyncTs > 0) {
                    long minAgo = (System.currentTimeMillis() - lastSyncTs) / 60000;
                    tvLastSync.setText("Última sync: hace " + minAgo + " min");
                } else {
                    tvLastSync.setText("Última sync: nunca");
                }
            }
            syncTimeHandler.postDelayed(this, 30000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_configuracion);
        // Edge-to-edge (Android 15+): cabecera bajo la barra de estado, Dispatch / Cola visible sobre la navegación
        InsetsHelper.padTop(findViewById(R.id.header));
        InsetsHelper.padBottom(findViewById(R.id.scrollConfig));

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        btnHacerBackup = findViewById(R.id.btnHacerBackup);
        tvEstadoBackup = findViewById(R.id.tvEstadoBackup);
        tvUltimoBackup = findViewById(R.id.tvUltimoBackup);

        btnExportarBackup  = findViewById(R.id.btnExportarBackup);
        btnRestaurarBackup = findViewById(R.id.btnRestaurarBackup);
        btnCompartirBackup = findViewById(R.id.btnCompartirBackup);
        tvUltimoBackupDB   = findViewById(R.id.tvUltimoBackupDB);
        tvEstadoBackupDB   = findViewById(R.id.tvEstadoBackupDB);

        // File picker for restore
        pickFileLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) restaurarDesdeUri(uri); });

        TextView tvVersion = findViewById(R.id.tvVersion);
        String versionName = "?";
        try {
            versionName = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {}
        tvVersion.setText("Versión " + versionName);

        tvRamUsada  = findViewById(R.id.tvRamUsada);
        tvRamMax    = findViewById(R.id.tvRamMax);
        tvCacheJobs = findViewById(R.id.tvCacheJobs);

        // Proposal defaults section
        etMarkupGood   = findViewById(R.id.etMarkupGood);
        etMarkupBetter = findViewById(R.id.etMarkupBetter);
        etMarkupBest   = findViewById(R.id.etMarkupBest);
        etTaxRate      = findViewById(R.id.etTaxRate);

        ConfigManager config = new ConfigManager(this);
        etMarkupGood.setText(String.valueOf(config.getMarkupGood()));
        etMarkupBetter.setText(String.valueOf(config.getMarkupBetter()));
        etMarkupBest.setText(String.valueOf(config.getMarkupBest()));
        etTaxRate.setText(String.valueOf(config.getTaxRate()));

        Button btnGuardarPropuesta = findViewById(R.id.btnGuardarPropuestaConfig);
        btnGuardarPropuesta.setOnClickListener(v -> guardarPropuestaConfig(config));

        // Accordion: Márgenes y Markup
        android.widget.LinearLayout headerMarkup = findViewById(R.id.headerMarkup);
        android.widget.LinearLayout layoutMarkupContent = findViewById(R.id.layoutMarkupContent);
        android.widget.TextView tvMarkupArrow = findViewById(R.id.tvMarkupArrow);
        if (headerMarkup != null && layoutMarkupContent != null) {
            headerMarkup.setOnClickListener(v -> {
                boolean expanded = layoutMarkupContent.getVisibility() == android.view.View.VISIBLE;
                layoutMarkupContent.setVisibility(expanded ? android.view.View.GONE : android.view.View.VISIBLE);
                if (tvMarkupArrow != null) tvMarkupArrow.setText(expanded ? "▼" : "▲");
            });
        }

        layoutCoberturas = findViewById(R.id.layoutCoberturas);
        cargarCoberturas();

        layoutMemberships = findViewById(R.id.layoutMemberships);
        cargarMemberships();
        Button btnGenerarTarjeta = findViewById(R.id.btnGenerarTarjeta);
        if (btnGenerarTarjeta != null) {
            btnGenerarTarjeta.setOnClickListener(v -> mostrarDialogoGenerarTarjeta());
        }

        cargarUltimoBackup();
        cargarUltimoBackupDB();
        actualizarRendimiento();

        btnHacerBackup.setOnClickListener(v -> hacerBackup());
        btnExportarBackup.setOnClickListener(v -> exportarBackupDB());
        btnRestaurarBackup.setOnClickListener(v -> pickFileLauncher.launch(
                new String[]{"application/octet-stream", "*/*"}));
        btnCompartirBackup.setOnClickListener(v -> compartirBackupDB());

        // 27A — Partes e Inventario
        View btnPartes = findViewById(R.id.btnPartesInventario);
        if (btnPartes != null) {
            btnPartes.setOnClickListener(v ->
                startActivity(new Intent(this, PartsActivity.class)));
        }

        // 27D — Técnicos
        View btnTecnicos = findViewById(R.id.btnTecnicos);
        if (btnTecnicos != null) {
            btnTecnicos.setOnClickListener(v ->
                startActivity(new Intent(this, TechnicianRosterActivity.class)));
        }

        // 27D — Dispatch / Cola
        View btnDispatch = findViewById(R.id.btnDispatchConfig);
        if (btnDispatch != null) {
            btnDispatch.setOnClickListener(v ->
                startActivity(new Intent(this, DispatchActivity.class)));
        }

        findViewById(R.id.btnVerAuditoria).setOnClickListener(v ->
                startActivity(new Intent(this, AuditLogActivity.class)));

        findViewById(R.id.btnLimpiarAudit).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("Limpiar auditoría")
                        .setMessage("¿Eliminar entradas de auditoría con más de 90 días?")
                        .setPositiveButton("Limpiar", (d, w) -> {
                            JobRepository.get().limpiarAuditAntiguo(90);
                            Toast.makeText(this, "Auditoría antigua eliminada",
                                    Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancelar", null).show());

        // ── SINCRONIZACIÓN ────────────────────────────────────────────────────
        tvUltimaSync    = findViewById(R.id.tvUltimaSync);
        tvPendientesSync = findViewById(R.id.tvPendientesSync);
        etSyncUrl       = findViewById(R.id.etSyncUrl);
        switchSyncAuto  = findViewById(R.id.switchSyncAuto);

        if (etSyncUrl != null) {
            etSyncUrl.setText(ApiClient.getBaseUrl(this));
        }

        boolean autoEnabled = getSharedPreferences(PREFS_SYNC, MODE_PRIVATE)
                .getBoolean(KEY_SYNC_AUTO, true);  // default ON
        if (switchSyncAuto != null) {
            switchSyncAuto.setChecked(autoEnabled);
            switchSyncAuto.setOnCheckedChangeListener((btn, checked) -> {
                getSharedPreferences(PREFS_SYNC, MODE_PRIVATE)
                    .edit().putBoolean(KEY_SYNC_AUTO, checked).apply();
                if (checked) {
                    schedulePeriodicSync();
                    Toast.makeText(this, "Sincronización automática activada", Toast.LENGTH_SHORT).show();
                } else {
                    WorkManager.getInstance(this).cancelUniqueWork(SyncWorker.TAG);
                    WorkManager.getInstance(this).cancelUniqueWork("auto_sync_silent");
                    Toast.makeText(this, "Sincronización automática desactivada", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // tvLastSync: "Última sync: hace X min"
        tvLastSync = findViewById(R.id.tvLastSync);

        Button btnTest = findViewById(R.id.btnTestConexion);
        if (btnTest != null) btnTest.setOnClickListener(v -> probarConexion());

        Button btnSyncNow = findViewById(R.id.btnSyncAhora);
        if (btnSyncNow != null) btnSyncNow.setOnClickListener(v -> sincronizarAhora());

        Button btnLab = findViewById(R.id.btnLabConectar);
        if (btnLab != null) btnLab.setOnClickListener(v -> conectarLaboratorio());
        actualizarEstadoLab();

        actualizarInfoSync();

        // ── ADMINISTRAR DATOS LOCALES ─────────────────────────────────────────
        tvDatosLocalCuenta = findViewById(R.id.tvDatosLocalCuenta);
        tvDatosLocalStats  = findViewById(R.id.tvDatosLocalStats);
        tvEstadoVaciar     = findViewById(R.id.tvEstadoVaciar);
        Button btnVaciar = findViewById(R.id.btnVaciarDatosLocales);
        if (btnVaciar != null) btnVaciar.setOnClickListener(v -> iniciarVaciadoLocal());

        // ── DASHBOARD WEB ─────────────────────────────────────────────────────
        Button btnAbrir = findViewById(R.id.btnAbrirDashboard);
        Button btnCompartir = findViewById(R.id.btnCompartirDashboard);
        TextView tvUrl = findViewById(R.id.tvDashboardUrl);
        ImageView ivQR = findViewById(R.id.ivDashboardQR);

        String dashUrl = getDashboardUrl();
        if (tvUrl != null) {
            tvUrl.setText("URL: " + dashUrl);
        }
        if (btnAbrir != null) {
            btnAbrir.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getDashboardUrl())));
                } catch (Exception e) {
                    Toast.makeText(this, "No se pudo abrir el navegador", Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (btnCompartir != null) {
            btnCompartir.setOnClickListener(v -> {
                String url = getDashboardUrl();
                String tailscale = getTailscaleUrl();
                String msg = "L&C Control:\n\nLocal: " + url
                        + "\nRemoto (Tailscale): " + tailscale + "/control/"
                        + "\n\nEsta URL abre desde la misma red o mediante Tailscale.\n"
                        + "Usar las credenciales asignadas por el administrador.";
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, msg);
                startActivity(Intent.createChooser(share, "Compartir URL del Dashboard"));
            });
        }
        if (ivQR != null) {
            int sizePx = (int)(160 * getResources().getDisplayMetrics().density);
            Bitmap qr = generarQR(dashUrl, sizePx);
            if (qr != null) ivQR.setImageBitmap(qr);
        }
    }

    private void probarConexion() {
        String url = etSyncUrl != null ? etSyncUrl.getText().toString().trim() : ApiClient.DEFAULT_BASE_URL;
        if (!url.isEmpty()) {
            ApiClient.setBaseUrl(this, url);
        }
        Toast.makeText(this, "Probando conexión…", Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                retrofit2.Response<com.lc.technician.sync.SyncModels.HealthResponse> resp =
                    ApiClient.get(this).health().execute();
                String msg = resp.isSuccessful() && resp.body() != null
                    ? "✅ Conexión OK — v" + resp.body().version
                    : "❌ HTTP " + resp.code();
                runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                String msg = "❌ Sin conexión: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void sincronizarAhora() {
        String url = etSyncUrl != null ? etSyncUrl.getText().toString().trim() : "";
        if (!url.isEmpty()) ApiClient.setBaseUrl(this, url);
        Toast.makeText(this, "Sincronizando…", Toast.LENGTH_SHORT).show();
        androidx.work.OneTimeWorkRequest req = new androidx.work.OneTimeWorkRequest.Builder(SyncWorker.class).build();
        WorkManager.getInstance(this).enqueue(req);
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(req.getId())
            .observe(this, info -> {
                if (info != null && info.getState().isFinished()) {
                    runOnUiThread(() -> {
                        actualizarInfoSync();
                        if (info.getState() == androidx.work.WorkInfo.State.SUCCEEDED) {
                            int pushed = info.getOutputData().getInt(SyncWorker.KEY_PUSHED_COUNT, 0);
                            int pulled = info.getOutputData().getInt(SyncWorker.KEY_PULLED_JOBS, 0);
                            String msg = "✅ Subidos: " + pushed + "  |  Bajados: " + pulled + " jobs";
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "⚠️ Sincronización con errores", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            });
    }

    private void schedulePeriodicSync() {
        Constraints networkConstraint = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .setInputData(new Data.Builder().putBoolean("silent", true).build())
                .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            SyncWorker.TAG, ExistingPeriodicWorkPolicy.KEEP, req);
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "auto_sync_silent", ExistingPeriodicWorkPolicy.KEEP, req);
    }

    /** Estado de la sesión con el backend de laboratorio (sync v2). */
    private void actualizarEstadoLab() {
        TextView tv = findViewById(R.id.tvLabEstado);
        Button btn = findViewById(R.id.btnLabConectar);
        if (tv == null || btn == null) return;
        boolean activa = com.lc.technician.sync.LabSession.activa(this);
        tv.setText(activa ? "Laboratorio: conectado como " + com.lc.technician.sync.LabSession.usuario(this)
                + " (sync v2)" : "Laboratorio: sin conectar (se usa la sincronización histórica)");
        btn.setText(activa ? "DESCONECTAR DEL LABORATORIO" : "CONECTAR AL LABORATORIO");
        findViewById(R.id.etLabUsuario).setVisibility(activa ? View.GONE : View.VISIBLE);
        findViewById(R.id.etLabClave).setVisibility(activa ? View.GONE : View.VISIBLE);
        if (BuildConfig.DEBUG) {
            if (tvServidorLab == null && tv.getParent() instanceof android.view.ViewGroup) {
                android.view.ViewGroup padre = (android.view.ViewGroup) tv.getParent();
                tvServidorLab = new TextView(this);
                tvServidorLab.setTextSize(14);
                tvServidorLab.setTextColor(0xFF1E88E5);
                int pad = (int) (8 * getResources().getDisplayMetrics().density);
                tvServidorLab.setPadding(0, pad, 0, pad);
                padre.addView(tvServidorLab, padre.indexOfChild(tv) + 1);
                tvServidorLab.setOnClickListener(v -> {
                    if (com.lc.technician.sync.LabSession.activa(this)) {
                        Toast.makeText(this, "Desconecta primero para cambiar de servidor", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    com.lc.technician.sync.LabSession.setServidorWifi(this, !com.lc.technician.sync.LabSession.servidorWifi(this));
                    actualizarEstadoLab();
                });
            }
            if (tvServidorLab != null) {
                tvServidorLab.setText("Servidor: " + (com.lc.technician.sync.LabSession.servidorWifi(this)
                        ? "Wi-Fi laboratorio (HTTPS 10.0.0.121)" : "este teléfono (local)")
                        + (activa ? "" : " · toca para cambiar"));
            }
        }
    }

    private TextView tvServidorLab;

    /** Primer inicio de sesión: elegir contraseña propia (mín. 8). Solo después se guarda la sesión. */
    private void pedirNuevaClave(com.lc.technician.sync.SyncModels.LoginResponse lr, String usuario, String actual) {
        android.widget.LinearLayout caja = new android.widget.LinearLayout(this);
        caja.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        caja.setPadding(pad, pad / 2, pad, 0);
        EditText n1 = new EditText(this);
        EditText n2 = new EditText(this);
        for (EditText e : new EditText[]{n1, n2}) {
            e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            caja.addView(e);
        }
        n1.setHint("Contraseña nueva (mínimo 8)");
        n2.setHint("Repite la contraseña nueva");
        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle("Cambia tu contraseña")
                .setMessage("Es tu primer inicio de sesión: elige una contraseña propia para conectar.")
                .setView(caja)
                .setCancelable(false)
                .setPositiveButton("GUARDAR", null)
                .setNegativeButton("CANCELAR", (x, w) -> Toast.makeText(this,
                        "Sin conectar: debes cambiar la contraseña", Toast.LENGTH_LONG).show())
                .create();
        d.setOnShowListener(x -> d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = n1.getText().toString(), b = n2.getText().toString();
            if (a.length() < 8) { n1.setError("Mínimo 8 caracteres"); return; }
            if (!a.equals(b)) { n2.setError("No coinciden"); return; }
            if (a.equals(actual)) { n1.setError("Debe ser distinta de la inicial"); return; }
            v.setEnabled(false);
            Executors.newSingleThreadExecutor().execute(() -> {
                String m;
                boolean ok = false;
                try {
                    com.lc.technician.sync.SyncModels.CambioClaveRequest req = new com.lc.technician.sync.SyncModels.CambioClaveRequest();
                    req.actual = actual;
                    req.nueva = a;
                    retrofit2.Response<com.google.gson.JsonObject> r = ApiClient.get(this).cambiarClave("Bearer " + lr.token, req).execute();
                    ok = r.isSuccessful();
                    m = ok ? "Contraseña cambiada · conectado al laboratorio"
                            : r.code() == 429 ? "Cuenta bloqueada temporalmente" : "No se pudo cambiar (HTTP " + r.code() + ")";
                    if (ok) {
                        lr.mustChangePassword = false;
                        com.lc.technician.sync.LabSession.guardar(this, lr, usuario);
                    }
                } catch (Exception e) {
                    m = "Sin conexión con el laboratorio: " + e.getClass().getSimpleName();
                }
                final String fm = m;
                final boolean fok = ok;
                runOnUiThread(() -> {
                    n1.setText(""); n2.setText("");
                    if (fok) d.dismiss(); else v.setEnabled(true);
                    actualizarEstadoLab();
                    Toast.makeText(this, fm, Toast.LENGTH_LONG).show();
                });
            });
        }));
        d.show();
    }

    private void conectarLaboratorio() {
        if (com.lc.technician.sync.LabSession.activa(this)) {
            com.lc.technician.sync.LabSession.cerrar(this);
            actualizarEstadoLab();
            return;
        }
        EditText etU = findViewById(R.id.etLabUsuario);
        EditText etP = findViewById(R.id.etLabClave);
        String usuario = etU.getText().toString().trim();
        String clave = etP.getText().toString();
        if (usuario.isEmpty() || clave.isEmpty()) {
            Toast.makeText(this, "Usuario y contraseña requeridos", Toast.LENGTH_SHORT).show();
            return;
        }
        // Laboratorio: este teléfono (local) o la Wi-Fi privada por HTTPS (solo CA de laboratorio)
        String url = com.lc.technician.sync.LabSession.urlServidor(this);
        ApiClient.setBaseUrl(this, url);
        if (etSyncUrl != null) etSyncUrl.setText(url);
        Executors.newSingleThreadExecutor().execute(() -> {
            String msg;
            try {
                com.lc.technician.sync.SyncModels.LoginRequest req = new com.lc.technician.sync.SyncModels.LoginRequest();
                req.username = usuario;
                req.password = clave;
                req.deviceId = com.lc.technician.util.DeviceIdManager.getDeviceId(this);
                retrofit2.Response<com.lc.technician.sync.SyncModels.LoginResponse> r =
                        ApiClient.get(this).loginV2(req).execute();
                if (r.isSuccessful() && r.body() != null && r.body().token != null
                        && "TECHNICIAN".equals(r.body().role) && Boolean.TRUE.equals(r.body().mustChangePassword)) {
                    // cuenta nueva o restablecida: no se conecta hasta que el técnico elija su contraseña
                    final com.lc.technician.sync.SyncModels.LoginResponse lr = r.body();
                    runOnUiThread(() -> { etP.setText(""); pedirNuevaClave(lr, usuario, clave); });
                    return;
                } else if (r.isSuccessful() && r.body() != null && r.body().token != null
                        && "TECHNICIAN".equals(r.body().role)) {
                    com.lc.technician.sync.LabSession.guardar(this, r.body(), usuario);
                    msg = "Conectado al laboratorio";
                } else if (r.isSuccessful()) {
                    msg = "Esa cuenta no es de técnico";
                } else {
                    msg = r.code() == 401 ? "Credenciales inválidas" : "Error HTTP " + r.code();
                }
            } catch (Exception e) {
                msg = "Sin conexión con el laboratorio: " + e.getClass().getSimpleName();
            }
            final String m = msg;
            runOnUiThread(() -> {
                etP.setText("");   // la contraseña no se guarda
                actualizarEstadoLab();
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void actualizarInfoSync() {
        Executors.newSingleThreadExecutor().execute(() -> {
            LcDatabase db = LcDatabase.getInstance(this);
            boolean v2 = com.lc.technician.sync.LabSession.activa(this);
            // Pendientes reales (synced=0): no enviados aún
            int pendJ  = db.jobDao().countPendientesSinEnviar();
            int pendWI = db.workItemDao().countPendientesSinEnviar();
            int pendT  = v2 ? 0 : db.technicianDao().countUnsynced();
            int total  = pendJ + pendWI + pendT;
            // Rechazados por el servidor (synced=3): requieren edición para reintentar
            int rechazadosJ  = db.jobDao().countRechazados();
            int rechazadosWI = db.workItemDao().countRechazados();
            int rechazados   = rechazadosJ + rechazadosWI;

            SimpleDateFormat sdf = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.forLanguageTag("es"));

            SyncStateEntity pushState = db.syncStateDao().getByType("WORK_ITEM");
            String lastPush = "Nunca";
            if (pushState != null && pushState.lastSyncAt != null && pushState.lastSyncAt > 0) {
                lastPush = sdf.format(new Date(pushState.lastSyncAt));
            }

            SyncStateEntity pullState = db.syncStateDao().getByType("PULL_JOB");
            String lastPull = "Nunca";
            if (pullState != null && pullState.lastSyncAt != null && pullState.lastSyncAt > 0) {
                lastPull = sdf.format(new Date(pullState.lastSyncAt));
            }

            final String lsPush = lastPush;
            final String lsPull = lastPull;
            final int tot = total;
            String pendientesTexto;
            if (tot == 0 && rechazados == 0) {
                pendientesTexto = "Pendientes de subir: 0 ✓";
            } else {
                pendientesTexto = "Pendientes de subir: " + tot;
                if (rechazados > 0) {
                    pendientesTexto += "  ·  Rechazados: " + rechazados
                            + " (editar para reintentar)";
                }
            }
            final String pendTxt = pendientesTexto;
            runOnUiThread(() -> {
                if (tvUltimaSync != null)
                    tvUltimaSync.setText("Última subida: " + lsPush + "  |  Última bajada: " + lsPull);
                if (tvPendientesSync != null)
                    tvPendientesSync.setText(pendTxt);
            });
        });
    }

    // ── ADMINISTRAR DATOS LOCALES ────────────────────────────────────────────

    private void actualizarDatosLocales() {
        String cuentaDisplay = com.lc.technician.sync.LabSession.activa(this)
                ? com.lc.technician.sync.LabSession.usuario(this)
                : SessionManager.get(this).getTechnicianName();
        Executors.newSingleThreadExecutor().execute(() -> {
            LcDatabase db = LcDatabase.getInstance(this);
            int totalJobs    = db.jobDao().count();
            int sincJobs     = 0;
            for (com.lc.technician.model.JobEntity j : db.jobDao().getAll()) {
                if (j.synced == 1) sincJobs++;
            }
            int pendJobs     = db.jobDao().countPendientesSinEnviar();
            int rechJobs     = db.jobDao().countRechazados();
            int pendWI       = db.workItemDao().countPendientesSinEnviar();
            int rechWI       = db.workItemDao().countRechazados();

            com.lc.technician.model.SyncStateEntity pullState = db.syncStateDao().getByType("PULL_JOB");
            String ultimaSync = "Nunca";
            if (pullState != null && pullState.lastSyncAt != null && pullState.lastSyncAt > 0) {
                ultimaSync = FMT.format(new java.util.Date(pullState.lastSyncAt));
            }

            final String cu = cuentaDisplay != null ? cuentaDisplay : "—";
            final String stats = "Órdenes: " + totalJobs + " totales  ·  " + sincJobs + " sincronizadas\n"
                    + "Pendientes de subir: " + pendJobs + " órdenes · " + pendWI + " partidas"
                    + (rechJobs + rechWI > 0 ? "\nRechazados: " + (rechJobs + rechWI) + " (editar para reintentar)" : "")
                    + "\nÚltima sincronización confirmada: " + ultimaSync;

            runOnUiThread(() -> {
                if (tvDatosLocalCuenta != null) tvDatosLocalCuenta.setText("Cuenta: " + cu);
                if (tvDatosLocalStats  != null) tvDatosLocalStats.setText(stats);
            });
        });
    }

    private void iniciarVaciadoLocal() {
        Executors.newSingleThreadExecutor().execute(() -> {
            LcDatabase db = LcDatabase.getInstance(this);
            int pendJobs = db.jobDao().countPendientesSinEnviar();
            int pendWI   = db.workItemDao().countPendientesSinEnviar();
            int total    = pendJobs + pendWI;
            runOnUiThread(() -> {
                if (total > 0) {
                    Toast.makeText(this,
                            "Hay " + total + " elemento(s) sin enviar al servidor.\n"
                                    + "Sincroniza primero antes de vaciar.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                mostrarDialogoVaciar();
            });
        });
    }

    private void mostrarDialogoVaciar() {
        android.widget.LinearLayout caja = new android.widget.LinearLayout(this);
        caja.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(20 * getResources().getDisplayMetrics().density);
        caja.setPadding(pad, pad / 2, pad, 0);

        TextView aviso = new TextView(this);
        aviso.setText("Se eliminarán las órdenes ya sincronizadas y sus partidas de trabajo. "
                + "Los datos del servidor NO se ven afectados. "
                + "Escribe VACIAR para confirmar.");
        aviso.setTextSize(13f);
        aviso.setTextColor(0xFF333333);
        caja.addView(aviso);

        EditText etConfirm = new EditText(this);
        etConfirm.setHint("Escribe VACIAR");
        etConfirm.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        caja.addView(etConfirm);

        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle("⚠ Vaciar datos locales")
                .setView(caja)
                .setCancelable(true)
                .setPositiveButton("VACIAR", null)
                .setNegativeButton("Cancelar", null)
                .create();
        d.setOnShowListener(x -> d.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (!"VACIAR".equals(etConfirm.getText().toString().trim().toUpperCase())) {
                        etConfirm.setError("Escribe exactamente VACIAR");
                        return;
                    }
                    d.dismiss();
                    ejecutarVaciadoLocal();
                }));
        d.show();
    }

    private void ejecutarVaciadoLocal() {
        if (tvEstadoVaciar != null) {
            tvEstadoVaciar.setText("Vaciando datos locales…");
            tvEstadoVaciar.setVisibility(View.VISIBLE);
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                LcDatabase db = LcDatabase.getInstance(this);
                // Recopilar ids de órdenes ya confirmadas en el servidor (synced=1)
                java.util.List<Long> idsParaBorrar = new java.util.ArrayList<>();
                for (com.lc.technician.model.JobEntity j : db.jobDao().getAll()) {
                    if (j.synced == 1) idsParaBorrar.add(j.id);
                }
                if (idsParaBorrar.isEmpty()) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "No hay datos sincronizados que vaciar.",
                                Toast.LENGTH_SHORT).show();
                        if (tvEstadoVaciar != null) tvEstadoVaciar.setVisibility(View.GONE);
                    });
                    return;
                }
                // Borrar partidas de trabajo de esas órdenes (synced=1)
                for (Long jid : idsParaBorrar) {
                    // Solo borrar workitems que también estén sincronizadas
                    java.util.List<com.lc.technician.model.WorkItemEntity> wis =
                            db.workItemDao().getByJob(jid);
                    for (com.lc.technician.model.WorkItemEntity wi : wis) {
                        if (wi.synced == 1) db.workItemDao().delete(wi);
                    }
                }
                // Borrar las órdenes confirmadas
                db.jobDao().deleteByIds(idsParaBorrar);
                // Invalidar caché del repositorio
                com.lc.technician.data.JobRepository.get().recargarCacheAsync(null);
                final int borradas = idsParaBorrar.size();
                runOnUiThread(() -> {
                    Toast.makeText(this,
                            "Datos locales vaciados (" + borradas + " órdenes eliminadas).",
                            Toast.LENGTH_LONG).show();
                    if (tvEstadoVaciar != null) {
                        tvEstadoVaciar.setText("✓ " + borradas + " órdenes eliminadas localmente.");
                        tvEstadoVaciar.setVisibility(View.VISIBLE);
                    }
                    actualizarDatosLocales();
                    actualizarInfoSync();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Error al vaciar: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    if (tvEstadoVaciar != null) {
                        tvEstadoVaciar.setText("❌ Error: " + e.getMessage());
                        tvEstadoVaciar.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    // ── BACKUP DE CARPETA EXISTENTE ──────────────────────────────────────────

    private void cargarUltimoBackup() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long ts = prefs.getLong("ultimo_backup_ts", 0);
        if (ts == 0) {
            tvUltimoBackup.setText("Sin backups aún.");
        } else {
            String fecha = FMT.format(new Date(ts));
            float mb = prefs.getFloat("ultimo_backup_size_mb", 0f);
            tvUltimoBackup.setText(fecha + "  ·  " + String.format(Locale.US, "%.1f MB", mb));
        }
    }

    private void hacerBackup() {
        btnHacerBackup.setEnabled(false);
        tvEstadoBackup.setText("Respaldando datos…");
        tvEstadoBackup.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                String ts = String.valueOf(System.currentTimeMillis());
                File destDir = new File(getExternalFilesDir(null), "backups/backup_" + ts);
                destDir.mkdirs();

                File dbFile = getDatabasePath("lc_database");
                for (String suf : new String[]{"", "-shm", "-wal"}) {
                    File src = new File(dbFile.getPath() + suf);
                    if (src.exists()) copyFile(src, new File(destDir, src.getName()));
                }

                File fotosDir = new File(getExternalFilesDir(null), "fotos");
                if (fotosDir.exists()) copyDir(fotosDir, new File(destDir, "fotos"));

                File reportesDir = new File(getExternalFilesDir(null), "reportes");
                if (reportesDir.exists()) copyDir(reportesDir, new File(destDir, "reportes"));

                long bytes = dirSize(destDir);
                float mb = bytes / (1024f * 1024f);

                SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                ed.putLong("ultimo_backup_ts", System.currentTimeMillis());
                ed.putFloat("ultimo_backup_size_mb", mb);
                ed.putString("ultimo_backup_path", destDir.getAbsolutePath());
                ed.apply();

                runOnUiThread(() -> {
                    btnHacerBackup.setEnabled(true);
                    tvEstadoBackup.setText(String.format(Locale.US, "✓ Backup guardado: %.1f MB", mb));
                    cargarUltimoBackup();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnHacerBackup.setEnabled(true);
                    tvEstadoBackup.setText("❌ Error: " + e.getMessage());
                });
            }
        }).start();
    }

    // ── BACKUP DB ÚNICO ARCHIVO ──────────────────────────────────────────────

    private void cargarUltimoBackupDB() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        long ts = prefs.getLong("ultimo_backup_db_ts", 0);
        if (ts == 0) {
            tvUltimoBackupDB.setText("Sin backups DB aún.");
        } else {
            String fecha = FMT.format(new Date(ts));
            float mb = prefs.getFloat("ultimo_backup_db_mb", 0f);
            tvUltimoBackupDB.setText("Último backup: " + fecha + " · "
                    + String.format(Locale.US, "%.1f MB", mb));
        }
    }

    private void exportarBackupDB() {
        btnExportarBackup.setEnabled(false);
        tvEstadoBackupDB.setText("Exportando DB…");
        tvEstadoBackupDB.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                String stamp = BACKUP_FMT.format(new Date());
                File dstDir = new File(getExternalFilesDir(
                        android.os.Environment.DIRECTORY_DOWNLOADS), "");
                dstDir.mkdirs();
                File dst = new File(dstDir, "lc_backup_" + stamp + ".db");

                // Copy main DB + WAL/SHM for consistency
                File dbSrc = getDatabasePath("lc_database");
                copyFile(dbSrc, dst);
                // Also copy WAL if it exists so restore includes latest transactions
                File walSrc = new File(dbSrc.getPath() + "-wal");
                if (walSrc.exists() && walSrc.length() > 0) {
                    copyFile(walSrc, new File(dstDir, "lc_backup_" + stamp + ".db-wal"));
                }

                long bytes = dst.length();
                float mb = bytes / (1024f * 1024f);

                SharedPreferences.Editor ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                ed.putLong("ultimo_backup_db_ts", System.currentTimeMillis());
                ed.putFloat("ultimo_backup_db_mb", mb);
                ed.putString("ultimo_backup_db_path", dst.getAbsolutePath());
                ed.apply();

                runOnUiThread(() -> {
                    btnExportarBackup.setEnabled(true);
                    tvEstadoBackupDB.setVisibility(View.GONE);
                    cargarUltimoBackupDB();
                    new AlertDialog.Builder(this)
                            .setTitle("✓ Backup exportado")
                            .setMessage("Archivo: " + dst.getName()
                                    + "\nRuta: " + dst.getAbsolutePath()
                                    + "\nTamaño: " + String.format(Locale.US, "%.2f MB", mb))
                            .setPositiveButton("OK", null)
                            .setNeutralButton("Compartir", (d, w) -> compartirArchivo(dst))
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnExportarBackup.setEnabled(true);
                    tvEstadoBackupDB.setText("❌ " + e.getMessage());
                    tvEstadoBackupDB.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    private void restaurarDesdeUri(Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle("Restaurar backup")
                .setMessage("¿Reemplazar la base de datos actual con el archivo seleccionado? "
                        + "Se perderán los cambios no respaldados.")
                .setPositiveButton("Restaurar", (d, w) -> ejecutarRestauracion(uri))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void ejecutarRestauracion(Uri uri) {
        tvEstadoBackupDB.setText("Restaurando…");
        tvEstadoBackupDB.setVisibility(View.VISIBLE);
        btnRestaurarBackup.setEnabled(false);

        new Thread(() -> {
            try {
                // Close Room database
                LcDatabase.getInstance(getApplicationContext()).close();

                File dbDst = getDatabasePath("lc_database");
                dbDst.getParentFile().mkdirs();

                // Delete WAL/SHM
                new File(dbDst.getPath() + "-wal").delete();
                new File(dbDst.getPath() + "-shm").delete();

                // Copy from URI
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(dbDst)) {
                    if (in == null) throw new IOException("No se pudo abrir el archivo");
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }

                runOnUiThread(() -> {
                    btnRestaurarBackup.setEnabled(true);
                    tvEstadoBackupDB.setVisibility(View.GONE);
                    Toast.makeText(this, "Backup restaurado. Reiniciá la app.",
                            Toast.LENGTH_LONG).show();
                    // Relaunch app
                    Intent intent = getBaseContext().getPackageManager()
                            .getLaunchIntentForPackage(getBaseContext().getPackageName());
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnRestaurarBackup.setEnabled(true);
                    tvEstadoBackupDB.setText("❌ " + e.getMessage());
                    tvEstadoBackupDB.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    private void compartirBackupDB() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String path = prefs.getString("ultimo_backup_db_path", null);
        if (path == null) {
            Toast.makeText(this, "Primero exportá un backup", Toast.LENGTH_SHORT).show();
            return;
        }
        File f = new File(path);
        if (!f.exists()) {
            Toast.makeText(this, "Archivo no encontrado. Exportá uno nuevo.", Toast.LENGTH_SHORT).show();
            return;
        }
        compartirArchivo(f);
    }

    private void compartirArchivo(File f) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/octet-stream");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "LC Backup " + f.getName());
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Compartir backup"));
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ── UTILS ────────────────────────────────────────────────────────────────

    private static void copyFile(File src, File dst) throws IOException {
        dst.getParentFile().mkdirs();
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private static void copyDir(File src, File dst) throws IOException {
        dst.mkdirs();
        File[] files = src.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) copyDir(f, new File(dst, f.getName()));
            else copyFile(f, new File(dst, f.getName()));
        }
    }

    private static long dirSize(File dir) {
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) total += f.isDirectory() ? dirSize(f) : f.length();
        return total;
    }

    private void actualizarRendimiento() {
        Runtime rt = Runtime.getRuntime();
        long usedBytes = rt.totalMemory() - rt.freeMemory();
        long maxBytes  = rt.maxMemory();
        int cacheSize  = JobRepository.get().getCacheSize();

        tvRamUsada.setText(String.format(Locale.US,
                "RAM usada: %.1f MB", usedBytes / (1024f * 1024f)));
        tvRamMax.setText(String.format(Locale.US,
                "RAM máxima (heap): %.1f MB", maxBytes / (1024f * 1024f)));
        tvCacheJobs.setText("Jobs en caché: " + cacheSize);
    }

    @Override
    protected void onResume() {
        super.onResume();
        actualizarRendimiento();
        syncTimeHandler.post(updateSyncTime);
        actualizarDatosLocales();
    }

    @Override
    protected void onPause() {
        super.onPause();
        syncTimeHandler.removeCallbacks(updateSyncTime);
    }

    // ── FASE 15: Coberturas ──────────────────────────────────────────────────

    private void cargarCoberturas() {
        new Thread(() -> {
            List<CoverageTypeEntity> tipos = LcDatabase.getInstance(this).coverageDao().getAllActive();
            runOnUiThread(() -> {
                if (layoutCoberturas == null) return;
                layoutCoberturas.removeAllViews();
                for (CoverageTypeEntity c : tipos) {
                    TextView row = new TextView(this);
                    row.setText(c.displayName + "  ·  $" +
                            String.format(Locale.US, "%.0f", c.deductibleAmount) +
                            " ded  ·  " +
                            String.format(Locale.US, "%.0f", c.coveragePercentage) + "%  →");
                    row.setTextSize(14);
                    row.setPadding(0, 16, 0, 16);
                    row.setTextColor(0xFF1A1A1A);
                    row.setOnClickListener(v -> mostrarDialogoEditarCobertura(c));
                    layoutCoberturas.addView(row);
                }
            });
        }).start();
    }

    private void mostrarDialogoEditarCobertura(CoverageTypeEntity cov) {
        EditText etDeductible = new EditText(this);
        etDeductible.setHint("Deducible ($)");
        etDeductible.setText(String.format(Locale.US, "%.0f", cov.deductibleAmount));
        etDeductible.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);

        EditText etCoverage = new EditText(this);
        etCoverage.setHint("Cobertura (%)");
        etCoverage.setText(String.format(Locale.US, "%.0f", cov.coveragePercentage));
        etCoverage.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 16, 48, 8);
        layout.addView(etDeductible);
        layout.addView(etCoverage);

        new AlertDialog.Builder(this)
                .setTitle("Editar: " + cov.displayName)
                .setView(layout)
                .setPositiveButton("Guardar", (d, w) -> {
                    try {
                        cov.deductibleAmount = Double.parseDouble(etDeductible.getText().toString().trim());
                        cov.coveragePercentage = Double.parseDouble(etCoverage.getText().toString().trim());
                        cov.updatedAt = System.currentTimeMillis();
                        new Thread(() -> {
                            LcDatabase.getInstance(this).coverageDao().update(cov);
                            runOnUiThread(this::cargarCoberturas);
                        }).start();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Valor inválido", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void guardarPropuestaConfig(ConfigManager config) {
        try {
            float mg  = Float.parseFloat(etMarkupGood.getText().toString().trim());
            float mb  = Float.parseFloat(etMarkupBetter.getText().toString().trim());
            float mbe = Float.parseFloat(etMarkupBest.getText().toString().trim());
            float tax = Float.parseFloat(etTaxRate.getText().toString().trim());

            boolean ok = config.setMarkupGood(mg)
                    & config.setMarkupBetter(mb)
                    & config.setMarkupBest(mbe)
                    & config.setTaxRate(tax);

            if (ok) {
                Toast.makeText(this, "Configuración de propuestas guardada", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "Valores fuera de rango. Markup: 0–100%, Tax: 0–30%",
                        Toast.LENGTH_LONG).show();
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Valor inválido — usa solo números", Toast.LENGTH_SHORT).show();
        }
    }

    private void cargarMemberships() {
        new Thread(() -> {
            List<MembershipCardEntity> cards = LcDatabase.getInstance(this)
                    .membershipCardDao().getAll();
            runOnUiThread(() -> {
                if (layoutMemberships == null) return;
                layoutMemberships.removeAllViews();
                if (cards.isEmpty()) {
                    TextView tv = new TextView(this);
                    tv.setText("Sin tarjetas registradas");
                    tv.setTextColor(0xFF9A9A9A);
                    tv.setPadding(0, 16, 0, 16);
                    layoutMemberships.addView(tv);
                    return;
                }
                for (MembershipCardEntity card : cards) {
                    LinearLayout fila = new LinearLayout(this);
                    fila.setOrientation(LinearLayout.HORIZONTAL);

                    TextView row = new TextView(this);
                    String status = MembershipCardEntity.STATUS_ACTIVE.equals(card.status)
                            ? "\u2705" : "\u274C";
                    row.setText(status + " " + card.cardNumber + "  \u00b7  " + card.status);
                    row.setTextSize(14);
                    row.setPadding(0, 16, 0, 16);
                    row.setTextColor(0xFF1A1A1A);
                    row.setLayoutParams(new LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                    Button btnPdf = new Button(this);
                    btnPdf.setText("PDF");
                    btnPdf.setTextColor(android.graphics.Color.WHITE);
                    btnPdf.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(0xFFEB1700));
                    btnPdf.setTextSize(11);
                    btnPdf.setPadding(16, 4, 16, 4);
                    final MembershipCardEntity cardFinal = card;
                    btnPdf.setOnClickListener(v -> exportarPdfTarjeta(cardFinal));

                    Button btnHist = new Button(this);
                    btnHist.setText("HIST");
                    btnHist.setTextColor(android.graphics.Color.WHITE);
                    btnHist.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(0xFF1B71C9));
                    btnHist.setTextSize(11);
                    btnHist.setPadding(16, 4, 16, 4);
                    final MembershipCardEntity cardFinalH = card;
                    btnHist.setOnClickListener(v -> {
                        Intent i = new Intent(this, MembershipHistoryActivity.class);
                        i.putExtra(MembershipHistoryActivity.EXTRA_CARD_ID, cardFinalH.id);
                        i.putExtra(MembershipHistoryActivity.EXTRA_CARD_NUMBER,
                                cardFinalH.cardNumber);
                        startActivity(i);
                    });

                    fila.addView(row);
                    fila.addView(btnPdf);
                    fila.addView(btnHist);
                    layoutMemberships.addView(fila);
                }
            });
        }).start();
    }

    private void exportarPdfTarjeta(MembershipCardEntity card) {
        new Thread(() -> {
            try {
                com.lc.technician.model.CustomerEntity customer = null;
                com.lc.technician.model.CoverageTypeEntity coverage = null;

                try {
                    customer = LcDatabase.getInstance(this)
                            .customerDao().getById(card.customerId);
                } catch (Exception ignored) {}

                if (card.coverageTypeId != null) {
                    try {
                        coverage = LcDatabase.getInstance(this)
                                .coverageDao().getById(card.coverageTypeId);
                    } catch (Exception ignored) {}
                }

                File exportsDir = new File(getExternalFilesDir(null), "exports");
                exportsDir.mkdirs();
                String safeName = card.cardNumber != null
                        ? card.cardNumber.replace("/", "_") : "tarjeta";
                File pdfFile = new File(exportsDir, "tarjeta_" + safeName + ".pdf");

                boolean ok = com.lc.technician.util.MembershipCardPdfGenerator
                        .generateCardPdf(this, card, customer, coverage, pdfFile);

                runOnUiThread(() -> {
                    if (!ok || !pdfFile.exists()) {
                        Toast.makeText(this, "Error generando PDF", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    android.net.Uri uri = FileProvider.getUriForFile(
                            this, getPackageName() + ".fileprovider", pdfFile);
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("application/pdf");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.putExtra(Intent.EXTRA_SUBJECT,
                            "Tarjeta Membresia " + card.cardNumber);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "Compartir tarjeta"));
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void mostrarDialogoGenerarTarjeta() {
        new Thread(() -> {
            List<CoverageTypeEntity> tipos = LcDatabase.getInstance(this)
                    .coverageDao().getAllActive();
            List<com.lc.technician.model.CustomerEntity> clientes =
                    LcDatabase.getInstance(this).customerDao().getAll();

            runOnUiThread(() -> {
                if (tipos.isEmpty()) {
                    Toast.makeText(this, "No hay tipos de cobertura configurados",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (clientes.isEmpty()) {
                    Toast.makeText(this, "No hay clientes registrados",
                            Toast.LENGTH_SHORT).show();
                    return;
                }

                LinearLayout form = new LinearLayout(this);
                form.setOrientation(LinearLayout.VERTICAL);
                form.setPadding(48, 16, 48, 8);

                android.widget.Spinner spinnerClientes = new android.widget.Spinner(this);
                String[] nombresClientes = new String[clientes.size()];
                for (int i = 0; i < clientes.size(); i++) {
                    nombresClientes[i] = clientes.get(i).nombre;
                }
                android.widget.ArrayAdapter<String> adpClientes =
                        new android.widget.ArrayAdapter<>(this,
                                android.R.layout.simple_spinner_item, nombresClientes);
                adpClientes.setDropDownViewResource(
                        android.R.layout.simple_spinner_dropdown_item);
                spinnerClientes.setAdapter(adpClientes);

                android.widget.Spinner spinnerCobertura = new android.widget.Spinner(this);
                String[] nombresCobertura = new String[tipos.size()];
                for (int i = 0; i < tipos.size(); i++) {
                    nombresCobertura[i] = tipos.get(i).displayName;
                }
                android.widget.ArrayAdapter<String> adpCob =
                        new android.widget.ArrayAdapter<>(this,
                                android.R.layout.simple_spinner_item, nombresCobertura);
                adpCob.setDropDownViewResource(
                        android.R.layout.simple_spinner_dropdown_item);
                spinnerCobertura.setAdapter(adpCob);

                EditText etExpiry = new EditText(this);
                etExpiry.setHint("Meses de vigencia (ej: 12)");
                etExpiry.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
                etExpiry.setText("12");

                EditText etNotas = new EditText(this);
                etNotas.setHint("Notas (opcional)");

                TextView lblCliente = new TextView(this);
                lblCliente.setText("Cliente:");
                lblCliente.setTextSize(12);
                lblCliente.setTextColor(0xFF9A9A9A);
                lblCliente.setPadding(0, 16, 0, 4);

                TextView lblCob = new TextView(this);
                lblCob.setText("Tipo de membresía:");
                lblCob.setTextSize(12);
                lblCob.setTextColor(0xFF9A9A9A);
                lblCob.setPadding(0, 16, 0, 4);

                TextView lblMeses = new TextView(this);
                lblMeses.setText("Vigencia (meses):");
                lblMeses.setTextSize(12);
                lblMeses.setTextColor(0xFF9A9A9A);
                lblMeses.setPadding(0, 16, 0, 4);

                form.addView(lblCliente);
                form.addView(spinnerClientes);
                form.addView(lblCob);
                form.addView(spinnerCobertura);
                form.addView(lblMeses);
                form.addView(etExpiry);
                form.addView(etNotas);

                final List<CoverageTypeEntity> tiposFinal = tipos;
                final List<com.lc.technician.model.CustomerEntity> clientesFinal = clientes;

                new AlertDialog.Builder(this)
                        .setTitle("Generar tarjeta de membresía")
                        .setView(form)
                        .setPositiveButton("Generar", (d, w) -> {
                            int idxCliente = spinnerClientes.getSelectedItemPosition();
                            int idxCob = spinnerCobertura.getSelectedItemPosition();
                            int meses = 12;
                            try { meses = Integer.parseInt(etExpiry.getText().toString().trim()); }
                            catch (NumberFormatException ignored) {}

                            com.lc.technician.model.CustomerEntity cliente =
                                    clientesFinal.get(idxCliente);
                            CoverageTypeEntity cob = tiposFinal.get(idxCob);

                            generarTarjeta(cliente, cob, meses,
                                    etNotas.getText().toString().trim());
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
            });
        }).start();
    }

    // ── Dashboard URL helpers ─────────────────────────────────────────────────

    private String getDashboardUrl() {
        String baseUrl = ApiClient.getBaseUrl(this);
        // Strip /api/ or /api to get root URL, then point to L&C Control
        if (baseUrl.endsWith("/api/")) {
            return baseUrl.substring(0, baseUrl.length() - 5) + "/control/";
        }
        if (baseUrl.endsWith("/api")) {
            return baseUrl.substring(0, baseUrl.length() - 4) + "/control/";
        }
        try {
            java.net.URL u = new java.net.URL(baseUrl);
            int port = u.getPort();
            return u.getProtocol() + "://" + u.getHost()
                    + (port != -1 ? ":" + port : "") + "/control/";
        } catch (Exception ignored) {}
        if (!baseUrl.endsWith("/")) baseUrl += "/";
        return baseUrl + "control/";
    }

    private String getTailscaleUrl() {
        return "https://tailscale-termux.tail1142a7.ts.net";
    }

    private Bitmap generarQR(String texto, int sizePx) {
        try {
            java.util.Hashtable<com.google.zxing.EncodeHintType, Object> hints = new java.util.Hashtable<>();
            hints.put(com.google.zxing.EncodeHintType.MARGIN, 1);
            com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
            com.google.zxing.common.BitMatrix matrix = writer.encode(
                    texto, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
            for (int x = 0; x < sizePx; x++) {
                for (int y = 0; y < sizePx; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    private void generarTarjeta(com.lc.technician.model.CustomerEntity cliente,
                                CoverageTypeEntity cob, int meses, String notas) {
        new Thread(() -> {
            MembershipCardDao dao = LcDatabase.getInstance(this).membershipCardDao();
            List<MembershipCardEntity> todas = dao.getAll();
            int nextNum = todas.size() + 1;
            String cardNumber = String.format("LC-%s-%06d", cob.code, nextNum);

            long now = System.currentTimeMillis();
            long expiry = now + (long) meses * 30L * 24 * 3600 * 1000;

            MembershipCardEntity card = new MembershipCardEntity();
            card.cardNumber = cardNumber;
            card.customerId = cliente.id;
            card.coverageTypeId = cob.id;
            card.status = MembershipCardEntity.STATUS_ACTIVE;
            card.paymentStatus = MembershipCardEntity.PAYMENT_PAID;
            card.issueDate = now;
            card.expiryDate = expiry;
            card.notes = notas;
            card.createdAt = now;
            card.updatedAt = now;

            dao.insert(card);

            runOnUiThread(() -> {
                Toast.makeText(this, "Tarjeta generada: " + cardNumber,
                        Toast.LENGTH_LONG).show();
                cargarMemberships();
            });
        }).start();
    }
}
