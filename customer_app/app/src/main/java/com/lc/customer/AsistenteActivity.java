package com.lc.customer;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

/**
 * Asistente automático bilingüe (ES/EN). Responde preguntas frecuentes por palabras clave: NO es inteligencia artificial y no
 * ejecuta acciones. Solo habla de las 4 categorías oficiales y de los precios de referencia vigentes (settings pricing.*);
 * no promete promociones, horarios, garantías ni membresías (no están definidos). Diseño y colores del Cliente.
 */
public class AsistenteActivity extends AppCompatActivity {

    private boolean en = false;
    private Ui.Pantalla p;
    private LinearLayout chat;
    private ScrollView sv;
    private EditText entrada;
    private TextView idioma;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        en = b != null && b.getBoolean("en", false);
        p = Ui.pantalla(this, en ? "Assistant" : "Asistente", en ? "Automated assistant · common questions" : "Asistente automático · preguntas frecuentes", true);
        idioma = Ui.texto(this, "", 14, R.color.lc_rojo, true);
        idioma.setMinHeight(Ui.dp(this, 44));
        idioma.setGravity(Gravity.CENTER_VERTICAL);
        idioma.setOnClickListener(v -> { en = !en; recrearTextos(); });
        p.contenido.addView(idioma);
        chat = new LinearLayout(this);
        chat.setOrientation(LinearLayout.VERTICAL);
        p.contenido.addView(chat);
        sv = (ScrollView) p.contenido.getParent();
        entrada = Ui.campo(this, p.inferior, "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        Button enviar = Ui.boton(this, p.inferior, "", true);
        enviar.setTag("enviar");
        enviar.setOnClickListener(v -> enviar());
        Button solicitar = Ui.boton(this, p.inferior, "", false);
        solicitar.setTag("solicitar");
        solicitar.setOnClickListener(v -> startActivity(new Intent(this, NewRequestActivity.class)));
        recrearTextos();
        burbuja(false, saludo());
        for (String s : sugerencias()) sugerencia(s);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putBoolean("en", en); }

    private void recrearTextos() {
        idioma.setText(en ? "Español" : "English");
        entrada.setHint(en ? "Write your question" : "Escribe tu pregunta");
        ((Button) p.inferior.findViewWithTag("enviar")).setText(en ? "Send" : "Enviar");
        ((Button) p.inferior.findViewWithTag("solicitar")).setText(en ? "Request service" : "Solicitar servicio");
    }

    private void enviar() {
        String q = entrada.getText().toString().trim();
        if (q.isEmpty()) return;
        entrada.setText("");
        burbuja(true, q);
        burbuja(false, responder(q.toLowerCase(Locale.ROOT)));
    }

    private void sugerencia(String s) {
        TextView t = Ui.texto(this, s, 14, R.color.lc_rojo, true);
        t.setBackgroundResource(R.drawable.bg_boton_sec);
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.dp(this, 8);
        t.setOnClickListener(v -> { burbuja(true, s); burbuja(false, responder(s.toLowerCase(Locale.ROOT))); });
        chat.addView(t, lp);
    }

