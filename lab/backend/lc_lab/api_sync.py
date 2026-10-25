"""Sincronización v2 con la app del técnico.

push: cada trabajo recibe un resultado propio (ACCEPTED / REJECTED / CONFLICT) con la versión del
servidor y el updatedAt del cliente que se confirmó, para que el teléfono solo marque como sincronizada
exactamente esa versión. Idempotente: repetir el mismo push devuelve el mismo resultado sin duplicar.
pull: trabajos asignados al técnico (o creados por su dispositivo) cambiados desde un cursor.
"""
import base64
import json

from flask import Blueprint, g, jsonify, request

from . import jobs as J
from .api_ops import camino_de_estados, guardar_evento, guardar_foto
from .db import audit, get_db, now_ms
from .security import requiere

bp = Blueprint("sync", __name__, url_prefix="/api/v2/sync")
MAX_LOTE = 50
RANGO_SERVIDOR = 8_000_000_000_000   # ids creados por el servidor (cliente/despacho)
BASE_DISPOSITIVOS = 10_000_000_000_000  # 1e13 + n·1e8: espacio propio de cada teléfono registrado
TAMANO_ESPACIO = 100_000_000


def registrar_dispositivo(db, device_id, technician_code):
    """Número único por dispositivo (estable entre inicios de sesión) y base de su espacio de ids."""
    r = db.execute("SELECT device_number FROM device_registry WHERE device_id = ?", (device_id,)).fetchone()
    if r is None:
        n = db.execute("INSERT INTO device_registry(device_id, technician_code, first_seen) VALUES (?,?,?)",
                       (device_id, technician_code, now_ms())).lastrowid
    else:
        n = r[0]
    return n, BASE_DISPOSITIVOS + n * TAMANO_ESPACIO


def dueno_del_dispositivo(db, device, user):
    """True si el teléfono está registrado a nombre de este técnico (el primero que lo registró).
    Las órdenes creadas en un teléfono solo son "propias" para su dueño: otro técnico que inicie sesión en el
    mismo teléfono no ve ni modifica esas órdenes salvo que despacho se las asigne."""
    if not device or not user["technician_code"]:
        return False
    r = db.execute("SELECT technician_code FROM device_registry WHERE device_id = ?", (device,)).fetchone()
    return r is not None and r[0] == user["technician_code"]


def orden_propia(db, jrow, user, device):
    return jrow["technician_code"] == user["technician_code"] or (
        jrow["origin_device"] == device and dueno_del_dispositivo(db, device, user))


def id_permitido_para_crear(db, device, jid):
    """Ids que un teléfono puede usar para una orden NUEVA: históricos (< 8e12) o los de su propio espacio."""
    if jid < RANGO_SERVIDOR:
        return None
    r = db.execute("SELECT device_number FROM device_registry WHERE device_id = ?", (device,)).fetchone()
    if r is not None:
        base = BASE_DISPOSITIVOS + r[0] * TAMANO_ESPACIO
        if base <= jid < base + TAMANO_ESPACIO:
            return None
    return "ID_FUERA_DE_RANGO: id reservado al servidor o a otro dispositivo"


def _vincular_cliente(db, device, d):
    """Cliente/propiedad del teléfono → entidades del servidor, sin duplicar (dispositivo + id local)."""
    cust_id = prop_id = None
    cl = d.get("customerLocal")
    if isinstance(cl, dict) and cl.get("localId") and cl.get("nombre"):
        r = db.execute("SELECT id FROM customers WHERE origin_device = ? AND origin_local_id = ?",
                       (device, cl["localId"])).fetchone()
        t = now_ms()
        if r:
            cust_id = r[0]
            db.execute("UPDATE customers SET nombre = ?, telefono = ?, updated_at = ? WHERE id = ?",
                       (cl["nombre"], cl.get("telefono"), t, cust_id))
        else:
            cust_id = db.execute("INSERT INTO customers(nombre, telefono, origin_device, origin_local_id, created_at, "
                                 "updated_at) VALUES (?,?,?,?,?,?)",
                                 (cl["nombre"], cl.get("telefono"), device, cl["localId"], t, t)).lastrowid
    pl = d.get("propertyLocal")
    if cust_id and isinstance(pl, dict) and pl.get("localId") and pl.get("direccion"):
        r = db.execute("SELECT id FROM properties WHERE origin_device = ? AND origin_local_id = ?",
                       (device, pl["localId"])).fetchone()
        t = now_ms()
        if r:
            prop_id = r[0]
        else:
            prop_id = db.execute("INSERT INTO properties(customer_id, direccion, tipo_propiedad, lat, lng, "
                                 "ubicacion_fuente, origin_device, origin_local_id, created_at, updated_at) "
                                 "VALUES (?,?,?,?,?,?,?,?,?,?)",
                                 (cust_id, pl["direccion"], pl.get("tipoPropiedad"), d.get("latitud"), d.get("longitud"),
                                  d.get("ubicacionFuente") if d.get("latitud") is not None else None,
                                  device, pl["localId"], t, t)).lastrowid
    return cust_id, prop_id


