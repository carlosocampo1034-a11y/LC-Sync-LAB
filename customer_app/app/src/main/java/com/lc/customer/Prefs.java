package com.lc.customer;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/** Preferencias persistentes de apariencia e idioma. */
final class Prefs {

    private static final String FILE = "lc_settings_v1";

    private Prefs() {}

    static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ── Tema ─────────────────────────────────────────────────────────────────
    // Valores: "claro" | "oscuro" | "auto"

    static String tema(Context c) { return p(c).getString("tema", "auto"); }
    static void setTema(Context c, String v) { p(c).edit().putString("tema", v).apply(); }

    static boolean dark(Context c) {
        switch (tema(c)) {
            case "oscuro": return true;
            case "claro": return false;
            default: {
                int mask = c.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                return mask == Configuration.UI_MODE_NIGHT_YES;
            }
        }
    }

    // ── Idioma ────────────────────────────────────────────────────────────────
    // Valores: "es" | "en" | "auto"

    static String idioma(Context c) { return p(c).getString("idioma", "auto"); }
    static void setIdioma(Context c, String v) { p(c).edit().putString("idioma", v).apply(); }

    static boolean en(Context c) {
        switch (idioma(c)) {
            case "en": return true;
            case "es": return false;
            default:
                return java.util.Locale.getDefault().getLanguage().startsWith("en");
        }
    }

    // ── Paleta dinámica ──────────────────────────────────────────────────────

    static final int ROJO = 0xFFEB1700;

    static int fondo(Context c)      { return dark(c) ? 0xFF1A1A1A : 0xFFFFFFFF; }
    static int fondoCard(Context c)  { return dark(c) ? 0xFF2C2C2C : 0xFFFFFFFF; }
    static int fondoSuave(Context c) { return dark(c) ? 0xFF252525 : 0xFFF6F6F6; }
    static int texto(Context c)      { return dark(c) ? 0xFFEEEEEE : 0xFF191919; }
    static int sec(Context c)        { return dark(c) ? 0xFF9E9E9E : 0xFF6B6B6B; }
    static int borde(Context c)      { return dark(c) ? 0xFF3A3A3A : 0xFFE5E5E5; }
    static int catBg(Context c)      { return dark(c) ? 0xFF3A1818 : 0xFFFFF0EE; }
    static int aiBubble(Context c)   { return dark(c) ? 0xFF2C1A1A : 0xFFFFF5F5; }
    static int aiBorde(Context c)    { return dark(c) ? 0xFF5C2020 : 0xFFFFCDD0; }
    static int navBg(Context c)      { return dark(c) ? 0xFF242424 : 0xFFFFFFFF; }
    static int inputBg(Context c)    { return dark(c) ? 0xFF2A2A2A : 0xFFF6F6F6; }
    static int inputBorde(Context c) { return dark(c) ? 0xFF484848 : 0xFFDDDDDD; }

    // ── Texto de opciones (para SettingsActivity) ────────────────────────────

    static String temaLabel(Context c) {
        switch (tema(c)) {
            case "oscuro": return en(c) ? "Dark" : "Oscuro";
            case "claro":  return en(c) ? "Light" : "Claro";
            default:       return en(c) ? "Automatic" : "Automático";
        }
    }

    static String idiomaLabel(Context c) {
        switch (idioma(c)) {
            case "en": return "English";
            case "es": return "Español";
            default:   return en(c) ? "Automatic" : "Automático";
        }
    }
}
