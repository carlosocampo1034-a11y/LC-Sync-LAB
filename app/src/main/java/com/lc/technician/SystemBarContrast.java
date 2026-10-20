package com.lc.technician;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Iconos de las barras del sistema legibles sobre lo que la app dibuja de verdad debajo.
 *
 * El tema fija windowLightStatusBar=true (iconos oscuros) porque la app es clara. Pero con el
 * modo oscuro forzado de Android/Samsung (force dark) el sistema oscurece el contenido y los
 * iconos quedaban oscuros sobre oscuro. En lugar de adivinar por la configuración, se mide el
 * píxel real que la app dibuja bajo cada barra (PixelCopy de la propia ventana, sin los iconos)
 * y se eligen iconos oscuros sobre fondo claro o claros sobre fondo oscuro.
 * En modo claro el resultado es el mismo que antes (iconos oscuros).
 */
final class SystemBarContrast implements Application.ActivityLifecycleCallbacks {

    private static final float UMBRAL_LUMINANCIA = 0.5f;
    private final Handler main = new Handler(Looper.getMainLooper());

    static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new SystemBarContrast());
    }

    @Override public void onActivityResumed(Activity a) {
        // Tras el primer dibujado; se repite una vez por si el contenido carga después
        View decor = a.getWindow().getDecorView();
        decor.post(() -> main.postDelayed(() -> medir(a), 120));
        main.postDelayed(() -> medir(a), 900);
    }

    private void medir(Activity a) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || a.isFinishing() || a.isDestroyed()) return;
        Window w = a.getWindow();
        View decor = w.getDecorView();
        if (!decor.isAttachedToWindow() || decor.getWidth() == 0) return;
        WindowInsetsCompat ins = ViewCompat.getRootWindowInsets(decor);
        int top = ins != null ? ins.getInsets(WindowInsetsCompat.Type.statusBars()).top : 0;
        int bottom = ins != null ? ins.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom : 0;
        int ancho = decor.getWidth();
        int alto = decor.getHeight();
        // Franja bajo la barra de estado (sin los iconos: PixelCopy solo copia la ventana de la app)
        muestrear(a, w, new Rect(0, 0, ancho, Math.max(1, top)), claro -> {
            if (claro != null) new WindowInsetsControllerCompat(w, decor).setAppearanceLightStatusBars(claro);
        });
        if (bottom > 0) {
            muestrear(a, w, new Rect(0, alto - bottom, ancho, alto), claro -> {
                if (claro != null) new WindowInsetsControllerCompat(w, decor).setAppearanceLightNavigationBars(claro);
            });
        }
    }

    interface Resultado { void fondoClaro(Boolean claro); }

    private void muestrear(Activity a, Window w, Rect zona, Resultado r) {
        try {
            // Muestra reducida: suficiente para la luminancia media y barato
            Bitmap bmp = Bitmap.createBitmap(Math.max(1, zona.width() / 8), Math.max(1, zona.height() / 4),
                    Bitmap.Config.ARGB_8888);
            PixelCopy.request(w, zona, bmp, res -> {
                if (res != PixelCopy.SUCCESS || a.isDestroyed()) { r.fondoClaro(null); return; }
                double suma = 0;
                int n = 0;
                for (int y = 0; y < bmp.getHeight(); y++) {
                    for (int x = 0; x < bmp.getWidth(); x++) {
                        int c = bmp.getPixel(x, y);
                        suma += (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
                        n++;
                    }
                }
                bmp.recycle();
                r.fondoClaro(n > 0 && suma / n > UMBRAL_LUMINANCIA);
            }, main);
        } catch (Exception e) {
            r.fondoClaro(null);  // sin cambios: se mantiene lo que diga el tema
        }
    }

    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityPaused(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
    @Override public void onActivityDestroyed(Activity a) {}
}
