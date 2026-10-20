"""Centro de mando (despacho), detalle de órdenes y operaciones: fotos, notas, aprobaciones,
repuestos, facturas y reportes."""
import base64
import hashlib
import json
import os
import re

from flask import Blueprint, current_app, g, jsonify, request, send_file

from . import jobs as J
from .db import audit, get_db, now_ms
from .security import puede_ver_acceso, requiere

bp = Blueprint("ops", __name__, url_prefix="/api/v2")

# Transiciones permitidas (MASTER_FLOW §10, nombres de la app). Despacho/admin pueden además
# reabrir o cancelar; el técnico solo avanza el trabajo.
TRANSICIONES = {
    "PENDIENTE": {"EN RUTA", "CANCELADO"},
    "EN RUTA": {"LLEGUÉ", "PENDIENTE", "PAUSADO"},
    "LLEGUÉ": {"EN REPARACIÓN", "ESPERANDO APROBACIÓN", "PAUSADO"},
    "EN REPARACIÓN": {"ESPERANDO APROBACIÓN", "PART HOLD", "PAUSADO", "COMPLETADO"},
    "ESPERANDO APROBACIÓN": {"EN REPARACIÓN", "PART HOLD", "PAUSADO", "CANCELADO"},
    "PART HOLD": {"EN REPARACIÓN", "PENDIENTE", "PAUSADO"},
    "PAUSADO": {"EN RUTA", "LLEGUÉ", "EN REPARACIÓN", "PENDIENTE"},
    "COMPLETADO": set(),
    "CANCELADO": set(),
}
SOLO_DESPACHO = {("COMPLETADO", "EN REPARACIÓN"), ("CANCELADO", "PENDIENTE")}
MIME_OK = {"image/jpeg": ".jpg", "image/png": ".png"}
MAX_FOTO = 5 * 1024 * 1024


def _json():
    d = request.get_json(silent=True)
    return d if isinstance(d, dict) else {}


def _err(msg, code=400, **kw):
    return jsonify(error=msg, **kw), code


def transicion_valida(actual, nuevo, rol):
    if actual == nuevo:
        return True
    if nuevo in TRANSICIONES.get(actual, set()):
        return True
    return rol in ("ADMIN", "DISPATCHER") and (actual, nuevo) in SOLO_DESPACHO


def camino_de_estados(actual, nuevo, rol):
    """Camino más corto de estados permitidos (BFS) de `actual` a `nuevo`, sin incluir `actual`.
    Sirve para la sincronización: sin conexión el teléfono puede haber avanzado varios pasos y solo
    envía el último. None si no hay camino válido."""
    if actual == nuevo:
        return []
    from collections import deque
    previo = {actual: None}
    cola = deque([actual])
    while cola:
        e = cola.popleft()
        siguientes = set(TRANSICIONES.get(e, set()))
        if rol in ("ADMIN", "DISPATCHER"):
            siguientes |= {b for (a, b) in SOLO_DESPACHO if a == e}
        for s in sorted(siguientes):
            if s in previo:
                continue
            previo[s] = e
            if s == nuevo:
                camino = [s]
                while previo[camino[-1]] != actual:
                    camino.append(previo[camino[-1]])
                return list(reversed(camino))
            cola.append(s)
    return None


def job_visible(db, jid, user):
    """La orden si el usuario puede verla (despacho todo; técnico la suya; cliente la suya)."""
    row = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
    if row is None:
        return None
    r = user["role"]
    if r in ("ADMIN", "DISPATCHER"):
        return row
    if r == "TECHNICIAN" and row["technician_code"] == user["technician_code"]:
        return row
    if r == "CUSTOMER" and row["customer_id"] is not None and row["customer_id"] == user["customer_id"]:
        return row
    return None


# ── despacho ────────────────────────────────────────────────────────────────

