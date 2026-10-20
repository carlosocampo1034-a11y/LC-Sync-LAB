"""Backend de laboratorio: cada módulo recibe, valida, persiste y devuelve. Datos ficticios."""
import base64
import io
import sqlite3
import time

from conftest import PW, h, registrar_cliente, token

HOY_MS = lambda: int(time.time() * 1000)  # noqa: E731
DIA = 86_400_000
JPEG = b"\xff\xd8\xff\xe0" + b"0" * 200
PNG = b"\x89PNG\r\n\x1a\n" + b"0" * 64


def _propiedad(client, ct, **kw):
    datos = {"direccion": "100 Calle Ficticia QA, Lab City", "tipoPropiedad": "Casa", "tipoAcceso": "Código puerta",
             "codigoAcceso": "4321", "mascota": "Perro ficticio", "contactoRespaldo": "(305) 555-0188"}
    datos.update(kw)
    r = client.post("/api/v2/customer/properties", json=datos, headers=h(ct))
    assert r.status_code == 201, r.get_json()
    return r.get_json()["id"]


def _solicitud(client, ct, pid, **kw):
    datos = {"propertyId": pid, "descripcion": "Nevera ficticia no enfría", "categoria": "ELECTRODOMESTICOS"}
    datos.update(kw)
    r = client.post("/api/v2/customer/requests", json=datos, headers=h(ct))
    assert r.status_code in (200, 201), r.get_json()
    return r.get_json()


def _db(app):
    c = sqlite3.connect(app.config["DATABASE"])
    c.row_factory = sqlite3.Row
    return c


# ── autenticación y roles ───────────────────────────────────────────────────

def test_login_correcto_y_fallido(client):
    assert client.post("/api/v2/auth/login", json={"username": "desp_t", "password": "mal"}).status_code == 401
    t = token(client, "desp_t")
    assert client.get("/api/v2/me", headers=h(t)).get_json()["role"] == "DISPATCHER"


def test_bloqueo_tras_cinco_fallos(client):
    for _ in range(5):
        client.post("/api/v2/auth/login", json={"username": "tec_t", "password": "mal"})
    r = client.post("/api/v2/auth/login", json={"username": "tec_t", "password": PW})
    assert r.status_code == 401 and "bloqueada" in r.get_json()["error"]


def test_sin_token_401_y_rol_incorrecto_403(client, toks):
    assert client.get("/api/v2/dispatch/board").status_code == 401
    assert client.get("/api/v2/dispatch/board", headers=h(toks["tec"])).status_code == 403
    assert client.get("/api/v2/dispatch/board", headers=h(toks["desp"])).status_code == 200


def test_registro_valida_y_no_duplica(client):
    assert client.post("/api/v2/auth/register", json={"username": "x", "password": PW, "nombre": "Abc"}).status_code == 400
    assert client.post("/api/v2/auth/register", json={"username": "cli1", "password": "corta", "nombre": "Abc"}).status_code == 400
    registrar_cliente(client, "cli1")
    r = client.post("/api/v2/auth/register", json={"username": "cli1", "password": PW, "nombre": "Otro"})
    assert r.status_code == 409


def test_contrasenas_no_en_claro(app, client):
    registrar_cliente(client)
    assert all(PW not in r[0] for r in _db(app).execute("SELECT password_hash FROM users"))


# ── portal del cliente ──────────────────────────────────────────────────────

def test_cliente_propiedad_equipo_y_solicitud_persisten(app, client):
    ct = registrar_cliente(client)
    pid = _propiedad(client, ct)
    r = client.post(f"/api/v2/customer/properties/{pid}/equipment", json={"nombre": "Nevera ficticia",
                                                                           "marca": "MarcaLab", "modelo": "N-1"}, headers=h(ct))
    eid = r.get_json()["id"]
    props = client.get("/api/v2/customer/properties", headers=h(ct)).get_json()["properties"]
    assert props[0]["equipos"][0]["nombre"] == "Nevera ficticia"
    j = _solicitud(client, ct, pid, equipmentId=eid, fechaPreferida=HOY_MS() + 2 * DIA)
    assert j["estado"] == "PENDIENTE" and j["origin"] == "CUSTOMER" and j["equipo"] == "Nevera ficticia"
    assert j["programadoPara"] is None, "la fecha preferida no es una cita confirmada"
    d = client.get(f"/api/v2/customer/requests/{j['id']}", headers=h(ct)).get_json()
    assert d["descripcion"] == "Nevera ficticia no enfría" and d["mascota"] == "Perro ficticio"


