package com.lc.technician.worker;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.lc.technician.sync.ApiClient;
import com.lc.technician.sync.SyncApi;
import com.lc.technician.sync.SyncModels;
import com.lc.technician.util.NotificationHelper;
import retrofit2.Response;

public class NotificationPollWorker extends Worker {
    public static final String TAG = "notif_poll_worker";

    public NotificationPollWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Context ctx = getApplicationContext();
            SharedPreferences prefs = ctx.getSharedPreferences("lc_prefs", Context.MODE_PRIVATE);
            long lastPoll = prefs.getLong("last_notif_poll", 0);
            if (com.lc.technician.BuildConfig.CLOUD_LOGIN) return sondearQa(ctx, prefs, lastPoll);

            SyncApi api = ApiClient.get(ctx);
            Response<SyncModels.NotificationResponse> resp = api.getNotifications(lastPoll).execute();

            if (!resp.isSuccessful() || resp.body() == null) {
                return Result.retry();
            }

            SyncModels.NotificationResponse body = resp.body();

            if (body.jobs != null) {
                for (SyncModels.JobNotification job : body.jobs) {
                    NotificationHelper.notifyJobAssigned(ctx, job.cliente, job.hora, job.id);
                }
            }

            if (body.renewals != null) {
                for (SyncModels.RenewalNotification r : body.renewals) {
                    NotificationHelper.notifyMembershipRenewed(ctx, r.entityId);
                }
            }

            prefs.edit().putLong("last_notif_poll", body.now).apply();
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    /** QA: lc-api /notifications (antes pedía /sync/notifications de Flask a Supabase y recibía 404). */
    private Result sondearQa(Context ctx, SharedPreferences prefs, long lastPoll) throws Exception {
        com.lc.technician.sync.SupabaseClient sb = com.lc.technician.sync.SupabaseClient.get(ctx);
        if (!sb.hasUserSession()) return Result.success();
        // primera vez: no se repiten avisos antiguos, solo lo que llegue desde ahora
        if (lastPoll == 0) {
            prefs.edit().putLong("last_notif_poll", System.currentTimeMillis()).apply();
            return Result.success();
        }
        Response<okhttp3.ResponseBody> r = sb.api().notifications(sb.authHeader(), lastPoll).execute();
        if (!r.isSuccessful() || r.body() == null) return Result.retry();
        org.json.JSONArray lista = new org.json.JSONArray(r.body().string());
        long maximo = lastPoll;
        for (int i = 0; i < lista.length(); i++) {
            org.json.JSONObject n = lista.getJSONObject(i);
            maximo = Math.max(maximo, n.optLong("created_at"));
            if ("ASIGNACION".equals(n.optString("tipo")) && !n.isNull("job_id")) {
                NotificationHelper.notifyJobAssigned(ctx, n.optString("cuerpo", n.optString("titulo")), "", n.getLong("job_id"));
            }
        }
        prefs.edit().putLong("last_notif_poll", maximo).apply();
        return Result.success();
    }
}