    private void burbuja(boolean mio, String texto) {
        LinearLayout c = Ui.tarjeta(this, chat);
        c.setGravity(mio ? Gravity.END : Gravity.START);
        if (mio) c.getBackground().mutate().setTint(getResources().getColor(R.color.lc_suave));
        c.addView(Ui.texto(this, mio ? (en ? "You" : "Tú") : (en ? "Assistant" : "Asistente"), 12, R.color.lc_sec, true));
        c.addView(Ui.texto(this, texto, 15, R.color.lc_texto, false));
        sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private String saludo() {
        return en ? "Hello! I'm the L&C automated assistant. I can answer common questions about our four services: plumbing, basic electrical, air conditioning (A/C) and appliances. I'm not a person and I can't schedule visits; to request one, use «Request service»."
                : "¡Hola! Soy el asistente automático de L&C. Puedo responder preguntas frecuentes sobre nuestros cuatro servicios: plomería, electricidad básica, aire acondicionado (A/C) y electrodomésticos. No soy una persona y no puedo agendar visitas; para pedir una usa «Solicitar servicio».";
    }

    private String[] sugerencias() {
        return en ? new String[]{"My AC is not cooling", "I have a water leak", "How much is a visit?", "My washer is not working"}
                : new String[]{"Mi aire no enfría", "Tengo una fuga de agua", "¿Cuánto cuesta una visita?", "Mi lavadora no funciona"};
    }

    private String precioTxt(String grupo, String oficio) {
        double d = Ajustes.precio(this, grupo, "diagnostic"), h = Ajustes.precio(this, grupo, "hourly");
        return String.format(Locale.US, en ? "Reference price for %s: diagnostic visit $%.2f, $%.2f per hour. The technician confirms the price before working."
                : "Precio de referencia de %s: visita de diagnóstico $%.2f, $%.2f por hora. El técnico confirma el precio antes de trabajar.", oficio, d, h);
    }

    private static boolean tiene(String q, String... ks) { for (String k : ks) if (q.contains(k)) return true; return false; }

    private String responder(String q) {
        if (tiene(q, "precio", "cuesta", "cuánto", "cuanto", "costo", "price", "cost", "how much", "tarifa", "rate")) {
            return en ? "Reference prices (they can change):\n" + precioTxt("plumbing", "plumbing") + "\n" + precioTxt("electricity", "basic electrical") + "\n"
                    + precioTxt("hvac", "air conditioning") + "\n" + precioTxt("general", "appliances")
                    : "Precios de referencia (pueden cambiar):\n" + precioTxt("plumbing", "plomería") + "\n" + precioTxt("electricity", "electricidad básica") + "\n"
                    + precioTxt("hvac", "aire acondicionado") + "\n" + precioTxt("general", "electrodomésticos");
        }
        if (tiene(q, "aire", " ac", "ac ", "a/c", "hvac", "enfr", "frío", "frio", "cool", "heat", "calef", "air cond")) {
            return (en ? "Air conditioning (A/C): we diagnose and repair cooling problems, leaks and noises. Describe what is happening in «Request service»; you can add a photo.\n"
                    : "Aire acondicionado (A/C): diagnosticamos y reparamos fallas de enfriamiento, goteras y ruidos. Describe qué pasa en «Solicitar servicio»; puedes agregar una foto.\n") + precioTxt("hvac", en ? "A/C" : "A/C");
        }
        if (tiene(q, "agua", "fuga", "plom", "tuber", "grifo", "leak", "plumb", "water", "pipe", "faucet", "drain", "desag")) {
            return (en ? "Plumbing: leaks, clogged drains, faucets and pipes. If water is flowing, close the main valve if you can and request service.\n"
                    : "Plomería: fugas, desagües tapados, grifos y tuberías. Si hay agua corriendo, cierra la llave de paso si puedes y solicita el servicio.\n") + precioTxt("plumbing", en ? "plumbing" : "plomería");
        }
        if (tiene(q, "electr", "luz", "breaker", "enchufe", "outlet", "socket", "switch", "apagador", "cable", "wiring")) {
            return (en ? "Basic electrical: outlets, switches, lights and breakers. For your safety, do not touch exposed wires. Request service and describe the problem.\n"
                    : "Electricidad básica: tomas, interruptores, luces y breakers. Por tu seguridad, no toques cables expuestos. Solicita el servicio y describe el problema.\n") + precioTxt("electricity", en ? "basic electrical" : "electricidad básica");
        }
        if (tiene(q, "lavadora", "secadora", "nevera", "refriger", "estufa", "horno", "lavavaj", "washer", "dryer", "fridge", "stove", "oven", "dishwasher", "appliance", "electrodom")) {
            return (en ? "Appliances: washers, dryers, refrigerators, stoves, ovens and dishwashers. Tell us the brand and the symptom when you request service.\n"
                    : "Electrodomésticos: lavadoras, secadoras, neveras, estufas, hornos y lavavajillas. Indícanos la marca y el síntoma al solicitar el servicio.\n") + precioTxt("general", en ? "appliances" : "electrodomésticos");
        }
        if (tiene(q, "cita", "agendar", "visita", "schedule", "appointment", "when", "cuándo", "cuando")) {
            return en ? "I can't schedule visits. Tap «Request service», choose your service and a preferred date. The date is only a preference: L&C confirms the appointment."
                    : "No puedo agendar visitas. Toca «Solicitar servicio», elige el servicio y una fecha preferida. La fecha es solo una preferencia: L&C confirma la cita.";
        }
        if (tiene(q, "hola", "hello", "buenas", "buenos", "hi ", "hey")) return saludo();
        if (tiene(q, "gracias", "thank")) return en ? "You're welcome!" : "¡Con gusto!";
        return en ? "I didn't understand. I can answer about plumbing, basic electrical, A/C, appliances and reference prices. For anything else, use «Request service» and describe it."
                : "No te entendí. Puedo responder sobre plomería, electricidad básica, A/C, electrodomésticos y precios de referencia. Para otra cosa, usa «Solicitar servicio» y descríbelo.";
    }
}
