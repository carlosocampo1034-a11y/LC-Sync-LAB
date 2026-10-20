package com.lc.technician;

import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.AuditLogEntity;
import com.lc.technician.model.TechnicianEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;

/**
 * TechnicianRosterActivity — lists all technicians, with CRUD for ADMIN role.
 * Accessible from ProfileActivity (ADMIN/DISPATCHER).
 */
public class TechnicianRosterActivity extends AppCompatActivity {

    private TechnicianRosterAdapter adapter;
    private final List<TechnicianEntity> tecnicos = new ArrayList<>();
    private TextView tvContador;
    private TextView tvEmpty;
    private TextView fabAgregar;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean esAdmin;
    private String actorName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_technician_roster);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        esAdmin   = SessionManager.get(this).isAdmin();
        actorName = SessionManager.get(this).getTechnicianName();

        ((android.widget.ImageView) findViewById(R.id.btnAtras)).setOnClickListener(v -> finish());

        tvContador = findViewById(R.id.tvContador);
        tvEmpty    = findViewById(R.id.tvEmpty);
        fabAgregar = findViewById(R.id.fabAgregarTecnico);

        RecyclerView recycler = findViewById(R.id.recyclerTecnicos);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TechnicianRosterAdapter(tecnicos, this::mostrarOpcionesTecnico);
        recycler.setAdapter(adapter);

        if (esAdmin) {
            fabAgregar.setVisibility(View.VISIBLE);
            fabAgregar.setOnClickListener(v -> mostrarDialogoAgregarTecnico());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        cargarTecnicos();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void cargarTecnicos() {
        executor.execute(() -> {
            List<TechnicianEntity> todos = LcDatabase.getInstance(this).technicianDao().getAll();
            mainHandler.post(() -> {
                tecnicos.clear();
                tecnicos.addAll(todos);
                adapter.notifyDataSetChanged();
                tvContador.setText(todos.size() + " técnicos");
                tvEmpty.setVisibility(todos.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    private void mostrarOpcionesTecnico(TechnicianEntity tech) {
        if (!esAdmin) {
            Toast.makeText(this, tech.nombre + " — " + (tech.activo ? "Activo" : "Inactivo"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> opciones = new ArrayList<>();
        opciones.add("Editar datos");
        opciones.add(tech.activo ? "Desactivar" : "Reactivar");

        new AlertDialog.Builder(this)
            .setTitle(tech.nombre)
            .setItems(opciones.toArray(new CharSequence[0]), (d, which) -> {
                if (which == 0) {
                    mostrarDialogoEditar(tech);
                } else {
                    toggleActivo(tech);
                }
            })
            .show();
    }

    private void mostrarDialogoAgregarTecnico() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(12), dp(20), 0);

        EditText etNombre   = new EditText(this);
        etNombre.setHint("Nombre *");
        etNombre.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);

        EditText etEmail    = new EditText(this);
        etEmail.setHint("Email (opcional)");
        etEmail.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        EditText etTelefono = new EditText(this);
        etTelefono.setHint("Teléfono (opcional)");
        etTelefono.setInputType(InputType.TYPE_CLASS_PHONE);

        Spinner spinnerRol = new Spinner(this);
        String[] roles = {"TECNICO", "DISPATCHER", "ADMIN"};
        spinnerRol.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, roles));

        layout.addView(etNombre);
        layout.addView(etEmail);
        layout.addView(etTelefono);
        layout.addView(spinnerRol);

        new AlertDialog.Builder(this)
            .setTitle("Nuevo técnico")
            .setView(layout)
            .setPositiveButton("Guardar", (d, w) -> {
                String nombre = etNombre.getText().toString().trim();
                if (nombre.isEmpty()) {
                    Toast.makeText(this, "El nombre es requerido", Toast.LENGTH_SHORT).show();
                    return;
                }
                TechnicianEntity tech = new TechnicianEntity();
                tech.id        = UUID.randomUUID().toString();
                tech.nombre    = nombre;
                tech.email     = etEmail.getText().toString().trim();
                tech.telefono  = etTelefono.getText().toString().trim();
                tech.rol       = roles[spinnerRol.getSelectedItemPosition()];
                tech.activo    = true;
                tech.createdAt = System.currentTimeMillis();
                tech.updatedAt = System.currentTimeMillis();
                guardarTecnico(tech, true);
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void mostrarDialogoEditar(TechnicianEntity tech) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(12), dp(20), 0);

        EditText etNombre   = new EditText(this);
        etNombre.setText(tech.nombre);
        etNombre.setHint("Nombre *");

        EditText etEmail    = new EditText(this);
        etEmail.setText(tech.email);
        etEmail.setHint("Email");

        EditText etTelefono = new EditText(this);
        etTelefono.setText(tech.telefono);
        etTelefono.setHint("Teléfono");

        layout.addView(etNombre);
        layout.addView(etEmail);
        layout.addView(etTelefono);

        new AlertDialog.Builder(this)
            .setTitle("Editar técnico")
            .setView(layout)
            .setPositiveButton("Guardar", (d, w) -> {
                String nombre = etNombre.getText().toString().trim();
                if (nombre.isEmpty()) {
                    Toast.makeText(this, "El nombre es requerido", Toast.LENGTH_SHORT).show();
                    return;
                }
                executor.execute(() -> {
                    LcDatabase.getInstance(this).technicianDao().updateInfo(
                        tech.id,
                        nombre,
                        etEmail.getText().toString().trim(),
                        etTelefono.getText().toString().trim(),
                        System.currentTimeMillis()
                    );
                    auditTechnicianEvent("TECHNICIAN_UPDATED", tech.id,
                            "Técnico actualizado: " + nombre);
                    mainHandler.post(() -> {
                        Toast.makeText(this, "Actualizado", Toast.LENGTH_SHORT).show();
                        cargarTecnicos();
                    });
                });
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void toggleActivo(TechnicianEntity tech) {
        boolean nuevoEstado = !tech.activo;
        String msg = nuevoEstado ? "¿Reactivar a " + tech.nombre + "?" :
                                   "¿Desactivar a " + tech.nombre + "? Las asignaciones históricas se conservan.";
        new AlertDialog.Builder(this)
            .setTitle(nuevoEstado ? "Reactivar técnico" : "Desactivar técnico")
            .setMessage(msg)
            .setPositiveButton("Confirmar", (d, w) -> {
                executor.execute(() -> {
                    LcDatabase.getInstance(this).technicianDao()
                            .setActivo(tech.id, nuevoEstado, System.currentTimeMillis());
                    String tipo = nuevoEstado ? "TECHNICIAN_REACTIVATED" : "TECHNICIAN_DEACTIVATED";
                    auditTechnicianEvent(tipo, tech.id,
                            (nuevoEstado ? "Técnico reactivado: " : "Técnico desactivado: ") + tech.nombre);
                    mainHandler.post(() -> {
                        Toast.makeText(this,
                                nuevoEstado ? "Reactivado" : "Desactivado", Toast.LENGTH_SHORT).show();
                        cargarTecnicos();
                    });
                });
            })
            .setNegativeButton("Cancelar", null)
            .show();
    }

    private void guardarTecnico(TechnicianEntity tech, boolean esNuevo) {
        executor.execute(() -> {
            LcDatabase.getInstance(this).technicianDao().insert(tech);
            if (esNuevo) {
                auditTechnicianEvent("TECHNICIAN_CREATED", tech.id,
                        "Técnico creado: " + tech.nombre);
            }
            mainHandler.post(() -> {
                Toast.makeText(this, "Técnico guardado", Toast.LENGTH_SHORT).show();
                cargarTecnicos();
            });
        });
    }

    private void auditTechnicianEvent(String tipo, String techId, String descripcion) {
        try {
            AuditLogEntity entry = new AuditLogEntity();
            entry.entityType = "TECHNICIAN";
            entry.entityId   = techId.hashCode();
            entry.jobId      = 0L;
            entry.action     = tipo;
            entry.field      = "technicianId";
            entry.newValue   = techId;
            entry.actor      = actorName;
            entry.timestamp  = System.currentTimeMillis();
            entry.extra      = descripcion;
            LcDatabase.getInstance(this).auditLogDao().insert(entry);
        } catch (Exception ignored) {}
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
