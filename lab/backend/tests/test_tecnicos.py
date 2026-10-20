"""Alta y gestión de técnicos desde L&C Control (API + web), cambio de contraseña y aislamiento por técnico."""
import re
import sqlite3

from conftest import PW, h, token

NUEVO = {"username": "tecnico_nuevo", "display_name": "Técnico Nuevo Ficticio", "password": "inicial-ficticia-1",
         "technician_code": "tec-t-03", "specialties": "HVAC, Lavadoras"}


def _db(app):
    c = sqlite3.connect(app.config["DATABASE"])
    c.row_factory = sqlite3.Row
    return c


def _crear(client, tok, **cambios):
    return client.post("/api/v2/dispatch/technicians", json=dict(NUEVO, **cambios), headers=h(tok))


def _orden(client, tok, desc="Orden de prueba para técnicos"):
    r = client.post("/api/v2/dispatch/jobs", json={"cliente": "Cliente Ficticio", "direccion": "1 Calle Ficticia",
                                                    "descripcion": desc}, headers=h(tok))
    assert r.status_code == 201, r.get_json()
    return r.get_json()["id"]


def _asignar(client, tok, jid, code):
    r = client.post(f"/api/v2/dispatch/jobs/{jid}/assign", json={"technicianId": code}, headers=h(tok))
    assert r.status_code == 200, r.get_json()


# ── alta ────────────────────────────────────────────────────────────────────

def test_crear_tecnico_valido(app, client, toks):
    r = _crear(client, toks["admin"])
    assert r.status_code == 201, r.get_json()
    j = r.get_json()
    assert (j["username"], j["technician_code"], j["must_change_password"]) == ("tecnico_nuevo", "tec-t-03", 1)
    assert j["password_inicial"] == NUEVO["password"]
    u = _db(app).execute("SELECT * FROM users WHERE username = 'tecnico_nuevo'").fetchone()
    assert (u["role"], u["active"], u["must_change_password"], u["technician_code"]) == ("TECHNICIAN", 1, 1, "tec-t-03")
    assert u["specialties"] == "HVAC, Lavadoras" and u["created_at"] > 0 and u["updated_at"] > 0
    assert u["password_hash"].startswith("scrypt:") and NUEVO["password"] not in u["password_hash"]


def test_rol_forzado_a_tecnico(app, client, toks):
    assert _crear(client, toks["admin"], role="ADMIN").status_code == 201
    assert _db(app).execute("SELECT role FROM users WHERE username='tecnico_nuevo'").fetchone()[0] == "TECHNICIAN"


def test_contrasena_generada_si_no_se_indica(client, toks):
    j = _crear(client, toks["admin"], password="").get_json()
    assert len(j["password_inicial"]) >= 8
    assert client.post("/api/v2/auth/login", json={"username": "tecnico_nuevo", "password": j["password_inicial"]}).status_code == 200


def test_usuario_duplicado(client, toks):
    assert _crear(client, toks["admin"]).status_code == 201
    r = _crear(client, toks["admin"], technician_code="tec-t-09")
    assert r.status_code == 409 and "usuario" in r.get_json()["error"]
    assert _crear(client, toks["admin"], username="tec_t", technician_code="tec-t-10").status_code == 409


def test_contrasena_corta(client, toks):
    r = _crear(client, toks["admin"], password="corta")
    assert r.status_code == 400 and "8" in r.get_json()["error"]


def test_codigo_duplicado_o_invalido(client, toks):
    r = _crear(client, toks["admin"], technician_code="tec-t-01")            # ya es de tec_t
    assert r.status_code == 409 and "código" in r.get_json()["error"]
    assert _crear(client, toks["admin"], technician_code="TEC T 03").status_code == 400
    assert _crear(client, toks["admin"], technician_code="TEC-T-01").status_code == 409, "se normaliza a minúsculas"


def test_permisos_alta(client, toks):
    assert client.post("/api/v2/dispatch/technicians", json=NUEVO).status_code == 401
    assert _crear(client, toks["tec"]).status_code == 403
    assert _crear(client, toks["desp"]).status_code == 403, "despacho asigna, no da de alta"
    assert _crear(client, toks["admin"]).status_code == 201


def test_ver_y_listar(client, toks):
    uid = _crear(client, toks["admin"]).get_json()["id"]
    assert client.get(f"/api/v2/dispatch/technicians/{uid}", headers=h(toks["desp"])).status_code == 200
    assert client.get(f"/api/v2/dispatch/technicians/{uid}", headers=h(toks["tec"])).status_code == 403
    assert client.get("/api/v2/dispatch/technicians/999999", headers=h(toks["admin"])).status_code == 404
    lista = client.get("/api/v2/dispatch/technicians", headers=h(toks["desp"])).get_json()["technicians"]
    assert "tec-t-03" in [t["technicianId"] for t in lista]
    assert "password_hash" not in str(lista) and "password" not in str(lista).lower().replace("mustchangepassword", "")


