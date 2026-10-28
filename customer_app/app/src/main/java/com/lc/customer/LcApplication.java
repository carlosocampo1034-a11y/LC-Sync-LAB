package com.lc.customer;

import android.app.Application;

/** Aplicación del Cliente: escucha la red (Wi-Fi ↔ datos), muestra «Reconectando…» y vacía los envíos en cola al volver la conexión. */
public class LcApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Red.iniciar(this);
        RedBanner.instalar(this);
        Red.alVolver(() -> ColaCliente.vaciar(this));
        if (ColaCliente.pendientes(this) > 0) Red.vaciarColasYa();
    }
}