@bp.get("/dispatch/board")
@requiere("DISPATCHER")
def tablero():
    db = get_db()
    dias = max(1, min(int(request.args.get("dias", 7)), 366))
    hasta = request.args.get("hasta", type=int)
    grupos = {k: [] for k in ("EN_CURSO", "HOY", "ATRASADOS", "PROXIMOS", "SIN_FECHA", "COMPLETADOS", "CANCELADOS")}
    fuera = 0
    for row in db.execute("SELECT * FROM jobs ORDER BY COALESCE(programado_para, created_at)"):
        cat = J.categoria_agenda(row)
        if cat == "PROXIMOS" and not J.dentro_de_proximos(row, dias, hasta):
            fuera += 1   # no se oculta: se informa y se consulta con ?dias= o ?hasta=
            continue
        grupos[cat].append(J.a_api(row, g.user, db))
    return jsonify(grupos=grupos, conteos={k: len(v) for k, v in grupos.items()},
                   proximosFueraDeRango=fuera, diasProximos=dias)


@bp.post("/dispatch/jobs")
@requiere("DISPATCHER")
def crear_orden():
    d = _json()
    db = get_db()
    errs = J.validar(d, creando=True)
    if d.get("descripcion") is None or len(d.get("descripcion", "").strip()) < 10:
        errs.append("descripción muy corta (mín. 10 caracteres)")
    extra = {"estado_dispatch": "QUEUE", "prioridad": d.get("prioridad") or "NORMAL"}
    if d.get("customerId") is not None:
        c = db.execute("SELECT id FROM customers WHERE id = ?", (d["customerId"],)).fetchone()
        if c is None:
            errs.append("cliente no encontrado")
        extra["customer_id"] = d["customerId"]
    if d.get("propertyId") is not None:
        extra["property_id"] = d["propertyId"]
    if errs:
        return _err("; ".join(errs))
    d.setdefault("estado", "PENDIENTE")
    jid = J.crear(db, d, "DISPATCH", g.user["username"], "control", extra=extra)
    db.commit()
    return jsonify(J.a_api(db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone(), g.user)), 201


@bp.post("/dispatch/jobs/<int:jid>/schedule")
@requiere("DISPATCHER")
def programar(jid):
    from datetime import datetime
    from zoneinfo import ZoneInfo
    d = _json()
    db = get_db()
    row = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
    if row is None:
        return _err("orden no encontrada", 404)
    pp = d.get("programadoPara")
    if not isinstance(pp, int):
        return _err("programadoPara (epoch ms) requerido")
    zona = d.get("zonaHoraria") or row["zona_horaria"] or current_app.config["ZONA_HORARIA"]
    try:
        hora = datetime.fromtimestamp(pp / 1000, ZoneInfo(zona)).strftime("%-I:%M %p")
    except Exception:
        return _err("zona horaria inválida")
    if d.get("version") is not None and d["version"] != row["version"]:
        return _err("la orden cambió; recarga antes de programar", 409, version=row["version"])
    status, v, conf = J.aplicar(db, row, {"programadoPara": pp, "zonaHoraria": zona, "hora": hora},
                                g.user["username"], "control")
    if status == "CONFLICT":
        return _err("la orden cambió; recarga antes de programar", 409, conflictFields=conf)
    db.commit()
    return jsonify(J.a_api(db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone(), g.user))


@bp.post("/dispatch/jobs/<int:jid>/assign")
@requiere("DISPATCHER")
def asignar(jid):
    d = _json()
    db = get_db()
    row = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
    if row is None:
        return _err("orden no encontrada", 404)
    code = d.get("technicianId")
    if code is not None and not db.execute("SELECT 1 FROM users WHERE technician_code = ? AND role='TECHNICIAN' "
                                           "AND active = 1", (code,)).fetchone():
        return _err("técnico no encontrado o inactivo", 404)
    if d.get("version") is not None and d["version"] != row["version"]:
        return _err("la orden cambió; recarga antes de asignar", 409, version=row["version"])
    status, v, conf = J.aplicar(db, row, {"technicianId": code,
                                          "estadoDispatch": "ASSIGNED" if code else "QUEUE"},
                                g.user["username"], "control")
    if status == "CONFLICT":
        return _err("la orden cambió; recarga antes de asignar", 409, conflictFields=conf)
    db.commit()
    return jsonify(J.a_api(db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone(), g.user))


EDITABLES_DESPACHO = {"descripcion", "notas", "instrucciones", "mascota", "contactoRespaldo", "telefono",
                      "direccion", "equipo", "categoria", "prioridad", "tipoAcceso", "codigoAcceso", "ubicacionLlave",
                      "notasSeguridad"}


