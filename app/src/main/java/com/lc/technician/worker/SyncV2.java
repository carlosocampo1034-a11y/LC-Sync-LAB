package com.lc.technician.worker;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.lc.technician.data.JobRepository;
import com.lc.technician.data.LcDatabase;
import com.lc.technician.model.AuditLogEntity;
import com.lc.technician.model.CustomerEntity;
import com.lc.technician.model.FieldMeasurementEntity;
import com.lc.technician.model.JobEntity;
import com.lc.technician.model.PhotoEntity;
import com.lc.technician.model.PropertyEntity;
import com.lc.technician.model.WorkItemEntity;
import com.lc.technician.sync.LabSession;
import com.lc.technician.sync.SyncAckPolicy;
import com.lc.technician.sync.SyncApi;
import com.lc.technician.sync.SyncModels;
import com.lc.technician.sync.SupabaseSyncBridge;
import com.lc.technician.util.DeviceIdManager;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sincronización v2 con el backend de laboratorio.
 * - Envía la orden completa con la versión del servidor que conoce (base del merge de 3 vías).
 * - Confirma cada orden solo si el servidor la aceptó y devuelve la marca de ESTE envío.
 * - Rechazos: synced=3 (se conserva, no se reenvía hasta editarla). Conflictos: se aplica la copia
 *   del servidor y se deja constancia en auditoría con los campos afectados.
 * - Pull: órdenes asignadas; nunca pisa una orden local con cambios sin confirmar.
 */
final class SyncV2 {

    static final String TAG = "LcSyncV2";
    private static final int MAX_FOTOS = 5;
    private static final long MAX_FOTO_BYTES = 1_500_000;
    private static final AtomicLong ULTIMA_MARCA = new AtomicLong();

    static final class Resultado {
        int enviados, confirmados, rechazados, conflictos, sinRespuesta, recibidos;
    }

    private SyncV2() {}

    /** Marca única y creciente por envío (dos cambios nunca viajan con la misma marca). */
    static long nuevaMarca() {
        while (true) {
            long prev = ULTIMA_MARCA.get();
            long next = Math.max(System.currentTimeMillis(), prev + 1);
            if (ULTIMA_MARCA.compareAndSet(prev, next)) return next;
        }
    }

