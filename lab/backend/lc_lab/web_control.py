"""L&C Control (laboratorio): interfaz web mínima de despacho sobre la misma lógica que la API."""
import io
import json
import re
import secrets
from datetime import datetime
from functools import wraps
from zoneinfo import ZoneInfo

import qrcode
import qrcode.image.svg
from flask import Blueprint, abort, current_app, jsonify, redirect, render_template, request, session, url_for

from . import jobs as J
from .api_ops import transicion_valida
from .db import audit, get_db, now_ms
from .security import login as do_login

bp = Blueprint("control", __name__, url_prefix="/control", template_folder="templates")

ETIQUETAS = [("EN_CURSO", "En curso"), ("HOY", "Hoy"), ("ATRASADOS", "Atrasados"), ("PROXIMOS", "Próximos"),
             ("SIN_FECHA", "Sin fecha"), ("COMPLETADOS", "Completados"), ("CANCELADOS", "Cancelados")]


def _usuario():
    uid = session.get("uid")
    if not uid:
        return None
    return get_db().execute("SELECT * FROM users WHERE id = ? AND active = 1", (uid,)).fetchone()


def requiere_despacho(fn):
    @wraps(fn)
    def inner(*a, **kw):
        u = _usuario()
        if u is None or u["role"] not in ("ADMIN", "DISPATCHER"):
            return redirect(url_for("control.login"))
        return fn(u, *a, **kw)
    return inner


def _csrf():
    if "csrf" not in session:
        session["csrf"] = secrets.token_urlsafe(16)
    return session["csrf"]


def _check_csrf():
    if request.form.get("csrf") != session.get("csrf"):
        abort(400, "CSRF")


def _version_vigente(row):
    """Bloqueo optimista estricto en la web de despacho: el formulario debe traer la versión actual.
    (El merge de 3 vías es para la sincronización del teléfono, no para pestañas desactualizadas.)"""
    return request.form.get("version", type=int) == row["version"]


@bp.app_template_filter("fecha")
def fecha(ms, zona=None):
    if not ms:
        return "—"
    tz = ZoneInfo(zona or current_app.config["ZONA_HORARIA"])
    return datetime.fromtimestamp(int(ms) / 1000, tz).strftime("%a %d %b %Y · %I:%M %p")


@bp.app_template_filter("preferencia")
def preferencia(v):
    """La app del cliente guarda la fecha preferida como epoch ms; el texto libre se muestra tal cual."""
    if v and str(v).isdigit() and len(str(v)) >= 12:
        return fecha(int(v))
    return v


@bp.route("/login", methods=["GET", "POST"])
def login():
    error = None
    if request.method == "POST":
        db = get_db()
        u, err = do_login(db, request.form.get("username", "").strip(), request.form.get("password", ""))
        db.commit()
        if u and u["role"] in ("ADMIN", "DISPATCHER"):
            session.clear()
            session["uid"] = u["id"]
            return redirect(url_for("control.tablero"))
        error = err or "este usuario no es de despacho"
    return render_template("control_login.html", error=error, csrf=_csrf())


@bp.get("/logout")
def logout():
    session.clear()
    return redirect(url_for("control.login"))


@bp.get("/")
@requiere_despacho
def tablero(u):
    db = get_db()
    dias = max(1, min(request.args.get("dias", default=7, type=int), 366))
    vista = request.args.get("vista", "EN_CURSO")
    grupos = {k: [] for k, _ in ETIQUETAS}
    fuera = 0
    for row in db.execute("SELECT * FROM jobs ORDER BY COALESCE(programado_para, created_at)"):
        cat = J.categoria_agenda(row)
        if cat == "PROXIMOS" and not J.dentro_de_proximos(row, dias):
            fuera += 1
            continue
        grupos[cat].append(J.a_api(row, u, db))
    tecnicos = db.execute("SELECT display_name, technician_code FROM users WHERE role='TECHNICIAN' AND active=1").fetchall()
    ordenes = [j for k, _ in ETIQUETAS for j in grupos.get(k, [])]
    return render_template("control_board.html", u=u, grupos=grupos, etiquetas=ETIQUETAS, vista=vista,
                           dias=dias, fuera=fuera, tecnicos=tecnicos, csrf=_csrf(), ordenes=ordenes)


