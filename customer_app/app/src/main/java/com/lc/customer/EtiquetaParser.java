package com.lc.customer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interpreta el texto leído (OCR) de la etiqueta de un equipo. Solo devuelve lo que aparece en la etiqueta con
 * confianza razonable; si un dato es dudoso o ambiguo queda en null (el cliente lo escribe a mano). No inventa valores.
 */
final class EtiquetaParser {

    static final class Resultado {
        String tipo, marca, modelo, serial, capacidad;
        boolean vacio() { return tipo == null && marca == null && modelo == null && serial == null && capacidad == null; }
    }

    private static final String[] MARCAS = {"GOODMAN", "CARRIER", "TRANE", "LENNOX", "RHEEM", "RUUD", "DAIKIN", "YORK", "BRYANT",
            "AMANA", "COLEMAN", "MITSUBISHI", "FUJITSU", "FRIEDRICH", "PAYNE", "HEIL", "ARMSTRONG", "AMERICAN STANDARD",
            "A. O. SMITH", "A.O. SMITH", "AO SMITH", "BRADFORD WHITE", "NAVIEN", "RINNAI", "NORITZ", "STATE", "WHIRLPOOL",
            "MAYTAG", "KENMORE", "FRIGIDAIRE", "ELECTROLUX", "SAMSUNG", "LG", "GE", "HOTPOINT", "BOSCH", "KITCHENAID",
            "AMANA", "SPEED QUEEN", "HAIER", "MIDEA", "SUBZERO", "SUB-ZERO", "VIKING", "WOLF", "THERMADOR", "GAGGENAU"};

    /** Palabras clave → nombre del tipo de equipo (en español) tal como lo guarda el formulario. */
    private static final String[][] TIPOS = {
            {"HEAT\\s+PUMP|BOMBA\\s+DE\\s+CALOR", "Bomba de calor"},
            {"AIR\\s+HANDLER|MANEJADORA", "Manejadora de aire"},
            {"GAS\\s+FURNACE|FURNACE|CALEFACTOR", "Calefactor (furnace)"},
            {"AIR\\s+CONDITIONER|AIRE\\s+ACONDICIONADO|CONDENSING\\s+UNIT|CONDENSER|SPLIT\\s+SYSTEM", "Aire acondicionado"},
            {"WATER\\s+HEATER|CALENTADOR\\s+DE\\s+AGUA|TANKLESS", "Calentador de agua"},
            {"REFRIGERATOR|REFRIGERADOR|NEVERA|FRIDGE|FREEZER|CONGELADOR", "Nevera"},
            {"DISHWASHER|LAVAPLATOS|LAVAVAJILLAS", "Lavaplatos"},
            {"WASHER|WASHING\\s+MACHINE|LAVADORA", "Lavadora"},
            {"DRYER|SECADORA", "Secadora"},
            {"RANGE|COOKTOP|STOVE|ESTUFA|OVEN", "Estufa / horno"}};

    private static final Pattern P_MODELO = Pattern.compile(
            "(?<![A-Z])(?:MODEL\\s*(?:NO\\.?|NUMBER|NUM\\.?|#)?|MODELO|MOD\\.?(?:\\s*NO\\.?)?|M/N)\\s*[:#.\\-]?\\s*([A-Z0-9][A-Z0-9\\-/.]{2,29})");
    private static final Pattern P_SERIE = Pattern.compile(
            "(?<![A-Z])(?:SERIAL\\s*(?:NO\\.?|NUMBER|NUM\\.?|#)?|SER\\.?\\s*(?:NO\\.?|#)|S/N|SN|SERIE|N[UÚ]MERO\\s+DE\\s+SERIE)\\s*[:#.\\-]?\\s*([A-Z0-9][A-Z0-9\\-]{4,29})");
    private static final Pattern P_SOLO_ETIQUETA_MODELO = Pattern.compile(
            "^(?:MODEL\\s*(?:NO\\.?|NUMBER|NUM\\.?|#)?|MODELO|MOD\\.?|M/N)\\s*[:#.\\-]?$");
    private static final Pattern P_SOLO_ETIQUETA_SERIE = Pattern.compile(
            "^(?:SERIAL\\s*(?:NO\\.?|NUMBER|NUM\\.?|#)?|SER\\.?\\s*(?:NO\\.?|#)|S/N|SN|SERIE|N[UÚ]MERO\\s+DE\\s+SERIE)\\s*[:#.\\-]?$");
    private static final Pattern P_TOKEN = Pattern.compile("^([A-Z0-9][A-Z0-9\\-/.]{2,29})$");
    private static final Pattern P_CAP = Pattern.compile(
            "(\\d{1,3}(?:[.,]\\d{3})+|\\d{1,6}(?:[.,]\\d+)?)\\s*(BTU/?H?|BTUH|TONS?|TONELADAS?|GALLONS?|GAL|GALONES|HP|KW|SEER2?|LITROS?|CFM)(?![A-Z])");

