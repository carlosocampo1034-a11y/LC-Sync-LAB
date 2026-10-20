package com.lc.technician.worker;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.MembershipCardEntity;
import com.lc.technician.util.NotificationHelper;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MembershipExpiryWorker extends Worker {

    private static final String PREFS = "lc_expiry_notified";

    public MembershipExpiryWorker(@NonNull Context ctx, @NonNull WorkerParameters params) {
        super(ctx, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Context ctx = getApplicationContext();
            List<MembershipCardEntity> cards =
                    LcDatabase.getInstance(ctx).membershipCardDao().getAll();

            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(new java.util.Date());
            SharedPreferences.Editor ed = prefs.edit();
            long now = System.currentTimeMillis();

            for (MembershipCardEntity card : cards) {
                // Skip suspended cards only; expired cards need expiry notification
                if (MembershipCardEntity.STATUS_SUSPENDED.equals(card.status)) continue;

                String key = "notified_" + card.id + "_" + today;
                if (prefs.getBoolean(key, false)) continue;

                long diff = card.expiryDate - now;
                int daysLeft = (int) TimeUnit.MILLISECONDS.toDays(diff);

                String customerName = "Cliente #" + card.customerId;
                try {
                    com.lc.technician.model.CustomerEntity cust =
                            LcDatabase.getInstance(ctx).customerDao().getById(card.customerId);
                    if (cust != null && cust.nombre != null) customerName = cust.nombre;
                } catch (Exception ignored) {}

                String cn = card.cardNumber != null ? card.cardNumber : "LC-???";
                if (daysLeft < 0) {
                    // Membresía vencida — notificación alta prioridad
                    NotificationHelper.notifyMembershipExpired(ctx, cn, daysLeft);
                    ed.putBoolean(key, true);
                } else if (daysLeft <= 30) {
                    // Membresía próxima a vencer
                    NotificationHelper.notifyMembershipExpiringSoon(ctx, cn, daysLeft);
                    ed.putBoolean(key, true);
                }
            }
            ed.apply();
            return Result.success();
        } catch (Exception e) {
            android.util.Log.e("MembershipExpiryWorker", "Error", e);
            return Result.failure();
        }
    }
}