@bp.get("/jobs/<int:jid>")
@requiere_despacho
def detalle(u, jid):
    db = get_db()
    row = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
    if row is None:
        abort(404)
    if row is None:
        abort(404)
    hist = db.execute("SELECT * FROM job_history WHERE job_id = ? ORDER BY id DESC", (jid,)).fetchall()
    eventos = [dict(e, payload=json.loads(e["payload"])) for e in
               db.execute("SELECT * FROM job_events WHERE job_id = ? ORDER BY id", (jid,))]
    fotos = db.execute("SELECT id, categoria, uploaded_by, created_at FROM photos WHERE job_id = ?", (jid,)).fetchall()
    tecnicos = db.execute("SELECT display_name, technician_code FROM users WHERE role='TECHNICIAN' AND active=1").fetchall()
    conflictos = [J.conflicto_api(c, True) for c in J.conflictos_pendientes(db, jid)]
    from . import campo as CA
    from . import proposals as PR
    inv = db.execute("SELECT * FROM invoices WHERE job_id = ?", (jid,)).fetchone()
    factura = PR.factura_api(inv, db) if inv else None
    descuentos = db.execute("SELECT * FROM job_discounts WHERE job_id = ? ORDER BY id", (jid,)).fetchall()
    return render_template("control_job.html", u=u, j=J.a_api(row, u, db), hist=hist, eventos=eventos, fotos=fotos,
                           conflictos=conflictos, factura=factura, pagos=CA.pagos_de_orden(db, jid),
                           firmas=CA.firmas_de_orden(db, jid), descuentos=descuentos,
                           propuestas=PR.propuestas_de_orden(db, jid, tecnico=False),
                           variaciones=PR.variaciones_de_orden(db, jid),
                           comparacion=PR.comparacion(db, jid, factura["total"]) if factura else None,
                           tecnicos=tecnicos, estados=J.ESTADOS, csrf=_csrf(), mensaje=request.args.get("m"))


@bp.post("/jobs/<int:jid>/programar")
@requiere_despacho
def programar(u, jid):
    _check_csrf()
    db = get_db()
    row = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
    if row is None:
        abort(404)
    if not _version_vigente(row):
        return redirect(url_for("control.detalle", jid=jid, m="Conflicto: la orden cambió (ahora v%d); recarga" % row["version"]))
    zona = current_app.config["ZONA_HORARIA"]
    try:
        dt = datetime.strptime(request.form["fecha"] + " " + request.form["hora"], "%Y-%m-%d %H:%M").replace(
            tzinfo=ZoneInfo(zona))
    except (KeyError, ValueError):
        return redirect(url_for("control.detalle", jid=jid, m="Fecha u hora inválida"))
    st, v, conf = J.aplicar(db, row, {"programadoPara": int(dt.timestamp() * 1000), "zonaHoraria": zona,
                                      "hora": dt.strftime("%-I:%M %p")}, u["username"], "control",
                            version_base=request.form.get("version", type=int))
    db.commit()
    is_ajax = request.headers.get("X-Requested-With") == "XMLHttpRequest"
    if st == "ACCEPTED":
        msg = "Programada"
        if is_ajax:
            return jsonify({"ok": True, "msg": msg, "version": v})
    else:
        msg = f"Conflicto: {', '.join(conf)} cambió; recarga"
        if is_ajax:
            return jsonify({"ok": False, "msg": msg, "version": v})
    return redirect(url_for("control.detalle", jid=jid, m=msg))


@bp.post("/jobs/<int:jid>/asignar")
@requiere_despacho
def asignar(u, jid):
    _check_csrf()
    db = get_db()
    row = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
    if row is None:
        abort(404)
    if not _version_vigente(row):
        return redirect(url_for("control.detalle", jid=jid, m="Conflicto: la orden cambió (ahora v%d); recarga" % row["version"]))
    code = request.form.get("technicianId") or None
    if code and not db.execute("SELECT 1 FROM users WHERE technician_code=? AND role='TECHNICIAN'", (code,)).fetchone():
        return redirect(url_for("control.detalle", jid=jid, m="Técnico no válido"))
    st, v, conf = J.aplicar(db, row, {"technicianId": code, "estadoDispatch": "ASSIGNED" if code else "QUEUE"},
                            u["username"], "control", version_base=request.form.get("version", type=int))
    db.commit()
    is_ajax = request.headers.get("X-Requested-With") == "XMLHttpRequest"
    if st == "ACCEPTED":
        msg = "Asignada"
        if is_ajax:
            return jsonify({"ok": True, "msg": msg, "version": v, "technicianId": code or ""})
    else:
        msg = f"Conflicto: {', '.join(conf)} cambió; recarga"
        if is_ajax:
            return jsonify({"ok": False, "msg": msg, "version": v})
    return redirect(url_for("control.detalle", jid=jid, m=msg))


