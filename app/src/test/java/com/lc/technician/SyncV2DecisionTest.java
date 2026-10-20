package com.lc.technician;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.lc.technician.sync.SyncAckPolicy;
import com.lc.technician.sync.SyncModels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

/** Decisión v2: cada orden se confirma solo si el servidor aceptó EXACTAMENTE la versión enviada. */
public class SyncV2DecisionTest {

    private static Map<Long, Long> enviados() {
        Map<Long, Long> m = new LinkedHashMap<>();
        m.put(1L, 1001L);
        m.put(2L, 1002L);
        m.put(3L, 1003L);
        return m;
    }

    private static SyncModels.JobResultV2 r(long id, String st, Integer v, Long upd) {
        SyncModels.JobResultV2 x = new SyncModels.JobResultV2();
        x.id = id;
        x.status = st;
        x.version = v;
        x.clientUpdatedAt = upd;
        return x;
    }

    private static SyncModels.PushV2Response resp(SyncModels.JobResultV2... rs) {
        SyncModels.PushV2Response p = new SyncModels.PushV2Response();
        p.results = new ArrayList<>(Arrays.asList(rs));
        return p;
    }

    @Test public void aceptados_rechazados_y_conflictos_por_separado() {
        SyncModels.JobResultV2 c = r(3, "CONFLICT", 7, 1003L);
        c.server = new SyncModels.JobV2Dto();
        c.conflictFields = Arrays.asList("descripcion");
        SyncAckPolicy.DecisionV2 d = SyncAckPolicy.decidirV2(enviados(), true,
                resp(r(1, "ACCEPTED", 4, 1001L), r(2, "REJECTED", null, 1002L), c));
        assertEquals(Integer.valueOf(4), d.confirmar.get(1L));
        assertTrue("el aceptado conserva la respuesta completa (copia del merge)", d.aceptados.containsKey(1L));
        assertFalse(d.aceptados.containsKey(2L));
        assertTrue(d.rechazar.containsKey(2L));
        assertTrue(d.conflictos.containsKey(3L));
        assertTrue(d.sinRespuesta.isEmpty());
    }

    @Test public void otra_version_local_no_se_confirma() {
        // el servidor confirma una marca distinta (p. ej. de un envío anterior): no vale para esta versión
        SyncAckPolicy.DecisionV2 d = SyncAckPolicy.decidirV2(enviados(), true, resp(r(1, "ACCEPTED", 4, 999L)));
        assertFalse(d.confirmar.containsKey(1L));
        assertTrue(d.sinRespuesta.contains(1L));
    }

    @Test public void ids_no_enviados_se_ignoran() {
        SyncAckPolicy.DecisionV2 d = SyncAckPolicy.decidirV2(enviados(), true, resp(r(99, "ACCEPTED", 1, 1001L)));
        assertTrue(d.confirmar.isEmpty());
        assertEquals(3, d.sinRespuesta.size());
    }

    @Test public void http_error_o_sin_cuerpo_todo_pendiente() {
        assertEquals(3, SyncAckPolicy.decidirV2(enviados(), false, resp(r(1, "ACCEPTED", 2, 1001L))).sinRespuesta.size());
        assertEquals(3, SyncAckPolicy.decidirV2(enviados(), true, null).sinRespuesta.size());
    }

    @Test public void aceptado_sin_version_no_se_confirma() {
        SyncAckPolicy.DecisionV2 d = SyncAckPolicy.decidirV2(enviados(), true, resp(r(1, "ACCEPTED", null, 1001L)));
        assertTrue(d.confirmar.isEmpty());
    }

    @Test public void conflicto_sin_copia_del_servidor_sigue_pendiente() {
        SyncAckPolicy.DecisionV2 d = SyncAckPolicy.decidirV2(enviados(), true, resp(r(3, "CONFLICT", 7, 1003L)));
        assertTrue(d.conflictos.isEmpty());
        assertTrue(d.sinRespuesta.contains(3L));
    }

    @Test public void respuesta_parcial_deja_el_resto_pendiente() {
        SyncAckPolicy.DecisionV2 d = SyncAckPolicy.decidirV2(enviados(), true, resp(r(2, "ACCEPTED", 3, 1002L)));
        assertEquals(1, d.confirmar.size());
        assertTrue(d.sinRespuesta.containsAll(Arrays.asList(1L, 3L)));
    }
}