def _procesar_job(db, user, device, d):
    jid = d.get("id")
    cua = d.get("updatedAt")
    if not isinstance(jid, int) or not isinstance(cua, int):
        return {"id": jid, "status": "REJECTED", "reason": "id y updatedAt (enteros) son obligatorios",
                "clientUpdatedAt": cua}
    prev = db.execute("SELECT status, version, detail FROM sync_receipts WHERE device_id = ? AND job_id = ? AND "
                      "client_updated_at = ?", (device, jid, cua)).fetchone()
    if prev:  # reintento del mismo cambio: misma respuesta, sin aplicar dos veces
        out = {"id": jid, "status": prev["status"], "version": prev["version"], "clientUpdatedAt": cua,
               "replay": True}
        if prev["detail"]:
            out.update(json.loads(prev["detail"]))
        if prev["status"] in ("CONFLICT", "ACCEPTED"):  # copia actual para resolver / converger
            actual = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
            if actual is not None:
                out["server"] = J.a_api(actual, user, db)
        return out
    row = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
    actor = user["username"]
    if row is None:
        errs = J.validar(d, creando=True)
        motivo = id_permitido_para_crear(db, device, jid)
        if motivo:
            errs.append(motivo)
        if errs:
            res = {"id": jid, "status": "REJECTED", "reason": "; ".join(errs), "clientUpdatedAt": cua}
        else:
            cust_id, prop_id = _vincular_cliente(db, device, d)
            extra = {"customer_id": cust_id, "property_id": prop_id}
            if not d.get("technicianId"):
                extra["technician_code"] = user["technician_code"]
            J.crear(db, d, "DEVICE", actor, "android", device=device, job_id=jid, extra=extra)
            res = {"id": jid, "status": "ACCEPTED", "version": 1, "clientUpdatedAt": cua, "created": True,
                   "server": J.a_api(db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone(), user, db)}
    else:
        propio = orden_propia(db, row, user, device)
        errs = J.validar(d, creando=False)
        if d.get("version") is None and row["origin"] == "DEVICE" and row["origin_device"] != device:
            # el teléfono nunca sincronizó esta orden y el id ya lo usó OTRO dispositivo: colisión, no se fusiona
            res = {"id": jid, "status": "REJECTED", "clientUpdatedAt": cua,
                   "reason": "ID_EN_USO: otro dispositivo ya creó una orden con este id"}
        elif user["role"] == "TECHNICIAN" and not propio:
            res = {"id": jid, "status": "REJECTED", "reason": "orden no asignada a este técnico", "clientUpdatedAt": cua}
        elif errs:
            res = {"id": jid, "status": "REJECTED", "reason": "; ".join(errs), "clientUpdatedAt": cua}
        elif d.get("estado") and camino_de_estados(row["estado"], d["estado"], user["role"]) is None:
            res = {"id": jid, "status": "REJECTED", "clientUpdatedAt": cua,
                   "reason": f"transición no permitida: {row['estado']} → {d['estado']}"}
        elif d.get("version") == 0:
            res = {"id": jid, "status": "REJECTED", "clientUpdatedAt": cua,
                   "reason": "versión 0 no válida: envía la versión actual del servidor"}
        elif isinstance(d.get("version"), int) and d["version"] > row["version"]:
            # Versión del futuro: imposible salvo error o manipulación; no se aplica nada.
            actual = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
            res = {"id": jid, "status": "CONFLICT", "version": row["version"], "clientUpdatedAt": cua,
                   "reason": f"versión {d['version']} no válida (servidor: {row['version']})",
                   "conflictFields": [], "server": J.a_api(actual, user, db)}
        else:
            datos = dict(d)
            # el técnico no puede reasignarse ni reprogramar desde el teléfono: eso es de despacho
            if user["role"] == "TECHNICIAN":
                for k in ("technicianId", "estadoDispatch", "prioridad", "programadoPara"):
                    datos.pop(k, None)
            intermedios = camino_de_estados(row["estado"], d["estado"], user["role"])[:-1] if d.get("estado") else []
            status, v, conf = J.aplicar(db, row, datos, actor, "android",
                                        version_base=d.get("version"), device=device)
            if status == "ACCEPTED" and intermedios:
                # el teléfono avanzó varios pasos sin conexión: queda constancia de los pasos inferidos
                db.execute("INSERT INTO job_history(job_id, version, field, old_value, new_value, actor, source, ts) "
                           "VALUES (?,?,?,?,?,?,?,?)", (jid, v, "estado_intermedio", row["estado"],
                                                       " → ".join(intermedios), actor, "android-inferido", now_ms()))
            if status == "CONFLICT":
                actual = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
                res = {"id": jid, "status": "CONFLICT", "version": v, "clientUpdatedAt": cua,
                       "conflictFields": conf, "server": J.a_api(actual, user, db),
                       "conflictIds": [c["id"] for c in J.conflictos_pendientes(db, jid) if c["field"] in conf]}
            else:
                # se devuelve el resultado del merge: el teléfono lo aplica y ambos quedan iguales
                res = {"id": jid, "status": "ACCEPTED", "version": v, "clientUpdatedAt": cua,
                       "server": J.a_api(db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone(), user, db)}
    detalle = {k: res[k] for k in ("reason", "conflictFields") if k in res}
    db.execute("INSERT INTO sync_receipts(device_id, job_id, client_updated_at, status, version, detail, ts) "
               "VALUES (?,?,?,?,?,?,?)", (device, jid, cua, res["status"], res.get("version"),
                                          json.dumps(detalle) if detalle else None, now_ms()))
    return res


