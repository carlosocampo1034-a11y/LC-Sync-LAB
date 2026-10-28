package com.lc.customer;

import android.app.Application;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Estado de la red de la app (Orden 29). Escucha los cambios del sistema con ConnectivityManager.NetworkCallback: al pasar de
 * Wi-Fi a datos (o al revés) la app no se cae ni muestra un error; muestra el banner «Reconectando…» y, cuando la red vuelve,
 * comprueba el servidor y vacía las colas de envíos pendientes. Las peticiones reintentan con espera exponencial (1 s, 2 s, 4 s).
 */
public final class Red {

    private static final String TAG = "LcRed";
    private static volatile boolean disponible = true;
    private static volatile int fallos;
    private static final CopyOnWriteArrayList<Runnable> oyentes = new CopyOnWriteArrayList<>();      // cambian el banner (hilo principal)
    private static final CopyOnWriteArrayList<Runnable> alVolver = new CopyOnWriteArrayList<>();     // colas que se vacían al volver la red
    private static final CopyOnWriteArrayList<Runnable> alCambiar = new CopyOnWriteArrayList<>();    // p. ej. cerrar conexiones viejas
    private static final Object cerrojo = new Object();
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static final ExecutorService fondo = Executors.newSingleThreadExecutor();
    private static boolean iniciada;

    private Red() {}

    public static synchronized void iniciar(Application app) {
        if (iniciada) return;
        iniciada = true;
        final ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        disponible = tieneInternet(cm, cm.getActiveNetwork());
        try {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { cambio(app, true); }
                @Override public void onLost(Network n) { cambio(app, false); }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                    boolean ok = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                    if (ok != disponible) cambio(app, ok);
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "no se pudo escuchar la red: " + e.getClass().getSimpleName());
        }
    }

    private static boolean tieneInternet(ConnectivityManager cm, Network n) {
        if (n == null) return false;
        NetworkCapabilities c = cm.getNetworkCapabilities(n);
        return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private static void cambio(Application app, boolean ahora) {
        disponible = ahora;
        synchronized (cerrojo) { cerrojo.notifyAll(); }
        for (Runnable r : alCambiar) { try { r.run(); } catch (Exception ignored) { } }   // las conexiones de la red anterior ya no sirven
        avisar();
        if (ahora) {
            // red nueva: comprobar que el servidor responde y vaciar lo que quedó en cola
            fondo.execute(() -> {
                if (!sonda()) fallo();
                for (Runnable r : alVolver) { try { r.run(); } catch (Exception ignored) { } }
            });
        }
    }

    /** Comprueba que el servidor responde. true = hay conexión real (quita el banner). */
    private static boolean sonda() {
        HttpURLConnection con = null;
        try {
            String base = BuildConfig.SUPABASE_URL;
            if (base == null || base.isEmpty()) { exito(); return true; }
            con = (HttpURLConnection) new URL(base + "/functions/v1/lc-api/health").openConnection();
            con.setConnectTimeout(6000);
            con.setReadTimeout(6000);
            con.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
            if (con.getResponseCode() == 200) { exito(); return true; }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static volatile boolean sondaProgramada;

    /** Mientras haya fallos sin recuperar, se vuelve a comprobar el servidor cada 5 s; al responder se vacían las colas. */
    private static void programarSonda() {
        if (sondaProgramada) return;
        sondaProgramada = true;
        ui.postDelayed(() -> fondo.execute(() -> {
            sondaProgramada = false;
            if (fallos == 0) return;
            if (sonda()) { for (Runnable r : alVolver) { try { r.run(); } catch (Exception ignored) { } } }
            else programarSonda();
        }), 5000);
    }

    /** Hay que mostrar «Reconectando…»: sin red, o con peticiones que fallaron y todavía no se han recuperado. */
    public static boolean reconectando() { return !disponible || fallos > 0; }

    public static void exito() { if (fallos != 0) { fallos = 0; avisar(); } }

    public static void fallo() { fallos++; avisar(); programarSonda(); }

    /** Bloquea (en un hilo de fondo) hasta que haya red o venza el plazo. */
    public static boolean esperarRed(long ms) {
        long fin = System.currentTimeMillis() + ms;
        synchronized (cerrojo) {
            while (!disponible) {
                long resta = fin - System.currentTimeMillis();
                if (resta <= 0) return false;
                try { cerrojo.wait(resta); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
        }
        return true;
    }

    public static void oyente(Runnable r) { if (r != null) oyentes.add(r); }
    public static void quitarOyente(Runnable r) { oyentes.remove(r); }
    public static void alVolver(Runnable r) { if (r != null) alVolver.add(r); }
    public static void alCambiar(Runnable r) { if (r != null) alCambiar.add(r); }

    /** Lanza ya las tareas de «al volver la red» (por ejemplo al abrir la app con envíos en cola). */
    public static void vaciarColasYa() {
        fondo.execute(() -> { for (Runnable r : alVolver) { try { r.run(); } catch (Exception ignored) { } } });
    }

    private static void avisar() {
        ui.post(() -> { for (Runnable r : oyentes) { try { r.run(); } catch (Exception ignored) { } } });
    }
}
