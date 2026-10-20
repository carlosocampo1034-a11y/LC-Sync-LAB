package com.lc.technician;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.TechnicianEntity;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {

    static final String PREFS       = "lc_prefs";
    static final String KEY_SESSION = "session_active";
    static final String KEY_ROLE    = "user_role";
    static final String KEY_NAME    = "user_name";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_SESSION, false)) {
            goMain();
            return;
        }

        setContentView(R.layout.activity_login);

        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado

        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        EditText etPin = findViewById(R.id.etPin);

        findViewById(R.id.btnIngresar).setOnClickListener(v -> {
            String pin = etPin.getText().toString().trim();
            String role = resolveRole(pin);
            if (role == null) {
                Toast.makeText(this, "PIN incorrecto", Toast.LENGTH_SHORT).show();
                etPin.setText("");
                return;
            }
            String name = nameForRole(role);
            prefs.edit()
                .putBoolean(KEY_SESSION, true)
                .putString(KEY_ROLE, role)
                .putString(KEY_NAME, name)
                .apply();
            seedTechnicianIfNeeded(name, role);
            goMain();
        });
    }

    private void seedTechnicianIfNeeded(String name, String role) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        exec.execute(() -> {
            try {
                LcDatabase db = LcDatabase.getInstance(getApplicationContext());
                TechnicianEntity existing = db.technicianDao().getByNombre(name);
                if (existing == null) {
                    TechnicianEntity tech = new TechnicianEntity();
                    tech.id = UUID.randomUUID().toString();
                    tech.nombre = name;
                    tech.rol = role;
                    tech.activo = true;
                    tech.createdAt = System.currentTimeMillis();
                    tech.updatedAt = System.currentTimeMillis();
                    db.technicianDao().insert(tech);
                }
            } catch (Exception ignored) {
                // Non-fatal: DB may not be ready yet
            }
        });
        exec.shutdown();
    }

    static String resolveRole(String pin) {
        // PINs loaded from local.properties (not tracked by git) via BuildConfig.
        // Empty input never matches, and a role whose PIN is not configured is disabled.
        if (pin == null || pin.isEmpty()) return null;
        if (matches(pin, BuildConfig.PIN_TECNICO))    return "TECNICO";
        if (matches(pin, BuildConfig.PIN_DISPATCHER)) return "DISPATCHER";
        if (matches(pin, BuildConfig.PIN_ADMIN))      return "ADMIN";
        return null;
    }

    private static boolean matches(String pin, String configured) {
        return configured != null && !configured.isEmpty() && pin.equals(configured);
    }

    private static String nameForRole(String role) {
        switch (role) {
            case "DISPATCHER": return "Dispatcher";
            case "ADMIN":      return "Admin";
            default:           return "Técnico";
        }
    }

    private void goMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
