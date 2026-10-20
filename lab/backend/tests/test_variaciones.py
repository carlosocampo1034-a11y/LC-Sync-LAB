"""Regla del propietario: la factura advierte y exige aprobación cuando difiere de la propuesta aprobada."""
import base64

from conftest import h
from test_lab_backend import PNG, _db
from test_propuestas_facturas import _decidir, _enviar, _opcion, _push, orden  # noqa: F401

FIRMA = base64.b64encode(PNG).decode()


def _aprobar_135_36(client, toks, jid, ct):
    r = _enviar(client, toks, jid, [_opcion("GOOD", 90, 20, markup=15, imp=7)])      # 135.36 (110 × 1.15 = 126.50 exacto)
    assert _decidir(client, ct, r["id"], r["contenidoSha256"], opcion="GOOD").status_code == 200
    return r


def _trabajo(client, toks, jid, precio, lid=41, upd=10):
    _push(client, toks["tec"], work_items=[{"id": lid, "jobId": jid, "tipo": "LABOR", "descripcion": f"Trabajo {lid}",
                                            "cantidad": 1, "precioUnitario": precio, "updatedAt": upd}])


def _completar(client, toks, jid):
    v = client.get(f"/api/v2/jobs/{jid}", headers=h(toks["desp"])).get_json()["version"]
    _push(client, toks["tec"], jobs=[{"id": jid, "estado": "COMPLETADO", "version": v, "updatedAt": 99}])


def _emitir(client, toks, jid, **kw):
    d = {"jobId": jid, "clientInvoiceId": "fv-1"}
    d.update(kw)
    return _push(client, toks["tec"], invoices=[d])["invoices"]["results"][0]


def _ver(client, ct, jid):
    return client.get(f"/api/v2/customer/requests/{jid}", headers=h(ct)).get_json()


def _decidir_var(client, ct, vid, sha, decision="APROBADA", firma=FIRMA):
    return client.post(f"/api/v2/customer/variances/{vid}/decide", headers=h(ct),
                       json={"decision": decision, "firmante": "Cliente F6 Ficticio", "firmaPngBase64": firma,
                             "itemsSha256": sha})


def test_caso_laboratorio_mayor_exige_aprobacion_del_cliente(app, client, toks, orden):
    jid, ct = orden
    _aprobar_135_36(client, toks, jid, ct)
    _trabajo(client, toks, jid, 140)                      # 140 + 7 % = 149.80
    _completar(client, toks, jid)
    r = _emitir(client, toks, jid)
    assert r["status"] == "REJECTED" and r["codigo"] == "REQUIERE_APROBACION_CLIENTE"
    v = r["variacion"]
    assert (v["tipo"], v["aprobado"], v["facturado"], v["diferencia"], v["estado"]) == ("MAYOR", 135.36, 149.8, 14.44, "PENDIENTE")
    assert r["comparacion"]["diferencia"] == 14.44
    assert _emitir(client, toks, jid)["variacion"]["id"] == v["id"], "reintento: la misma solicitud"
    assert _db(app).execute("SELECT COUNT(*) FROM invoices WHERE estado != 'BORRADOR'").fetchone()[0] == 0, "no se emitió"
    vis = _ver(client, ct, jid)["variaciones"][0]
    assert vis["diferencia"] == 14.44 and vis["items"][0]["descripcion"] == "Trabajo 41"
    assert _decidir_var(client, ct, v["id"], "otro-sha").status_code == 409
    assert _decidir_var(client, ct, v["id"], v["itemsSha256"], firma="x").status_code == 400
    assert _decidir_var(client, ct, v["id"], v["itemsSha256"]).status_code == 200
    f = _emitir(client, toks, jid)["factura"]
    assert f["total"] == 149.8, "nunca se ajusta al presupuesto"
    assert f["comparacion"]["diferencia"] == 14.44 and f["variacionId"] == v["id"]


