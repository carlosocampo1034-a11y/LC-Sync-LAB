package com.lc.technician;

import android.content.SharedPreferences;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.net.HttpURLConnection;
import java.net.URL;

public class OnboardingActivity extends AppCompatActivity {

    private static final int TOTAL_PAGES = 5;
    private int currentPage = 0;
    private SharedPreferences prefs;
    private LinearLayout progressDots;
    private FrameLayout pageContent;
    private Button btnBack, btnNext;
    private TextView btnSkip;

    // Input fields
    private EditText etServerUrl;
    private String serverUrlValue = "http://127.0.0.1:5000/api/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        prefs = getSharedPreferences("lc_prefs", MODE_PRIVATE);
        progressDots = findViewById(R.id.progressDots);
        pageContent = findViewById(R.id.pageContent);
        btnBack = findViewById(R.id.btnBack);
        btnNext = findViewById(R.id.btnNext);
        btnSkip = findViewById(R.id.btnSkip);

        setupDots();

        btnBack.setOnClickListener(v -> navigate(-1));
        btnNext.setOnClickListener(v -> {
            if (currentPage == TOTAL_PAGES - 1) {
                finishOnboarding();
            } else {
                navigate(1);
            }
        });
        btnSkip.setOnClickListener(v -> finishOnboarding());