@bp.patch("/dispatch/jobs/<int:jid>")
@requiere("DISPATCHER")
def editar_orden(jid):
    """Edición de despacho con bloqueo optimista estricto (version obligatoria)."""
    d = _json()
    db = get_db()
    row = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
    if row is None:
        return _err("orden no encontrada", 404)
    if d.get("version") != row["version"]:
        return _err("la orden cambió; recarga", 409, version=row["version"])
    cambios = {k: v for k, v in (d.get("campos") or {}).items() if k in EDITABLES_DESPACHO}
    if not cambios:
        return _err("ningún campo editable")
    errs = J.validar(cambios, creando=False)
    if errs:
        return _err("; ".join(errs))
    J.aplicar(db, row, cambios, g.user["username"], "control")
    db.commit()
    return jsonify(J.a_api(db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone(), g.user))


@bp.get("/dispatch/conflicts")
@requiere("DISPATCHER")
def listar_conflictos():
    db = get_db()
    estado = request.args.get("estado", "PENDIENTE")
    rows = db.execute("SELECT * FROM job_conflicts WHERE estado = ? ORDER BY id", (estado,)).fetchall()
    return jsonify(conflicts=[J.conflicto_api(c, True) for c in rows])


@bp.post("/dispatch/conflicts/<int:cid>/resolve")
@requiere("DISPATCHER")
def resolver_conflicto(cid):
    """Despacho decide: SERVIDOR (se queda lo actual), TECNICO (se aplica lo del teléfono) o COMBINAR (texto
    nuevo). Ambos valores originales quedan guardados en el conflicto (recuperables). Bloqueo optimista."""
    d = _json()
    db = get_db()
    code, msg, extra = J.resolver_conflicto(db, cid, d.get("decision"), d.get("valor"), d.get("version"),
                                            g.user["username"])
    if code != 200:
        return _err(msg, code, **extra)
    db.commit()
    c = db.execute("SELECT * FROM job_conflicts WHERE id = ?", (cid,)).fetchone()
    return jsonify(conflicto=J.conflicto_api(c, True),
                   orden=J.a_api(db.execute("SELECT * FROM jobs WHERE id = ?", (c["job_id"],)).fetchone(), g.user, db))


@bp.get("/dispatch/technicians")
@requiere("DISPATCHER")
def tecnicos():
    """Activos (para asignar). ?todos=1 incluye los desactivados."""
    db = get_db()
    out = []
    filtro = "" if request.args.get("todos") == "1" else " AND active=1"
    for u in db.execute("SELECT id, username, display_name, technician_code, active, specialties FROM users "
                        "WHERE role='TECHNICIAN'" + filtro + " ORDER BY technician_code"):
        carga = db.execute("SELECT COUNT(*) FROM jobs WHERE technician_code = ? AND estado NOT IN "
                           "('COMPLETADO','CANCELADO')", (u["technician_code"],)).fetchone()[0]
        disp = [dict(r) for r in db.execute("SELECT dia, desde, hasta FROM technician_availability "
                                            "WHERE technician_code = ? ORDER BY dia, desde", (u["technician_code"],))]
        out.append({"nombre": u["display_name"], "technicianId": u["technician_code"], "abiertas": carga,
                    "disponibilidad": disp, "id": u["id"], "username": u["username"], "active": bool(u["active"]),
                    "specialties": u["specialties"] or ""})
    return jsonify(technicians=out)


@bp.post("/dispatch/technicians")
@requiere("ADMIN")
def crear_tecnico():
    """Alta de técnico (solo ADMIN). Rol forzado a TECHNICIAN; debe cambiar la contraseña al entrar."""
    from . import tecnicos as T
    db = get_db()
    code, err, tec, clave = T.crear(db, g.user["username"], _json())
    if err:
        return _err(err, code)
    db.commit()
    # la contraseña inicial solo viaja en esta respuesta (no se guarda en claro ni en logs)
    return jsonify(id=tec["id"], username=tec["username"], technician_code=tec["technicianId"],
                   must_change_password=1, tecnico=tec, password_inicial=clave), 201


