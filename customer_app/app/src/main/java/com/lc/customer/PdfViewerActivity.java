package com.lc.customer;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.Executors;

/** Muestra el PDF de la factura dentro de la app (sin abrir aplicaciones externas). */
public class PdfViewerActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.Pantalla p = Ui.pantalla(this, "Factura", "Documento PDF", true);
        TextView estado = Ui.texto(this, "Descargando…", 14, R.color.lc_sec, false);
        p.contenido.addView(estado);
        long jobId = getIntent().getLongExtra("jobId", 0);
        Executors.newSingleThreadExecutor().execute(() -> {
            byte[] pdf = Api.descargarPdf(this, "/customer/requests/" + jobId + "/factura.pdf");
            if (pdf == null) {
                runOnUiThread(() -> estado.setText("No se pudo descargar la factura."));
                return;
            }
            try {
                // copia temporal en la caché privada de la app (se borra al salir)
                File f = new File(getCacheDir(), "factura_" + jobId + ".pdf");
                try (FileOutputStream os = new FileOutputStream(f)) { os.write(pdf); }
                try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                     PdfRenderer r = new PdfRenderer(fd)) {
                    int ancho = getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 32);
                    for (int i = 0; i < r.getPageCount() && i < 10; i++) {
                        try (PdfRenderer.Page pg = r.openPage(i)) {
                            int alto = (int) ((float) ancho * pg.getHeight() / pg.getWidth());
                            Bitmap bmp = Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            runOnUiThread(() -> {
                                ImageView iv = new ImageView(this);
                                iv.setImageBitmap(bmp);
                                iv.setAdjustViewBounds(true);
                                p.contenido.addView(iv, new LinearLayout.LayoutParams(ancho, alto));
                            });
                        }
                    }
                    final int paginas = r.getPageCount();
                    runOnUiThread(() -> estado.setText(paginas + " página(s)"));
                }
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            } catch (Exception e) {
                runOnUiThread(() -> estado.setText("No se pudo mostrar el PDF."));
            }
        });
    }
}
