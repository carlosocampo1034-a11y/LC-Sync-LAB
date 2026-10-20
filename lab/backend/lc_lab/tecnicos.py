"""Alta y gestión de técnicos (L&C Control y API). Misma tabla `users` (rol TECHNICIAN), misma autenticación.
Las reglas viven aquí para que la API y la web apliquen exactamente lo mismo. Nunca se borra un técnico:
se desactiva (sus órdenes, historial y auditoría se conservan)."""
import re
import secrets

from .db import audit, now_ms
from .security import hash_password, password_valida

USUARIO_RE = re.compile(r"^[a-zA-Z0-9._-]{3,40}$")
CODIGO_RE = re.compile(r"^[a-z0-9][a-z0-9-]{2,29}$")       # ej. tec-lab-03 (lo usa la app y la sincronización)
MAX_ESPECIALIDADES = 200


def _api(u, db=None):
    out = {"id": u["id"], "username": u["username"], "displayName": u["display_name"],
           "technicianId": u["technician_code"], "active": bool(u["active"]),
           "mustChangePassword": bool(u["must_change_password"]), "specialties": u["specialties"] or "",
           "createdAt": u["created_at"], "updatedAt": u["updated_at"] or None,
           "bloqueadoHasta": u["locked_until"] if u["locked_until"] > now_ms() else None}
    if db is not None:
        out["abiertas"] = db.execute("SELECT COUNT(*) FROM jobs WHERE technician_code = ? AND estado NOT IN "
                                     "('COMPLETADO','CANCELADO')", (u["technician_code"],)).fetchone()[0]
    return out


def _texto(d, *claves):
    for k in claves:
        if k in d and d[k] is not None:
            return str(d[k]).strip()
    return ""


def _especialidades(v):
    """Lista o texto separado por comas → texto normalizado ("HVAC, Lavadoras")."""
    if isinstance(v, (list, tuple)):
        v = ", ".join(str(x) for x in v)
    partes = [p.strip() for p in str(v or "").split(",") if p.strip()]
    return ", ".join(dict.fromkeys(partes))


def listar(db, incluir_inactivos=True):
    q = "SELECT * FROM users WHERE role = 'TECHNICIAN'" + ("" if incluir_inactivos else " AND active = 1")
    return [_api(u, db) for u in db.execute(q + " ORDER BY active DESC, technician_code")]


def obtener(db, uid):
    u = db.execute("SELECT * FROM users WHERE id = ? AND role = 'TECHNICIAN'", (uid,)).fetchone()
    return _api(u, db) if u else None


def crear(db, actor, d):
    """(código HTTP, error|None, técnico|None, contraseña_inicial|None). El rol es SIEMPRE TECHNICIAN.
    Si no se indica contraseña se genera una al azar; se devuelve UNA vez para entregarla al técnico."""
    usuario = _texto(d, "username")
    nombre = _texto(d, "display_name", "displayName")
    codigo = _texto(d, "technician_code", "technicianId").lower()
    clave = d.get("password")
    generada = None
    if clave in (None, ""):
        clave = generada = secrets.token_urlsafe(9)        # ~12 caracteres
    esp = _especialidades(d.get("specialties"))
    if not USUARIO_RE.match(usuario):
        return 400, "usuario inválido (3-40: letras, números, . _ -)", None, None
    if len(nombre) < 3 or len(nombre) > 80:
        return 400, "nombre para mostrar de 3 a 80 caracteres", None, None
    if not password_valida(clave):
        return 400, "la contraseña debe tener al menos 8 caracteres", None, None
    if not CODIGO_RE.match(codigo):
        return 400, "código de técnico inválido (3-30: minúsculas, números y guiones; ej. tec-lab-03)", None, None
    if len(esp) > MAX_ESPECIALIDADES:
        return 400, f"especialidades: máximo {MAX_ESPECIALIDADES} caracteres", None, None
    if db.execute("SELECT 1 FROM users WHERE username = ?", (usuario,)).fetchone():
        return 409, "ese usuario ya existe", None, None
    if db.execute("SELECT 1 FROM users WHERE technician_code = ?", (codigo,)).fetchone():
        return 409, "ese código de técnico ya está en uso", None, None
    t = now_ms()
    uid = db.execute("INSERT INTO users(username, password_hash, role, display_name, technician_code, active, "
                     "must_change_password, specialties, created_at, updated_at) VALUES (?,?,?,?,?,1,1,?,?,?)",
                     (usuario, hash_password(clave), "TECHNICIAN", nombre, codigo, esp, t, t)).lastrowid
    audit(db, actor, "TECH_CREATE", "user", uid, f"username={usuario} code={codigo}")
    return 201, None, obtener(db, uid), (generada or clave)


def actualizar(db, actor, uid, d):
    """Nombre, especialidades, activo y restablecer contraseña. (código, error|None, técnico|None, clave|None)."""
    u = db.execute("SELECT * FROM users WHERE id = ? AND role = 'TECHNICIAN'", (uid,)).fetchone()
    if u is None:
        return 404, "técnico no encontrado", None, None
    sets, args, acciones, nueva = [], [], [], None
    if "display_name" in d or "displayName" in d:
        nombre = _texto(d, "display_name", "displayName")
        if len(nombre) < 3 or len(nombre) > 80:
            return 400, "nombre para mostrar de 3 a 80 caracteres", None, None
        if nombre != u["display_name"]:
            sets.append("display_name = ?"); args.append(nombre); acciones.append("TECH_UPDATE:nombre")
    if "specialties" in d:
        esp = _especialidades(d.get("specialties"))
        if len(esp) > MAX_ESPECIALIDADES:
            return 400, f"especialidades: máximo {MAX_ESPECIALIDADES} caracteres", None, None
        if esp != (u["specialties"] or ""):
            sets.append("specialties = ?"); args.append(esp); acciones.append("TECH_UPDATE:especialidades")
    if "active" in d:
        if not isinstance(d["active"], bool):
            return 400, "active debe ser true o false", None, None
        if int(d["active"]) != u["active"]:
            sets.append("active = ?"); args.append(int(d["active"]))
            acciones.append("TECH_ACTIVATE" if d["active"] else "TECH_DEACTIVATE")
    if d.get("reset_password") or d.get("resetPassword"):
        clave = d.get("password")
        if clave in (None, ""):
            clave = nueva = secrets.token_urlsafe(9)
        elif not password_valida(clave):
            return 400, "la contraseña debe tener al menos 8 caracteres", None, None
        else:
            nueva = clave
        sets += ["password_hash = ?", "must_change_password = 1", "failed_logins = 0", "locked_until = 0"]
        args.append(hash_password(clave))
        acciones.append("TECH_PASSWORD_RESET")
    if not sets:
        return 200, None, obtener(db, uid), None
    t = now_ms()
    sets.append("updated_at = ?"); args.append(t)
    db.execute(f"UPDATE users SET {', '.join(sets)} WHERE id = ?", (*args, uid))
    if "TECH_DEACTIVATE" in acciones or "TECH_PASSWORD_RESET" in acciones:
        # las sesiones abiertas caducan (no se borran): al reactivar o con la clave vieja no vuelven a valer
        db.execute("UPDATE tokens SET expires_at = ? WHERE user_id = ? AND expires_at > ?", (t, uid, t))
    for a in acciones:
        audit(db, actor, a.split(":")[0], "user", uid, a.split(":")[1] if ":" in a else None)
    return 200, None, obtener(db, uid), nueva
