package com.lc.technician.ui.adapter;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.lc.technician.R;
import com.lc.technician.model.ContactEntity;
import java.util.List;

public class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.VH> {

    public interface OnDeleteClick { void onDelete(ContactEntity contact); }

    private final List<ContactEntity> contacts;
    private final OnDeleteClick deleteListener;

    public ContactAdapter(List<ContactEntity> contacts, OnDeleteClick deleteListener) {
        this.contacts = contacts;
        this.deleteListener = deleteListener;
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ContactEntity c = contacts.get(position);

        h.nombre.setText(c.nombre);
        h.telefono.setText(c.telefono != null ? c.telefono : "");

        if (c.email != null && !c.email.isEmpty()) {
            h.email.setText(c.email);
            h.email.setVisibility(View.VISIBLE);
        } else {
            h.email.setVisibility(View.GONE);
        }

        h.chip.setText(c.rol);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(colorForRol(c.rol));
        bg.setCornerRadius(20f);
        h.chip.setBackground(bg);

        h.btnLlamar.setOnClickListener(v -> {
            if (c.telefono != null && !c.telefono.isEmpty()) {
                Intent intent = new Intent(Intent.ACTION_DIAL,
                        Uri.parse("tel:" + c.telefono));
                v.getContext().startActivity(intent);
            }
        });

        h.btnBorrar.setOnClickListener(v -> {
            if (deleteListener != null) deleteListener.onDelete(c);
        });
    }

    @Override public int getItemCount() { return contacts.size(); }

    static class VH extends RecyclerView.ViewHolder {
        final TextView chip, nombre, telefono, email;
        final ImageView btnLlamar, btnBorrar;
        VH(@NonNull View v) {
            super(v);
            chip      = v.findViewById(R.id.chipRol);
            nombre    = v.findViewById(R.id.contactoNombre);
            telefono  = v.findViewById(R.id.contactoTelefono);
            email     = v.findViewById(R.id.contactoEmail);
            btnLlamar = v.findViewById(R.id.btnLlamar);
            btnBorrar = v.findViewById(R.id.btnBorrar);
        }
    }

    private static int colorForRol(String rol) {
        if (rol == null) return 0xFF767676;
        switch (rol) {
            case "DUEÑO":     return 0xFF1B71C9;
            case "INQUILINO": return 0xFFF57C00;
            case "PAGADOR":   return 0xFF137333;
            case "FIRMANTE":  return 0xFFEB1700;
            default:          return 0xFF767676;
        }
    }
}