@bp.post("/jobs/<int:jid>/estado")
@requiere_despacho
def estado(u, jid):
    _check_csrf()
    db = get_db()
    row = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
    if row is None:
        abort(404)
    if not _version_vigente(row):
        return redirect(url_for("control.detalle", jid=jid, m="Conflicto: la orden cambió (ahora v%d); recarga" % row["version"]))
    nuevo = request.form.get("estado")
    if nuevo not in J.ESTADOS or not transicion_valida(row["estado"], nuevo, u["role"]):
        return redirect(url_for("control.detalle", jid=jid, m=f"Transición no permitida: {row['estado']} → {nuevo}"))
    st, v, conf = J.aplicar(db, row, {"estado": nuevo}, u["username"], "control",
                            version_base=request.form.get("version", type=int))
    db.commit()
    is_ajax = request.headers.get("X-Requested-With") == "XMLHttpRequest"
    if st == "ACCEPTED":
        msg = "Estado actualizado"
        if is_ajax:
            return jsonify({"ok": True, "msg": msg, "version": v, "estado": nuevo})
    else:
        msg = "Conflicto"
        if is_ajax:
            return jsonify({"ok": False, "msg": msg, "version": v})
    return redirect(url_for("control.detalle", jid=jid, m=msg))


@bp.route("/nueva", methods=["GET", "POST"])
@requiere_despacho
def nueva(u):
    db = get_db()
    if request.method == "POST":
        _check_csrf()
        d = {k: (request.form.get(k) or "").strip() for k in
             ("cliente", "telefono", "direccion", "descripcion", "categoria", "equipo")}
        d = {k: v for k, v in d.items() if v}
        d["estado"] = "PENDIENTE"
        errs = J.validar(d, creando=True)
        if len(d.get("descripcion", "")) < 10:
            errs.append("descripción muy corta")
        if errs:
            return render_template("control_new.html", u=u, error="; ".join(errs), f=request.form, csrf=_csrf())
        jid = J.crear(db, d, "DISPATCH", u["username"], "control", extra={"estado_dispatch": "QUEUE",
                                                                          "prioridad": "NORMAL"})
        db.commit()
        return redirect(url_for("control.detalle", jid=jid, m="Orden creada"))
    return render_template("control_new.html", u=u, error=None, f={}, csrf=_csrf())


@bp.post("/conflicts/<int:cid>/resolver")
@requiere_despacho
def resolver(u, cid):
    _check_csrf()
    db = get_db()
    c = db.execute("SELECT job_id FROM job_conflicts WHERE id = ?", (cid,)).fetchone()
    if c is None:
        abort(404)
    code, msg, _ = J.resolver_conflicto(db, cid, request.form.get("decision"), request.form.get("valor"),
                                        request.form.get("version", type=int), u["username"])
    if code == 200:
        db.commit()
    return redirect(url_for("control.detalle", jid=c["job_id"], m="Conflicto resuelto" if code == 200 else
                            ("Conflicto: " + msg)))


@bp.post("/jobs/<int:jid>/descuento")
@requiere_despacho
def descuento(u, jid):
    _check_csrf()
    from . import campo as CA
    db = get_db()
    code, msg = CA.autorizar_descuento(db, u, jid, request.form.get("monto", type=float), request.form.get("motivo"))
    if code == 200:
        db.commit()
    return redirect(url_for("control.detalle", jid=jid, m="Descuento autorizado" if code == 200 else msg))


@bp.post("/pagos/<int:pid>/revisar")
@requiere_despacho
def revisar_pago(u, pid):
    _check_csrf()
    from . import campo as CA
    db = get_db()
    p = db.execute("SELECT job_id FROM payments WHERE id = ?", (pid,)).fetchone()
    if p is None:
        abort(404)
    code, msg = CA.revisar_pago(db, u, pid, request.form.get("decision"), request.form.get("motivo"))
    if code == 200:
        db.commit()
    return redirect(url_for("control.detalle", jid=p["job_id"], m=("Pago " + request.form.get("decision", "").lower())
                            if code == 200 else msg))


