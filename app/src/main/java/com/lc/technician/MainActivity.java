package com.lc.technician;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.data.JobRepository;
import com.lc.technician.model.Job;
import com.lc.technician.sync.SupabaseSyncBridge;
import com.lc.technician.ui.adapter.JobAdapter;
import com.lc.technician.util.NotificationHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String SORT_HORA     = "hora";
    private static final String SORT_CLIENTE  = "cliente";
    private static final String SORT_ESTADO   = "estado";

    private JobAdapter adapter;
    private final List<Job> allJobs      = new ArrayList<>();
    private final List<Job> displayedJobs = new ArrayList<>();

    private TextView contador;
    private EditText etBusqueda;
    private ImageView btnLimpiarBusqueda;
    private LinearLayout layoutChips;
    private TextView tvSinResultados;

    private LinearLayout layoutJobActivo;
    private TextView tvActivoHora, tvActivoCliente, tvActivoDireccion, tvActivoDescripcion;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;

    private String filtroEstado         = "TODOS";
    private String ordenActual          = SORT_HORA;
    private boolean filtroMisJobs       = false;  // true = show only my jobs + unassigned
    private boolean filtroMisJobsInitialized = false;

    private static final String[] ESTADOS_CHIPS = {
        "TODOS", Job.STATUS_PENDING, Job.STATUS_EN_ROUTE,
        Job.STATUS_ARRIVED, Job.STATUS_EN_REPARACION,
        Job.STATUS_ON_HOLD, Job.STATUS_DONE
    };

    private static final String[] LABELS_CHIPS = {
        "Todos", "Pendiente", "En ruta",
        "En sitio", "En reparación",
        "Pausado", "Completado"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // FASE 48: Onboarding check — first run redirect
        SharedPreferences onboardingPrefs = getSharedPreferences("lc_prefs", MODE_PRIVATE);
        if (!onboardingPrefs.getBoolean("onboarding_done", false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(LoginActivity.PREFS, MODE_PRIVATE);
        if (!prefs.getBoolean(LoginActivity.KEY_SESSION, false)) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        // Preserve LAB HTTP override if set; only clear stale Flask IPs when no explicit lab_http_url
        android.content.SharedPreferences cloudPrefs =
                getSharedPreferences("lc_sync_prefs", MODE_PRIVATE);
        String savedSyncUrl = cloudPrefs.getString("sync_base_url", "");
        boolean hasLabOverride = com.lc.technician.sync.LabSession.urlServidor(this)
                .startsWith("http://");
        if (!hasLabOverride && (savedSyncUrl.contains("10.0.0.") || savedSyncUrl.contains("127.0.0.1")
                || savedSyncUrl.contains("192.168."))) {
            cloudPrefs.edit().remove("sync_base_url").apply();
            android.util.Log.i("MainActivity", "cleared stale Flask URL: " + savedSyncUrl);
        }

        // Edge-to-edge (Android 15+): cabecera bajo la barra de estado, SOLICITAR SERVICIO sobre la navegación
        InsetsHelper.marginTop(findViewById(R.id.header));
        InsetsHelper.padBottom(findViewById(R.id.bottomBar));

        // FASE 22: Notificaciones locales
        NotificationHelper.ensureChannels(this);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 991);
            }
        }

        contador           = findViewById(R.id.contador);
        etBusqueda         = findViewById(R.id.etBusqueda);
        btnLimpiarBusqueda = findViewById(R.id.btnLimpiarBusqueda);
        layoutChips        = findViewById(R.id.layoutChips);
        tvSinResultados    = findViewById(R.id.tvSinResultados);

        layoutJobActivo    = findViewById(R.id.layoutJobActivo);
        tvActivoHora       = findViewById(R.id.tvActivoHora);
        tvActivoCliente    = findViewById(R.id.tvActivoCliente);
        tvActivoDireccion  = findViewById(R.id.tvActivoDireccion);
        tvActivoDescripcion = findViewById(R.id.tvActivoDescripcion);

        RecyclerView lista = findViewById(R.id.listaTrabajos);
        lista.setLayoutManager(new LinearLayoutManager(this));
        lista.setHasFixedSize(true);
        lista.setItemViewCacheSize(20);

        runOneTimeSeeds(prefs);

        buildChips();

        etBusqueda.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                btnLimpiarBusqueda.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                aplicarFiltros();
                actualizarJobActivo();
            }
        });
        // Al abrirse o cerrarse el teclado se reevalúa la tarjeta EN CURSO
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.listaTrabajos),
                (v, insets) -> { v.post(this::actualizarJobActivo); return insets; });

        btnLimpiarBusqueda.setOnClickListener(v -> {
            etBusqueda.setText("");
            btnLimpiarBusqueda.setVisibility(View.GONE);
        });

        findViewById(R.id.btnOrdenar).setOnClickListener(v -> mostrarDialogoOrden());

        findViewById(R.id.btnSolicitarServicio).setOnClickListener(v ->
                startActivity(new Intent(this, RequestServiceActivity.class)));

        findViewById(R.id.btnPerfil).setOnClickListener(v ->
                startActivity(new Intent(this, ProfileActivity.class)));

        adapter = new JobAdapter(displayedJobs, job -> {
            Intent i = new Intent(MainActivity.this, JobDetailActivity.class);
            i.putExtra("job_id", job.id);
            startActivity(i);
        });
        lista.setAdapter(adapter);

        swipeRefresh = findViewById(R.id.swipeRefresh);
        if (swipeRefresh != null) {
            swipeRefresh.setColorSchemeColors(0xFFEB1700);
            swipeRefresh.setOnRefreshListener(this::sincronizarConSwipe);
        }

        // FASE 25: Programar chequeo diario de membresias
        try {
            androidx.work.PeriodicWorkRequest expiryWork =
                    new androidx.work.PeriodicWorkRequest.Builder(
                            com.lc.technician.worker.MembershipExpiryWorker.class,
                            1, java.util.concurrent.TimeUnit.DAYS)
                            .build();
            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    "membership_expiry_check",
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    expiryWork);
        } catch (Exception ignored) {}

        // FASE 43: Silent auto sync every 15 min
        try {
            androidx.work.Constraints networkConstraint = new androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build();

            androidx.work.PeriodicWorkRequest autoSyncReq =
                    new androidx.work.PeriodicWorkRequest.Builder(
                            com.lc.technician.worker.SyncWorker.class,
                            15, java.util.concurrent.TimeUnit.MINUTES)
                    .setConstraints(networkConstraint)
                    .setInputData(new androidx.work.Data.Builder()
                            .putBoolean("silent", true).build())
                    .build();
            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    "auto_sync_silent",
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    autoSyncReq);

            // Fase 44: Notification polling every 15 minutes
            androidx.work.PeriodicWorkRequest pollReq =
                    new androidx.work.PeriodicWorkRequest.Builder(
                            com.lc.technician.worker.NotificationPollWorker.class,
                            15, java.util.concurrent.TimeUnit.MINUTES)
                    .setConstraints(new androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build())
                    .build();
            androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    com.lc.technician.worker.NotificationPollWorker.TAG,
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    pollReq);

            // Sync on app open if >5 min since last sync
            SharedPreferences syncPrefs = getSharedPreferences("lc_prefs", MODE_PRIVATE);
            long lastSync = syncPrefs.getLong("last_sync_time", 0);
            if (System.currentTimeMillis() - lastSync > 5 * 60 * 1000L) {
                androidx.work.OneTimeWorkRequest quickSync =
                        new androidx.work.OneTimeWorkRequest.Builder(
                                com.lc.technician.worker.SyncWorker.class)
                        .setConstraints(networkConstraint)
                        .setInputData(new androidx.work.Data.Builder()
                                .putBoolean("silent", true).build())
                        .build();
                // única con KEEP: abrir la app varias veces no apila sincronizaciones
                androidx.work.WorkManager.getInstance(this).enqueueUniqueWork("sync_inmediata",
                        androidx.work.ExistingWorkPolicy.KEEP, quickSync);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onPause() {
        super.onPause();
        JobRepository.get().setOyenteCache(null);
        SupabaseSyncBridge.get(this).stopRealtime();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // lo que traiga la sincronización (órdenes nuevas o cambiadas) aparece sin salir y volver a entrar
        JobRepository.get().setOyenteCache(this::recargarJobs);
        recargarJobs();
        // Sync v2: al volver a la app se pide lo nuevo (p. ej. órdenes asignadas por despacho), con límite de 30 s
        if (com.lc.technician.sync.LabSession.activa(this)) {
            long ultima = getSharedPreferences("lc_prefs", MODE_PRIVATE).getLong("last_sync_time", 0);
            if (System.currentTimeMillis() - ultima > 30_000L) {
                JobRepository.get().solicitarSyncInmediata();
                // la caché se recarga al terminar el worker; al volver a esta pantalla se ve
            }
            // Inicia suscripción Realtime para recibir cambios de Control y Customer
            SupabaseSyncBridge.get(this).startRealtime(null);
        }
    }

    private void recargarJobs() {
        allJobs.clear();
        allJobs.addAll(JobRepository.get().getTodayJobs());
        // Default to "Mis Jobs" for TECNICO role
        String role = SessionManager.get(this).getRole();
        if (("TECNICO".equals(role) || "TECHNICIAN".equals(role)) && !filtroMisJobsInitialized) {
            filtroMisJobs = true;
            filtroMisJobsInitialized = true;
            buildChips();  // antes el chip seguía diciendo "Todos" con el filtro "Mis Jobs" activo
        }
        // Pasar id del técnico actual al adapter para que muestre el badge "OTRO TÉCNICO"
        String labId = com.lc.technician.sync.LabSession.technicianId(this);
        adapter.setCurrentTechId(labId != null ? labId : SessionManager.get(this).getTechnicianName());
        aplicarFiltros();
        actualizarContador();
        actualizarJobActivo();
    }

    private void actualizarJobActivo() {
        // Mientras se busca, la tarjeta fija dejaría sin sitio a los resultados (sobre todo con teclado)
        androidx.core.view.WindowInsetsCompat ins = androidx.core.view.ViewCompat.getRootWindowInsets(etBusqueda);
        boolean teclado = ins != null && ins.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
        if ((etBusqueda.hasFocus() && teclado) || etBusqueda.getText().toString().trim().length() > 0) {
            layoutJobActivo.setVisibility(View.GONE);
            return;
        }
        Job activo = null;
        String myTechId = SessionManager.get(this).getTechnicianName();
        for (Job j : allJobs) {
            // la tarjeta del trabajo activo: TÉCNICO siempre filtra por su ID
            boolean esTec = "TECNICO".equals(SessionManager.get(this).getRole()) || "TECHNICIAN".equals(SessionManager.get(this).getRole());
            if ((filtroMisJobs || esTec) && !pasaFiltroMisJobs(j, myTechId)) continue;
            if (Job.STATUS_EN_ROUTE.equals(j.estado)
                    || Job.STATUS_ARRIVED.equals(j.estado)
                    || Job.STATUS_EN_REPARACION.equals(j.estado)) {
                activo = j;
                break;
            }
        }
        if (activo == null) {
            layoutJobActivo.setVisibility(View.GONE);
            return;
        }
        final Job jobActivo = activo;
        tvActivoHora.setText(jobActivo.hora != null ? jobActivo.hora : "");
        tvActivoCliente.setText(jobActivo.cliente != null ? jobActivo.cliente : "");
        tvActivoDireccion.setText(jobActivo.direccion != null ? jobActivo.direccion : "");
        tvActivoDescripcion.setText(jobActivo.descripcion != null ? jobActivo.descripcion : "");
        layoutJobActivo.setVisibility(View.VISIBLE);
        layoutJobActivo.setOnClickListener(v -> {
            Intent i = new Intent(MainActivity.this, JobDetailActivity.class);
            i.putExtra("job_id", jobActivo.id);
            startActivity(i);
        });
    }

    private void aplicarFiltros() {
        String query = etBusqueda.getText().toString().trim().toLowerCase();
        String myTechId = SessionManager.get(this).getTechnicianName();
        // TÉCNICO: siempre filtrar por su propio ID aunque el chip esté en "Todos"
        boolean esTecnico = "TECNICO".equals(SessionManager.get(this).getRole()) || "TECHNICIAN".equals(SessionManager.get(this).getRole());

        displayedJobs.clear();
        for (Job j : allJobs) {
            if (!pasaFiltroEstado(j)) continue;
            if ((filtroMisJobs || esTecnico) && !pasaFiltroMisJobs(j, myTechId)) continue;
            if (!query.isEmpty() && !pasaBusqueda(j, query)) continue;
            displayedJobs.add(j);
        }

        ordenar(displayedJobs);

        adapter.notifyDataSetChanged();
        tvSinResultados.setVisibility(displayedJobs.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Returns true if job belongs to this tech OR is unassigned. */
    private boolean pasaFiltroMisJobs(Job j, String myTechId) {
        if (j.technicianId == null || j.technicianId.isEmpty()) return true;  // unassigned
        // Con sesión de laboratorio manda el técnico de la sesión: en un teléfono compartido, el perfil local
        // (SessionManager) puede ser de otro técnico y sus órdenes no deben aparecer en "Mis Jobs".
        String labId = com.lc.technician.sync.LabSession.technicianId(this);
        if (labId != null) return j.technicianId.equals(labId);
        return j.technicianId.equals(myTechId);
    }

    private boolean pasaFiltroEstado(Job j) {
        if ("TODOS".equals(filtroEstado)) return true;
        return filtroEstado.equals(j.estado);
    }

    private boolean pasaBusqueda(Job j, String q) {
        return contains(j.cliente, q)
            || contains(j.direccion, q)
            || contains(j.descripcion, q)
            || contains(j.equipo, q)
            || contains(j.hora, q);
    }

    private boolean contains(String field, String q) {
        return field != null && field.toLowerCase().contains(q);
    }

    private void ordenar(List<Job> list) {
        switch (ordenActual) {
            case SORT_CLIENTE:
                Collections.sort(list, (a, b) -> {
                    String ca = a.cliente != null ? a.cliente : "";
                    String cb = b.cliente != null ? b.cliente : "";
                    return ca.compareToIgnoreCase(cb);
                });
                break;
            case SORT_ESTADO:
                Collections.sort(list, (a, b) -> estadoOrder(a.estado) - estadoOrder(b.estado));
                break;
            default: // SORT_HORA
                Collections.sort(list, (a, b) -> horaAMinutos(a.hora) - horaAMinutos(b.hora));
        }
    }

    private int horaAMinutos(String hora) {
        if (hora == null || hora.isEmpty()) return Integer.MAX_VALUE;
        try {
            String h = hora.trim().toUpperCase();
            boolean pm = h.contains("PM");
            h = h.replace("AM", "").replace("PM", "").trim();
            String[] parts = h.split(":");
            int hh = Integer.parseInt(parts[0].trim());
            int mm = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            if (pm && hh != 12) hh += 12;
            if (!pm && hh == 12) hh = 0;
            return hh * 60 + mm;
        } catch (Exception e) { return Integer.MAX_VALUE; }
    }

    private int estadoOrder(String estado) {
        if (estado == null) return 99;
        switch (estado) {
            case Job.STATUS_EN_REPARACION:     return 0;
            case Job.STATUS_ARRIVED:           return 1;
            case Job.STATUS_EN_ROUTE:          return 2;
            case Job.STATUS_PENDING:           return 3;
            case Job.STATUS_AWAITING_APPROVAL: return 4;
            case Job.STATUS_ON_HOLD:           return 5;
            case Job.STATUS_PART_HOLD:         return 6;
            case Job.STATUS_DONE:              return 7;
            default:                           return 99;
        }
    }

    private void mostrarDialogoOrden() {
        String[] opciones = {"Por hora", "Por cliente (A–Z)", "Por estado"};
        int current = ordenActual.equals(SORT_HORA) ? 0 : ordenActual.equals(SORT_CLIENTE) ? 1 : 2;
        new AlertDialog.Builder(this)
                .setTitle("Ordenar por")
                .setSingleChoiceItems(opciones, current, (dialog, which) -> {
                    ordenActual = which == 0 ? SORT_HORA : which == 1 ? SORT_CLIENTE : SORT_ESTADO;
                    aplicarFiltros();
                    dialog.dismiss();
                })
                .show();
    }

    private void buildChips() {
        layoutChips.removeAllViews();
        int dp6 = dp(6); int dp16 = dp(16); int dp32 = dp(32); int dp36 = dp(36);

        // "Mis Jobs" toggle chip — only visible for TECNICO role or when ADMIN/DISPATCHER choose
        String role = SessionManager.get(this).getRole();
        boolean mostrarToggle = true; // show for all roles
        if (mostrarToggle) {
            TextView chipMis = new TextView(this);
            chipMis.setText(filtroMisJobs ? "Mis Jobs" : "Todos");
            chipMis.setTextSize(13f);
            chipMis.setGravity(android.view.Gravity.CENTER);
            chipMis.setPadding(dp16, dp6, dp16, dp6);
            chipMis.setSingleLine(true);
            LinearLayout.LayoutParams lpMis = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lpMis.setMarginEnd(dp(12));
            chipMis.setLayoutParams(lpMis);
            actualizarChipMisJobs(chipMis, filtroMisJobs);
            chipMis.setOnClickListener(v -> {
                filtroMisJobs = !filtroMisJobs;
                chipMis.setText(filtroMisJobs ? "Mis Jobs" : "Todos");
                actualizarChipMisJobs(chipMis, filtroMisJobs);
                aplicarFiltros();
            });
            layoutChips.addView(chipMis);

            // Separator view
            View sep = new View(this);
            LinearLayout.LayoutParams lpSep = new LinearLayout.LayoutParams(1, dp(20));
            lpSep.setMarginEnd(dp(12));
            lpSep.gravity = android.view.Gravity.CENTER_VERTICAL;
            sep.setLayoutParams(lpSep);
            sep.setBackgroundColor(0xFFE4E4E4);
            layoutChips.addView(sep);
        }

        for (int i = 0; i < ESTADOS_CHIPS.length; i++) {
            final String estado = ESTADOS_CHIPS[i];
            TextView chip = new TextView(this);
            chip.setText(LABELS_CHIPS[i]);
            chip.setTextSize(13f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp16, dp6, dp16, dp6);
            chip.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            chip.setLayoutParams(lp);
            chip.setTag("estado_chip");
            actualizarChip(chip, estado.equals(filtroEstado));
            chip.setOnClickListener(v -> {
                filtroEstado = estado;
                // Re-render only the estado chips (skip misJobs toggle + separator at start)
                int estadoIdx = 0;
                for (int j = 0; j < layoutChips.getChildCount(); j++) {
                    View c = layoutChips.getChildAt(j);
                    if (c instanceof TextView && estadoIdx < ESTADOS_CHIPS.length) {
                        String chipTag = (String) c.getTag();
                        if ("estado_chip".equals(chipTag)) {
                            actualizarChip((TextView) c, ESTADOS_CHIPS[estadoIdx].equals(filtroEstado));
                            estadoIdx++;
                        }
                    }
                }
                aplicarFiltros();
            });
            layoutChips.addView(chip);
        }
    }

    private void actualizarChip(TextView chip, boolean activo) {
        chip.setBackground(getDrawable(activo ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive));
        chip.setTextColor(activo ? 0xFF191919 : 0xFF767676);
        chip.setTypeface(null, activo ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void actualizarChipMisJobs(TextView chip, boolean activo) {
        // Use a distinct color for the mis-jobs toggle
        chip.setBackground(getDrawable(activo ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive));
        chip.setTextColor(activo ? 0xFF1B71C9 : 0xFF767676);
        chip.setTypeface(null, activo ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void actualizarContador() {
        int pendientes = 0, completados = 0;
        for (Job j : allJobs) {
            if (Job.STATUS_DONE.equals(j.estado)) completados++;
            else pendientes++;
        }
        contador.setText(pendientes + " pendientes · " + completados + " completados");
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void sincronizarConSwipe() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo net = cm != null ? cm.getActiveNetworkInfo() : null;
        if (net == null || !net.isConnected()) {
            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            android.widget.Toast.makeText(this,
                    "Sin conexión · cambios en cola", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        androidx.work.OneTimeWorkRequest req = new androidx.work.OneTimeWorkRequest.Builder(
                com.lc.technician.worker.SyncWorker.class).build();
        androidx.work.WorkManager.getInstance(this).enqueueUniqueWork(
                "swipe_sync", androidx.work.ExistingWorkPolicy.KEEP, req);
        androidx.work.WorkManager.getInstance(this)
                .getWorkInfosForUniqueWorkLiveData("swipe_sync")
                .observe(this, infos -> {
                    if (infos == null || infos.isEmpty()) return;
                    androidx.work.WorkInfo info = infos.get(0);
                    if (info.getState().isFinished()) {
                        runOnUiThread(() -> {
                            if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
                            recargarJobs();
                            if (info.getState() == androidx.work.WorkInfo.State.SUCCEEDED) {
                                android.widget.Toast.makeText(this, "✓ Sincronizado",
                                        android.widget.Toast.LENGTH_SHORT).show();
                            } else {
                                android.widget.Toast.makeText(this,
                                        "Sin conexión con el servidor",
                                        android.widget.Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                });
    }

    private void runOneTimeSeeds(SharedPreferences prefs) {
        if (!prefs.getBoolean("historial_limpiado_v1", false)) {
            JobRepository.get().limpiarHistorialDuplicado();
            prefs.edit().putBoolean("historial_limpiado_v1", true).apply();
        }
        JobRepository.get().backfillCustomerIds(n -> {});
        if (!prefs.getBoolean("bfs_imported_v1", false)) {
            JobRepository.get().seedDatosRealesBFS(() -> {
                prefs.edit().putBoolean("bfs_imported_v1", true).apply();
                runOnUiThread(this::recargarJobs);
            });
        }
        if (!prefs.getBoolean("johnstone_imported_v1", false)) {
            JobRepository.get().seedRepuestosDesdeJohnstone(() ->
                prefs.edit().putBoolean("johnstone_imported_v1", true).apply());
        }
        if (!prefs.getBoolean("dedup_jobs_v1", false)) {
            JobRepository.get().dedupJobsV1(() -> {
                prefs.edit().putBoolean("dedup_jobs_v1", true).apply();
                runOnUiThread(this::recargarJobs);
            });
        }
        if (!prefs.getBoolean("bfs_full_v1", false)) {
            JobRepository.get().seedBFSFull(() -> {
                prefs.edit().putBoolean("bfs_full_v1", true).apply();
                runOnUiThread(this::recargarJobs);
            });
        }
        if (!prefs.getBoolean("workitems_bfs_v1", false)) {
            JobRepository.get().seedWorkItemsBFS(() ->
                prefs.edit().putBoolean("workitems_bfs_v1", true).apply());
        }
        if (!prefs.getBoolean("bfs_fotos_v1", false)) {
            JobRepository.get().seedEquiposDesdeFotos(() ->
                JobRepository.get().seedCustomersDesdeFotos(() ->
                    JobRepository.get().seedRepuestosDesdeFotos(() ->
                        prefs.edit().putBoolean("bfs_fotos_v1", true).apply())));
        }
    }
}