@bp.get("/dispatch/technicians/<int:uid>")
@requiere("DISPATCHER")
def ver_tecnico(uid):
    from . import tecnicos as T
    tec = T.obtener(get_db(), uid)
    return jsonify(tecnico=tec) if tec else _err("técnico no encontrado", 404)


@bp.patch("/dispatch/technicians/<int:uid>")
@requiere("ADMIN")
def editar_tecnico(uid):
    """Nombre, especialidades, activar/desactivar y restablecer contraseña (solo ADMIN)."""
    from . import tecnicos as T
    db = get_db()
    code, err, tec, clave = T.actualizar(db, g.user["username"], uid, _json())
    if err:
        return _err(err, code)
    db.commit()
    out = {"tecnico": tec}
    if clave:
        out["password_inicial"] = clave
    return jsonify(out)


@bp.post("/dispatch/availability")
@requiere("DISPATCHER")
def disponibilidad():
    d = _json()
    if not re.match(r"^\d{4}-\d{2}-\d{2}$", d.get("dia") or "") or not re.match(r"^\d{2}:\d{2}$", d.get("desde") or "") \
            or not re.match(r"^\d{2}:\d{2}$", d.get("hasta") or "") or d["desde"] >= d["hasta"]:
        return _err("dia YYYY-MM-DD y desde < hasta (HH:MM) requeridos")
    db = get_db()
    db.execute("INSERT OR REPLACE INTO technician_availability(technician_code, dia, desde, hasta) VALUES (?,?,?,?)",
               (d.get("technicianId"), d["dia"], d["desde"], d["hasta"]))
    db.commit()
    return jsonify(ok=True), 201


# ── detalle y estado ────────────────────────────────────────────────────────

@bp.get("/jobs/<int:jid>")
@requiere()
def detalle(jid):
    db = get_db()
    row = job_visible(db, jid, g.user)
    if row is None:
        return _err("orden no encontrada", 404)
    out = J.a_api(row, g.user, db)
    if g.user["role"] != "CUSTOMER":
        ver = puede_ver_acceso(g.user, row)
        out["conflictos"] = [J.conflicto_api(c, ver) for c in db.execute(
            "SELECT * FROM job_conflicts WHERE job_id = ? ORDER BY id", (jid,))]
        out["historial"] = [dict(h) for h in db.execute(
            "SELECT version, field, old_value, new_value, actor, source, ts FROM job_history WHERE job_id = ? ORDER BY id",
            (jid,))]
        out["eventos"] = [dict(e, payload=json.loads(e["payload"])) for e in db.execute(
            "SELECT id, tipo, payload, actor, ts FROM job_events WHERE job_id = ? ORDER BY id", (jid,))]
    return jsonify(out)


@bp.post("/jobs/<int:jid>/status")
@requiere("TECHNICIAN", "DISPATCHER")
def cambiar_estado(jid):
    d = _json()
    db = get_db()
    row = job_visible(db, jid, g.user)
    if row is None:
        return _err("orden no encontrada", 404)
    nuevo = d.get("estado")
    if nuevo not in J.ESTADOS:
        return _err("estado desconocido")
    if not transicion_valida(row["estado"], nuevo, g.user["role"]):
        return _err(f"transición no permitida: {row['estado']} → {nuevo}", 409)
    if d.get("version") is not None and d["version"] != row["version"]:
        return _err("la orden cambió; recarga", 409, version=row["version"])
    status, v, conf = J.aplicar(db, row, {"estado": nuevo}, g.user["username"], "api")
    if status == "CONFLICT":
        return _err("la orden cambió; recarga", 409, conflictFields=conf)
    db.commit()
    return jsonify(id=jid, estado=nuevo, version=v)


# ── fotos ───────────────────────────────────────────────────────────────────

