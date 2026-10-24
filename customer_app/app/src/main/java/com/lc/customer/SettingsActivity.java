package com.lc.customer;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

public class SettingsActivity extends AppCompatActivity {

    private String temaSeleccionado;
    private String idiomaSeleccionado;
    private EditText etUrlServidor;

    // Referencias a chips para resaltar selección activa
    private TextView[] chipsTema = new TextView[3];
    private TextView[] chipsIdioma = new TextView[3];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        temaSeleccionado = Prefs.tema(this);
        idiomaSeleccionado = Prefs.idioma(this);

        boolean en = Prefs.en(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Prefs.fondo(this));

        // Cabecera
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.VERTICAL);
        cab.setBackgroundColor(Prefs.fondo(this));
        cab.setPadding(dp(20), dp(20), dp(20), dp(12));

        TextView volver = tv(en ? "← Back" : "← Volver", 14, Prefs.ROJO, true);
        volver.setMinHeight(dp(44));
        volver.setGravity(Gravity.CENTER_VERTICAL);
        volver.setOnClickListener(v -> finish());
        cab.addView(volver);

        TextView titulo = tv(en ? "Settings" : "Ajustes", 24, Prefs.texto(this), true);
        cab.addView(titulo);

        TextView sub = tv(en ? "Appearance and language" : "Apariencia e idioma",
                13, Prefs.sec(this), false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        sub.setLayoutParams(subLp);
        cab.addView(sub);
        raiz.addView(cab);

        // Línea divisora
        View linea = new View(this);
        linea.setBackgroundColor(Prefs.borde(this));
        raiz.addView(linea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Contenido desplazable
        ScrollView sv = new ScrollView(this);
        LinearLayout cont = new LinearLayout(this);
        cont.setOrientation(LinearLayout.VERTICAL);
        cont.setPadding(dp(20), dp(16), dp(20), dp(40));
        sv.addView(cont);
        raiz.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── Sección Apariencia ────────────────────────────────────────────────
        cont.addView(seccion(en ? "Appearance" : "Apariencia"));

        LinearLayout filaT = fila();
        String[] temaVals = {"claro", "oscuro", "auto"};
        String[] temaLabels = en
                ? new String[]{"Light", "Dark", "Automatic"}
                : new String[]{"Claro", "Oscuro", "Automático"};

        for (int i = 0; i < 3; i++) {
            final String val = temaVals[i];
            chipsTema[i] = chip(temaLabels[i], val.equals(temaSeleccionado));
            final int fi = i;
            chipsTema[i].setOnClickListener(v -> {
                temaSeleccionado = val;
                actualizarChips(chipsTema, fi);
            });
            filaT.addView(chipsTema[i]);
            if (i < 2) filaT.addView(spacer(8));
        }
        cont.addView(filaT);

        // Preview del tema
        TextView prevTema = tv(en ? "Current: " + Prefs.temaLabel(this)
                : "Actual: " + Prefs.temaLabel(this), 12, Prefs.sec(this), false);
        LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prevLp.topMargin = dp(8);
        prevTema.setLayoutParams(prevLp);
        cont.addView(prevTema);

        // ── Sección Idioma ────────────────────────────────────────────────────
        cont.addView(seccion(en ? "Language" : "Idioma"));

        LinearLayout filaI = fila();
        String[] idiomaVals = {"es", "en", "auto"};
        String[] idiomaLabels = en
                ? new String[]{"Spanish", "English", "Automatic"}
                : new String[]{"Español", "English", "Automático"};

        for (int i = 0; i < 3; i++) {
            final String val = idiomaVals[i];
            chipsIdioma[i] = chip(idiomaLabels[i], val.equals(idiomaSeleccionado));
            final int fi = i;
            chipsIdioma[i].setOnClickListener(v -> {
                idiomaSeleccionado = val;
                actualizarChips(chipsIdioma, fi);
            });
            filaI.addView(chipsIdioma[i]);
            if (i < 2) filaI.addView(spacer(8));
        }
        cont.addView(filaI);

        TextView prevIdioma = tv(en ? "Current: " + Prefs.idiomaLabel(this)
                : "Actual: " + Prefs.idiomaLabel(this), 12, Prefs.sec(this), false);
        prevIdioma.setLayoutParams(prevLp);
        cont.addView(prevIdioma);

        // ── Sección Servidor ─────────────────────────────────────────────────
        cont.addView(seccion(en ? "Advanced" : "Avanzado"));

        TextView lblUrl = tv(en ? "Server URL (leave empty for default)" : "URL del servidor (vacío = predeterminado)",
                13, Prefs.sec(this), false);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lblLp.bottomMargin = dp(8);
        lblUrl.setLayoutParams(lblLp);
        cont.addView(lblUrl);

        etUrlServidor = new EditText(this);
        etUrlServidor.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        etUrlServidor.setTextColor(Prefs.texto(this));
        etUrlServidor.setHintTextColor(Prefs.sec(this));
        etUrlServidor.setHint(BuildConfig.API_BASE);
        String currentUrl = Api.urlCustom(this);
        if (currentUrl != null) etUrlServidor.setText(currentUrl);
        GradientDrawable etBg = new GradientDrawable();
        etBg.setCornerRadius(dp(10));
        etBg.setColor(Prefs.fondoCard(this));
        etBg.setStroke(dp(1), Prefs.borde(this));
        etUrlServidor.setBackground(etBg);
        etUrlServidor.setPadding(dp(12), dp(12), dp(12), dp(12));
        etUrlServidor.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        cont.addView(etUrlServidor);

        // ── Nota informativa ─────────────────────────────────────────────────
        cont.addView(spacerView(24));
        TextView nota = tv(en
                ? "Changes apply immediately. The app will restart to apply the new settings."
                : "Los cambios se aplican de inmediato. La app se reiniciará para aplicar la nueva configuración.",
                13, Prefs.sec(this), false);
        LinearLayout.LayoutParams notaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nota.setLayoutParams(notaLp);
        nota.setLineSpacing(0, 1.4f);
        cont.addView(nota);

        // ── Botón Guardar ─────────────────────────────────────────────────────
        cont.addView(spacerView(24));
        TextView btnGuardar = new TextView(this);
        btnGuardar.setText(en ? "SAVE & RESTART" : "GUARDAR Y REINICIAR");
        btnGuardar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btnGuardar.setTypeface(Typeface.DEFAULT_BOLD);
        btnGuardar.setTextColor(Color.WHITE);
        btnGuardar.setGravity(Gravity.CENTER);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(dp(14));
        btnBg.setColor(Prefs.ROJO);
        btnGuardar.setBackground(btnBg);
        btnGuardar.setPadding(dp(20), dp(18), dp(20), dp(18));
        btnGuardar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        btnGuardar.setOnClickListener(v -> guardar());
        cont.addView(btnGuardar);

        setContentView(raiz);

        ViewCompat.setOnApplyWindowInsetsListener(raiz, (view, ins) -> {
            Insets sys = ins.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return ins;
        });
    }

    private void guardar() {
        Prefs.setTema(this, temaSeleccionado);
        Prefs.setIdioma(this, idiomaSeleccionado);
        String urlIngresada = etUrlServidor.getText().toString().trim();
        Api.setUrlCustom(this, urlIngresada.isEmpty() ? null : urlIngresada);
        Intent it = new Intent(this, WelcomeActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(it);
    }

    // ── Helpers de UI ─────────────────────────────────────────────────────────

    private TextView chip(String label, boolean activo) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        tv.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tv.setTypeface(activo ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        aplicarChipEstado(tv, activo);
        return tv;
    }

    private void aplicarChipEstado(TextView tv, boolean activo) {
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(10));
        if (activo) {
            gd.setColor(Prefs.ROJO);
            tv.setTextColor(Color.WHITE);
        } else {
            gd.setColor(Prefs.fondoCard(this));
            gd.setStroke(dp(1), Prefs.borde(this));
            tv.setTextColor(Prefs.texto(this));
        }
        tv.setBackground(gd);
    }

    private void actualizarChips(TextView[] chips, int activo) {
        for (int i = 0; i < chips.length; i++) {
            chips[i].setTypeface(i == activo ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            aplicarChipEstado(chips[i], i == activo);
        }
    }

    private TextView seccion(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(Prefs.texto(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(28);
        lp.bottomMargin = dp(12);
        tv.setLayoutParams(lp);
        return tv;
    }

    private LinearLayout fila() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        f.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return f;
    }

    private View spacer(int dpW) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(dpW), 1));
        return v;
    }

    private View spacerView(int dpH) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(dpH)));
        return v;
    }

    private TextView tv(String t, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
