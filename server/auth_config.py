import os
import json
import hashlib
import hmac
import secrets as _secrets

USERS_FILE = os.path.join(os.path.dirname(__file__), "users.json")
SECRET_KEY_FILE = os.path.join(os.path.dirname(__file__), ".secret_key")

_PBKDF2_ALGO = 'sha256'
_PBKDF2_ITERS = 200_000


def _pbkdf2_hash(value: str) -> str:
    salt = _secrets.token_bytes(16)
    h = hashlib.pbkdf2_hmac(_PBKDF2_ALGO, value.encode(), salt, _PBKDF2_ITERS)
    return f"pbkdf2:{salt.hex()}:{h.hex()}"


def _pbkdf2_verify(value: str, stored: str) -> bool:
    if not stored.startswith("pbkdf2:"):
        return False
    parts = stored.split(":")
    if len(parts) != 3:
        return False
    try:
        salt = bytes.fromhex(parts[1])
        stored_hash = bytes.fromhex(parts[2])
        test = hashlib.pbkdf2_hmac(_PBKDF2_ALGO, value.encode(), salt, _PBKDF2_ITERS)
        return hmac.compare_digest(test, stored_hash)
    except Exception:
        return False


def get_secret_key():
    if os.path.exists(SECRET_KEY_FILE):
        with open(SECRET_KEY_FILE) as f:
            return f.read().strip()
    key = _secrets.token_urlsafe(48)
    with open(SECRET_KEY_FILE, "w") as f:
        f.write(key)
    os.chmod(SECRET_KEY_FILE, 0o600)
    return key


def load_users():
    if not os.path.exists(USERS_FILE):
        return {}
    with open(USERS_FILE) as f:
        data = json.load(f)
    return {u["username"]: u for u in data.get("users", [])}


def verify_user(username, password):
    users = load_users()
    user = users.get(username)
    if not user:
        return None
    if _pbkdf2_verify(password, user["password_hash"]):
        return user
    return None


def get_user_role(username):
    user = load_users().get(username)
    return user.get("role") if user else None


SESSION_LIFETIME_DAYS = 30
SESSION_LIFETIME_SECONDS = SESSION_LIFETIME_DAYS * 24 * 3600