TIPOS_WI = {"LABOR", "PART", "SERVICE", "FEE", "OTHER", "DISCOUNT"}


def _work_items(db, user, device, items, borrados):
    """Upsert por (dispositivo, id local); solo en órdenes propias; valida tipo y números."""
    ok, ko = [], []
    t = now_ms()
    for w in items:
        lid = w.get("id")
        try:
            jrow = db.execute("SELECT * FROM jobs WHERE id = ?", (w.get("jobId"),)).fetchone()
            if jrow is None or not orden_propia(db, jrow, user, device):
                ko.append({"id": lid, "reason": "orden desconocida o ajena"})
                continue
            anul = w.get("anulado") is True
            tipo = w.get("tipo") if w.get("tipo") in TIPOS_WI else ("PART" if w.get("tipo") in ("MATERIAL", "PARTE") else None)
            cant, precio = w.get("cantidad"), w.get("precioUnitario")
            if tipo is None or not isinstance(lid, int) or not isinstance(cant, (int, float)) or cant <= 0 \
                    or not isinstance(precio, (int, float)) or precio < 0:
                ko.append({"id": lid, "reason": "tipo, cantidad > 0 y precio >= 0 requeridos"})
                continue
            upd = int(w.get("updatedAt") or 0)
            prev = db.execute("SELECT id, client_updated_at FROM job_work_items WHERE origin_device = ? AND local_id = ?",
                              (device, lid)).fetchone()
            total = round(cant * precio, 2)
            if prev is None:
                # también si llega ya anulada (creada y anulada sin conexión): se guarda como evidencia
                db.execute("INSERT INTO job_work_items(job_id, origin_device, local_id, tipo, descripcion, cantidad, "
                           "precio_unitario, total, client_updated_at, actor, created_at, updated_at, anulado) "
                           "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                           (jrow["id"], device, lid, tipo, w.get("descripcion"), cant, precio, total, upd,
                            user["username"], t, t, 1 if anul else 0))
            elif upd >= prev["client_updated_at"]:   # una versión más vieja que llegue tarde no pisa la nueva
                db.execute("UPDATE job_work_items SET tipo=?, descripcion=?, cantidad=?, precio_unitario=?, total=?, "
                           "client_updated_at=?, anulado=?, updated_at=? WHERE id=?",
                           (tipo, w.get("descripcion"), cant, precio, total, upd, 1 if anul else 0, t, prev["id"]))
            if anul:
                audit(db, user["username"], "WORK_ITEM_ANULADO", "work_item", f"{device}:{lid}")
            ok.append(lid)
        except Exception as e:  # noqa: BLE001
            ko.append({"id": lid, "reason": type(e).__name__})
    for lid in borrados:
        cur = db.execute("UPDATE job_work_items SET anulado = 1, updated_at = ? WHERE origin_device = ? AND local_id = ?",
                         (t, device, lid))
        # se confirma aunque no existiera (nunca llegó al servidor): el teléfono ya puede olvidar el borrado
        ok.append(lid)
        if cur.rowcount:
            audit(db, user["username"], "WORK_ITEM_ANULADO", "work_item", f"{device}:{lid}")
    return ok, ko


