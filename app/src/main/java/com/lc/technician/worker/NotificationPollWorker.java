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
}
