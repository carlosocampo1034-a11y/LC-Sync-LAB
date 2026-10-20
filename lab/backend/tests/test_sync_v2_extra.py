"""Sync v2: work items, borrados, reasignación, edición de despacho y fallos simulados de laboratorio."""
import json
import os
import sqlite3

import pytest

from conftest import h
from test_lab_backend import _db, _job_dev, _orden

JID = 1791974954591


def _crear_job(client, toks):
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [_job_dev(JID, 100)]}, headers=h(toks["tec"]))
    assert r.get_json()["results"][0]["status"] == "ACCEPTED"


def _wi(lid, upd, **kw):
    d = {"id": lid, "jobId": JID, "tipo": "LABOR", "descripcion": "Mano de obra ficticia", "cantidad": 1.5,
         "precioUnitario": 80.0, "updatedAt": upd}
    d.update(kw)
    return d


def test_work_items_upsert_ack_e_idempotencia(app, client, toks):
    _crear_job(client, toks)
    body = {"deviceId": "dev-A", "jobs": [], "work_items": [_wi(11, 500), _wi(12, 501, tipo="INVENTADO")]}
    r = client.post("/api/v2/sync/push", json=body, headers=h(toks["tec"])).get_json()
    assert r["work_items"]["accepted_ids"] == [11] and r["work_items"]["rejected"][0]["id"] == 12
    client.post("/api/v2/sync/push", json=body, headers=h(toks["tec"]))   # reintento
    rows = _db(app).execute("SELECT local_id, total FROM job_work_items").fetchall()
    assert [tuple(x) for x in rows] == [(11, 120.0)], "sin duplicados"


def test_work_item_version_vieja_no_pisa_la_nueva(app, client, toks):
    _crear_job(client, toks)
    client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "work_items": [_wi(11, 900, cantidad=3)]},
                headers=h(toks["tec"]))
    client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "work_items": [_wi(11, 800, cantidad=1)]},
                headers=h(toks["tec"]))
    assert _db(app).execute("SELECT cantidad FROM job_work_items").fetchone()[0] == 3


def test_borrado_anula_sin_eliminar(app, client, toks):
    _crear_job(client, toks)
    client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "work_items": [_wi(11, 500)]}, headers=h(toks["tec"]))
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "deleted_work_item_ids": [11, 99]},
                    headers=h(toks["tec"])).get_json()
    assert r["work_items"]["accepted_ids"] == [11, 99]
    fila = _db(app).execute("SELECT anulado FROM job_work_items WHERE local_id = 11").fetchone()
    assert fila["anulado"] == 1, "se conserva anulado, no se borra"
    rep = client.get(f"/api/v2/jobs/{JID}/report", headers=h(toks["desp"])).get_json()
    assert rep["trabajo"] == []


def test_work_item_en_orden_ajena_rechazado(client, toks):
    _crear_job(client, toks)
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-B", "work_items": [_wi(11, 500)]},
                    headers=h(toks["tec2"])).get_json()
    assert r["work_items"]["accepted_ids"] == []


def test_pull_avisa_reasignacion_y_oculta_secretos(client, toks):
    j = _orden(client, toks["desp"], codigoAcceso="5555")
    client.post(f"/api/v2/dispatch/jobs/{j['id']}/assign", json={"technicianId": "tec-t-01"}, headers=h(toks["desp"]))
    p1 = client.get("/api/v2/sync/pull?since=0", headers=h(toks["tec"])).get_json()
    assert next(x for x in p1["jobs"] if x["id"] == j["id"])["codigoAcceso"] == "5555"
    client.post(f"/api/v2/dispatch/jobs/{j['id']}/assign", json={"technicianId": "tec-t-02"}, headers=h(toks["desp"]))
    p2 = client.get(f"/api/v2/sync/pull?since={p1['cursor']}", headers=h(toks["tec"])).get_json()
    x = next(x for x in p2["jobs"] if x["id"] == j["id"])
    assert x["technicianId"] == "tec-t-02" and x["codigoAcceso"] is None and x["codigoAccesoProtegido"] is True


