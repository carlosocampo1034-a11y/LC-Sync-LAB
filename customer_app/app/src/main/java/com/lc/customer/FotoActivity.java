package com.lc.customer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;

/** Foto a pantalla completa: pellizcar para ampliar, arrastrar para moverla, «Cerrar» para volver. */
public class FotoActivity extends AppCompatActivity {

    private final Matrix m = new Matrix();
    private ImageView iv;
    private float escala = 1f, ultimoX, ultimoY;
    private ScaleGestureDetector zoom;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(0xFF171717);
        iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.MATRIX);
        raiz.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        TextView cerrar = Ui.texto(this, "Cerrar", 16, android.R.color.white, true);
        cerrar.setPadding(Ui.dp(this, 20), Ui.dp(this, 14), Ui.dp(this, 20), Ui.dp(this, 14));
        cerrar.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(this, 36);
        raiz.addView(cerrar, lp);
        setContentView(raiz);
        zoom = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float f = Math.max(1f / escala, Math.min(6f / escala, d.getScaleFactor()));
                escala *= f;
                m.postScale(f, f, d.getFocusX(), d.getFocusY());
                iv.setImageMatrix(m);
                return true;
            }
        });
        iv.setOnTouchListener((v, e) -> {
            zoom.onTouchEvent(e);
            if (!zoom.isInProgress()) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { ultimoX = e.getX(); ultimoY = e.getY(); }
                else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && e.getPointerCount() == 1) {
                    m.postTranslate(e.getX() - ultimoX, e.getY() - ultimoY);
                    ultimoX = e.getX(); ultimoY = e.getY();
                    iv.setImageMatrix(m);
                }
            }
            return true;
        });
        String url = getIntent().getStringExtra("url");
        Executors.newSingleThreadExecutor().execute(() -> {
            Bitmap bmp = null;
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(10000);
                con.setReadTimeout(20000);
                try (InputStream in = con.getInputStream()) { bmp = BitmapFactory.decodeStream(in); }
            } catch (Exception ignored) {}
            final Bitmap f = bmp;
            runOnUiThread(() -> {
                if (f == null) { Ui.texto(this, "", 1, android.R.color.white, false); setTitle("No se pudo abrir la foto"); return; }
                iv.setImageBitmap(f);
                iv.post(() -> {   // ajustar al ancho de la pantalla, centrada
                    float s = Math.min((float) iv.getWidth() / f.getWidth(), (float) iv.getHeight() / f.getHeight());
                    m.reset();
                    m.postScale(s, s);
                    m.postTranslate((iv.getWidth() - f.getWidth() * s) / 2f, (iv.getHeight() - f.getHeight() * s) / 2f);
                    escala = 1f;
                    iv.setImageMatrix(m);
                });
            });
        });
    }
}
