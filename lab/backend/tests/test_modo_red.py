"""Modo red del laboratorio: nunca arranca sin autorización, TLS y lista blanca; IP no autorizada → 403."""
import importlib.util
import os
import sys

import pytest

from conftest import h

RUN = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "run_lab.py")


def _cfg(monkeypatch, **env):
    for k in ("LC_LAB_TLS", "LC_LAB_LAN", "LC_LAB_LAN_AUTORIZADO", "LC_LAB_HOST", "LC_LAB_ALLOW_IPS"):
        monkeypatch.delenv(k, raising=False)
    for k, v in env.items():
        monkeypatch.setenv(k, v)
    spec = importlib.util.spec_from_file_location("run_lab_t", RUN)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m.configuracion()


def test_por_defecto_solo_localhost_http(monkeypatch):
    assert _cfg(monkeypatch) == ("127.0.0.1", None, None)


@pytest.mark.parametrize("env,motivo", [
    ({"LC_LAB_LAN": "1", "LC_LAB_TLS": "1", "LC_LAB_HOST": "10.0.0.121", "LC_LAB_ALLOW_IPS": "10.0.0.50"}, "NO autorizado"),
    ({"LC_LAB_LAN": "1", "LC_LAB_LAN_AUTORIZADO": "si-propietario", "LC_LAB_HOST": "10.0.0.121",
      "LC_LAB_ALLOW_IPS": "10.0.0.50"}, "sin TLS"),
    ({"LC_LAB_LAN": "1", "LC_LAB_LAN_AUTORIZADO": "si-propietario", "LC_LAB_TLS": "1", "LC_LAB_HOST": "0.0.0.0",
      "LC_LAB_ALLOW_IPS": "10.0.0.50"}, "IP privada"),
    ({"LC_LAB_LAN": "1", "LC_LAB_LAN_AUTORIZADO": "si-propietario", "LC_LAB_TLS": "1", "LC_LAB_HOST": "8.8.8.8",
      "LC_LAB_ALLOW_IPS": "10.0.0.50"}, "IP privada"),
    ({"LC_LAB_LAN": "1", "LC_LAB_LAN_AUTORIZADO": "si-propietario", "LC_LAB_TLS": "1", "LC_LAB_HOST": "10.0.0.121"},
     "lista blanca"),
])
def test_modo_red_exige_todas_las_condiciones(monkeypatch, env, motivo):
    with pytest.raises(SystemExit) as e:
        _cfg(monkeypatch, **env)
    assert motivo in str(e.value)


def test_modo_red_completo(monkeypatch):
    host, ctx, ips = _cfg(monkeypatch, LC_LAB_LAN="1", LC_LAB_LAN_AUTORIZADO="si-propietario", LC_LAB_TLS="1",
                          LC_LAB_HOST="10.0.0.121", LC_LAB_ALLOW_IPS="10.0.0.50")
    assert host == "10.0.0.121" and ctx and ips == {"10.0.0.50", "10.0.0.121"}


def test_lista_blanca_rechaza_ip_ajena(app, client, toks):
    app.config["ALLOW_IPS"] = {"10.0.0.50", "10.0.0.121"}
    ajena = client.get("/api/v2/health", environ_base={"REMOTE_ADDR": "10.0.0.77"})
    assert ajena.status_code == 403
    ok = client.get("/api/v2/sync/pull?since=0", headers=h(toks["tec"]), environ_base={"REMOTE_ADDR": "10.0.0.50"})
    assert ok.status_code == 200
    sin_token = client.get("/api/v2/sync/pull?since=0", environ_base={"REMOTE_ADDR": "10.0.0.50"})
    assert sin_token.status_code == 401, "la lista blanca no sustituye a la autenticación"
