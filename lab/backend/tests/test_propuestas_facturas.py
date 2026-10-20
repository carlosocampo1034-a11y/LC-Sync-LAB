"""Fase 6: propuestas del técnico → decisión firmada del cliente → factura emitida desde el técnico (sync v2)."""
import base64

import pytest

from conftest import h, registrar_cliente
from test_lab_backend import PNG, _db, _propiedad, _solicitud

FIRMA = base64.b64encode(PNG).decode()


def _opcion(nivel, mano=100.0, materiales=40.0, markup=0.0, desc=0.0, imp=7.0, titulo=None, **kw):
    o = {"nivel": nivel, "titulo": titulo or f"Opción {nivel}", "descripcion": f"Detalle {nivel}",
         "costoEquipo": 0, "costoManoObra": mano, "costoMateriales": materiales, "costoPermiso": 0, "costoRemocion": 0,
         "costoAccesorios": 0, "markupPorcentaje": markup, "descuentoMonto": desc, "impuestoPorcentaje": imp}
    o.update(kw)
    return o


@pytest.fixture
def orden(client, toks):
    """Solicitud de un cliente asignada a tec_t (tec-t-01)."""
    ct = registrar_cliente(client, "cli_f6", "Cliente F6 Ficticio")
    jid = _solicitud(client, ct, _propiedad(client, ct))["id"]
    r = client.post(f"/api/v2/dispatch/jobs/{jid}/assign", json={"technicianId": "tec-t-01"}, headers=h(toks["desp"]))
    assert r.status_code == 200, r.get_json()
    return jid, ct


def _push(client, tok, **kw):
    body = {"deviceId": "dev-A", "jobs": []}
    body.update(kw)
    r = client.post("/api/v2/sync/push", json=body, headers=h(tok))
    assert r.status_code == 200, r.get_json()
    return r.get_json()


def _enviar(client, toks, jid, opciones, lid=501):
    return _push(client, toks["tec"], proposals=[{"localId": lid, "jobId": jid, "opciones": opciones}])["proposals"]["results"][0]


def _ver(client, ct, jid):
    return client.get(f"/api/v2/customer/requests/{jid}", headers=h(ct)).get_json()


def _decidir(client, ct, pid, sha, decision="APROBADA", opcion="BETTER", firmante="Cliente F6 Ficticio", firma=FIRMA):
    return client.post(f"/api/v2/customer/proposals/{pid}/decide", headers=h(ct),
                       json={"decision": decision, "opcion": opcion, "firmante": firmante, "firmaPngBase64": firma,
                             "contenidoSha256": sha})


def test_propuesta_total_calculado_por_el_servidor_y_visible_al_cliente(client, toks, orden):
    jid, ct = orden
    r = _enviar(client, toks, jid, [_opcion("GOOD", 100, 40), _opcion("BETTER", 150, 60, markup=10, desc=5),
                                    _opcion("BEST", 0, 0)])
    assert r["status"] == "ACCEPTED" and r["estado"] == "PENDIENTE" and r["revision"] == 1
    p = _ver(client, ct, jid)["propuestas"][0]
    tot = {o["nivel"]: o["total"] for o in p["opciones"]}
    assert tot == {"GOOD": 149.8, "BETTER": round((210 * 1.1 - 5) * 1.07, 2)}, "BEST vacía no se ofrece"
    assert p["contenidoSha256"] == r["contenidoSha256"]


def test_total_manipulado_se_rechaza(client, toks, orden):
    jid, _ = orden
    r = _enviar(client, toks, jid, [_opcion("GOOD", 100, 40, totalCalculado=10.0)])
    assert r["status"] == "REJECTED" and "no coincide" in r["reason"]


def test_reintento_no_duplica(app, client, toks, orden):
    jid, _ = orden
    a = _enviar(client, toks, jid, [_opcion("GOOD")])
    b = _enviar(client, toks, jid, [_opcion("GOOD")])
    assert a["id"] == b["id"] and b["revision"] == 1
    assert _db(app).execute("SELECT COUNT(*) FROM proposals").fetchone()[0] == 1


