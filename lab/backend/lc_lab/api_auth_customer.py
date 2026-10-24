"""Autenticación y portal del cliente (L&C Customer)."""
import re
import time
import threading
import json
from flask import Blueprint, g, jsonify, request

# Simple in-process rate limiter for /guest/quote (IP-based, max 5 req/min)
_RATE_LOCK = threading.Lock()
_RATE_BUCKETS: dict = {}   # ip → [timestamp, ...]
_RATE_WINDOW = 60          # seconds
_RATE_MAX = 5              # requests per window per IP

def _rate_ok(ip: str) -> bool:
    now = time.time()
    with _RATE_LOCK:
        hits = _RATE_BUCKETS.get(ip, [])
        hits = [t for t in hits if now - t < _RATE_WINDOW]
        if len(hits) >= _RATE_MAX:
            _RATE_BUCKETS[ip] = hits
            return False
        hits.append(now)
        _RATE_BUCKETS[ip] = hits
        return True

from . import jobs as J
from .db import audit, get_db, now_ms
from .security import emitir_token, hash_password, login, password_valida, requiere

bp = Blueprint("auth_customer", __name__, url_prefix="/api/v2")

USUARIO_RE = re.compile(r"^[a-zA-Z0-9._-]{3,40}$")
TEL_RE = re.compile(r"^\+?[0-9 ()\-]{7,20}$")


def _json():
    d = request.get_json(silent=True)
    return d if isinstance(d, dict) else {}


def _err(msg, code=400, **kw):
    return jsonify(error=msg, **kw), code


@bp.post("/auth/login")
def auth_login():
    d = _json()
    db = get_db()
    u, err = login(db, (d.get("username") or "").strip(), d.get("password") or "")
    if err:
        return _err(err, 401)
    tok = emitir_token(db, u["id"], d.get("deviceId"))
    audit(db, u["username"], "LOGIN", "user", u["id"], device=d.get("deviceId"))
    extra = {}
    if u["role"] == "TECHNICIAN" and (d.get("deviceId") or "").strip():
        from .api_sync import registrar_dispositivo
        n, base = registrar_dispositivo(db, d["deviceId"].strip(), u["technician_code"])
        extra = {"deviceNumber": n, "idBase": base, "idLimite": base + 100_000_000}
    db.commit()
    return jsonify(token=tok, role=u["role"], displayName=u["display_name"],
                   customerId=u["customer_id"], technicianId=u["technician_code"],
                   mustChangePassword=bool(u["must_change_password"]), **extra)


@bp.post("/auth/register")
def auth_register():
    """Alta de cliente (solo rol CUSTOMER). Técnicos y despacho los crea un administrador."""
    d = _json()
    usuario = (d.get("username") or "").strip()
    nombre = (d.get("nombre") or "").strip()
    tel = (d.get("telefono") or "").strip()
    if not USUARIO_RE.match(usuario):
        return _err("usuario inválido (3-40: letras, números, . _ -)")
    if not password_valida(d.get("password")):
        return _err("la contraseña debe tener al menos 8 caracteres")
    if len(nombre) < 3:
        return _err("nombre demasiado corto")
    if tel and not TEL_RE.match(tel):
        return _err("teléfono inválido")
    # se guarda normalizado (solo dígitos; 10 dígitos, o 11 con el 1 inicial de EE. UU.)
    if tel:
        dig = re.sub(r"\D", "", tel)
        if len(dig) == 11 and dig.startswith("1"):
            dig = dig[1:]
        if len(dig) != 10:
            return _err("teléfono: 10 dígitos")
        tel = dig
    db = get_db()
    if db.execute("SELECT 1 FROM users WHERE username = ?", (usuario,)).fetchone():
        return _err("ese usuario ya existe", 409)
    t = now_ms()
    cid = db.execute("INSERT INTO customers(nombre, telefono, email, created_at, updated_at) VALUES (?,?,?,?,?)",
                     (nombre, tel or None, (d.get("email") or "").strip() or None, t, t)).lastrowid
    uid = db.execute("INSERT INTO users(username, password_hash, role, display_name, customer_id, created_at) "
                     "VALUES (?,?,?,?,?,?)", (usuario, hash_password(d["password"]), "CUSTOMER", nombre, cid, t)).lastrowid
    audit(db, usuario, "REGISTER", "user", uid)
    tok = emitir_token(db, uid, d.get("deviceId"))
    db.commit()
    return jsonify(token=tok, role="CUSTOMER", displayName=nombre, customerId=cid), 201