@bp.get("/firmas/<int:sid>.png")
@requiere_despacho
def ver_firma(u, sid):
    from flask import send_file
    r = get_db().execute("SELECT path FROM job_signatures WHERE id = ?", (sid,)).fetchone()
    if r is None:
        abort(404)
    return send_file(r["path"], mimetype="image/png")


# ── técnicos (alta y gestión) ───────────────────────────────────────────────

def _pagina_tecnicos(u, mensaje=None, error=None, clave=None, pin=None, f=None, codigo=200):
    from flask import make_response
    from . import tecnicos as T
    r = make_response(render_template("control_tecnicos.html", u=u, tecnicos=T.listar(get_db()), csrf=_csrf(),
                                      mensaje=mensaje or request.args.get("m"), error=error, clave=clave, pin=pin,
                                      f=f or {}, es_admin=u["role"] == "ADMIN"), codigo)
    r.headers["Cache-Control"] = "no-store"
    return r


@bp.get("/tecnicos")
@requiere_despacho
def tecnicos(u):
    return _pagina_tecnicos(u)


@bp.post("/tecnicos")
@requiere_despacho
def crear_tecnico(u):
    _check_csrf()
    if u["role"] != "ADMIN":
        return _pagina_tecnicos(u, error="Solo un administrador puede dar de alta técnicos.", codigo=403)
    from . import tecnicos as T
    db = get_db()
    datos = {k: request.form.get(k, "") for k in ("username", "display_name", "password", "technician_code", "specialties")}
    code, err, tec, clave = T.crear(db, u["username"], datos)
    if err:
        datos.pop("password", None)          # nunca se devuelve la contraseña al formulario
        return _pagina_tecnicos(u, error=err, f=datos, codigo=code)
    db.commit()
    # respuesta directa (sin redirección): la contraseña inicial no pasa por la URL ni el historial
    return _pagina_tecnicos(u, mensaje=f"Técnico {tec['username']} ({tec['technicianId']}) creado. Debe cambiar la contraseña al entrar.",
                            clave={"usuario": tec["username"], "valor": clave, "uid": tec["id"]})


@bp.post("/tecnicos/<int:uid>/estado")
@requiere_despacho
def estado_tecnico(u, uid):
    _check_csrf()
    if u["role"] != "ADMIN":
        return _pagina_tecnicos(u, error="Solo un administrador puede activar o desactivar técnicos.", codigo=403)
    from . import tecnicos as T
    db = get_db()
    activo = request.form.get("activo") == "1"
    code, err, tec, _ = T.actualizar(db, u["username"], uid, {"active": activo})
    if err:
        return _pagina_tecnicos(u, error=err, codigo=code)
    db.commit()
    aviso = f" Tiene {tec['abiertas']} orden(es) abierta(s): reasígnalas." if not activo and tec["abiertas"] else ""
    return redirect(url_for("control.tecnicos", m=f"{tec['username']} {'activado' if activo else 'desactivado'}.{aviso}"))


@bp.post("/tecnicos/<int:uid>/clave")
@requiere_despacho
def clave_tecnico(u, uid):
    _check_csrf()
    if u["role"] != "ADMIN":
        return _pagina_tecnicos(u, error="Solo un administrador puede restablecer contraseñas.", codigo=403)
    from . import tecnicos as T
    db = get_db()
    code, err, tec, clave = T.actualizar(db, u["username"], uid, {"reset_password": True})
    if err:
        return _pagina_tecnicos(u, error=err, codigo=code)
    db.commit()
    return _pagina_tecnicos(u, mensaje=f"Contraseña de {tec['username']} restablecida; sus sesiones abiertas se cerraron.",
                            clave={"usuario": tec["username"], "valor": clave})


@bp.post("/tecnicos/<int:uid>/reset_pin")
@requiere_despacho
def reset_pin_tecnico(u, uid):
    _check_csrf()
    if u["role"] != "ADMIN":
        return _pagina_tecnicos(u, error="Solo un administrador puede restablecer el PIN.", codigo=403)
    db = get_db()
    tec = db.execute("SELECT * FROM users WHERE id = ? AND role = 'TECHNICIAN'", (uid,)).fetchone()
    if tec is None:
        return _pagina_tecnicos(u, error="Técnico no encontrado.", codigo=404)
    import random
    nuevo_pin = str(random.randint(100000, 999999))
    db.execute("UPDATE users SET pin_override = ?, updated_at = ? WHERE id = ?",
               (nuevo_pin, now_ms(), uid))
    audit(db, u["username"], "TECH_PIN_RESET", "user", uid, f"username={tec['username']}")
    db.commit()
    return _pagina_tecnicos(u, mensaje=f"Nuevo PIN de {tec['username']}: {nuevo_pin}  (el técnico debe activarlo desde la app)",
                            pin={"usuario": tec["username"], "valor": nuevo_pin})