def test_aprobar_con_firma_y_el_tecnico_lo_recibe(app, client, toks, orden):
    jid, ct = orden
    r = _enviar(client, toks, jid, [_opcion("GOOD"), _opcion("BETTER", 150, 60)])
    d = _decidir(client, ct, r["id"], r["contenidoSha256"])
    assert d.status_code == 200 and d.get_json()["propuesta"]["estado"] == "APROBADA"
    fila = _db(app).execute("SELECT estado, opcion_elegida, total_aceptado, firmante, firma_sha256, decided_at "
                            "FROM proposals").fetchone()
    assert fila[0] == "APROBADA" and fila[1] == "BETTER" and fila[2] == round(210 * 1.07, 2) and fila[3]
    assert fila[4] and fila[5], "firma y fecha conservadas"
    pull = client.get("/api/v2/sync/pull?since=0", headers=h(toks["tec"])).get_json()
    pj = next(j for j in pull["jobs"] if j["id"] == jid)["propuestas"][0]
    assert pj["estado"] == "APROBADA" and pj["localId"] == 501 and "opciones" not in pj
    assert "firmaPngBase64" not in str(pull), "la imagen de la firma no viaja al técnico"


def test_aprobacion_vieja_no_autoriza_propuesta_modificada(client, toks, orden):
    jid, ct = orden
    r1 = _enviar(client, toks, jid, [_opcion("GOOD", 100, 40)])
    r2 = _enviar(client, toks, jid, [_opcion("GOOD", 300, 40)])          # el técnico la cambia antes de la firma
    assert r2["revision"] == 2 and r2["id"] != r1["id"]
    assert _decidir(client, ct, r1["id"], r1["contenidoSha256"], opcion="GOOD").status_code == 409, "rev 1 SUPERADA"
    assert _decidir(client, ct, r2["id"], r1["contenidoSha256"], opcion="GOOD").status_code == 409, "sha viejo"
    assert _decidir(client, ct, r2["id"], r2["contenidoSha256"], opcion="GOOD").status_code == 200


def test_modificar_tras_aprobar_crea_revision_pendiente(client, toks, orden):
    jid, ct = orden
    r1 = _enviar(client, toks, jid, [_opcion("GOOD")])
    _decidir(client, ct, r1["id"], r1["contenidoSha256"], opcion="GOOD")
    r2 = _enviar(client, toks, jid, [_opcion("GOOD", 999, 0)])
    assert r2["estado"] == "PENDIENTE" and r2["revision"] == 2
    estados = {p["revision"]: p["estado"] for p in _ver(client, ct, jid)["propuestas"]}
    assert estados == {1: "APROBADA", 2: "PENDIENTE"}, "la aprobación de rev 1 se conserva como evidencia"


def test_rechazo_y_validaciones(client, toks, orden):
    jid, ct = orden
    r = _enviar(client, toks, jid, [_opcion("GOOD")])
    assert _decidir(client, ct, r["id"], r["contenidoSha256"], opcion="BEST").status_code == 400, "opción no ofrecida"
    assert _decidir(client, ct, r["id"], r["contenidoSha256"], firma="no-es-png", opcion="GOOD").status_code == 400
    assert _decidir(client, ct, r["id"], r["contenidoSha256"], firmante="", opcion="GOOD").status_code == 400
    d = _decidir(client, ct, r["id"], r["contenidoSha256"], decision="RECHAZADA", firmante="", firma="")
    assert d.status_code == 200 and d.get_json()["propuesta"]["estado"] == "RECHAZADA"
    assert _decidir(client, ct, r["id"], r["contenidoSha256"]).status_code == 409, "ya decidida"


def test_vencida(app, client, toks, orden):
    jid, ct = orden
    r = _enviar(client, toks, jid, [_opcion("GOOD")])
    c = _db(app)
    c.execute("UPDATE proposals SET expires_at = 1")
    c.commit()
    assert _decidir(client, ct, r["id"], r["contenidoSha256"], opcion="GOOD").status_code == 409
    assert _ver(client, ct, jid)["propuestas"][0]["estado"] == "VENCIDA"


def test_roles_y_propiedad(client, toks, orden):
    jid, ct = orden
    otro = _push(client, toks["tec2"], deviceId="dev-B",
                 proposals=[{"localId": 9, "jobId": jid, "opciones": [_opcion("GOOD")]}])["proposals"]["results"][0]
    assert otro["status"] == "REJECTED", "un técnico no asignado no envía propuestas"
    r = _enviar(client, toks, jid, [_opcion("GOOD")])
    ajeno = registrar_cliente(client, "cli_ajeno", "Cliente Ajeno Ficticio")
    assert _decidir(client, ajeno, r["id"], r["contenidoSha256"], opcion="GOOD").status_code == 404
    assert client.post(f"/api/v2/customer/proposals/{r['id']}/decide", json={}, headers=h(toks["tec"])).status_code == 403


# ── facturas ────────────────────────────────────────────────────────────────

