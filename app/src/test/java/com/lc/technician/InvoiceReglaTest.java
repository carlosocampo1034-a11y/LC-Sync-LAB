package com.lc.technician;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regla del propietario: la factura advierte cuando difiere de la propuesta aprobada (nunca se ajusta). */
public class InvoiceReglaTest {

    @Test public void caso_laboratorio_mayor() {
        assertEquals("MAYOR", InvoiceActivity.clasificar(135.35, 149.80));
        String t = InvoiceActivity.textoAviso("MAYOR", 135.35, 149.80, null, false, false);
        assertTrue(t, t.startsWith("⚠ SUPERA LO APROBADO EN $14.45") && t.contains("Requiere aprobación adicional"));
    }

    @Test public void emitida_antes_de_la_regla_se_advierte() {
        String t = InvoiceActivity.textoAviso("MAYOR", 135.35, 149.80, null, true, false);
        assertTrue(t, t.contains("sin aprobación adicional"));
    }

    @Test public void umbrales_menor_alcance_igual_y_sin_propuesta() {
        assertEquals("MENOR", InvoiceActivity.clasificar(135.35, 128.40));
        assertEquals("ALCANCE", InvoiceActivity.clasificar(135.35, 107.00));
        assertEquals("IGUAL", InvoiceActivity.clasificar(135.35, 135.355));
        assertNull(InvoiceActivity.clasificar(null, 10));
        assertTrue(InvoiceActivity.textoAviso("MENOR", 135.35, 128.40, null, false, false).contains("Revisa y confirma"));
    }

    @Test public void estados_de_la_aprobacion_adicional() {
        assertTrue(InvoiceActivity.textoAviso("MAYOR", 100, 120, "PENDIENTE", false, false).contains("Esperando"));
        assertTrue(InvoiceActivity.textoAviso("MAYOR", 100, 120, "APROBADA", false, false).contains("ya se puede emitir"));
        assertTrue(InvoiceActivity.textoAviso("MAYOR", 100, 120, "RECHAZADA", false, false).contains("rechazó"));
    }
}