# ── login, desactivación, sesiones ──────────────────────────────────────────

def test_login_del_tecnico_nuevo_devuelve_token_y_aviso(client, toks):
    _crear(client, toks["admin"])
    r = client.post("/api/v2/auth/login", json={"username": "tecnico_nuevo", "password": NUEVO["password"], "deviceId": "dev-N"})
    assert r.status_code == 200
    j = r.get_json()
    assert j["token"] and j["role"] == "TECHNICIAN" and j["technicianId"] == "tec-t-03" and j["mustChangePassword"] is True
    me = client.get("/api/v2/me", headers=h(j["token"])).get_json()
    assert me["must_change_password"] == 1


def test_desactivar_rechaza_login_y_cierra_sesiones(app, client, toks):
    uid = _crear(client, toks["admin"]).get_json()["id"]
    tok = token(client, "tecnico_nuevo", NUEVO["password"], "dev-N")
    assert client.get("/api/v2/sync/pull?since=0", headers=h(tok)).status_code == 200
    r = client.patch(f"/api/v2/dispatch/technicians/{uid}", json={"active": False}, headers=h(toks["admin"]))
    assert r.status_code == 200 and r.get_json()["tecnico"]["active"] is False
    assert _db(app).execute("SELECT active FROM users WHERE id = ?", (uid,)).fetchone()[0] == 0
    assert client.post("/api/v2/auth/login", json={"username": "tecnico_nuevo", "password": NUEVO["password"]}).status_code == 401
    assert client.get("/api/v2/sync/pull?since=0", headers=h(tok)).status_code == 401, "su sesión abierta deja de valer"
    client.patch(f"/api/v2/dispatch/technicians/{uid}", json={"active": True}, headers=h(toks["admin"]))
    assert client.get("/api/v2/sync/pull?since=0", headers=h(tok)).status_code == 401, "reactivar no revive tokens viejos"
    assert client.post("/api/v2/auth/login", json={"username": "tecnico_nuevo", "password": NUEVO["password"]}).status_code == 200
    # no se puede asignar a un técnico inactivo
    client.patch(f"/api/v2/dispatch/technicians/{uid}", json={"active": False}, headers=h(toks["admin"]))
    jid = _orden(client, toks["desp"])
    assert client.post(f"/api/v2/dispatch/jobs/{jid}/assign", json={"technicianId": "tec-t-03"},
                       headers=h(toks["desp"])).status_code == 404


def test_patch_permisos_y_validacion(client, toks):
    uid = _crear(client, toks["admin"]).get_json()["id"]
    assert client.patch(f"/api/v2/dispatch/technicians/{uid}", json={"active": False}, headers=h(toks["desp"])).status_code == 403
    assert client.patch(f"/api/v2/dispatch/technicians/{uid}", json={"active": "no"}, headers=h(toks["admin"])).status_code == 400
    r = client.patch(f"/api/v2/dispatch/technicians/{uid}", json={"display_name": "Técnica Renombrada", "specialties": ["HVAC", "HVAC", "Hielo"]},
                     headers=h(toks["admin"])).get_json()["tecnico"]
    assert r["displayName"] == "Técnica Renombrada" and r["specialties"] == "HVAC, Hielo"
    # el admin no puede convertir un usuario no técnico desde aquí
    admin_id = client.get("/api/v2/me", headers=h(toks["admin"])).get_json()
    assert client.patch("/api/v2/dispatch/technicians/1", json={"active": False}, headers=h(toks["admin"])).status_code == 404


def test_restablecer_contrasena(app, client, toks):
    uid = _crear(client, toks["admin"]).get_json()["id"]
    tok = token(client, "tecnico_nuevo", NUEVO["password"])
    client.post("/api/v2/me/password", json={"actual": NUEVO["password"], "nueva": "propia-ficticia-22"}, headers=h(tok))
    r = client.patch(f"/api/v2/dispatch/technicians/{uid}", json={"reset_password": True}, headers=h(toks["admin"])).get_json()
    nueva = r["password_inicial"]
    assert r["tecnico"]["mustChangePassword"] is True
    assert client.post("/api/v2/auth/login", json={"username": "tecnico_nuevo", "password": "propia-ficticia-22"}).status_code == 401
    assert client.post("/api/v2/auth/login", json={"username": "tecnico_nuevo", "password": nueva}).status_code == 200


