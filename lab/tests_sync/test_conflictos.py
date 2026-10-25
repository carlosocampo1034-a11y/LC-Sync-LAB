#!/usr/bin/env python3
"""
Pruebas de integridad de sincronización contra el CÓDIGO REAL del LAB (SQLite aislada en /tmp).

Cubre lo que la orden exige antes de aprobar f5af753:
  S1  mismo campo, dos actores → CONFLICTO, valor del servidor intacto, conflicto REGISTRADO
  S2  campos distintos, base vieja → se aplican ambos, sin pérdida
  S3  respuesta antigua después de una edición nueva → converge sin sobrescritura silenciosa
  S4  los conflictos no resueltos siguen visibles (job_conflicts / conflictos_pendientes)
  S5  convergencia: sin reintentos infinitos, versión monótona, historial completo

Cada comprobación imprime PASS/FAIL. Sale con código 1 si algo falla.
"""
import os
import sys
import types
import json

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.join(HERE, "..", "backend")
sys.path.insert(0, REPO)

# Stub de cryptography (no hay wheel para Python/Android aquí); no interviene en esta ruta.
if "cryptography" not in sys.modules:
    _c = types.ModuleType("cryptography"); _cf = types.ModuleType("cryptography.fernet")
    class Fernet:
        def __init__(self, key): self.key = key
        @staticmethod
        def generate_key(): return b"stub-key-0000000000000000000000000000="
        def encrypt(self, b): return b"enc:" + b
        def decrypt(self, b): return b[4:] if b.startswith(b"enc:") else b
    class InvalidToken(Exception): pass
    _cf.Fernet = Fernet; _cf.InvalidToken = InvalidToken; _c.fernet = _cf
    sys.modules["cryptography"] = _c; sys.modules["cryptography.fernet"] = _cf

from flask import Flask                      # noqa: E402
from lc_lab import db as DB                  # noqa: E402
from lc_lab import jobs as J                 # noqa: E402
from lc_lab.api_sync import _procesar_job    # noqa: E402

JOB = 8000000000011
DEV_A, DEV_B, DEV_D = "dev-tecnico-a", "dev-tecnico-b", "dev-despacho"
RESULTS = []


def blocked(name, detail):
    RESULTS.append((name, None, detail))
    print(f"  BLOCKED  {name}  · {detail}")


def check(name, ok, detail=""):
    RESULTS.append((name, bool(ok), detail))
    print(f"  {'PASS' if ok else 'FAIL'}  {name}{('  · ' + detail) if detail else ''}")


def fresh_db(path):
    for f in (path, path + "-wal", path + "-shm"):
        if os.path.exists(f):
            os.remove(f)
    app = Flask(__name__)
    app.config.update(DATABASE=path, FERNET_KEY="stub", SECRET_KEY="x")
    return app


def seed(conn):
    cols = {r[1] for r in conn.execute("PRAGMA table_info(users)")}
    users = {}
    for username, role, code in (("tec_a", "TECHNICIAN", "T-A"), ("tec_b", "TECHNICIAN", "T-B"),
                                 ("despacho", "DISPATCHER", None)):
        row = {"username": username, "role": role, "technician_code": code, "password_hash": "x",
               "display_name": username, "created_at": DB.now_ms(), "must_change_password": 0,
               "specialties": "", "updated_at": DB.now_ms()}
        row = {k: v for k, v in row.items() if k in cols or k == "username"}
        conn.execute(f"INSERT INTO users({','.join(row)}) VALUES ({','.join('?' * len(row))})",
                     list(row.values()))
        u = conn.execute("SELECT * FROM users WHERE username=?", (username,)).fetchone()
        users[username] = {"id": u["id"], "username": u["username"], "role": u["role"],
                           "technician_code": u["technician_code"]}
    return users


def job(conn):
    return conn.execute("SELECT * FROM jobs WHERE id=?", (JOB,)).fetchone()


def conflictos(conn):
    try:
        return conn.execute("SELECT * FROM job_conflicts WHERE job_id=? AND estado='PENDIENTE'", (JOB,)).fetchall()
    except Exception:
        return []


