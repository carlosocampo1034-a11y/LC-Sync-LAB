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

    static final String PREFS            = "lc_prefs";
    static final String KEY_SESSION      = "session_active";
    static final String KEY_ROLE         = "user_role";
    static final String KEY_NAME         = "user_name";
    static final String KEY_PIN_TECNICO  = "pin_override_tecnico";
    static final String KEY_PIN_DISPATCH = "pin_override_dispatcher";
    static final String KEY_PIN_ADMIN    = "pin_override_admin";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // QA: la sesión local solo vale si también hay sesión de Supabase Auth del técnico
        if (prefs.getBoolean(KEY_SESSION, false)
                && (!BuildConfig.CLOUD_LOGIN || com.lc.technician.sync.SupabaseClient.get(this).hasUserSession())) {
            goMain();
            return;
        }

        setContentView(R.layout.activity_login);

        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado

        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        EditText etPin = findViewById(R.id.etPin);
        if (BuildConfig.CLOUD_LOGIN) {
            // Con cuenta en la nube no hay PIN: se entra con correo y contraseña (cuadro al pulsar el botón)
            etPin.setVisibility(android.view.View.GONE);
            ((android.widget.TextView) findViewById(R.id.tvLoginEtiqueta)).setText("Inicia sesión con tu cuenta");
            ((android.widget.TextView) findViewById(R.id.btnIngresar)).setText("Iniciar sesión");
        }

        findViewById(R.id.btnIngresar).setOnClickListener(v -> {
            if (BuildConfig.CLOUD_LOGIN) { loginCloud(prefs); return; }
            String pin = etPin.getText().toString().trim();
            String role = resolveRole(this, pin);
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

    /** QA: correo y contraseña del técnico en Supabase Auth; el rol lo confirma lc-api (profiles). */
    private void loginCloud(SharedPreferences prefs) {
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        EditText email = new EditText(this);
        email.setHint("Correo");
        email.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText clave = new EditText(this);
        clave.setHint("Contraseña");
        clave.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(email);
        box.addView(clave);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Iniciar sesión · L&C")
                .setView(box)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Entrar", (d, w) -> {
                    String e = email.getText().toString().trim();
                    String c = clave.getText().toString();
                    if (e.isEmpty() || c.isEmpty()) {
                        Toast.makeText(this, "Escribe correo y contraseña", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    com.lc.technician.sync.SupabaseClient sb = com.lc.technician.sync.SupabaseClient.get(this);
                    sb.signIn(e, c, new com.lc.technician.sync.SupabaseClient.Callback() {
                        @Override public void onSuccess() {
                            String rol = null, codigo = null;
                            try {
                                retrofit2.Response<okhttp3.ResponseBody> r = sb.api().me(sb.authHeader()).execute();
                                if (r.isSuccessful() && r.body() != null) {
                                    org.json.JSONObject me = new org.json.JSONObject(r.body().string());
                                    rol = me.optString("role");
                                    codigo = me.isNull("technician_code") ? null : me.optString("technician_code", null);
                                }
                            } catch (Exception ignored) {}
                            final String role = rol, techCode = codigo;
                            com.lc.technician.push.PushTokenRegistrar.registrar(LoginActivity.this);   // push: token vigente en cada inicio de sesión
                            runOnUiThread(() -> {
                                if (!"TECHNICIAN".equals(role)) {
                                    sb.signOut();
                                    Toast.makeText(LoginActivity.this, role == null
                                            ? "Sin conexión con L&C" : "Esta app es para técnicos", Toast.LENGTH_LONG).show();
                                    return;
                                }
                                // Las órdenes llegan con technician_code (QA-T01): es la identidad que filtra "Mis Jobs"
                                String name = techCode != null && !techCode.isEmpty() ? techCode
                                        : e.contains("@") ? e.substring(0, e.indexOf('@')) : e;
                                prefs.edit().putBoolean(KEY_SESSION, true).putString(KEY_ROLE, "TECNICO")
                                        .putString(KEY_NAME, name).apply();
                                seedTechnicianIfNeeded(name, "TECNICO");
                                goMain();
                            });
                        }
                        @Override public void onError(String msg) {
                            runOnUiThread(() -> Toast.makeText(LoginActivity.this,
                                    "Correo o contraseña incorrectos", Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .show();
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

    static String resolveRole(android.content.Context ctx, String pin) {
        if (pin == null || pin.isEmpty()) return null;
        android.content.SharedPreferences p = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
        // Dynamic overrides take priority over compile-time PINs
        if (matchesDynamic(pin, p.getString(KEY_PIN_TECNICO, null)))  return "TECNICO";
        if (matchesDynamic(pin, p.getString(KEY_PIN_DISPATCH, null))) return "DISPATCHER";
        if (matchesDynamic(pin, p.getString(KEY_PIN_ADMIN, null)))    return "ADMIN";
        // Fall back to PINs compiled from local.properties
        if (matches(pin, BuildConfig.PIN_TECNICO))    return "TECNICO";
        if (matches(pin, BuildConfig.PIN_DISPATCHER)) return "DISPATCHER";
        if (matches(pin, BuildConfig.PIN_ADMIN))      return "ADMIN";
        // Aprovisionamiento SOLO en compilaciones de laboratorio (BuildConfig.DEBUG). En release no
        // existe: sin PIN compilado o dinámico la app no deja entrar y la autenticación real es la
        // del servidor. El PIN local no sustituye a la autenticación Cloud.
        if (BuildConfig.DEBUG && sinPinConfigurado(p) && pin.matches("\\d{4,8}")) {
            p.edit().putString(KEY_PIN_TECNICO, pin).apply();
            return "TECNICO";
        }
        return null;
    }

    private static boolean sinPinConfigurado(android.content.SharedPreferences p) {
        return !matchesDynamic("x", p.getString(KEY_PIN_TECNICO, null))
            && !matchesDynamic("x", p.getString(KEY_PIN_DISPATCH, null))
            && !matchesDynamic("x", p.getString(KEY_PIN_ADMIN, null))
            && (BuildConfig.PIN_TECNICO == null || BuildConfig.PIN_TECNICO.isEmpty())
            && (BuildConfig.PIN_DISPATCHER == null || BuildConfig.PIN_DISPATCHER.isEmpty())
            && (BuildConfig.PIN_ADMIN == null || BuildConfig.PIN_ADMIN.isEmpty());
    }

    // Keep backward-compatible no-context version for tests that still use it
    static String resolveRole(String pin) {
        if (pin == null || pin.isEmpty()) return null;
        if (matches(pin, BuildConfig.PIN_TECNICO))    return "TECNICO";
        if (matches(pin, BuildConfig.PIN_DISPATCHER)) return "DISPATCHER";
        if (matches(pin, BuildConfig.PIN_ADMIN))      return "ADMIN";
        return null;
    }

    private static boolean matchesDynamic(String pin, String stored) {
        return stored != null && !stored.isEmpty() && pin.equals(stored);
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
