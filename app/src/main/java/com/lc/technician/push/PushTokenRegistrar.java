package com.lc.technician.push;

import android.content.Context;
import android.util.Log;

import com.lc.technician.BuildConfig;
import com.lc.technician.sync.SupabaseClient;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Envía el token de FCM de este teléfono a lc-api (tabla push_tokens) asociado al técnico con sesión.
 * Se llama al iniciar sesión, al arrancar la pantalla principal y cuando FCM entrega un token nuevo.
 * Sin google-services.json (compilación sin Firebase) o sin sesión no hace nada: nunca rompe la sincronización.
 */
public final class PushTokenRegistrar {

    private static final String TAG = "LcPush";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private PushTokenRegistrar() {}

    /** Pide el token vigente a Firebase y lo registra. */
    public static void registrar(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!BuildConfig.CLOUD_LOGIN || !SupabaseClient.get(app).hasUserSession()) return;
        try {
            if (com.google.firebase.FirebaseApp.getApps(app).isEmpty()) {
                Log.i(TAG, "Firebase no configurado en esta compilación; sin push");
                return;
            }
            com.google.firebase.messaging.FirebaseMessaging.getInstance().getToken()
                    .addOnSuccessListener(token -> enviar(app, token))
                    .addOnFailureListener(e -> Log.w(TAG, "token FCM no disponible: " + e.getClass().getSimpleName()));
        } catch (Throwable t) {
            Log.w(TAG, "registro de token omitido: " + t.getClass().getSimpleName());
        }
    }

    /** Registra un token ya conocido (onNewToken). */
    public static void enviar(Context ctx, String token) {
        if (token == null || token.length() < 20) return;
        final Context app = ctx.getApplicationContext();
        IO.execute(() -> {
            try {
                SupabaseClient sb = SupabaseClient.get(app);
                if (!sb.hasUserSession() || sb.ensureAuthSync() != null) {
                    // sin sesión todavía: el token se envía al iniciar sesión
                    app.getSharedPreferences("lc_push", Context.MODE_PRIVATE).edit().putString("token_pendiente", token).apply();
                    return;
                }
                HashMap<String, Object> body = new HashMap<>();
                body.put("token", token);
                body.put("platform", "android");
                body.put("app", app.getPackageName());
                retrofit2.Response<okhttp3.ResponseBody> r = sb.api().registerPushToken(sb.authHeader(), body).execute();
                if (r.isSuccessful()) {
                    app.getSharedPreferences("lc_push", Context.MODE_PRIVATE).edit().remove("token_pendiente").apply();
                    Log.i(TAG, "token FCM registrado en el servidor");
                } else {
                    Log.w(TAG, "registro de token HTTP " + r.code());
                }
            } catch (Exception e) {
                Log.w(TAG, "registro de token sin conexión: " + e.getClass().getSimpleName());
            }
        });
    }
}