def test_solicitud_valida_campos(client):
    ct = registrar_cliente(client)
    pid = _propiedad(client, ct)
    r = client.post("/api/v2/customer/requests", json={"propertyId": pid, "descripcion": "corta"}, headers=h(ct))
    assert r.status_code == 400
    r = client.post("/api/v2/customer/requests", json={"propertyId": 999, "descripcion": "x" * 20}, headers=h(ct))
    assert r.status_code == 404
    r = client.post("/api/v2/customer/requests", json={"propertyId": pid, "descripcion": "x" * 20,
                                                       "fechaPreferida": HOY_MS() - 10 * DIA}, headers=h(ct))
    assert r.status_code == 400


def test_solicitud_idempotente_por_client_request_id(app, client):
    ct = registrar_cliente(client)
    pid = _propiedad(client, ct)
    a = _solicitud(client, ct, pid, clientRequestId="req-001")
    b = _solicitud(client, ct, pid, clientRequestId="req-001")
    assert a["id"] == b["id"]
    assert _db(app).execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 1


def test_cliente_no_ve_lo_ajeno(client):
    c1 = registrar_cliente(client, "cli_a", "Cliente A Ficticio")
    c2 = registrar_cliente(client, "cli_b", "Cliente B Ficticio")
    j = _solicitud(client, c1, _propiedad(client, c1))
    assert client.get(f"/api/v2/customer/requests/{j['id']}", headers=h(c2)).status_code == 404
    assert client.get(f"/api/v2/jobs/{j['id']}", headers=h(c2)).status_code == 404
    assert client.get("/api/v2/customer/requests", headers=h(c2)).get_json()["requests"] == []


# ── datos sensibles ─────────────────────────────────────────────────────────

def test_codigo_acceso_cifrado_y_solo_visible_a_quien_corresponde(app, client, toks):
    ct = registrar_cliente(client)
    j = _solicitud(client, ct, _propiedad(client, ct))
    raw = _db(app).execute("SELECT codigo_acceso_enc FROM jobs WHERE id = ?", (j["id"],)).fetchone()[0]
    assert raw and "4321" not in raw, "debe guardarse cifrado"
    assert client.get(f"/api/v2/jobs/{j['id']}", headers=h(toks["desp"])).get_json()["codigoAcceso"] == "4321"
    client.post(f"/api/v2/dispatch/jobs/{j['id']}/assign", json={"technicianId": "tec-t-01"}, headers=h(toks["desp"]))
    assert client.get(f"/api/v2/jobs/{j['id']}", headers=h(toks["tec"])).get_json()["codigoAcceso"] == "4321"
    assert client.get(f"/api/v2/jobs/{j['id']}", headers=h(toks["tec2"])).status_code == 404
    hist = _db(app).execute("SELECT new_value FROM job_history WHERE field='codigo_acceso_enc'").fetchall()
    assert all("4321" not in (r[0] or "") for r in hist), "el historial no guarda el código en claro"


# ── agenda y despacho ───────────────────────────────────────────────────────

def _orden(client, tok, **kw):
    d = {"cliente": "Cliente Agenda Ficticio", "direccion": "1 Calle Lab", "descripcion": "Revisión ficticia de prueba"}
    d.update(kw)
    r = client.post("/api/v2/dispatch/jobs", json=d, headers=h(tok))
    assert r.status_code == 201, r.get_json()
    return r.get_json()


