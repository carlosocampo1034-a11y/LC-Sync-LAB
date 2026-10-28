package com.lc.technician;

import android.app.Application;
import com.lc.technician.data.JobRepository;

public class LcApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        JobRepository.init(this);
        // Iconos de barras legibles también con el modo oscuro forzado del sistema
        SystemBarContrast.install(this);
        // Resiliencia de red (Orden 29): escuchar cambios de red, banner «Reconectando…» y vaciar colas al volver la conexión
        Red.iniciar(this);
        RedBanner.instalar(this);
        Red.alVolver(() -> {
            ColaCierre.vaciar(this);
            if (com.lc.technician.sync.SupabaseClient.get(this).hasUserSession()) {
                androidx.work.WorkManager.getInstance(this).enqueue(
                        new androidx.work.OneTimeWorkRequest.Builder(com.lc.technician.worker.SyncWorker.class).build());
            }
        });
        if (ColaCierre.pendientes(this) > 0) Red.vaciarColasYa();
    }
}
