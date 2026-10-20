"""Trabajo de campo desde el teléfono (sync v2): firmas, repuestos usados, pagos reportados, PDF de factura,
descuentos autorizados por despacho."""
import base64
import hashlib
import json
import os

from flask import current_app

from .db import audit, next_counter, now_ms

METODOS = ("EFECTIVO", "CHEQUE", "TARJETA", "ZELLE", "TRANSFERENCIA", "OTRO")
TIPOS_FIRMA = ("CLIENTE_CONFORMIDAD", "TECNICO", "PROPUESTA_PRESENCIAL")


def _orden_del_tecnico(db, user, jid):
    j = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
    return j if j is not None and j["technician_code"] == user["technician_code"] else None


def _tocar(db, jid):
    db.execute("UPDATE jobs SET seq = ? WHERE id = ?", (next_counter(db, "job_seq"), jid))


def _png(b64, limite=2 * 1024 * 1024):
    try:
        datos = base64.b64decode(b64 or "", validate=True)
    except Exception:
        return None
    return datos if datos[:8] == b"\x89PNG\r\n\x1a\n" and len(datos) <= limite else None


# ── firmas ──────────────────────────────────────────────────────────────────

def firma_desde_dispositivo(db, user, device, d):
    lid, jid, tipo = d.get("localId"), d.get("jobId"), d.get("tipo")
    base = {"localId": lid, "jobId": jid}
    if not isinstance(lid, int) or tipo not in TIPOS_FIRMA:
        return dict(base, status="REJECTED", reason="firma inválida")
    if _orden_del_tecnico(db, user, jid) is None:
        return dict(base, status="REJECTED", reason="orden desconocida o no asignada a este técnico")
    prev = db.execute("SELECT * FROM job_signatures WHERE origin_device = ? AND local_id = ?", (device, lid)).fetchone()
    if prev is not None:                         # reintento: misma firma
        return dict(base, status="ACCEPTED", id=prev["id"], **_estado_propuesta(db, prev["proposal_id"]))
    firmante = (d.get("firmante") or "").strip()
    png = _png(d.get("pngBase64"))
    if len(firmante) < 3 or png is None:
        return dict(base, status="REJECTED", reason="falta el nombre del firmante o la imagen PNG de la firma")
    prop = None
    if tipo == "PROPUESTA_PRESENCIAL":
        prop = db.execute("SELECT * FROM proposals WHERE origin_device = ? AND local_id = ? ORDER BY revision DESC "
                          "LIMIT 1", (device, d.get("proposalLocalId"))).fetchone()
        if prop is None or prop["job_id"] != jid:
            return dict(base, status="REJECTED", reason="la propuesta no está en el servidor")
        from .proposals import estado_efectivo
        if estado_efectivo(db, prop) != "PENDIENTE":
            return dict(base, status="REJECTED", reason=f"la propuesta está {estado_efectivo(db, prop)}")
        opcion = next((o for o in json.loads(prop["opciones"]) if o["nivel"] == d.get("opcion")), None)
        total = d.get("total")
        if opcion is None or not isinstance(total, (int, float)) or abs(round(total, 2) - opcion["total"]) > 0.0151:
            # lo firmado no coincide con la revisión vigente → no autoriza nada
            return dict(base, status="REJECTED", reason="la opción o el total firmados no coinciden con la propuesta vigente")
    sha = hashlib.sha256(png).hexdigest()
    carpeta = os.path.join(current_app.config["UPLOADS"], str(jid), "firmas")
    os.makedirs(carpeta, mode=0o700, exist_ok=True)
    ruta = os.path.join(carpeta, f"firma_{tipo.lower()}_{sha[:16]}.png")
    with open(ruta, "wb") as fh:
        fh.write(png)
    t = now_ms()
    sid = db.execute("INSERT INTO job_signatures(job_id, origin_device, local_id, tipo, firmante, opcion, total, proposal_id,"
                     " png_sha256, path, capturada_por, firmada_at, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                     (jid, device, lid, tipo, firmante, d.get("opcion"), d.get("total"), prop["id"] if prop else None,
                      sha, ruta, user["username"], int(d.get("firmadaAt") or t), t)).lastrowid
    if prop is not None:
        db.execute("UPDATE proposals SET estado = 'APROBADA', opcion_elegida = ?, total_aceptado = ?, firmante = ?, "
                   "rol_firmante = 'CLIENTE_PRESENCIAL', firma_sha256 = ?, decided_at = ?, updated_at = ? WHERE id = ?",
                   (d.get("opcion"), opcion["total"], firmante, sha, t, t, prop["id"]))
        audit(db, user["username"], "PROPOSAL_APROBADA_PRESENCIAL", "proposal", prop["id"],
              f"job={jid} rev={prop['revision']} firma={sha[:12]}", device)
    audit(db, user["username"], "SIGNATURE_" + tipo, "job", jid, sha[:12], device)
    _tocar(db, jid)
    return dict(base, status="ACCEPTED", id=sid, **_estado_propuesta(db, prop["id"] if prop else None))