def guardar_foto(db, jid, contenido, mime, actor, categoria=None, device=None, local_id=None, nombre=None):
    """Valida y guarda una foto. Idempotente por (dispositivo, id local). Devuelve (id, error)."""
    if mime not in MIME_OK:
        return None, "tipo de imagen no permitido"
    if not contenido or len(contenido) > MAX_FOTO:
        return None, "imagen vacía o mayor de 5 MB"
    firma_ok = contenido[:3] == b"\xff\xd8\xff" if mime == "image/jpeg" else contenido[:8] == b"\x89PNG\r\n\x1a\n"
    if not firma_ok:
        return None, "el contenido no es una imagen válida"
    if device and local_id is not None:
        prev = db.execute("SELECT id FROM photos WHERE origin_device = ? AND origin_local_id = ?",
                          (device, local_id)).fetchone()
        if prev:
            return prev[0], None
    sha = hashlib.sha256(contenido).hexdigest()
    carpeta = os.path.join(current_app.config["UPLOADS"], str(jid))
    os.makedirs(carpeta, exist_ok=True)
    ruta = os.path.join(carpeta, sha[:16] + MIME_OK[mime])
    with open(ruta, "wb") as f:
        f.write(contenido)
    pid = db.execute("INSERT INTO photos(job_id, uploaded_by, origin_device, origin_local_id, categoria, filename, mime, "
                     "size_bytes, sha256, path, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                     (jid, actor, device, local_id, categoria, os.path.basename(nombre or ruta), mime, len(contenido),
                      sha, ruta, now_ms())).lastrowid
    audit(db, actor, "UPLOAD", "photo", pid, f"job={jid}", device)
    return pid, None


@bp.post("/jobs/<int:jid>/photos")
@requiere("CUSTOMER", "TECHNICIAN", "DISPATCHER")
def subir_foto(jid):
    db = get_db()
    if job_visible(db, jid, g.user) is None:
        return _err("orden no encontrada", 404)
    f = request.files.get("foto")
    if f is None:
        return _err("falta el archivo 'foto'")
    # clientPhotoId (opcional): la app genera un número por foto y lo repite en los reintentos → sin duplicados
    cpid = request.form.get("clientPhotoId", "")
    origen = (f"user:{g.user['id']}", int(cpid)) if cpid.isdigit() and 0 < int(cpid) < 2**62 else (None, None)
    pid, err = guardar_foto(db, jid, f.read(), f.mimetype, g.user["username"], request.form.get("categoria"),
                            device=origen[0], local_id=origen[1], nombre=f.filename)
    if err:
        return _err(err)
    db.commit()
    return jsonify(id=pid), 201


@bp.get("/photos/<int:pid>")
@requiere()
def ver_foto(pid):
    db = get_db()
    p = db.execute("SELECT * FROM photos WHERE id = ?", (pid,)).fetchone()
    if p is None or job_visible(db, p["job_id"], g.user) is None:
        return _err("foto no encontrada", 404)
    return send_file(p["path"], mimetype=p["mime"])


# ── notas / mediciones / diagnóstico ────────────────────────────────────────

def guardar_evento(db, jid, tipo, payload, actor, client_event_id=None):
    if tipo not in ("NOTA", "MEDICION", "DIAGNOSTICO"):
        return None, "tipo de evento inválido"
    if not isinstance(payload, dict) or not payload:
        return None, "payload vacío"
    if tipo == "MEDICION":
        if "nombre" not in payload or "valor" not in payload:
            return None, "una medición necesita nombre y valor"
        try:
            float(payload["valor"])
        except (TypeError, ValueError):
            return None, "el valor de la medición debe ser numérico"
    if client_event_id:
        prev = db.execute("SELECT id FROM job_events WHERE job_id = ? AND client_event_id = ?",
                          (jid, client_event_id)).fetchone()
        if prev:
            return prev[0], None
    eid = db.execute("INSERT INTO job_events(job_id, tipo, payload, actor, client_event_id, ts) VALUES (?,?,?,?,?,?)",
                     (jid, tipo, json.dumps(payload, ensure_ascii=False), actor, client_event_id, now_ms())).lastrowid
    return eid, None


@bp.post("/jobs/<int:jid>/events")
@requiere("TECHNICIAN", "DISPATCHER")
def crear_evento(jid):
    d = _json()
    db = get_db()
    if job_visible(db, jid, g.user) is None:
        return _err("orden no encontrada", 404)
    eid, err = guardar_evento(db, jid, d.get("tipo"), d.get("payload"), g.user["username"], d.get("clientEventId"))
    if err:
        return _err(err)
    db.commit()
    return jsonify(id=eid), 201