    static Resultado ejecutar(Context ctx, LcDatabase db, SyncApi api) throws Exception {
        String auth = LabSession.authHeader(ctx);
        String device = DeviceIdManager.getDeviceId(ctx);
        Resultado res = new Resultado();
        if (LabSession.espacioIds(ctx) == null) {
            // Sesiones iniciadas antes de existir el registro: se obtiene el espacio de ids sin cerrar sesión
            // (cerrar sesión borraría las marcas de lo ya confirmado). Si falla, se reintenta en la próxima sync.
            try {
                SyncModels.DeviceV2Request dr = new SyncModels.DeviceV2Request();
                dr.deviceId = device;
                retrofit2.Response<SyncModels.DeviceV2Response> r = api.registrarDispositivo(auth, dr).execute();
                if (r.isSuccessful() && r.body() != null && r.body().idBase != null && r.body().idLimite != null) {
                    LabSession.guardarEspacio(ctx, r.body().idBase, r.body().idLimite);
                    Log.i(TAG, "espacio de ids del dispositivo asignado");
                }
            } catch (java.io.IOException ex) {
                Log.w(TAG, "registro de dispositivo pendiente: " + ex.getClass().getSimpleName());
            }
        }
        try {
            db.jobDao().liberarEnTransito();
            db.workItemDao().liberarEnTransito();
            db.proposalDao().liberarEnTransito();
            db.invoiceSyncDao().liberarEnTransito();
            liberarCampo(db);
            // Lote: leer + marcar en tránsito atómicamente (órdenes y partidas de trabajo)
            List<WorkItemEntity> loteWi = new ArrayList<>();
            List<com.lc.technician.model.ProposalEntity> lotePropuestas = new ArrayList<>();
            List<com.lc.technician.model.InvoiceSyncEntity> loteFacturas = new ArrayList<>();
            final LoteCampo campo = new LoteCampo();
            List<JobEntity> lote = db.runInTransaction(() -> {
                List<JobEntity> l = db.jobDao().getUnsynced();
                List<Long> ids = new ArrayList<>();
                for (JobEntity e : l) ids.add(e.id);
                if (!ids.isEmpty()) db.jobDao().marcarEnTransito(ids);
                List<WorkItemEntity> w = db.workItemDao().getUnsynced();
                List<Long> wids = new ArrayList<>();
                for (WorkItemEntity x : w) {
                    if (wids.size() >= 50) break;
                    wids.add(x.id);
                    loteWi.add(x);
                }
                if (!wids.isEmpty()) db.workItemDao().marcarEnTransito(wids);
                // propuestas y facturas pendientes: también en tránsito dentro de la misma transacción
                List<Long> pids = new ArrayList<>();
                for (com.lc.technician.model.ProposalEntity p : db.proposalDao().pendientesDeEnvio()) {
                    pids.add(p.id);
                    lotePropuestas.add(p);
                }
                if (!pids.isEmpty()) db.proposalDao().marcarEnTransito(pids);
                List<Long> fids = new ArrayList<>();
                for (com.lc.technician.model.InvoiceSyncEntity f : db.invoiceSyncDao().pendientes()) {
                    fids.add(f.jobId);
                    loteFacturas.add(f);
                }
                if (!fids.isEmpty()) db.invoiceSyncDao().marcarEnTransito(fids);
                tomarCampo(db, campo);
                return l;
            });
            Map<Long, Long> marcaPorId = new LinkedHashMap<>();
            Map<Long, Long> updatedLocal = new HashMap<>();
            SyncModels.PushV2Request req = new SyncModels.PushV2Request();
            req.deviceId = device;
            req.jobs = new ArrayList<>();
            for (JobEntity e : lote) {
                long marca = nuevaMarca();
                marcaPorId.put(e.id, marca);
                updatedLocal.put(e.id, e.updatedAt != null ? e.updatedAt : e.id);
                req.jobs.add(aDto(db, e, marca));
            }
            req.work_items = new ArrayList<>();
            for (WorkItemEntity w : loteWi) req.work_items.add(aDto(w));
            req.deleted_work_item_ids = new ArrayList<>();
            // anulaciones antiguas (versión anterior: borrado físico + lista en preferencias)
            for (String s : LabSession.confirmados(ctx, "wi_borrado")) req.deleted_work_item_ids.add(Long.parseLong(s));
            req.photos = fotosPendientes(ctx, db);
            req.events = medicionesPendientes(ctx, db);
            req.proposals = new ArrayList<>();
            for (com.lc.technician.model.ProposalEntity p : lotePropuestas) {
                req.proposals.add(aDtoPropuesta(p, db.proposalOptionDao().getForProposal(p.id)));
            }
            armarCampo(db, campo, req);
            req.invoices = new ArrayList<>();
            for (com.lc.technician.model.InvoiceSyncEntity f : loteFacturas) {
                SyncModels.InvoiceReqV2Dto d = new SyncModels.InvoiceReqV2Dto();
                d.jobId = f.jobId;
                d.clientInvoiceId = f.clientInvoiceId;
                if (f.confirmaMenor == 1) d.confirmaMenor = Boolean.TRUE;
                req.invoices.add(d);
            }
            res.enviados = req.jobs.size();

            if (!req.jobs.isEmpty() || !req.photos.isEmpty() || !req.events.isEmpty()
                    || !req.work_items.isEmpty() || !req.deleted_work_item_ids.isEmpty()
                    || !req.proposals.isEmpty() || !req.invoices.isEmpty() || !campo.vacio()) {
                retrofit2.Response<SyncModels.PushV2Response> resp = api.pushV2(auth, req).execute();
                SyncModels.PushV2Response body = resp.body();
                if (resp.code() == 401) {
                    Log.w(TAG, "sesión de laboratorio caducada");
                    LabSession.cerrar(ctx);
                }
                SyncAckPolicy.DecisionV2 d = SyncAckPolicy.decidirV2(marcaPorId, resp.isSuccessful(), body);
                long ts = System.currentTimeMillis();
                for (Map.Entry<Long, Integer> c : d.confirmar.entrySet()) {
                    SyncModels.JobResultV2 r = d.aceptados.get(c.getKey());
                    if (r == null || r.server == null) {
                        res.confirmados += db.jobDao().confirmarVersion(c.getKey(), updatedLocal.get(c.getKey()),
                                c.getValue(), ts);
                        continue;
                    }
                    // Confirmar y adoptar el resultado del merge del servidor, solo si la orden no se editó
                    // mientras estaba en tránsito (synced sigue en 2 y es la misma versión local enviada).
                    final long id = c.getKey();
                    final long enviado = updatedLocal.get(id);
                    boolean hecho = db.runInTransaction(() -> {
                        JobEntity local = db.jobDao().getJobById(id);
                        if (local == null || local.synced != 2) return false;
                        long actual = local.updatedAt != null ? local.updatedAt : local.id;
                        if (actual != enviado) return false;
                        aplicarServidor(local, r.server);
                        local.synced = 1;
                        local.serverVersion = r.version;
                        local.lastSyncedAt = ts;
                        db.jobDao().insert(local);
                        return true;
                    });
                    if (hecho) res.confirmados++;
                }
                for (SyncModels.JobResultV2 r : d.aceptados.values()) marcarConflictos(ctx, r.id, r.server);
                for (SyncModels.JobResultV2 c : d.conflictos.values()) marcarConflictos(ctx, c.id, c.server);
                for (Map.Entry<Long, String> r : d.rechazar.entrySet()) {
                    if (db.jobDao().marcarRechazado(r.getKey()) > 0) {
                        res.rechazados++;
                        auditar(db, r.getKey(), "SYNC_RECHAZADO", null, r.getValue());
                    }
                }
                for (SyncModels.JobResultV2 c : d.conflictos.values()) {
                    // leer-comprobar-escribir en una transacción: ninguna edición local se cuela en medio
                    boolean aplicado = db.runInTransaction(() -> {
                        JobEntity local = db.jobDao().getJobById(c.id);
                        // solo si sigue en tránsito: si el técnico volvió a editarla, se reintenta con su cambio
                        if (local == null || local.synced != 2) return false;
                        resolverConflicto(local, c, ts);
                        db.jobDao().insert(local);
                        auditar(db, c.id, "SYNC_CONFLICTO", c.conflictFields != null
                                ? String.join(",", c.conflictFields) : null, descartadosTexto(local, c));
                        return true;
                    });
                    if (aplicado) res.conflictos++;
                }
                res.sinRespuesta = d.sinRespuesta.size();
                if (resp.isSuccessful() && body != null && body.work_items != null) {
                    List<Long> okWi = new ArrayList<>(), koWi = new ArrayList<>();
                    List<String> borradosOk = new ArrayList<>();
                    java.util.Set<Long> enviadosWi = new java.util.HashSet<>();
                    for (WorkItemEntity w : loteWi) enviadosWi.add(w.id);
                    if (body.work_items.accepted_ids != null) {
                        for (Long id : body.work_items.accepted_ids) {
                            if (enviadosWi.contains(id)) okWi.add(id);                       // solo lo enviado
                            if (req.deleted_work_item_ids.contains(id)) borradosOk.add(String.valueOf(id));
                        }
                    }
                    if (body.work_items.rejected != null) {
                        for (SyncModels.RejectedItem r : body.work_items.rejected) {
                            if (r.id != null && enviadosWi.contains(r.id)) koWi.add(r.id);
                        }
                    }
                    if (!okWi.isEmpty()) res.confirmados += db.workItemDao().confirmar(okWi, ts);
                    if (!koWi.isEmpty()) res.rechazados += db.workItemDao().marcarRechazados(koWi);
                    LabSession.quitarConfirmados(ctx, "wi_borrado", borradosOk);
                    res.sinRespuesta += loteWi.size() - okWi.size() - koWi.size();
                }
                if (body != null && body.photos != null && body.photos.accepted_ids != null) {
                    List<String> ok = new ArrayList<>();
                    for (Long id : body.photos.accepted_ids) ok.add(String.valueOf(id));
                    LabSession.agregarConfirmados(ctx, "foto", ok);
                }
                if (body != null && body.events != null && body.events.accepted_ids != null) {
                    LabSession.agregarConfirmados(ctx, "evento", body.events.accepted_ids);
                }
                // confirmaciones individuales: solo lo que el servidor contestó cambia de estado; lo demás vuelve
                // a pendiente en el finally
                if (resp.isSuccessful() && body != null && body.proposals != null && body.proposals.results != null) {
                    for (SyncModels.PropuestaEstadoV2 r : body.proposals.results) {
                        if (r.localId == null) continue;
                        db.runInTransaction(() -> confirmarPropuesta(db, r));
                    }
                }
                if (resp.isSuccessful() && body != null && body.invoices != null && body.invoices.results != null) {
                    for (SyncModels.FacturaResultV2 r : body.invoices.results) {
                        if (r.jobId == null) continue;
                        db.runInTransaction(() -> confirmarFactura(db, r));
                    }
                }
                if (resp.isSuccessful() && body != null) db.runInTransaction(() -> confirmarCampo(db, campo, body));
                Log.i(TAG, "push v2: enviados=" + res.enviados + " confirmados=" + res.confirmados
                        + " rechazados=" + res.rechazados + " conflictos=" + res.conflictos
                        + " sinRespuesta=" + res.sinRespuesta + " http=" + resp.code());
                if (!resp.isSuccessful()) return res;   // no se hace pull con la sesión inválida
            }
        } finally {
            db.jobDao().liberarEnTransito();   // lo no confirmado vuelve a pendiente
            db.workItemDao().liberarEnTransito();
            db.proposalDao().liberarEnTransito();
            db.invoiceSyncDao().liberarEnTransito();
            liberarCampo(db);
        }

        // ── PULL ──
        retrofit2.Response<SyncModels.PullV2Response> pull =
                api.pullV2(auth, LabSession.cursor(ctx), device).execute();
        if (pull.code() == 401) {
            // token caducado: se cierra la sesión (se conservan marcas y pendientes) para que Ajustes pida entrar
            Log.w(TAG, "sesión de laboratorio caducada (pull)");
            LabSession.cerrar(ctx);
            return res;
        }
        if (pull.isSuccessful() && pull.body() != null) {
            long ts = System.currentTimeMillis();
            for (SyncModels.JobV2Dto dto : pull.body().jobs) {
                boolean aplicado = db.runInTransaction(() -> {
                    JobEntity local = db.jobDao().getJobById(dto.id);
                    if (local != null && local.synced != 1) {
                        Log.i(TAG, "pull: " + dto.id + " tiene cambios locales sin confirmar; se conserva");
                        return false;
                    }
                    if (local != null && Integer.valueOf(dto.version).equals(local.serverVersion)) {
                        return false; // misma versión ya aplicada; seq cambió por evento secundario (propuesta/pago/firma)
                    }
                    JobEntity e = local != null ? local : new JobEntity();
                    e.id = dto.id;
                    aplicarServidor(e, dto);
                    e.synced = 1;
                    e.serverVersion = dto.version;
                    e.lastSyncedAt = ts;
                    db.jobDao().insert(e);
                    return true;
                });
                if (aplicado) res.recibidos++;
                marcarConflictos(ctx, dto.id, dto);
                if (dto.propuestas != null) {
                    for (SyncModels.PropuestaEstadoV2 pe : dto.propuestas) db.runInTransaction(() -> aplicarEstadoPropuesta(db, pe));
                }
                if (dto.factura != null) {
                    final SyncModels.FacturaV2 fv = dto.factura;
                    db.runInTransaction(() -> aplicarFacturaServidor(db, dto.id, fv));
                }
                if (dto.pagos != null) db.runInTransaction(() -> aplicarPagos(db, dto.pagos));
                if (dto.variaciones != null) db.runInTransaction(() -> aplicarVariaciones(db, dto.id, dto.variaciones));
                if (dto.descuentoAutorizado != null) LabSession.setDescuento(ctx, dto.id, dto.descuentoAutorizado);
            }
            LabSession.setCursor(ctx, pull.body().cursor);
            // Replica el estado canónico a Supabase sin bloquear el hilo de sync
            final List<SyncModels.JobV2Dto> dtos = new ArrayList<>(pull.body().jobs);
            new Thread(() -> SupabaseSyncBridge.get(ctx).replicateDtos(dtos), "sb-replicate").start();
        }
        JobRepository.get().recargarCacheAsync(null);
        // Ediciones hechas mientras el envío estaba en tránsito siguen pendientes: una ejecución de seguimiento
        // (única, KEEP) las sube. Así no hace falta encolar una sincronización por cada cambio.
        if (db.jobDao().countPendientesSinEnviar() > 0 || db.workItemDao().countPendientesSinEnviar() > 0
                || db.proposalDao().countPendientes() > 0 || db.invoiceSyncDao().countPendientes() > 0
                || db.signatureDao().countPendientes() > 0 || db.jobPartDao().countPendientes() > 0
                || db.paymentDao().countPendientesSync() > 0) {
            JobRepository.get().solicitarSyncSeguimiento();
        }
        return res;
    }

