"""Usuarios base y catálogo de repuestos del laboratorio.

Crea solo las cuentas de gestión mínimas (ADMIN + DISPATCHER) y el catálogo
de repuestos estándar. Los técnicos se crean manualmente desde el portal de
control, nunca de forma automática al iniciar.

Las contraseñas se generan al azar y se registran en instance/credenciales_lab.txt
(permisos 600). No se imprimen ni se incluyen en logs.
"""
import os
import secrets
import sqlite3

from .db import now_ms
from .security import hash_password

# Solo cuentas de gestión — los técnicos se crean vía portal de control
USUARIOS_GESTION = [
    ("admin_lab",    "ADMIN",      "Admin Laboratorio",    None),
    ("despacho_lab", "DISPATCHER", "Despacho Laboratorio", None),
]

REPUESTOS = [
    ("CAP-455",  "Capacitor dual 45/5 µF",      38.0,  10),
    ("CON-30A",  "Contactor 30A",               29.5,   6),
    ("FIL-162",  "Filtro 16x25x1",               9.0,  40),
    ("MOT-14",   "Motor ventilador 1/4 HP",    165.0,   2),
]


def sembrar(db_path, instance):
    """Siembra cuentas de gestión y catálogo si no existen. Idempotente."""
    c = sqlite3.connect(db_path)
    ruta = os.path.join(instance, "credenciales_lab.txt")
    nuevas = []
    t = now_ms()

    for user, rol, nombre, code in USUARIOS_GESTION:
        if c.execute("SELECT 1 FROM users WHERE username = ?", (user,)).fetchone():
            continue
        pw = secrets.token_urlsafe(18)
        c.execute(
            "INSERT INTO users(username, password_hash, role, display_name, "
            "technician_code, created_at) VALUES (?,?,?,?,?,?)",
            (user, hash_password(pw), rol, nombre, code, t),
        )
        nuevas.append(f"{user}\t{rol}\t{pw}")

    for sku, nombre, precio, stock in REPUESTOS:
        c.execute(
            "INSERT OR IGNORE INTO parts(sku, nombre, precio, stock) VALUES (?,?,?,?)",
            (sku, nombre, precio, stock),
        )

    c.commit()
    c.close()

    if nuevas:
        fd = os.open(ruta, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, "a") as f:
            f.write("\n".join(nuevas) + "\n")

    return len(nuevas)
