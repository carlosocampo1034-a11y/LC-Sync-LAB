"""Firmas, repuestos usados, descuentos autorizados, pagos reportados/confirmados y PDF de factura (sync v2)."""
import base64
import re

from conftest import PW, h, registrar_cliente
from test_lab_backend import PNG, _db
from test_propuestas_facturas import _completar_con_trabajo, _enviar, _opcion, _push, orden  # noqa: F401

FIRMA = base64.b64encode(PNG).decode()
PDF = base64.b64encode(b"%PDF-1.4\n%lab\n1 0 obj<<>>endobj\n%%EOF\n").decode()


def _firma(lid, jid, tipo="CLIENTE_CONFORMIDAD", **kw):
    d = {"localId": lid, "jobId": jid, "tipo": tipo, "firmante": "Cliente Ficticio F7", "pngBase64": FIRMA,
         "firmadaAt": 123}
    d.update(kw)
    return d


def _emitir(client, toks, jid, cid="f-1"):
    return _push(client, toks["tec"], invoices=[{"jobId": jid, "clientInvoiceId": cid}])["invoices"]["results"][0]


def _login_web(client):
    pagina = client.get("/control/login")
    client.post("/control/login", data={"username": "desp_t", "password": PW})
    return client


def _csrf(client, jid):
    html = client.get(f"/control/jobs/{jid}").get_data(as_text=True)
    return re.search(r'name="csrf" value="([^"]+)"', html).group(1), html


# ── firmas ──────────────────────────────────────────────────────────────────

def test_firmas_se_guardan_idempotentes_y_validadas(app, client, toks, orden):
    jid, _ = orden
    r = _push(client, toks["tec"], signatures=[_firma(1, jid), _firma(2, jid, "TECNICO", firmante="Técnico T"),
                                               _firma(3, jid, pngBase64="no-png"), _firma(4, jid, firmante="")])
    st = [x["status"] for x in r["signatures"]["results"]]
    assert st == ["ACCEPTED", "ACCEPTED", "REJECTED", "REJECTED"]
    _push(client, toks["tec"], signatures=[_firma(1, jid)])                       # reintento
    assert _db(app).execute("SELECT COUNT(*) FROM job_signatures").fetchone()[0] == 2
    ajena = _push(client, toks["tec2"], deviceId="dev-B", signatures=[_firma(9, jid)])["signatures"]["results"][0]
    assert ajena["status"] == "REJECTED"


def test_aceptacion_presencial_aprueba_solo_lo_vigente(app, client, toks, orden):
    jid, ct = orden
    r1 = _enviar(client, toks, jid, [_opcion("GOOD", 100, 40), _opcion("BETTER", 150, 60)], lid=601)
    mal = _push(client, toks["tec"], signatures=[_firma(10, jid, "PROPUESTA_PRESENCIAL", proposalLocalId=601,
                                                         opcion="BETTER", total=1.0)])["signatures"]["results"][0]
    assert mal["status"] == "REJECTED" and "no coinciden" in mal["reason"]
    ok = _push(client, toks["tec"], signatures=[_firma(11, jid, "PROPUESTA_PRESENCIAL", proposalLocalId=601,
                                                        opcion="BETTER", total=round(210 * 1.07, 2))])["signatures"]["results"][0]
    assert ok["status"] == "ACCEPTED" and ok["propuesta"]["estado"] == "APROBADA"
    fila = _db(app).execute("SELECT rol_firmante, opcion_elegida, firmante FROM proposals WHERE id = ?", (r1["id"],)).fetchone()
    assert tuple(fila) == ("CLIENTE_PRESENCIAL", "BETTER", "Cliente Ficticio F7")
    otra = _push(client, toks["tec"], signatures=[_firma(12, jid, "PROPUESTA_PRESENCIAL", proposalLocalId=601,
                                                          opcion="GOOD", total=149.8)])["signatures"]["results"][0]
    assert otra["status"] == "REJECTED", "ya decidida"


