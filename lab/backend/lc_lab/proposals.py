"""Propuestas (Good/Better/Best) y facturas creadas desde la app del técnico (sync v2)."""
import base64
import hashlib
import json
import os
from decimal import ROUND_HALF_UP, Decimal

from flask import current_app

from .db import audit, next_counter, now_ms

NIVELES = ("GOOD", "BETTER", "BEST")
COSTOS = ("costoEquipo", "costoManoObra", "costoMateriales", "costoPermiso", "costoRemocion", "costoAccesorios")
VIGENCIA_MS = 7 * 24 * 3600 * 1000
# texto libre por opción (máquina/equipo propuesto, especificaciones, garantía): forma parte del contenido firmado
DETALLES = ("equipoPropuesto", "especificaciones", "garantia")
MAX_DETALLE = 300


def _d(v):
    """Importe exacto (sin el error binario de float): 110 × 1.15 = 126.50, no 126.4999…"""
    return Decimal(str(v or 0))


def centavos(v):
    """Redondeo comercial al centavo (mitad hacia arriba), igual en propuesta, factura y app."""
    return _d(v).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def total_opcion(o):
    """Misma fórmula que la app (ProposalOptionEntity.calcularTotal): recargo e impuesto redondeados al centavo."""
    duros = sum(_d(o.get(c)) for c in COSTOS)
    con_markup = centavos(duros * (1 + _d(o.get("markupPorcentaje")) / 100))
    con_desc = con_markup - _d(o.get("descuentoMonto"))
    return float(max(Decimal(0), centavos(con_desc * (1 + _d(o.get("impuestoPorcentaje")) / 100))))


def _validar_opciones(opciones):
    if not isinstance(opciones, list) or not opciones:
        return None, "la propuesta no tiene opciones"
    limpias = []
    for o in opciones:
        if not isinstance(o, dict) or o.get("nivel") not in NIVELES:
            return None, "nivel de opción inválido"
        for k in COSTOS + ("descuentoMonto",):
            v = o.get(k) or 0
            if not isinstance(v, (int, float)) or v < 0 or v > 1_000_000:
                return None, f"{k} inválido en {o['nivel']}"
        for k, tope in (("markupPorcentaje", 300), ("impuestoPorcentaje", 30)):
            v = o.get(k) or 0
            if not isinstance(v, (int, float)) or v < 0 or v > tope:
                return None, f"{k} fuera de rango en {o['nivel']}"
        total = total_opcion(o)
        if total > 0 and not (o.get("titulo") or "").strip():
            return None, f"la opción {o['nivel']} necesita título"
        for k in DETALLES:
            v = o.get(k)
            if v is not None and (not isinstance(v, str) or len(v.strip()) > MAX_DETALLE):
                return None, f"{k} inválido en {o['nivel']} (texto de hasta {MAX_DETALLE} caracteres)"
        cliente = o.get("totalCalculado")
        # tolerancia de 1 centavo: Java (app) y Python pueden redondear distinto un medio centavo; el servidor
        # guarda siempre su propio total
        if isinstance(cliente, (int, float)) and abs(round(cliente, 2) - total) > 0.0151:
            return None, f"el total de {o['nivel']} no coincide con el cálculo del servidor"
        if total <= 0:
            continue                       # opción vacía: no se ofrece al cliente
        limpia = {k: (o.get(k) or 0) for k in COSTOS + ("markupPorcentaje", "descuentoMonto", "impuestoPorcentaje")}
        limpia.update(nivel=o["nivel"], titulo=o["titulo"].strip(), descripcion=(o.get("descripcion") or "").strip(),
                      total=total, recomendada=bool(o.get("recomendada")))
        limpia.update({k: o[k].strip() for k in DETALLES if isinstance(o.get(k), str) and o[k].strip()})
        limpias.append(limpia)
    if not limpias:
        return None, "ninguna opción tiene importe"
    limpias.sort(key=lambda x: NIVELES.index(x["nivel"]))
    return limpias, None