# ── aprobaciones y firmas ───────────────────────────────────────────────────

@bp.post("/jobs/<int:jid>/approvals")
@requiere("TECHNICIAN", "DISPATCHER")
def pedir_aprobacion(jid):
    d = _json()
    db = get_db()
    if job_visible(db, jid, g.user) is None:
        return _err("orden no encontrada", 404)
    if d.get("tipo") not in ("PROPUESTA", "AUTORIZACION", "CIERRE", "FACTURA"):
        return _err("tipo de aprobación inválido")
    monto = d.get("monto")
    if monto is not None and (not isinstance(monto, (int, float)) or monto < 0):
        return _err("monto inválido")
    aid = db.execute("INSERT INTO approvals(job_id, tipo, descripcion, monto, estado, solicitada_por, created_at) "
                     "VALUES (?,?,?,?,?,?,?)", (jid, d["tipo"], d.get("descripcion"), monto, "PENDIENTE",
                                                g.user["username"], now_ms())).lastrowid
    audit(db, g.user["username"], "REQUEST_APPROVAL", "approval", aid, f"job={jid}")
    db.commit()
    return jsonify(id=aid, estado="PENDIENTE"), 201


@bp.post("/approvals/<int:aid>/decide")
@requiere("CUSTOMER")
def decidir(aid):
    """El cliente aprueba o rechaza y firma (PNG en base64; se guarda su huella SHA-256)."""
    d = _json()
    db = get_db()
    a = db.execute("SELECT * FROM approvals WHERE id = ?", (aid,)).fetchone()
    if a is None or job_visible(db, a["job_id"], g.user) is None:
        return _err("aprobación no encontrada", 404)
    if a["estado"] != "PENDIENTE":
        return _err("esta aprobación ya fue decidida", 409)
    decision = d.get("decision")
    if decision not in ("APROBADA", "RECHAZADA"):
        return _err("decision debe ser APROBADA o RECHAZADA")
    firmante = (d.get("firmante") or "").strip()
    if decision == "APROBADA" and len(firmante) < 3:
        return _err("nombre del firmante requerido")
    sha = None
    if d.get("firmaPngBase64"):
        try:
            png = base64.b64decode(d["firmaPngBase64"], validate=True)
        except Exception:
            return _err("firma inválida")
        if png[:8] != b"\x89PNG\r\n\x1a\n":
            return _err("la firma debe ser PNG")
        sha = hashlib.sha256(png).hexdigest()
        carpeta = os.path.join(current_app.config["UPLOADS"], str(a["job_id"]), "firmas")
        os.makedirs(carpeta, exist_ok=True)
        with open(os.path.join(carpeta, f"aprobacion_{aid}.png"), "wb") as f:
            f.write(png)
    elif decision == "APROBADA":
        return _err("la aprobación requiere firma")
    db.execute("UPDATE approvals SET estado = ?, firmante = ?, rol_firmante = 'CLIENTE', firma_sha256 = ?, "
               "decided_at = ? WHERE id = ?", (decision, firmante or None, sha, now_ms(), aid))
    audit(db, g.user["username"], decision, "approval", aid, f"job={a['job_id']}")
    db.commit()
    return jsonify(id=aid, estado=decision, firmaSha256=sha)


# ── repuestos ───────────────────────────────────────────────────────────────

ESTADOS_REPUESTO = {"SOLICITADO": {"APROBADO", "CANCELADO"}, "APROBADO": {"PEDIDO", "USADO", "CANCELADO"},
                    "PEDIDO": {"RECIBIDO", "CANCELADO"}, "RECIBIDO": {"USADO"}, "USADO": set(), "CANCELADO": set()}


@bp.get("/parts")
@requiere("TECHNICIAN", "DISPATCHER")
def repuestos():
    return jsonify(parts=[dict(p) for p in get_db().execute("SELECT * FROM parts ORDER BY nombre")])