def _completar_con_trabajo(client, toks, jid, anulada=True):
    wis = [{"id": 31, "jobId": jid, "tipo": "LABOR", "descripcion": "Mano de obra F6", "cantidad": 1, "precioUnitario": 95,
            "updatedAt": 10},
           {"id": 32, "jobId": jid, "tipo": "PART", "descripcion": "Capacitor F6", "cantidad": 1, "precioUnitario": 38,
            "updatedAt": 10}]
    if anulada:
        wis.append({"id": 33, "jobId": jid, "tipo": "LABOR", "descripcion": "Anulada", "cantidad": 1,
                    "precioUnitario": 500, "updatedAt": 10, "anulado": True})
    _push(client, toks["tec"], work_items=wis)
    v = client.get(f"/api/v2/jobs/{jid}", headers=h(toks["desp"])).get_json()["version"]
    _push(client, toks["tec"], jobs=[{"id": jid, "estado": "COMPLETADO", "version": v, "updatedAt": 99}])


def test_factura_desde_tecnico_totales_y_cliente(app, client, toks, orden):
    jid, ct = orden
    no = _push(client, toks["tec"], invoices=[{"jobId": jid, "clientInvoiceId": "f-1"}])["invoices"]["results"][0]
    assert no["status"] == "REJECTED" and "COMPLETADA" in no["reason"]
    _completar_con_trabajo(client, toks, jid)
    r = _push(client, toks["tec"], invoices=[{"jobId": jid, "clientInvoiceId": "f-1"}])["invoices"]["results"][0]
    f = r["factura"]
    assert r["status"] == "ACCEPTED" and f["estado"] == "EMITIDA" and f["numero"].startswith(f"LC-{jid}-")
    assert [i["descripcion"] for i in f["items"]] == ["Mano de obra F6", "Capacitor F6"], "sin la anulada"
    assert (f["subtotal"], f["impuesto"], f["total"], f["descuento"]) == (133.0, 9.31, 142.31, 0)
    vis = _ver(client, ct, jid)["factura"]
    assert vis["numero"] == f["numero"] and vis["total"] == 142.31 and vis["estado"] == "EMITIDA"
    assert _db(app).execute("SELECT estado FROM invoices").fetchone()[0] != "PAGADA"


def test_factura_reintento_idempotente_y_sin_doble_facturacion(app, client, toks, orden):
    jid, _ = orden
    _completar_con_trabajo(client, toks, jid, anulada=False)
    a = _push(client, toks["tec"], invoices=[{"jobId": jid, "clientInvoiceId": "f-1"}])["invoices"]["results"][0]
    b = _push(client, toks["tec"], invoices=[{"jobId": jid, "clientInvoiceId": "f-1"}])["invoices"]["results"][0]
    assert a["factura"]["id"] == b["factura"]["id"] and a["factura"]["numero"] == b["factura"]["numero"]
    assert _db(app).execute("SELECT COUNT(*) FROM invoices").fetchone()[0] == 1
    # una partida nueva después de emitir: no se refactura ni se modifica la emitida en silencio
    _push(client, toks["tec"], work_items=[{"id": 34, "jobId": jid, "tipo": "LABOR", "descripcion": "Extra",
                                            "cantidad": 1, "precioUnitario": 10, "updatedAt": 20}])
    c = _push(client, toks["tec"], invoices=[{"jobId": jid, "clientInvoiceId": "f-2"}])["invoices"]["results"][0]
    assert c["status"] == "REJECTED" and "dos veces" in c["reason"] and c["factura"]["total"] == 142.31


def test_factura_de_orden_ajena_rechazada(client, toks, orden):
    jid, _ = orden
    _completar_con_trabajo(client, toks, jid)
    r = _push(client, toks["tec2"], deviceId="dev-B", invoices=[{"jobId": jid, "clientInvoiceId": "x"}])
    assert r["invoices"]["results"][0]["status"] == "REJECTED"


def test_diferencia_de_un_centavo_por_redondeo_se_acepta_y_manda_el_servidor(client, toks, orden):
    jid, _ = orden
    o = _opcion("GOOD", 90, 20, markup=15, imp=7)          # 135.355 → el teléfono puede enviar 135.36
    o["totalCalculado"] = 135.36
    r = _enviar(client, toks, jid, [o])
    assert r["status"] == "ACCEPTED"
    o2 = _opcion("GOOD", 90, 20, markup=15, imp=7, totalCalculado=135.38)
    assert _enviar(client, toks, jid, [o2], lid=777)["status"] == "REJECTED", "más de un centavo sigue rechazado"
