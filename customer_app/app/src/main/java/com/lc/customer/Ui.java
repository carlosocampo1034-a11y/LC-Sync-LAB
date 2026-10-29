package com.lc.customer;

import android.app.Activity;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Construcción de pantallas con la identidad de L&C (adaptada al cliente). */
final class Ui {

    private Ui() {}

    static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    /** Pantalla con cabecera fija, contenido desplazable y barra inferior; respeta barras y teclado (Android 16). */
    static final class Pantalla {
        final LinearLayout raiz, cabecera, contenido, inferior;
        Pantalla(LinearLayout r, LinearLayout c, LinearLayout co, LinearLayout i) {
            raiz = r; cabecera = c; contenido = co; inferior = i;
        }
    }

    static Pantalla pantalla(Activity a, String titulo, String subtitulo, boolean conAtras) {
        a.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout raiz = new LinearLayout(a);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(ContextCompat.getColor(a, R.color.lc_fondo));
        LinearLayout cab = new LinearLayout(a);
        cab.setOrientation(LinearLayout.VERTICAL);
        cab.setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 12));
        if (conAtras) {
            TextView atras = texto(a, "← Volver", 15, R.color.lc_rojo, true);
            atras.setPadding(0, dp(a, 4), 0, dp(a, 8));
            atras.setMinHeight(dp(a, 44));
            atras.setGravity(Gravity.CENTER_VERTICAL);
            atras.setOnClickListener(v -> a.finish());
            cab.addView(atras);
        }
        cab.addView(texto(a, titulo, 28, R.color.lc_texto, true));
        if (subtitulo != null) cab.addView(texto(a, subtitulo, 14, R.color.lc_sec, false));
        View linea = new View(a);
        linea.setBackgroundColor(ContextCompat.getColor(a, R.color.lc_borde));
        ScrollView sv = new ScrollView(a);
        sv.setClipToPadding(false);
        LinearLayout cont = new LinearLayout(a);
        cont.setOrientation(LinearLayout.VERTICAL);
        cont.setPadding(dp(a, 20), dp(a, 16), dp(a, 20), dp(a, 24));
        sv.addView(cont);
        LinearLayout inf = new LinearLayout(a);
        inf.setOrientation(LinearLayout.VERTICAL);
        inf.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 16));
        raiz.addView(cab);
        raiz.addView(linea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 1)));
        raiz.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        raiz.addView(inf);
        // Edge-to-edge: barra de estado arriba; navegación o teclado abajo (lo mayor)
        final int top0 = raiz.getPaddingTop(), bot0 = raiz.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(raiz, (v, ins) -> {
            Insets b = ins.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int ime = ins.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            v.setPadding(b.left, top0 + b.top, b.right, bot0 + Math.max(b.bottom, ime));
            return ins;
        });
        a.setContentView(raiz);
        return new Pantalla(raiz, cab, cont, inf);
    }

    static TextView texto(Activity a, String t, int sp, int color, boolean negrita) {
        TextView tv = new TextView(a);
        tv.setText(t);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(ContextCompat.getColor(a, color));
        if (negrita) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    static TextView seccion(Activity a, LinearLayout dest, String t) {
        TextView tv = texto(a, t, 18, R.color.lc_texto, true);
        tv.setPadding(0, dp(a, 18), 0, dp(a, 8));
        dest.addView(tv);
        return tv;
    }

    static EditText campo(Activity a, LinearLayout dest, String hint, int tipo) {
        EditText et = new EditText(a);
        et.setHint(hint);
        et.setInputType(tipo);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setTextColor(ContextCompat.getColor(a, R.color.lc_texto));
        et.setHintTextColor(ContextCompat.getColor(a, R.color.lc_sec));
        et.setBackground(ContextCompat.getDrawable(a, R.drawable.bg_input));
        et.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        et.setMinHeight(dp(a, 52));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 10);
        dest.addView(et, lp);
        return et;
    }

    static EditText campo(Activity a, LinearLayout dest, String hint) {
        return campo(a, dest, hint, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    }

    static Button boton(Activity a, LinearLayout dest, String t, boolean principal) {
        Button b = new Button(a);
        b.setText(t);
        b.setAllCaps(true);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(principal ? 0xFFFFFFFF : ContextCompat.getColor(a, R.color.lc_texto));
        b.setBackground(ContextCompat.getDrawable(a, principal ? R.drawable.bg_boton : R.drawable.bg_boton_sec));
        b.setStateListAnimator(null);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 52));
        lp.topMargin = dp(a, 10);
        dest.addView(b, lp);
        return b;
    }

    static LinearLayout tarjeta(Activity a, LinearLayout dest) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(ContextCompat.getDrawable(a, R.drawable.bg_card));
        c.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, 10);
        dest.addView(c, lp);
        return c;
    }

    /**
     * Insignia de estado con la paleta oficial: gris (pendiente, pausado, repuesto, cancelado), negro (en ruta, llegué,
     * esperando aprobación), rojo (en reparación) y verde SOLO para completado. Se decide por el id del estado.
     */
    static TextView badgeEstado(Activity a, String idEstado, String texto) {
        TextView b = texto(a, texto, 12, android.R.color.white, true);
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(ContextCompat.getDrawable(a, R.drawable.bg_badge).mutate());
        int color;
        switch (idEstado == null ? "" : idEstado) {
            case "COMPLETADO": case "PAGADO": color = R.color.lc_ok; break;
            case "EN REPARACIÓN": color = R.color.lc_rojo; break;
            case "EN RUTA": case "LLEGUÉ": case "ESPERANDO APROBACIÓN": color = R.color.lc_texto; break;
            default: color = R.color.lc_sec;          // pendiente, pausado, falta repuesto, cancelado
        }
        b.getBackground().setTint(ContextCompat.getColor(a, color));
        b.setPadding(dp(a, 8), dp(a, 3), dp(a, 8), dp(a, 3));
        return b;
    }

    // ── Fecha corta, miniaturas y barra inferior compartidas ──────────────────────────────────────────────────────

    /** «Hoy 10:30 AM» · «Ayer 02:15 PM» · «Oct 25, 2026». */
    static String fechaCorta(long ms) {
        if (ms <= 0) return "";
        java.util.Calendar a = java.util.Calendar.getInstance(), b = java.util.Calendar.getInstance();
        b.setTimeInMillis(ms);
        String hora = new java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US).format(new java.util.Date(ms));
        if (a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)) return "Hoy " + hora;
        a.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)) return "Ayer " + hora;
        String f = new java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.forLanguageTag("es")).format(new java.util.Date(ms));
        return f.substring(0, 1).toUpperCase() + f.substring(1);
    }

    static int iconoCategoria(String id) {
        switch (id == null ? "" : id) {
            case "HVAC": return R.drawable.ic_cat_hvac;
            case "PLOMERIA": return R.drawable.ic_cat_plomeria;
            case "ELECTRICIDAD": return R.drawable.ic_cat_electricidad;
            case "ELECTRODOMESTICOS": return R.drawable.ic_cat_electro;
            case "CALENTADOR": return R.drawable.ic_cat_calentador;
            default: return R.drawable.ic_cat_otros;
        }
    }

    private static final java.util.Map<Long, android.graphics.Bitmap> MINIATURAS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Long, Long> SIN_FOTO = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ExecutorService CARGA_FOTOS = java.util.concurrent.Executors.newFixedThreadPool(2);

    /**
     * Foto del servicio (la primera de la orden) en un recuadro redondeado de {@code lado} dp. Sin foto, el ícono de la
     * categoría sobre un círculo gris claro. La foto se baja una vez y se guarda; sin foto se vuelve a mirar cada minuto.
     */
    static void miniaturaServicio(Activity a, android.widget.ImageView iv, long jobId, String categoria, int lado) {
        int px = dp(a, lado);
        iv.setTag(jobId);
        iv.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        android.graphics.Bitmap en = MINIATURAS.get(jobId);
        if (en != null) { ponerFoto(a, iv, en); return; }
        // marcador: ícono de la categoría sobre fondo gris claro
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(a, 8));
        bg.setColor(0xFFF1F2F3);
        iv.setBackground(bg);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        iv.setPadding(dp(a, lado / 4), dp(a, lado / 4), dp(a, lado / 4), dp(a, lado / 4));
        iv.setImageResource(iconoCategoria(categoria));
        Long visto = SIN_FOTO.get(jobId);
        if (visto != null && System.currentTimeMillis() - visto < 60_000) return;
        CARGA_FOTOS.execute(() -> {
            try {
                Api.Respuesta r = Api.get(a, "/customer/requests/" + jobId + "/photos");
                org.json.JSONArray fs = r.ok() ? r.cuerpo.optJSONArray("fotos") : null;
                for (int i = 0; fs != null && i < fs.length(); i++) {
                    String u = fs.optJSONObject(i).optString("url");
                    if (u.isEmpty() || "null".equals(u)) continue;
                    java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                    con.setConnectTimeout(8000); con.setReadTimeout(15000);
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
                    o.inSampleSize = 4;
                    android.graphics.Bitmap bm;
                    try (java.io.InputStream in = con.getInputStream()) { bm = android.graphics.BitmapFactory.decodeStream(in, null, o); }
                    if (bm != null) {
                        MINIATURAS.put(jobId, bm);
                        a.runOnUiThread(() -> { if (!a.isFinishing() && Long.valueOf(jobId).equals(iv.getTag())) ponerFoto(a, iv, bm); });
                        return;
                    }
                }
                SIN_FOTO.put(jobId, System.currentTimeMillis());
            } catch (Exception ignored) {
                SIN_FOTO.put(jobId, System.currentTimeMillis());
            }
        });
    }

    private static void ponerFoto(Activity a, android.widget.ImageView iv, android.graphics.Bitmap bm) {
        iv.setPadding(0, 0, 0, 0);
        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(a, 8));
        bg.setColor(0xFFE5E5E8);
        iv.setBackground(bg);
        iv.setClipToOutline(true);
        iv.setImageBitmap(bm);
    }

    /** Insignia de estado del Cliente según la maqueta: naranja en curso, azul asignada, verde completada, gris el resto. */
    static TextView badgeCliente(Activity a, String idEstado, String texto, boolean asignado) {
        TextView b = texto(a, texto, 12, android.R.color.white, true);
        b.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(a, 20));
        int color;
        switch (idEstado == null ? "" : idEstado) {
            case "COMPLETADO": case "PAGADO": color = 0xFF16A34A; break;
            case "EN RUTA": case "LLEGUÉ": case "EN REPARACIÓN": case "ESPERANDO APROBACIÓN": case "PART HOLD": case "PAUSADO":
            case "REQUIERE GERENTE": case "REPUESTO PRECARGADO": color = 0xFFF59E0B; break;
            case "PENDIENTE": color = asignado ? 0xFF3B82F6 : 0xFF62666A; break;
            default: color = 0xFF62666A;   // cancelado, cliente no está…
        }
        g.setColor(color);
        b.setBackground(g);
        b.setPadding(dp(a, 12), dp(a, 4), dp(a, 12), dp(a, 4));
        return b;
    }

    /** Línea de tiempo compacta: puntos de 16 dp, texto de 13 sp y checks verdes pequeños. */
    static void lineaTiempoCompacta(Activity a, LinearLayout card, java.util.List<String[]> etapas) {
        for (int k = 0; k < etapas.size(); k++) {
            String[] e = etapas.get(k);
            boolean ultima = k == etapas.size() - 1;
            LinearLayout fila = new LinearLayout(a);
            fila.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView punto = texto(a, "done".equals(e[1]) ? "✓" : "", 9, android.R.color.white, true);
            punto.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if ("done".equals(e[1])) g.setColor(ContextCompat.getColor(a, R.color.lc_ok));
            else if ("current".equals(e[1])) g.setColor(ContextCompat.getColor(a, R.color.lc_rojo));
            else { g.setColor(0xFFFFFFFF); g.setStroke(dp(a, 1), ContextCompat.getColor(a, R.color.lc_borde)); }
            punto.setBackground(g);
            col.addView(punto, new LinearLayout.LayoutParams(dp(a, 16), dp(a, 16)));
            if (!ultima) {
                View linea = new View(a);
                linea.setBackgroundColor(ContextCompat.getColor(a, "done".equals(e[1]) ? R.color.lc_ok : R.color.lc_borde));
                col.addView(linea, new LinearLayout.LayoutParams(dp(a, 2), dp(a, 8)));
            }
            fila.addView(col, new LinearLayout.LayoutParams(dp(a, 22), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView t = texto(a, e[0], 13, "todo".equals(e[1]) ? R.color.lc_sec : R.color.lc_texto, !"todo".equals(e[1]));
            t.setPadding(dp(a, 6), 0, 0, 0);
            fila.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(fila);
        }
    }

    /** Barra inferior de 4 pestañas para las pantallas que no son HomeActivity; tocar una vuelve a Inicio en esa pestaña. */
    static LinearLayout barraNav(Activity a, int seleccionada) {
        String[] t = {"Inicio", "Solicitudes", "Seguimiento", "Perfil"};
        int[] ic = {R.drawable.ic_home, R.drawable.ic_list, R.drawable.ic_clock, R.drawable.ic_person};
        LinearLayout barra = new LinearLayout(a);
        barra.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(a);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setMinimumHeight(dp(a, 60));
            android.widget.ImageView iv = new android.widget.ImageView(a);
            iv.setImageResource(ic[i]);
            iv.setColorFilter(i == seleccionada ? 0xFFE91605 : 0xFF62666A);
            item.addView(iv, new LinearLayout.LayoutParams(dp(a, 24), dp(a, 24)));
            TextView x = texto(a, t[i], 12, i == seleccionada ? R.color.lc_rojo : R.color.lc_sec, true);
            x.setGravity(Gravity.CENTER);
            item.addView(x);
            item.setOnClickListener(v -> {
                a.startActivity(new android.content.Intent(a, HomeActivity.class).putExtra("tab", idx).putExtra("filtro", 1)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP));
                a.finish();
            });
            barra.addView(item, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        return barra;
    }

    /** Línea de tiempo vertical: cada etapa es {texto, done|current|todo}. */
    static void lineaTiempo(Activity a, LinearLayout card, java.util.List<String[]> etapas) {
        for (int k = 0; k < etapas.size(); k++) {
            String[] e = etapas.get(k);
            boolean ultima = k == etapas.size() - 1;
            LinearLayout fila = new LinearLayout(a);
            fila.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            TextView punto = texto(a, "done".equals(e[1]) ? "✓" : "", 12, android.R.color.white, true);
            punto.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if ("done".equals(e[1])) g.setColor(ContextCompat.getColor(a, R.color.lc_ok));
            else if ("current".equals(e[1])) g.setColor(ContextCompat.getColor(a, R.color.lc_rojo));
            else { g.setColor(0xFFFFFFFF); g.setStroke(dp(a, 2), ContextCompat.getColor(a, R.color.lc_borde)); }
            punto.setBackground(g);
            col.addView(punto, new LinearLayout.LayoutParams(dp(a, 22), dp(a, 22)));
            if (!ultima) {
                View linea = new View(a);
                linea.setBackgroundColor(ContextCompat.getColor(a, "done".equals(e[1]) ? R.color.lc_ok : R.color.lc_borde));
                col.addView(linea, new LinearLayout.LayoutParams(dp(a, 2), dp(a, 22)));
            }
            fila.addView(col, new LinearLayout.LayoutParams(dp(a, 28), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView t = texto(a, e[0], 15, "todo".equals(e[1]) ? R.color.lc_sec : R.color.lc_texto, !"todo".equals(e[1]));
            t.setPadding(dp(a, 8), dp(a, 1), 0, 0);
            fila.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            card.addView(fila);
        }
    }

    /** Soporte de L&C: llamar o escribir (contacto publicado en el sitio de L&C). */
    static void dialogoSoporte(Activity a) {
        new androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle("Soporte L&C")
                .setItems(new String[]{"Llamar · 786-922-7383", "Escribir un correo"}, (d, which) -> {
                    if (which == 0) a.startActivity(new android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:+17869227383")));
                    else a.startActivity(new android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:Carlosocampo1034@gmail.com"))
                            .putExtra(android.content.Intent.EXTRA_SUBJECT, "Soporte L&C Cliente"));
                })
                .setNegativeButton("Cerrar", null).show();
    }

    /** Texto legible para el cliente a partir del estado interno (lista editable order_states.list). */
    static String estadoCliente(android.content.Context c, String e) {
        return Ajustes.estadoCliente(c, e);
    }

    static String fecha(long ms) {
        if (ms <= 0) return "—";
        return new java.text.SimpleDateFormat("EEE d MMM · h:mm a", java.util.Locale.forLanguageTag("es"))
                .format(new java.util.Date(ms));
    }
}
