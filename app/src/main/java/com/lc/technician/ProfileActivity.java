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
        // Edge-to-edge (Android 15+): cabecera bajo la barra de estado (el inferior ya lo cubre marginBottom=56dp)
        InsetsHelper.padTop(findViewById(R.id.header));

        ((ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        SharedPreferences prefs = getSharedPreferences(LoginActivity.PREFS, MODE_PRIVATE);
        String rol  = prefs.getString(LoginActivity.KEY_ROLE, "TECNICO");
        String name = prefs.getString(LoginActivity.KEY_NAME, Technician.NOMBRE);

        ((TextView) findViewById(R.id.tvNombre)).setText(name);
        ((TextView) findViewById(R.id.tvSubtitulo)).setText("Técnico desde " + Technician.FECHA_INGRESO);

        TextView tvRol = findViewById(R.id.tvRol);
        tvRol.setText(labelForRole(rol));
        tvRol.setVisibility(View.VISIBLE);

        configurarMenu(prefs, rol);
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
            if (!Job.STATUS_DONE.equals(j.estado)) continue;
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

        ((TextView) findViewById(R.id.statHoy)).setText(String.valueOf(completadosHoy));
        ((TextView) findViewById(R.id.statSemana)).setText(String.valueOf(completadosSemana));
        ((TextView) findViewById(R.id.statCompletados)).setText(String.valueOf(totalCompletados));
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

        // Cerrar sesión
        View cerrar = findViewById(R.id.menuCerrarSesion);
        ((ImageView) cerrar.findViewById(R.id.menuIcon)).setImageResource(R.drawable.ic_menu_logout);
        TextView tvCerrar = cerrar.findViewById(R.id.menuLabel);
        tvCerrar.setText("Cerrar sesión");
        tvCerrar.setTextColor(0xFFE91605);
        cerrar.setOnClickListener(v -> cerrarSesion());
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
        ((TextView)  item.findViewById(R.id.menuLabel)).setText("Mis datos personales");
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
                    .setTitle("Mis datos personales")
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
        getSharedPreferences(LoginActivity.PREFS, MODE_PRIVATE).edit()
                .remove(LoginActivity.KEY_SESSION)
                .remove(LoginActivity.KEY_ROLE)
                .remove(LoginActivity.KEY_NAME)
                .apply();
        Intent i = new Intent(this, LoginActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
    }

    private static String labelForRole(String rol) {
        switch (rol) {
            case "DISPATCHER": return "Dispatcher";
            case "ADMIN":      return "Admin";
            default:           return "Técnico";
        }
    }
}