        showPage(0);
    }

    private void setupDots() {
        progressDots.removeAllViews();
        for (int i = 0; i < TOTAL_PAGES; i++) {
            View dot = new View(this);
            int size = 10;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dpToPx(size), dpToPx(size));
            lp.setMargins(dpToPx(4), 0, dpToPx(4), 0);
            dot.setLayoutParams(lp);
            dot.setBackgroundColor(i == 0 ? Color.parseColor("#E91605") : Color.parseColor("#62666A"));
            dot.setTag(i);
            dot.setClipToOutline(true);
            progressDots.addView(dot);
        }
    }

    private void updateDots() {
        for (int i = 0; i < progressDots.getChildCount(); i++) {
            View dot = progressDots.getChildAt(i);
            dot.setBackgroundColor(i == currentPage ? Color.parseColor("#E91605") : Color.parseColor("#62666A"));
        }
    }

    private void navigate(int direction) {
        // Save current page data before navigating
        if (currentPage == 2 && direction == 1 && etServerUrl != null) {
            serverUrlValue = etServerUrl.getText().toString().trim();
            if (!serverUrlValue.endsWith("/")) serverUrlValue += "/";
            prefs.edit().putString("server_url", serverUrlValue).apply();
        }

        int newPage = currentPage + direction;
        if (newPage >= 0 && newPage < TOTAL_PAGES) {
            currentPage = newPage;
            showPage(currentPage);
        }
    }

    private void showPage(int page) {
        currentPage = page;
        updateDots();
        pageContent.removeAllViews();

        btnBack.setVisibility(page > 0 ? View.VISIBLE : View.GONE);
        btnSkip.setVisibility(page == 0 ? View.GONE : View.VISIBLE);
        btnNext.setText(page == TOTAL_PAGES - 1 ? "COMENZAR" : "SIGUIENTE");

        switch (page) {
            case 0: showWelcomePage(); break;
            case 1: showPinPage(); break;
            case 2: showUrlPage(); break;
            case 3: showPermissionsPage(); break;
            case 4: showConnectionPage(); break;
        }
    }

    private void showWelcomePage() {
        LinearLayout ll = createPageLayout();

        TextView logo = new TextView(this);
        logo.setText("\u26a1");
        logo.setTextSize(64);
        logo.setGravity(Gravity.CENTER);

        TextView title = new TextView(this);
        title.setText("LC Technician");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);

        TextView sub = new TextView(this);
        sub.setText("Sistema de gesti\u00f3n de servicios t\u00e9cnicos\n\nBienvenido al asistente de configuraci\u00f3n inicial.");
        sub.setTextColor(Color.parseColor("#9A9A9A"));
        sub.setTextSize(15);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dpToPx(16), 0, 0);

        ll.addView(logo);
        ll.addView(title);
        ll.addView(sub);
        pageContent.addView(ll);
        btnNext.setText("COMENZAR CONFIGURACI\u00d3N");
    }

    private void showPinPage() {
        LinearLayout ll = createPageLayout();

        TextView title = new TextView(this);
        title.setText("2. Confirmaci\u00f3n de acceso");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);

        TextView sub = new TextView(this);
        sub.setText("Tu administrador te ha asignado un PIN de acceso. Ingr\u00e9salo a continuaci\u00f3n para verificar tu rol.");
        sub.setTextColor(Color.parseColor("#9A9A9A"));
        sub.setTextSize(14);
        sub.setPadding(0, dpToPx(12), 0, dpToPx(20));

        TextView note = new TextView(this);
        note.setText("\u2139\ufe0f  El PIN fue configurado por tu empresa. Si no lo tienes, contacta a tu supervisor.");
        note.setTextColor(Color.parseColor("#808080"));
        note.setTextSize(12);
        note.setPadding(0, dpToPx(16), 0, 0);

        ll.addView(title);
        ll.addView(sub);
        ll.addView(note);
        pageContent.addView(ll);
    }

    private void showUrlPage() {
        LinearLayout ll = createPageLayout();

        TextView title = new TextView(this);
        title.setText("3. URL del servidor");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);

        TextView sub = new TextView(this);
        sub.setText("Ingresa la direcci\u00f3n del servidor Flask. Tu administrador te la proporcionar\u00e1.");
        sub.setTextColor(Color.parseColor("#9A9A9A"));
        sub.setTextSize(14);
        sub.setPadding(0, dpToPx(12), 0, dpToPx(16));

        etServerUrl = new EditText(this);
        String savedUrl = prefs.getString("server_url", "http://127.0.0.1:5000/api/");
        etServerUrl.setText(savedUrl);
        etServerUrl.setTextColor(Color.WHITE);
        etServerUrl.setHintTextColor(Color.parseColor("#808080"));
        etServerUrl.setBackgroundColor(Color.parseColor("#2B2B2B"));
        etServerUrl.setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12));

        TextView hint = new TextView(this);
        hint.setText("Ejemplo:\nhttp://192.168.1.100:5000/api/\nhttps://mi-servidor.tailscale.net/api/");
        hint.setTextColor(Color.parseColor("#808080"));
        hint.setTextSize(12);
        hint.setPadding(0, dpToPx(8), 0, 0);

        ll.addView(title);
        ll.addView(sub);
        ll.addView(etServerUrl);
        ll.addView(hint);
        pageContent.addView(ll);
    }

    private void showPermissionsPage() {
        LinearLayout ll = createPageLayout();

        TextView title = new TextView(this);
        title.setText("4. Permisos requeridos");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);

        TextView sub = new TextView(this);
        sub.setText("La app necesita los siguientes permisos para funcionar correctamente:");
        sub.setTextColor(Color.parseColor("#9A9A9A"));
        sub.setTextSize(14);
        sub.setPadding(0, dpToPx(12), 0, dpToPx(16));

        String[] perms = {
            "\uD83D\uDCF7 C\u00e1mara \u2014 Para tomar fotos del trabajo y escanear membres\u00edas",
            "\uD83D\uDD14 Notificaciones \u2014 Para recibir alertas de trabajos asignados",
            "\uD83D\uDCBE Almacenamiento \u2014 Para guardar fotos e informes PDF"
        };

        ll.addView(title);
        ll.addView(sub);
        for (String perm : perms) {
            TextView tv = new TextView(this);
            tv.setText(perm);
            tv.setTextColor(Color.parseColor("#d1d5db"));
            tv.setTextSize(13);
            tv.setPadding(0, dpToPx(8), 0, dpToPx(4));
            ll.addView(tv);
        }

        TextView note = new TextView(this);
        note.setText("\nAndroid solicitar\u00e1 los permisos cuando los necesite por primera vez.");
        note.setTextColor(Color.parseColor("#808080"));
        note.setTextSize(12);
        ll.addView(note);

        pageContent.addView(ll);
    }

    private void showConnectionPage() {
        LinearLayout ll = createPageLayout();

        TextView title = new TextView(this);
        title.setText("5. Verificando conexi\u00f3n");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);

        TextView status = new TextView(this);
        status.setText("\uD83D\uDD04 Probando conexi\u00f3n al servidor...");
        status.setTextColor(Color.parseColor("#9A9A9A"));
        status.setTextSize(14);
        status.setPadding(0, dpToPx(16), 0, 0);
        status.setTag("statusText");

        ll.addView(title);
        ll.addView(status);
        pageContent.addView(ll);

        // Test connection in background
        String urlToTest = prefs.getString("server_url", "http://127.0.0.1:5000/api/");
        new Thread(() -> {
            boolean ok = testConnection(urlToTest);
            runOnUiThread(() -> {
                if (ok) {
                    status.setText("\u2705 Conexi\u00f3n exitosa al servidor\n\nTodo est\u00e1 listo. Pulsa COMENZAR para iniciar la app.");
                    status.setTextColor(Color.parseColor("#FFFFFF"));
                } else {
                    status.setText("\u26a0\ufe0f No se pudo conectar al servidor\n\nVerifica la URL o la conexi\u00f3n de red. Puedes continuar y configurar m\u00e1s tarde en Ajustes.");
                    status.setTextColor(Color.parseColor("#E91605"));
                }
            });
        }).start();
    }

    private boolean testConnection(String baseUrl) {
        try {
            String healthUrl = baseUrl.replace("/api/", "/api/health");
            if (!healthUrl.contains("/api/health")) {
                healthUrl = baseUrl + (baseUrl.endsWith("/") ? "" : "/") + "health";
            }
            URL url = new URL(healthUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void finishOnboarding() {
        // Save server URL if on url page or later
        if (etServerUrl != null) {
            String url = etServerUrl.getText().toString().trim();
            if (!url.isEmpty()) {
                if (!url.endsWith("/")) url += "/";
                prefs.edit().putString("server_url", url).apply();
            }
        }
        prefs.edit().putBoolean("onboarding_done", true).apply();
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private LinearLayout createPageLayout() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        ll.setGravity(Gravity.CENTER_VERTICAL);
        return ll;
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return (int) (dp * density);
    }
}