@bp.get("/me")
@requiere()
def me():
    u = g.user
    return jsonify(username=u["username"], role=u["role"], displayName=u["display_name"],
                   customerId=u["customer_id"], technicianId=u["technician_code"],
                   mustChangePassword=bool(u["must_change_password"]), must_change_password=int(u["must_change_password"]))


@bp.post("/me/password")
@requiere()
def cambiar_password():
    """Cambio de contraseña del propio usuario (cualquier rol). Exige la actual; una actual incorrecta cuenta como
    intento fallido (mismo bloqueo que el inicio de sesión). Limpia must_change_password."""
    from werkzeug.security import check_password_hash
    from .security import BLOQUEO_MS, MAX_FALLOS
    d = _json()
    db = get_db()
    u = db.execute("SELECT * FROM users WHERE id = ?", (g.user["id"],)).fetchone()
    t = now_ms()
    if u["locked_until"] > t:
        return _err("cuenta bloqueada temporalmente", 429)
    actual, nueva = d.get("actual") or "", d.get("nueva")
    if not check_password_hash(u["password_hash"], actual):
        fallos = u["failed_logins"] + 1
        bloqueo = t + BLOQUEO_MS if fallos >= MAX_FALLOS else 0
        db.execute("UPDATE users SET failed_logins = ?, locked_until = ? WHERE id = ?", (0 if bloqueo else fallos, bloqueo, u["id"]))
        audit(db, u["username"], "PASSWORD_CHANGE_FAILED", "user", u["id"])
        db.commit()
        return _err("la contraseña actual no es correcta", 403)
    if not password_valida(nueva):
        return _err("la contraseña nueva debe tener al menos 8 caracteres")
    if nueva == actual:
        return _err("la contraseña nueva debe ser distinta de la actual")
    db.execute("UPDATE users SET password_hash = ?, must_change_password = 0, failed_logins = 0, updated_at = ? "
               "WHERE id = ?", (hash_password(nueva), t, u["id"]))
    audit(db, u["username"], "PASSWORD_CHANGE", "user", u["id"])
    db.commit()
    return jsonify(ok=True, mustChangePassword=False)


# ── propiedades y equipos del cliente ───────────────────────────────────────

def _propiedad_del_cliente(db, pid):
    return db.execute("SELECT * FROM properties WHERE id = ? AND customer_id = ?",
                      (pid, g.user["customer_id"])).fetchone()


def _prop_api(p):
    return {"id": p["id"], "direccion": p["direccion"], "tipoPropiedad": p["tipo_propiedad"],
            "latitud": p["lat"], "longitud": p["lng"], "ubicacionFuente": p["ubicacion_fuente"],
            "tipoAcceso": p["tipo_acceso"], "tieneCodigoAcceso": bool(p["codigo_acceso_enc"]),
            "instrucciones": p["instrucciones"], "mascota": p["mascota"],
            "contactoRespaldo": p["contacto_respaldo"]}


@bp.post("/customer/properties")
@requiere("CUSTOMER")
def crear_propiedad():
    from .security import cifrar
    d = _json()
    if len((d.get("direccion") or "").strip()) < 5:
        return _err("dirección demasiado corta")
    if (d.get("latitud") is None) != (d.get("longitud") is None):
        return _err("coordenadas incompletas")
    db = get_db()
    t = now_ms()
    pid = db.execute(
        "INSERT INTO properties(customer_id, direccion, tipo_propiedad, lat, lng, ubicacion_fuente, tipo_acceso, "
        "codigo_acceso_enc, instrucciones, mascota, contacto_respaldo, created_at, updated_at) "
        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        (g.user["customer_id"], d["direccion"].strip(), d.get("tipoPropiedad"), d.get("latitud"), d.get("longitud"),
         d.get("ubicacionFuente") if d.get("latitud") is not None else None, d.get("tipoAcceso"),
         cifrar(d.get("codigoAcceso")), d.get("instrucciones"), d.get("mascota"), d.get("contactoRespaldo"), t, t)
    ).lastrowid
    audit(db, g.user["username"], "CREATE", "property", pid)
    db.commit()
    return jsonify(_prop_api(_propiedad_del_cliente(db, pid))), 201


@bp.get("/customer/properties")
@requiere("CUSTOMER")
def listar_propiedades():
    db = get_db()
    props = db.execute("SELECT * FROM properties WHERE customer_id = ? ORDER BY id", (g.user["customer_id"],)).fetchall()
    out = []
    for p in props:
        eq = db.execute("SELECT id, nombre, tipo, marca, modelo, serial FROM equipment WHERE property_id = ?",
                        (p["id"],)).fetchall()
        x = _prop_api(p)
        x["equipos"] = [dict(e) for e in eq]
        out.append(x)
    return jsonify(properties=out)


