package com.lc.technician;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.lc.technician.data.JobRepository;
import com.lc.technician.model.Job;
import com.lc.technician.model.Technician;
import java.util.Calendar;
import java.util.List;

public class ProfileActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);
        BottomNav.attach(this, BottomNav.PERFIL);
        // Engranaje de la maqueta: abre Ajustes
        android.widget.LinearLayout hdr = findViewById(R.id.header);
        if (hdr != null) {
            android.widget.ImageView gear = new android.widget.ImageView(this);
            gear.setImageResource(R.drawable.m_gear);
            gear.setColorFilter(0xFF62666A);
            int pad = Math.round(10 * getResources().getDisplayMetrics().density);
            gear.setPadding(pad, pad, pad, pad);
            gear.setOnClickListener(v -> startActivity(new Intent(this, ConfiguracionActivity.class)));
            hdr.addView(gear, new android.widget.LinearLayout.LayoutParams(Math.round(48 * getResources().getDisplayMetrics().density), Math.round(48 * getResources().getDisplayMetrics().density)));
            // El título empieza a 14 dp del borde (8 de la cabecera + 6 de su margen): el engranaje queda a esos mismos 14 dp
            // (48 dp de botón con 10 dp de aire interno → la cabecera deja 4 dp a la derecha).
            hdr.setPaddingRelative(hdr.getPaddingStart(), hdr.getPaddingTop(), Math.round(4 * getResources().getDisplayMetrics().density), hdr.getPaddingBottom());
        }
        View atrasPerfil = findViewById(R.id.btnAtras);
        if (atrasPerfil != null) atrasPerfil.setVisibility(View.GONE);   // la barra inferior reemplaza la flecha
        // Edge-to-edge (Android 15+): cabecera bajo la barra de estado (el inferior ya lo cubre marginBottom=56dp)
        InsetsHelper.padTop(findViewById(R.id.header));

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        SharedPreferences prefs = getSharedPreferences(LoginActivity.PREFS, MODE_PRIVATE);
        String rol  = prefs.getString(LoginActivity.KEY_ROLE, "TECNICO");
        String name = prefs.getString(LoginActivity.KEY_NAME, Technician.NOMBRE);

        ((TextView) findViewById(R.id.tvNombre)).setText(name);
        // Avatar: inicial del usuario (profiles no tiene avatar_url todavía: cuando exista se mostrará la foto)
        TextView avatar = findViewById(R.id.avatar);
        if (avatar != null && name != null && !name.trim().isEmpty()) avatar.setText(name.trim().substring(0, 1).toUpperCase());
        final TextView tvSub = (TextView) findViewById(R.id.tvSubtitulo);
        if ("TECNICO".equals(rol)) {
            // «Técnico HVAC»: la especialidad viene del perfil (profiles.specialties); sin dato, «HVAC» como respaldo
            final String claveEsp = "esp_" + name;
            String esp = getSharedPreferences("lc_perfil_especialidad", MODE_PRIVATE).getString(claveEsp, null);
            tvSub.setText("Técnico " + (esp != null ? esp : "HVAC"));
            cargarEspecialidad(tvSub, claveEsp);
        } else {
            tvSub.setText("Técnico desde " + Technician.FECHA_INGRESO);
        }

        TextView tvRol = findViewById(R.id.tvRol);
        tvRol.setText(labelForRole(rol));
        tvRol.setVisibility(View.VISIBLE);

        configurarMenu(prefs, rol);
        View menuTitulo = encontrarTexto(findViewById(R.id.scrollPerfil), "MENÚ");
        if (menuTitulo != null) menuTitulo.setVisibility(View.GONE);
    }

    private static View encontrarTexto(View raiz, String texto) {
        if (raiz instanceof TextView && texto.contentEquals(((TextView) raiz).getText())) return raiz;
        if (raiz instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) raiz;
            for (int i = 0; i < g.getChildCount(); i++) { View r = encontrarTexto(g.getChildAt(i), texto); if (r != null) return r; }
        }
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        calcularEstadisticas();
    }

    private void calcularEstadisticas() {
        List<Job> jobs = JobRepository.get().getTodayJobs();

        Calendar ahora = Calendar.getInstance();
        int hoyAnio   = ahora.get(Calendar.YEAR);
        int hoySemana = ahora.get(Calendar.WEEK_OF_YEAR);
        int hoyDia    = ahora.get(Calendar.DAY_OF_YEAR);

        int completadosHoy = 0, completadosSemana = 0, totalCompletados = 0;

        for (Job j : jobs) {
            if (!Job.esCompletado(j.estado)) continue;
            totalCompletados++;
            if (j.updatedAt != null) {
                Calendar c = Calendar.getInstance();
                c.setTimeInMillis(j.updatedAt);
                if (c.get(Calendar.YEAR) == hoyAnio && c.get(Calendar.DAY_OF_YEAR) == hoyDia)
                    completadosHoy++;
                if (c.get(Calendar.YEAR) == hoyAnio && c.get(Calendar.WEEK_OF_YEAR) == hoySemana)
                    completadosSemana++;
            }
        }

        int enCurso = 0;
        for (Job j : jobs) {
            if (Job.esEnCurso(j.estado)) enCurso++;
        }
        ((TextView) findViewById(R.id.statHoy)).setText(String.valueOf(jobs.size()));
        ((TextView) findViewById(R.id.statSemana)).setText(String.valueOf(totalCompletados));
        ((TextView) findViewById(R.id.statCompletados)).setText(String.valueOf(enCurso));
        colorearKpi(R.id.statSemana, 0xFF16A34A);     // Completados: verde
        colorearKpi(R.id.statCompletados, 0xFFF59E0B); // En curso: naranja
    }

    /** Texto de una especialidad del perfil («HVAC», «PLOMERIA»…); varias separadas por coma se unen con « · ». */
    private static String textoEspecialidad(Object valor) {
        java.util.List<String> partes = new java.util.ArrayList<>();
        if (valor instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) valor;
            for (int i = 0; i < a.length(); i++) partes.add(a.optString(i));
        } else if (valor instanceof String) {
            for (String s : ((String) valor).split("[,;|]")) partes.add(s);
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : partes) {
            String t = s.trim();
            if (t.isEmpty() || out.size() >= 2) continue;
            switch (t.toUpperCase(java.util.Locale.ROOT)) {
                case "HVAC": out.add("HVAC"); break;
                case "PLOMERIA": out.add("Plomería"); break;
                case "ELECTRICIDAD": out.add("Electricidad"); break;
                case "ELECTRODOMESTICOS": out.add("Electrodomésticos"); break;
                default: out.add(t.substring(0, 1).toUpperCase() + t.substring(1).toLowerCase(java.util.Locale.ROOT));
            }
        }
        return out.isEmpty() ? null : android.text.TextUtils.join(" · ", out);
    }

    private void cargarEspecialidad(final TextView destino, final String clave) {
        if (!com.lc.technician.BuildConfig.CLOUD_LOGIN) return;
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            try {
                com.lc.technician.sync.SupabaseClient sb = com.lc.technician.sync.SupabaseClient.get(this);
                if (!sb.hasUserSession()) return;
                retrofit2.Response<okhttp3.ResponseBody> r = sb.api().miPerfil(sb.authHeader(), "specialties", 1).execute();
                if (!r.isSuccessful() || r.body() == null) return;
                org.json.JSONArray a = new org.json.JSONArray(r.body().string());
                if (a.length() == 0) return;
                final String esp = textoEspecialidad(a.getJSONObject(0).opt("specialties"));
                if (esp == null) return;
                getSharedPreferences("lc_perfil_especialidad", MODE_PRIVATE).edit().putString(clave, esp).apply();
                runOnUiThread(() -> { if (!isFinishing()) destino.setText("Técnico " + esp); });
            } catch (Exception ignored) { }
        });
    }

    private void colorearKpi(int idNumero, int color) {
        TextView num = findViewById(idNumero);
        if (num == null) return;
        num.setTextColor(color);
        if (num.getParent() instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) num.getParent();
            for (int i = 0; i < g.getChildCount(); i++) if (g.getChildAt(i) != num && g.getChildAt(i) instanceof TextView) ((TextView) g.getChildAt(i)).setTextColor(color);
        }
    }

    private static int posicionConTag(android.view.ViewGroup box, String tag) {
        for (int i = 0; i < box.getChildCount(); i++) if (tag.equals(box.getChildAt(i).getTag())) return i;
        return -1;
    }

    /**
     * El menú visible tiene exactamente las cinco entradas de la maqueta. Las demás funciones (completados, repuestos, equipos,
     * favoritos, solicitudes de repuestos, reportes, dispatch…) siguen existiendo y se abren desde «Documentos».
     */
    private void agruparOpcionesExtra(android.view.ViewGroup box) {
        int a = posicionConTag(box, "docs"), b = posicionConTag(box, "notif");
        if (a < 0 || b <= a + 1) return;
        final java.util.List<View> extras = new java.util.ArrayList<>();
        for (int i = a + 1; i < b; i++) extras.add(box.getChildAt(i));
        final java.util.List<View> acciones = new java.util.ArrayList<>();
        final java.util.List<String> nombres = new java.util.ArrayList<>();
        for (View v : extras) {
            TextView et = v.findViewById(R.id.menuLabel);
            if (et != null && v.getVisibility() == View.VISIBLE) { acciones.add(v); nombres.add(et.getText().toString()); }
            v.setVisibility(View.GONE);   // fuera del menú visible
        }
        box.findViewWithTag("docs").setOnClickListener(v -> {
            if (acciones.isEmpty()) {
                new android.app.AlertDialog.Builder(this).setTitle("Documentos")
                        .setMessage("Aquí verás tus documentos (licencia, seguro, certificaciones).")
                        .setPositiveButton("Entendido", null).show();
                return;
            }
            new android.app.AlertDialog.Builder(this).setTitle("Documentos")
                    .setItems(nombres.toArray(new String[0]), (d, which) -> acciones.get(which).performClick())
                    .setNegativeButton("Cerrar", null).show();
        });
    }

    private void configurarMenu(SharedPreferences prefs, String rol) {
        boolean esTecnico    = "TECNICO".equals(rol);
        boolean esDispatcher = "DISPATCHER".equals(rol);

        setupItemDatos(prefs, rol);
        setupItem(R.id.menuCompletados,    R.drawable.ic_menu_check,     "Mis trabajos completados", JobCompletedActivity.class);
        setupItem(R.id.menuRepuestos,      R.drawable.ic_menu_parts,     "Mis repuestos",            PartsActivity.class);
        setupItem(R.id.menuEquipos,        R.drawable.ic_menu_hvac,      "Mis equipos",              EquipmentActivity.class);
        setupItem(R.id.menuEstadisticas,   R.drawable.ic_menu_bar_chart, "Mis estadísticas",         EstadisticasActivity.class);
        setupItem(R.id.menuReportes,       R.drawable.ic_menu_report,    "Mis reportes",             ReportesActivity.class);
        setupItem(R.id.menuAuditoria,      R.drawable.ic_menu_audit,     "Ver auditoría",            AuditLogActivity.class);
        setupItem(R.id.menuDispatch,       R.drawable.ic_menu_dispatch,  "Dispatch",                 DispatchActivity.class);
        setupItem(R.id.menuPagos,          R.drawable.ic_menu_payment,   "Historial de pagos",       PagosActivity.class);
        setupItem(R.id.menuFavoritos,      R.drawable.ic_star,           "Mis favoritos",            FavoritosActivity.class);
        setupItem(R.id.menuConfiguracion,  R.drawable.ic_menu_settings,  "Configuración",            ConfiguracionActivity.class);
        setupItem(R.id.menuAyuda,          R.drawable.ic_menu_parts,     "Solicitudes de Repuestos", PartsRequestActivity.class);
        setupItem(R.id.menuTecnicos,       R.drawable.ic_menu_dispatch,  "Técnicos",                 TechnicianRosterActivity.class);

        // Visibilidad por rol
        if (esTecnico) {
            setVisible(R.id.menuReportes,   R.id.dividerReportes,   false);
            setVisible(R.id.menuAuditoria,  R.id.dividerAuditoria,  false);
            setVisible(R.id.menuDispatch,   R.id.dividerDispatch,   false);
            setVisible(R.id.menuPagos,      R.id.dividerPagos,      false);
            setVisible(R.id.menuTecnicos,   R.id.dividerTecnicos,   false);
        } else if (esDispatcher) {
            setVisible(R.id.menuAuditoria,  R.id.dividerAuditoria,  false);
            setVisible(R.id.menuTecnicos,   R.id.dividerTecnicos,   false);
        }
        // ADMIN: ve todo incluyendo Técnicos

        // Rutas ya cubiertas por la barra inferior y el engranaje: se ocultan (las pantallas siguen existiendo)
        setVisible(R.id.menuEstadisticas, -1, false);
        setVisible(R.id.menuConfiguracion, -1, false);
        // Notificaciones y Acerca de (reales): ajustes de notificaciones del sistema y versión instalada
        View cerrarRef = findViewById(R.id.menuCerrarSesion);
        if (cerrarRef != null && cerrarRef.getParent() instanceof android.view.ViewGroup) {
            android.view.ViewGroup box = (android.view.ViewGroup) cerrarRef.getParent();
            int pos = box.indexOfChild(cerrarRef);
            View notif = getLayoutInflater().inflate(R.layout.item_menu, box, false);
            notif.setTag("notif");
            ((ImageView) notif.findViewById(R.id.menuIcon)).setImageResource(R.drawable.m_bell);
            ((TextView) notif.findViewById(R.id.menuLabel)).setText("Notificaciones");
            notif.setOnClickListener(v -> startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())));
            View acerca = getLayoutInflater().inflate(R.layout.item_menu, box, false);
            acerca.setTag("acerca");
            ((ImageView) acerca.findViewById(R.id.menuIcon)).setImageResource(R.drawable.ic_info);
            ((TextView) acerca.findViewById(R.id.menuLabel)).setText("Acerca de");
            acerca.setOnClickListener(v -> {
                String ver = "";
                try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
                new android.app.AlertDialog.Builder(this).setTitle("L&C Technician")
                        .setMessage("Versión " + ver + "\nL&C Home Services").setPositiveButton("Cerrar", null).show();
            });
            View soporte = getLayoutInflater().inflate(R.layout.item_menu, box, false);
            ((ImageView) soporte.findViewById(R.id.menuIcon)).setImageResource(R.drawable.ic_help_q);
            ((TextView) soporte.findViewById(R.id.menuLabel)).setText("Soporte");
            soporte.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                    .setTitle("Soporte L&C")
                    .setItems(new String[]{"Llamar · 786-922-7383", "Escribir un correo"}, (d, which) -> {
                        if (which == 0) startActivity(new Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:+17869227383")));
                        else startActivity(new Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:Carlosocampo1034@gmail.com"))
                                .putExtra(Intent.EXTRA_SUBJECT, "Soporte L&C Technician"));
                    })
                    .setNegativeButton("Cerrar", null).show());
            box.addView(notif, pos);
            box.addView(soporte, pos + 1);
            box.addView(acerca, pos + 2);
            // «Documentos» justo bajo «Mi información» (orden de la maqueta)
            View datosItem = findViewById(R.id.menuDatos);
            if (datosItem != null && datosItem.getParent() == box) {
                View docs = getLayoutInflater().inflate(R.layout.item_menu, box, false);
                docs.setTag("docs");
                ((ImageView) docs.findViewById(R.id.menuIcon)).setImageResource(R.drawable.m_doc);
                ((TextView) docs.findViewById(R.id.menuLabel)).setText("Documentos");
                docs.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                        .setTitle("Documentos")
                        .setMessage("Aquí verás tus documentos (licencia, seguro, certificaciones). Esta sección se activa "
                                + "cuando L&C defina qué documentos se suben y dónde se guardan.")
                        .setPositiveButton("Entendido", null).show());
                box.addView(docs, box.indexOfChild(datosItem) + 1);
            }
            agruparOpcionesExtra(box);
        }

        // Cerrar sesión
        View cerrar = findViewById(R.id.menuCerrarSesion);
        ((ImageView) cerrar.findViewById(R.id.menuIcon)).setImageResource(R.drawable.ic_menu_logout);
        TextView tvCerrar = cerrar.findViewById(R.id.menuLabel);
        tvCerrar.setText("Cerrar sesión");
        tvCerrar.setTextColor(0xFFE91605);
        cerrar.setOnClickListener(v -> cerrarSesion());
        // Botón de contorno rojo, como la maqueta
        android.graphics.drawable.GradientDrawable cb = new android.graphics.drawable.GradientDrawable();
        cb.setCornerRadius(Math.round(12 * getResources().getDisplayMetrics().density));
        cb.setStroke(Math.round(1.5f * getResources().getDisplayMetrics().density), 0xFFE91605);
        cb.setColor(0xFFFFFFFF);
        cerrar.setBackground(cb);
        // como la maqueta: ícono y texto juntos y centrados, sin flecha
        if (cerrar instanceof LinearLayout) {
            ((LinearLayout) cerrar).setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams ll = (LinearLayout.LayoutParams) tvCerrar.getLayoutParams();
            if (ll != null) { ll.weight = 0; ll.width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT; tvCerrar.setLayoutParams(ll); }
            View ultimo = ((LinearLayout) cerrar).getChildAt(((LinearLayout) cerrar).getChildCount() - 1);
            if (ultimo != tvCerrar && ultimo != cerrar.findViewById(R.id.menuIcon)) ultimo.setVisibility(View.GONE);
        }
        ((ImageView) cerrar.findViewById(R.id.menuIcon)).setColorFilter(0xFFE91605);
        tvCerrar.setTypeface(null, android.graphics.Typeface.BOLD);
        if (cerrar.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams) {
            android.view.ViewGroup.MarginLayoutParams mp = (android.view.ViewGroup.MarginLayoutParams) cerrar.getLayoutParams();
            int m = Math.round(16 * getResources().getDisplayMetrics().density);
            mp.setMargins(m, m, m, m);
            cerrar.setLayoutParams(mp);
        }
    }

    private void setVisible(int itemId, int dividerId, boolean visible) {
        int vis = visible ? View.VISIBLE : View.GONE;
        View item    = findViewById(itemId);
        View divider = findViewById(dividerId);
        if (item    != null) item.setVisibility(vis);
        if (divider != null) divider.setVisibility(vis);
    }

    private void setupItemDatos(SharedPreferences prefs, String rol) {
        View item = findViewById(R.id.menuDatos);
        ((ImageView) item.findViewById(R.id.menuIcon)).setImageResource(R.drawable.ic_menu_datos);
        ((TextView)  item.findViewById(R.id.menuLabel)).setText("Mi información");
        String name  = prefs.getString(LoginActivity.KEY_NAME, Technician.NOMBRE);
        item.setOnClickListener(v -> {
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            int pad = (int)(20 * getResources().getDisplayMetrics().density);
            layout.setPadding(pad, pad, pad, 0);

            String[] labels = {"Nombre", "Email", "Teléfono", "Rol", "Desde"};
            String[] values = {name, Technician.EMAIL, Technician.TELEFONO, labelForRole(rol), Technician.FECHA_INGRESO};

            for (int i = 0; i < labels.length; i++) {
                TextView tvLabel = new TextView(this);
                tvLabel.setText(labels[i]);
                tvLabel.setTextSize(12f);
                tvLabel.setTextColor(0xFF9E9E9E);
                tvLabel.setPadding(0, i == 0 ? 0 : (int)(12 * getResources().getDisplayMetrics().density), 0, 2);
                layout.addView(tvLabel);

                TextView tvValue = new TextView(this);
                tvValue.setText(values[i]);
                tvValue.setTextSize(16f);
                tvValue.setTextColor(0xFF212121);
                tvValue.setTypeface(null, android.graphics.Typeface.BOLD);
                layout.addView(tvValue);
            }

            new AlertDialog.Builder(this)
                    .setTitle("Mi información")
                    .setView(layout)
                    .setPositiveButton("Cerrar", null)
                    .show();
        });
    }

    private void setupItem(int itemId, int iconRes, String label, Class<?> target) {
        View item = findViewById(itemId);
        ((ImageView) item.findViewById(R.id.menuIcon)).setImageResource(iconRes);
        ((TextView)  item.findViewById(R.id.menuLabel)).setText(label);
        if (target != null) {
            item.setOnClickListener(v -> startActivity(new Intent(this, target)));
        } else {
            item.setOnClickListener(v ->
                    Toast.makeText(this, "Próximamente", Toast.LENGTH_SHORT).show());
        }
    }

    private void cerrarSesion() {
        // Nada se pierde: si hay cambios o fotos sin subir, no se cierra sesión (se borrarían datos aún no enviados)
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            int pendientes = PrivacidadLocal.pendientesSinSubir(this);
            if (pendientes > 0) {
                runOnUiThread(() -> new android.app.AlertDialog.Builder(this)
                        .setTitle("Hay cambios sin subir")
                        .setMessage("Conéctate a internet y sincroniza antes de cerrar sesión, para no perder tu trabajo.")
                        .setPositiveButton("Entendido", null).show());
                return;
            }
            PrivacidadLocal.purgarAlCerrarSesion(this);   // sin datos del cliente en el teléfono
            runOnUiThread(() -> {
                getSharedPreferences(LoginActivity.PREFS, MODE_PRIVATE).edit()
                        .remove(LoginActivity.KEY_SESSION)
                        .remove(LoginActivity.KEY_ROLE)
                        .remove(LoginActivity.KEY_NAME)
                        .apply();
                Intent i = new Intent(this, LoginActivity.class);
                i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i);
            });
        });
    }

    private static String labelForRole(String rol) {
        switch (rol) {
            case "DISPATCHER": return "Dispatcher";
            case "ADMIN":      return "Admin";
            default:           return "Técnico";
        }
    }
}
