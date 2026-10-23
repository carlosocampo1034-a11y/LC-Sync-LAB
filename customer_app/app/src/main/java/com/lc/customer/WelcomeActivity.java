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
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
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

public class WelcomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Api.conSesion(this)) {
            startActivity(new Intent(this, HomeActivity.class));
        }
    }

    private void buildUI() {
        boolean en = Prefs.en(this);

        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Prefs.fondo(this));

        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        LinearLayout cont = new LinearLayout(this);
        cont.setOrientation(LinearLayout.VERTICAL);
        cont.setBackgroundColor(Prefs.fondo(this));
        sv.addView(cont);
        raiz.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cont.addView(buildCabecera(en));
        cont.addView(buildSaludo(en));
        cont.addView(buildEtiqueta(en ? "Service Categories" : "Categorías de Servicio"));
        cont.addView(buildCategorias(en));
        cont.addView(buildEtiqueta(en ? "Promotions" : "Promociones"));
        cont.addView(buildBannerPromo(en));
        cont.addView(buildEtiqueta(en ? "How can we help you?" : "¿Cómo podemos ayudarte?"));
        cont.addView(buildTarjetasAyuda(en));
        cont.addView(buildEspaciador(108));

        raiz.addView(buildBottomNav(en));

        setContentView(raiz);

        ViewCompat.setOnApplyWindowInsetsListener(raiz, (view, ins) -> {
            Insets sys = ins.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return ins;
        });
    }

    // ── CABECERA ─────────────────────────────────────────────────────────────

    private View buildCabecera(boolean en) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setBackgroundColor(Prefs.fondo(this));
        h.setPadding(dp(24), dp(18), dp(24), dp(14));
        h.setGravity(Gravity.CENTER_VERTICAL);

        TextView logo = new TextView(this);
        logo.setText("L&C");
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setTextColor(Prefs.ROJO);
        h.addView(logo, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Icono ajustes
        ImageView icSettings = new ImageView(this);
        icSettings.setImageResource(R.drawable.ic_settings);
        icSettings.setColorFilter(Prefs.sec(this), PorterDuff.Mode.SRC_IN);
        int icSz = dp(22);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(icSz, icSz);
        icLp.rightMargin = dp(14);
        icLp.gravity = Gravity.CENTER_VERTICAL;
        icSettings.setLayoutParams(icLp);
        icSettings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        h.addView(icSettings);

        TextView miCuenta = new TextView(this);
        miCuenta.setText(en ? "My Account" : "Mi Cuenta");
        miCuenta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        miCuenta.setTypeface(Typeface.DEFAULT_BOLD);
        miCuenta.setTextColor(Prefs.ROJO);
        miCuenta.setPadding(dp(12), dp(8), dp(12), dp(8));
        miCuenta.setOnClickListener(v -> irACuenta());
        h.addView(miCuenta);

        return h;
    }

    // ── SALUDO ────────────────────────────────────────────────────────────────

    private View buildSaludo(boolean en) {
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setPadding(dp(24), dp(4), dp(24), dp(20));

        TextView t1 = new TextView(this);
        t1.setText(en ? "Hi! How can we" : "¡Hola! ¿En qué podemos");
        t1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(Prefs.texto(this));
        s.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText(en ? "help you today?" : "ayudarte hoy?");
        t2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        t2.setTypeface(Typeface.DEFAULT_BOLD);
        t2.setTextColor(Prefs.texto(this));
        s.addView(t2);

        TextView sub = new TextView(this);
        sub.setText(en ? "Find the service you need for your home."
                : "Encuentra el servicio que necesitas para tu hogar.");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setTextColor(Prefs.sec(this));
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
        tv.setTextColor(Prefs.texto(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(24);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(12);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ── CATEGORÍAS ────────────────────────────────────────────────────────────

    private View buildCategorias(boolean en) {
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

        Object[][] cats = en ? new Object[][]{
                {R.drawable.ic_plumbing,   "Plumbing"},
                {R.drawable.ic_electric,   "Electric"},
                {R.drawable.ic_hvac,       "HVAC"},
                {R.drawable.ic_home,       "Home"},
                {R.drawable.ic_appliances, "Appliances"},
                {R.drawable.ic_services,   "See All"}
        } : new Object[][]{
                {R.drawable.ic_plumbing,   "Plomería"},
                {R.drawable.ic_electric,   "Electricidad"},
                {R.drawable.ic_hvac,       "HVAC"},
                {R.drawable.ic_home,       "Hogar"},
                {R.drawable.ic_appliances, "Electrodomésticos"},
                {R.drawable.ic_services,   "Ver Todo"}
        };

        for (int i = 0; i < cats.length; i++) {
            fila.addView(buildIconoCategoria(
                    (int) cats[i][0], (String) cats[i][1], i < cats.length - 1));
        }
        hsv.addView(fila);
        return hsv;
    }

    private View buildIconoCategoria(int iconRes, String label, boolean marginRight) {
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

        int sz = dp(56);
        FrameLayout circle = new FrameLayout(this);
        GradientDrawable circ = new GradientDrawable();
        circ.setShape(GradientDrawable.OVAL);
        circ.setColor(Prefs.catBg(this));
        circle.setBackground(circ);
        circle.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));

        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setColorFilter(Prefs.ROJO, PorterDuff.Mode.SRC_IN);
        int pad = dp(14);
        ic.setPadding(pad, pad, pad, pad);
        circle.addView(ic, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        col.addView(circle);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTextColor(Prefs.texto(this));
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

    private View buildBannerPromo(boolean en) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wLp.leftMargin = dp(24);
        wLp.rightMargin = dp(24);
        wLp.bottomMargin = dp(4);
        wrap.setLayoutParams(wLp);

        CardView card = new CardView(this);
        card.setRadius(dp(16));
        card.setCardElevation(dp(3));
        card.setCardBackgroundColor(Prefs.ROJO);
        card.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(148)));

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(24), dp(20), dp(24), dp(20));
        inner.setGravity(Gravity.CENTER_VERTICAL);

        TextView tit = new TextView(this);
        tit.setText(en ? "Professional home services"
                : "Servicios profesionales para tu hogar");
        tit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tit.setTypeface(Typeface.DEFAULT_BOLD);
        tit.setTextColor(Color.WHITE);
        inner.addView(tit);

        TextView sub = new TextView(this);
        sub.setText(en ? "Plumbing · Electric · HVAC · More"
                : "Plomería · Electricidad · HVAC · Más");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(0xCCFFFFFF);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        sub.setLayoutParams(subLp);
        inner.addView(sub);

        TextView cta = new TextView(this);
        cta.setText(en ? "REQUEST NOW  →" : "SOLICITAR AHORA  →");
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
        return wrap;
    }

    // ── TARJETAS DE AYUDA ─────────────────────────────────────────────────────

    private View buildTarjetasAyuda(boolean en) {
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
                R.drawable.ic_chat,
                en ? "AI Chat" : "Hablar con IA",
                en ? "Your virtual assistant" : "Tu asistente virtual",
                v -> startActivity(new Intent(this, ChatActivity.class))));

        View sep = new View(this);
        sep.setLayoutParams(new LinearLayout.LayoutParams(dp(12), 1));
        fila.addView(sep);

        fila.addView(buildTarjetaAyuda(
                R.drawable.ic_plumbing,
                en ? "Our Services" : "Ver Servicios",
                en ? "Request a visit" : "Solicita una visita",
                v -> startActivity(new Intent(this, GuestRequestActivity.class))));

        return fila;
    }

    private View buildTarjetaAyuda(int iconRes, String titulo, String sub,
                                    View.OnClickListener click) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(18), dp(16), dp(18));
        card.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Prefs.fondoCard(this));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Prefs.borde(this));
        card.setBackground(bg);
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(click);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        card.setForeground(ContextCompat.getDrawable(this, ripple.resourceId));

        int sz = dp(48);
        FrameLayout circle = new FrameLayout(this);
        GradientDrawable icBg = new GradientDrawable();
        icBg.setShape(GradientDrawable.OVAL);
        icBg.setColor(Prefs.catBg(this));
        circle.setBackground(icBg);
        circle.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));

        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setColorFilter(Prefs.ROJO, PorterDuff.Mode.SRC_IN);
        int pad = dp(11);
        ic.setPadding(pad, pad, pad, pad);
        circle.addView(ic, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(circle);

        TextView tit = new TextView(this);
        tit.setText(titulo);
        tit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tit.setTypeface(Typeface.DEFAULT_BOLD);
        tit.setTextColor(Prefs.texto(this));
        LinearLayout.LayoutParams titLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titLp.topMargin = dp(10);
        tit.setLayoutParams(titLp);
        card.addView(tit);

        TextView subTv = new TextView(this);
        subTv.setText(sub);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subTv.setTextColor(Prefs.sec(this));
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        subTv.setLayoutParams(subLp);
        card.addView(subTv);

        return card;
    }

    // ── BOTTOM NAV FLOTANTE ───────────────────────────────────────────────────

    private View buildBottomNav(boolean en) {
        CardView nav = new CardView(this);
        nav.setRadius(dp(24));
        nav.setCardElevation(dp(8));
        nav.setCardBackgroundColor(Prefs.navBg(this));

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

        int[] tabIcons = {
                R.drawable.ic_home,
                R.drawable.ic_orders,
                R.drawable.ic_chat,
                R.drawable.ic_settings
        };
        String[] labels = en
                ? new String[]{"Home", "Orders", "Chat", "Settings"}
                : new String[]{"Inicio", "Órdenes", "Chat", "Ajustes"};

        for (int i = 0; i < tabIcons.length; i++) {
            fila.addView(buildTabItem(tabIcons[i], labels[i], i == 0, i, en));
        }

        nav.addView(fila);
        return nav;
    }

    private View buildTabItem(int iconRes, String label, boolean activo, int idx, boolean en) {
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        int iconColor = activo ? Prefs.ROJO : Prefs.sec(this);

        ImageView ic = new ImageView(this);
        ic.setImageResource(iconRes);
        ic.setColorFilter(iconColor, PorterDuff.Mode.SRC_IN);
        int iconSz = dp(22);
        tab.addView(ic, new LinearLayout.LayoutParams(iconSz, iconSz));

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        lbl.setGravity(Gravity.CENTER);
        lbl.setTextColor(iconColor);
        if (activo) lbl.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lblLp.topMargin = dp(2);
        lbl.setLayoutParams(lblLp);
        tab.addView(lbl);

        tab.setOnClickListener(v -> onNavTab(idx, en));
        tab.setClickable(true);
        tab.setFocusable(true);
        return tab;
    }

    private void onNavTab(int idx, boolean en) {
        switch (idx) {
            case 0: break;
            case 1: irACuenta(); break;
            case 2: startActivity(new Intent(this, ChatActivity.class)); break;
            case 3: startActivity(new Intent(this, SettingsActivity.class)); break;
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
