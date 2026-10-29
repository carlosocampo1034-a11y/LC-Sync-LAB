package com.lc.technician;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Barra de navegación inferior del Técnico: Trabajos · Estadísticas · Perfil. Se agrega sin tocar el layout de cada pantalla. */
public final class BottomNav {
    public static final int TRABAJOS = 0, ESTADISTICAS = 1, PERFIL = 2;
    private BottomNav() {}

    public static void attach(Activity a, int seleccionado) {
        ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0 || content.findViewWithTag("lc_bottom_nav") != null) return;
        View raiz = content.getChildAt(0);
        content.removeView(raiz);

        LinearLayout envoltura = new LinearLayout(a);
        envoltura.setOrientation(LinearLayout.VERTICAL);
        envoltura.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        raiz.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
        envoltura.addView(raiz);

        LinearLayout barra = new LinearLayout(a);
        barra.setTag("lc_bottom_nav");
        barra.setOrientation(LinearLayout.HORIZONTAL);
        barra.setBackgroundColor(0xFFFFFFFF);
        barra.setElevation(a.getResources().getDisplayMetrics().density * 8);
        String[] ico = {"🏠", "📊", "👤"};
        String[] txt = {"Trabajos", "Estadísticas", "Perfil"};
        final Class<?>[] destino = {MainActivity.class, EstadisticasActivity.class, ProfileActivity.class};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = new TextView(a);
            t.setText(ico[i] + "\n" + txt[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(11f);
            t.setPadding(0, dp(a, 8), 0, dp(a, 8));
            boolean activo = i == seleccionado;
            t.setTextColor(activo ? 0xFFE91605 : 0xFF62666A);
            t.setTypeface(null, activo ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            t.setOnClickListener(v -> {
                if (idx == seleccionado) return;
                Intent in = new Intent(a, destino[idx]);
                if (idx == TRABAJOS) in.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                else in.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                a.startActivity(in);
                a.overridePendingTransition(0, 0);
                if (seleccionado != TRABAJOS && idx != TRABAJOS) a.finish();   // entre pestañas secundarias no se apila
            });
            barra.addView(t);
        }
        // La barra inferior se encarga del inset de navegación: el contenido de arriba lo recibe ya descontado (sin franja doble)
        final int base = barra.getPaddingBottom();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(envoltura, (v, ins) -> {
            androidx.core.graphics.Insets b = ins.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            barra.setPadding(0, barra.getPaddingTop(), 0, base + b.bottom);
            androidx.core.view.WindowInsetsCompat resto = new androidx.core.view.WindowInsetsCompat.Builder(ins)
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(),
                            androidx.core.graphics.Insets.of(b.left, b.top, b.right, 0))
                    .build();
            return resto;
        });
        envoltura.addView(barra, new LinearLayout.LayoutParams(-1, -2));
        content.addView(envoltura);
        envoltura.requestApplyInsets();
    }

    private static int dp(Activity a, int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }
}
