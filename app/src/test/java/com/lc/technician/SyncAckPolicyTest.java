package com.lc.technician;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.lc.technician.sync.SyncAckPolicy;
import com.lc.technician.sync.SyncModels;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/** Respuestas simuladas del servidor: qué trabajos se dan por sincronizados. */
public class SyncAckPolicyTest {

    private static final List<Long> LOTE = Arrays.asList(101L, 102L, 103L);

    private static SyncModels.SyncPushResponse resp(int accepted, int rejected, List<Long> okIds, List<Long> koIds) {
        SyncModels.SyncPushResponse r = new SyncModels.SyncPushResponse();
        r.accepted = accepted;
        r.rejected = rejected;
        r.timestamp = 1_000L;
        r.accepted_job_ids = okIds;
        r.rejected_job_ids = koIds;
        return r;
    }

    @Test public void exitoCompleto_servidorHistorico_confirmaElLote() {
        Set<Long> ok = SyncAckPolicy.jobsConfirmados(LOTE, true, resp(5, 0, null, null), 5);
        assertEquals(3, ok.size());
        assertTrue(ok.containsAll(LOTE));
    }

    @Test public void exitoParcial_servidorHistorico_noConfirmaNada() {
        // 2xx pero con un rechazo: sin ids no se sabe cuál falló → todo sigue pendiente
        assertTrue(SyncAckPolicy.jobsConfirmados(LOTE, true, resp(4, 1, null, null), 5).isEmpty());
    }

    @Test public void aceptadosMenosQueEnviados_noConfirmaNada() {
        assertTrue(SyncAckPolicy.jobsConfirmados(LOTE, true, resp(2, 0, null, null), 5).isEmpty());
    }

    @Test public void exitoParcial_conIds_confirmaSoloLosAceptados() {
        Set<Long> ok = SyncAckPolicy.jobsConfirmados(LOTE, true,
                resp(2, 1, Arrays.asList(101L, 103L), Collections.singletonList(102L)), 3);
        assertEquals(2, ok.size());
        assertTrue(ok.contains(101L));
        assertTrue(ok.contains(103L));
        assertFalse(ok.contains(102L));
    }

    @Test public void rechazo_idEnAmbasListas_ganaElRechazo() {
        Set<Long> ok = SyncAckPolicy.jobsConfirmados(LOTE, true,
                resp(3, 1, LOTE, Collections.singletonList(103L)), 3);
        assertFalse(ok.contains(103L));
        assertEquals(2, ok.size());
    }

    @Test public void idsAceptadosQueNoSeEnviaron_seIgnoran() {
        // Un trabajo creado durante el envío no está en el lote: aunque el servidor lo nombre, no se confirma
        Set<Long> ok = SyncAckPolicy.jobsConfirmados(LOTE, true,
                resp(4, 0, Arrays.asList(101L, 102L, 103L, 999L), null), 3);
        assertFalse(ok.contains(999L));
        assertEquals(3, ok.size());
    }

    @Test public void listaAceptadosVacia_noConfirmaNada() {
        assertTrue(SyncAckPolicy.jobsConfirmados(LOTE, true,
                resp(0, 3, Collections.emptyList(), LOTE), 3).isEmpty());
    }

    @Test public void errorHttp_noConfirmaNada() {
        assertTrue(SyncAckPolicy.jobsConfirmados(LOTE, false, resp(5, 0, LOTE, null), 5).isEmpty());
    }

    @Test public void timeoutOCuerpoNulo_noConfirmaNada() {
        // Timeout / red caída: no hay cuerpo de respuesta
        assertTrue(SyncAckPolicy.jobsConfirmados(LOTE, true, null, 5).isEmpty());
    }

    @Test public void loteVacio_noConfirmaNada() {
        assertTrue(SyncAckPolicy.jobsConfirmados(Collections.emptyList(), true, resp(1, 0, null, null), 1).isEmpty());
    }

    @Test public void reintento_mismaRespuesta_mismoResultado_sinDuplicar() {
        // El reintento envía los mismos ids (el servidor hace REPLACE): la decisión es idempotente
        SyncModels.SyncPushResponse r = resp(3, 0, Arrays.asList(101L, 102L), null);
        Set<Long> a = SyncAckPolicy.jobsConfirmados(LOTE, true, r, 3);
        Set<Long> b = SyncAckPolicy.jobsConfirmados(LOTE, true, r, 3);
        assertEquals(a, b);
        assertEquals(2, a.size());
    }

    @Test public void loteCompleto_requiereSinRechazosYTodosContados() {
        assertTrue(SyncAckPolicy.loteCompletoAceptado(true, resp(6, 0, null, null), 5));
        assertFalse(SyncAckPolicy.loteCompletoAceptado(true, resp(5, 1, null, null), 5));
        assertFalse(SyncAckPolicy.loteCompletoAceptado(true, resp(4, 0, null, null), 5));
        assertFalse(SyncAckPolicy.loteCompletoAceptado(false, resp(5, 0, null, null), 5));
        assertFalse(SyncAckPolicy.loteCompletoAceptado(true, resp(0, 0, null, null), 0));
    }
}
