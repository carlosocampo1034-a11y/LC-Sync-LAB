package com.lc.customer;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Construcción de pantallas con la identidad de L&C (adaptada al cliente). */
final class Ui {

    private Ui() {}

    static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    /** Pantalla con cabecera fija, contenido desplazable y barra inferior; respeta barras y teclado (Android 16). */
    static final class Pantalla {
        final LinearLayout raiz, cabecera, contenido, inferior;
        Pantalla(LinearLayout r, LinearLayout c, LinearLayout co, LinearLayout i) {
            raiz = r; cabecera = c; contenido = co; inferior = i;
        }
    }

    static Pantalla pantalla(Activity a, String titulo, String subtitulo, boolean conAtras) {
        a.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout raiz = new LinearLayout(a);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(ContextCompat.getColor(a, R.color.lc_fondo));
        LinearLayout cab = new LinearLayout(a);
        cab.setOrientation(LinearLayout.VERTICAL);
        cab.setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 12));
        if (conAtras) {
            TextView atras = texto(a, "← Volver", 15, R.color.lc_rojo, true);
            atras.setPadding(0, dp(a, 4), 0, dp(a, 8));
            atras.setMinHeight(dp(a, 44));
            atras.setGravity(Gravity.CENTER_VERTICAL);
            atras.setOnClickListener(v -> a.finish());
            cab.addView(atras);
        }
        cab.addView(texto(a, titulo, 28, R.color.lc_texto, true));
        if (subtitulo != null) cab.addView(texto(a, subtitulo, 14, R.color.lc_sec, false));
        View linea = new View(a);
        linea.setBackgroundColor(ContextCompat.getColor(a, R.color.lc_borde));
        ScrollView sv = new ScrollView(a);
        sv.setClipToPadding(false);
        LinearLayout cont = new LinearLayout(a);
        cont.setOrientation(LinearLayout.VERTICAL);
        cont.setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 24));
        sv.addView(cont);
        LinearLayout inf = new LinearLayout(a);
        inf.setOrientation(LinearLayout.VERTICAL);
        inf.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 16));
        raiz.addView(cab);
        raiz.addView(linea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 1)));
        raiz.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        raiz.addView(inf);
        // Edge-to-edge: barra de estado arriba; navegación o teclado abajo (lo mayor)
        final int top0 = raiz.getPaddingTop(), bot0 = raiz.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(raiz, (v, ins) -> {
            Insets b = ins.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int ime = ins.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(b.left, top0 + b.top, b.right, bot0 + Math.max(b.bottom, ime));
            return ins;
        });
        a.setContentView(raiz);
        return new Pantalla(raiz, cab, cont, inf);
    }

    static TextView texto(Activity a, String t, int sp, int color, boolean negrita) {
        TextView tv = new TextView(a);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(ContextCompat.getColor(a, color));
        if (negrita) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    static TextView seccion(Activity a, LinearLayout dest, String t) {
        TextView tv = texto(a, t, 18, R.color.lc_texto, true);
        tv.setPadding(0, dp(a, 18), 0, dp(a, 8));
        dest.addView(tv);
        return tv;
    }

    static EditText campo(Activity a, LinearLayout dest, String hint, int tipo) {
        EditText et = new EditText(a);
        et.setHint(hint);
        et.setInputType(tipo);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setTextColor(ContextCompat.getColor(a, R.color.lc_texto));
        et.setHintTextColor(ContextCompat.getColor(a, R.color.lc_sec));
        et.setBackground(ContextCompat.getDrawable(a, R.drawable.bg_input));
        et.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        et.setMinHeight(dp(a, 52));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 10);
        dest.addView(et, lp);
        return et;
    }

    static EditText campo(Activity a, LinearLayout dest, String hint) {
        return campo(a, dest, hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    }

    static Button boton(Activity a, LinearLayout dest, String t, boolean principal) {
        Button b = new Button(a);
        b.setText(t);
        b.setAllCaps(true);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(principal ? 0xFFFFFFFF : ContextCompat.getColor(a, R.color.lc_texto));
        b.setBackground(ContextCompat.getDrawable(a, principal ? R.drawable.bg_boton : R.drawable.bg_boton_sec));
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 54));
        lp.topMargin = dp(a, 10);
        dest.addView(b, lp);
        return b;
    }

    static LinearLayout tarjeta(Activity a, LinearLayout dest) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(ContextCompat.getDrawable(a, R.drawable.bg_card));
        c.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 10);
        dest.addView(c, lp);
        return c;
    }

    static TextView badge(Activity a, String estado) {
        TextView b = texto(a, estado, 12, android.R.color.white, true);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(ContextCompat.getDrawable(a, R.drawable.bg_badge));
        if ("COMPLETADO".equals(estado)) b.getBackground().setTint(ContextCompat.getColor(a, R.color.lc_ok));
        b.setPadding(dp(a, 8), dp(a, 3), dp(a, 8), dp(a, 3));
        return b;
    }

    /** Texto legible para el cliente a partir del estado interno (lista editable order_states.list). */
    static String estadoCliente(android.content.Context c, String e) {
        return Ajustes.estadoCliente(c, e);
    }

    static String fecha(long ms) {
        if (ms <= 0) return "—";
        return new java.text.SimpleDateFormat("EEE d MMM · h:mm a", java.util.Locale.forLanguageTag("es"))
                .format(new java.util.Date(ms));
    }
}