@bp.post("/customer/properties/<int:pid>/equipment")
@requiere("CUSTOMER")
def crear_equipo(pid):
    d = _json()
    db = get_db()
    if _propiedad_del_cliente(db, pid) is None:
        return _err("propiedad no encontrada", 404)
    if len((d.get("nombre") or "").strip()) < 2:
        return _err("nombre del equipo requerido")
    t = now_ms()
    eid = db.execute("INSERT INTO equipment(property_id, nombre, tipo, marca, modelo, serial, created_at, updated_at) "
                     "VALUES (?,?,?,?,?,?,?,?)", (pid, d["nombre"].strip(), d.get("tipo"), d.get("marca"),
                                                  d.get("modelo"), d.get("serial"), t, t)).lastrowid
    audit(db, g.user["username"], "CREATE", "equipment", eid)
    db.commit()
    return jsonify(id=eid, nombre=d["nombre"].strip()), 201


# ── solicitudes del cliente ─────────────────────────────────────────────────

@bp.post("/customer/requests")
@requiere("CUSTOMER")
def crear_solicitud():
    d = _json()
    db = get_db()
    p = _propiedad_del_cliente(db, d.get("propertyId"))
    if p is None:
        return _err("propiedad no encontrada", 404)
    desc = (d.get("descripcion") or "").strip()
    if len(desc) < 10:
        return _err("descripción muy corta (mín. 10 caracteres)")
    eq = None
    if d.get("equipmentId") is not None:
        eq = db.execute("SELECT * FROM equipment WHERE id = ? AND property_id = ?",
                        (d["equipmentId"], p["id"])).fetchone()
        if eq is None:
            return _err("equipo no encontrado en esa propiedad", 404)
    pref = d.get("fechaPreferida")  # epoch ms opcional
    if pref is not None and (not isinstance(pref, int) or pref < now_ms() - 3600_000):
        return _err("fecha preferida inválida o en el pasado")
    # Idempotencia: la app manda un clientRequestId; reintentar no duplica la solicitud
    crid = (d.get("clientRequestId") or "").strip() or None
    if crid:
        prev = db.execute("SELECT j.id FROM jobs j JOIN audit_log a ON a.entity='job' AND a.entity_id = j.id "
                          "WHERE a.action='CUSTOMER_REQUEST' AND a.detail = ? AND j.customer_id = ?",
                          (crid, g.user["customer_id"])).fetchone()
        if prev:
            row = db.execute("SELECT * FROM jobs WHERE id = ?", (prev[0],)).fetchone()
            return jsonify(J.a_api(row, g.user, db)), 200
    c = db.execute("SELECT * FROM customers WHERE id = ?", (g.user["customer_id"],)).fetchone()
    datos = {"cliente": c["nombre"], "telefono": c["telefono"], "direccion": p["direccion"],
             "latitud": p["lat"], "longitud": p["lng"], "ubicacionFuente": p["ubicacion_fuente"],
             "descripcion": desc, "categoria": d.get("categoria"), "equipo": eq["nombre"] if eq else None,
             "tipoPropiedad": p["tipo_propiedad"], "tipoAcceso": p["tipo_acceso"],
             "instrucciones": p["instrucciones"], "mascota": p["mascota"], "contactoRespaldo": p["contacto_respaldo"],
             "ventanaPreferida": d.get("ventanaPreferida"), "zonaHoraria": d.get("zonaHoraria"),
             "clientePresente": d.get("clientePresente"), "estado": "PENDIENTE"}
    errs = J.validar(datos, creando=True)
    if errs:
        return _err("; ".join(errs))
    jid = J.crear(db, datos, "CUSTOMER", g.user["username"], "customer_app",
                  extra={"customer_id": c["id"], "property_id": p["id"], "equipment_id": eq["id"] if eq else None,
                         "estado_dispatch": "QUEUE", "prioridad": "NORMAL",
                         "codigo_acceso_enc": p["codigo_acceso_enc"],
                         # la fecha preferida NO es la cita: la confirma despacho
                         "ventana_preferida": d.get("ventanaPreferida") or (str(pref) if pref else None)})
    if crid:
        audit(db, g.user["username"], "CUSTOMER_REQUEST", "job", jid, crid)
    db.commit()
    return jsonify(J.a_api(db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone(), g.user)), 201