def test_patch_despacho_bloqueo_optimista(client, toks):
    j = _orden(client, toks["desp"])
    ok = client.patch(f"/api/v2/dispatch/jobs/{j['id']}", json={"version": 1, "campos": {"notas": "Nota despacho"}},
                      headers=h(toks["desp"]))
    assert ok.status_code == 200 and ok.get_json()["notas"] == "Nota despacho" and ok.get_json()["version"] == 2
    assert client.patch(f"/api/v2/dispatch/jobs/{j['id']}", json={"version": 1, "campos": {"notas": "x"}},
                        headers=h(toks["desp"])).status_code == 409
    assert client.patch(f"/api/v2/dispatch/jobs/{j['id']}", json={"version": 2, "campos": {"technicianId": "x"}},
                        headers=h(toks["desp"])).status_code == 400, "asignar no es por PATCH"


@pytest.fixture
def faults(app):
    app.config["LAB_FAULTS"] = True
    ruta = os.path.join(os.path.dirname(app.config["DATABASE"]), "fault.json")
    def poner(**kw):
        json.dump(kw, open(ruta, "w"))
    return poner


def test_fallo_tras_guardar_y_reintento_sin_duplicar(app, client, toks, faults):
    faults(push_fail_after_commit=1)
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [_job_dev(JID, 100)]}, headers=h(toks["tec"]))
    assert r.status_code == 503, "la respuesta se 'pierde'"
    assert _db(app).execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 1, "pero el servidor ya guardó"
    # el teléfono reintenta con una marca nueva (nunca repite marca): no duplica ni sube versión
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [_job_dev(JID, 101)]}, headers=h(toks["tec"]))
    res = r.get_json()["results"][0]
    assert r.status_code == 200 and res["status"] == "ACCEPTED" and res["version"] == 1
    db = _db(app)
    assert db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM customers").fetchone()[0] == 1


def test_fallos_apagados_por_defecto(app, client, toks):
    ruta = os.path.join(os.path.dirname(app.config["DATABASE"]), "fault.json")
    json.dump({"push_fail_after_commit": 5}, open(ruta, "w"))
    assert app.config["LAB_FAULTS"] is False
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [_job_dev(JID, 100)]}, headers=h(toks["tec"]))
    assert r.status_code == 200


def test_ids_servidor_no_colisionan_con_ids_del_telefono(app, client, toks):
    # un teléfono antiguo creó una orden en el rango del servidor (antes de la corrección)
    from lc_lab.db import now_ms
    db = sqlite3.connect(app.config["DATABASE"])
    t = now_ms()
    db.execute("INSERT INTO jobs(id, version, seq, origin, estado, cliente, created_at, updated_at) VALUES "
               "(8000000000001, 1, 0, 'DEVICE', 'PENDIENTE', 'Antiguo', ?, ?)", (t, t))
    db.commit()
    j = _orden(client, toks["desp"])
    assert j["id"] == 8000000000002, "salta el id ocupado"
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [_job_dev(8000000000009, 1)]},
                    headers=h(toks["tec"])).get_json()
    assert r["results"][0]["status"] == "REJECTED" and "ID_FUERA_DE_RANGO" in r["results"][0]["reason"]


def test_edicion_en_transito_vuelve_a_valor_anterior_no_se_pierde(app, client, toks):
    """Técnico: EN REPARACIÓN (v2) → PAUSADO (v3, respuesta no confirmada en el teléfono) → EN REPARACIÓN
    enviado con base v2. Antes el servidor lo tomaba como 'sin cambio' y se quedaba en PAUSADO."""
    j = _orden(client, toks["desp"])
    client.post(f"/api/v2/dispatch/jobs/{j['id']}/assign", json={"technicianId": "tec-t-01"}, headers=h(toks["desp"]))
    push = lambda upd, est, ver: client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [
        {"id": j["id"], "updatedAt": upd, "estado": est, "version": ver}]}, headers=h(toks["tec"])).get_json()["results"][0]
    r = push(1, "EN REPARACIÓN", 2)
    assert r["status"] == "ACCEPTED" and r["server"]["estado"] == "EN REPARACIÓN"
    base = r["version"]
    assert push(2, "PAUSADO", base)["version"] == base + 1         # el teléfono no llega a confirmar esta
    r = push(3, "EN REPARACIÓN", base)                             # base vieja: su propio PAUSADO no confirmado
    assert r["status"] == "ACCEPTED" and r["server"]["estado"] == "EN REPARACIÓN", r
    assert _db(app).execute("SELECT estado FROM jobs WHERE id = ?", (j["id"],)).fetchone()[0] == "EN REPARACIÓN"


