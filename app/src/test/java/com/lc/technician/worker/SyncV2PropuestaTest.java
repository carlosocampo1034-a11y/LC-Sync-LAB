package com.lc.technician.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.lc.technician.ProposalActivity;
import com.lc.technician.model.ProposalEntity;
import com.lc.technician.model.ProposalOptionEntity;
import com.lc.technician.sync.SyncModels;

import java.util.Arrays;

import org.junit.Test;

/** Fase 6: envío de propuestas y estado devuelto por el servidor. */
public class SyncV2PropuestaTest {

    private static ProposalOptionEntity op(String nivel, double mano, double mat, double markup, double desc, double imp) {
        ProposalOptionEntity o = new ProposalOptionEntity();
        o.nivel = nivel; o.titulo = "Opción " + nivel;
        o.costoManoObra = mano; o.costoMateriales = mat;
        o.markupPorcentaje = markup; o.descuentoMonto = desc; o.impuestoPorcentaje = imp;
        return o;
    }

    @Test public void dto_lleva_el_total_redondeado_que_recalcula_el_servidor() {
        ProposalEntity p = new ProposalEntity();
        p.id = 7; p.jobId = 8000000000009L;
        SyncModels.ProposalV2Dto d = SyncV2.aDtoPropuesta(p, Arrays.asList(op("GOOD", 100, 40, 0, 0, 7),
                op("BETTER", 150, 60, 10, 5, 7)));
        assertEquals(7, d.localId);
        assertEquals(149.8, d.opciones.get(0).totalCalculado, 0.0001);
        assertEquals(Math.round((210 * 1.1 - 5) * 1.07 * 100.0) / 100.0, d.opciones.get(1).totalCalculado, 0.0001);
    }

    private static SyncModels.PropuestaEstadoV2 estado(String e) {
        SyncModels.PropuestaEstadoV2 r = new SyncModels.PropuestaEstadoV2();
        r.id = 3L; r.revision = 2; r.estado = e; r.firmante = "Cliente Ficticio"; r.opcionElegida = "BETTER";
        r.totalAceptado = 246.88; r.decidedAt = 1000L; r.contenidoSha256 = "abc";
        return r;
    }

    @Test public void aprobada_bloquea_la_propuesta_con_la_opcion_del_cliente() {
        ProposalEntity p = new ProposalEntity();
        SyncV2.copiarEstado(p, estado("APROBADA"));
        assertTrue(p.isLocked);
        assertEquals("ACCEPTED", p.estado);
        assertEquals("BETTER", p.opcionAceptada);
        assertEquals(Integer.valueOf(2), p.serverRevision);
    }

    @Test public void pendiente_o_rechazada_no_bloquean() {
        ProposalEntity p = new ProposalEntity();
        SyncV2.copiarEstado(p, estado("PENDIENTE"));
        assertFalse(p.isLocked);
        SyncV2.copiarEstado(p, estado("RECHAZADA"));
        assertFalse(p.isLocked);
        assertEquals("REJECTED", p.estado);
    }

    @Test public void texto_distingue_local_de_confirmado() {
        ProposalEntity p = new ProposalEntity();
        p.syncState = 0;
        assertTrue(ProposalActivity.textoEstado(p).contains("pendiente de envío"));
        p.syncState = 3; p.motivoRechazo = "la orden ya está cerrada";
        assertTrue(ProposalActivity.textoEstado(p).contains("rechazó"));
        p.syncState = 1;
        SyncV2.copiarEstado(p, estado("APROBADA"));
        String t = ProposalActivity.textoEstado(p);
        assertTrue(t, t.startsWith("APROBADA por Cliente Ficticio") && t.contains("$246.88"));
    }
}
