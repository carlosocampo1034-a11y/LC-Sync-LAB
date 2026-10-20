"""Opciones de propuesta con máquina, especificaciones y garantía: forman parte de lo que el cliente firma."""
from test_propuestas_facturas import _decidir, _enviar, _opcion, _ver, orden  # noqa: F401


def test_detalles_de_opcion_se_guardan_y_firman(client, toks, orden):
    jid, ct = orden
    o1 = _opcion("GOOD", 90, 20, titulo="Reparar compresor", garantia="30 días mano de obra",
                 especificaciones="Capacitor 45/5 µF", equipoPropuesto="  ")
    o2 = _opcion("BETTER", 900, 0, titulo="Unidad nueva", equipoPropuesto="Condensadora 3 t 16 SEER (ficticia)",
                 garantia="5 años compresor")
    r1 = _enviar(client, toks, jid, [o1, o2])
    vis = _ver(client, ct, jid)["propuestas"][-1]["opciones"]
    assert vis[0]["garantia"] == "30 días mano de obra" and "equipoPropuesto" not in vis[0], "vacíos no se guardan"
    assert vis[1]["equipoPropuesto"].startswith("Condensadora") and vis[1]["garantia"] == "5 años compresor"
    o2b = dict(o2, garantia="1 año compresor")                    # solo cambia la garantía
    r2 = _enviar(client, toks, jid, [o1, o2b])
    assert r2["revision"] == r1["revision"] + 1 and r2["contenidoSha256"] != r1["contenidoSha256"]
    viejo = _decidir(client, ct, r2["id"], r1["contenidoSha256"], opcion="BETTER")
    assert viejo.status_code == 409, "firmar lo que se vio antes del cambio de garantía no vale"
    assert _decidir(client, ct, r2["id"], r2["contenidoSha256"], opcion="BETTER").status_code == 200


def test_detalle_demasiado_largo_o_no_texto(client, toks, orden):
    jid, _ = orden
    from test_propuestas_facturas import _push
    for malo in ({"garantia": "x" * 301}, {"especificaciones": 5}):
        r = _push(client, toks["tec"], proposals=[{"localId": 777, "jobId": jid,
                                                   "opciones": [_opcion("GOOD", 10, 0, titulo="T", **malo)]}])
        assert r["proposals"]["results"][0]["status"] == "REJECTED", malo
