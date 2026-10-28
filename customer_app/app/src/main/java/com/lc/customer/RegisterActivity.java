package com.lc.customer;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.concurrent.Executors;

public class RegisterActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.Pantalla p = Ui.pantalla(this, "Crear cuenta", "Tus datos de contacto", true);
        EditText nombre = Ui.campo(this, p.contenido, "Nombre completo",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        EditText tel = Ui.campo(this, p.contenido, "Teléfono", InputType.TYPE_CLASS_PHONE);
        EditText email = Ui.campo(this, p.contenido, CloudApi.activo() ? "Correo electrónico" : "Email (opcional)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        Ui.seccion(this, p.contenido, "Acceso");
        EditText usuario = Ui.campo(this, p.contenido, "Usuario (3-40: letras, números, . _ -)", InputType.TYPE_CLASS_TEXT);
        if (CloudApi.activo()) usuario.setVisibility(android.view.View.GONE);   // en QA se entra con el correo
        EditText clave = Ui.campo(this, p.contenido, "Contraseña (mínimo 8)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView error = Ui.texto(this, "", 14, R.color.lc_rojo, false);
        p.contenido.addView(error);
        Button crear = Ui.boton(this, p.inferior, "Crear cuenta", true);
        crear.setOnClickListener(v -> {
            String n = nombre.getText().toString().trim();
            String d = tel.getText().toString().replaceAll("\\D", "");
            if (n.length() < 3) { nombre.setError("Escribe tu nombre"); return; }
            if (d.length() != 10 && !(d.length() == 11 && d.startsWith("1"))) { tel.setError("Teléfono de 10 dígitos"); return; }
            if (CloudApi.activo() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email.getText().toString().trim()).matches()) {
                email.setError("Escribe un correo válido"); email.requestFocus(); return;
            }
            if (clave.getText().length() < 8) { clave.setError("Mínimo 8 caracteres"); return; }
            crear.setEnabled(false);
            Executors.newSingleThreadExecutor().execute(() -> {
                JSONObject body = new JSONObject();
                try {
                    body.put("nombre", n).put("telefono", tel.getText().toString().trim())
                            .put("email", email.getText().toString().trim())
                            .put("username", usuario.getText().toString().trim())
                            .put("password", clave.getText().toString());
                } catch (Exception ignored) {}
                Api.Respuesta r = Api.post(this, "/auth/register", body);
                runOnUiThread(() -> {
                    crear.setEnabled(true);
                    if (r.ok()) {
                        Api.guardarSesion(this, r.cuerpo);
                        Intent i = new Intent(this, HomeActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } else {
                        error.setText(r.error());
                    }
                });
            });
        });
    }
}
