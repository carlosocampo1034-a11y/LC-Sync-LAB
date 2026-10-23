package com.lc.customer;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Pantalla de bienvenida — L&C Home Services
 * Modo invitado: accesible sin login.
 * Acciones: Solicitar Servicio · Enviar Foto · Hablar o Escribir
 */
public class WelcomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        // ── Raíz ────────────────────────────────────────────────────────────
        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Color.WHITE);

        // ── Cabecera ─────────────────────────────────────────────────────────
        FrameLayout cab = new FrameLayout(this);
        cab.setBackgroundColor(Color.WHITE);
        int h20 = dp(20), h16 = dp(16);
        cab.setPadding(h20, dp(22), h20, dp(14));

        // Título + subtítulo
        LinearLayout titulos = new LinearLayout(this);
        titulos.setOrientation(LinearLayout.VERTICAL);
        titulos.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));

        TextView marca = new TextView(this);
        marca.setText("L&C Home Services");
        marca.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        marca.setTypeface(Typeface.DEFAULT_BOLD);
        marca.setTextColor(ContextCompat.getColor(this, R.color.lc_texto));
        titulos.addView(marca);

        TextView tagline = new TextView(this);
        tagline.setText(R.string.tagline);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tagline.setTextColor(ContextCompat.getColor(this, R.color.lc_sec));
        titulos.addView(tagline);
        cab.addView(titulos);

        // Botón Mi Cuenta (esquina superior derecha)
        TextView miCuenta = new TextView(this);
        miCuenta.setText("👤  Mi Cuenta");
        miCuenta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        miCuenta.setTypeface(Typeface.DEFAULT_BOLD);
        miCuenta.setTextColor(ContextCompat.getColor(this, R.color.lc_rojo));
        miCuenta.setPadding(dp(12), dp(8), dp(12), dp(8));
        FrameLayout.LayoutParams miCuentaLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
        cab.addView(miCuenta, miCuentaLp);
        miCuenta.setOnClickListener(v -> irACuenta());

        raiz.addView(cab);

        // Línea separadora
        View linea = new View(this);
        linea.setBackgroundColor(ContextCompat.getColor(this, R.color.lc_borde));
        raiz.addView(linea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // ── Contenido desplazable ────────────────────────────────────────────
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        LinearLayout cont = new LinearLayout(this);
        cont.setOrientation(LinearLayout.VERTICAL);
        cont.setPadding(h20, h16, h20, dp(32));
        sv.addView(cont);
        raiz.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Burbuja del asistente IA
        cont.addView(burbuja());

        // Separador
        cont.addView(espacio(12));

        // Tarjeta SOLICITAR SERVICIO
        cont.addView(tarjetaAccion(
                "🔧",
                "SOLICITAR SERVICIO",
                "Visita, reparación, diagnóstico o mantenimiento",
                true,
                v -> startActivity(new Intent(this, GuestRequestActivity.class))));

        // Tarjeta ENVIAR FOTO
        cont.addView(tarjetaAccion(
                "📷",
                "ENVIAR FOTO",
                getString(R.string.accion_foto_sub),
                false,
                v -> {
                    Intent i = new Intent(this, GuestRequestActivity.class);
                    i.putExtra("open_camera", true);
                    startActivity(i);
                }));

        // Tarjeta HABLAR O ESCRIBIR
        cont.addView(tarjetaAccion(
                "💬",
                "HABLAR O ESCRIBIR",
                getString(R.string.accion_chat_sub),
                false,
                v -> startActivity(new Intent(this, ChatActivity.class))));

        // Promo
        cont.addView(espacio(20));
        cont.addView(tarjetaPromo());

        setContentView(raiz);

        // Edge-to-edge
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
        // Si el usuario inició sesión en otra pantalla, ir directo a Home
        if (Api.conSesion(this)) {
            startActivity(new Intent(this, HomeActivity.class));
            // No llamamos finish() — el usuario puede volver con el botón Atrás
        }
    }

    // ── Constructores de vistas ──────────────────────────────────────────────

    private LinearLayout burbuja() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(redondeado(ContextCompat.getColor(this, R.color.lc_ai_bubble),
                ContextCompat.getColor(this, R.color.lc_ai_borde), dp(14)));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        card.setLayoutParams(lp);

        TextView icono = new TextView(this);
        icono.setText("✨");
        icono.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        icono.setPadding(0, 0, dp(10), 0);
        card.addView(icono);

        TextView msg = new TextView(this);
        msg.setText(getString(R.string.ai_saludo));
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        msg.setTextColor(ContextCompat.getColor(this, R.color.lc_texto));
        msg.setLineSpacing(0, 1.3f);
        card.addView(msg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return card;
    }

    private LinearLayout tarjetaAccion(String icono, String titulo, String sub,
                                       boolean principal, View.OnClickListener click) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        int bg = principal
                ? ContextCompat.getColor(this, R.color.lc_rojo)
                : Color.WHITE;
        int borde = principal
                ? ContextCompat.getColor(this, R.color.lc_rojo)
                : ContextCompat.getColor(this, R.color.lc_borde);
        card.setBackground(redondeado(bg, borde, dp(14)));
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);

        // Icono circular
        TextView iconoV = new TextView(this);
        iconoV.setText(icono);
        iconoV.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        iconoV.setGravity(Gravity.CENTER);
        int iconBg = principal
                ? 0x22FFFFFF
                : ContextCompat.getColor(this, R.color.lc_card_icon_bg);
        iconoV.setBackground(circulo(iconBg));
        int s = dp(52);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(s, s);
        iconLp.gravity = Gravity.CENTER_VERTICAL;
        iconLp.rightMargin = dp(14);
        card.addView(iconoV, iconLp);

        // Textos
        LinearLayout textos = new LinearLayout(this);
        textos.setOrientation(LinearLayout.VERTICAL);
        textos.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(titulo);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(principal ? Color.WHITE
                : ContextCompat.getColor(this, R.color.lc_texto));
        textos.addView(t);

        if (!TextUtils.isEmpty(sub)) {
            TextView s2 = new TextView(this);
            s2.setText(sub);
            s2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            s2.setTextColor(principal ? 0xCCFFFFFF
                    : ContextCompat.getColor(this, R.color.lc_sec));
            LinearLayout.LayoutParams s2Lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            s2Lp.topMargin = dp(3);
            s2.setLayoutParams(s2Lp);
            textos.addView(s2);
        }
        card.addView(textos);

        // Flecha
        TextView flecha = new TextView(this);
        flecha.setText("›");
        flecha.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        flecha.setTextColor(principal ? Color.WHITE
                : ContextCompat.getColor(this, R.color.lc_sec));
        flecha.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        fLp.gravity = Gravity.CENTER_VERTICAL;
        card.addView(flecha, fLp);

        card.setOnClickListener(click);
        card.setClickable(true);
        card.setFocusable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        card.setForeground(ContextCompat.getDrawable(this,
                principal ? android.R.drawable.list_selector_background : ripple.resourceId));

        return card;
    }

    private LinearLayout tarjetaPromo() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBackground(redondeado(
                ContextCompat.getColor(this, R.color.lc_suave),
                ContextCompat.getColor(this, R.color.lc_borde), dp(12)));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        TextView txt = new TextView(this);
        txt.setText(R.string.promo_intro);
        txt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        txt.setTextColor(ContextCompat.getColor(this, R.color.lc_texto));
        txt.setLineSpacing(0, 1.3f);
        card.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return card;
    }

    private View espacio(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(dp)));
        return v;
    }

    // ── Navegación ────────────────────────────────────────────────────────────

    private void irACuenta() {
        if (Api.conSesion(this)) {
            startActivity(new Intent(this, HomeActivity.class));
        } else {
            startActivity(new Intent(this, LoginActivity.class));
        }
    }

    // ── Helpers de drawable ──────────────────────────────────────────────────

    private android.graphics.drawable.Drawable redondeado(int fill, int borde, int radio) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        gd.setCornerRadius(radio);
        gd.setColor(fill);
        gd.setStroke(dp(1), borde);
        return gd;
    }

    private android.graphics.drawable.Drawable circulo(int fill) {
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(fill);
        return gd;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
