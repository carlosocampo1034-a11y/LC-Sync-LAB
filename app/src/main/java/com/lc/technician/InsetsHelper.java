package com.lc.technician;

import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Edge-to-edge (targetSdk 36 / Android 15+): suma las barras del sistema al
 * padding ORIGINAL de la vista, guardado una sola vez, para no acumular espacio
 * en cada nuevo dispatch de insets. Mismo patrón que bottomBar en JobDetailActivity.
 */
public final class InsetsHelper {

    private InsetsHelper() {}

    /** Cabecera: padding superior original + barra de estado / recorte de cámara. */
    public static void padTop(View v) {
        if (v == null) return;
        final int origTop = v.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(view.getPaddingLeft(), origTop + bars.top,
                    view.getPaddingRight(), view.getPaddingBottom());
            return insets;
        });
    }

    /**
     * Cabecera RelativeLayout wrap_content con hijos centerVertical: sumar padding
     * desplaza mal el contenido (recorta el título), así que se usa margen superior.
     */
    public static void marginTop(View v) {
        if (v == null || !(v.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) return;
        final int origTop = ((ViewGroup.MarginLayoutParams) v.getLayoutParams()).topMargin;
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            if (lp.topMargin != origTop + bars.top) {
                lp.topMargin = origTop + bars.top;
                view.setLayoutParams(lp);
            }
            return insets;
        });
    }

    /**
     * Raíz de un formulario: arriba la barra de estado; abajo la navegación o el teclado,
     * lo que sea mayor. Así el botón inferior queda sobre el teclado y el ScrollView se
     * encoge en vez de desplazarse la ventana entera (requiere SOFT_INPUT_ADJUST_RESIZE).
     */
    public static void padSystemBarsAndIme(View v) {
        if (v == null) return;
        final int origTop = v.getPaddingTop();
        final int origBottom = v.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            view.setPadding(view.getPaddingLeft(), origTop + bars.top,
                    view.getPaddingRight(), origBottom + Math.max(bars.bottom, ime));
            return insets;
        });
    }

    /** Barra inferior o lista con clipToPadding=false: padding inferior original + barra de navegación. */
    public static void padBottom(View v) {
        if (v == null) return;
        final int origBottom = v.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(v, (view, insets) -> {
            int nav = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), origBottom + nav);
            return insets;
        });
    }
}
