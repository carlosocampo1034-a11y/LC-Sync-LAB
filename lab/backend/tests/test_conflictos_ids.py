"""T5 conflictos (ambas versiones conservadas, resolución por despacho) e ids multi-dispositivo."""
import sqlite3
import threading

from conftest import PW, h, registrar_cliente, token
from test_lab_backend import _db, _job_dev, _orden

JID = 1791974954591


def _setup(client, toks):
    """Orden del teléfono (v1) + despacho cambia las notas (v2) → el técnico edita con base v1."""
    client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [_job_dev(JID, 100, notas="original")]},
                headers=h(toks["tec"]))
    r = client.patch(f"/api/v2/dispatch/jobs/{JID}", json={"version": 1, "campos": {"notas": "DESPACHO: llamar antes"}},
                     headers=h(toks["desp"]))
    assert r.status_code == 200


def _push_tecnico(client, toks, upd, **campos):
    d = {"id": JID, "updatedAt": upd, "version": 1}
    d.update(campos)
    return client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [d]},
                       headers=h(toks["tec"])).get_json()["results"][0]


def test_conflicto_conserva_ambas_versiones_y_aplica_lo_seguro(app, client, toks):
    _setup(client, toks)
    r = _push_tecnico(client, toks, 200, notas="TECNICO: compresor revisado", estado="EN RUTA")
    assert r["status"] == "CONFLICT" and r["conflictFields"] == ["notas"] and len(r["conflictIds"]) == 1
    assert r["server"]["estado"] == "EN RUTA", "el estado (sin conflicto) sí se aplica"
    assert r["server"]["notas"] == "DESPACHO: llamar antes", "la nota de despacho no se pisa"
    assert r["server"]["conflictosPendientes"] == 1
    c = client.get("/api/v2/dispatch/conflicts", headers=h(toks["desp"])).get_json()["conflicts"][0]
    assert c["valorServidor"] == "DESPACHO: llamar antes" and c["valorTecnico"] == "TECNICO: compresor revisado"
    assert c["estado"] == "PENDIENTE"


def test_reintentos_no_duplican_conflictos(app, client, toks):
    _setup(client, toks)
    for upd in (200, 201, 202):          # el teléfono reintenta con marcas nuevas
        _push_tecnico(client, toks, upd, notas="TECNICO: compresor revisado")
    assert _db(app).execute("SELECT COUNT(*) FROM job_conflicts").fetchone()[0] == 1


def test_resolver_tecnico_servidor_y_combinar(app, client, toks):
    _setup(client, toks)
    cid = _push_tecnico(client, toks, 200, notas="TECNICO: compresor revisado")["conflictIds"][0]
    v = client.get(f"/api/v2/jobs/{JID}", headers=h(toks["desp"])).get_json()["version"]
    assert client.post(f"/api/v2/dispatch/conflicts/{cid}/resolve", json={"decision": "TECNICO", "version": v - 1},
                       headers=h(toks["desp"])).status_code == 409, "bloqueo optimista"
    r = client.post(f"/api/v2/dispatch/conflicts/{cid}/resolve", json={"decision": "COMBINAR", "version": v,
                    "valor": "DESPACHO: llamar antes / TECNICO: compresor revisado"}, headers=h(toks["desp"])).get_json()
    assert r["orden"]["notas"] == "DESPACHO: llamar antes / TECNICO: compresor revisado"
    assert r["conflicto"]["estado"] == "RESUELTO" and r["conflicto"]["resolucion"] == "COMBINADO"
    assert r["conflicto"]["valorServidor"] == "DESPACHO: llamar antes", "los originales siguen recuperables"
    assert r["conflicto"]["valorTecnico"] == "TECNICO: compresor revisado"
    assert client.post(f"/api/v2/dispatch/conflicts/{cid}/resolve", json={"decision": "SERVIDOR",
                       "version": r["orden"]["version"]}, headers=h(toks["desp"])).status_code == 409


def test_resolver_tecnico_aplica_valor_del_telefono_y_el_telefono_lo_recibe(client, toks):
    _setup(client, toks)
    cid = _push_tecnico(client, toks, 200, notas="TECNICO gana")["conflictIds"][0]
    v = client.get(f"/api/v2/jobs/{JID}", headers=h(toks["desp"])).get_json()["version"]
    r = client.post(f"/api/v2/dispatch/conflicts/{cid}/resolve", json={"decision": "TECNICO", "version": v},
                    headers=h(toks["desp"])).get_json()
    assert r["orden"]["notas"] == "TECNICO gana" and r["orden"]["conflictosPendientes"] == 0
    p = client.get("/api/v2/sync/pull?since=0", headers=h(toks["tec"])).get_json()
    assert next(j for j in p["jobs"] if j["id"] == JID)["notas"] == "TECNICO gana"


