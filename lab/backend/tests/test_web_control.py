"""L&C Control (web de despacho): acceso por rol, agenda, programar/asignar con CSRF y versión."""
import re
import time

from conftest import PW, h


def _login_web(client, user):
    return client.post("/control/login", data={"username": user, "password": PW}, follow_redirects=True)


def _csrf(html):
    return re.search(r'name="csrf" value="([^"]+)"', html).group(1)


def test_solo_despacho_entra(client):
    r = _login_web(client, "tec_t")
    assert "no es de despacho" in r.get_data(as_text=True)
    assert client.get("/control/").status_code == 302


def test_crear_programar_asignar_desde_la_web(client, toks):
    r = _login_web(client, "desp_t")
    assert r.status_code == 200 and "Agenda y despacho" in r.get_data(as_text=True)
    html = client.get("/control/nueva").get_data(as_text=True)
    r = client.post("/control/nueva", data={"csrf": _csrf(html), "cliente": "Cliente Web Ficticio",
                                            "direccion": "1 Calle Web Lab", "descripcion": "Revisión web ficticia"},
                    follow_redirects=True)
    pagina = r.get_data(as_text=True)
    assert "Orden creada" in pagina and "Cliente Web Ficticio" in pagina
    jid = int(re.search(r"#(\d+) · v", pagina).group(1))
    tok = _csrf(pagina)
    manana = time.strftime("%Y-%m-%d", time.localtime(time.time() + 86400))
    r = client.post(f"/control/jobs/{jid}/programar", data={"csrf": tok, "version": 1, "fecha": manana, "hora": "10:30"},
                    follow_redirects=True)
    assert "Programada" in r.get_data(as_text=True)
    r = client.post(f"/control/jobs/{jid}/asignar", data={"csrf": tok, "version": 2, "technicianId": "tec-t-01"},
                    follow_redirects=True)
    assert "Asignada" in r.get_data(as_text=True)
    j = client.get(f"/api/v2/jobs/{jid}", headers=h(toks["desp"])).get_json()
    assert j["technicianId"] == "tec-t-01" and j["programadoPara"] and j["categoriaAgenda"] == "PROXIMOS"
    board = client.get("/control/?vista=PROXIMOS").get_data(as_text=True)
    assert "Cliente Web Ficticio" in board


def test_formulario_sin_csrf_rechazado(client):
    _login_web(client, "desp_t")
    html = client.get("/control/nueva").get_data(as_text=True)
    r = client.post("/control/nueva", data={"csrf": _csrf(html), "cliente": "X Ficticio", "direccion": "2 Calle",
                                            "descripcion": "Otra prueba ficticia"}, follow_redirects=True)
    jid = int(re.search(r"#(\d+) · v", r.get_data(as_text=True)).group(1))
    r = client.post(f"/control/jobs/{jid}/asignar", data={"version": 1, "technicianId": "tec-t-01"})
    assert r.status_code == 400


def test_version_antigua_da_conflicto_en_la_web(client, toks):
    _login_web(client, "desp_t")
    html = client.get("/control/nueva").get_data(as_text=True)
    r = client.post("/control/nueva", data={"csrf": _csrf(html), "cliente": "Cliente Bloqueo Ficticio", "direccion": "3 Calle",
                                            "descripcion": "Prueba de conflicto web"}, follow_redirects=True)
    pagina = r.get_data(as_text=True)
    jid, tok = int(re.search(r"#(\d+) · v", pagina).group(1)), _csrf(pagina)
    client.post(f"/control/jobs/{jid}/asignar", data={"csrf": tok, "version": 1, "technicianId": "tec-t-01"})
    r = client.post(f"/control/jobs/{jid}/asignar", data={"csrf": tok, "version": 1, "technicianId": "tec-t-02"},
                    follow_redirects=True)
    assert "Conflicto: la orden cambió" in r.get_data(as_text=True)
    assert client.get(f"/api/v2/jobs/{jid}", headers=h(toks["desp"])).get_json()["technicianId"] == "tec-t-01"


def test_orden_inexistente_da_404(client):
    _login_web(client, "desp_t")
    html = client.get("/control/nueva").get_data(as_text=True)
    tok = _csrf(html)
    for ruta in ("programar", "asignar", "estado"):
        assert client.post(f"/control/jobs/999999/{ruta}", data={"csrf": tok}).status_code == 404
    assert client.get("/control/jobs/999999").status_code == 404


def test_resolver_conflicto_desde_la_web(client, toks):
    from test_conflictos_ids import JID, _push_tecnico, _setup
    _setup(client, toks)
    _push_tecnico(client, toks, 200, notas="TECNICO web")
    _login_web(client, "desp_t")
    pagina = client.get(f"/control/jobs/{JID}").get_data(as_text=True)
    assert "Conflictos pendientes (1)" in pagina and "TECNICO web" in pagina and "DESPACHO: llamar antes" in pagina
    cid = int(re.search(r"/control/conflicts/(\d+)/resolver", pagina).group(1))
    v = int(re.search(r'name="version" value="(\d+)"', pagina).group(1))
    r = client.post(f"/control/conflicts/{cid}/resolver", data={"csrf": _csrf(pagina), "version": v,
                                                                "decision": "TECNICO"}, follow_redirects=True)
    assert "Conflicto resuelto" in r.get_data(as_text=True)
    assert client.get(f"/api/v2/jobs/{JID}", headers=h(toks["desp"])).get_json()["notas"] == "TECNICO web"