    private EtiquetaParser() {}

    static Resultado analizar(String textoOcr) {
        Resultado r = new Resultado();
        if (textoOcr == null) return r;
        List<String> lineas = new ArrayList<>();
        for (String l : textoOcr.split("\\r?\\n")) {
            String t = l.trim().toUpperCase(Locale.ROOT);
            if (!t.isEmpty()) lineas.add(t);
        }
        String todo = String.join("\n", lineas);
        r.marca = marca(todo);
        r.tipo = tipo(todo);
        r.modelo = valorEtiquetado(lineas, P_MODELO, P_SOLO_ETIQUETA_MODELO, null);
        r.serial = valorEtiquetado(lineas, P_SERIE, P_SOLO_ETIQUETA_SERIE, r.modelo);
        r.capacidad = capacidad(todo);
        return r;
    }

    private static String marca(String texto) {
        Set<String> encontradas = new LinkedHashSet<>();
        for (String m : MARCAS) {
            if (Pattern.compile("(?<![A-Z0-9])" + Pattern.quote(m) + "(?![A-Z0-9])").matcher(texto).find()) encontradas.add(m);
        }
        if (encontradas.size() != 1) return null;           // ninguna o varias: dudoso, se deja vacío
        String m = encontradas.iterator().next();
        if (m.length() <= 2) return m;                      // LG, GE
        if (m.contains(".")) return m;
        StringBuilder sb = new StringBuilder();
        for (String w : m.split("[ -]")) {
            if (sb.length() > 0) sb.append(m.contains("-") && !m.contains(" ") ? "-" : " ");
            sb.append(w.charAt(0)).append(w.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static String tipo(String texto) {
        Set<String> tipos = new LinkedHashSet<>();
        for (String[] t : TIPOS) {
            if (Pattern.compile("(?<![A-Z])(?:" + t[0] + ")(?![A-Z])").matcher(texto).find()) tipos.add(t[1]);
        }
        if (tipos.size() == 2 && tipos.contains("Aire acondicionado") && tipos.contains("Bomba de calor")) return "Bomba de calor";
        return tipos.size() == 1 ? tipos.iterator().next() : null;
    }

    /** Valor tras la etiqueta, en la misma línea («MODEL NO: ABC123») o en la línea siguiente. */
    private static String valorEtiquetado(List<String> lineas, Pattern conValor, Pattern soloEtiqueta, String excluir) {
        for (int i = 0; i < lineas.size(); i++) {
            String l = lineas.get(i);
            Matcher m = conValor.matcher(l);
            while (m.find()) {
                String v = limpiar(m.group(1));
                if (valido(v, excluir)) return v;
            }
            if (soloEtiqueta.matcher(l).matches() && i + 1 < lineas.size()) {
                Matcher t = P_TOKEN.matcher(lineas.get(i + 1));
                if (t.matches() && valido(limpiar(t.group(1)), excluir)) return limpiar(t.group(1));
            }
        }
        return null;
    }

    private static String limpiar(String v) { return v.replaceAll("[.\\-/]+$", ""); }

    /** Confianza mínima: debe llevar al menos un dígito y no ser una palabra de etiqueta. */
    private static boolean valido(String v, String excluir) {
        if (v == null || v.length() < 3 || !v.matches(".*\\d.*")) return false;
        if (v.matches("(?:NO|NUMBER|NUM|SERIAL|MODEL|BTU|TON)\\.?")) return false;
        return excluir == null || !v.equals(excluir);
    }

    private static String capacidad(String texto) {
        Set<String> caps = new LinkedHashSet<>();
        Matcher m = P_CAP.matcher(texto);
        while (m.find() && caps.size() < 3) {
            String unidad = m.group(2);
            String u = unidad.startsWith("TON") ? (unidad.equals("TON") || unidad.equals("TONS") ? "TON" : "TON") : unidad;
            caps.add(m.group(1) + " " + u);
        }
        return caps.isEmpty() ? null : String.join(" · ", caps);
    }
}
