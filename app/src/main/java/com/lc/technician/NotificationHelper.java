package com.lc.technician;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class NotificationHelper {

    static final String CHANNEL_ID = "lc_jobs";
    static final String CHANNEL_NAME = "Recordatorios de Jobs";
    static int NOTIF_ID = 1000;

    static void crearCanalNotificaciones(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Recordatorios de trabajos programados");
            NotificationManager manager = ctx.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    static void mostrarRecordatorioJob(Context ctx, long jobId, String cliente, String descripcion) {
        Intent intent = new Intent(ctx, JobDetailActivity.class);
        intent.putExtra("job_id", jobId);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                ctx,
                (int) jobId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String texto = (descripcion != null) ? descripcion : "Trabajo pendiente";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Recordatorio: " + cliente)
                .setContentText(texto)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID++, builder.build());
        } catch (SecurityException e) {
            e.printStackTrace();
        }
    }

    // Phase 54: Rich push notifications

    public static void notifyJobAssignedRich(Context ctx, long jobId, String cliente, String hora, String direccion) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("lc_jobs_rich",
                "Jobs (Rich)", NotificationManager.IMPORTANCE_HIGH);
            ch.enableVibration(true);
            ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Intent intent = new Intent(ctx, JobDetailActivity.class);
        intent.putExtra("jobId", jobId);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, (int) jobId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent dialIntent = new Intent(Intent.ACTION_DIAL);
        PendingIntent dialPi = PendingIntent.getActivity(ctx, (int) jobId + 10000, dialIntent,
            PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, "lc_jobs_rich")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Nuevo Job Asignado")
            .setContentText(cliente + " \u00b7 " + hora)
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText("Cliente: " + cliente + "\nHora: " + hora + "\nDirecci\u00f3n: " + direccion))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_view, "VER JOB", pi)
            .addAction(android.R.drawable.sym_action_call, "LLAMAR", dialPi)
            .setAutoCancel(true);
        try { nm.notify((int) jobId, builder.build()); } catch (SecurityException ignored) {}
    }

    public static void notifyMembershipExpiringRich(Context ctx, String cardNumber, int daysLeft, String clienteName) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel("lc_jobs_rich",
                "Jobs (Rich)", NotificationManager.IMPORTANCE_HIGH);
            ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, "lc_jobs_rich")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Membres\u00eda por vencer")
            .setContentText(clienteName + " \u00b7 " + daysLeft + " d\u00edas")
            .setStyle(new NotificationCompat.BigTextStyle()
                .bigText("Tarjeta: " + cardNumber + "\nCliente: " + clienteName
                    + "\nVence en: " + daysLeft + " d\u00edas"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true);
        try { nm.notify(cardNumber.hashCode(), builder.build()); } catch (SecurityException ignored) {}
    }
}
