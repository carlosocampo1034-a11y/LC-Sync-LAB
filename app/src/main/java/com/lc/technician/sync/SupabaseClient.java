package com.lc.technician.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.lc.technician.BuildConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Singleton que mantiene la instancia Retrofit y el token de Supabase.
 *
 * Flujo de autenticación:
 *   1. App arranca con anon key (acceso de solo lectura según RLS).
 *   2. Cuando el técnico hace login en Flask, llamar signIn() con sus
 *      credenciales de Supabase Auth (email + password provisionados por admin).
 *   3. El access_token se guarda en SharedPreferences cifradas y se usa
 *      en cada llamada REST y Realtime.
 *   4. refreshIfNeeded() renueva el token antes de que expire.
 */
public class SupabaseClient {

    private static final String TAG      = "SupabaseClient";
    private static final String PREFS    = "supabase_session";
    private static final String KEY_AT   = "access_token";
    private static final String KEY_RT   = "refresh_token";
    private static final String KEY_EXP  = "expires_at_ms";

    private static SupabaseClient sInstance;

    private final SupabaseApi api;
    private final SharedPreferences prefs;
    private final Gson gson = new GsonBuilder().create();

    private SupabaseClient(Context ctx) {
        prefs = ctx.getApplicationContext()
                   .getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                // tope TOTAL por llamada (conexión + envío + respuesta): con red lenta no se acumulan minutos
                .callTimeout(20, TimeUnit.SECONDS)
                // Añade apikey en cada petición (requerido por PostgREST + auth)
                .addInterceptor(chain -> {
                    Request req = chain.request().newBuilder()
                            .addHeader("apikey", BuildConfig.SUPABASE_ANON_KEY)
                            .build();
                    return chain.proceed(req);
                })
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BuildConfig.SUPABASE_URL + "/")
                .client(http)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        api = retrofit.create(SupabaseApi.class);
    }

    public static synchronized SupabaseClient get(Context ctx) {
        if (sInstance == null) sInstance = new SupabaseClient(ctx);
        return sInstance;
    }

    public SupabaseApi api() { return api; }

    // ── Token ────────────────────────────────────────────────────────────────

    /** Encabezado "Bearer <token>" listo para usar en llamadas REST. */
    public String authHeader() {
        String at = prefs.getString(KEY_AT, null);
        if (at != null) return "Bearer " + at;
        // Sin sesión de usuario: usa anon key directamente (RLS anon)
        return "Bearer " + BuildConfig.SUPABASE_ANON_KEY;
    }

    /** Access token del usuario (null si no hay sesión). Para Realtime con RLS. */
    public String accessToken() {
        return prefs.getString(KEY_AT, null);
    }

    public boolean hasUserSession() {
        return prefs.getString(KEY_AT, null) != null;
    }

    /** Llama en background; guarda la sesión si tiene éxito. */
    public void signIn(String email, String password, Callback cb) {
        new Thread(() -> {
            try {
                retrofit2.Response<SupabaseModels.SignInResponse> r =
                        api.signIn("password",
                                   new SupabaseModels.SignInRequest(email, password))
                           .execute();
                if (r.isSuccessful() && r.body() != null) {
                    saveSession(r.body());
                    cb.onSuccess();
                } else {
                    cb.onError("HTTP " + r.code());
                }
            } catch (Exception e) {
                Log.w(TAG, "signIn error", e);
                cb.onError(e.getMessage());
            }
        }, "sb-signin").start();
    }

    /**
     * Ensures a valid auth session synchronously (for use on background threads).
     * Returns null on success, or an error string on failure.
     */
    public String ensureAuthSync() {
        long exp = prefs.getLong(KEY_EXP, 0);
        String at = prefs.getString(KEY_AT, null);
        // Token still valid
        if (at != null && System.currentTimeMillis() < exp - 5 * 60_000L) return null;

        // Try refresh first if we have a refresh token
        String rt = prefs.getString(KEY_RT, null);
        if (rt != null) {
            try {
                retrofit2.Response<SupabaseModels.SignInResponse> r =
                        api.refreshToken("refresh_token", new SupabaseModels.RefreshRequest(rt)).execute();
                if (r.isSuccessful() && r.body() != null) { saveSession(r.body()); return null; }
            } catch (Exception ignored) {}
        }

        // Fall back to password sign-in
        if (BuildConfig.CLOUD_LOGIN) return "sin sesión de usuario: inicia sesión de nuevo";
        if (BuildConfig.SUPABASE_AUTH_EMAIL.isEmpty()) return "SUPABASE_AUTH_EMAIL not configured";
        try {
            retrofit2.Response<SupabaseModels.SignInResponse> r =
                    api.signIn("password", new SupabaseModels.SignInRequest(
                            BuildConfig.SUPABASE_AUTH_EMAIL, BuildConfig.SUPABASE_AUTH_PASSWORD)).execute();
            if (r.isSuccessful() && r.body() != null) { saveSession(r.body()); return null; }
            return "signIn HTTP " + r.code();
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    /** Renueva el token si queda menos de 5 minutos para que expire. */
    public void refreshIfNeeded() {
        long exp = prefs.getLong(KEY_EXP, 0);
        if (System.currentTimeMillis() < exp - 5 * 60_000L) return;

        String rt = prefs.getString(KEY_RT, null);
        if (rt == null) return;

        new Thread(() -> {
            try {
                retrofit2.Response<SupabaseModels.SignInResponse> r =
                        api.refreshToken("refresh_token",
                                         new SupabaseModels.RefreshRequest(rt))
                           .execute();
                if (r.isSuccessful() && r.body() != null) saveSession(r.body());
                else Log.w(TAG, "refresh failed: " + r.code());
            } catch (Exception e) {
                Log.w(TAG, "refresh error", e);
            }
        }, "sb-refresh").start();
    }

    public void signOut() {
        prefs.edit().remove(KEY_AT).remove(KEY_RT).remove(KEY_EXP).apply();
    }

    private void saveSession(SupabaseModels.SignInResponse s) {
        long expiresAt = System.currentTimeMillis() + s.expiresIn * 1000L;
        prefs.edit()
             .putString(KEY_AT,  s.accessToken)
             .putString(KEY_RT,  s.refreshToken)
             .putLong(KEY_EXP,   expiresAt)
             .apply();
        Log.i(TAG, "sesión guardada, expira en " + (s.expiresIn / 60) + " min");
    }

    public interface Callback {
        void onSuccess();
        void onError(String msg);
    }
}