    // ── Fase 6: propuestas y facturas ───────────────────────────────────────

    static SyncModels.ProposalV2Dto aDtoPropuesta(com.lc.technician.model.ProposalEntity p,
                                                  List<com.lc.technician.model.ProposalOptionEntity> ops) {
        SyncModels.ProposalV2Dto d = new SyncModels.ProposalV2Dto();
        d.localId = p.id;
        d.jobId = p.jobId;
        d.opciones = new ArrayList<>();
        if (ops != null) {
            for (com.lc.technician.model.ProposalOptionEntity o : ops) {
                SyncModels.ProposalOptionV2Dto x = new SyncModels.ProposalOptionV2Dto();
                x.nivel = o.nivel; x.titulo = o.titulo; x.descripcion = o.descripcion;
                x.costoEquipo = o.costoEquipo; x.costoManoObra = o.costoManoObra;
                x.costoMateriales = o.costoMateriales; x.costoPermiso = o.costoPermiso;
                x.costoRemocion = o.costoRemocion; x.costoAccesorios = o.costoAccesorios;
                x.markupPorcentaje = o.markupPorcentaje; x.descuentoMonto = o.descuentoMonto;
                x.impuestoPorcentaje = o.impuestoPorcentaje;
                x.totalCalculado = Math.round(o.calcularTotal() * 100.0) / 100.0;
                x.recomendada = o.recomendada;
                d.opciones.add(x);
            }
        }
        return d;
    }