def test_conflicto_en_codigo_de_acceso_cifrado_y_oculto(app, client, toks):
    client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [_job_dev(JID, 100)]}, headers=h(toks["tec"]))
    client.patch(f"/api/v2/dispatch/jobs/{JID}", json={"version": 1, "campos": {"codigoAcceso": "1111"}},
                 headers=h(toks["desp"]))
    r = _push_tecnico(client, toks, 200, codigoAcceso="2222")
    assert r["status"] == "CONFLICT" and r["conflictFields"] == ["codigo_acceso_enc"]
    raw = _db(app).execute("SELECT server_value, client_value FROM job_conflicts").fetchone()
    assert "1111" not in (raw[0] or "") and "2222" not in (raw[1] or ""), "cifrados en reposo"
    c = client.get("/api/v2/dispatch/conflicts", headers=h(toks["desp"])).get_json()["conflicts"][0]
    assert c["valorServidor"] == "1111" and c["valorTecnico"] == "2222"
    tec = client.get(f"/api/v2/jobs/{JID}", headers=h(toks["tec2"]))
    assert tec.status_code == 404


def test_despacho_nunca_pisa_por_version_vieja(client, toks):
    j = _orden(client, toks["desp"])
    client.patch(f"/api/v2/dispatch/jobs/{j['id']}", json={"version": 1, "campos": {"notas": "A"}}, headers=h(toks["desp"]))
    r = client.patch(f"/api/v2/dispatch/jobs/{j['id']}", json={"version": 1, "campos": {"notas": "B"}},
                     headers=h(toks["desp"]))
    assert r.status_code == 409
    assert client.get(f"/api/v2/jobs/{j['id']}", headers=h(toks["desp"])).get_json()["notas"] == "A"


# ── identificadores ─────────────────────────────────────────────────────────

def test_cada_dispositivo_recibe_su_espacio_de_ids(client):
    a = client.post("/api/v2/auth/login", json={"username": "tec_t", "password": PW, "deviceId": "tel-1"}).get_json()
    b = client.post("/api/v2/auth/login", json={"username": "tec_t2", "password": PW, "deviceId": "tel-2"}).get_json()
    a2 = client.post("/api/v2/auth/login", json={"username": "tec_t", "password": PW, "deviceId": "tel-1"}).get_json()
    assert a["idBase"] != b["idBase"] and a2["idBase"] == a["idBase"], "estable por dispositivo"
    assert a["idLimite"] <= b["idBase"] or b["idLimite"] <= a["idBase"], "espacios disjuntos"


def test_dos_tecnicos_crean_a_la_vez_sin_colision(app, client):
    ta = client.post("/api/v2/auth/login", json={"username": "tec_t", "password": PW, "deviceId": "tel-1"}).get_json()
    tb = client.post("/api/v2/auth/login", json={"username": "tec_t2", "password": PW, "deviceId": "tel-2"}).get_json()
    for t, dev in ((ta, "tel-1"), (tb, "tel-2")):
        lote = [_job_dev(t["idBase"] + i, 10 + i, customerLocal={"localId": i, "nombre": f"C{dev}{i}"}) for i in range(1, 6)]
        r = client.post("/api/v2/sync/push", json={"deviceId": dev, "jobs": lote}, headers=h(t["token"])).get_json()
        assert all(x["status"] == "ACCEPTED" for x in r["results"]), r
    assert _db(app).execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 10
    # un teléfono no puede usar el espacio de otro
    r = client.post("/api/v2/sync/push", json={"deviceId": "tel-1", "jobs": [_job_dev(tb["idBase"] + 99, 1)]},
                    headers=h(ta["token"])).get_json()
    assert r["results"][0]["status"] == "REJECTED" and "ID_FUERA_DE_RANGO" in r["results"][0]["reason"]


