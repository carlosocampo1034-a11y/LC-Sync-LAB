import os
import json
import bcrypt

USERS_FILE = os.path.join(os.path.dirname(__file__), "users.json")
SECRET_KEY_FILE = os.path.join(os.path.dirname(__file__), ".secret_key")


def get_secret_key():
    if os.path.exists(SECRET_KEY_FILE):
        with open(SECRET_KEY_FILE) as f:
            return f.read().strip()
    import secrets
    key = secrets.token_urlsafe(48)
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
    stored_hash = user["password_hash"].encode()
    if bcrypt.checkpw(password.encode(), stored_hash):
        return user
    return None


def get_user_role(username):
    user = load_users().get(username)
    return user.get("role") if user else None


SESSION_LIFETIME_DAYS = 30
SESSION_LIFETIME_SECONDS = SESSION_LIFETIME_DAYS * 24 * 3600