# ── cambio de contraseña ────────────────────────────────────────────────────

def test_cambio_de_contrasena(app, client, toks):
    _crear(client, toks["admin"])
    tok = token(client, "tecnico_nuevo", NUEVO["password"])
    assert client.post("/api/v2/me/password", json={"actual": "x", "nueva": "nueva-ficticia-55"}).status_code == 401
    assert client.post("/api/v2/me/password", json={"actual": "incorrecta", "nueva": "nueva-ficticia-55"}, headers=h(tok)).status_code == 403
    assert client.post("/api/v2/me/password", json={"actual": NUEVO["password"], "nueva": "corta"}, headers=h(tok)).status_code == 400
    assert client.post("/api/v2/me/password", json={"actual": NUEVO["password"], "nueva": NUEVO["password"]}, headers=h(tok)).status_code == 400
    r = client.post("/api/v2/me/password", json={"actual": NUEVO["password"], "nueva": "nueva-ficticia-55"}, headers=h(tok))
    assert r.status_code == 200
    u = _db(app).execute("SELECT must_change_password, failed_logins FROM users WHERE username='tecnico_nuevo'").fetchone()
    assert tuple(u) == (0, 0)
    assert client.post("/api/v2/auth/login", json={"username": "tecnico_nuevo", "password": NUEVO["password"]}).status_code == 401
    assert client.post("/api/v2/auth/login", json={"username": "tecnico_nuevo", "password": "nueva-ficticia-55"}).get_json()["mustChangePassword"] is False


def test_cambio_de_contrasena_con_fuerza_bruta_se_bloquea(client, toks):
    _crear(client, toks["admin"])
    tok = token(client, "tecnico_nuevo", NUEVO["password"])
    for _ in range(5):
        client.post("/api/v2/me/password", json={"actual": "adivina", "nueva": "nueva-ficticia-55"}, headers=h(tok))
    r = client.post("/api/v2/me/password", json={"actual": NUEVO["password"], "nueva": "nueva-ficticia-55"}, headers=h(tok))
    assert r.status_code == 429, "tras 5 intentos fallidos queda bloqueado como el login"


# ── aislamiento: solo sus órdenes ───────────────────────────────────────────

def test_pull_solo_ordenes_del_tecnico(client, toks):
    _crear(client, toks["admin"])
    mia, ajena, libre = _orden(client, toks["desp"]), _orden(client, toks["desp"]), _orden(client, toks["desp"])
    _asignar(client, toks["desp"], mia, "tec-t-03")
    _asignar(client, toks["desp"], ajena, "tec-t-01")
    tok = token(client, "tecnico_nuevo", NUEVO["password"], "dev-N")
    ids = {j["id"] for j in client.get("/api/v2/sync/pull?since=0", headers=h(tok)).get_json()["jobs"]}
    assert ids == {mia}, ids
    assert client.get(f"/api/v2/jobs/{ajena}", headers=h(tok)).status_code == 404
    assert client.get(f"/api/v2/jobs/{mia}", headers=h(tok)).status_code == 200
    # reasignación: aparece en su siguiente sincronización
    cursor = client.get("/api/v2/sync/pull?since=0", headers=h(tok)).get_json()["cursor"]
    _asignar(client, toks["desp"], libre, "tec-t-03")
    nuevos = client.get(f"/api/v2/sync/pull?since={cursor}", headers=h(tok)).get_json()["jobs"]
    assert [j["id"] for j in nuevos] == [libre]


def test_telefono_compartido_no_filtra_ordenes_del_otro_tecnico(client, toks):
    """Bug encontrado en la auditoría: pull devolvía también las órdenes CREADAS en el mismo teléfono."""
    d = client.post("/api/v2/sync/device", json={"deviceId": "dev-A"}, headers=h(toks["tec"])).get_json()
    jid = d["idBase"] + 5
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [
        {"id": jid, "cliente": "Cliente del técnico T", "direccion": "2 Calle", "descripcion": "Orden creada en el teléfono",
         "estado": "PENDIENTE", "updatedAt": 1}]}, headers=h(toks["tec"])).get_json()
    assert r["results"][0]["status"] == "ACCEPTED"
    _crear(client, toks["admin"])
    otro = token(client, "tecnico_nuevo", NUEVO["password"], "dev-A")          # mismo teléfono, otra persona
    ids = {j["id"] for j in client.get("/api/v2/sync/pull?since=0&deviceId=dev-A", headers=h(otro)).get_json()["jobs"]}
    assert jid not in ids, "no debe recibir las órdenes del otro técnico creadas en ese teléfono"
    w = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [
        {"id": jid, "version": 1, "descripcion": "Intento de cambio ajeno", "updatedAt": 2}]}, headers=h(otro)).get_json()
    assert w["results"][0]["status"] == "REJECTED"
    # el dueño del teléfono sigue viéndola
    assert jid in {j["id"] for j in client.get("/api/v2/sync/pull?since=0&deviceId=dev-A", headers=h(toks["tec"])).get_json()["jobs"]}