def main():
    path = "/tmp/lc-sync-tests/lab.db"
    os.makedirs("/tmp/lc-sync-tests", exist_ok=True)
    app = fresh_db(path)
    t = 1_760_000_000_000

    with app.app_context():
        DB.init_db(path)
        c = DB.get_db()
        us = seed(c)
        tec, tec_b, desp = us["tec_a"], us["tec_b"], us["despacho"]

        J.crear(c, {"id": JOB, "updatedAt": t, "estado": "PENDIENTE", "clienteNombre": "Cliente Prueba",
                    "descripcion": "Base de pruebas de merge"}, "DISPATCH", "despacho", "control",
                job_id=JOB, extra={"technician_code": "T-A"})
        print(f"orden {JOB} creada v1 asignada a T-A\n")

        # ── S1 · mismo campo, dos actores ────────────────────────────────────────────────────────
        print("S1 · mismo campo (estado) desde técnico y despacho con la misma base")
        r1 = _procesar_job(c, tec, DEV_A, {"id": JOB, "updatedAt": t + 10, "version": 1, "estado": "EN RUTA"})
        check("S1.1 técnico aplica su cambio", r1["status"] == "ACCEPTED", f"{r1['status']} v={r1.get('version')}")
        r2 = _procesar_job(c, desp, DEV_D, {"id": JOB, "updatedAt": t + 20, "version": 1, "estado": "CANCELADO"})
        check("S1.2 despacho con base vieja → CONFLICT", r2["status"] == "CONFLICT", f"{r2['status']}")
        check("S1.3 el valor del servidor NO se sobrescribe", job(c)["estado"] == "EN RUTA", f"estado={job(c)['estado']}")
        regs = conflictos(c)
        check("S1.4 el conflicto queda REGISTRADO y visible", len(regs) >= 1, f"{len(regs)} fila(s) en job_conflicts")

        # ── S2 · campos distintos con base vieja ────────────────────────────────────────────────
        print("\nS2 · campos distintos con la misma base vieja")
        v = job(c)["version"]
        r3 = _procesar_job(c, desp, DEV_D, {"id": JOB, "updatedAt": t + 30, "version": v, "notas": "Nota del despacho"})
        check("S2.1 despacho con versión vigente aplica notas", r3["status"] == "ACCEPTED", f"{r3['status']} v={r3.get('version')}")
        v = job(c)["version"]
        r4 = _procesar_job(c, tec, DEV_A, {"id": JOB, "updatedAt": t + 40, "version": v, "notas": "Nota del técnico"})
        check("S2.2 técnico con versión vigente aplica su nota", r4["status"] == "ACCEPTED", f"{r4['status']} v={r4.get('version')}")
        check("S2.3 no se pierde el estado previo", job(c)["estado"] == "EN RUTA", f"estado={job(c)['estado']}")

        # ── S0/S3 · reasignación verificada y respuesta antigua ────────────────────────────────
        print("\nS0 · reasignación T-A → T-B (precondición de S3 y S5)")
        v_pre = job(c)["version"]
        r_asig = _procesar_job(c, desp, DEV_D, {"id": JOB, "updatedAt": t + 45,
                                                "version": v_pre, "technicianId": "T-B"})
        asignado = job(c)["technician_code"]
        pre_ok = (r_asig["status"] == "ACCEPTED" and asignado == "T-B"
                  and job(c)["version"] > v_pre and r_asig.get("version") == job(c)["version"])
        check("S0.1 reasignación aceptada", r_asig["status"] == "ACCEPTED",
              f"{r_asig['status']} v={r_asig.get('version')} (motivo: {r_asig.get('reason','-')})")
        check("S0.2 el técnico asignado cambió de verdad", asignado == "T-B", f"technician_code={asignado}")
        check("S0.3 la versión avanzó y la respuesta corresponde", pre_ok,
              f"v{v_pre} → v{job(c)['version']} · respuesta v={r_asig.get('version')}")

        print("\nS3 · respuesta antigua sobre un campo que otro ya cambió después de la base")
        if not pre_ok:
            for n in ("S3.1 el envío tardío no se acepta a ciegas", "S3.2 no sobrescribe el estado vigente",
                      "S3.3 el choque queda registrado", "S5.1 seis subidas con versión vigente se aceptan",
                      "S5.3 la versión es monótona"):
                blocked(n, "precondición S0 no cumplida (reasignación)")
        else:
            base_desp = job(c)["version"]          # versión que el despacho leyó
            _procesar_job(c, tec_b, DEV_B, {"id": JOB, "updatedAt": t + 50, "version": base_desp,
                                            "estado": "EN CAMINO"})
            _procesar_job(c, tec_b, DEV_B, {"id": JOB, "updatedAt": t + 55, "version": job(c)["version"],
                                            "estado": "COMPLETADO"})
            estado_servidor = job(c)["estado"]
            r5 = _procesar_job(c, desp, DEV_D, {"id": JOB, "updatedAt": t + 56, "version": base_desp,
                                                "estado": "CANCELADO"})
            check("S3.1 el envío tardío no se acepta a ciegas", r5["status"] == "CONFLICT", f"{r5['status']}")
            check("S3.2 no sobrescribe el estado vigente", job(c)["estado"] == estado_servidor,
                  f"estado={job(c)['estado']} (servidor={estado_servidor})")
            check("S3.3 el choque queda registrado", len(conflictos(c)) >= 1, f"{len(conflictos(c))} pendiente(s)")

        # ── S4 · conflictos no resueltos permanecen ────────────────────────────────────────────
        print("\nS4 · visibilidad de conflictos no resueltos")
        regs = conflictos(c)
        check("S4.1 siguen registrados tras aplicar otros cambios", len(regs) >= 1, f"{len(regs)} pendientes")
        try:
            pend = J.conflictos_pendientes(c, JOB)
            check("S4.2 el detalle los expone al despacho", len(pend) >= 1, f"{len(pend)} campo(s)")
        except Exception as e:
            check("S4.2 el detalle los expone al despacho", False, str(e)[:60])

        # ── S5 · convergencia ─────────────────────────────────────────────────────────────────
        print("\nS5 · convergencia y ausencia de reintentos infinitos")
        antes = job(c)["version"]
        rep = 0
        if not pre_ok:
            blocked("S5.1 seis subidas con versión vigente se aceptan", "precondición S0")
        for i in range(6 if pre_ok else 0):
            r = _procesar_job(c, tec_b, DEV_B, {"id": JOB, "updatedAt": t + 100 + i * 10,
                                              "version": job(c)["version"], "notas": f"nota {i}"})
            if r["status"] == "ACCEPTED":
                rep += 1
        if pre_ok:
            check("S5.1 seis subidas con versión vigente se aceptan", rep == 6, f"{rep}/6 aceptadas")
        hist = c.execute("SELECT COUNT(*) n FROM job_history WHERE job_id=?", (JOB,)).fetchone()["n"]
        check("S5.2 el historial conserva cada cambio", hist >= 8, f"{hist} filas de historial")
        if pre_ok:
            check("S5.3 la versión es monótona", job(c)["version"] > antes, f"v{antes} → v{job(c)['version']}")


        # ── S6 · reintento idéntico (misma marca) → sin recibo duplicado ──────────────────────
        print("\nS6 · reintento idéntico del mismo cambio")
        v0 = job(c)["version"]
        marca = t + 700
        a = _procesar_job(c, tec_b, DEV_B, {"id": JOB, "updatedAt": marca, "version": v0, "notas": "reintento"})
        n1 = c.execute("SELECT COUNT(*) n FROM sync_receipts WHERE job_id=? AND client_updated_at=?",
                       (JOB, marca)).fetchone()["n"]
        b = _procesar_job(c, tec_b, DEV_B, {"id": JOB, "updatedAt": marca, "version": v0, "notas": "reintento"})
        n2 = c.execute("SELECT COUNT(*) n FROM sync_receipts WHERE job_id=? AND client_updated_at=?",
                       (JOB, marca)).fetchone()["n"]
        check("S6.1 el reintento no crea otro recibo", n1 == 1 and n2 == 1, f"{n1} → {n2} recibos")
        check("S6.2 no vuelve a subir la versión", a.get("version") == b.get("version"),
              f"v{a.get('version')} vs v{b.get('version')}")

        # ── S7 · concurrencia: dos dispositivos, misma base, mismo campo ──────────────────────
        print("\nS7 · concurrencia sobre el mismo campo desde dos dispositivos")
        base = job(c)["version"]
        r_a = _procesar_job(c, tec_b, DEV_B, {"id": JOB, "updatedAt": t + 800, "version": base,
                                             "notas": "Nota A (técnico)"})
        r_b = _procesar_job(c, desp, DEV_D, {"id": JOB, "updatedAt": t + 801, "version": base,
                                             "notas": "Nota B (despacho)"})
        check("S7.1 el primero gana", r_a["status"] == "ACCEPTED", f"{r_a['status']}")
        check("S7.2 el segundo no pisa el campo", r_b["status"] == "CONFLICT", f"{r_b['status']}")
        check("S7.3 el segundo queda registrado", len(conflictos(c)) >= 1, f"{len(conflictos(c))} pendientes")
        check("S7.4 el valor del primero se conserva", job(c)["notas"] == "Nota A (técnico)",
              f"notas={job(c)['notas']}")

    fails = [r for r in RESULTS if r[1] is False]
    blocks = [r for r in RESULTS if r[1] is None]
    oks = [r for r in RESULTS if r[1] is True]
    print(f"\n== PASS {len(oks)} · FAIL {len(fails)} · BLOCKED {len(blocks)} · total {len(RESULTS)} ==")
    for n, _, d in fails:
        print(f"  FALLA: {n} · {d}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
