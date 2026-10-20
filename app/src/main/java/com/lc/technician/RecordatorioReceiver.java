package com.lc.technician;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class RecordatorioReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        long jobId = intent.getLongExtra("job_id", -1);
        String cliente = intent.getStringExtra("cliente");
        String descripcion = intent.getStringExtra("descripcion");

        NotificationHelper.crearCanalNotificaciones(context);
        NotificationHelper.mostrarRecordatorioJob(context, jobId, cliente, descripcion);
    }
}
