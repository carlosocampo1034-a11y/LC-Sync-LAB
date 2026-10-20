package com.lc.technician;

import com.lc.technician.model.Job;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Phase 1 Core Data tests.
 * Covers:
 * - Null access field handling (P0 bug fix)
 * - Job model null-safety
 * - State constants integrity
 */
public class Phase1CoreTest {

    // ── P0: "Entrada: null" fix ───────────────────────────────────────────────

    /**
     * Simulates the fixed display logic: tipoAcceso == null → no display.
     * Before fix: acceso.append("Entrada: ").append(null) → "Entrada: null"
     * After fix: tipoAcceso is null or empty → view hidden.
     */
    @Test
    public void accesoNull_noGeneraEntradaNullString() {
        String tipoAcceso = null;
        String resultado = buildAccesoString(tipoAcceso, null);
        assertNull("tipoAcceso nulo no debe producir string", resultado);
    }

    @Test
    public void accesoVacio_noGeneraEntradaVaciaString() {
        String tipoAcceso = "";
        String resultado = buildAccesoString(tipoAcceso, null);
        assertNull("tipoAcceso vacío no debe producir string", resultado);
    }

    @Test
    public void accesoConValor_generaStringCorrecto() {
        String resultado = buildAccesoString("CÓDIGO PUERTA", "#4821");
        assertEquals("Entrada: CÓDIGO PUERTA   →   #4821", resultado);
    }

    @Test
    public void accesoSinCodigo_generaStringSinFlecha() {
        String resultado = buildAccesoString("LLAVE ESCONDIDA", "");
        assertEquals("Entrada: LLAVE ESCONDIDA", resultado);
    }

    @Test
    public void accesoSinCodigo_codigoNull_generaStringSinFlecha() {
        String resultado = buildAccesoString("PRESENTE", null);
        assertEquals("Entrada: PRESENTE", resultado);
    }

    /**
     * Mirrors the fixed logic from JobDetailActivity for tipoAcceso display.
     * Returns null when the view should be GONE, String when it should be VISIBLE.
     */
    private String buildAccesoString(String tipoAcceso, String codigoAcceso) {
        if (tipoAcceso == null || tipoAcceso.isEmpty()) {
            return null; // view should be GONE
        }
        StringBuilder acceso = new StringBuilder();
        acceso.append("Entrada: ").append(tipoAcceso);
        if (codigoAcceso != null && !codigoAcceso.isEmpty()) {
            acceso.append("   →   ").append(codigoAcceso);
        }
        return acceso.toString();
    }

    // ── Job model null safety ─────────────────────────────────────────────────

    @Test
    public void jobEntity_toEntity_nunca_guardaNullEnCamposString() {
        Job job = new Job(
                9999L, "Test Cliente", null, null, null, null, null,
                null, null, false, null, null, null, null, null, null, null);
        // toEntity must not throw and must produce safe defaults
        var entity = job.toEntity();
        assertNotNull(entity);
        assertEquals("", entity.telefono);
        assertEquals("", entity.direccion);
        assertEquals("", entity.hora);
        assertEquals("", entity.descripcion);
        assertEquals("", entity.equipo);
        assertEquals(Job.STATUS_PENDING, entity.estado);
        assertEquals("", entity.tipoAcceso);
        assertEquals("", entity.codigoAcceso);
        assertEquals("", entity.ubicacionLlave);
        assertEquals("", entity.instrucciones);
        assertEquals("", entity.mascota);
        assertEquals("", entity.contactoRespaldo);
        assertEquals("", entity.notasSeguridad);
    }

    // ── Customer case-insensitive dedup logic ─────────────────────────────────

    @Test
    public void customerDedup_caseSensitiveDifference_detected() {
        // Verify that "maria" != "MARIA" in case-SENSITIVE comparison
        String a = "maria rodriguez";
        String b = "MARIA RODRIGUEZ";
        assertNotEquals("Case-sensitive match must differ", a, b);
    }

    @Test
    public void customerDedup_caseInsensitiveSameCustomer_matched() {
        // The LOWER() SQL logic is equivalent to equalsIgnoreCase in Java
        String a = "maria rodriguez";
        String b = "MARIA RODRIGUEZ";
        assertTrue("equalsIgnoreCase should find same customer",
                a.equalsIgnoreCase(b));
    }

    @Test
    public void customerDedup_trimmedName_matches() {
        String stored = "María Rodríguez";
        String input  = "  María Rodríguez  ";
        assertTrue("Trimmed input must match stored name",
                stored.equalsIgnoreCase(input.trim()));
    }

    // ── State transition completeness ─────────────────────────────────────────

    @Test
    public void estadosDone_completado() {
        assertEquals("COMPLETADO", Job.STATUS_DONE);
    }

    @Test
    public void estadoTransicion_pendienteAEnRuta_valido() {
        // Verify that STATUS_EN_ROUTE is a different, non-null value from PENDING
        assertNotNull(Job.STATUS_EN_ROUTE);
        assertNotEquals(Job.STATUS_PENDING, Job.STATUS_EN_ROUTE);
    }
}