def test_colision_de_id_historico_entre_dispositivos_se_rechaza(app, client):
    ta = client.post("/api/v2/auth/login", json={"username": "tec_t", "password": PW, "deviceId": "tel-1"}).get_json()
    tb = client.post("/api/v2/auth/login", json={"username": "tec_t", "password": PW, "deviceId": "tel-2"}).get_json()
    legacy = 1791999999999   # mismo milisegundo en dos teléfonos (ids históricos por reloj)
    client.post("/api/v2/sync/push", json={"deviceId": "tel-1", "jobs": [_job_dev(legacy, 1, cliente="De tel-1")]},
                headers=h(ta["token"]))
    r = client.post("/api/v2/sync/push", json={"deviceId": "tel-2", "jobs": [_job_dev(legacy, 2, cliente="De tel-2")]},
                    headers=h(tb["token"])).get_json()["results"][0]
    assert r["status"] == "REJECTED" and r["reason"].startswith("ID_EN_USO")
    assert _db(app).execute("SELECT cliente FROM jobs WHERE id = ?", (legacy,)).fetchone()[0] == "De tel-1"


def test_clientes_y_despacho_concurrentes_ids_unicos(app):
    """Hilos reales: 6 clientes × 3 solicitudes + despacho creando órdenes a la vez."""
    ids, errores = [], []
    lock = threading.Lock()

    def cliente(n):
        c = app.test_client()
        tok = registrar_cliente(c, f"cli_conc_{n}", f"Cliente Concurrente {n}")
        pid = c.post("/api/v2/customer/properties", json={"direccion": f"{n} Calle Concurrente"},
                     headers=h(tok)).get_json()["id"]
        for k in range(3):
            r = c.post("/api/v2/customer/requests", json={"propertyId": pid, "descripcion": f"Solicitud concurrente {n}-{k}"},
                       headers=h(tok))
            with lock:
                (ids.append(r.get_json()["id"]) if r.status_code == 201 else errores.append(r.status_code))

    def despacho():
        c = app.test_client()
        tok = token(c, "desp_t")
        for k in range(6):
            r = c.post("/api/v2/dispatch/jobs", json={"cliente": "Desp", "direccion": "1 Calle",
                                                      "descripcion": f"Orden de despacho {k}"}, headers=h(tok))
            with lock:
                (ids.append(r.get_json()["id"]) if r.status_code == 201 else errores.append(r.status_code))

    hilos = [threading.Thread(target=cliente, args=(n,)) for n in range(6)] + [threading.Thread(target=despacho)]
    for t in hilos:
        t.start()
    for t in hilos:
        t.join()
    assert not errores, errores
    assert len(ids) == 24 and len(set(ids)) == 24
    assert sqlite3.connect(app.config["DATABASE"]).execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 24


def test_registro_de_dispositivo_con_sesion_existente(client, toks):
    a = client.post("/api/v2/sync/device", json={"deviceId": "dev-A"}, headers=h(toks["tec"])).get_json()
    b = client.post("/api/v2/sync/device", json={"deviceId": "dev-A"}, headers=h(toks["tec"])).get_json()
    c = client.post("/api/v2/sync/device", json={"deviceId": "dev-B"}, headers=h(toks["tec2"])).get_json()
    assert a == b, "idempotente"
    assert a["idLimite"] <= c["idBase"] or c["idLimite"] <= a["idBase"]
    login = client.post("/api/v2/auth/login", json={"username": "tec_t", "password": PW, "deviceId": "dev-A"}).get_json()
    assert login["idBase"] == a["idBase"], "mismo espacio que por login"
    assert client.post("/api/v2/sync/device", json={"deviceId": "x"}, headers=h(toks["desp"])).status_code == 403


def test_resolver_servidor_avisa_al_telefono_por_pull(client, toks):
    """Mantener lo del centro de mando no cambia la versión, pero el pull debe reenviar la orden con 0 pendientes."""
    _setup(client, toks)
    r = _push_tecnico(client, toks, 200, notas="TECNICO pierde")
    assert r["server"]["conflictosPendientes"] == 1
    cursor = client.get("/api/v2/sync/pull?since=0", headers=h(toks["tec"])).get_json()["cursor"]
    v = r["server"]["version"]
    client.post(f"/api/v2/dispatch/conflicts/{r['conflictIds'][0]}/resolve", json={"decision": "SERVIDOR", "version": v},
                headers=h(toks["desp"]))
    p = client.get(f"/api/v2/sync/pull?since={cursor}", headers=h(toks["tec"])).get_json()
    j = next(x for x in p["jobs"] if x["id"] == JID)
    assert j["conflictosPendientes"] == 0 and j["version"] == v and j["notas"] == "DESPACHO: llamar antes"