def test_presencial_sobre_revision_superada_rechazada(client, toks, orden):
    jid, _ = orden
    _enviar(client, toks, jid, [_opcion("GOOD", 100, 40)], lid=602)
    _enviar(client, toks, jid, [_opcion("GOOD", 300, 40)], lid=602)      # rev 2
    r = _push(client, toks["tec"], signatures=[_firma(13, jid, "PROPUESTA_PRESENCIAL", proposalLocalId=602,
                                                       opcion="GOOD", total=149.8)])["signatures"]["results"][0]
    assert r["status"] == "REJECTED", "firma con el total de la rev 1 no aprueba la rev 2"


# ── repuestos usados, descuento y factura ───────────────────────────────────

def test_repuestos_usados_entran_en_factura_y_anulados_no(app, client, toks, orden):
    jid, _ = orden
    _push(client, toks["tec"], parts_used=[
        {"localId": 1, "jobId": jid, "sku": "CAP-455", "nombre": "Capacitor 45/5", "cantidad": 1, "precioUnitario": 30,
         "updatedAt": 5},
        {"localId": 2, "jobId": jid, "sku": "FUS-3", "nombre": "Fusible", "cantidad": 2, "precioUnitario": 4,
         "updatedAt": 5, "anulado": True}])
    _push(client, toks["tec"], parts_used=[{"localId": 1, "jobId": jid, "sku": "CAP-455", "nombre": "Capacitor 45/5",
                                            "cantidad": 9, "precioUnitario": 30, "updatedAt": 1}])   # versión vieja
    _completar_con_trabajo(client, toks, jid, anulada=False)
    f = _emitir(client, toks, jid)["factura"]
    desc = [i["descripcion"] for i in f["items"]]
    assert "Capacitor 45/5 (CAP-455)" in desc and not any("Fusible" in x for x in desc)
    assert f["subtotal"] == 133.0 + 30.0
    assert _db(app).execute("SELECT COUNT(*) FROM job_parts_used").fetchone()[0] == 2, "el anulado se conserva"


def test_descuento_autorizado_por_despacho_antes_de_emitir(client, toks, orden):
    jid, _ = orden
    _completar_con_trabajo(client, toks, jid, anulada=False)
    _login_web(client)
    csrf, _ = _csrf(client, jid)
    r = client.post(f"/control/jobs/{jid}/descuento", data={"csrf": csrf, "monto": "13", "motivo": "Cliente frecuente"},
                    follow_redirects=True)
    assert "Descuento autorizado" in r.get_data(as_text=True)
    f = _emitir(client, toks, jid)["factura"]
    assert (f["subtotal"], f["descuento"], f["impuesto"], f["total"]) == (133.0, 13.0, 8.4, 128.4)
    csrf, _ = _csrf(client, jid)
    r = client.post(f"/control/jobs/{jid}/descuento", data={"csrf": csrf, "monto": "5", "motivo": "tarde"},
                    follow_redirects=True)
    assert "ya fue emitida" in r.get_data(as_text=True)


# ── pagos ───────────────────────────────────────────────────────────────────

def _pago(lid, jid, monto, metodo="EFECTIVO"):
    return {"localId": lid, "jobId": jid, "monto": monto, "metodo": metodo, "referencia": "REF-LAB"}