def _fallos():
    import json as _j
    import os as _o
    from flask import current_app
    if not current_app.config.get("LAB_FAULTS"):
        return None, None
    ruta = _o.path.join(_o.path.dirname(current_app.config["DATABASE"]), "fault.json")
    if not _o.path.exists(ruta):
        return ruta, {}
    try:
        return ruta, _j.load(open(ruta))
    except Exception:
        return ruta, {}


def _retraso_simulado():
    import time as _t
    ruta, f = _fallos()
    if f and f.get("push_delay_s"):
        # El retardo simula red/proceso lento: no debe retener el bloqueo de escritura tomado al autenticar.
        from flask import g
        db = g.get("db")
        if db is not None and db.in_transaction:
            db.commit()
        _t.sleep(min(float(f["push_delay_s"]), 30))
        if db is not None:
            db.execute("BEGIN IMMEDIATE")


def _fallo_tras_guardar():
    import json as _j
    ruta, f = _fallos()
    if f and int(f.get("push_fail_after_commit", 0)) > 0:
        f["push_fail_after_commit"] = int(f["push_fail_after_commit"]) - 1
        with open(ruta, "w") as fh:
            _j.dump(f, fh)
        return True
    return False


@bp.post("/device")
@requiere("TECHNICIAN")
def dispositivo():
    """Registro idempotente del teléfono con la sesión actual → su espacio de ids (sin cerrar sesión)."""
    d = request.get_json(silent=True) or {}
    device = (d.get("deviceId") or g.user["token_device"] or "").strip()
    if not device:
        return jsonify(error="deviceId requerido"), 400
    db = get_db()
    n, base = registrar_dispositivo(db, device, g.user["technician_code"])
    db.commit()
    return jsonify(deviceNumber=n, idBase=base, idLimite=base + TAMANO_ESPACIO)