def sha_contenido(opciones):
    return hashlib.sha256(json.dumps(opciones, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def estado_efectivo(db, p):
    """Las pendientes caducadas pasan a VENCIDA (se persiste)."""
    if p["estado"] == "PENDIENTE" and now_ms() > p["expires_at"]:
        db.execute("UPDATE proposals SET estado = 'VENCIDA', updated_at = ? WHERE id = ?", (now_ms(), p["id"]))
        return "VENCIDA"
    return p["estado"]


def _tocar_orden(db, jid):
    """Nueva secuencia de la orden: el pull del técnico recibe el cambio aunque la orden no cambie de versión."""
    db.execute("UPDATE jobs SET seq = ? WHERE id = ?", (next_counter(db, "job_seq"), jid))


def upsert_desde_dispositivo(db, user, device, p):
    lid, jid = p.get("localId"), p.get("jobId")
    base = {"localId": lid, "jobId": jid}
    if not isinstance(lid, int) or lid <= 0:
        return dict(base, status="REJECTED", reason="localId inválido")
    jrow = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
    if jrow is None or jrow["technician_code"] != user["technician_code"]:
        return dict(base, status="REJECTED", reason="orden desconocida o no asignada a este técnico")
    if jrow["estado"] in ("COMPLETADO", "CANCELADO"):
        return dict(base, status="REJECTED", reason="la orden ya está cerrada")
    opciones, err = _validar_opciones(p.get("opciones"))
    if err:
        return dict(base, status="REJECTED", reason=err)
    sha = sha_contenido(opciones)
    cur = db.execute("SELECT * FROM proposals WHERE origin_device = ? AND local_id = ? ORDER BY revision DESC LIMIT 1",
                     (device, lid)).fetchone()
    t = now_ms()
    if cur is not None and cur["contenido_sha256"] == sha:
        return dict(base, status="ACCEPTED", **serializar(db, cur, tecnico=True))   # reintento idempotente
    if cur is not None and cur["job_id"] != jid:
        return dict(base, status="REJECTED", reason="localId ya usado en otra orden")
    rev = 1 if cur is None else cur["revision"] + 1
    if cur is not None and estado_efectivo(db, cur) == "PENDIENTE":
        db.execute("UPDATE proposals SET estado = 'SUPERADA', updated_at = ? WHERE id = ?", (t, cur["id"]))
    pid = db.execute("INSERT INTO proposals(job_id, origin_device, local_id, revision, opciones, contenido_sha256, estado,"
                     " solicitada_por, created_at, updated_at, expires_at) VALUES (?,?,?,?,?,?, 'PENDIENTE', ?,?,?,?)",
                     (jid, device, lid, rev, json.dumps(opciones, ensure_ascii=False), sha, user["username"], t, t,
                      t + VIGENCIA_MS)).lastrowid
    _tocar_orden(db, jid)
    audit(db, user["username"], "PROPOSAL_SENT", "proposal", pid, f"job={jid} rev={rev}", device)
    return dict(base, status="ACCEPTED", **serializar(db, db.execute("SELECT * FROM proposals WHERE id = ?",
                                                                       (pid,)).fetchone(), tecnico=True))


def serializar(db, p, tecnico=False):
    out = {"id": p["id"], "jobId": p["job_id"], "revision": p["revision"], "estado": estado_efectivo(db, p),
           "contenidoSha256": p["contenido_sha256"], "opcionElegida": p["opcion_elegida"],
           "totalAceptado": p["total_aceptado"], "firmante": p["firmante"], "decidedAt": p["decided_at"],
           "expiresAt": p["expires_at"], "createdAt": p["created_at"], "firmaSha256": p["firma_sha256"]}
    if tecnico:
        out["localId"] = p["local_id"]
    else:
        out["opciones"] = json.loads(p["opciones"])
    return out


def propuestas_de_orden(db, jid, tecnico):
    return [serializar(db, p, tecnico) for p in
            db.execute("SELECT * FROM proposals WHERE job_id = ? ORDER BY id", (jid,))]


def decidir(db, user, pid, d):
    """Cliente: APROBADA (opción + firmante + firma PNG) o RECHAZADA, siempre sobre el contenido que vio."""
    p = db.execute("SELECT * FROM proposals WHERE id = ?", (pid,)).fetchone()
    if p is None:
        return 404, "propuesta no encontrada", None
    job = db.execute("SELECT customer_id FROM jobs WHERE id = ?", (p["job_id"],)).fetchone()
    if job is None or job["customer_id"] != user["customer_id"]:
        return 404, "propuesta no encontrada", None
    estado = estado_efectivo(db, p)
    if estado == "SUPERADA":
        return 409, "esta versión de la propuesta fue reemplazada; revisa la nueva", None
    if estado == "VENCIDA":
        return 409, "la propuesta venció; pide una nueva al técnico", None
    if estado != "PENDIENTE":
        return 409, "esta propuesta ya fue decidida", None
    if d.get("contenidoSha256") != p["contenido_sha256"]:
        return 409, "la propuesta cambió desde que la abriste; recarga", None
    decision = d.get("decision")
    if decision not in ("APROBADA", "RECHAZADA"):
        return 400, "decision debe ser APROBADA o RECHAZADA", None
    opciones = json.loads(p["opciones"])
    elegida, total, sha_firma = None, None, None
    firmante = (d.get("firmante") or "").strip() or None
    if decision == "APROBADA":
        elegida = next((o for o in opciones if o["nivel"] == d.get("opcion")), None)
        if elegida is None:
            return 400, "elige una de las opciones ofrecidas", None
        if not firmante or len(firmante) < 3:
            return 400, "escribe tu nombre completo", None
        try:
            png = base64.b64decode(d.get("firmaPngBase64") or "", validate=True)
        except Exception:
            return 400, "firma inválida", None
        if png[:8] != b"\x89PNG\r\n\x1a\n" or len(png) > 2 * 1024 * 1024:
            return 400, "la firma debe ser una imagen PNG", None
        sha_firma = hashlib.sha256(png).hexdigest()
        carpeta = os.path.join(current_app.config["UPLOADS"], str(p["job_id"]), "firmas")
        os.makedirs(carpeta, mode=0o700, exist_ok=True)
        with open(os.path.join(carpeta, f"propuesta_{pid}_r{p['revision']}.png"), "wb") as fh:
            fh.write(png)
        total = elegida["total"]
    t = now_ms()
    db.execute("UPDATE proposals SET estado = ?, opcion_elegida = ?, total_aceptado = ?, firmante = ?, rol_firmante = "
               "'CLIENTE', firma_sha256 = ?, decided_at = ?, updated_at = ? WHERE id = ?",
               (decision, elegida["nivel"] if elegida else None, total, firmante, sha_firma, t, t, pid))
    _tocar_orden(db, p["job_id"])
    audit(db, user["username"], "PROPOSAL_" + decision, "proposal", pid,
          f"job={p['job_id']} rev={p['revision']} sha={p['contenido_sha256'][:12]}")
    return 200, "ok", serializar(db, db.execute("SELECT * FROM proposals WHERE id = ?", (pid,)).fetchone())


# ── facturas ────────────────────────────────────────────────────────────────

def items_factura(db, jid):
    """Partidas vigentes sincronizadas por el técnico + repuestos USADOS (precio congelado). Sin anuladas."""
    items = []
    for jp in db.execute("SELECT jp.cantidad, jp.precio_snapshot, p.nombre, p.sku FROM job_parts jp JOIN parts p "
                         "ON p.id = jp.part_id WHERE jp.job_id = ? AND jp.estado = 'USADO'", (jid,)):
        items.append({"tipo": "MATERIAL", "descripcion": f"{jp['nombre']} ({jp['sku']})", "cantidad": jp["cantidad"],
                      "precio": jp["precio_snapshot"]})
    for w in db.execute("SELECT tipo, descripcion, cantidad, precio_unitario FROM job_work_items "
                        "WHERE job_id = ? AND anulado = 0 ORDER BY id", (jid,)):
        items.append({"tipo": w["tipo"], "descripcion": w["descripcion"] or w["tipo"], "cantidad": w["cantidad"],
                      "precio": w["precio_unitario"]})
    # repuestos del inventario del técnico (vigentes)
    for r in db.execute("SELECT sku, nombre, cantidad, precio_unitario FROM job_parts_used WHERE job_id = ? AND anulado = 0 "
                        "ORDER BY id", (jid,)):
        items.append({"tipo": "MATERIAL", "descripcion": r["nombre"] + (f" ({r['sku']})" if r["sku"] else ""),
                      "cantidad": r["cantidad"], "precio": r["precio_unitario"]})
    for i in items:
        i["importe"] = float(centavos(_d(i["cantidad"]) * _d(i["precio"])))
    return items


def totales(items, tasa, descuento=0.0):
    """Impuesto sobre (subtotal - descuento autorizado), igual que en la propuesta. El descuento nunca supera el subtotal."""
    subtotal = centavos(sum((_d(i["importe"]) for i in items), Decimal(0)))
    descuento = centavos(min(max(_d(descuento), Decimal(0)), subtotal))
    impuesto = centavos((subtotal - descuento) * _d(tasa))
    return float(subtotal), float(descuento), float(impuesto), float(subtotal - descuento + impuesto)


def factura_api(inv, db=None):
    out = {"id": inv["id"], "jobId": inv["job_id"], "numero": inv["numero"], "estado": inv["estado"],
           "subtotal": inv["subtotal"], "descuento": inv["descuento"], "impuesto": inv["impuesto"],
           "total": inv["total"], "items": json.loads(inv["items"]), "emitidaAt": inv["emitida_at"],
           "emitidaPor": inv["emitida_por"]}
    if db is not None:
        from . import campo as CA
        out["saldo"], out["pagado"] = CA.saldo(db, inv)
        out["tienePdf"] = CA.ultimo_pdf(db, inv["id"]) is not None
        ap = db.execute("SELECT revision, opcion_elegida, total_aceptado FROM proposals WHERE job_id = ? AND estado = "
                        "'APROBADA' ORDER BY id DESC LIMIT 1", (inv["job_id"],)).fetchone()
        # informativo: la factura se basa en el trabajo realizado; se muestra lo aprobado para comparar
        out["propuestaAprobada"] = ({"revision": ap[0], "opcion": ap[1], "total": ap[2]} if ap else None)
        out["comparacion"] = comparacion(db, inv["job_id"], inv["total"])
        out["variacionId"] = inv["variacion_id"]
        out["confirmacionMenorPor"] = inv["confirmacion_menor_por"]
    return out


def emitir_desde_tecnico(db, user, device, d):
    """Crea y emite la factura de una orden COMPLETADA asignada al técnico. Una por orden; reintentos idempotentes.
    Nunca la marca PAGADA; no aplica descuentos (no existe un descuento autorizado en el flujo)."""
    jid, cid = d.get("jobId"), str(d.get("clientInvoiceId") or "")
    base = {"jobId": jid, "clientInvoiceId": cid}
    jrow = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
    if jrow is None or jrow["technician_code"] != user["technician_code"]:
        return dict(base, status="REJECTED", reason="orden desconocida o no asignada a este técnico")
    if jrow["estado"] != "COMPLETADO":
        return dict(base, status="REJECTED", reason="solo se factura una orden COMPLETADA")
    items = items_factura(db, jid)
    if not items:
        return dict(base, status="REJECTED", reason="la orden no tiene trabajos para facturar")
    from . import campo as CA
    subtotal, descuento, impuesto, total = totales(items, current_app.config.get("IMPUESTO", 0.07),
                                                   CA.descuento_autorizado(db, jid))
    sha = hashlib.sha256(json.dumps({"items": items, "descuento": descuento}, sort_keys=True).encode()).hexdigest()
    prev = db.execute("SELECT * FROM invoices WHERE job_id = ?", (jid,)).fetchone()
    t = now_ms()
    if prev is not None and prev["estado"] != "BORRADOR":
        if prev["items_sha256"] == sha or prev["items_sha256"] is None and json.loads(prev["items"]) == items:
            return dict(base, status="ACCEPTED", factura=factura_api(prev, db))      # reintento: la misma factura
        return dict(base, status="REJECTED", factura=factura_api(prev, db),
                    reason="la orden ya tiene una factura emitida con otro contenido (no se factura dos veces)")
    bloqueo, extra = control_variacion(db, user, jid, items, total, sha, d.get("confirmaMenor") is True)
    if bloqueo is not None:
        codigo, texto, var = bloqueo
        return dict(base, status="REJECTED", codigo=codigo, reason=texto, variacion=var,
                    comparacion=comparacion(db, jid, total))
    if prev is None:
        iid = db.execute("INSERT INTO invoices(job_id, items, subtotal, impuesto, total, estado, created_at, updated_at)"
                         " VALUES (?,?,?,?,?, 'BORRADOR', ?, ?)",
                         (jid, json.dumps(items, ensure_ascii=False), subtotal, impuesto, total, t, t)).lastrowid
    else:
        iid = prev["id"]
    numero = f"LC-{jid}-{iid:04d}"
    db.execute("UPDATE invoices SET items = ?, subtotal = ?, impuesto = ?, total = ?, descuento = ?, estado = 'EMITIDA',"
               " numero = ?, emitida_por = ?, emitida_at = ?, origin_device = ?, client_invoice_id = ?, items_sha256 = ?,"
               " updated_at = ? WHERE id = ?",
               (json.dumps(items, ensure_ascii=False), subtotal, impuesto, total, descuento, numero, user["username"], t,
                device, cid, sha, t, iid))
    db.execute("UPDATE invoices SET aprobado_total = ?, diferencia = ?, variacion_id = ?, confirmacion_menor_por = ?, "
               "confirmacion_menor_at = ? WHERE id = ?", (extra.get("aprobado_total"), extra.get("diferencia"),
                                                          extra.get("variacion_id"), extra.get("confirmacion_menor_por"),
                                                          extra.get("confirmacion_menor_at"), iid))
    _tocar_orden(db, jid)
    audit(db, user["username"], "INVOICE_EMITTED", "invoice", iid, f"job={jid} total={total}", device)
    return dict(base, status="ACCEPTED", factura=factura_api(db.execute("SELECT * FROM invoices WHERE id = ?",
                                                                          (iid,)).fetchone(), db))


# ── factura frente a la propuesta aprobada ──────────────────────────────────
# Regla del propietario: la factura NUNCA se ajusta para igualar el presupuesto. Se compara con lo aprobado:
#   · mayor que lo aprobado (> 1 centavo)                → aprobación adicional firmada del cliente (MAYOR)
#   · menor en más de VARIACION_ALCANCE_PCT de lo aprobado → variación de alcance: aprobación revisada (ALCANCE)
#   · menor dentro del umbral                             → el técnico revisa y confirma (queda registrado)
VARIACION_ALCANCE_PCT = 15.0


def propuesta_aprobada(db, jid):
    return db.execute("SELECT * FROM proposals WHERE job_id = ? AND estado = 'APROBADA' ORDER BY id DESC LIMIT 1",
                      (jid,)).fetchone()


def clasificar(aprobado, total):
    dif = round(total - aprobado, 2)
    if dif > 0.01:
        return "MAYOR", dif
    if dif < -0.01:
        if aprobado > 0 and -dif / aprobado * 100.0 > VARIACION_ALCANCE_PCT:
            return "ALCANCE", dif
        return "MENOR", dif
    return "IGUAL", dif


def variacion_api(v):
    return {"id": v["id"], "jobId": v["job_id"], "tipo": v["tipo"], "aprobado": v["aprobado_total"],
            "facturado": v["factura_total"], "diferencia": v["diferencia"], "estado": v["estado"],
            "itemsSha256": v["items_sha256"], "items": json.loads(v["items"]), "firmante": v["firmante"],
            "decidedAt": v["decided_at"], "createdAt": v["created_at"]}


def variaciones_de_orden(db, jid):
    return [variacion_api(v) for v in db.execute("SELECT * FROM invoice_variances WHERE job_id = ? ORDER BY id", (jid,))]


def comparacion(db, jid, total):
    """Para mostrar (técnico, cliente, despacho): aprobado vs facturado y la diferencia, sin tocar importes."""
    ap = propuesta_aprobada(db, jid)
    if ap is None or ap["total_aceptado"] is None or total is None:
        return None
    tipo, dif = clasificar(ap["total_aceptado"], total)
    v = db.execute("SELECT estado FROM invoice_variances WHERE job_id = ? ORDER BY id DESC LIMIT 1", (jid,)).fetchone()
    return {"aprobado": ap["total_aceptado"], "facturado": total, "diferencia": dif, "tipo": tipo,
            "revision": ap["revision"], "variacionEstado": v[0] if v else None}


def control_variacion(db, user, jid, items, total, sha, confirma_menor):
    """None si se puede emitir; si no, (motivo_codigo, texto, variacion). No modifica importes."""
    ap = propuesta_aprobada(db, jid)
    if ap is None or ap["total_aceptado"] is None:
        return None, {}
    tipo, dif = clasificar(ap["total_aceptado"], total)
    extra = {"aprobado_total": ap["total_aceptado"], "diferencia": dif}
    if tipo == "IGUAL":
        return None, extra
    if tipo == "MENOR":
        if not confirma_menor:
            return ("CONFIRMAR_MENOR", f"el importe final es ${-dif:.2f} menor que lo aprobado (${ap['total_aceptado']:.2f}); "
                    "el técnico debe revisarlo y confirmarlo", None), extra
        extra.update(confirmacion_menor_por=user["username"], confirmacion_menor_at=now_ms())
        return None, extra
    # MAYOR o ALCANCE: aprobación del cliente sobre estas líneas exactas
    v = db.execute("SELECT * FROM invoice_variances WHERE job_id = ? AND items_sha256 = ? ORDER BY id DESC LIMIT 1",
                   (jid, sha)).fetchone()
    if v is not None and v["estado"] == "APROBADA":
        extra["variacion_id"] = v["id"]
        return None, extra
    if v is not None and v["estado"] == "RECHAZADA":
        return ("CLIENTE_RECHAZO", "el cliente rechazó la diferencia; revisa el trabajo facturado", variacion_api(v)), extra
    if v is None or v["estado"] != "PENDIENTE":
        t = now_ms()
        db.execute("UPDATE invoice_variances SET estado = 'SUPERADA' WHERE job_id = ? AND estado = 'PENDIENTE'", (jid,))
        vid = db.execute("INSERT INTO invoice_variances(job_id, proposal_id, tipo, aprobado_total, factura_total, diferencia,"
                         " items, items_sha256, estado, solicitada_por, created_at) VALUES (?,?,?,?,?,?,?,?, 'PENDIENTE', ?, ?)",
                         (jid, ap["id"], tipo, ap["total_aceptado"], total, dif, json.dumps(items, ensure_ascii=False), sha,
                          user["username"], t)).lastrowid
        audit(db, user["username"], "INVOICE_VARIANCE_REQUESTED", "job", jid, f"{tipo} {dif:+.2f}")
        _tocar_orden(db, jid)
        v = db.execute("SELECT * FROM invoice_variances WHERE id = ?", (vid,)).fetchone()
    texto = (f"la factura supera lo aprobado en ${dif:.2f}" if tipo == "MAYOR"
             else f"la factura es ${-dif:.2f} menor (más del {VARIACION_ALCANCE_PCT:.0f} %): cambio de alcance")
    return ("REQUIERE_APROBACION_CLIENTE", texto + "; requiere aprobación del cliente", variacion_api(v)), extra


def decidir_variacion(db, user, vid, d):
    v = db.execute("SELECT * FROM invoice_variances WHERE id = ?", (vid,)).fetchone()
    job = db.execute("SELECT customer_id FROM jobs WHERE id = ?", (v["job_id"],)).fetchone() if v else None
    if v is None or job is None or job["customer_id"] != user["customer_id"]:
        return 404, "no encontrada", None
    if v["estado"] != "PENDIENTE":
        return 409, "esta solicitud ya no está pendiente", None
    if d.get("itemsSha256") != v["items_sha256"]:
        return 409, "el detalle cambió desde que lo abriste; recarga", None
    decision = d.get("decision")
    if decision not in ("APROBADA", "RECHAZADA"):
        return 400, "decision debe ser APROBADA o RECHAZADA", None
    firmante, sha_firma = (d.get("firmante") or "").strip() or None, None
    if decision == "APROBADA":
        try:
            png = base64.b64decode(d.get("firmaPngBase64") or "", validate=True)
        except Exception:
            png = b""
        if not firmante or len(firmante) < 3 or png[:8] != b"\x89PNG\r\n\x1a\n":
            return 400, "nombre completo y firma requeridos", None
        sha_firma = hashlib.sha256(png).hexdigest()
        carpeta = os.path.join(current_app.config["UPLOADS"], str(v["job_id"]), "firmas")
        os.makedirs(carpeta, mode=0o700, exist_ok=True)
        with open(os.path.join(carpeta, f"variacion_{vid}.png"), "wb") as fh:
            fh.write(png)
    t = now_ms()
    db.execute("UPDATE invoice_variances SET estado = ?, firmante = ?, firma_sha256 = ?, decided_at = ? WHERE id = ?",
               (decision, firmante, sha_firma, t, vid))
    audit(db, user["username"], "INVOICE_VARIANCE_" + decision, "job", v["job_id"], f"{v['diferencia']:+.2f}")
    _tocar_orden(db, v["job_id"])
    return 200, "ok", variacion_api(db.execute("SELECT * FROM invoice_variances WHERE id = ?", (vid,)).fetchone())
