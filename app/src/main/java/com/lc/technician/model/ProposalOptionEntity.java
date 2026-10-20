package com.lc.technician.model;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "proposal_options")
public class ProposalOptionEntity {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public long proposalId;
    public String nivel;          // "GOOD","BETTER","BEST"
    public String titulo;
    public String descripcion;    // nullable
    public double costoEquipo;
    public double costoManoObra;
    public double costoMateriales;
    public double costoPermiso;
    public double costoRemocion;
    public double costoAccesorios;
    public double markupPorcentaje;
    public double descuentoMonto;
    public double impuestoPorcentaje;
    public double totalCalculado;
    public boolean recomendada;

    /** Snapshot of markup % used at creation time (for audit trail) */
    public double markupUsed;

    /** Snapshot of tax % used at creation time (for audit trail) */
    public double taxUsed;

    /** Misma fórmula que el servidor (proposals.total_opcion): decimal exacto, recargo e impuesto al centavo (mitad hacia arriba). */
    public double calcularTotal() {
        java.math.BigDecimal duros = d(costoEquipo).add(d(costoManoObra)).add(d(costoMateriales))
                .add(d(costoPermiso)).add(d(costoRemocion)).add(d(costoAccesorios));
        java.math.BigDecimal cien = java.math.BigDecimal.valueOf(100);
        java.math.BigDecimal conMarkup = centavos(duros.multiply(
                java.math.BigDecimal.ONE.add(d(markupPorcentaje).divide(cien))));
        java.math.BigDecimal conDesc = conMarkup.subtract(d(descuentoMonto));
        java.math.BigDecimal total = centavos(conDesc.multiply(
                java.math.BigDecimal.ONE.add(d(impuestoPorcentaje).divide(cien))));
        return Math.max(0, total.doubleValue());
    }

    private static java.math.BigDecimal d(double v) { return java.math.BigDecimal.valueOf(v); }

    private static java.math.BigDecimal centavos(java.math.BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