def test_agenda_categorias(client, toks):
    t = toks["desp"]
    ids = {}
    for nombre, delta in (("hoy", 0), ("manana", 1), ("atrasado", -2), ("lejano", 30)):
        j = _orden(client, t)
        client.post(f"/api/v2/dispatch/jobs/{j['id']}/schedule", json={"programadoPara": HOY_MS() + delta * DIA},
                    headers=h(t))
        ids[nombre] = j["id"]
    ids["sinfecha"] = _orden(client, t)["id"]
    curso = _orden(client, t)["id"]
    client.post(f"/api/v2/jobs/{curso}/status", json={"estado": "EN RUTA"}, headers=h(t))
    hecho = _orden(client, t)["id"]
    for e in ("EN RUTA", "LLEGUÉ", "EN REPARACIÓN", "COMPLETADO"):
        client.post(f"/api/v2/jobs/{hecho}/status", json={"estado": e}, headers=h(t))
    b = client.get("/api/v2/dispatch/board", headers=h(t)).get_json()
    g = {k: {j["id"] for j in v} for k, v in b["grupos"].items()}
    assert ids["hoy"] in g["HOY"] and ids["manana"] in g["PROXIMOS"] and ids["atrasado"] in g["ATRASADOS"]
    assert ids["sinfecha"] in g["SIN_FECHA"] and curso in g["EN_CURSO"] and hecho in g["COMPLETADOS"]
    assert ids["lejano"] not in g["PROXIMOS"] and b["proximosFueraDeRango"] == 1, "no se oculta: se informa"
    b60 = client.get("/api/v2/dispatch/board?dias=60", headers=h(t)).get_json()
    assert ids["lejano"] in {j["id"] for j in b60["grupos"]["PROXIMOS"]}


def test_transiciones_de_estado(client, toks):
    j = _orden(client, toks["desp"])
    r = client.post(f"/api/v2/jobs/{j['id']}/status", json={"estado": "COMPLETADO"}, headers=h(toks["desp"]))
    assert r.status_code == 409, "no se completa sin pasar por el trabajo"
    assert client.post(f"/api/v2/jobs/{j['id']}/status", json={"estado": "INVENTADO"},
                       headers=h(toks["desp"])).status_code == 400


def test_asignar_tecnico_inexistente(client, toks):
    j = _orden(client, toks["desp"])
    assert client.post(f"/api/v2/dispatch/jobs/{j['id']}/assign", json={"technicianId": "nadie"},
                       headers=h(toks["desp"])).status_code == 404


# ── sincronización v2 ───────────────────────────────────────────────────────

def _job_dev(jid, upd, **kw):
    d = {"id": jid, "updatedAt": upd, "cliente": "QA Ficticio", "telefono": "(305) 555-0199",
         "direccion": "200 Avenida Ficticia QA", "descripcion": "Equipo ficticio QA", "estado": "PENDIENTE",
         "programadoPara": HOY_MS() + DIA, "zonaHoraria": "America/New_York", "tipoAcceso": "Garaje",
         "codigoAcceso": "9988", "customerLocal": {"localId": 7, "nombre": "QA Ficticio", "telefono": "(305) 555-0199"},
         "propertyLocal": {"localId": 7, "direccion": "200 Avenida Ficticia QA", "tipoPropiedad": "Casa"}}
    d.update(kw)
    return d


def test_push_crea_y_confirma_por_trabajo(app, client, toks):
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [
        _job_dev(1791974954591, 100), _job_dev(1791974954592, 101, estado="NO_EXISTE")]}, headers=h(toks["tec"]))
    b = r.get_json()
    assert r.status_code == 200
    res = {x["id"]: x for x in b["results"]}
    assert res[1791974954591]["status"] == "ACCEPTED" and res[1791974954591]["version"] == 1
    assert res[1791974954591]["clientUpdatedAt"] == 100
    assert res[1791974954592]["status"] == "REJECTED", "HTTP 200 no significa todo aceptado"
    assert b["accepted_job_ids"] == [1791974954591] and b["rejected_job_ids"] == [1791974954592]
    db = _db(app)
    fila = db.execute("SELECT * FROM jobs WHERE id = 1791974954591").fetchone()
    assert fila["direccion"] == "200 Avenida Ficticia QA" and fila["technician_code"] == "tec-t-01"
    assert fila["customer_id"] and fila["property_id"], "cliente y propiedad vinculados"
    assert "9988" not in fila["codigo_acceso_enc"]