def test_pago_reportado_requiere_confirmacion_de_despacho(app, client, toks, orden):
    jid, ct = orden
    sin = _push(client, toks["tec"], payments=[_pago(1, jid, 10)])["payments"]["results"][0]
    assert sin["status"] == "REJECTED", "sin factura emitida no hay pago"
    _completar_con_trabajo(client, toks, jid, anulada=False)
    total = _emitir(client, toks, jid)["factura"]["total"]
    assert _push(client, toks["tec"], payments=[_pago(2, jid, total + 1)])["payments"]["results"][0]["status"] == "REJECTED"
    a = _push(client, toks["tec"], payments=[_pago(3, jid, 100)])["payments"]["results"][0]
    b = _push(client, toks["tec"], payments=[_pago(3, jid, 100)])["payments"]["results"][0]     # reintento
    assert a["pago"]["id"] == b["pago"]["id"] and a["pago"]["estado"] == "REPORTADO"
    assert _db(app).execute("SELECT estado FROM invoices").fetchone()[0] == "EMITIDA", "reportado ≠ pagado"
    c = _push(client, toks["tec"], payments=[_pago(4, jid, round(total - 100, 2), "ZELLE")])["payments"]["results"][0]
    _login_web(client)
    csrf, _ = _csrf(client, jid)
    r = client.post(f"/control/pagos/{a['pago']['id']}/revisar", data={"csrf": csrf, "decision": "CONFIRMADO"},
                    follow_redirects=True)
    assert "Pago confirmado" in r.get_data(as_text=True)
    assert _db(app).execute("SELECT estado FROM invoices").fetchone()[0] == "EMITIDA", "parcial: sigue EMITIDA"
    csrf, _ = _csrf(client, jid)
    assert "indica el motivo" in client.post(f"/control/pagos/{c['pago']['id']}/revisar",
                                             data={"csrf": csrf, "decision": "RECHAZADO"}, follow_redirects=True
                                             ).get_data(as_text=True)
    csrf, _ = _csrf(client, jid)
    client.post(f"/control/pagos/{c['pago']['id']}/revisar", data={"csrf": csrf, "decision": "CONFIRMADO"})
    assert _db(app).execute("SELECT estado FROM invoices").fetchone()[0] == "PAGADA"
    vis = client.get(f"/api/v2/customer/requests/{jid}", headers=h(ct)).get_json()
    assert vis["factura"]["estado"] == "PAGADA" and vis["factura"]["saldo"] == 0
    assert {p["estado"] for p in vis["pagos"]} == {"CONFIRMADO"}
    assert "revisadoPor" not in str(vis["pagos"])


# ── PDF ─────────────────────────────────────────────────────────────────────

def test_pdf_de_factura_para_el_cliente(app, client, toks, orden):
    jid, ct = orden
    assert client.get(f"/api/v2/customer/requests/{jid}/factura.pdf", headers=h(ct)).status_code == 404
    _completar_con_trabajo(client, toks, jid, anulada=False)
    num = _emitir(client, toks, jid)["factura"]["numero"]
    malo = _push(client, toks["tec"], documents=[{"jobId": jid, "numero": "OTRO", "pdfBase64": PDF}])
    assert malo["documents"]["results"][0]["status"] == "REJECTED"
    no_pdf = _push(client, toks["tec"], documents=[{"jobId": jid, "numero": num, "pdfBase64": FIRMA}])
    assert no_pdf["documents"]["results"][0]["status"] == "REJECTED"
    for _ in range(2):
        r = _push(client, toks["tec"], documents=[{"jobId": jid, "numero": num, "pdfBase64": PDF}])
        assert r["documents"]["results"][0]["status"] == "ACCEPTED"
    assert _db(app).execute("SELECT COUNT(*) FROM invoice_documents").fetchone()[0] == 1
    d = client.get(f"/api/v2/customer/requests/{jid}/factura.pdf", headers=h(ct))
    assert d.status_code == 200 and d.data.startswith(b"%PDF-")
    otro = registrar_cliente(client, "cli_otro_pdf", "Cliente Otro Ficticio")
    assert client.get(f"/api/v2/customer/requests/{jid}/factura.pdf", headers=h(otro)).status_code == 404


def test_telefono_normalizado_al_registrar(app, client):
    r = client.post("/api/v2/auth/register", json={"username": "cli_tel", "password": PW, "nombre": "Cliente Tel",
                                                   "telefono": "+1 (305) 555-0199"})
    assert r.status_code == 201, r.get_json()
    assert _db(app).execute("SELECT telefono FROM customers WHERE nombre = 'Cliente Tel'").fetchone()[0] == "3055550199"
    assert client.post("/api/v2/auth/register", json={"username": "cli_tel2", "password": PW, "nombre": "Cliente Tel2",
                                                      "telefono": "555-01"}).status_code == 400
