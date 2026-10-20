package com.lc.technician.util;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.lc.technician.JobDetailActivity;

public class NotificationHelper {
    private static final String CHANNEL_JOBS = "lc_jobs";
    private static final String CHANNEL_REQUESTS = "lc_requests";
    private static final String CHANNEL_REMINDERS = "lc_reminders";
    private static final String CHANNEL_MEMBERSHIP = "lc_membership_expiry";
    private static final String CHANNEL_MEMBERSHIP_RENEWAL = "lc_membership_renewal";

    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_JOBS, "Jobs asignados", NotificationManager.IMPORTANCE_HIGH));
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_REQUESTS, "Solicitudes", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_REMINDERS, "Recordatorios", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_MEMBERSHIP, "Vencimiento de membresia", NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_MEMBERSHIP_RENEWAL, "Renovacion de membresia", NotificationManager.IMPORTANCE_DEFAULT));
    }

    public static void notificarJobAsignado(Context ctx, long jobId, String cliente, String hora, boolean reasignado) {
        ensureChannels(ctx);

        Intent i = new Intent(ctx, JobDetailActivity.class);
        i.putExtra("job_id", jobId);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(
                ctx, (int) jobId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = reasignado ? "Trabajo reasignado" : "Nuevo trabajo asignado";
        String text = (hora != null ? hora + " · " : "") + (cliente != null ? cliente : "");

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_JOBS)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Trabajo #" + jobId + "\n" + text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((int) (1000 + jobId), b.build());
    }

    public static void notifyMembershipExpiry(Context ctx, String cardNumber,
                                               String customerName, int daysLeft) {
        ensureChannels(ctx);

        Intent i = new Intent(ctx, com.lc.technician.ConfiguracionActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pi = PendingIntent.getActivity(
                ctx, cardNumber.hashCode(), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = daysLeft <= 0
                ? "Membresia vencida"
                : "Membresia proxima a vencer";
        String text = daysLeft <= 0
                ? customerName + " \u00b7 " + cardNumber + " VENCIDA"
                : customerName + " \u00b7 " + cardNumber + " vence en " + daysLeft + " dias";

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_MEMBERSHIP)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pi);

        NotificationManager nm = (NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(cardNumber.hashCode(), b.build());
    }

    // Fase 42: Explicit expiry notification methods
    public static void notifyMembershipExpiringSoon(Context ctx, String cardNumber, int daysLeft) {
        ensureChannels(ctx);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_MEMBERSHIP)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Membresía vence pronto")
                .setContentText(cardNumber + " vence en " + daysLeft + " días")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        android.app.NotificationManager nm = (android.app.NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((int)(System.currentTimeMillis() % 100000), builder.build());
    }

    public static void notifyMembershipExpired(Context ctx, String cardNumber, long daysOverdue) {
        ensureChannels(ctx);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_MEMBERSHIP)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Membresía VENCIDA")
                .setContentText(cardNumber + " venció hace " + Math.abs(daysOverdue) + " días")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);
        android.app.NotificationManager nm = (android.app.NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((int)(System.currentTimeMillis() % 100000), builder.build());
    }

    public static void notificarSolicitudResuelta(Context ctx, long reqId, String estado, String cliente) {
        ensureChannels(ctx);

        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_REQUESTS)
                .setSmallIcon(android.R.drawable.ic_menu_agenda)
                .setContentTitle("Solicitud " + estado)
                .setContentText("Solicitud #" + reqId + " · " + cliente)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((int) (2000 + reqId), b.build());
    }

    // Fase 44: Push notifications via polling
    public static void notifyJobAssigned(Context ctx, String cliente, String hora, long jobId) {
        ensureChannels(ctx);
        Intent intent = new Intent(ctx, JobDetailActivity.class);
        intent.putExtra("jobId", jobId);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, (int) jobId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_JOBS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Nuevo trabajo asignado")
            .setContentText(cliente + (hora != null ? " · " + hora : ""))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((int)(System.currentTimeMillis() % 100000), builder.build());
    }

    public static void notifyMembershipRenewed(Context ctx, long cardId) {
        ensureChannels(ctx);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_MEMBERSHIP_RENEWAL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Membresia renovada")
            .setContentText("Membresia #" + cardId + " renovada exitosamente")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true);

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((int)(System.currentTimeMillis() % 100000), builder.build());
    }
}