def _historial_publico(db, jid):
    """Lo que el cliente puede ver: cambios de estado, cita y técnico (sin datos internos)."""
    visibles = {"estado": "Estado", "programado_para": "Cita", "technician_code": "Técnico asignado"}
    rows = db.execute("SELECT field, new_value, ts FROM job_history WHERE job_id = ? AND field IN "
                      "('estado','programado_para','technician_code') ORDER BY id", (jid,)).fetchall()
    return [{"campo": visibles[r["field"]], "valor": r["new_value"], "ts": r["ts"]} for r in rows]


@bp.get("/customer/requests")
@requiere("CUSTOMER")
def listar_solicitudes():
    db = get_db()
    rows = db.execute("SELECT * FROM jobs WHERE customer_id = ? ORDER BY created_at DESC",
                      (g.user["customer_id"],)).fetchall()
    return jsonify(requests=[J.a_api(r, g.user, db) for r in rows])


@bp.get("/customer/requests/<int:jid>")
@requiere("CUSTOMER")
def ver_solicitud(jid):
    db = get_db()
    row = db.execute("SELECT * FROM jobs WHERE id = ? AND customer_id = ?", (jid, g.user["customer_id"])).fetchone()
    if row is None:
        return _err("solicitud no encontrada", 404)
    out = J.a_api(row, g.user, db)
    out["historial"] = _historial_publico(db, jid)
    tec = db.execute("SELECT display_name FROM users WHERE technician_code = ? AND role='TECHNICIAN'",
                     (row["technician_code"],)).fetchone() if row["technician_code"] else None
    out["tecnicoNombre"] = tec["display_name"] if tec else None
    out["aprobaciones"] = [dict(a) for a in db.execute(
        "SELECT id, tipo, descripcion, monto, estado, created_at, decided_at FROM approvals WHERE job_id = ?", (jid,))]
    inv = db.execute("SELECT id, subtotal, impuesto, total, estado FROM invoices WHERE job_id = ? AND estado != 'BORRADOR'",
                     (jid,)).fetchone()
    out["factura"] = dict(inv) if inv else None
    out["fotos"] = [dict(f) for f in db.execute("SELECT id, categoria, created_at FROM photos WHERE job_id = ?", (jid,))]
    from . import proposals as PR
    out["propuestas"] = PR.propuestas_de_orden(db, jid, tecnico=False)
    out["variaciones"] = PR.variaciones_de_orden(db, jid)
    if out["factura"] is not None:
        inv = db.execute("SELECT * FROM invoices WHERE id = ?", (out["factura"]["id"],)).fetchone()
        out["factura"] = PR.factura_api(inv, db)
        from . import campo as CA
        # el cliente ve el estado de sus pagos (sin datos internos de revisión)
        out["pagos"] = [{"monto": p["monto"], "metodo": p["metodo"], "estado": p["estado"], "fecha": p["reportadoAt"]}
                        for p in CA.pagos_de_orden(db, jid)]
    # resultado del servicio: solo partidas vigentes (las anuladas se conservan en el servidor, no se muestran)
    out["trabajoRealizado"] = [dict(w) for w in db.execute(
        "SELECT tipo, descripcion, cantidad, precio_unitario AS precioUnitario, total FROM job_work_items "
        "WHERE job_id = ? AND anulado = 0 ORDER BY id", (jid,))]
    return jsonify(out)


@bp.post("/customer/proposals/<int:pid>/decide")
@requiere("CUSTOMER")
def decidir_propuesta(pid):
    from . import proposals as PR
    d = request.get_json(silent=True) or {}
    db = get_db()
    code, msg, out = PR.decidir(db, g.user, pid, d)
    if code != 200:
        db.commit()        # una pendiente caducada queda VENCIDA aunque la decisión se rechace
        return jsonify(error=msg), code
    db.commit()
    return jsonify(propuesta=out)


@bp.get("/customer/requests/<int:jid>/factura.pdf")
@requiere("CUSTOMER")
def factura_pdf(jid):
    from flask import send_file
    from . import campo as CA
    db = get_db()
    row = db.execute("SELECT id FROM jobs WHERE id = ? AND customer_id = ?", (jid, g.user["customer_id"])).fetchone()
    inv = db.execute("SELECT id FROM invoices WHERE job_id = ? AND estado != 'BORRADOR'", (jid,)).fetchone() if row else None
    doc = CA.ultimo_pdf(db, inv["id"]) if inv else None
    if doc is None:
        return _err("no hay PDF de la factura", 404)
    return send_file(doc["path"], mimetype="application/pdf")