def test_push_idempotente(app, client, toks):
    body = {"deviceId": "dev-A", "jobs": [_job_dev(1791974954591, 100)]}
    a = client.post("/api/v2/sync/push", json=body, headers=h(toks["tec"])).get_json()
    b = client.post("/api/v2/sync/push", json=body, headers=h(toks["tec"])).get_json()
    assert a["results"][0]["version"] == b["results"][0]["version"] == 1 and b["results"][0]["replay"]
    db = _db(app)
    assert db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM customers").fetchone()[0] == 1, "sin clientes duplicados"


def test_merge_sin_conflicto_despacho_y_tecnico(app, client, toks):
    """Despacho reprograma (v2) mientras el técnico, offline con v1, cambia el estado → ambos se conservan."""
    jid = 1791974954591
    client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [_job_dev(jid, 100)]}, headers=h(toks["tec"]))
    nueva_cita = HOY_MS() + 3 * DIA
    client.post(f"/api/v2/dispatch/jobs/{jid}/schedule", json={"programadoPara": nueva_cita}, headers=h(toks["desp"]))
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [
        _job_dev(jid, 200, estado="EN RUTA", version=1)]}, headers=h(toks["tec"])).get_json()
    assert r["results"][0]["status"] == "ACCEPTED", r
    fila = _db(app).execute("SELECT estado, programado_para, version FROM jobs WHERE id = ?", (jid,)).fetchone()
    assert fila["estado"] == "EN RUTA" and fila["programado_para"] == nueva_cita and fila["version"] == 3


def test_conflicto_mismo_campo(app, client, toks):
    jid = 1791974954591
    client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [_job_dev(jid, 100)]}, headers=h(toks["tec"]))
    db_tok = toks["desp"]
    # despacho edita la descripción desde el centro de mando (v2)
    from lc_lab import jobs as J  # noqa
    with client.application.app_context():
        from lc_lab.db import get_db
        db = get_db()
        row = db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone()
        J.aplicar(db, row, {"descripcion": "Descripción corregida por despacho"}, "desp_t", "control")
        db.commit()
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [
        _job_dev(jid, 300, descripcion="Descripción cambiada en el teléfono", version=1)]},
        headers=h(toks["tec"])).get_json()
    res = r["results"][0]
    assert res["status"] == "CONFLICT" and res["conflictFields"] == ["descripcion"]
    assert res["server"]["descripcion"] == "Descripción corregida por despacho"
    assert r["conflict_job_ids"] == [jid] and db_tok


def test_tecnico_no_toca_orden_ajena_ni_reprograma(app, client, toks):
    j = _orden(client, toks["desp"])
    client.post(f"/api/v2/dispatch/jobs/{j['id']}/assign", json={"technicianId": "tec-t-01"}, headers=h(toks["desp"]))
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-B", "jobs": [
        {"id": j["id"], "updatedAt": 5, "estado": "EN RUTA", "version": 2}]}, headers=h(toks["tec2"])).get_json()
    assert r["results"][0]["status"] == "REJECTED"
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [
        {"id": j["id"], "updatedAt": 6, "programadoPara": 1, "technicianId": "tec-t-02", "version": 2}]},
        headers=h(toks["tec"])).get_json()
    fila = _db(app).execute("SELECT technician_code, programado_para FROM jobs WHERE id = ?", (j["id"],)).fetchone()
    assert fila["technician_code"] == "tec-t-01" and fila["programado_para"] is None


def test_pull_entrega_asignadas_con_cursor(client, toks):
    j = _orden(client, toks["desp"])
    client.post(f"/api/v2/dispatch/jobs/{j['id']}/assign", json={"technicianId": "tec-t-01"}, headers=h(toks["desp"]))
    p = client.get("/api/v2/sync/pull?since=0", headers=h(toks["tec"])).get_json()
    assert [x["id"] for x in p["jobs"]] == [j["id"]] and p["jobs"][0]["version"] == 2
    p2 = client.get(f"/api/v2/sync/pull?since={p['cursor']}", headers=h(toks["tec"])).get_json()
    assert p2["jobs"] == []
    assert client.get("/api/v2/sync/pull?since=0", headers=h(toks["tec2"])).get_json()["jobs"] == []