    /** Respuesta del push para una propuesta enviada: solo si sigue en tránsito (no se editó mientras tanto). */
    static void confirmarPropuesta(LcDatabase db, SyncModels.PropuestaEstadoV2 r) {
        com.lc.technician.model.ProposalEntity p = db.proposalDao().getById(r.localId);
        if (p == null || p.syncState != 2) return;
        if ("REJECTED".equals(r.status)) {
            p.syncState = 3;
            p.motivoRechazo = r.reason;
        } else if ("ACCEPTED".equals(r.status)) {
            p.syncState = 1;
            p.motivoRechazo = null;
            copiarEstado(p, r);
        } else {
            return;
        }
        db.proposalDao().update(p);
    }

    /** Estado recibido en el pull (decisión del cliente, vencimiento, revisión superada). */
    static void aplicarEstadoPropuesta(LcDatabase db, SyncModels.PropuestaEstadoV2 r) {
        com.lc.technician.model.ProposalEntity p = r.id != null ? db.proposalDao().getByServerId(r.id) : null;
        if (p == null || p.syncState != 1) return;                 // otra propuesta, o editada sin enviar
        if (p.serverRevision != null && r.revision != null && r.revision < p.serverRevision) return;
        copiarEstado(p, r);
        db.proposalDao().update(p);
    }

    static void copiarEstado(com.lc.technician.model.ProposalEntity p, SyncModels.PropuestaEstadoV2 r) {
        p.serverId = r.id;
        p.serverRevision = r.revision;
        p.serverEstado = r.estado;
        p.contenidoSha = r.contenidoSha256;
        p.firmanteServidor = r.firmante;
        p.opcionServidor = r.opcionElegida;
        p.totalServidor = r.totalAceptado;
        p.decididaAt = r.decidedAt;
        p.venceAt = r.expiresAt;
        if ("APROBADA".equals(r.estado)) {
            // la evidencia es la firma del cliente guardada en el servidor; la propuesta queda bloqueada
            p.estado = "ACCEPTED";
            p.opcionAceptada = r.opcionElegida;
            p.isLocked = true;
            p.lockedAt = r.decidedAt != null ? r.decidedAt : System.currentTimeMillis();
        } else if ("RECHAZADA".equals(r.estado)) {
            p.estado = "REJECTED";
        }
    }

    static void confirmarFactura(LcDatabase db, SyncModels.FacturaResultV2 r) {
        com.lc.technician.model.InvoiceSyncEntity f = db.invoiceSyncDao().get(r.jobId);
        if (f == null || f.syncState != 2) return;
        f.syncState = "ACCEPTED".equals(r.status) ? 1 : 3;
        f.motivo = "ACCEPTED".equals(r.status) ? null : r.reason;
        f.codigoRechazo = "ACCEPTED".equals(r.status) ? null : r.codigo;
        copiarComparacion(f, r.comparacion);
        if (r.variacion != null) { f.variacionId = r.variacion.id; f.variacionEstado = r.variacion.estado; }
        copiarFactura(f, r.factura);
        f.updatedAt = System.currentTimeMillis();
        db.invoiceSyncDao().upsert(f);
    }

