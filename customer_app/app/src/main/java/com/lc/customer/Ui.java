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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 52));
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

    /**
     * Insignia de estado con la paleta oficial: gris (pendiente, pausado, repuesto, cancelado), negro (en ruta, llegué,
     * esperando aprobación), rojo (en reparación) y verde SOLO para completado. Se decide por el id del estado.
     */
    static TextView badgeEstado(Activity a, String idEstado, String texto) {
        TextView b = texto(a, texto, 12, android.R.color.white, true);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(ContextCompat.getDrawable(a, R.drawable.bg_badge).mutate());
        int color;
        switch (idEstado == null ? "" : idEstado) {
            case "COMPLETADO": case "PAGADO": color = R.color.lc_ok; break;
            case "EN REPARACIÓN": color = R.color.lc_rojo; break;
            case "EN RUTA": case "LLEGUÉ": case "ESPERANDO APROBACIÓN": color = R.color.lc_texto; break;
            default: color = R.color.lc_sec;          // pendiente, pausado, falta repuesto, cancelado
        }
        b.getBackground().setTint(ContextCompat.getColor(a, color));
        b.setPadding(dp(a, 8), dp(a, 3), dp(a, 8), dp(a, 3));
        return b;
    }

    /** Línea de tiempo vertical: cada etapa es {texto, done|current|todo}. */
    static void lineaTiempo(Activity a, LinearLayout card, java.util.List<String[]> etapas) {
        for (int k = 0; k < etapas.size(); k++) {
            String[] e = etapas.get(k);
            boolean ultima = k == etapas.size() - 1;
            LinearLayout fila = new LinearLayout(a);
            fila.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView punto = texto(a, "done".equals(e[1]) ? "✓" : "", 12, android.R.color.white, true);
            punto.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if ("done".equals(e[1])) g.setColor(ContextCompat.getColor(a, R.color.lc_ok));
            else if ("current".equals(e[1])) g.setColor(ContextCompat.getColor(a, R.color.lc_rojo));
            else { g.setColor(0xFFFFFFFF); g.setStroke(dp(a, 2), ContextCompat.getColor(a, R.color.lc_borde)); }
            punto.setBackground(g);
            col.addView(punto, new LinearLayout.LayoutParams(dp(a, 22), dp(a, 22)));
            if (!ultima) {
                View linea = new View(a);
                linea.setBackgroundColor(ContextCompat.getColor(a, "done".equals(e[1]) ? R.color.lc_ok : R.color.lc_borde));
                col.addView(linea, new LinearLayout.LayoutParams(dp(a, 2), dp(a, 22)));
            }
            fila.addView(col, new LinearLayout.LayoutParams(dp(a, 28), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView t = texto(a, e[0], 15, "todo".equals(e[1]) ? R.color.lc_sec : R.color.lc_texto, !"todo".equals(e[1]));
            t.setPadding(dp(a, 8), dp(a, 1), 0, 0);
            fila.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(fila);
        }
    }

    /** Soporte de L&C: llamar o escribir (contacto publicado en el sitio de L&C). */
    static void dialogoSoporte(Activity a) {
        new androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle("Soporte L&C")
                .setItems(new String[]{"Llamar · 786-922-7383", "Escribir un correo"}, (d, which) -> {
                    if (which == 0) a.startActivity(new android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:+17869227383")));
                    else a.startActivity(new android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:Carlosocampo1034@gmail.com"))
                            .putExtra(android.content.Intent.EXTRA_SUBJECT, "Soporte L&C Cliente"));
                })
                .setNegativeButton("Cerrar", null).show();
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