def test_push_fotos_y_eventos_idempotentes(app, client, toks):
    jid = 1791974954591
    foto = {"id": 55, "jobId": jid, "filename": "f.jpg", "mimeType": "image/jpeg",
            "base64Data": base64.b64encode(JPEG).decode()}
    ev = {"clientEventId": "ev-1", "jobId": jid, "tipo": "MEDICION", "payload": {"nombre": "Delta T", "valor": 18.5}}
    body = {"deviceId": "dev-A", "jobs": [_job_dev(jid, 100)], "photos": [foto], "events": [ev]}
    a = client.post("/api/v2/sync/push", json=body, headers=h(toks["tec"])).get_json()
    client.post("/api/v2/sync/push", json=body, headers=h(toks["tec"]))
    assert a["photos"]["accepted_ids"] == [55] and a["events"]["accepted_ids"] == ["ev-1"]
    db = _db(app)
    assert db.execute("SELECT COUNT(*) FROM photos").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM job_events").fetchone()[0] == 1


# ── fotos, eventos, aprobaciones, repuestos, factura, reporte ───────────────

def test_foto_valida_tipo_y_contenido(client):
    ct = registrar_cliente(client)
    j = _solicitud(client, ct, _propiedad(client, ct))
    falso = client.post(f"/api/v2/jobs/{j['id']}/photos", data={"foto": (io.BytesIO(b"no soy imagen"), "x.jpg",
                                                                          "image/jpeg")}, headers=h(ct))
    assert falso.status_code == 400
    pdf = client.post(f"/api/v2/jobs/{j['id']}/photos", data={"foto": (io.BytesIO(b"%PDF"), "x.pdf",
                                                                        "application/pdf")}, headers=h(ct))
    assert pdf.status_code == 400
    ok = client.post(f"/api/v2/jobs/{j['id']}/photos", data={"foto": (io.BytesIO(JPEG), "nevera.jpg", "image/jpeg")},
                     headers=h(ct))
    assert ok.status_code == 201
    img = client.get(f"/api/v2/photos/{ok.get_json()['id']}", headers=h(ct))
    assert img.status_code == 200 and img.data == JPEG


def test_medicion_valida_numero(client, toks):
    j = _orden(client, toks["desp"])
    r = client.post(f"/api/v2/jobs/{j['id']}/events", json={"tipo": "MEDICION", "payload": {"nombre": "Voltaje",
                                                                                           "valor": "alto"}},
                    headers=h(toks["desp"]))
    assert r.status_code == 400


def test_aprobacion_requiere_firma_y_no_se_decide_dos_veces(client, toks):
    ct = registrar_cliente(client)
    j = _solicitud(client, ct, _propiedad(client, ct))
    a = client.post(f"/api/v2/jobs/{j['id']}/approvals", json={"tipo": "PROPUESTA", "descripcion": "Cambiar capacitor",
                                                               "monto": 120.0}, headers=h(toks["desp"])).get_json()
    assert client.post(f"/api/v2/approvals/{a['id']}/decide", json={"decision": "APROBADA", "firmante": "Cliente F"},
                       headers=h(ct)).status_code == 400
    r = client.post(f"/api/v2/approvals/{a['id']}/decide", json={"decision": "APROBADA", "firmante": "Cliente F",
                                                                 "firmaPngBase64": base64.b64encode(PNG).decode()},
                    headers=h(ct))
    assert r.status_code == 200 and r.get_json()["firmaSha256"]
    assert client.post(f"/api/v2/approvals/{a['id']}/decide", json={"decision": "RECHAZADA"},
                       headers=h(ct)).status_code == 409


