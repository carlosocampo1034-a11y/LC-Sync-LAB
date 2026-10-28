package com.lc.technician.sync;

import com.lc.technician.Red;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Reintenta una petición que falla por la red (cambio de Wi-Fi a datos, túnel, señal perdida): hasta 3 intentos con espera
 * exponencial (1 s, 2 s, 4 s) y esperando a que vuelva la red antes de cada reintento. Solo falla después del último intento.
 * Las peticiones críticas son idempotentes en el servidor (request_id, clientPhotoId, clave de pago), así que reintentar no duplica.
 */
final class ReintentoInterceptor implements Interceptor {

    private static final int INTENTOS = 3;

    @Override public Response intercept(Chain chain) throws IOException {
        Request req = chain.request();
        IOException ultimo = null;
        for (int intento = 1; intento <= INTENTOS; intento++) {
            try {
                Response r = chain.proceed(req);
                Red.exito();
                return r;
            } catch (IOException e) {
                ultimo = e;
                Red.fallo();
                if (chain.call().isCanceled() || intento == INTENTOS) break;
                try { Thread.sleep(1000L << (intento - 1)); }          // 1 s, 2 s
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException("interrumpido", ie); }
                Red.esperarRed(8000);                                    // si no hay red, espera a que vuelva
            }
        }
        throw ultimo != null ? ultimo : new IOException("sin respuesta");
    }
}