    static void aplicarFacturaServidor(LcDatabase db, long jobId, SyncModels.FacturaV2 fv) {
        com.lc.technician.model.InvoiceSyncEntity f = db.invoiceSyncDao().get(jobId);
        if (f != null && f.syncState != 1 && f.syncState != 3) return;   // hay una petición local en curso
        if (f == null) {
            f = new com.lc.technician.model.InvoiceSyncEntity();
            f.jobId = jobId;
            f.clientInvoiceId = "servidor-" + fv.id;
            f.syncState = 1;
        }
        copiarFactura(f, fv);
        f.updatedAt = System.currentTimeMillis();
        db.invoiceSyncDao().upsert(f);
    }

    static void copiarComparacion(com.lc.technician.model.InvoiceSyncEntity f, SyncModels.ComparacionV2 c) {
        if (c == null) return;
        f.aprobadoTotal = c.aprobado;
        f.diferencia = c.diferencia;
        f.tipoComparacion = c.tipo;
        if (c.variacionEstado != null) f.variacionEstado = c.variacionEstado;
    }

    /** Estado de la aprobación adicional (la decide el cliente en L&C Customer). */
    static void aplicarVariaciones(LcDatabase db, long jobId, List<SyncModels.VariacionV2> vs) {
        if (vs == null || vs.isEmpty()) return;
        com.lc.technician.model.InvoiceSyncEntity f = db.invoiceSyncDao().get(jobId);
        if (f == null || f.syncState == 0 || f.syncState == 2) return;
        SyncModels.VariacionV2 ult = vs.get(vs.size() - 1);
        f.variacionId = ult.id;
        f.variacionEstado = ult.estado;
        f.diferencia = ult.diferencia;
        f.aprobadoTotal = ult.aprobado;
        f.tipoComparacion = ult.tipo;
        db.invoiceSyncDao().upsert(f);
    }

    static void copiarFactura(com.lc.technician.model.InvoiceSyncEntity f, SyncModels.FacturaV2 fv) {
        if (fv == null) return;
        copiarComparacion(f, fv.comparacion);
        f.descuento = fv.descuento;
        f.pagado = fv.pagado;
        f.saldo = fv.saldo;
        f.propuestaTotal = fv.propuestaAprobada != null ? fv.propuestaAprobada.total : null;
        f.serverId = fv.id;
        f.numero = fv.numero;
        f.estadoServidor = fv.estado;
        f.subtotal = fv.subtotal;
        f.impuesto = fv.impuesto;
        f.total = fv.total;
    }

    // ── Trabajo de campo: firmas, repuestos usados, pagos reportados, PDF ────

    static final class LoteCampo {
        final List<com.lc.technician.model.SignatureEntity> firmas = new ArrayList<>();
        final List<com.lc.technician.model.JobPartEntity> repuestos = new ArrayList<>();
        final List<com.lc.technician.model.PaymentEntity> pagos = new ArrayList<>();
        final List<com.lc.technician.model.InvoiceSyncEntity> pdfs = new ArrayList<>();
        boolean vacio() { return firmas.isEmpty() && repuestos.isEmpty() && pagos.isEmpty() && pdfs.isEmpty(); }
    }

    static void liberarCampo(LcDatabase db) {
        db.signatureDao().liberarEnTransito();
        db.jobPartDao().liberarEnTransito();
        db.paymentDao().liberarEnTransito();
        db.invoiceSyncDao().liberarPdfEnTransito();
    }

    /** Dentro de la transacción del lote: lee lo pendiente y lo marca en tránsito. */
    static void tomarCampo(LcDatabase db, LoteCampo c) {
        List<Long> ids = new ArrayList<>();
        for (com.lc.technician.model.SignatureEntity s : db.signatureDao().pendientes()) {
            if (c.firmas.size() >= 5) break;
            c.firmas.add(s); ids.add(s.id);
        }
        if (!ids.isEmpty()) db.signatureDao().marcarEnTransito(ids);
        ids = new ArrayList<>();
        for (com.lc.technician.model.JobPartEntity p : db.jobPartDao().pendientes()) {
            if (c.repuestos.size() >= 50) break;
            c.repuestos.add(p); ids.add(p.id);
        }
        if (!ids.isEmpty()) db.jobPartDao().marcarEnTransito(ids);
        List<String> pids = new ArrayList<>();
        for (com.lc.technician.model.PaymentEntity p : db.paymentDao().pendientesSync()) { c.pagos.add(p); pids.add(p.id); }
        if (!pids.isEmpty()) db.paymentDao().marcarEnTransito(pids);
        ids = new ArrayList<>();
        for (com.lc.technician.model.InvoiceSyncEntity f : db.invoiceSyncDao().pdfPendientes()) {
            if (c.pdfs.size() >= 2) break;
            c.pdfs.add(f); ids.add(f.jobId);
        }
        if (!ids.isEmpty()) db.invoiceSyncDao().marcarPdfEnTransito(ids);
    }

    static String tipoFirma(com.lc.technician.model.SignatureEntity s) {
        if (s.proposalId > 0) return "PROPUESTA_PRESENCIAL";
        if ("TECHNICIAN_SIGN".equals(s.opcionAceptada)) return "TECNICO";
        return "CLIENTE_CONFORMIDAD";
    }

