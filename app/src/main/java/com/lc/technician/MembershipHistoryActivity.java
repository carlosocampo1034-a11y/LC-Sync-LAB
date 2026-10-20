package com.lc.technician;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.MembershipCheckInEntity;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MembershipHistoryActivity extends AppCompatActivity {

    public static final String EXTRA_CARD_ID     = "card_id";
    public static final String EXTRA_CARD_NUMBER = "card_number";

    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.forLanguageTag("es"));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_membership_history);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        long cardId = getIntent().getLongExtra(EXTRA_CARD_ID, -1);
        String cardNumber = getIntent().getStringExtra(EXTRA_CARD_NUMBER);

        ImageView btnAtras = findViewById(R.id.btnAtras);
        if (btnAtras != null) btnAtras.setOnClickListener(v -> finish());

        TextView tvTitle = findViewById(R.id.tvHistoryTitle);
        if (tvTitle != null && cardNumber != null) {
            tvTitle.setText("Historial \u00b7 " + cardNumber);
        }

        if (cardId < 0) { finish(); return; }

        cargarHistorial(cardId);
    }

    private void cargarHistorial(long cardId) {
        new Thread(() -> {
            List<MembershipCheckInEntity> items =
                    LcDatabase.getInstance(this).membershipCheckInDao().getByCard(cardId);
            runOnUiThread(() -> mostrarHistorial(items));
        }).start();
    }

    private void mostrarHistorial(List<MembershipCheckInEntity> items) {
        LinearLayout container = findViewById(R.id.layoutHistorial);
        if (container == null) return;
        container.removeAllViews();

        if (items == null || items.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Sin check-ins registrados");
            tv.setTextColor(0xFF9A9A9A);
            tv.setPadding(32, 32, 32, 32);
            tv.setTextSize(15);
            container.addView(tv);
            return;
        }

        for (MembershipCheckInEntity item : items) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(24, 20, 24, 20);
            card.setBackgroundColor(0xFFF5F5F5);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, 12);
            card.setLayoutParams(lp);

            TextView tvDate = new TextView(this);
            tvDate.setText("\uD83D\uDCC5 " + SDF.format(new Date(item.checkInDate)));
            tvDate.setTextColor(0xFF1A1A1A);
            tvDate.setTextSize(14);
            tvDate.setTypeface(null, android.graphics.Typeface.BOLD);
            card.addView(tvDate);

            if (item.technicianName != null && !item.technicianName.isEmpty()) {
                TextView tvTech = new TextView(this);
                tvTech.setText("\uD83D\uDC64 " + item.technicianName);
                tvTech.setTextColor(0xFF555555);
                tvTech.setTextSize(13);
                card.addView(tvTech);
            }

            if (item.jobId != null) {
                TextView tvJob = new TextView(this);
                tvJob.setText("\uD83D\uDD27 Job #" + item.jobId);
                tvJob.setTextColor(0xFF555555);
                tvJob.setTextSize(13);
                card.addView(tvJob);
            }

            if (item.notes != null && !item.notes.isEmpty()) {
                TextView tvNotes = new TextView(this);
                tvNotes.setText("\uD83D\uDCDD " + item.notes);
                tvNotes.setTextColor(0xFF777777);
                tvNotes.setTextSize(12);
                card.addView(tvNotes);
            }

            container.addView(card);
        }
    }
}