def test_trabajo_nuevo_tras_aprobar_la_diferencia_pide_otra_aprobacion(client, toks, orden):
    jid, ct = orden
    _aprobar_135_36(client, toks, jid, ct)
    _trabajo(client, toks, jid, 140)
    _completar(client, toks, jid)
    v1 = _emitir(client, toks, jid)["variacion"]
    _decidir_var(client, ct, v1["id"], v1["itemsSha256"])
    _trabajo(client, toks, jid, 25, lid=42)               # otra línea: lo aprobado ya no corresponde
    r = _emitir(client, toks, jid)
    assert r["status"] == "REJECTED" and r["variacion"]["id"] != v1["id"] and r["variacion"]["facturado"] == 176.55


def test_cliente_rechaza_la_diferencia(client, toks, orden):
    jid, ct = orden
    _aprobar_135_36(client, toks, jid, ct)
    _trabajo(client, toks, jid, 140)
    _completar(client, toks, jid)
    v = _emitir(client, toks, jid)["variacion"]
    assert _decidir_var(client, ct, v["id"], v["itemsSha256"], decision="RECHAZADA", firma="").status_code == 200
    r = _emitir(client, toks, jid)
    assert r["status"] == "REJECTED" and r["codigo"] == "CLIENTE_RECHAZO"


def test_menor_dentro_del_umbral_requiere_confirmacion_del_tecnico(app, client, toks, orden):
    jid, ct = orden
    _aprobar_135_36(client, toks, jid, ct)
    _trabajo(client, toks, jid, 120)                      # 128.40 → −6.96 (5 %)
    _completar(client, toks, jid)
    r = _emitir(client, toks, jid)
    assert r["status"] == "REJECTED" and r["codigo"] == "CONFIRMAR_MENOR"
    f = _emitir(client, toks, jid, confirmaMenor=True)["factura"]
    assert f["total"] == 128.4 and f["comparacion"]["tipo"] == "MENOR" and f["confirmacionMenorPor"] == "tec_t"
    assert _db(app).execute("SELECT COUNT(*) FROM invoice_variances").fetchone()[0] == 0


def test_menor_significativo_es_cambio_de_alcance(client, toks, orden):
    jid, ct = orden
    _aprobar_135_36(client, toks, jid, ct)
    _trabajo(client, toks, jid, 100)                      # 107.00 → −28.36 (21 %)
    _completar(client, toks, jid)
    r = _emitir(client, toks, jid, confirmaMenor=True)
    assert r["status"] == "REJECTED" and r["variacion"]["tipo"] == "ALCANCE", "la confirmación del técnico no basta"


def test_igual_a_lo_aprobado_se_emite(client, toks, orden):
    jid, ct = orden
    r = _enviar(client, toks, jid, [_opcion("GOOD", 100, 0, markup=0, imp=7)])   # 107.00
    _decidir(client, ct, r["id"], r["contenidoSha256"], opcion="GOOD")
    _trabajo(client, toks, jid, 100)
    _completar(client, toks, jid)
    f = _emitir(client, toks, jid)["factura"]
    assert f["estado"] == "EMITIDA" and f["comparacion"]["tipo"] == "IGUAL"


def test_misma_base_misma_cifra_en_propuesta_y_factura():
    """Prueba LAN: propuesta 90+20 (+15 %, +7 %) daba 135.35 y la factura de 126.50 (+7 %) 135.36."""
    from lc_lab.proposals import total_opcion, totales
    assert total_opcion({"costoManoObra": 90, "costoMateriales": 20, "markupPorcentaje": 15, "impuestoPorcentaje": 7}) == 135.36
    assert totales([{"importe": 86.5}, {"importe": 40}], 0.07) == (126.5, 0.0, 8.86, 135.36)
    assert totales([{"importe": 0.1}, {"importe": 0.2}], 0.0)[0] == 0.3, "sin error binario en la suma"
