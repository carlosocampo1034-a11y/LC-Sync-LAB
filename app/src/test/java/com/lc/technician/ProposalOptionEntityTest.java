package com.lc.technician;

import com.lc.technician.model.ProposalOptionEntity;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Tests para ProposalOptionEntity.calcularTotal()
 * Fórmula: (hardCosts * (1 + markup%) - descuento) * (1 + impuesto%)
 */
public class ProposalOptionEntityTest {

    private ProposalOptionEntity make(double equipo, double labor, double materiales,
                                      double permiso, double remocion, double accesorios,
                                      double markup, double descuento, double impuesto) {
        ProposalOptionEntity e = new ProposalOptionEntity();
        e.costoEquipo       = equipo;
        e.costoManoObra     = labor;
        e.costoMateriales   = materiales;
        e.costoPermiso      = permiso;
        e.costoRemocion     = remocion;
        e.costoAccesorios   = accesorios;
        e.markupPorcentaje  = markup;
        e.descuentoMonto    = descuento;
        e.impuestoPorcentaje = impuesto;
        return e;
    }

    @Test
    public void totalCero_cuandoTodosLosCostossonCero() {
        ProposalOptionEntity e = make(0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(0.0, e.calcularTotal(), 0.001);
    }

    @Test
    public void totalSimple_sinMarkupNiDescuentoNiImpuesto() {
        // 500 equipo + 300 labor = 800
        ProposalOptionEntity e = make(500, 300, 0, 0, 0, 0, 0, 0, 0);
        assertEquals(800.0, e.calcularTotal(), 0.001);
    }

    @Test
    public void totalConMarkup15() {
        // 1000 * 1.15 = 1150
        ProposalOptionEntity e = make(1000, 0, 0, 0, 0, 0, 15, 0, 0);
        assertEquals(1150.0, e.calcularTotal(), 0.001);
    }

    @Test
    public void totalConImpuesto7() {
        // 1000 * 1.07 = 1070
        ProposalOptionEntity e = make(1000, 0, 0, 0, 0, 0, 0, 0, 7);
        assertEquals(1070.0, e.calcularTotal(), 0.001);
    }

    @Test
    public void totalConDescuento100() {
        // (1000 - 100) = 900
        ProposalOptionEntity e = make(1000, 0, 0, 0, 0, 0, 0, 100, 0);
        assertEquals(900.0, e.calcularTotal(), 0.001);
    }

    @Test
    public void totalCompleto_markup20_descuento50_impuesto7() {
        // hardCosts = 500 + 300 = 800
        // 800 * 1.20 = 960
        // 960 - 50 = 910
        // 910 * 1.07 = 973.70
        ProposalOptionEntity e = make(500, 300, 0, 0, 0, 0, 20, 50, 7);
        assertEquals(973.70, e.calcularTotal(), 0.01);
    }

    @Test
    public void totalConTodosLosCostos() {
        // 100+200+150+50+75+25 = 600
        // 600 * 1.15 = 690
        // 690 - 0 = 690
        // 690 * 1.07 = 738.30
        ProposalOptionEntity e = make(100, 200, 150, 50, 75, 25, 15, 0, 7);
        assertEquals(738.30, e.calcularTotal(), 0.01);
    }

    @Test
    public void descuentoMayorQueCosto_retornaCero() {
        // calcularTotal usa Math.max(0, resultado) — nunca retorna negativo
        ProposalOptionEntity e = make(100, 0, 0, 0, 0, 0, 0, 500, 0);
        assertEquals(0.0, e.calcularTotal(), 0.001);
    }

    @Test
    public void redondeoAlCentavo_igualQueElServidor() {
        // Caso real de la prueba LAN: en double, 110 × 1.15 = 126.4999… y el total salía 135.35 en el servidor
        // y 135.36 en la app. Con decimal exacto ambos dan 135.36 (= factura de 126.50 + 7 %).
        ProposalOptionEntity e = make(0, 90, 20, 0, 0, 0, 15, 0, 7);
        assertEquals(135.36, e.calcularTotal(), 0.0001);
        assertEquals(149.80, make(0, 140, 0, 0, 0, 0, 0, 0, 7).calcularTotal(), 0.0001);
    }
}