def test_repuestos_permisos_y_stock(client, toks):
    j = _orden(client, toks["desp"])
    client.post(f"/api/v2/dispatch/jobs/{j['id']}/assign", json={"technicianId": "tec-t-01"}, headers=h(toks["desp"]))
    rp = client.post(f"/api/v2/jobs/{j['id']}/parts", json={"sku": "T-CAP", "cantidad": 2},
                     headers=h(toks["tec"])).get_json()
    assert client.post(f"/api/v2/job_parts/{rp['id']}/status", json={"estado": "APROBADO"},
                       headers=h(toks["tec"])).status_code == 403
    assert client.post(f"/api/v2/job_parts/{rp['id']}/status", json={"estado": "APROBADO"},
                       headers=h(toks["desp"])).status_code == 200
    assert client.post(f"/api/v2/job_parts/{rp['id']}/status", json={"estado": "USADO"},
                       headers=h(toks["tec"])).status_code == 200
    rp2 = client.post(f"/api/v2/jobs/{j['id']}/parts", json={"sku": "T-CAP", "cantidad": 2},
                      headers=h(toks["tec"])).get_json()
    client.post(f"/api/v2/job_parts/{rp2['id']}/status", json={"estado": "APROBADO"}, headers=h(toks["desp"]))
    assert client.post(f"/api/v2/job_parts/{rp2['id']}/status", json={"estado": "USADO"},
                       headers=h(toks["tec"])).status_code == 409, "stock 3: el segundo uso de 2 no cabe"


def test_factura_y_visibilidad_cliente(client, toks):
    ct = registrar_cliente(client)
    j = _solicitud(client, ct, _propiedad(client, ct))
    inv = client.post(f"/api/v2/jobs/{j['id']}/invoice", json={"manoDeObra": [{"horas": 1.5, "tarifa": 80}]},
                      headers=h(toks["desp"])).get_json()
    assert inv["subtotal"] == 120.0 and inv["impuesto"] == 8.4 and inv["total"] == 128.4
    assert client.get(f"/api/v2/customer/requests/{j['id']}", headers=h(ct)).get_json()["factura"] is None
    client.post(f"/api/v2/invoices/{inv['id']}/emit", headers=h(toks["desp"]))
    assert client.get(f"/api/v2/customer/requests/{j['id']}", headers=h(ct)).get_json()["factura"]["total"] == 128.4


def test_replay_de_conflicto_incluye_copia_del_servidor(client, toks):
    jid = 1791974954591
    client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [_job_dev(jid, 100)]}, headers=h(toks["tec"]))
    with client.application.app_context():
        from lc_lab import jobs as J
        from lc_lab.db import get_db
        db = get_db()
        J.aplicar(db, db.execute("SELECT * FROM jobs WHERE id = ?", (jid,)).fetchone(),
                  {"descripcion": "Cambiada por despacho"}, "desp_t", "control")
        db.commit()
    body = {"deviceId": "dev-A", "jobs": [_job_dev(jid, 300, descripcion="Cambiada en teléfono", version=1)]}
    client.post("/api/v2/sync/push", json=body, headers=h(toks["tec"]))
    again = client.post("/api/v2/sync/push", json=body, headers=h(toks["tec"])).get_json()["results"][0]
    assert again["status"] == "CONFLICT" and again["replay"] and again["server"]["descripcion"] == "Cambiada por despacho"


def test_sync_acepta_varios_pasos_offline_y_registra_intermedios(app, client, toks):
    j = _orden(client, toks["desp"])
    client.post(f"/api/v2/dispatch/jobs/{j['id']}/assign", json={"technicianId": "tec-t-01"}, headers=h(toks["desp"]))
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [
        {"id": j["id"], "updatedAt": 77, "estado": "EN REPARACIÓN", "version": 2}]}, headers=h(toks["tec"])).get_json()
    assert r["results"][0]["status"] == "ACCEPTED", r
    inter = _db(app).execute("SELECT new_value, source FROM job_history WHERE job_id = ? AND field = 'estado_intermedio'",
                             (j["id"],)).fetchone()
    assert inter["new_value"] == "EN RUTA → LLEGUÉ" and inter["source"] == "android-inferido"
    # pero no se puede saltar a un estado imposible (COMPLETADO → PENDIENTE desde el teléfono)
    for e in ("COMPLETADO",):
        client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [
            {"id": j["id"], "updatedAt": 78, "estado": e, "version": 3}]}, headers=h(toks["tec"]))
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [
        {"id": j["id"], "updatedAt": 79, "estado": "PENDIENTE", "version": 4}]}, headers=h(toks["tec"])).get_json()
    assert r["results"][0]["status"] == "REJECTED"


