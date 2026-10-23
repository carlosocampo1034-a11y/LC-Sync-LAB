package com.lc.customer;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Asistente virtual de L&C Home Services.
 * Responde en español/inglés. Lista para conectar a una IA real vía API HTTP.
 */
public class ChatActivity extends AppCompatActivity {

    private LinearLayout mensajes;
    private ScrollView sv;
    private EditText entrada;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Color.WHITE);

        // Cabecera
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.VERTICAL);
        cab.setBackgroundColor(Color.WHITE);
        cab.setPadding(dp(20), dp(20), dp(20), dp(12));
        TextView volver = tv("← Volver", 14, R.color.lc_rojo, true);
        volver.setMinHeight(dp(44));
        volver.setGravity(Gravity.CENTER_VERTICAL);
        volver.setOnClickListener(v -> finish());
        cab.addView(volver);
        TextView titulo = tv("Tu Asistente L&C", 22, R.color.lc_texto, true);
        cab.addView(titulo);
        TextView sub = tv("✨  HVAC · Plomería · Electricidad · Electrodomésticos", 13, R.color.lc_sec, false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        sub.setLayoutParams(subLp);
        cab.addView(sub);
        raiz.addView(cab);

        // Línea
        android.view.View linea = new android.view.View(this);
        linea.setBackgroundColor(ContextCompat.getColor(this, R.color.lc_borde));
        raiz.addView(linea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Área de mensajes
        sv = new ScrollView(this);
        sv.setClipToPadding(false);
        mensajes = new LinearLayout(this);
        mensajes.setOrientation(LinearLayout.VERTICAL);
        mensajes.setPadding(dp(16), dp(12), dp(16), dp(16));
        sv.addView(mensajes);
        raiz.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Barra de entrada
        LinearLayout barra = new LinearLayout(this);
        barra.setOrientation(LinearLayout.HORIZONTAL);
        barra.setBackgroundColor(Color.WHITE);
        barra.setPadding(dp(12), dp(8), dp(12), dp(8));

        // Separador sobre la barra
        android.view.View sep = new android.view.View(this);
        sep.setBackgroundColor(ContextCompat.getColor(this, R.color.lc_borde));
        raiz.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        entrada = new EditText(this);
        entrada.setHint("Escribe tu pregunta aquí…");
        entrada.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        entrada.setImeOptions(EditorInfo.IME_ACTION_SEND);
        entrada.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_input));
        entrada.setPadding(dp(14), dp(12), dp(14), dp(12));
        entrada.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        entrada.setTextColor(ContextCompat.getColor(this, R.color.lc_texto));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        entrada.setLayoutParams(etLp);
        barra.addView(entrada);

        TextView enviarBtn = tv("Enviar", 14, R.color.lc_rojo, true);
        enviarBtn.setPadding(dp(14), dp(12), dp(8), dp(12));
        enviarBtn.setMinHeight(dp(48));
        enviarBtn.setGravity(Gravity.CENTER);
        barra.addView(enviarBtn);

        raiz.addView(barra);
        setContentView(raiz);

        // Edge-to-edge
        ViewCompat.setOnApplyWindowInsetsListener(raiz, (view, ins) -> {
            Insets sys = ins.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return ins;
        });

        // Mensaje de bienvenida
        agregarMensajeIA(getString(R.string.ai_saludo));
        agregarSugerencias();

        // Enviar
        enviarBtn.setOnClickListener(v -> procesar());
        entrada.setOnEditorActionListener((tv2, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEND) { procesar(); return true; }
            return false;
        });
    }

    private void procesar() {
        String txt = entrada.getText().toString().trim();
        if (txt.isEmpty()) return;
        agregarMensajeUsuario(txt);
        entrada.setText("");
        String resp = responder(txt.toLowerCase());
        mensajes.postDelayed(() -> {
            agregarMensajeIA(resp);
            // Si el usuario quiere solicitar, ofrecer botón
            if (debeOfrecerSolicitud(txt.toLowerCase())) {
                agregarBotonSolicitud();
            }
        }, 600);
    }

    // ── Lógica del asistente ──────────────────────────────────────────────────

    private String responder(String q) {
        // Mantenimiento introductorio
        if (q.contains("50") || q.contains("promo") || q.contains("oferta")
                || q.contains("introductorio") || q.contains("precio") || q.contains("costo")
                || q.contains("price") || q.contains("cost") || q.contains("how much")) {
            return "🎉 Ofrecemos un mantenimiento introductorio desde $50. Incluye revisión del sistema y diagnóstico básico. Las condiciones exactas y disponibilidad las confirma nuestro equipo al agendar. ¿Te gustaría solicitar una visita?";
        }
        // HVAC
        if (q.contains("ac") || q.contains("aire") || q.contains("hvac") || q.contains("frío")
                || q.contains("frio") || q.contains("calor") || q.contains("heat") || q.contains("cool")
                || q.contains("aire acondicionado")) {
            return "Atendemos sistemas de HVAC: aire acondicionado, calefacción y ventilación. Podemos realizar diagnósticos, mantenimiento preventivo, reparaciones y reemplazos. ¿Tu sistema no enciende, no enfría/calienta correctamente, o escuchas ruidos inusuales?";
        }
        // Plomería
        if (q.contains("agua") || q.contains("plom") || q.contains("fuga") || q.contains("tubería")
                || q.contains("leak") || q.contains("plumb") || q.contains("water") || q.contains("pipe")
                || q.contains("water heater") || q.contains("calentador")) {
            return "Atendemos problemas de plomería: fugas, tuberías, calentadores de agua, y más. ¿Puedes describir brevemente el problema? Puedes también enviar una foto para que nuestro equipo lo evalúe.";
        }
        // Electricidad
        if (q.contains("electr") || q.contains("luz") || q.contains("braker") || q.contains("breaker")
                || q.contains("panel") || q.contains("wiring") || q.contains("outlet") || q.contains("enchufe")) {
            return "Ofrecemos servicios eléctricos: inspecciones, reparaciones de paneles, instalación de tomas y más. Por seguridad, cualquier problema eléctrico debe atenderse pronto. ¿Quieres agendar una visita?";
        }
        // Electrodomésticos
        if (q.contains("refriger") || q.contains("lavadora") || q.contains("secadora")
                || q.contains("dishwasher") || q.contains("dryer") || q.contains("washer")
                || q.contains("appliance") || q.contains("nevera") || q.contains("estufa") || q.contains("stove")) {
            return "Reparamos electrodomésticos: refrigeradores, lavadoras, secadoras, lavavajillas y más. Cuéntame qué aparato presenta el problema y cuál es el síntoma que observas.";
        }
        // Membresía
        if (q.contains("membresía") || q.contains("membresia") || q.contains("membership")
                || q.contains("plan") || q.contains("gold") || q.contains("silver")) {
            return "Tenemos planes de membresía que incluyen mantenimientos preventivos y descuentos en reparaciones. Los planes y precios exactos los administra nuestro equipo. ¿Te gustaría que alguien te contacte para explicarte las opciones?";
        }
        // Foto
        if (q.contains("foto") || q.contains("photo") || q.contains("image") || q.contains("imagen")
                || q.contains("picture") || q.contains("ver")) {
            return "Puedes enviar fotos del problema directamente desde la pantalla principal con el botón «Enviar Foto». Nuestro equipo las revisa antes de la visita. ¿Deseas ir a esa opción?";
        }
        // Cita/agendar
        if (q.contains("cita") || q.contains("visita") || q.contains("agendar") || q.contains("schedule")
                || q.contains("appointment") || q.contains("cuando") || q.contains("cuándo")
                || q.contains("mañana") || q.contains("hoy")) {
            return "Para agendar una visita, toca «Solicitar Servicio» en la pantalla principal o usa el botón de abajo. Nuestro equipo se pondrá en contacto contigo para confirmar la fecha y hora que mejor te convenga.";
        }
        // Disponibilidad
        if (q.contains("disponib") || q.contains("availab") || q.contains("horario") || q.contains("hours")) {
            return "Atendemos de lunes a sábado. Para confirmar la disponibilidad exacta para tu zona y servicio, nuestro equipo te contactará tras recibir tu solicitud. ¿Quieres enviarla ahora?";
        }
        // Garantía
        if (q.contains("garantía") || q.contains("garantia") || q.contains("warranty") || q.contains("warranty")) {
            return "Ofrecemos garantía en nuestros trabajos. Los términos específicos dependen del tipo de servicio y se detallan antes de iniciar el trabajo. ¿Tienes alguna pregunta sobre un servicio anterior?";
        }
        // Saludo
        if (q.contains("hola") || q.contains("hello") || q.contains("hi") || q.contains("buenos")
                || q.contains("buenas") || q.contains("good morning") || q.contains("good afternoon")) {
            return "¡Hola! Me alegra que estés aquí. Soy el asistente de L&C Home Services. Puedo ayudarte con información sobre nuestros servicios de HVAC, plomería, electricidad y electrodomésticos, o guiarte para solicitar una visita. ¿Qué necesitas?";
        }
        // Gracias
        if (q.contains("gracias") || q.contains("thank") || q.contains("perfect") || q.contains("perfecto")) {
            return "¡Con gusto! Estoy aquí para lo que necesites. Si quieres solicitar un servicio, usa el botón «Solicitar Servicio» o escríbeme para guiarte en el proceso. 😊";
        }
        // Fallback — invitar a solicitar
        return "Entendí tu mensaje. Para darte la mejor atención, lo mejor es que uno de nuestros asesores te contacte. ¿Quieres que tomemos tus datos y te llamemos? Puedes también usar «Solicitar Servicio» para enviarnos los detalles.";
    }

    private boolean debeOfrecerSolicitud(String q) {
        return q.contains("sí") || q.contains("si") || q.contains("yes") || q.contains("quiero")
                || q.contains("ok") || q.contains("agendar") || q.contains("solicitar")
                || q.contains("visita") || q.contains("cita");
    }

    // ── Construcción de mensajes ──────────────────────────────────────────────

    private void agregarMensajeIA(String txt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        row.setLayoutParams(rowLp);

        TextView avatar = tv("✨", 18, R.color.lc_texto, false);
        avatar.setPadding(0, dp(4), dp(8), 0);
        row.addView(avatar);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadii(new float[]{dp(2), dp(2), dp(14), dp(14), dp(14), dp(14), dp(14), dp(14)});
        bg.setColor(ContextCompat.getColor(this, R.color.lc_ai_bubble));
        bg.setStroke(dp(1), ContextCompat.getColor(this, R.color.lc_ai_borde));

        TextView bubble = tv(txt, 14, R.color.lc_texto, false);
        bubble.setBackground(bg);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubble.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        bubble.setLayoutParams(bLp);
        row.addView(bubble);
        mensajes.addView(row);
        desplazar();
    }

    private void agregarMensajeUsuario(String txt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        row.setLayoutParams(rowLp);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadii(new float[]{dp(14), dp(14), dp(2), dp(2), dp(14), dp(14), dp(14), dp(14)});
        bg.setColor(ContextCompat.getColor(this, R.color.lc_rojo));

        TextView bubble = new TextView(this);
        bubble.setText(txt);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bubble.setTextColor(Color.WHITE);
        bubble.setBackground(bg);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubble.setLineSpacing(0, 1.3f);
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.75f);
        bubble.setMaxWidth(maxW);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bubble.setLayoutParams(bLp);
        row.addView(bubble);
        mensajes.addView(row);
        desplazar();
    }

    private void agregarSugerencias() {
        TextView hint = tv("Temas sugeridos:", 13, R.color.lc_sec, false);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(16);
        hint.setLayoutParams(hLp);
        mensajes.addView(hint);

        String[] sugs = {
                "¿Qué incluye el mantenimiento por $50?",
                "Mi AC no enfría",
                "Tengo una fuga de agua",
                "Quiero agendar una visita",
                "¿Qué planes de membresía tienen?"
        };
        for (String s : sugs) {
            TextView chip = tv(s, 13, R.color.lc_rojo, false);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadius(dp(20));
            gd.setColor(ContextCompat.getColor(this, R.color.lc_ai_bubble));
            gd.setStroke(dp(1), ContextCompat.getColor(this, R.color.lc_ai_borde));
            chip.setBackground(gd);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cLp.topMargin = dp(6);
            chip.setLayoutParams(cLp);
            chip.setOnClickListener(v -> {
                entrada.setText(s);
                procesar();
            });
            mensajes.addView(chip);
        }
    }

    private void agregarBotonSolicitud() {
        android.widget.Button btn = new android.widget.Button(this);
        btn.setText("SOLICITAR SERVICIO →");
        btn.setAllCaps(true);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(Color.WHITE);
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setCornerRadius(dp(12));
        gd.setColor(ContextCompat.getColor(this, R.color.lc_rojo));
        btn.setBackground(gd);
        btn.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp.topMargin = dp(10);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> startActivity(new Intent(this, GuestRequestActivity.class)));
        mensajes.addView(btn);
        desplazar();
    }

    private void desplazar() {
        sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private TextView tv(String t, int sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(ContextCompat.getColor(this, color));
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
