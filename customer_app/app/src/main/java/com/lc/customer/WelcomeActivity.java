package com.lc.customer;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Pantalla principal L&C Home Services — Diseño v2.
 * Distribución: cabecera + saludo + categorías + banner promo + tarjetas ayuda + nav flotante.
 * Modo invitado: no requiere login. Toda la lógica de navegación existente se conserva.
 */
public class WelcomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        // Raíz FrameLayout permite overlay del nav flotante
        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.WHITE);

        // ScrollView con todo el contenido
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        LinearLayout cont = new LinearLayout(this);
        cont.setOrientation(LinearLayout.VERTICAL);
        cont.setBackgroundColor(Color.WHITE);
        sv.addView(cont);
        raiz.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cont.addView(buildCabecera());
        cont.addView(buildSaludo());
        cont.addView(buildEtiqueta("Categorías de Servicio"));
        cont.addView(buildCategorias());
        cont.addView(buildEtiqueta("Promociones"));
        cont.addView(buildBannerPromo());
        cont.addView(buildEtiqueta("¿Cómo podemos ayudarte?"));
        cont.addView(buildTarjetasAyuda());
        cont.addView(buildEspaciador(88)); // espacio para nav flotante

        // Nav flotante superpuesto al fondo
        raiz.addView(buildBottomNav());

        setContentView(raiz);

        ViewCompat.setOnApplyWindowInsetsListener(raiz, (view, ins) -> {
            Insets sys = ins.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return ins;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Api.conSesion(this)) {
            startActivity(new Intent(this, HomeActivity.class));
        }
    }

    // ── CABECERA ─────────────────────────────────────────────────────────────

    private View buildCabecera() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setBackgroundColor(Color.WHITE);
        h.setPadding(dp(24), dp(18), dp(24), dp(14));
        h.setGravity(Gravity.CENTER_VERTICAL);

        // Logo L&C (izquierda)
        TextView logo = new TextView(this);
        logo.setText("L&C");
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setTextColor(ContextCompat.getColor(this, R.color.lc_rojo));
        h.addView(logo, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Mi Cuenta (derecha)
        TextView miCuenta = new TextView(this);
        miCuenta.setText("👤  Mi Cuenta");
        miCuenta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        miCuenta.setTypeface(Typeface.DEFAULT_BOLD);
        miCuenta.setTextColor(ContextCompat.getColor(this, R.color.lc_rojo));
        miCuenta.setPadding(dp(12), dp(8), dp(12), dp(8));
        miCuenta.setOnClickListener(v -> irACuenta());
        h.addView(miCuenta);

        return h;
    }

    // ── SALUDO ────────────────────────────────────────────────────────────────

    private View buildSaludo() {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setPadding(dp(24), dp(4), dp(24), dp(20));

        TextView t1 = new TextView(this);
        t1.setText("¡Hola! ¿En qué podemos");
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(ContextCompat.getColor(this, R.color.lc_texto));
        s.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText("ayudarte hoy?");
        t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        t2.setTypeface(Typeface.DEFAULT_BOLD);
        t2.setTextColor(ContextCompat.getColor(this, R.color.lc_texto));
        s.addView(t2);

        TextView sub = new TextView(this);
        sub.setText("Encuentra el servicio que necesitas para tu hogar.");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setTextColor(ContextCompat.getColor(this, R.color.lc_sec));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        sub.setLayoutParams(subLp);
        s.addView(sub);

        return s;
    }

    // ── ETIQUETA DE SECCIÓN ───────────────────────────────────────────────────

    private View buildEtiqueta(String texto) {
        TextView tv = new TextView(this);
        tv.setText(texto);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(ContextCompat.getColor(this, R.color.lc_texto));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(24);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(12);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ── CATEGORÍAS ────────────────────────────────────────────────────────────

    private View buildCategorias() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipToPadding(false);
        hsv.setPadding(dp(24), 0, dp(24), 0);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.bottomMargin = dp(8);
        hsv.setLayoutParams(hLp);

        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        fila.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String[][] cats = {
                {"🔧", "Plomería"},
                {"⚡", "Electricidad"},
                {"❄️", "HVAC"},
                {"🏠", "Hogar"},
                {"🔌", "Electrodomésticos"},
                {"➕", "Ver Todo"}
        };
        for (int i = 0; i < cats.length; i++) {
            fila.addView(buildIconoCategoria(cats[i][0], cats[i][1], i < cats.length - 1));
        }
        hsv.addView(fila);
        return hsv;
    }

    private View buildIconoCategoria(String emoji, String label, boolean marginRight) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(74), ViewGroup.LayoutParams.WRAP_CONTENT);
        if (marginRight) lp.rightMargin = dp(8);
        col.setLayoutParams(lp);
        col.setOnClickListener(v -> {
            Intent it = new Intent(this, GuestRequestActivity.class);
            it.putExtra("prefill_descripcion", label);
            startActivity(it);
        });
        col.setClickable(true);
        col.setFocusable(true);

        // Círculo rojo pálido
        TextView ic = new TextView(this);
        ic.setText(emoji);
        ic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable circ = new GradientDrawable();
        circ.setShape(GradientDrawable.OVAL);
        circ.setColor(ContextCompat.getColor(this, R.color.lc_cat_bg));
        ic.setBackground(circ);
        int sz = dp(56);
        col.addView(ic, new LinearLayout.LayoutParams(sz, sz));

        // Etiqueta
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(ContextCompat.getColor(this, R.color.lc_texto));
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(2);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tvLp.topMargin = dp(6);
        tv.setLayoutParams(tvLp);
        col.addView(tv);

        return col;
    }

    // ── BANNER PROMOCIONAL ────────────────────────────────────────────────────

    private View buildBannerPromo() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wLp.leftMargin = dp(24);
        wLp.rightMargin = dp(24);
        wLp.bottomMargin = dp(4);
        wrap.setLayoutParams(wLp);

        // Tarjeta roja con CardView (corner 16dp, elevation)
        CardView card = new CardView(this);
        card.setRadius(dp(16));
        card.setCardElevation(dp(3));
        card.setCardBackgroundColor(ContextCompat.getColor(this, R.color.lc_rojo));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(148)));

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(24), dp(20), dp(24), dp(20));
        inner.setGravity(Gravity.CENTER_VERTICAL);

        TextView tit = new TextView(this);
        tit.setText("🎉 Mantenimiento desde $50");
        tit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        tit.setTypeface(Typeface.DEFAULT_BOLD);
        tit.setTextColor(Color.WHITE);
        inner.addView(tit);

        TextView sub = new TextView(this);
        sub.setText("Diagnóstico incluido · Agenda tu visita hoy");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(0xCCFFFFFF);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        sub.setLayoutParams(subLp);
        inner.addView(sub);

        // Botón CTA
        TextView cta = new TextView(this);
        cta.setText("SOLICITAR AHORA  →");
        cta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cta.setTypeface(Typeface.DEFAULT_BOLD);
        cta.setTextColor(Color.WHITE);
        GradientDrawable ctaBg = new GradientDrawable();
        ctaBg.setCornerRadius(dp(20));
        ctaBg.setColor(0x33FFFFFF);
        cta.setBackground(ctaBg);
        cta.setPadding(dp(16), dp(8), dp(16), dp(8));
        LinearLayout.LayoutParams ctaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctaLp.topMargin = dp(12);
        cta.setLayoutParams(ctaLp);
        cta.setOnClickListener(v -> startActivity(new Intent(this, GuestRequestActivity.class)));
        inner.addView(cta);

        card.addView(inner);
        wrap.addView(card);

        // Indicadores de carrusel (3 puntos — visual)
        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dotsLp.topMargin = dp(10);
        dotsLp.bottomMargin = dp(4);
        dots.setLayoutParams(dotsLp);
        int[] dotColors = {
                ContextCompat.getColor(this, R.color.lc_rojo),
                ContextCompat.getColor(this, R.color.lc_borde),
                ContextCompat.getColor(this, R.color.lc_borde)
        };
        for (int i = 0; i < 3; i++) {
            View dot = new View(this);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(dotColors[i]);
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                    dp(i == 0 ? 18 : 8), dp(8));
            dotLp.rightMargin = dp(4);
            dot.setLayoutParams(dotLp);
            dots.addView(dot);
        }
        wrap.addView(dots);

        return wrap;
    }

    // ── TARJETAS DE AYUDA ─────────────────────────────────────────────────────

    private View buildTarjetasAyuda() {
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams filaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        filaLp.leftMargin = dp(24);
        filaLp.rightMargin = dp(24);
        filaLp.topMargin = dp(4);
        filaLp.bottomMargin = dp(8);
        fila.setLayoutParams(filaLp);

        fila.addView(buildTarjetaAyuda(
                "💬", "Hablar con IA", "Tu asistente virtual",
                v -> startActivity(new Intent(this, ChatActivity.class))));

        View sep = new View(this);
        sep.setLayoutParams(new LinearLayout.LayoutParams(dp(12), 1));
        fila.addView(sep);

        fila.addView(buildTarjetaAyuda(
                "🔧", "Ver Servicios", "Solicita una visita",
                v -> startActivity(new Intent(this, GuestRequestActivity.class))));

        return fila;
    }

    private View buildTarjetaAyuda(String emoji, String titulo, String sub,
                                    View.OnClickListener click) {
        // Usa GradientDrawable para el borde sutil (CardView no soporta stroke nativo)
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(18), dp(16), dp(18));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), ContextCompat.getColor(this, R.color.lc_borde));
        card.setBackground(bg);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(click);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        card.setForeground(ContextCompat.getDrawable(this, ripple.resourceId));

        // Icono circular rojo pálido
        TextView ic = new TextView(this);
        ic.setText(emoji);
        ic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable icBg = new GradientDrawable();
        icBg.setShape(GradientDrawable.OVAL);
        icBg.setColor(ContextCompat.getColor(this, R.color.lc_cat_bg));
        ic.setBackground(icBg);
        int sz = dp(48);
        card.addView(ic, new LinearLayout.LayoutParams(sz, sz));

        TextView tit = new TextView(this);
        tit.setText(titulo);
        tit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tit.setTypeface(Typeface.DEFAULT_BOLD);
        tit.setTextColor(ContextCompat.getColor(this, R.color.lc_texto));
        LinearLayout.LayoutParams titLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titLp.topMargin = dp(10);
        tit.setLayoutParams(titLp);
        card.addView(tit);

        TextView subTv = new TextView(this);
        subTv.setText(sub);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subTv.setTextColor(ContextCompat.getColor(this, R.color.lc_sec));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        subTv.setLayoutParams(subLp);
        card.addView(subTv);

        return card;
    }

    // ── BOTTOM NAV FLOTANTE ───────────────────────────────────────────────────

    private View buildBottomNav() {
        CardView nav = new CardView(this);
        nav.setRadius(dp(24));
        nav.setCardElevation(dp(8));
        nav.setCardBackgroundColor(Color.WHITE);

        FrameLayout.LayoutParams navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        navLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        navLp.leftMargin = dp(16);
        navLp.rightMargin = dp(16);
        navLp.bottomMargin = dp(16);
        nav.setLayoutParams(navLp);

        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        fila.setGravity(Gravity.CENTER_VERTICAL);
        fila.setPadding(dp(8), 0, dp(8), 0);

        String[][] tabs = {{"🏠", "Inicio"}, {"🎁", "Promos"}, {"📋", "Órdenes"}, {"💬", "Chat"}};

        for (int i = 0; i < tabs.length; i++) {
            fila.addView(buildTabItem(tabs[i][0], tabs[i][1], i == 0, i));
        }

        nav.addView(fila);
        return nav;
    }

    private View buildTabItem(String emoji, String label, boolean activo, int idx) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView ic = new TextView(this);
        ic.setText(emoji);
        ic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        ic.setGravity(Gravity.CENTER);
        tab.addView(ic);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        lbl.setGravity(Gravity.CENTER);
        lbl.setTextColor(activo
                ? ContextCompat.getColor(this, R.color.lc_rojo)
                : ContextCompat.getColor(this, R.color.lc_sec));
        if (activo) lbl.setTypeface(Typeface.DEFAULT_BOLD);
        tab.addView(lbl);

        tab.setOnClickListener(v -> onNavTab(idx));
        tab.setClickable(true);
        tab.setFocusable(true);
        return tab;
    }

    private void onNavTab(int idx) {
        switch (idx) {
            case 0: break; // Inicio — ya estamos aquí
            case 1: Toast.makeText(this, "Promociones · Próximamente", Toast.LENGTH_SHORT).show(); break;
            case 2: irACuenta(); break; // Órdenes requiere cuenta
            case 3: startActivity(new Intent(this, ChatActivity.class)); break;
        }
    }

    // ── NAVEGACIÓN ────────────────────────────────────────────────────────────

    private void irACuenta() {
        if (Api.conSesion(this)) {
            startActivity(new Intent(this, HomeActivity.class));
        } else {
            startActivity(new Intent(this, LoginActivity.class));
        }
    }

    // ── UTILIDADES ────────────────────────────────────────────────────────────

    private View buildEspaciador(int dpH) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(dpH)));
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
