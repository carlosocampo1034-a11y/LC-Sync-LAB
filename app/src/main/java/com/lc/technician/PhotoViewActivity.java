package com.lc.technician;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;

public class PhotoViewActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_view);
        // Edge-to-edge (targetSdk 36): el contenido no queda bajo las barras del sistema ni el teclado
        InsetsHelper.padSystemBarsAndIme(findViewById(android.R.id.content));

        String path = getIntent().getStringExtra("photo_path");
        ImageView imageView = findViewById(R.id.fullImage);

        if (path != null) {
            new Thread(() -> {
                Bitmap bitmap = decodeSampled(path, 1080, 1920);
                if (bitmap != null) runOnUiThread(() -> imageView.setImageBitmap(bitmap));
            }).start();
        }

        findViewById(R.id.btnCerrar).setOnClickListener(v -> finish());
    }

    private static Bitmap decodeSampled(String path, int reqW, int reqH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        int sample = 1;
        int w = opts.outWidth, h = opts.outHeight;
        while (w / (sample * 2) >= reqW && h / (sample * 2) >= reqH) sample *= 2;
        opts.inSampleSize = sample;
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, opts);
    }
}