# ── auditoría ───────────────────────────────────────────────────────────────

def test_auditoria_de_cada_operacion(app, client, toks):
    uid = _crear(client, toks["admin"]).get_json()["id"]
    client.patch(f"/api/v2/dispatch/technicians/{uid}", json={"active": False}, headers=h(toks["admin"]))
    client.patch(f"/api/v2/dispatch/technicians/{uid}", json={"active": True}, headers=h(toks["admin"]))
    client.patch(f"/api/v2/dispatch/technicians/{uid}", json={"reset_password": True}, headers=h(toks["admin"]))
    clave = client.patch(f"/api/v2/dispatch/technicians/{uid}", json={"reset_password": True},
                         headers=h(toks["admin"])).get_json()["password_inicial"]
    tok = token(client, "tecnico_nuevo", clave)
    client.post("/api/v2/me/password", json={"actual": clave, "nueva": "nueva-ficticia-77"}, headers=h(tok))
    filas = _db(app).execute("SELECT actor, action, ts, detail FROM audit_log WHERE entity = 'user' AND entity_id = ? ORDER BY id",
                             (str(uid),)).fetchall()
    acciones = [f["action"] for f in filas]
    for a in ("TECH_CREATE", "TECH_DEACTIVATE", "TECH_ACTIVATE", "TECH_PASSWORD_RESET", "PASSWORD_CHANGE"):
        assert a in acciones, (a, acciones)
    assert all(f["actor"] and f["ts"] > 0 for f in filas)
    assert {f["actor"] for f in filas if f["action"].startswith("TECH_")} == {"admin_t"}
    todo = " ".join(str(dict(f)) for f in _db(app).execute("SELECT * FROM audit_log"))
    assert NUEVO["password"] not in todo and clave not in todo, "ninguna contraseña en la auditoría"


# ── web de L&C Control ──────────────────────────────────────────────────────

def _web(client, usuario):
    client.post("/control/login", data={"username": usuario, "password": PW})
    html = client.get("/control/tecnicos").get_data(as_text=True)
    csrf = re.search(r'name="csrf" value="([^"]+)"', html + client.get("/control/nueva").get_data(as_text=True)).group(1)
    return csrf, html


def test_web_admin_crea_y_desactiva(app, client):
    csrf, html = _web(client, "admin_t")
    assert "Agregar técnico" in html and ">Técnicos</a>" in client.get("/control/").get_data(as_text=True)
    r = client.post("/control/tecnicos", data=dict(NUEVO, csrf=csrf, password=""))
    t = r.get_data(as_text=True)
    assert r.status_code == 200 and "creado" in t and "solo esta vez" in t
    assert r.headers["Cache-Control"] == "no-store"
    clave = re.search(r'letter-spacing:1px;margin:8px 0">([^<]+)<', t).group(1)
    assert client.post("/api/v2/auth/login", json={"username": "tecnico_nuevo", "password": clave}).status_code == 200
    assert clave not in client.get("/control/tecnicos").get_data(as_text=True), "la contraseña no vuelve a mostrarse"
    uid = _db(app).execute("SELECT id FROM users WHERE username='tecnico_nuevo'").fetchone()[0]
    r = client.post(f"/control/tecnicos/{uid}/estado", data={"csrf": csrf, "activo": "0"}, follow_redirects=True)
    assert "desactivado" in r.get_data(as_text=True) and "INACTIVO" in r.get_data(as_text=True)
    assert client.post("/api/v2/auth/login", json={"username": "tecnico_nuevo", "password": clave}).status_code == 401
    assert client.post("/control/tecnicos", data=dict(NUEVO, csrf="malo")).status_code == 400, "CSRF"


def test_web_despacho_ve_lista_pero_no_crea(client):
    csrf, html = _web(client, "desp_t")
    assert "tec-t-01" in html and "Agregar técnico" not in html and "Desactivar" not in html
    assert client.post("/control/tecnicos", data=dict(NUEVO, csrf=csrf)).status_code == 403


def test_web_error_no_devuelve_la_contrasena(client):
    csrf, _ = _web(client, "admin_t")
    t = client.post("/control/tecnicos", data=dict(NUEVO, csrf=csrf, technician_code="tec-t-01")).get_data(as_text=True)
    assert "ya está en uso" in t and NUEVO["password"] not in t
