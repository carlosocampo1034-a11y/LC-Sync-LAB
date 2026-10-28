package com.lc.customer;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.WeakHashMap;

/**
 * Banner discreto «Reconectando…» que se añade solo a la parte superior de cada pantalla mientras la red está caída o
 * recuperándose, y desaparece cuando vuelve. No cambia ni recrea la pantalla: el usuario conserva lo que estaba haciendo.
 */
public final class RedBanner implements Application.ActivityLifecycleCallbacks {

    private final WeakHashMap<Activity, TextView> banners = new WeakHashMap<>();
    private final WeakHashMap<Activity, Runnable> oyentes = new WeakHashMap<>();

    public static void instalar(Application app) { app.registerActivityLifecycleCallbacks(new RedBanner()); }

    @Override public void onActivityResumed(Activity a) {
        final FrameLayout raiz = a.findViewById(android.R.id.content);
        if (raiz == null) return;
        TextView b = banners.get(a);
        if (b == null) {
            b = new TextView(a);
            b.setText("Reconectando…");
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(0xE6171717);
            b.setTextSize(12f);
            b.setGravity(Gravity.CENTER);
            b.setClickable(false);
            b.setFocusable(false);
            float d = a.getResources().getDisplayMetrics().density;
            int barra = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, Math.round(26 * d), Gravity.TOP);
            lp.topMargin = barra > 0 ? a.getResources().getDimensionPixelSize(barra) : Math.round(24 * d);
            b.setLayoutParams(lp);
            b.setElevation(24 * d);
            b.setVisibility(View.GONE);
            raiz.addView(b);
            banners.put(a, b);
        }
        final TextView banner = b;
        Runnable r = () -> banner.setVisibility(Red.reconectando() ? View.VISIBLE : View.GONE);
        oyentes.put(a, r);
        Red.oyente(r);
        r.run();
    }

    @Override public void onActivityPaused(Activity a) {
        Runnable r = oyentes.remove(a);
        if (r != null) Red.quitarOyente(r);
    }

    @Override public void onActivityCreated(Activity a, Bundle s) { }
    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle s) { }
    @Override public void onActivityDestroyed(Activity a) { }
}