def test_api_despacho_bloqueo_optimista(client, toks):
    j = _orden(client, toks["desp"])
    ok = client.post(f"/api/v2/dispatch/jobs/{j['id']}/assign", json={"technicianId": "tec-t-01", "version": 1},
                     headers=h(toks["desp"]))
    assert ok.status_code == 200
    viejo = client.post(f"/api/v2/dispatch/jobs/{j['id']}/assign", json={"technicianId": "tec-t-02", "version": 1},
                        headers=h(toks["desp"]))
    assert viejo.status_code == 409 and viejo.get_json()["version"] == 2
    assert client.get(f"/api/v2/jobs/{j['id']}", headers=h(toks["desp"])).get_json()["technicianId"] == "tec-t-01"


def test_foto_con_client_photo_id_no_se_duplica_en_reintentos(app, client):
    ct = registrar_cliente(client, "cli_fotos", "Cliente Fotos Ficticio")
    pid = _propiedad(client, ct)
    jid = _solicitud(client, ct, pid)["id"]
    def subir(cpid, contenido=PNG):
        datos = {"categoria": "BEFORE", "foto": (io.BytesIO(contenido), "foto.png", "image/png")}
        if cpid is not None:
            datos["clientPhotoId"] = str(cpid)
        return client.post(f"/api/v2/jobs/{jid}/photos", data=datos, headers=h(ct),
                           content_type="multipart/form-data")
    a, b = subir(111222333), subir(111222333)            # reintento de la misma foto
    c = subir(444555666, PNG + b"1")                      # otra foto
    assert a.status_code == b.status_code == c.status_code == 201
    assert a.get_json()["id"] == b.get_json()["id"] != c.get_json()["id"]
    assert _db(app).execute("SELECT COUNT(*) FROM photos WHERE job_id = ?", (jid,)).fetchone()[0] == 2
    assert subir(None).status_code == 201, "sin clientPhotoId sigue funcionando"


def test_cliente_ve_trabajo_realizado_sin_anulados(app, client, toks):
    ct = registrar_cliente(client, "cli_resultado", "Cliente Resultado Ficticio")
    jid = _solicitud(client, ct, _propiedad(client, ct))["id"]
    c = _db(app)
    c.execute("UPDATE jobs SET technician_code = 'tec-t-01' WHERE id = ?", (jid,))
    for lid, anul in ((1, 0), (2, 1)):
        c.execute("INSERT INTO job_work_items(job_id, origin_device, local_id, tipo, descripcion, cantidad, precio_unitario,"
                  " total, client_updated_at, actor, created_at, updated_at, anulado) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                  (jid, "dev-A", lid, "LABOR", f"Trabajo {lid}", 1, 50, 50, 1, "tec_t", 1, 1, anul))
    c.commit()
    r = client.get(f"/api/v2/customer/requests/{jid}", headers=h(ct)).get_json()
    assert [w["descripcion"] for w in r["trabajoRealizado"]] == ["Trabajo 1"]


def test_factura_incluye_trabajo_realizado_vigente(app, client, toks):
    j = _orden(client, toks["desp"])
    c = _db(app)
    for lid, tipo, precio, anul in ((1, "LABOR", 95, 0), (2, "PART", 38, 0), (3, "LABOR", 500, 1)):
        c.execute("INSERT INTO job_work_items(job_id, origin_device, local_id, tipo, descripcion, cantidad, precio_unitario,"
                  " total, client_updated_at, actor, created_at, updated_at, anulado) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                  (j["id"], "dev-A", lid, tipo, f"Partida {lid}", 1, precio, precio, 1, "tec_t", 1, 1, anul))
    c.commit()
    r = client.post(f"/api/v2/jobs/{j['id']}/invoice", json={}, headers=h(toks["desp"])).get_json()
    assert [i["descripcion"] for i in r["items"]] == ["Partida 1", "Partida 2"], "la anulada no se factura"
    assert r["subtotal"] == 133.0 and r["total"] == 142.31
