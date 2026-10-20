import os
import sys

import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from lc_lab import create_app  # noqa: E402
from lc_lab.db import now_ms  # noqa: E402
from lc_lab.security import hash_password  # noqa: E402

PW = "clave-ficticia-123"
USUARIOS = [("admin_t", "ADMIN", "Admin T", None), ("desp_t", "DISPATCHER", "Despacho T", None),
            ("tec_t", "TECHNICIAN", "Técnico T", "tec-t-01"), ("tec_t2", "TECHNICIAN", "Técnico T2", "tec-t-02")]


@pytest.fixture
def app(tmp_path):
    a = create_app(instance=str(tmp_path / "instance"))
    import sqlite3
    c = sqlite3.connect(a.config["DATABASE"])
    for u, r, n, code in USUARIOS:
        c.execute("INSERT INTO users(username, password_hash, role, display_name, technician_code, created_at) "
                  "VALUES (?,?,?,?,?,?)", (u, hash_password(PW), r, n, code, now_ms()))
    c.execute("INSERT INTO parts(sku, nombre, precio, stock) VALUES ('T-CAP', 'Capacitor test', 40.0, 3)")
    c.commit()
    c.close()
    return a


@pytest.fixture
def client(app):
    return app.test_client()


def token(client, user, pw=PW, device=None):
    r = client.post("/api/v2/auth/login", json={"username": user, "password": pw, "deviceId": device})
    assert r.status_code == 200, r.get_json()
    return r.get_json()["token"]


def h(tok):
    return {"Authorization": f"Bearer {tok}"}


@pytest.fixture
def toks(client):
    return {"admin": token(client, "admin_t"), "desp": token(client, "desp_t"),
            "tec": token(client, "tec_t", device="dev-A"), "tec2": token(client, "tec_t2", device="dev-B")}


def registrar_cliente(client, usuario="cliente_t", nombre="Cliente Ficticio T"):
    r = client.post("/api/v2/auth/register", json={"username": usuario, "password": PW, "nombre": nombre,
                                                   "telefono": "(305) 555-0100"})
    assert r.status_code == 201, r.get_json()
    return r.get_json()["token"]
