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
        reorganizarSecciones();
        // Edge-to-edge (Android 15+): cabecera bajo la barra de estado, Dispatch / Cola visible sobre la navegación
        InsetsHelper.padTop(findViewById(R.id.header));
        InsetsHelper.padBottom(findViewById(R.id.scrollConfig));

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

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

        actualizarRendimiento();

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
        switchSyncAuto  = findViewById(R.id.switchSyncAuto);

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

        Button btnSyncNow = findViewById(R.id.btnSyncAhora);
        if (btnSyncNow != null) btnSyncNow.setOnClickListener(v -> sincronizarAhora());
        if (btnSyncNow != null && com.lc.technician.BuildConfig.CLOUD_LOGIN
                && btnSyncNow.getParent() instanceof android.view.ViewGroup) {
            // QA: limpia la copia local de órdenes que el servidor ya no tiene y vuelve a bajar todo
            android.view.ViewGroup padre = (android.view.ViewGroup) btnSyncNow.getParent();
            Button btnResync = new Button(this);
            btnResync.setText("RESINCRONIZAR DESDE EL SERVIDOR");
            btnResync.setTextSize(12);
            btnResync.setOnClickListener(v -> resincronizarDesdeServidor());
            padre.addView(btnResync, padre.indexOfChild(btnSyncNow) + 1);
        }

        Button btnCambiarPin = findViewById(R.id.btnCambiarPin);
        if (btnCambiarPin != null) btnCambiarPin.setOnClickListener(v -> mostrarDialogoCambiarPin());

        Button btnActivarPin = findViewById(R.id.btnActivarPinServidor);
        if (btnActivarPin != null) btnActivarPin.setOnClickListener(v -> activarPinDelServidor());

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

    /**
     * Resincronización forzada (QA). Borra de Room las copias ya confirmadas por el servidor (synced = 1) y vuelve a
     * bajarlas desde cero. NUNCA toca cambios pendientes de subir (synced != 1). Antes comprueba que el servidor
     * responde, para no vaciar el teléfono sin poder reponerlo.
     */
    private void resincronizarDesdeServidor() {
        new AlertDialog.Builder(this)
            .setTitle("Resincronizar desde el servidor")
            .setMessage("Se borrará la copia local de órdenes ya sincronizadas y se volverá a descargar todo desde el servidor. "
                    + "Los cambios que aún no se han subido se conservan.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Resincronizar", (d, w) -> Executors.newSingleThreadExecutor().execute(() -> {
                int borradas = -1, conservadas = 0, fotosSubidas = 0, fotosCuarentena = 0;
                try {
                    com.lc.technician.sync.SupabaseClient sb = com.lc.technician.sync.SupabaseClient.get(this);
                    retrofit2.Response<java.util.List<com.lc.technician.sync.SupabaseModels.JobRow>> r =
                            sb.api().getJobsSince(sb.authHeader(), "gte.0", "id,created_at", 1000).execute();
                    if (r.isSuccessful() && r.body() != null && r.body().size() < 1000) {
                        java.util.Map<Long, Long> visibles = new java.util.HashMap<>();
                        for (com.lc.technician.sync.SupabaseModels.JobRow row : r.body()) {
                            visibles.put(row.id, row.createdAt == null ? 0L : row.createdAt);
                        }
                        LcDatabase db = LcDatabase.getInstance(getApplicationContext());
                        // 1) fotos: se suben a Storage antes de tocar nada; las que no pertenecen a una orden del servidor
                        //    pasan a cuarentena (se conservan en el teléfono); nunca se borra una foto
                        com.lc.technician.sync.PhotoSync.Resultado fr =
                                com.lc.technician.sync.PhotoSync.procesarParaPurga(getApplicationContext(), db, sb, visibles);
                        fotosSubidas = fr.subidas;
                        fotosCuarentena = fr.enCuarentena;
                        // 2) órdenes: solo las ya confirmadas por el servidor y sin fotos pendientes de subir
                        java.util.List<Long> fantasmas = new java.util.ArrayList<>();
                        for (com.lc.technician.model.JobEntity e : db.jobDao().getAll()) {
                            if (e.synced == 1 && !fr.ordenesConFotosPendientes.contains(e.id)) fantasmas.add(e.id);
                            else if (e.synced == 1) conservadas++;
                        }
                        if (!fantasmas.isEmpty()) db.jobDao().deleteByIds(fantasmas);
                        borradas = fantasmas.size();
                        if (db.syncStateDao().countByType("PULL_JOB") > 0) db.syncStateDao().markSuccess("PULL_JOB", 0L);
                        com.lc.technician.data.JobRepository.get().recargarCacheAsync(null);
                    }
                } catch (Exception ignored) {}
                final int n = borradas, cons = conservadas, fs = fotosSubidas, fq = fotosCuarentena;
                runOnUiThread(() -> {
                    Toast.makeText(this, n < 0 ? "No se pudo consultar el servidor; no se borró nada"
                            : "Se quitaron " + n + " orden(es) antiguas · fotos subidas: " + fs + " · fotos en cuarentena: " + fq
                              + (cons > 0 ? " · conservadas por fotos sin subir: " + cons : "") + ". Descargando…", Toast.LENGTH_LONG).show();
                    if (n >= 0) sincronizarAhora();
                });
            }))
            .show();
    }

    private void sincronizarAhora() {
        Toast.makeText(this, "Sincronizando con Supabase…", Toast.LENGTH_SHORT).show();
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
                            String msg = "✓ Subidos: " + pushed + "  |  Bajados: " + pulled + " jobs";
                            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "! Sincronización con errores", Toast.LENGTH_SHORT).show();
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


    private void actualizarInfoSync() {
        Executors.newSingleThreadExecutor().execute(() -> {
            LcDatabase db = LcDatabase.getInstance(this);
            // Pendientes reales (synced=0): no enviados aún
            int pendJ  = db.jobDao().countPendientesSinEnviar();
            int pendWI = db.workItemDao().countPendientesSinEnviar();
            int pendT  = 0;
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
        String cuentaDisplay = SessionManager.get(this).getTechnicianName();
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
                        tvEstadoVaciar.setText("✕ Error: " + e.getMessage());
                        tvEstadoVaciar.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
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
        actualizarEstadoPin();
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
                            android.content.res.ColorStateList.valueOf(0xFFE91605));
                    btnPdf.setTextSize(11);
                    btnPdf.setPadding(16, 4, 16, 4);
                    final MembershipCardEntity cardFinal = card;
                    btnPdf.setOnClickListener(v -> exportarPdfTarjeta(cardFinal));

                    Button btnHist = new Button(this);
                    btnHist.setText("HIST");
                    btnHist.setTextColor(android.graphics.Color.WHITE);
                    btnHist.setBackgroundTintList(
                            android.content.res.ColorStateList.valueOf(0xFFE91605));
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
        return getTailscaleUrl() + "/control/";
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

    // ── GESTIÓN DE PIN DE ACCESO RÁPIDO ─────────────────────────────────────

    private void mostrarDialogoCambiarPin() {
        android.widget.LinearLayout caja = new android.widget.LinearLayout(this);
        caja.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        caja.setPadding(pad, pad / 2, pad, 0);

        EditText etActual = new EditText(this);
        etActual.setHint("PIN actual (o PIN de compilación)");
        etActual.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etActual.setMaxLines(1);
        caja.addView(etActual);

        EditText etNuevo = new EditText(this);
        etNuevo.setHint("PIN nuevo (4 a 8 dígitos)");
        etNuevo.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etNuevo.setMaxLines(1);
        caja.addView(etNuevo);

        EditText etConfirm = new EditText(this);
        etConfirm.setHint("Confirmar PIN nuevo");
        etConfirm.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        etConfirm.setMaxLines(1);
        caja.addView(etConfirm);

        android.app.AlertDialog d = new android.app.AlertDialog.Builder(this)
                .setTitle("Cambiar PIN de acceso")
                .setMessage("El PIN permite entrar a la app sin contraseña.")
                .setView(caja)
                .setCancelable(true)
                .setPositiveButton("GUARDAR", null)
                .setNegativeButton("Cancelar", null)
                .create();

        d.setOnShowListener(x -> d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String actual  = etActual.getText().toString().trim();
            String nuevo   = etNuevo.getText().toString().trim();
            String confirm = etConfirm.getText().toString().trim();

            // Verify current PIN
            String role = LoginActivity.resolveRole(this, actual);
            if (role == null) {
                etActual.setError("PIN incorrecto");
                return;
            }
            if (nuevo.length() < 4 || nuevo.length() > 8) {
                etNuevo.setError("El PIN debe tener entre 4 y 8 dígitos");
                return;
            }
            if (!nuevo.equals(confirm)) {
                etConfirm.setError("Los PINs no coinciden");
                return;
            }

            // Save dynamic override for the resolved role
            android.content.SharedPreferences prefs = getSharedPreferences(LoginActivity.PREFS, MODE_PRIVATE);
            String key = roleToKey(role);
            prefs.edit().putString(key, nuevo).apply();

            d.dismiss();
            actualizarEstadoPin();
            Toast.makeText(this, "PIN actualizado correctamente", Toast.LENGTH_SHORT).show();
        }));
        d.show();
    }

    private void activarPinDelServidor() {
        // PIN server reset is managed via the web dashboard.
        // Ask the admin to generate a PIN and share it manually.
        new android.app.AlertDialog.Builder(this)
                .setTitle("PIN del servidor")
                .setMessage("Para recibir un PIN generado desde el panel de administración, "
                        + "pídele al administrador que lo genere y te lo envíe. "
                        + "Luego usa 'CAMBIAR MI PIN' para activarlo.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void actualizarEstadoPin() {
        TextView tv = findViewById(R.id.tvPinEstado);
        if (tv == null) return;
        android.content.SharedPreferences p = getSharedPreferences(LoginActivity.PREFS, MODE_PRIVATE);
        boolean tieneOverride = p.contains(LoginActivity.KEY_PIN_TECNICO)
                || p.contains(LoginActivity.KEY_PIN_DISPATCH)
                || p.contains(LoginActivity.KEY_PIN_ADMIN);
        tv.setText(tieneOverride
                ? "PIN personalizado activo (guardado en este dispositivo)"
                : "Usando PIN de compilación. Puedes cambiarlo aquí.");
    }

    private static String roleToKey(String role) {
        switch (role) {
            case "DISPATCHER": return LoginActivity.KEY_PIN_DISPATCH;
            case "ADMIN":      return LoginActivity.KEY_PIN_ADMIN;
            default:           return LoginActivity.KEY_PIN_TECNICO;
        }
    }

    /**
     * Agrupa las secciones de Ajustes en cuatro bloques plegables (Operación, Administración, Datos y Avanzado, este último
     * cerrado). No se elimina ni se mueve de lugar ninguna vista de su dueño: solo cambia el contenedor, así los ids y los
     * listeners siguen funcionando.
     */
    private void reorganizarSecciones() {
        android.widget.ScrollView sv = null;
        View raiz = findViewById(android.R.id.content);
        sv = encontrarScroll(raiz);
        if (sv == null || !(sv.getChildAt(0) instanceof LinearLayout)) return;
        final LinearLayout col = (LinearLayout) sv.getChildAt(0);
        final String[][] grupos = {
                {"OPERACIÓN", "SINCRONIZACIÓN"},
                {"ADMINISTRACIÓN", "ADMINISTRACIÓN"},
                {"DATOS", "MANTENIMIENTO", "RENDIMIENTO", "ADMINISTRAR DATOS LOCALES"},
                {"AVANZADO", "COBERTURAS", "PROPUESTAS", "MEMBRESÍAS", "VINCULAR DISPOSITIVO"}};
        java.util.List<View> hijos = new java.util.ArrayList<>();
        for (int i = 0; i < col.getChildCount(); i++) hijos.add(col.getChildAt(i));
        // trozos: cada encabezado de sección arrastra los hijos que le siguen hasta el próximo encabezado
        java.util.LinkedHashMap<String, java.util.List<View>> trozos = new java.util.LinkedHashMap<>();
        java.util.List<View> cabeza = new java.util.ArrayList<>();
        java.util.List<View> actual = cabeza;
        for (View v : hijos) {
            if (v instanceof TextView && ((TextView) v).getId() == View.NO_ID) {
                String t = ((TextView) v).getText().toString();
                boolean esEncabezado = false;
                for (String[] g : grupos) for (int k = 1; k < g.length; k++) if (g[k].equals(t)) esEncabezado = true;
                if (esEncabezado) { actual = new java.util.ArrayList<>(); trozos.put(t, actual); }
            }
            actual.add(v);
        }
        col.removeAllViews();
        for (View v : cabeza) col.addView(v);   // «SISTEMA» y la versión quedan arriba
        for (final String[] g : grupos) {
            final LinearLayout cuerpo = new LinearLayout(this);
            cuerpo.setOrientation(LinearLayout.VERTICAL);
            if ("OPERACIÓN".equals(g[0])) cuerpo.addView(tarjetaPreferencias());
            for (int k = 1; k < g.length; k++) {
                java.util.List<View> l = trozos.get(g[k]);
                if (l != null) for (View v : l) cuerpo.addView(v);
            }
            if (cuerpo.getChildCount() == 0) continue;
            final boolean abierto = "OPERACIÓN".equals(g[0]);   // como la maqueta: solo Operación abierta; el resto se despliega al tocar
            // Tarjeta de sección como la maqueta: ícono, título en negrita y chevron; se pliega al tocarla
            final LinearLayout cab = new LinearLayout(this);
            cab.setOrientation(LinearLayout.HORIZONTAL);
            cab.setGravity(android.view.Gravity.CENTER_VERTICAL);
            cab.setBackgroundResource(R.drawable.bg_card_f5);
            cab.setPadding(dpPx(16), dpPx(14), dpPx(16), dpPx(14));
            android.widget.ImageView ic = new android.widget.ImageView(this);
            int[] ics = {R.drawable.m_gear, R.drawable.m_person, R.drawable.m_db, R.drawable.m_wrench};
            ic.setImageResource(ics[java.util.Arrays.asList("OPERACIÓN", "ADMINISTRACIÓN", "DATOS", "AVANZADO").indexOf(g[0])]);
            ic.setColorFilter(0xFF0A0A0A);
            cab.addView(ic, new LinearLayout.LayoutParams(dpPx(24), dpPx(24)));
            final TextView tit = new TextView(this);
            tit.setText(g[0].substring(0, 1) + g[0].substring(1).toLowerCase(java.util.Locale.ROOT).replace("ministración", "min"));
            tit.setTextColor(0xFF0A0A0A);
            tit.setTextSize(17f);
            tit.setTypeface(null, android.graphics.Typeface.BOLD);
            tit.setPadding(dpPx(14), 0, 0, 0);
            cab.addView(tit, new LinearLayout.LayoutParams(0, -2, 1f));
            final TextView chev = new TextView(this);
            chev.setTextColor(0xFF62666A);
            chev.setTextSize(18f);
            chev.setText(abierto ? "⌃" : "⌄");
            cab.addView(chev);
            cuerpo.setVisibility(abierto ? View.VISIBLE : View.GONE);
            cab.setOnClickListener(v -> {
                boolean ver = cuerpo.getVisibility() != View.VISIBLE;
                cuerpo.setVisibility(ver ? View.VISIBLE : View.GONE);
                chev.setText(ver ? "⌃" : "⌄");
            });
            LinearLayout.LayoutParams lcab = new LinearLayout.LayoutParams(-1, -2);
            lcab.topMargin = dpPx(12);
            lcab.bottomMargin = dpPx(4);
            col.addView(cab, lcab);
            col.addView(cuerpo);
        }
    }

    /** Preferencias de la maqueta con estado REAL: permisos del sistema y protección offline (esta última no se puede apagar). */
    private View tarjetaPreferencias() {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card_f5);
        card.setPadding(dpPx(16), dpPx(8), dpPx(16), dpPx(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dpPx(12);
        card.setLayoutParams(lp);
        boolean notif = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled();
        boolean gps = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                || androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        boolean cam = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        card.addView(filaPref("Notificaciones", "Se cambian en los ajustes del sistema", notif, true, v ->
                startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()))));
        card.addView(filaPref("GPS y mapas", gps ? "Ubicación permitida" : "Ubicación no permitida · el mapa abre igual con la dirección", gps, true, v -> abrirAjustesApp()));
        card.addView(filaPref("Captura de fotos", cam ? "Cámara permitida" : "Cámara no permitida · la galería sigue disponible", cam, true, v -> abrirAjustesApp()));
        // El conteo consulta Room: se calcula fuera del hilo principal (antes devolvía el valor de seguridad 2147483647)
        final View filaOffline = filaPref("Guardado offline (8 seg)", "Siempre activo", true, false, null);
        card.addView(filaOffline);
        final TextView detalleOffline = (TextView) ((LinearLayout) ((LinearLayout) filaOffline).getChildAt(0)).getChildAt(1);
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            final int pend = PrivacidadLocal.pendientesSinSubir(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || pend == Integer.MAX_VALUE) return;   // sin dato fiable no se muestra una cifra
                detalleOffline.setText(pend == 0 ? "Siempre activo · sin pendientes de subir" : "Siempre activo · " + pend + " pendiente(s) de subir");
            });
        });
        return card;
    }

    private void abrirAjustesApp() {
        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + getPackageName())));
    }

    private View filaPref(String titulo, String detalle, boolean activo, boolean editable, View.OnClickListener ir) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setGravity(android.view.Gravity.CENTER_VERTICAL);
        f.setPadding(0, dpPx(10), 0, dpPx(10));
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        TextView a = new TextView(this);
        a.setText(titulo); a.setTextSize(16f); a.setTextColor(0xFF0A0A0A);
        TextView b = new TextView(this);
        b.setText(detalle); b.setTextSize(12f); b.setTextColor(0xFF62666A);
        t.addView(a); t.addView(b);
        f.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(activo);
        sw.setThumbTintList(android.content.res.ColorStateList.valueOf(activo ? 0xFFE91605 : 0xFF9A9DA0));
        sw.setTrackTintList(android.content.res.ColorStateList.valueOf(activo ? 0x66E91605 : 0x55999999));
        if (editable) {
            sw.setClickable(false);   // el cambio real se hace en los ajustes del sistema
            f.setOnClickListener(ir);
        } else {
            sw.setEnabled(false);
        }
        f.addView(sw);
        return f;
    }

    private android.widget.ScrollView encontrarScroll(View v) {
        if (v instanceof android.widget.ScrollView) return (android.widget.ScrollView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.widget.ScrollView r = encontrarScroll(g.getChildAt(i));
                if (r != null) return r;
            }
        }
        return null;
    }

    private int dpPx(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