@bp.post("/jobs/<int:jid>/parts")
@requiere("TECHNICIAN", "DISPATCHER")
def pedir_repuesto(jid):
    d = _json()
    db = get_db()
    if job_visible(db, jid, g.user) is None:
        return _err("orden no encontrada", 404)
    p = db.execute("SELECT * FROM parts WHERE sku = ?", (d.get("sku"),)).fetchone()
    if p is None:
        return _err("repuesto no encontrado", 404)
    cant = d.get("cantidad")
    if not isinstance(cant, int) or cant <= 0:
        return _err("cantidad inválida")
    t = now_ms()
    # precio congelado al pedir (MASTER_FLOW §23: snapshots)
    rid = db.execute("INSERT INTO job_parts(job_id, part_id, cantidad, estado, precio_snapshot, created_at, updated_at) "
                     "VALUES (?,?,?,?,?,?,?)", (jid, p["id"], cant, "SOLICITADO", p["precio"], t, t)).lastrowid
    audit(db, g.user["username"], "REQUEST_PART", "job_part", rid, f"job={jid} sku={p['sku']} x{cant}")
    db.commit()
    return jsonify(id=rid, estado="SOLICITADO", precio=p["precio"]), 201


@bp.post("/job_parts/<int:rid>/status")
@requiere("TECHNICIAN", "DISPATCHER")
def estado_repuesto(rid):
    d = _json()
    db = get_db()
    r = db.execute("SELECT * FROM job_parts WHERE id = ?", (rid,)).fetchone()
    if r is None or job_visible(db, r["job_id"], g.user) is None:
        return _err("repuesto no encontrado", 404)
    nuevo = d.get("estado")
    if nuevo not in ESTADOS_REPUESTO.get(r["estado"], set()):
        return _err(f"transición no permitida: {r['estado']} → {nuevo}", 409)
    if nuevo in ("APROBADO", "PEDIDO", "RECIBIDO") and g.user["role"] == "TECHNICIAN":
        return _err("solo despacho aprueba, pide o recibe repuestos", 403)
    if nuevo == "USADO":
        cur = db.execute("UPDATE parts SET stock = stock - ? WHERE id = ? AND stock >= ?",
                         (r["cantidad"], r["part_id"], r["cantidad"]))
        if cur.rowcount == 0:
            return _err("stock insuficiente", 409)
    db.execute("UPDATE job_parts SET estado = ?, updated_at = ? WHERE id = ?", (nuevo, now_ms(), rid))
    audit(db, g.user["username"], "PART_" + nuevo, "job_part", rid)
    db.commit()
    return jsonify(id=rid, estado=nuevo)


# ── factura y reporte ───────────────────────────────────────────────────────

@bp.post("/jobs/<int:jid>/invoice")
@requiere("TECHNICIAN", "DISPATCHER")
def facturar(jid):
    """Factura en BORRADOR: repuestos USADOS (precio congelado) + mano de obra indicada."""
    d = _json()
    db = get_db()
    row = job_visible(db, jid, g.user)
    if row is None:
        return _err("orden no encontrada", 404)
    from . import campo as CA
    from . import proposals as PR
    items = PR.items_factura(db, jid)     # mismas líneas que la factura del técnico (sin anuladas)
    for m in d.get("manoDeObra") or []:
        if not isinstance(m, dict) or not isinstance(m.get("horas"), (int, float)) or m["horas"] <= 0 \
                or not isinstance(m.get("tarifa"), (int, float)) or m["tarifa"] < 0:
            return _err("mano de obra: horas > 0 y tarifa >= 0")
        items.append({"tipo": "LABOR", "descripcion": m.get("descripcion") or "Mano de obra",
                      "cantidad": m["horas"], "precio": m["tarifa"], "importe": round(m["horas"] * m["tarifa"], 2)})
    if not items:
        return _err("la factura no tiene líneas")
    subtotal, descuento, impuesto, total = PR.totales(items, current_app.config.get("IMPUESTO", 0.07),
                                                      CA.descuento_autorizado(db, jid))
    t = now_ms()
    prev = db.execute("SELECT id, estado FROM invoices WHERE job_id = ?", (jid,)).fetchone()
    if prev and prev["estado"] != "BORRADOR":
        return _err("la factura ya fue emitida", 409)
    if prev:
        db.execute("UPDATE invoices SET items=?, subtotal=?, descuento=?, impuesto=?, total=?, updated_at=? WHERE id=?",
                   (json.dumps(items, ensure_ascii=False), subtotal, descuento, impuesto, total, t, prev["id"]))
        iid = prev["id"]
    else:
        iid = db.execute("INSERT INTO invoices(job_id, items, subtotal, descuento, impuesto, total, estado, created_at, "
                         "updated_at) VALUES (?,?,?,?,?,?,?,?,?)", (jid, json.dumps(items, ensure_ascii=False), subtotal,
                                                                    descuento, impuesto, total, "BORRADOR", t, t)).lastrowid
    audit(db, g.user["username"], "INVOICE_DRAFT", "invoice", iid, f"job={jid}")
    db.commit()
    return jsonify(id=iid, items=items, subtotal=subtotal, descuento=descuento, impuesto=impuesto, total=total,
                   estado="BORRADOR"), 201