    static String base64Archivo(String path, int max) {
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists() || f.length() > max) return null;
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    static void armarCampo(LcDatabase db, LoteCampo c, SyncModels.PushV2Request req) {
        req.signatures = new ArrayList<>();
        for (com.lc.technician.model.SignatureEntity s : c.firmas) {
            SyncModels.SignatureV2Dto d = new SyncModels.SignatureV2Dto();
            d.localId = s.id; d.jobId = s.jobId; d.tipo = tipoFirma(s); d.firmante = s.nombreFirmante;
            d.firmadaAt = s.timestamp; d.pngBase64 = base64Archivo(s.imagePath, 2 * 1024 * 1024);
            if (s.proposalId > 0) {
                d.opcion = s.opcionAceptada;
                d.total = Math.round(s.totalAceptado * 100.0) / 100.0;
                d.proposalLocalId = s.proposalId;
            }
            req.signatures.add(d);
        }
        req.parts_used = new ArrayList<>();
        for (com.lc.technician.model.JobPartEntity p : c.repuestos) {
            com.lc.technician.model.PartEntity part = db.partDao().getById(p.partId);
            SyncModels.PartUsedV2Dto d = new SyncModels.PartUsedV2Dto();
            d.localId = p.id; d.jobId = p.jobId; d.cantidad = p.cantidad; d.precioUnitario = p.precioUnitario;
            d.sku = part != null ? part.sku : null;
            d.nombre = part != null && part.nombre != null ? part.nombre : "Repuesto #" + p.partId;
            d.updatedAt = p.updatedAt > 0 ? p.updatedAt : p.timestamp;
            if (p.anulado == 1) d.anulado = Boolean.TRUE;
            req.parts_used.add(d);
        }
        req.payments = new ArrayList<>();
        for (com.lc.technician.model.PaymentEntity p : c.pagos) {
            SyncModels.PaymentV2Dto d = new SyncModels.PaymentV2Dto();
            d.localId = p.syncLocalId; d.jobId = p.jobId; d.monto = p.amount; d.metodo = p.method; d.referencia = p.reference;
            req.payments.add(d);
        }
        req.documents = new ArrayList<>();
        for (com.lc.technician.model.InvoiceSyncEntity f : c.pdfs) {
            SyncModels.DocumentV2Dto d = new SyncModels.DocumentV2Dto();
            d.jobId = f.jobId; d.numero = f.numero; d.pdfBase64 = base64Archivo(f.pdfPath, 5 * 1024 * 1024);
            req.documents.add(d);
        }
    }

    /** Confirmación individual: solo lo que el servidor contestó y sigue en tránsito cambia de estado. */
    static void confirmarCampo(LcDatabase db, LoteCampo c, SyncModels.PushV2Response body) {
        if (body.signatures != null && body.signatures.results != null) {
            for (SyncModels.ResultadoCampoV2 r : body.signatures.results) {
                if (r.localId == null) continue;
                com.lc.technician.model.SignatureEntity s = db.signatureDao().getById(r.localId);
                if (s == null || s.syncState != 2) continue;
                boolean ok = "ACCEPTED".equals(r.status);
                s.syncState = ok ? 1 : 3;
                s.serverId = r.id;
                s.motivoRechazo = ok ? null : r.reason;
                db.signatureDao().update(s);
                if (s.proposalId > 0) {
                    com.lc.technician.model.ProposalEntity p = db.proposalDao().getById(s.proposalId);
                    if (p != null && ok && r.propuesta != null) {
                        copiarEstado(p, r.propuesta);
                        db.proposalDao().update(p);
                    } else if (p != null && !ok) {
                        // el servidor no aceptó la firma presencial: la propuesta vuelve a ser editable (sin aprobar)
                        p.isLocked = false;
                        p.estado = "SENT";
                        p.opcionAceptada = null;
                        p.motivoRechazo = "Firma presencial rechazada: " + r.reason;
                        db.proposalDao().update(p);
                    }
                }
            }
        }
        if (body.parts_used != null && body.parts_used.results != null) {
            List<Long> ok = new ArrayList<>(), ko = new ArrayList<>();
            for (SyncModels.ResultadoCampoV2 r : body.parts_used.results) {
                if (r.localId == null) continue;
                ("ACCEPTED".equals(r.status) ? ok : ko).add(r.localId);
            }
            if (!ok.isEmpty()) db.jobPartDao().confirmar(ok);
            if (!ko.isEmpty()) db.jobPartDao().rechazar(ko);
        }
        if (body.payments != null && body.payments.results != null) {
            for (SyncModels.ResultadoCampoV2 r : body.payments.results) {
                if (r.localId == null) continue;
                com.lc.technician.model.PaymentEntity p = db.paymentDao().getBySyncLocalId(r.localId);
                if (p == null || p.syncState != 2) continue;
                boolean ok = "ACCEPTED".equals(r.status);
                p.syncState = ok ? 1 : 3;
                p.motivo = ok ? null : r.reason;
                if (r.pago != null) { p.serverId = r.pago.id; p.serverEstado = r.pago.estado; }
                if (!ok) p.status = "REJECTED";
                db.paymentDao().actualizar(p);
            }
        }
        if (body.documents != null && body.documents.results != null) {
            for (SyncModels.ResultadoCampoV2 r : body.documents.results) {
                if (r.jobId == null) continue;
                com.lc.technician.model.InvoiceSyncEntity f = db.invoiceSyncDao().get(r.jobId);
                if (f == null || f.pdfSyncState != 2) continue;
                boolean ok = "ACCEPTED".equals(r.status);
                f.pdfSyncState = ok ? 1 : 3;
                f.pdfMotivo = ok ? null : r.reason;
                db.invoiceSyncDao().upsert(f);
            }
        }
    }

    /** Estado de los pagos según el servidor: PAGADO solo cuando despacho lo confirmó. */
    static void aplicarPagos(LcDatabase db, List<SyncModels.PagoV2> pagos) {
        for (SyncModels.PagoV2 s : pagos) {
            if (s.localId == null) continue;
            com.lc.technician.model.PaymentEntity p = db.paymentDao().getBySyncLocalId(s.localId);
            if (p == null || p.syncState != 1) continue;
            p.serverId = s.id;
            p.serverEstado = s.estado;
            p.motivo = s.motivo;
            p.status = "CONFIRMADO".equals(s.estado) ? "PAID" : "RECHAZADO".equals(s.estado) ? "REJECTED" : "REPORTED";
            db.paymentDao().actualizar(p);
        }
    }

