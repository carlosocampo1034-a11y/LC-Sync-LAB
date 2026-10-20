package com.lc.technician.ocr;

import android.net.Uri;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import android.content.Context;
import java.io.IOException;

/**
 * Image → raw text, on-device only.
 *
 * Uses ML Kit on-device TextRecognition (Latin script).
 * No cloud calls, no API keys, no per-scan cost.
 */
public class NameplateOcrProcessor {

    public interface OcrCallback {
        void onSuccess(String rawText);
        void onFailure(Exception e);
    }

    private final Context context;

    public NameplateOcrProcessor(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Runs ML Kit text recognition on the given image URI.
     * The callback is invoked on the main thread (ML Kit guarantee).
     */
    public void processUri(Uri imageUri, OcrCallback callback) {
        InputImage image;
        try {
            image = InputImage.fromFilePath(context, imageUri);
        } catch (IOException e) {
            callback.onFailure(e);
            return;
        }

        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(image)
                .addOnSuccessListener(visionText -> {
                    callback.onSuccess(visionText.getText());
                    recognizer.close();
                })
                .addOnFailureListener(e -> {
                    callback.onFailure(e);
                    recognizer.close();
                });
    }
}