@bp.post("/customer/variances/<int:vid>/decide")
@requiere("CUSTOMER")
def decidir_variacion(vid):
    from . import proposals as PR
    db = get_db()
    code, msg, out = PR.decidir_variacion(db, g.user, vid, request.get_json(silent=True) or {})
    if code != 200:
        return jsonify(error=msg), code
    db.commit()
    return jsonify(variacion=out)


_GUEST_DEDUP_WINDOW_MS = 5 * 60 * 1000  # 5 minutes


@bp.post("/guest/quote")
def guest_quote():
    """Solicitud de presupuesto sin cuenta (modo invitado de la app Customer).
    No requiere autenticación. Crea un cliente lead y una orden PENDIENTE en la cola de despacho.
    Protegido: rate limit por IP (5/min), deduplicación por teléfono (5 min), max lengths."""
    # Rate limiting (in-process, no persistence — sufficient for LAB; use Redis in prod)
    ip = request.headers.get("X-Forwarded-For", request.remote_addr or "unknown").split(",")[0].strip()
    if not _rate_ok(ip):
        return _err("demasiadas solicitudes, intenta en un momento", 429)

    d = _json()
    nombre = (d.get("nombre") or "").strip()[:120]
    telefono = (d.get("telefono") or "").strip()[:20]
    email = (d.get("email") or "").strip()[:120] or None
    direccion = (d.get("direccion") or "").strip()[:300]
    descripcion = (d.get("descripcion") or "").strip()[:1000]
    categoria = (d.get("categoria") or "").strip()[:50] or None

    if len(nombre) < 3:
        return _err("nombre requerido (mínimo 3 caracteres)")
    if not telefono:
        return _err("teléfono requerido")
    if not TEL_RE.match(telefono):
        return _err("teléfono inválido (solo dígitos, 7-15 caracteres)")
    if not direccion:
        return _err("dirección requerida")
    if len(descripcion) < 5:
        return _err("descripción muy corta (mínimo 5 caracteres)")

    db = get_db()
    t = now_ms()

    # Duplicate guard: same telefono within 5 minutes → return existing referencia
    recent = db.execute(
        "SELECT id FROM jobs WHERE telefono=? AND origin='CUSTOMER' AND created_at > ? "
        "AND estado NOT IN ('CANCELADO') ORDER BY created_at DESC LIMIT 1",
        (telefono, t - _GUEST_DEDUP_WINDOW_MS)
    ).fetchone()
    if recent:
        referencia = f"LC-{recent['id']:04d}"
        return jsonify(ok=True, id=recent["id"], referencia=referencia,
                       mensaje=f"Ya tienes una solicitud reciente ({referencia}). "
                                "Te contactaremos pronto."), 200

    # Crear cliente lead (sin cuenta)
    cid = db.execute(
        "INSERT INTO customers(nombre, telefono, email, created_at, updated_at) VALUES (?,?,?,?,?)",
        (nombre, telefono, email, t, t)
    ).lastrowid

    datos = {
        "cliente": nombre,
        "telefono": telefono,
        "direccion": direccion,
        "descripcion": descripcion,
        "categoria": categoria,
        "estado": "PENDIENTE",
    }
    errs = J.validar(datos, creando=True)
    if errs:
        return _err("; ".join(errs))

    jid = J.crear(db, datos, "CUSTOMER", "guest_app", "customer_app",
                  extra={"customer_id": cid, "estado_dispatch": "QUEUE", "prioridad": "NORMAL"})
    audit(db, "guest_app", "GUEST_QUOTE", "job", jid, f"tel={telefono} ip={ip}")
    db.commit()

    referencia = f"LC-{jid:04d}"
    return jsonify(
        ok=True,
        id=jid,
        referencia=referencia,
        mensaje=f"Tu solicitud fue registrada con el número {referencia}. "
                f"Nos pondremos en contacto al {telefono} para confirmar la visita."
    ), 201


@bp.get("/auth/pin-pending")
@requiere("TECHNICIAN")
def pin_pendiente():
    """Devuelve y borra el PIN temporal generado desde el web dashboard (reset por admin).
    La app debe llamar este endpoint tras conectar al laboratorio y mostrar el PIN al técnico."""
    db = get_db()
    row = db.execute("SELECT pin_override FROM users WHERE id = ?", (g.user["id"],)).fetchone()
    if row is None or row["pin_override"] is None:
        return jsonify(pendingPin=None)
    pin = row["pin_override"]
    db.execute("UPDATE users SET pin_override = NULL, updated_at = ? WHERE id = ?",
               (now_ms(), g.user["id"]))
    db.commit()
    return jsonify(pendingPin=pin)
