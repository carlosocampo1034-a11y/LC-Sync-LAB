package com.lc.customer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;

import java.io.ByteArrayOutputStream;

/** Área de firma: el cliente firma con el dedo; se envía como PNG. */
public class FirmaView extends View {
    private final Path trazo = new Path();
    private final Paint tinta = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean vacia = true;

    public FirmaView(Context c) {
        super(c);
        tinta.setColor(Color.BLACK);
        tinta.setStyle(Paint.Style.STROKE);
        tinta.setStrokeWidth(6f);
        tinta.setStrokeCap(Paint.Cap.ROUND);
        setBackgroundColor(0xFFF1F2F3);
    }

    @Override protected void onDraw(Canvas c) { c.drawPath(trazo, tinta); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        getParent().requestDisallowInterceptTouchEvent(true);
        if (e.getAction() == MotionEvent.ACTION_DOWN) trazo.moveTo(e.getX(), e.getY());
        else if (e.getAction() == MotionEvent.ACTION_MOVE) { trazo.lineTo(e.getX(), e.getY()); vacia = false; }
        invalidate();
        return true;
    }

    boolean vacia() { return vacia; }

    void limpiar() { trazo.reset(); vacia = true; invalidate(); }

    String pngBase64() {
        Bitmap b = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.WHITE);
        c.drawPath(trazo, tinta);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }
}
