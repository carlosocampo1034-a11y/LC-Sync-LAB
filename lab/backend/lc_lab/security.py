"""Autenticación, roles y cifrado de datos sensibles del laboratorio."""
import hashlib
import secrets
from functools import wraps

from cryptography.fernet import Fernet, InvalidToken
from flask import current_app, g, jsonify, request
from werkzeug.security import check_password_hash, generate_password_hash

from .db import get_db, now_ms

TOKEN_TTL_MS = 12 * 3600 * 1000
MAX_FALLOS = 5
BLOQUEO_MS = 5 * 60 * 1000


def hash_password(pw):
    return generate_password_hash(pw, method="scrypt")


def password_valida(pw):
    return isinstance(pw, str) and len(pw) >= 8


def _hash_token(tok):
    return hashlib.sha256(tok.encode()).hexdigest()


def emitir_token(db, user_id, device_id=None):
    tok = secrets.token_urlsafe(32)
    t = now_ms()
    db.execute("INSERT INTO tokens(token_hash, user_id, device_id, created_at, expires_at) VALUES (?,?,?,?,?)",
               (_hash_token(tok), user_id, device_id, t, t + TOKEN_TTL_MS))
    return tok


def login(db, username, password):
    """Devuelve (user_row, error). Bloqueo temporal tras varios fallos."""
    u = db.execute("SELECT * FROM users WHERE username = ?", (username,)).fetchone()
    t = now_ms()
    if u is None or not u["active"]:
        check_password_hash(hash_password("x" * 8), password or "")  # tiempo similar al caso válido
        return None, "credenciales inválidas"
    if u["locked_until"] > t:
        return None, "cuenta bloqueada temporalmente"
    if not check_password_hash(u["password_hash"], password or ""):
        fallos = u["failed_logins"] + 1
        bloqueo = t + BLOQUEO_MS if fallos >= MAX_FALLOS else 0
        db.execute("UPDATE users SET failed_logins = ?, locked_until = ? WHERE id = ?",
                   (0 if bloqueo else fallos, bloqueo, u["id"]))
        db.commit()
        return None, "credenciales inválidas"
    db.execute("UPDATE users SET failed_logins = 0, locked_until = 0 WHERE id = ?", (u["id"],))
    return u, None


def usuario_actual():
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        return None
    db = get_db()
    row = db.execute("SELECT u.*, t.device_id AS token_device FROM tokens t JOIN users u ON u.id = t.user_id "
                     "WHERE t.token_hash = ? AND t.expires_at > ? AND u.active = 1",
                     (_hash_token(auth[7:].strip()), now_ms())).fetchone()
    return row


def requiere(*roles):
    """Exige token válido y uno de los roles (ADMIN siempre puede)."""
    def deco(fn):
        @wraps(fn)
        def inner(*a, **kw):
            u = usuario_actual()
            if u is None:
                return jsonify(error="no autenticado"), 401
            if roles and u["role"] not in roles and u["role"] != "ADMIN":
                return jsonify(error="sin permiso para esta operación"), 403
            g.user = u
            return fn(*a, **kw)
        return inner
    return deco


def _fernet():
    return Fernet(current_app.config["FERNET_KEY"])


def cifrar(texto):
    if texto is None or texto == "":
        return None
    return _fernet().encrypt(texto.encode()).decode()


def descifrar(token):
    if not token:
        return ""
    try:
        return _fernet().decrypt(token.encode()).decode()
    except InvalidToken:
        return ""


def puede_ver_acceso(user, job):
    """Códigos de acceso y ubicación de llave: solo despacho/admin, el técnico asignado o el cliente dueño."""
    if user["role"] in ("ADMIN", "DISPATCHER"):
        return True
    if user["role"] == "TECHNICIAN":
        return job["technician_code"] is not None and job["technician_code"] == user["technician_code"]
    if user["role"] == "CUSTOMER":
        return job["customer_id"] is not None and job["customer_id"] == user["customer_id"]
    return False
