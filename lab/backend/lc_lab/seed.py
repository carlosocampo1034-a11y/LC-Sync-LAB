"""Usuarios y repuestos FICTICIOS del laboratorio. Las contraseñas se generan al azar y se guardan
en instance/credenciales_lab.txt (permisos 600); no se imprimen."""
import os
import secrets
import sqlite3

from .db import now_ms
from .security import hash_password

USUARIOS = [("admin_lab", "ADMIN", "Admin Laboratorio", None),
            ("despacho_lab", "DISPATCHER", "Despacho Laboratorio", None),
            ("tecnico_lab", "TECHNICIAN", "Técnico Laboratorio", "tec-lab-01"),
            ("tecnico_lab2", "TECHNICIAN", "Técnico Laboratorio 2", "tec-lab-02")]
REPUESTOS = [("LAB-CAP-455", "Capacitor dual 45/5 µF (lab)", 38.0, 10), ("LAB-CON-30A", "Contactor 30A (lab)", 29.5, 6),
             ("LAB-FIL-162", "Filtro 16x25x1 (lab)", 9.0, 40), ("LAB-MOT-14", "Motor ventilador 1/4 HP (lab)", 165.0, 2)]


def sembrar(db_path, instance):
    c = sqlite3.connect(db_path)
    ruta = os.path.join(instance, "credenciales_lab.txt")
    nuevas = []
    t = now_ms()
    for user, rol, nombre, code in USUARIOS:
        if c.execute("SELECT 1 FROM users WHERE username = ?", (user,)).fetchone():
            continue
        pw = secrets.token_urlsafe(12)
        c.execute("INSERT INTO users(username, password_hash, role, display_name, technician_code, created_at) "
                  "VALUES (?,?,?,?,?,?)", (user, hash_password(pw), rol, nombre, code, t))
        nuevas.append(f"{user}\t{rol}\t{pw}")
    for sku, nombre, precio, stock in REPUESTOS:
        c.execute("INSERT OR IGNORE INTO parts(sku, nombre, precio, stock) VALUES (?,?,?,?)", (sku, nombre, precio, stock))
    c.commit()
    c.close()
    if nuevas:
        fd = os.open(ruta, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, "a") as f:
            f.write("\n".join(nuevas) + "\n")
    return len(nuevas)