@bp.post("/push")
@requiere("TECHNICIAN")
def push():
    body = request.get_json(silent=True)
    if not isinstance(body, dict):
        return jsonify(error="cuerpo JSON requerido"), 400
    user = g.user
    device = (body.get("deviceId") or user["token_device"] or "").strip()
    if not device:
        return jsonify(error="deviceId requerido"), 400
    lote = body.get("jobs") or []
    if not isinstance(lote, list) or len(lote) > MAX_LOTE:
        return jsonify(error=f"jobs debe ser una lista de hasta {MAX_LOTE}"), 400
    _retraso_simulado()
    db = get_db()
    resultados = []
    for d in lote:
        # cada trabajo en su propio savepoint: un fallo no arrastra a los demás
        db.execute("SAVEPOINT job")
        try:
            r = _procesar_job(db, user, device, d if isinstance(d, dict) else {})
            db.execute("RELEASE job")
        except Exception as e:  # noqa: BLE001 — se informa por trabajo, no se pierde el lote
            db.execute("ROLLBACK TO job")
            db.execute("RELEASE job")
            r = {"id": d.get("id") if isinstance(d, dict) else None, "status": "REJECTED",
                 "reason": f"error interno: {type(e).__name__}", "clientUpdatedAt": d.get("updatedAt")
                 if isinstance(d, dict) else None}
        resultados.append(r)
    fotos_ok, fotos_ko = [], []
    for p in body.get("photos") or []:
        try:
            jrow = db.execute("SELECT * FROM jobs WHERE id = ?", (p.get("jobId"),)).fetchone()
            if jrow is None or not orden_propia(db, jrow, user, device):
                fotos_ko.append({"id": p.get("id"), "reason": "orden desconocida o ajena"})
                continue
            pid, err = guardar_foto(db, jrow["id"], base64.b64decode(p.get("base64Data") or ""),
                                    p.get("mimeType") or "image/jpeg", user["username"], p.get("categoria"),
                                    device=device, local_id=p.get("id"), nombre=p.get("filename"))
            (fotos_ko.append({"id": p.get("id"), "reason": err}) if err else fotos_ok.append(p.get("id")))
        except Exception as e:  # noqa: BLE001
            fotos_ko.append({"id": p.get("id"), "reason": type(e).__name__})
    wi_ok, wi_ko = _work_items(db, user, device, body.get("work_items") or [],
                               body.get("deleted_work_item_ids") or [])
    ev_ok, ev_ko = [], []
    for e in body.get("events") or []:
        jrow = db.execute("SELECT * FROM jobs WHERE id = ?", (e.get("jobId"),)).fetchone()
        if jrow is None or not orden_propia(db, jrow, user, device):
            ev_ko.append({"clientEventId": e.get("clientEventId"), "reason": "orden desconocida o ajena"})
            continue
        eid, err = guardar_evento(db, jrow["id"], e.get("tipo"), e.get("payload"), user["username"],
                                  e.get("clientEventId"))
        (ev_ko.append({"clientEventId": e.get("clientEventId"), "reason": err}) if err
         else ev_ok.append(e.get("clientEventId")))
    # propuestas y facturas después de órdenes y partidas: la factura usa lo recién subido en este mismo envío
    from . import proposals as PR
    from . import campo as CA
    def _lista(clave, n=20):
        return [x for x in (body.get(clave) or [])[:n] if isinstance(x, dict)]
    prop_res = [PR.upsert_desde_dispositivo(db, user, device, p) for p in _lista("proposals")]
    firma_res = [CA.firma_desde_dispositivo(db, user, device, s) for s in _lista("signatures", 10)]   # tras propuestas
    rep_res = [CA.repuesto_desde_dispositivo(db, user, device, r) for r in _lista("parts_used", 50)]  # antes de facturar
    fact_res = [PR.emitir_desde_tecnico(db, user, device, f) for f in _lista("invoices")]
    pago_res = [CA.pago_desde_dispositivo(db, user, device, p) for p in _lista("payments")]          # tras facturar
    doc_res = [CA.documento_desde_dispositivo(db, user, device, d) for d in _lista("documents", 3)]
    audit(db, user["username"], "SYNC_PUSH", "device", device,
          f"jobs={len(lote)} ok={sum(r['status'] == 'ACCEPTED' for r in resultados)}")
    db.commit()
    if _fallo_tras_guardar():
        # LABORATORIO: el servidor guardó pero la respuesta "se pierde" → prueba de reintento/idempotencia
        return jsonify(error="fallo simulado tras guardar (laboratorio)"), 503
    return jsonify(
        work_items={"accepted_ids": wi_ok, "rejected": wi_ko},
        results=resultados,
        accepted_job_ids=[r["id"] for r in resultados if r["status"] == "ACCEPTED"],
        rejected_job_ids=[r["id"] for r in resultados if r["status"] == "REJECTED"],
        conflict_job_ids=[r["id"] for r in resultados if r["status"] == "CONFLICT"],
        accepted=sum(r["status"] == "ACCEPTED" for r in resultados),
        rejected=sum(r["status"] != "ACCEPTED" for r in resultados),
        photos={"accepted_ids": fotos_ok, "rejected": fotos_ko},
        events={"accepted_ids": ev_ok, "rejected": ev_ko},
        proposals={"results": prop_res},
        invoices={"results": fact_res},
        signatures={"results": firma_res},
        parts_used={"results": rep_res},
        payments={"results": pago_res},
        documents={"results": doc_res},
        timestamp=now_ms(),
    )


@bp.get("/pull")
@requiere("TECHNICIAN")
def pull():
    user = g.user
    since = request.args.get("since", default=0, type=int)
    _, fallo = _fallos()
    if fallo and fallo.get("pull_delay_s"):     # laboratorio: respuesta lenta del pull (sin escrituras pendientes)
        import time as _t
        _t.sleep(min(float(fallo["pull_delay_s"]), 25))
    device = request.args.get("deviceId") or user["token_device"] or ""
    db = get_db()
    # incluye las órdenes que tuvo asignadas y le quitaron, para que el teléfono lo sepa
    # órdenes creadas en este teléfono: solo si el teléfono es de este técnico (teléfono compartido → no se filtran)
    propio_disp = device if dueno_del_dispositivo(db, device, user) else None
    rows = db.execute("SELECT * FROM jobs WHERE seq > ? AND (technician_code = ? OR origin_device = ? OR id IN "
                      "(SELECT job_id FROM job_history WHERE field = 'technician_code' AND old_value = ?)) "
                      "ORDER BY seq LIMIT 200",
                      (since, user["technician_code"], propio_disp, user["technician_code"])).fetchall()
    cursor = max([since] + [r["seq"] for r in rows])
    return jsonify(jobs=[J.a_api(r, user, db) for r in rows], cursor=cursor, server_time=now_ms())