def _estado_propuesta(db, pid):
    if not pid:
        return {}
    from .proposals import serializar
    return {"propuesta": serializar(db, db.execute("SELECT * FROM proposals WHERE id = ?", (pid,)).fetchone(), tecnico=True)}


def firmas_de_orden(db, jid):
    return [{"id": r["id"], "tipo": r["tipo"], "firmante": r["firmante"], "opcion": r["opcion"], "total": r["total"],
             "capturadaPor": r["capturada_por"], "firmadaAt": r["firmada_at"], "sha256": r["png_sha256"]}
            for r in db.execute("SELECT * FROM job_signatures WHERE job_id = ? ORDER BY id", (jid,))]


# ── repuestos usados ────────────────────────────────────────────────────────

def repuesto_desde_dispositivo(db, user, device, d):
    lid, jid = d.get("localId"), d.get("jobId")
    base = {"localId": lid, "jobId": jid}
    if not isinstance(lid, int) or _orden_del_tecnico(db, user, jid) is None:
        return dict(base, status="REJECTED", reason="orden desconocida o no asignada a este técnico")
    cant, precio, nombre = d.get("cantidad"), d.get("precioUnitario"), (d.get("nombre") or "").strip()
    if not nombre or not isinstance(cant, (int, float)) or cant <= 0 or not isinstance(precio, (int, float)) or precio < 0:
        return dict(base, status="REJECTED", reason="nombre, cantidad > 0 y precio >= 0 requeridos")
    upd, anul, t = int(d.get("updatedAt") or 0), 1 if d.get("anulado") is True else 0, now_ms()
    prev = db.execute("SELECT * FROM job_parts_used WHERE origin_device = ? AND local_id = ?", (device, lid)).fetchone()
    if prev is None:
        db.execute("INSERT INTO job_parts_used(job_id, origin_device, local_id, sku, nombre, cantidad, precio_unitario, "
                   "anulado, client_updated_at, actor, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                   (jid, device, lid, d.get("sku"), nombre, cant, precio, anul, upd, user["username"], t, t))
    elif upd >= prev["client_updated_at"]:          # una versión vieja que llegue tarde no pisa la nueva
        db.execute("UPDATE job_parts_used SET sku=?, nombre=?, cantidad=?, precio_unitario=?, anulado=?, "
                   "client_updated_at=?, updated_at=? WHERE id=?", (d.get("sku"), nombre, cant, precio, anul, upd, t,
                                                                   prev["id"]))
    if anul:
        audit(db, user["username"], "PART_USED_ANULADO", "job", jid, f"{device}:{lid}", device)
    return dict(base, status="ACCEPTED")


# ── descuentos ──────────────────────────────────────────────────────────────

def descuento_autorizado(db, jid):
    return round(db.execute("SELECT COALESCE(SUM(monto), 0) FROM job_discounts WHERE job_id = ? AND anulado = 0",
                            (jid,)).fetchone()[0], 2)


def autorizar_descuento(db, user, jid, monto, motivo):
    if db.execute("SELECT 1 FROM jobs WHERE id = ?", (jid,)).fetchone() is None:
        return 404, "orden no encontrada"
    inv = db.execute("SELECT estado FROM invoices WHERE job_id = ?", (jid,)).fetchone()
    if inv is not None and inv["estado"] != "BORRADOR":
        return 409, "la factura ya fue emitida; el descuento debe autorizarse antes"
    if not isinstance(monto, (int, float)) or monto <= 0 or monto > 100000 or not (motivo or "").strip():
        return 400, "monto > 0 y motivo requeridos"
    db.execute("INSERT INTO job_discounts(job_id, monto, motivo, autorizado_por, created_at) VALUES (?,?,?,?,?)",
               (jid, round(monto, 2), motivo.strip(), user["username"], now_ms()))
    audit(db, user["username"], "DISCOUNT_AUTHORIZED", "job", jid, f"{monto:.2f} {motivo.strip()[:60]}")
    _tocar(db, jid)
    return 200, "ok"


# ── pagos ───────────────────────────────────────────────────────────────────

def pago_desde_dispositivo(db, user, device, d):
    lid, jid = d.get("localId"), d.get("jobId")
    base = {"localId": lid, "jobId": jid}
    if not isinstance(lid, int) or _orden_del_tecnico(db, user, jid) is None:
        return dict(base, status="REJECTED", reason="orden desconocida o no asignada a este técnico")
    prev = db.execute("SELECT * FROM payments WHERE origin_device = ? AND local_id = ?", (device, lid)).fetchone()
    if prev is not None:
        return dict(base, status="ACCEPTED", pago=pago_api(prev))
    inv = db.execute("SELECT * FROM invoices WHERE job_id = ?", (jid,)).fetchone()
    if inv is None or inv["estado"] not in ("EMITIDA", "PAGADA"):
        return dict(base, status="REJECTED", reason="no hay una factura emitida para esta orden")
    monto, metodo = d.get("monto"), d.get("metodo")
    if not isinstance(monto, (int, float)) or monto <= 0 or metodo not in METODOS:
        return dict(base, status="REJECTED", reason="monto > 0 y método válido requeridos")
    ya = db.execute("SELECT COALESCE(SUM(monto), 0) FROM payments WHERE invoice_id = ? AND estado != 'RECHAZADO'",
                    (inv["id"],)).fetchone()[0]
    if round(ya + monto, 2) > inv["total"] + 0.01:
        return dict(base, status="REJECTED", reason="el pago supera el saldo de la factura")
    pid = db.execute("INSERT INTO payments(job_id, invoice_id, origin_device, local_id, monto, metodo, referencia, estado,"
                     " reportado_por, reportado_at) VALUES (?,?,?,?,?,?,?, 'REPORTADO', ?, ?)",
                     (jid, inv["id"], device, lid, round(monto, 2), metodo, (d.get("referencia") or "").strip() or None,
                      user["username"], now_ms())).lastrowid
    audit(db, user["username"], "PAYMENT_REPORTED", "payment", pid, f"job={jid} {monto:.2f} {metodo}", device)
    _tocar(db, jid)
    return dict(base, status="ACCEPTED", pago=pago_api(db.execute("SELECT * FROM payments WHERE id = ?", (pid,)).fetchone()))


def pago_api(p):
    return {"id": p["id"], "localId": p["local_id"], "jobId": p["job_id"], "monto": p["monto"], "metodo": p["metodo"],
            "estado": p["estado"], "reportadoAt": p["reportado_at"], "revisadoPor": p["revisado_por"],
            "revisadoAt": p["revisado_at"], "motivo": p["motivo"]}


def pagos_de_orden(db, jid):
    return [pago_api(p) for p in db.execute("SELECT * FROM payments WHERE job_id = ? ORDER BY id", (jid,))]


def revisar_pago(db, user, pid, decision, motivo=None):
    """Despacho confirma (comprobado) o rechaza un pago reportado. La factura pasa a PAGADA solo cuando lo
    confirmado cubre el total."""
    p = db.execute("SELECT * FROM payments WHERE id = ?", (pid,)).fetchone()
    if p is None:
        return 404, "pago no encontrado"
    if p["estado"] != "REPORTADO":
        return 409, "este pago ya fue revisado"
    if decision not in ("CONFIRMADO", "RECHAZADO"):
        return 400, "decision: CONFIRMADO o RECHAZADO"
    if decision == "RECHAZADO" and not (motivo or "").strip():
        return 400, "indica el motivo del rechazo"
    t = now_ms()
    db.execute("UPDATE payments SET estado = ?, revisado_por = ?, revisado_at = ?, motivo = ? WHERE id = ?",
               (decision, user["username"], t, (motivo or "").strip() or None, pid))
    inv = db.execute("SELECT * FROM invoices WHERE id = ?", (p["invoice_id"],)).fetchone()
    pagado = db.execute("SELECT COALESCE(SUM(monto), 0) FROM payments WHERE invoice_id = ? AND estado = 'CONFIRMADO'",
                        (inv["id"],)).fetchone()[0]
    if inv["estado"] == "EMITIDA" and pagado + 0.01 >= inv["total"]:
        db.execute("UPDATE invoices SET estado = 'PAGADA', updated_at = ? WHERE id = ?", (t, inv["id"]))
        audit(db, user["username"], "INVOICE_PAID", "invoice", inv["id"], f"confirmado={pagado:.2f}")
    audit(db, user["username"], "PAYMENT_" + decision, "payment", pid, (motivo or "")[:60])
    _tocar(db, p["job_id"])
    return 200, "ok"


def saldo(db, inv):
    pagado = db.execute("SELECT COALESCE(SUM(monto), 0) FROM payments WHERE invoice_id = ? AND estado = 'CONFIRMADO'",
                        (inv["id"],)).fetchone()[0]
    return round(inv["total"] - pagado, 2), round(pagado, 2)


# ── PDF de la factura ───────────────────────────────────────────────────────

def documento_desde_dispositivo(db, user, device, d):
    jid = d.get("jobId")
    base = {"jobId": jid, "numero": d.get("numero")}
    if _orden_del_tecnico(db, user, jid) is None:
        return dict(base, status="REJECTED", reason="orden desconocida o no asignada a este técnico")
    inv = db.execute("SELECT * FROM invoices WHERE job_id = ?", (jid,)).fetchone()
    if inv is None or inv["estado"] == "BORRADOR" or inv["numero"] != d.get("numero"):
        return dict(base, status="REJECTED", reason="el PDF no corresponde a la factura emitida")
    try:
        pdf = base64.b64decode(d.get("pdfBase64") or "", validate=True)
    except Exception:
        pdf = b""
    if pdf[:5] != b"%PDF-" or len(pdf) > 5 * 1024 * 1024:
        return dict(base, status="REJECTED", reason="no es un PDF válido (máx. 5 MB)")
    sha = hashlib.sha256(pdf).hexdigest()
    if db.execute("SELECT 1 FROM invoice_documents WHERE invoice_id = ? AND sha256 = ?", (inv["id"], sha)).fetchone():
        return dict(base, status="ACCEPTED", sha256=sha)          # reintento
    carpeta = os.path.join(current_app.config["UPLOADS"], str(jid), "facturas")
    os.makedirs(carpeta, mode=0o700, exist_ok=True)
    ruta = os.path.join(carpeta, f"{inv['numero']}_{sha[:12]}.pdf")
    with open(ruta, "wb") as fh:
        fh.write(pdf)
    db.execute("INSERT INTO invoice_documents(invoice_id, sha256, path, size_bytes, uploaded_by, created_at) "
               "VALUES (?,?,?,?,?,?)", (inv["id"], sha, ruta, len(pdf), user["username"], now_ms()))
    audit(db, user["username"], "INVOICE_PDF", "invoice", inv["id"], sha[:12], device)
    _tocar(db, jid)
    return dict(base, status="ACCEPTED", sha256=sha)


def ultimo_pdf(db, invoice_id):
    return db.execute("SELECT * FROM invoice_documents WHERE invoice_id = ? ORDER BY id DESC LIMIT 1",
                      (invoice_id,)).fetchone()
