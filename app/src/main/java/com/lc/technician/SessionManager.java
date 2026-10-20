package com.lc.technician;

import android.content.Context;
import android.content.SharedPreferences;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.TechnicianEntity;

/**
 * Provides stable access to the currently logged-in technician's identity.
 * Local/QA auth only — not production authentication.
 */
public class SessionManager {

    private static SessionManager instance;
    private final SharedPreferences prefs;
    private final Context appContext;

    private SessionManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(LoginActivity.PREFS, Context.MODE_PRIVATE);
    }

    public static SessionManager get(Context context) {
        if (instance == null) instance = new SessionManager(context);
        return instance;
    }

    public String getTechnicianName() {
        return prefs.getString(LoginActivity.KEY_NAME, "");
    }

    public String getRole() {
        return prefs.getString(LoginActivity.KEY_ROLE, "TECNICO");
    }

    public boolean isAdmin() {
        return "ADMIN".equals(getRole());
    }

    public boolean isDispatcher() {
        return "DISPATCHER".equals(getRole());
    }

    public boolean isLoggedIn() {
        return prefs.getBoolean(LoginActivity.KEY_SESSION, false);
    }

    /**
     * Resolves the current session's name to a TechnicianEntity from Room.
     * Must NOT be called on the main thread.
     */
    public TechnicianEntity getTechnicianEntity() {
        String name = getTechnicianName();
        if (name == null || name.isEmpty()) return null;
        return LcDatabase.getInstance(appContext).technicianDao().getByNombre(name);
    }

    /**
     * Returns a stable ID for the current user.
     * Prefers the Room-persisted UUID; falls back to name for local/QA auth.
     * Must NOT be called on the main thread (DB lookup may occur).
     */
    public String getCurrentTechnicianId() {
        String name = getTechnicianName();
        if (name == null || name.isEmpty()) return "";
        try {
            TechnicianEntity tech = LcDatabase.getInstance(appContext)
                    .technicianDao().getByNombre(name);
            if (tech != null) return tech.id;
        } catch (Exception ignored) {
            // DB not ready yet — fall through to name fallback
        }
        return name;
    }
}
