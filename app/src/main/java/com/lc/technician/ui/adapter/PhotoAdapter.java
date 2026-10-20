package com.lc.technician.ui.adapter;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.R;
import com.lc.technician.model.PhotoEntity;
import java.util.List;

public class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.VH> {

    public interface OnPhotoClick { void onClick(PhotoEntity photo); }

    private final List<PhotoEntity> photos;
    private final OnPhotoClick listener;

    public PhotoAdapter(List<PhotoEntity> photos, OnPhotoClick listener) {
        this.photos = photos;
        this.listener = listener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_photo, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        PhotoEntity photo = photos.get(position);
        Bitmap bmp = decodeSampled(photo.path, 300, 300);
        if (bmp != null) h.image.setImageBitmap(bmp);

        if (photo.categoria != null && !photo.categoria.isEmpty()) {
            h.chip.setText(photo.categoria);
            int color = colorForCategoria(photo.categoria);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(color);
            bg.setCornerRadius(20f);
            h.chip.setBackground(bg);
            h.chip.setVisibility(View.VISIBLE);
        } else {
            h.chip.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onClick(photo); });
    }

    @Override public int getItemCount() { return photos.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView chip;
        VH(@NonNull View v) {
            super(v);
            image = v.findViewById(R.id.photoImage);
            chip  = v.findViewById(R.id.chipCategoria);
        }
    }

    private static int colorForCategoria(String cat) {
        if (cat == null) return 0xFF767676;
        switch (cat) {
            case "ANTES":     return 0xFFEB1700;
            case "DESPUÉS":   return 0xFF137333;
            case "EQUIPO":    return 0xFF1B71C9;
            case "NAMEPLATE": return 0xFF767676;
            default:          return 0xFF767676;
        }
    }

    private static Bitmap decodeSampled(String path, int reqW, int reqH) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        opts.inSampleSize = calcSampleSize(opts, reqW, reqH);
        opts.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, opts);
    }

    private static int calcSampleSize(BitmapFactory.Options opts, int reqW, int reqH) {
        int h = opts.outHeight, w = opts.outWidth, s = 1;
        if (h > reqH || w > reqW) {
            int hh = h / 2, hw = w / 2;
            while ((hh / s) >= reqH && (hw / s) >= reqW) s *= 2;
        }
        return s;
    }
}