# ── clientes (alta y gestión desde el centro de control) ────────────────────

TEL_RE = re.compile(r"^[\d +().\-]{7,32}$")
EMAIL_RE = re.compile(r"^[^\s@]+@[^\s@]+\.[^\s@]+$")


def _qr_svg(texto, caja=4):
    """QR en SVG inline (sin PIL ni descargas). Devuelve el markup listo para embebido."""
    q = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=caja, border=2)
    q.add_data(texto)
    q.make(fit=True)
    buf = io.BytesIO()
    q.make_image(image_factory=qrcode.image.svg.SvgPathImage).save(buf)
    return buf.getvalue().decode("utf-8")


def _listar_clientes(db):
    return db.execute(
        "SELECT c.*, "
        "(SELECT COUNT(*) FROM properties p WHERE p.customer_id = c.id) AS n_prop, "
        "(SELECT COUNT(*) FROM jobs j WHERE j.customer_id = c.id) AS n_jobs "
        "FROM customers c ORDER BY c.updated_at DESC, c.id DESC LIMIT 200"
    ).fetchall()


def _validar_cliente(form):
    nombre = (form.get("nombre") or "").strip()
    telefono = (form.get("telefono") or "").strip()
    email = (form.get("email") or "").strip()
    errs = []
    if len(nombre) < 2 or len(nombre) > 80:
        errs.append("nombre entre 2 y 80 caracteres")
    if telefono and not TEL_RE.match(telefono):
        errs.append("teléfono inválido (dígitos, espacios, +().-, 7-32)")
    if email and not EMAIL_RE.match(email):
        errs.append("email inválido")
    return nombre, telefono, email, errs


@bp.get("/clientes")
@requiere_despacho
def clientes(u):
    db = get_db()
    return render_template("control_clientes.html", u=u, clientes=_listar_clientes(db),
                           csrf=_csrf(), mensaje=request.args.get("m"), error=None, f={})


@bp.post("/clientes")
@requiere_despacho
def crear_cliente(u):
    _check_csrf()
    db = get_db()
    nombre, telefono, email, errs = _validar_cliente(request.form)
    if errs:
        return render_template("control_clientes.html", u=u, clientes=_listar_clientes(db),
                               csrf=_csrf(), mensaje=None, error="; ".join(errs), f=request.form), 400
    if db.execute("SELECT 1 FROM customers WHERE nombre = ? AND COALESCE(telefono,'') = ? "
                  "AND COALESCE(email,'') = ?", (nombre, telefono, email)).fetchone():
        return render_template("control_clientes.html", u=u, clientes=_listar_clientes(db),
                               csrf=_csrf(), mensaje=None,
                               error="ya existe un cliente con esos datos exactos", f=request.form), 409
    t = now_ms()
    cid = db.execute("INSERT INTO customers(nombre, telefono, email, created_at, updated_at) VALUES (?,?,?,?,?)",
                     (nombre, telefono or None, email or None, t, t)).lastrowid
    audit(db, u["username"], "CUSTOMER_CREATE", "customer", cid, f"nombre={nombre}")
    db.commit()
    return redirect(url_for("control.clientes", m=f"Cliente «{nombre}» creado (id {cid})"))


# ── invitación QR para empleados ────────────────────────────────────────────

@bp.get("/invitar/<int:uid>.svg")
@requiere_despacho
def invitar_qr(u, uid):
    """QR con la URL del centro de control (misma raíz del request). El usuario y la contraseña
    se entregan aparte en pantalla y nunca se incrustan en el QR por seguridad."""
    r = get_db().execute("SELECT username, role, active FROM users WHERE id = ?", (uid,)).fetchone()
    if r is None:
        abort(404)
    base = request.host_url.rstrip("/")
    payload = f"{base}/control/login"
    from flask import Response
    resp = Response(_qr_svg(payload, caja=5), mimetype="image/svg+xml")
    resp.headers["Cache-Control"] = "no-store"
    return resp
