package com.lc.technician.sync;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decide qué trabajos enviados se dan por confirmados tras un push.
 * Regla: ante la duda, el trabajo sigue pendiente (se reenvía; el servidor hace REPLACE por id).
 *
 * Contrato ampliado (preferido): el servidor devuelve accepted_job_ids / rejected_job_ids.
 *   → se confirman solo los ids enviados que aparecen en accepted_job_ids y no en rejected_job_ids.
 * Servidor histórico (sin ids): solo cuenta accepted/rejected de TODOS los elementos del lote.
 *   → se confirma el lote completo únicamente si rejected == 0 y accepted cubre todos los
 *     elementos enviados; cualquier otro caso deja todo pendiente.
 * Sin Android: se prueba en la JVM (SyncAckPolicyTest).
 */
public final class SyncAckPolicy {

    private SyncAckPolicy() {}

    /**
     * @param jobsEnviados  ids de trabajos incluidos en este lote
     * @param httpOk        respuesta HTTP 2xx
     * @param body          cuerpo de la respuesta (puede ser null)
     * @param totalElementos total de elementos del lote (todas las entidades que el servidor cuenta)
     */
    public static Set<Long> jobsConfirmados(Collection<Long> jobsEnviados, boolean httpOk,
                                            SyncModels.SyncPushResponse body, int totalElementos) {
        Set<Long> out = new LinkedHashSet<>();
        if (!httpOk || body == null || jobsEnviados == null || jobsEnviados.isEmpty()) return out;
        if (body.accepted_job_ids != null) {
            Set<Long> rechazados = new LinkedHashSet<>();
            if (body.rejected_job_ids != null) rechazados.addAll(body.rejected_job_ids);
            for (Long id : jobsEnviados) {
                if (id != null && body.accepted_job_ids.contains(id) && !rechazados.contains(id)) out.add(id);
            }
            return out;
        }
        if (loteCompletoAceptado(httpOk, body, totalElementos)) out.addAll(jobsEnviados);
        return out;
    }

    /** Servidor histórico: todo el lote aceptado sin rechazos (para entidades sin ids de confirmación). */
    public static boolean loteCompletoAceptado(boolean httpOk, SyncModels.SyncPushResponse body,
                                               int totalElementos) {
        return httpOk && body != null && totalElementos > 0
                && body.rejected == 0 && body.accepted >= totalElementos;
    }

    /** Ids del lote como lista (para las consultas IN de Room). */
    public static List<Long> comoLista(Set<Long> ids) {
        return new java.util.ArrayList<>(ids);
    }

    // ── v2: resultado individual por trabajo ─────────────────────────────────

    /** Decisión v2 para un lote. */
    public static final class DecisionV2 {
        /** id → versión del servidor, solo para la versión exacta enviada. */
        public final java.util.Map<Long, Integer> confirmar = new java.util.LinkedHashMap<>();
        /** Respuesta completa de los aceptados (incluye la copia del servidor tras el merge). */
        public final java.util.Map<Long, SyncModels.JobResultV2> aceptados = new java.util.LinkedHashMap<>();
        /** Rechazados por el servidor: se conservan como "rechazado" (no se reenvían hasta editarlos). */
        public final java.util.Map<Long, String> rechazar = new java.util.LinkedHashMap<>();
        /** Conflictos: el servidor tiene cambios del mismo campo; se aplica su copia y se registra. */
        public final java.util.Map<Long, SyncModels.JobResultV2> conflictos = new java.util.LinkedHashMap<>();
        /** Enviados sin respuesta válida: siguen pendientes. */
        public final Set<Long> sinRespuesta = new LinkedHashSet<>();
    }

    /**
     * @param enviados id → updatedAt local que se envió en este lote
     */
    public static DecisionV2 decidirV2(java.util.Map<Long, Long> enviados, boolean httpOk,
                                      SyncModels.PushV2Response body) {
        DecisionV2 d = new DecisionV2();
        if (enviados == null) return d;
        d.sinRespuesta.addAll(enviados.keySet());
        if (!httpOk || body == null || body.results == null) return d;
        for (SyncModels.JobResultV2 r : body.results) {
            if (r == null || !enviados.containsKey(r.id)) continue;      // nunca algo que no se envió
            Long enviado = enviados.get(r.id);
            // la respuesta debe referirse a la MISMA versión local; si no, no se confirma nada
            if (r.clientUpdatedAt == null || !r.clientUpdatedAt.equals(enviado)) continue;
            if ("ACCEPTED".equals(r.status) && r.version != null) {
                d.confirmar.put(r.id, r.version);
                d.aceptados.put(r.id, r);
            } else if ("REJECTED".equals(r.status)) {
                d.rechazar.put(r.id, r.reason != null ? r.reason : "rechazado");
            } else if ("CONFLICT".equals(r.status) && r.server != null) {
                d.conflictos.put(r.id, r);
            } else {
                continue;
            }
            d.sinRespuesta.remove(r.id);
        }
        return d;
    }
}
