package com.lc.technician.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.lc.technician.model.CoverageTypeEntity;
import com.lc.technician.model.CustomerEntity;
import com.lc.technician.model.MembershipCardEntity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public class MembershipCardPdfGenerator {

    // A4 at 72 dpi
    private static final int PAGE_W = 595;
    private static final int PAGE_H = 842;

    // Card dimensions (~242 x ~153 points)
    private static final int CARD_W = 340;
    private static final int CARD_H = 214;

    private static final SimpleDateFormat SDF =
            new SimpleDateFormat("MMM yyyy", Locale.forLanguageTag("es"));

    public static boolean generateCardPdf(
            Context ctx,
            MembershipCardEntity card,
            CustomerEntity customer,
            CoverageTypeEntity coverage,
            File outputFile) {

        PdfDocument doc = new PdfDocument();
        try {
            // PAGE 1 - Front
            PdfDocument.PageInfo info1 =
                    new PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create();
            PdfDocument.Page page1 = doc.startPage(info1);
            drawFront(page1.getCanvas(), card, customer, coverage);
            doc.finishPage(page1);

            // PAGE 2 - Back
            PdfDocument.PageInfo info2 =
                    new PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 2).create();
            PdfDocument.Page page2 = doc.startPage(info2);
            drawBack(page2.getCanvas(), card);
            doc.finishPage(page2);

            outputFile.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                doc.writeTo(fos);
            }
            return true;
        } catch (Exception e) {
            android.util.Log.e("MembershipCardPdf", "Error generating PDF", e);
            return false;
        } finally {
            doc.close();
        }
    }

    private static void drawFront(Canvas c, MembershipCardEntity card,
                                  CustomerEntity customer, CoverageTypeEntity cov) {
        int cx = (PAGE_W - CARD_W) / 2;
        int cy = (PAGE_H - CARD_H * 2 - 30) / 2;

        // Card background
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] colors = membershipColor(cov);
        bg.setColor(colors[0]);
        c.drawRoundRect(new RectF(cx, cy, cx + CARD_W, cy + CARD_H), 16, 16, bg);

        // Company name
        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(Color.WHITE);
        title.setTextSize(22f);
        title.setFakeBoldText(true);
        c.drawText("L&C Technician", cx + 16, cy + 34, title);

        // Membership badge
        Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
        badge.setColor(colors[1]);
        String tier = cov != null ? cov.code : "MEMBER";
        float bw = title.measureText(tier) + 24;
        c.drawRoundRect(new RectF(cx + CARD_W - bw - 10, cy + 10, cx + CARD_W - 10, cy + 36),
                8, 8, badge);
        Paint badgeTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgeTxt.setColor(Color.WHITE);
        badgeTxt.setTextSize(14f);
        badgeTxt.setFakeBoldText(true);
        c.drawText(tier, cx + CARD_W - bw - 10 + 12, cy + 29, badgeTxt);

        // Customer name
        Paint name = new Paint(Paint.ANTI_ALIAS_FLAG);
        name.setColor(Color.WHITE);
        name.setTextSize(18f);
        String customerName = customer != null ? customer.nombre : "—";
        c.drawText(customerName, cx + 16, cy + 80, name);

        // Card number
        Paint cardNum = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardNum.setColor(0xCCFFFFFF);
        cardNum.setTextSize(13f);
        cardNum.setLetterSpacing(0.1f);
        c.drawText(card.cardNumber != null ? card.cardNumber : "", cx + 16, cy + 110, cardNum);

        // Dates
        Paint dates = new Paint(Paint.ANTI_ALIAS_FLAG);
        dates.setColor(0xBBFFFFFF);
        dates.setTextSize(11f);
        String issueStr = "Emision: " + SDF.format(new Date(card.issueDate));
        String expiryStr = "Vence: " + SDF.format(new Date(card.expiryDate));
        c.drawText(issueStr, cx + 16, cy + CARD_H - 40, dates);
        c.drawText(expiryStr, cx + 16, cy + CARD_H - 22, dates);

        // Barcode
        try {
            Bitmap barcode = generateCode128(card.cardNumber != null ? card.cardNumber : "LC-000", 220, 40);
            if (barcode != null) {
                Paint bPaint = new Paint();
                bPaint.setFilterBitmap(false);
                Rect dst = new Rect(cx + CARD_W - 230, cy + CARD_H - 70,
                        cx + CARD_W - 10, cy + CARD_H - 30);
                c.drawBitmap(barcode, null, dst, bPaint);
            }
        } catch (Exception ignored) {}
    }

    private static void drawBack(Canvas c, MembershipCardEntity card) {
        int cx = (PAGE_W - CARD_W) / 2;
        int cy = (PAGE_H - CARD_H * 2 - 30) / 2 + CARD_H + 30;

        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0xFF1A1A2E);
        c.drawRoundRect(new RectF(cx, cy, cx + CARD_W, cy + CARD_H), 16, 16, bg);

        // Magnetic strip area
        Paint strip = new Paint();
        strip.setColor(Color.BLACK);
        c.drawRect(cx, cy + 28, cx + CARD_W, cy + 68, strip);

        // QR Code
        try {
            Bitmap qr = generateQR(card.cardNumber != null ? card.cardNumber : "LC-000", 80);
            if (qr != null) {
                Paint qrPaint = new Paint();
                qrPaint.setFilterBitmap(false);
                Rect dst = new Rect(cx + CARD_W - 95, cy + 78, cx + CARD_W - 15, cy + 158);
                c.drawBitmap(qr, null, dst, qrPaint);
            }
        } catch (Exception ignored) {}

        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(0xBBFFFFFF);
        txt.setTextSize(9f);
        c.drawText("Atencion al cliente: (305) 555-0100", cx + 12, cy + 110, txt);
        c.drawText("Valida solo para el titular. No transferible.", cx + 12, cy + 130, txt);
        c.drawText("L&C Technician Services - Miami, FL", cx + 12, cy + 150, txt);

        // Card number small
        Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        small.setColor(0x88FFFFFF);
        small.setTextSize(8f);
        c.drawText(card.cardNumber != null ? card.cardNumber : "", cx + 12, cy + CARD_H - 12, small);
    }

    private static int[] membershipColor(CoverageTypeEntity cov) {
        if (cov == null) return new int[]{0xFF4A4A8A, 0xFF6A6AAA};
        switch (cov.code) {
            case "GOLD":          return new int[]{0xFFB8860B, 0xFFFFD700};
            case "SILVER":        return new int[]{0xFF708090, 0xFFC0C0C0};
            case "BRONZE":        return new int[]{0xFF8B4513, 0xFFCD7F32};
            case "HOME_WARRANTY": return new int[]{0xFF006400, 0xFF228B22};
            default:              return new int[]{0xFF1A3A5C, 0xFF2E6DA4};
        }
    }

    private static Bitmap generateCode128(String text, int width, int height) {
        try {
            BitMatrix matrix = new Code128Writer().encode(text,
                    BarcodeFormat.CODE_128, width, height);
            return bitMatrixToBitmap(matrix);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap generateQR(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(text,
                    BarcodeFormat.QR_CODE, size, size, hints);
            return bitMatrixToBitmap(matrix);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap bitMatrixToBitmap(BitMatrix matrix) {
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                pixels[y * w + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }
}
