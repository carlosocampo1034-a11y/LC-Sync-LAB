package com.lc.technician.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.lc.technician.ProposalActivity;
import com.lc.technician.model.ProposalEntity;
import com.lc.technician.model.SignatureEntity;

import org.junit.Test;

/** Trabajo de campo: tipo de firma enviado al servidor y estado mostrado tras firmar en persona. */
public class SyncV2CampoTest {

    private static SignatureEntity firma(long proposalId, String opcion) {
        SignatureEntity s = new SignatureEntity();
        s.proposalId = proposalId;
        s.opcionAceptada = opcion;
        return s;
    }

    @Test public void tipos_de_firma() {
        assertEquals("PROPUESTA_PRESENCIAL", SyncV2.tipoFirma(firma(7, "BETTER")));
        assertEquals("TECNICO", SyncV2.tipoFirma(firma(-1, "TECHNICIAN_SIGN")));
        assertEquals("CLIENTE_CONFORMIDAD", SyncV2.tipoFirma(firma(-1, "CUSTOMER_SIGN")));
    }

    @Test public void firmada_en_persona_sin_confirmar_no_se_presenta_como_aprobada() {
        ProposalEntity p = new ProposalEntity();
        p.syncState = 1;
        p.isLocked = true;
        p.serverEstado = "PENDIENTE";
        p.serverRevision = 1;
        String t = ProposalActivity.textoEstado(p);
        assertTrue(t, t.contains("pendiente de confirmación del servidor") && !t.startsWith("APROBADA"));
    }

    @Test public void firma_presencial_rechazada_se_explica() {
        ProposalEntity p = new ProposalEntity();
        p.syncState = 1;
        p.serverEstado = "PENDIENTE";
        p.serverRevision = 2;
        p.motivoRechazo = "Firma presencial rechazada: la opción o el total firmados no coinciden";
        assertTrue(ProposalActivity.textoEstado(p).startsWith("⚠ Firma presencial rechazada"));
    }
}