    // ── mapeos ──────────────────────────────────────────────────────────────

    private static SyncModels.JobV2Dto aDto(LcDatabase db, JobEntity e, long marca) {
        SyncModels.JobV2Dto d = new SyncModels.JobV2Dto();
        d.id = e.id;
        d.version = e.serverVersion;
        d.updatedAt = marca;
        d.estado = e.estado;
        d.estadoDispatch = e.estadoDispatch;
        d.prioridad = e.prioridad;
        d.technicianId = e.technicianId;
        d.cliente = e.cliente;
        d.telefono = e.telefono;
        d.direccion = e.direccion;
        d.latitud = e.latitud;
        d.longitud = e.longitud;
        d.ubicacionFuente = e.latitud != null ? e.ubicacionFuente : null;
        d.hora = e.hora;
        d.programadoPara = e.programadoPara;
        d.zonaHoraria = e.programadoPara != null ? TimeZone.getDefault().getID() : null;
        d.descripcion = e.descripcion;
        d.equipo = e.equipo;
        d.tipoPropiedad = e.tipoPropiedad;
        d.clientePresente = e.clientePresente;
        d.tipoAcceso = e.tipoAcceso;
        d.codigoAcceso = e.codigoAcceso;
        d.ubicacionLlave = e.ubicacionLlave;
        d.instrucciones = e.instrucciones;
        d.mascota = e.mascota;
        d.contactoRespaldo = e.contactoRespaldo;
        d.notasSeguridad = e.notasSeguridad;
        d.notas = e.notas;
        if (e.customerId != null) {
            CustomerEntity c = db.customerDao().getById(e.customerId);
            if (c != null) {
                d.customerLocal = new SyncModels.CustomerLocalDto();
                d.customerLocal.localId = c.id;
                d.customerLocal.nombre = c.nombre;
                d.customerLocal.telefono = c.telefono;
            }
        }
        if (e.propertyId != null) {
            PropertyEntity p = db.propertyDao().getById(e.propertyId);
            if (p != null) {
                d.propertyLocal = new SyncModels.PropertyLocalDto();
                d.propertyLocal.localId = p.id;
                d.propertyLocal.direccion = p.direccion;
                d.propertyLocal.tipoPropiedad = p.tipoPropiedad;
            }
        }
        return d;
    }

    private static SyncModels.WorkItemV2Dto aDto(WorkItemEntity w) {
        SyncModels.WorkItemV2Dto d = new SyncModels.WorkItemV2Dto();
        d.id = w.id;
        d.jobId = w.jobId;
        d.tipo = w.tipo;
        d.descripcion = w.descripcion;
        d.cantidad = w.cantidad;
        d.precioUnitario = w.precioUnitario;
        d.updatedAt = w.updatedAt > 0 ? w.updatedAt : w.timestamp;
        if (w.anulado == 1) d.anulado = Boolean.TRUE;
        return d;
    }

    /** Valores locales que el conflicto iba a descartar (antes de aplicar el servidor), por columna del servidor. */
    static Map<String, String> valoresLocales(JobEntity e, List<String> campos) {
        Map<String, String> out = new LinkedHashMap<>();
        if (campos == null) return out;
        for (String c : campos) {
            String v;
            switch (c) {
                case "notas": v = e.notas; break;
                case "descripcion": v = e.descripcion; break;
                case "instrucciones": v = e.instrucciones; break;
                case "mascota": v = e.mascota; break;
                case "telefono": v = e.telefono; break;
                case "direccion": v = e.direccion; break;
                case "equipo": v = e.equipo; break;
                case "estado": v = e.estado; break;
                case "contacto_respaldo": v = e.contactoRespaldo; break;
                case "notas_seguridad": v = e.notasSeguridad; break;
                case "tipo_acceso": v = e.tipoAcceso; break;
                case "codigo_acceso_enc": case "ubicacion_llave_enc": v = "•••"; break;  // nunca en claro
                default: v = null;
            }
            out.put(c, v);
        }
        return out;
    }

    private static final ThreadLocal<Map<String, String>> DESCARTADOS = new ThreadLocal<>();

    /**
     * Conflicto (el técnico y despacho cambiaron el mismo campo): el servidor NO pisa a ninguno. Guarda ambas
     * versiones como conflicto pendiente (lo resuelve despacho) y aplica los campos sin conflicto. El teléfono
     * adopta la copia del servidor y conserva su valor en la auditoría local de la orden (recuperable).
     */
    private static void marcarConflictos(Context ctx, Long id, SyncModels.JobV2Dto s) {
        if (id != null && s != null && s.conflictosPendientes != null) {
            LabSession.setConflictos(ctx, id, s.conflictosPendientes);
        }
    }

    static void resolverConflicto(JobEntity local, SyncModels.JobResultV2 c, long ts) {
        DESCARTADOS.set(valoresLocales(local, c.conflictFields));
        aplicarServidor(local, c.server);
        local.serverVersion = c.version;
        local.lastSyncedAt = ts;
        local.synced = 1;
    }

