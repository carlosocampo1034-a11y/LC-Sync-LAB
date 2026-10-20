package com.lc.technician.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Centralizes proposal defaults (markup tiers, tax rate) in SharedPreferences.
 * Use ConfigManager instead of hardcoding DEF_MARKUP in ProposalActivity.
 */
public class ConfigManager {

    private static final String PREFS_NAME       = "lc_config";
    private static final String KEY_MARKUP_GOOD  = "markup_good";
    private static final String KEY_MARKUP_BETTER = "markup_better";
    private static final String KEY_MARKUP_BEST  = "markup_best";
    private static final String KEY_TAX_RATE     = "tax_rate";

    // Defaults that match historical hardcoded values
    public static final float DEFAULT_MARKUP_GOOD   = 15f;
    public static final float DEFAULT_MARKUP_BETTER = 20f;
    public static final float DEFAULT_MARKUP_BEST   = 25f;
    public static final float DEFAULT_TAX_RATE      = 7f;

    private final SharedPreferences prefs;

    public ConfigManager(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public float getMarkupGood()   { return prefs.getFloat(KEY_MARKUP_GOOD,   DEFAULT_MARKUP_GOOD); }
    public float getMarkupBetter() { return prefs.getFloat(KEY_MARKUP_BETTER, DEFAULT_MARKUP_BETTER); }
    public float getMarkupBest()   { return prefs.getFloat(KEY_MARKUP_BEST,   DEFAULT_MARKUP_BEST); }
    public float getTaxRate()      { return prefs.getFloat(KEY_TAX_RATE,      DEFAULT_TAX_RATE); }

    // ── Setters with validation ───────────────────────────────────────────────

    public boolean setMarkupGood(float v) {
        if (!isValidMarkup(v)) return false;
        prefs.edit().putFloat(KEY_MARKUP_GOOD, v).apply();
        return true;
    }

    public boolean setMarkupBetter(float v) {
        if (!isValidMarkup(v)) return false;
        prefs.edit().putFloat(KEY_MARKUP_BETTER, v).apply();
        return true;
    }

    public boolean setMarkupBest(float v) {
        if (!isValidMarkup(v)) return false;
        prefs.edit().putFloat(KEY_MARKUP_BEST, v).apply();
        return true;
    }

    public boolean setTaxRate(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v) || v < 0 || v > 30) return false;
        prefs.edit().putFloat(KEY_TAX_RATE, v).apply();
        return true;
    }

    // ── Convenience array (indexed 0=GOOD, 1=BETTER, 2=BEST) ─────────────────

    public double[] getMarkups() {
        return new double[]{getMarkupGood(), getMarkupBetter(), getMarkupBest()};
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private static boolean isValidMarkup(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v) && v >= 0 && v <= 100;
    }
}
