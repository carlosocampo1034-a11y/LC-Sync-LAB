package com.lc.customer;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

public class ChatActivity extends AppCompatActivity {

    private LinearLayout mensajes;
    private ScrollView sv;
    private EditText entrada;
    private boolean en;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        en = Prefs.en(this);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Prefs.fondo(this));

        // Cabecera
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.VERTICAL);
        cab.setBackgroundColor(Prefs.fondo(this));
        cab.setPadding(dp(20), dp(20), dp(20), dp(12));

        TextView volver = tv(en ? "← Back" : "← Volver", 14, Prefs.ROJO, true);
        volver.setMinHeight(dp(44));
        volver.setGravity(Gravity.CENTER_VERTICAL);
        volver.setOnClickListener(v -> finish());
        cab.addView(volver);

        cab.addView(tv(en ? "Your L&C Assistant" : "Tu Asistente L&C", 22, Prefs.texto(this), true));

        TextView sub = tv("✨  HVAC · " + (en ? "Plumbing · Electric · Appliances"
                : "Plomería · Electricidad · Electrodomésticos"), 13, Prefs.sec(this), false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        sub.setLayoutParams(subLp);
        cab.addView(sub);
        raiz.addView(cab);

        // Línea
        android.view.View linea = new android.view.View(this);
        linea.setBackgroundColor(Prefs.borde(this));
        raiz.addView(linea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Área de mensajes
        sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setBackgroundColor(Prefs.fondo(this));
        mensajes = new LinearLayout(this);
        mensajes.setOrientation(LinearLayout.VERTICAL);
        mensajes.setPadding(dp(16), dp(12), dp(16), dp(16));
        sv.addView(mensajes);
        raiz.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Separador sobre la barra de entrada
        android.view.View sep = new android.view.View(this);
        sep.setBackgroundColor(Prefs.borde(this));
        raiz.addView(sep, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Barra de entrada
        LinearLayout barra = new LinearLayout(this);
        barra.setOrientation(LinearLayout.HORIZONTAL);
        barra.setBackgroundColor(Prefs.fondo(this));
        barra.setPadding(dp(12), dp(8), dp(12), dp(8));

        entrada = new EditText(this);
        entrada.setHint(en ? "Type your question here…" : "Escribe tu pregunta aquí…");
        entrada.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        entrada.setImeOptions(EditorInfo.IME_ACTION_SEND);
        entrada.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        entrada.setTextColor(Prefs.texto(this));
        entrada.setHintTextColor(Prefs.sec(this));

        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setCornerRadius(dp(14));
        inputBg.setColor(Prefs.inputBg(this));
        inputBg.setStroke(dp(1), Prefs.inputBorde(this));
        entrada.setBackground(inputBg);
        entrada.setPadding(dp(14), dp(12), dp(14), dp(12));

        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        entrada.setLayoutParams(etLp);
        barra.addView(entrada);

        TextView enviarBtn = tv(en ? "Send" : "Enviar", 14, Prefs.ROJO, true);
        enviarBtn.setPadding(dp(14), dp(12), dp(8), dp(12));
        enviarBtn.setMinHeight(dp(48));
        enviarBtn.setGravity(Gravity.CENTER);
        barra.addView(enviarBtn);

        raiz.addView(barra);
        setContentView(raiz);

        ViewCompat.setOnApplyWindowInsetsListener(raiz, (view, ins) -> {
            Insets sys = ins.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return ins;
        });

        agregarMensajeIA(saludo());
        agregarSugerencias();

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
            if (debeOfrecerSolicitud(txt.toLowerCase())) {
                agregarBotonSolicitud();
            }
        }, 600);
    }

    // ── Saludos y sugerencias ─────────────────────────────────────────────────

    private String saludo() {
        return en
            ? "Hello! Welcome to L&C Home Services. I'm your virtual assistant, here to help you take care of your home. How can I help you today?"
            : "¡Hola! Bienvenido a L&C Home Services. Soy tu asistente virtual y estoy aquí para ayudarte a cuidar tu hogar. ¿En qué puedo ayudarte hoy?";
    }

    private String[] sugerencias() {
        return en ? new String[]{
            "What does the $50 tune-up include?",
            "My AC is not cooling",
            "I have a water leak",
            "I want to schedule a visit",
            "What membership plans do you have?"
        } : new String[]{
            "¿Qué incluye el mantenimiento por $50?",
            "Mi AC no enfría",
            "Tengo una fuga de agua",
            "Quiero agendar una visita",
            "¿Qué planes de membresía tienen?"
        };
    }

    // ── Lógica del asistente bilingüe ─────────────────────────────────────────

    private String responder(String q) {
        // Precio / promo
        if (q.contains("50") || q.contains("promo") || q.contains("oferta")
                || q.contains("introductorio") || q.contains("precio") || q.contains("costo")
                || q.contains("price") || q.contains("cost") || q.contains("how much")) {
            return en
                ? "🎉 We offer an introductory tune-up starting at $50. It includes a system check and basic diagnosis. The exact terms and availability are confirmed by our team when scheduling. Would you like to request a visit?"
                : "🎉 Ofrecemos un mantenimiento introductorio desde $50. Incluye revisión del sistema y diagnóstico básico. Las condiciones exactas y disponibilidad las confirma nuestro equipo al agendar. ¿Te gustaría solicitar una visita?";
        }
        // HVAC
        if (q.contains("ac") || q.contains("aire") || q.contains("hvac") || q.contains("frío")
                || q.contains("frio") || q.contains("calor") || q.contains("heat")
                || q.contains("cool") || q.contains("aire acondicionado")) {
            return en
                ? "We handle HVAC systems: air conditioning, heating, and ventilation. We can run diagnostics, preventive maintenance, repairs, and replacements. Is your system not turning on, not cooling/heating properly, or making unusual noises?"
                : "Atendemos sistemas de HVAC: aire acondicionado, calefacción y ventilación. Podemos realizar diagnósticos, mantenimiento preventivo, reparaciones y reemplazos. ¿Tu sistema no enciende, no enfría/calienta correctamente, o escuchas ruidos inusuales?";
        }
        // Plomería
        if (q.contains("agua") || q.contains("plom") || q.contains("fuga")
                || q.contains("tubería") || q.contains("leak") || q.contains("plumb")
                || q.contains("water") || q.contains("pipe") || q.contains("calentador")) {
            return en
                ? "We handle plumbing issues: leaks, pipes, water heaters, and more. Can you briefly describe the problem? You can also send a photo so our team can evaluate it."
                : "Atendemos problemas de plomería: fugas, tuberías, calentadores de agua, y más. ¿Puedes describir brevemente el problema? Puedes también enviar una foto para que nuestro equipo lo evalúe.";
        }
        // Electricidad
        if (q.contains("electr") || q.contains("luz") || q.contains("braker")
                || q.contains("breaker") || q.contains("panel") || q.contains("wiring")
                || q.contains("outlet") || q.contains("enchufe")) {
            return en
                ? "We offer electrical services: inspections, panel repairs, outlet installation, and more. For safety, any electrical issue should be addressed promptly. Would you like to schedule a visit?"
                : "Ofrecemos servicios eléctricos: inspecciones, reparaciones de paneles, instalación de tomas y más. Por seguridad, cualquier problema eléctrico debe atenderse pronto. ¿Quieres agendar una visita?";
        }
        // Electrodomésticos
        if (q.contains("refriger") || q.contains("lavadora") || q.contains("secadora")
                || q.contains("dishwasher") || q.contains("dryer") || q.contains("washer")
                || q.contains("appliance") || q.contains("nevera") || q.contains("estufa")
                || q.contains("stove")) {
            return en
                ? "We repair appliances: refrigerators, washers, dryers, dishwashers, and more. Tell me which appliance has the issue and what symptoms you're noticing."
                : "Reparamos electrodomésticos: refrigeradores, lavadoras, secadoras, lavavajillas y más. Cuéntame qué aparato presenta el problema y cuál es el síntoma que observas.";
        }
        // Membresía
        if (q.contains("membresía") || q.contains("membresia") || q.contains("membership")
                || q.contains("plan") || q.contains("gold") || q.contains("silver")) {
            return en
                ? "We have membership plans that include preventive maintenance and discounts on repairs. Specific plans and pricing are handled by our team. Would you like someone to contact you to explain the options?"
                : "Tenemos planes de membresía que incluyen mantenimientos preventivos y descuentos en reparaciones. Los planes y precios exactos los administra nuestro equipo. ¿Te gustaría que alguien te contacte para explicarte las opciones?";
        }
        // Foto
        if (q.contains("foto") || q.contains("photo") || q.contains("image")
                || q.contains("imagen") || q.contains("picture")) {
            return en
                ? "You can send photos of the problem directly from the home screen using the «Request Service» button. Our team reviews them before the visit. Would you like to go to that option?"
                : "Puedes enviar fotos del problema directamente desde la pantalla principal con el botón «Enviar Foto». Nuestro equipo las revisa antes de la visita. ¿Deseas ir a esa opción?";
        }
        // Cita/agendar
        if (q.contains("cita") || q.contains("visita") || q.contains("agendar")
                || q.contains("schedule") || q.contains("appointment") || q.contains("cuando")
                || q.contains("cuándo") || q.contains("mañana") || q.contains("hoy")
                || q.contains("today") || q.contains("tomorrow")) {
            return en
                ? "To schedule a visit, tap «Request Service» on the home screen or use the button below. Our team will contact you to confirm the date and time that works best for you."
                : "Para agendar una visita, toca «Solicitar Servicio» en la pantalla principal o usa el botón de abajo. Nuestro equipo se pondrá en contacto contigo para confirmar la fecha y hora que mejor te convenga.";
        }
        // Disponibilidad
        if (q.contains("disponib") || q.contains("availab") || q.contains("horario")
                || q.contains("hours")) {
            return en
                ? "We serve Monday through Saturday. To confirm exact availability for your area and service, our team will reach out after receiving your request. Would you like to send it now?"
                : "Atendemos de lunes a sábado. Para confirmar la disponibilidad exacta para tu zona y servicio, nuestro equipo te contactará tras recibir tu solicitud. ¿Quieres enviarla ahora?";
        }
        // Garantía
        if (q.contains("garantía") || q.contains("garantia") || q.contains("warranty")) {
            return en
                ? "We offer warranties on our work. The specific terms depend on the type of service and are detailed before starting the job. Do you have a question about a previous service?"
                : "Ofrecemos garantía en nuestros trabajos. Los términos específicos dependen del tipo de servicio y se detallan antes de iniciar el trabajo. ¿Tienes alguna pregunta sobre un servicio anterior?";
        }
        // Saludo
        if (q.contains("hola") || q.contains("hello") || q.contains("hi")
                || q.contains("buenos") || q.contains("buenas") || q.contains("good morning")
                || q.contains("good afternoon")) {
            return en
                ? "Hello! Glad you're here. I'm the L&C Home Services assistant. I can help you with information about our HVAC, plumbing, electrical, and appliance services, or guide you to request a visit. What do you need?"
                : "¡Hola! Me alegra que estés aquí. Soy el asistente de L&C Home Services. Puedo ayudarte con información sobre nuestros servicios de HVAC, plomería, electricidad y electrodomésticos, o guiarte para solicitar una visita. ¿Qué necesitas?";
        }
        // Gracias
        if (q.contains("gracias") || q.contains("thank") || q.contains("perfect")
                || q.contains("perfecto")) {
            return en
                ? "You're welcome! I'm here for whatever you need. If you'd like to request a service, use the «Request Service» button or write to me for guidance. 😊"
                : "¡Con gusto! Estoy aquí para lo que necesites. Si quieres solicitar un servicio, usa el botón «Solicitar Servicio» o escríbeme para guiarte en el proceso. 😊";
        }
        // Fallback
        return en
            ? "I received your message. For the best assistance, one of our advisors should contact you. Would you like us to take your details and give you a call? You can also use «Request Service» to send us the details."
            : "Entendí tu mensaje. Para darte la mejor atención, lo mejor es que uno de nuestros asesores te contacte. ¿Quieres que tomemos tus datos y te llamemos? Puedes también usar «Solicitar Servicio» para enviarnos los detalles.";
    }

    private boolean debeOfrecerSolicitud(String q) {
        return q.contains("sí") || q.contains("si") || q.contains("yes") || q.contains("quiero")
                || q.contains("ok") || q.contains("agendar") || q.contains("solicitar")
                || q.contains("visita") || q.contains("cita") || q.contains("schedule")
                || q.contains("appointment");
    }

    // ── Construcción de mensajes ──────────────────────────────────────────────

    private void agregarMensajeIA(String txt) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        row.setLayoutParams(rowLp);

        TextView avatar = tv("✨", 18, Prefs.texto(this), false);
        avatar.setPadding(0, dp(4), dp(8), 0);
        row.addView(avatar);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadii(new float[]{dp(2), dp(2), dp(14), dp(14), dp(14), dp(14), dp(14), dp(14)});
        bg.setColor(Prefs.aiBubble(this));
        bg.setStroke(dp(1), Prefs.aiBorde(this));

        TextView bubble = tv(txt, 14, Prefs.texto(this), false);
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

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadii(new float[]{dp(14), dp(14), dp(2), dp(2), dp(14), dp(14), dp(14), dp(14)});
        bg.setColor(Prefs.ROJO);

        TextView bubble = new TextView(this);
        bubble.setText(txt);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        bubble.setTextColor(Color.WHITE);
        bubble.setBackground(bg);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubble.setLineSpacing(0, 1.3f);
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.75f);
        bubble.setMaxWidth(maxW);
        bubble.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(bubble);
        mensajes.addView(row);
        desplazar();
    }

    private void agregarSugerencias() {
        TextView hint = tv(en ? "Suggested topics:" : "Temas sugeridos:", 13, Prefs.sec(this), false);
        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hLp.topMargin = dp(16);
        hint.setLayoutParams(hLp);
        mensajes.addView(hint);

        for (String s : sugerencias()) {
            TextView chip = tv(s, 13, Prefs.ROJO, false);
            GradientDrawable gd = new GradientDrawable();
            gd.setCornerRadius(dp(20));
            gd.setColor(Prefs.aiBubble(this));
            gd.setStroke(dp(1), Prefs.aiBorde(this));
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
        btn.setText(en ? "REQUEST SERVICE →" : "SOLICITAR SERVICIO →");
        btn.setAllCaps(true);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(Color.WHITE);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(dp(12));
        gd.setColor(Prefs.ROJO);
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
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