def test_accepted_devuelve_resultado_del_merge(client, toks):
    """Despacho cambia el teléfono del cliente; el técnico (base vieja) cambia el estado: el ACCEPTED trae ambos."""
    j = _orden(client, toks["desp"])
    client.post(f"/api/v2/dispatch/jobs/{j['id']}/assign", json={"technicianId": "tec-t-01"}, headers=h(toks["desp"]))
    client.patch(f"/api/v2/dispatch/jobs/{j['id']}", json={"version": 2, "campos": {"telefono": "(305) 555-0999"}},
                 headers=h(toks["desp"]))
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [
        {"id": j["id"], "updatedAt": 9, "estado": "EN RUTA", "telefono": None, "version": 2}]},
        headers=h(toks["tec"])).get_json()["results"][0]
    assert r["status"] == "ACCEPTED" and r["server"]["estado"] == "EN RUTA" and r["server"]["telefono"] == "(305) 555-0999"


def test_retraso_simulado_no_bloquea_otras_escrituras(app, client, toks, tmp_path):
    """Con un push lento en curso, despacho puede escribir (el retardo no retiene el bloqueo de escritura)."""
    import json as _j, os, threading, time
    app.config["LAB_FAULTS"] = True
    ruta = os.path.join(os.path.dirname(app.config["DATABASE"]), "fault.json")
    _j.dump({"push_delay_s": 3}, open(ruta, "w"))
    try:
        hecho = {}
        def lento():
            c = app.test_client()
            hecho["r"] = c.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [_job_dev(1791974954777, 1)]},
                                headers=h(toks["tec"])).status_code
        t = threading.Thread(target=lento); t.start(); time.sleep(0.5)
        t0 = time.time()
        r = client.post("/api/v2/dispatch/jobs", json={"cliente": "D", "direccion": "1 Calle",
                                                       "descripcion": "Orden durante push lento"}, headers=h(toks["desp"]))
        assert r.status_code == 201 and time.time() - t0 < 2, "despacho no debe esperar al push lento"
        t.join()
        assert hecho["r"] == 200
    finally:
        os.remove(ruta)


def _push_wi(client, toks, *wis):
    return client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "work_items": list(wis)},
                       headers=h(toks["tec"])).get_json()["work_items"]


def test_anulacion_con_marca_temporal_no_se_deshace_con_reintento_viejo(app, client, toks):
    _crear_job(client, toks)
    _push_wi(client, toks, _wi(11, 500), _wi(12, 500, descripcion="Otro trabajo previo"))
    _push_wi(client, toks, _wi(11, 600, cantidad=2))                      # edición
    r = _push_wi(client, toks, dict(_wi(11, 700, cantidad=2), anulado=True))   # anulación
    assert 11 in r["accepted_ids"]
    _push_wi(client, toks, _wi(11, 600, cantidad=2))                      # reintento atrasado de la edición
    _push_wi(client, toks, dict(_wi(11, 700, cantidad=2), anulado=True))   # reintento de la anulación
    rows = {x[0]: (x[1], x[2]) for x in _db(app).execute("SELECT local_id, anulado, cantidad FROM job_work_items")}
    assert rows[11] == (1, 2), "sigue anulado; el registro se conserva"
    assert rows[12][0] == 0, "otro trabajo no se ve afectado"
    assert _db(app).execute("SELECT COUNT(*) FROM job_work_items").fetchone()[0] == 2, "sin duplicados"
    assert _db(app).execute("SELECT COUNT(*) FROM audit_log WHERE action='WORK_ITEM_ANULADO'").fetchone()[0] >= 1


def test_creado_y_anulado_sin_conexion_queda_como_evidencia(app, client, toks):
    _crear_job(client, toks)
    r = _push_wi(client, toks, dict(_wi(13, 800), anulado=True))
    assert r["accepted_ids"] == [13]
    assert tuple(_db(app).execute("SELECT local_id, anulado FROM job_work_items").fetchone()) == (13, 1)