@bp.post("/invoices/<int:iid>/emit")
@requiere("DISPATCHER")
def emitir(iid):
    db = get_db()
    inv = db.execute("SELECT * FROM invoices WHERE id = ?", (iid,)).fetchone()
    if inv is None:
        return _err("factura no encontrada", 404)
    if inv["estado"] != "BORRADOR":
        return _err("solo se emite un borrador", 409)
    db.execute("UPDATE invoices SET estado='EMITIDA', updated_at=? WHERE id=?", (now_ms(), iid))
    audit(db, g.user["username"], "INVOICE_EMIT", "invoice", iid)
    db.commit()
    return jsonify(id=iid, estado="EMITIDA")


@bp.get("/jobs/<int:jid>/report")
@requiere()
def reporte(jid):
    """Reporte técnico del servicio (MASTER_FLOW §37) armado con lo que está realmente guardado."""
    db = get_db()
    row = job_visible(db, jid, g.user)
    if row is None:
        return _err("orden no encontrada", 404)
    j = J.a_api(row, g.user, db)
    eventos = [dict(e, payload=json.loads(e["payload"])) for e in db.execute(
        "SELECT tipo, payload, actor, ts FROM job_events WHERE job_id = ? ORDER BY id", (jid,))]
    rep = {
        "orden": {k: j[k] for k in ("id", "estado", "categoria", "descripcion", "equipo", "programadoPara", "hora",
                                    "zonaHoraria", "technicianId", "version")},
        "cliente": {"nombre": j["cliente"], "telefono": j["telefono"]},
        "propiedad": {"direccion": j["direccion"], "tipo": j["tipoPropiedad"],
                      "ubicacionVerificada": j["latitud"] is not None},
        "estados": [dict(h) for h in db.execute("SELECT new_value AS estado, actor, ts FROM job_history WHERE job_id = ? "
                                                "AND field = 'estado' ORDER BY id", (jid,))],
        "diagnostico": [e for e in eventos if e["tipo"] == "DIAGNOSTICO"],
        "mediciones": [e for e in eventos if e["tipo"] == "MEDICION"],
        "notas": [e for e in eventos if e["tipo"] == "NOTA"],
        "fotos": [dict(p) for p in db.execute("SELECT id, categoria, sha256, created_at FROM photos WHERE job_id = ?", (jid,))],
        "repuestos": [dict(p) for p in db.execute("SELECT p.sku, p.nombre, jp.cantidad, jp.estado, jp.precio_snapshot "
                                                  "FROM job_parts jp JOIN parts p ON p.id = jp.part_id WHERE jp.job_id = ?",
                                                  (jid,))],
        "aprobaciones": [dict(a) for a in db.execute("SELECT tipo, estado, firmante, firma_sha256, monto, decided_at "
                                                     "FROM approvals WHERE job_id = ?", (jid,))],
        "trabajo": [dict(w) for w in db.execute("SELECT tipo, descripcion, cantidad, precio_unitario, total FROM "
                                                "job_work_items WHERE job_id = ? AND anulado = 0 ORDER BY id", (jid,))],
        "factura": None,
        "generado": now_ms(),
    }
    inv = db.execute("SELECT subtotal, impuesto, total, estado, items FROM invoices WHERE job_id = ?", (jid,)).fetchone()
    if inv and (g.user["role"] != "CUSTOMER" or inv["estado"] != "BORRADOR"):
        rep["factura"] = dict(inv, items=json.loads(inv["items"]))
    return jsonify(rep)
