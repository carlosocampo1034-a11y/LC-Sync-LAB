"""Puente Control original ↔ LC-Sync-QA con un lc-api simulado (sin red)."""
import json
import sqlite3

import pytest

from lc_lab import jobs as J
from lc_lab import qa_bridge as QB

TECS = [{"id": "uuid-t1", "technician_code": "QA-T01", "display_name": "Técnico QA Uno"},
        {"id": "uuid-t2", "technician_code": "QA-T02", "display_name": "Técnico QA Dos"}]


class FakeApi:
    """lc-api mínimo: órdenes con versión, PATCH con bloqueo optimista como lc_patch_order."""

    def __init__(self):
        self.orders, self.calls, self.t = {}, [], 1_000

    def nueva(self, oid, **kw):
        self.t += 1
        o = {"id": oid, "version": 1, "estado": "PENDIENTE", "estado_dispatch": None, "origin": "CUSTOMER",
             "technician_id": None, "technician_code": None, "cliente": "cliente ficticio",
             "direccion": "1 Calle Ficticia", "descripcion": "prueba ficticia", "notas": None,
             "prioridad": None, "categoria": None, "telefono": None, "programado_para": None, "updated_at": self.t}
        o.update(kw)
        self.orders[oid] = o
        return o

    def cambia(self, oid, **kw):
        self.t += 1
        o = self.orders[oid]
        o.update(kw, version=o["version"] + 1, updated_at=self.t)

    def call(self, method, path, body=None):
        self.calls.append((method, path, body))
        if path == "/technicians":
            return 200, TECS
        if path.startswith("/orders?since="):
            since = int(path.split("=")[1])
            return 200, sorted((dict(o) for o in self.orders.values() if o["updated_at"] >= since),
                               key=lambda o: o["updated_at"])
        oid = int(path.rsplit("/", 1)[1])
        o = self.orders[oid]
        if method == "GET":
            return 200, dict(o)
        if body["version"] != o["version"]:
            return 409, {"error": "version_conflict", "conflictFields": ["estado"]}
        cambios = {k: v for k, v in body.items() if k not in ("version", "device", "updatedAt")}
        if "technician_id" in cambios:
            cambios["technician_code"] = next((t["technician_code"] for t in TECS if t["id"] == cambios["technician_id"]), None)
        self.cambia(oid, **cambios)
        return 200, {"id": oid, "version": o["version"]}


@pytest.fixture
def db(app):
    c = sqlite3.connect(app.config["DATABASE"], isolation_level=None)
    c.row_factory = sqlite3.Row
    QB.preparar(c)
    yield c
    c.close()


def job(db, jid):
    return db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()


def test_orden_del_customer_llega_al_control_con_su_id(db):
    api, st = FakeApi(), {}
    api.nueva(7, descripcion="secadora no calienta (ficticio)")
    assert QB.ciclo(db, api, st) == (0, 1)
    j = job(db, 7)
    assert j["origin"] == "CUSTOMER" and j["estado"] == "PENDIENTE" and "secadora" in j["descripcion"]
    # los técnicos QA quedan disponibles para el selector del Control
    codes = {r[0] for r in db.execute("SELECT technician_code FROM users WHERE role='TECHNICIAN'")}
    assert {"QA-T01", "QA-T02"} <= codes
    # repetir el ciclo no duplica ni cambia nada
    assert QB.ciclo(db, api, st) == (0, 0)
    assert db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 1


def test_asignacion_del_control_sube_con_version_y_uuid(db):
    api, st = FakeApi(), {}
    api.nueva(7)
    QB.ciclo(db, api, st)
    J.aplicar(db, job(db, 7), {"technicianId": "QA-T01", "estadoDispatch": "ASSIGNED"}, "desp_t", "control",
              version_base=1)
    assert QB.ciclo(db, api, st) == (1, 0)
    patch = [c for c in api.calls if c[0] == "PATCH"][-1]
    assert patch[1] == "/orders/7" and patch[2]["version"] == 1
    assert patch[2]["technician_id"] == "uuid-t1" and patch[2]["estado_dispatch"] == "ASIGNADO"
    assert api.orders[7]["technician_code"] == "QA-T01" and api.orders[7]["version"] == 2
    # el eco de su propio cambio no vuelve a subir ni crea versiones locales nuevas
    v = job(db, 7)["version"]
    assert QB.ciclo(db, api, st) == (0, 0) and job(db, 7)["version"] == v


def test_cambio_del_tecnico_baja_al_control(db):
    api, st = FakeApi(), {}
    api.nueva(7, technician_id="uuid-t1", technician_code="QA-T01", estado_dispatch="ASIGNADO")
    QB.ciclo(db, api, st)
    assert job(db, 7)["estado_dispatch"] == "ASSIGNED"
    api.cambia(7, estado="EN RUTA")
    assert QB.ciclo(db, api, st) == (0, 1)
    assert job(db, 7)["estado"] == "EN RUTA"
    hist = db.execute("SELECT actor FROM job_history WHERE job_id = 7 AND field = 'estado' ORDER BY id DESC").fetchone()
    assert hist["actor"] == QB.ACTOR


def test_conflicto_gana_el_servidor_y_queda_registrado(db):
    api, st = FakeApi(), {}
    api.nueva(7)
    QB.ciclo(db, api, st)
    J.aplicar(db, job(db, 7), {"estado": "CANCELADO"}, "desp_t", "control", version_base=1)
    api.cambia(7, estado="EN RUTA")                 # el técnico cambió antes de que el Control subiera
    QB.ciclo(db, api, st)
    assert job(db, 7)["estado"] == "EN RUTA" and api.orders[7]["estado"] == "EN RUTA"
    log = [r["tipo"] for r in db.execute("SELECT tipo FROM qa_bridge_log")]
    assert "SUBIDA_CONFLICTO" in log


def test_tecnico_que_no_existe_en_qa_no_se_sube(db):
    api, st = FakeApi(), {}
    api.nueva(7)
    QB.ciclo(db, api, st)
    J.aplicar(db, job(db, 7), {"technicianId": "tec-t-01"}, "desp_t", "control", version_base=1)
    QB.ciclo(db, api, st)
    assert not [c for c in api.calls if c[0] == "PATCH"]
    assert db.execute("SELECT 1 FROM qa_bridge_log WHERE tipo = 'SUBIDA_OMITIDA'").fetchone()


def test_config_rechaza_service_role(tmp_path):
    (tmp_path / "qa_bridge.json").write_text(json.dumps({"url": "https://x.supabase.co", "service_role_key": "k"}))
    with pytest.raises(ValueError):
        QB.leer_config(str(tmp_path))
    (tmp_path / "qa_bridge.json").write_text(json.dumps({"url": "https://x.supabase.co"}))
    assert QB.leer_config(str(tmp_path)) is None
