package com.lc.customer;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.concurrent.Executors;

public class LoginActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Api.conSesion(this)) {
            startActivity(new Intent(this, HomeActivity.class));
            finish();
            return;
        }
        Ui.Pantalla p = Ui.pantalla(this, "L&C", "Servicio técnico a domicilio", false);
        Ui.seccion(this, p.contenido, "Inicia sesión");
        EditText usuario = Ui.campo(this, p.contenido, CloudApi.activo() ? "Correo electrónico" : "Usuario",
                CloudApi.activo() ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS : InputType.TYPE_CLASS_TEXT);
        EditText clave = Ui.campo(this, p.contenido, "Contraseña",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView error = Ui.texto(this, "", 14, R.color.lc_rojo, false);
        p.contenido.addView(error);
        Button entrar = Ui.boton(this, p.inferior, "Entrar", true);
        Button crear = Ui.boton(this, p.inferior, "Crear cuenta", false);
        TextView servidor = Ui.texto(this, "", 14, R.color.lc_sec, false);
        servidor.setMinHeight(Ui.dp(this, 44));
        servidor.setGravity(android.view.Gravity.CENTER_VERTICAL);
        p.contenido.addView(servidor);
        Runnable pintar = () -> servidor.setText("Servidor: " + Api.base(this) + " · toca para editar");
        pintar.run();
        servidor.setOnClickListener(v -> {
            EditText et = new EditText(this);
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
            et.setSingleLine(true);
            et.setText(Api.base(this));
            et.setSelection(et.getText().length());
            int pad = Ui.dp(this, 16);
            et.setPadding(pad, pad, pad, pad);
            new AlertDialog.Builder(this)
                    .setTitle("URL del servidor")
                    .setMessage("Escribe la URL completa, incluyendo /api/v2")
                    .setView(et)
                    .setPositiveButton("Guardar", (d, w) -> {
                        Api.setUrlCustom(this, et.getText().toString());
                        pintar.run();
                    })
                    .setNegativeButton("Cancelar", null)
                    .setNeutralButton("Restablecer", (d, w) -> {
                        Api.setUrlCustom(this, null);
                        pintar.run();
                    })
                    .show();
        });
        TextView diag = Ui.texto(this, "Diagnóstico de conexión", 15, R.color.lc_rojo, true);
        diag.setMinHeight(Ui.dp(this, 44));
        diag.setGravity(android.view.Gravity.CENTER_VERTICAL);
        p.contenido.addView(diag);
        diag.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticoActivity.class)));
        if (CloudApi.activo()) {   // el cliente no ve URL de servidor ni herramientas técnicas
            servidor.setVisibility(android.view.View.GONE);
            diag.setVisibility(android.view.View.GONE);
        }
        crear.setOnClickListener(v -> startActivity(new Intent(this, RegisterActivity.class)));
        entrar.setOnClickListener(v -> {
            String u = usuario.getText().toString().trim();
            String c = clave.getText().toString();
            if (u.isEmpty() || c.isEmpty()) { error.setText(CloudApi.activo() ? "Escribe tu correo y contraseña" : "Escribe usuario y contraseña"); return; }
            entrar.setEnabled(false);
            Executors.newSingleThreadExecutor().execute(() -> {
                JSONObject body = new JSONObject();
                try { body.put("username", u).put("password", c); } catch (Exception ignored) {}
                Api.Respuesta r = Api.post(this, "/auth/login", body);
                runOnUiThread(() -> {
                    entrar.setEnabled(true);
                    if (r.ok() && "CUSTOMER".equals(r.cuerpo.optString("role"))) {
                        Api.guardarSesion(this, r.cuerpo);
                        clave.setText("");
                        startActivity(new Intent(this, HomeActivity.class));
                        finish();
                    } else if (r.ok()) {
                        error.setText("Esta app es para clientes. Usa la app de técnico o L&C Control.");
                    } else {
                        error.setText(r.error());
                    }
                });
            });
        });
    }
}
