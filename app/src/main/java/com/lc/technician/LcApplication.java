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
    }
}