    static String descartadosTexto(JobEntity e, SyncModels.JobResultV2 c) {
        Map<String, String> d = DESCARTADOS.get();
        DESCARTADOS.remove();
        StringBuilder sb = new StringBuilder("Conflicto pendiente de resolución en el centro de mando");
        if (c.conflictIds != null && !c.conflictIds.isEmpty()) sb.append(" (n.º ").append(c.conflictIds).append(")");
        sb.append(". Se muestra la versión del centro de mando; el valor del técnico queda guardado.");
        if (d != null && !d.isEmpty()) {
            sb.append(" Valor del técnico: ");
            for (Map.Entry<String, String> x : d.entrySet()) {
                sb.append(x.getKey()).append("=").append(x.getValue() == null ? "∅" : x.getValue()).append("; ");
            }
        }
        return sb.toString().trim();
    }

    /** Copia los campos del servidor sobre la entidad local (conserva ids locales de cliente/propiedad). */
    static void aplicarServidor(JobEntity e, SyncModels.JobV2Dto s) {
        if (s == null) return;
        e.estado = s.estado != null ? JobEntity.normalizeEstado(s.estado) : e.estado;
        e.estadoDispatch = s.estadoDispatch;
        e.prioridad = s.prioridad;
        e.technicianId = s.technicianId;
        e.cliente = s.cliente;
        e.telefono = s.telefono;
        e.direccion = s.direccion;
        e.latitud = s.latitud;
        e.longitud = s.longitud;
        e.ubicacionFuente = s.ubicacionFuente;
        e.hora = s.hora;
        e.programadoPara = s.programadoPara;
        e.descripcion = s.descripcion;
        e.equipo = s.equipo;
        e.tipoPropiedad = s.tipoPropiedad;
        if (s.clientePresente != null) e.clientePresente = s.clientePresente;
        e.tipoAcceso = s.tipoAcceso;
        // Código / llave: si el servidor los marca protegidos (p. ej. la orden se reasignó a otro técnico),
        // el teléfono borra su copia; si vienen null sin protección, no se toca lo que ya tenga.
        if (Boolean.TRUE.equals(s.codigoAccesoProtegido)) e.codigoAcceso = "";
        else if (s.codigoAcceso != null) e.codigoAcceso = s.codigoAcceso;
        if (Boolean.TRUE.equals(s.ubicacionLlaveProtegido)) e.ubicacionLlave = "";
        else if (s.ubicacionLlave != null) e.ubicacionLlave = s.ubicacionLlave;
        e.instrucciones = s.instrucciones;
        e.mascota = s.mascota;
        e.contactoRespaldo = s.contactoRespaldo;
        e.notasSeguridad = s.notasSeguridad;
        e.notas = s.notas;
        if (s.updatedAt != null) e.updatedAt = s.updatedAt;
    }

    private static List<SyncModels.PhotoV2Dto> fotosPendientes(Context ctx, LcDatabase db) {
        Set<String> ok = LabSession.confirmados(ctx, "foto");
        List<SyncModels.PhotoV2Dto> out = new ArrayList<>();
        for (PhotoEntity p : db.photoDao().getAll()) {
            if (out.size() >= MAX_FOTOS) break;
            if (ok.contains(String.valueOf(p.id)) || p.path == null) continue;
            JobEntity j = db.jobDao().getJobById(p.jobId);
            if (j == null || j.serverVersion == null) continue;   // la orden aún no existe en el servidor
            File f = new File(p.path);
            if (!f.exists() || f.length() == 0 || f.length() > MAX_FOTO_BYTES) continue;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) f.length()];
                int leido = 0;
                while (leido < b.length) {
                    int n = in.read(b, leido, b.length - leido);
                    if (n < 0) break;
                    leido += n;
                }
                SyncModels.PhotoV2Dto d = new SyncModels.PhotoV2Dto();
                d.id = p.id;
                d.jobId = p.jobId;
                d.filename = f.getName();
                d.mimeType = f.getName().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
                d.categoria = p.categoria;
                d.base64Data = Base64.encodeToString(b, Base64.NO_WRAP);
                out.add(d);
            } catch (Exception ex) {
                Log.w(TAG, "foto " + p.id + " no legible: " + ex.getClass().getSimpleName());
            }
        }
        return out;
    }

    private static List<SyncModels.EventV2Dto> medicionesPendientes(Context ctx, LcDatabase db) {
        Set<String> ok = LabSession.confirmados(ctx, "evento");
        List<SyncModels.EventV2Dto> out = new ArrayList<>();
        for (JobEntity j : db.jobDao().getAll()) {
            if (j.serverVersion == null) continue;
            for (FieldMeasurementEntity m : db.fieldMeasurementDao().getMeasurementsForJob(j.id)) {
                String cid = "med-" + m.id;
                if (ok.contains(cid) || out.size() >= 50) continue;
                SyncModels.EventV2Dto ev = new SyncModels.EventV2Dto();
                ev.clientEventId = cid;
                ev.jobId = j.id;
                ev.tipo = "MEDICION";
                ev.payload = new LinkedHashMap<>();
                ev.payload.put("nombre", m.tipo);
                ev.payload.put("valor", m.valor);
                if (m.unidad != null) ev.payload.put("unidad", m.unidad);
                if (m.ubicacion != null) ev.payload.put("ubicacion", m.ubicacion);
                if (m.nota != null) ev.payload.put("nota", m.nota);
                out.add(ev);
            }
        }
        return out;
    }

    private static void auditar(LcDatabase db, long jobId, String accion, String campo, String detalle) {
        AuditLogEntity a = new AuditLogEntity();
        a.entityType = "JOB";
        a.entityId = jobId;
        a.jobId = jobId;
        a.action = accion;
        a.field = campo;
        a.newValue = detalle;
        a.actor = "sync";
        a.timestamp = System.currentTimeMillis();
        db.auditLogDao().insert(a);
    }
}
