package com.lc.technician.push;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.lc.technician.MainActivity;

import java.util.List;
import java.util.Map;

/**
 * Notificaciones push (FCM). El servidor envía solo mensajes de DATOS, así que llegan aquí con la app abierta,
 * en segundo plano o cerrada, y esta clase decide cómo mostrarlos:
 *   tipo=offer → «NUEVA OFERTA DE TRABAJO»; al tocar abre la pantalla principal, que muestra la oferta con ACEPTAR/RECHAZAR.
 *   tipo=order → aviso de una orden (asignación, cambios); al tocar abre la lista de órdenes.
 */
public class PushNotificationService extends FirebaseMessagingService {

    private static final String TAG = "LcPush";
    static final String CANAL_OFERTAS = "lc_ofertas";
    public static final String EXTRA_PUSH_TIPO = "push_tipo";
    public static final String EXTRA_PUSH_JOB = "push_job_id";

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.i(TAG, "nuevo token FCM");
        PushTokenRegistrar.enviar(this, token);
    }

    @Override
    public void onMessageReceived(RemoteMessage msg) {
        super.onMessageReceived(msg);
        Map<String, String> d = msg.getData();
        String tipo = d.get("tipo");
        if (tipo == null) return;
        // con la app a la vista la oferta ya sale como alerta (OfertasPoller); la notificación sería ruido
        if ("offer".equals(tipo) && enPrimerPlano(this)) return;
        mostrar(this, tipo, d.get("titulo"), d.get("cuerpo"), d.get("job_id"), d.get("offer_id"));
    }

    /** Público para poder probar la notificación sin FCM (p. ej. desde un diagnóstico). */
    public static void mostrar(Context ctx, String tipo, String titulo, String cuerpo, String jobId, String offerId) {
        crearCanal(ctx);
        Intent i = new Intent(ctx, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(EXTRA_PUSH_TIPO, tipo);
        if (jobId != null) i.putExtra(EXTRA_PUSH_JOB, jobId);
        int id = offerId != null ? (int) (Long.parseLong(offerId.replaceAll("\\D", "0")) % 100000) + 5000 : 4999;
        PendingIntent pi = PendingIntent.getActivity(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean oferta = "offer".equals(tipo);
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CANAL_OFERTAS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(titulo != null ? titulo : (oferta ? "NUEVA OFERTA DE TRABAJO" : "L&C"))
                .setContentText(cuerpo != null ? cuerpo : "Toca para verla")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(cuerpo != null ? cuerpo : "Toca para verla"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(oferta ? NotificationCompat.CATEGORY_CALL : NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (oferta) b.setTimeoutAfter(120_000L);   // la oferta vence a los 120 s
        try {
            NotificationManagerCompat.from(ctx).notify(id, b.build());
        } catch (SecurityException e) {
            Log.w(TAG, "sin permiso de notificaciones");
        }
    }

    private static void crearCanal(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CANAL_OFERTAS) != null) return;
        NotificationChannel c = new NotificationChannel(CANAL_OFERTAS, "Ofertas de trabajo", NotificationManager.IMPORTANCE_HIGH);
        c.setDescription("Avisos inmediatos de nuevas ofertas de trabajo");
        nm.createNotificationChannel(c);
    }

    private static boolean enPrimerPlano(Context ctx) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
        if (ps == null) return false;
        for (ActivityManager.RunningAppProcessInfo p : ps) {
            if (p.processName.equals(ctx.getPackageName())) {
                return p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            }
        }
        return false;
    }
}
