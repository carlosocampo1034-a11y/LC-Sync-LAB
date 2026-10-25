"""Backend de laboratorio. Por defecto SOLO en 0.0.0.0 por HTTP (sin acceso desde la red ni túneles).

Modos opcionales (variables de entorno):
  LC_LAB_TLS=1                      HTTPS con el certificado de laboratorio (instance/tls), en 0.0.0.0
  LC_LAB_LAN=1                      escuchar en la IP Wi-Fi privada para un segundo teléfono. Solo arranca si:
      LC_LAB_LAN_AUTORIZADO=si-propietario   (autorización explícita del propietario)
      LC_LAB_TLS=1                           (nunca en claro por la red)
      LC_LAB_HOST=<IP privada del S24>       (10.x / 172.16-31.x / 192.168.x; nunca 0.0.0.0)
      LC_LAB_ALLOW_IPS=<ip1,ip2>             (lista blanca: los teléfonos de la prueba)
      LC_LAB_LAN_MINUTOS=<n>                 (cierre automático; por defecto 90, máximo 240)
Se enlaza SOLO a la IPv4 privada indicada: nunca a 0.0.0.0 ni a IPv6 (el teléfono tiene IPv6 globales).
"""
import ipaddress
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lc_lab import create_app  # noqa: E402
from lc_lab.seed import sembrar  # noqa: E402

PORT = int(os.environ.get("LC_LAB_PORT", "5055"))


def configuracion():
    """(host, ssl_context, allow_ips) o SystemExit con el motivo. Sin las condiciones, nunca sale de 0.0.0.0."""
    base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "instance", "tls")
    tls = os.environ.get("LC_LAB_TLS") == "1"
    ctx = (os.path.join(base, "servidor.pem"), os.path.join(base, "servidor.key")) if tls else None
    if tls and not all(os.path.exists(p) for p in ctx):
        raise SystemExit("faltan los certificados de laboratorio en instance/tls")
    if os.environ.get("LC_LAB_LAN") != "1":
        return "0.0.0.0", ctx, None
    if os.environ.get("LC_LAB_LAN_AUTORIZADO") != "si-propietario":
        raise SystemExit("modo red NO autorizado: falta LC_LAB_LAN_AUTORIZADO=si-propietario")
    if not tls:
        raise SystemExit("modo red sin TLS no permitido (LC_LAB_TLS=1)")
    host = os.environ.get("LC_LAB_HOST", "")
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        raise SystemExit("LC_LAB_HOST debe ser la IP privada del teléfono servidor")
    if not ip.is_private or ip.is_loopback or ip.is_unspecified:
        raise SystemExit("LC_LAB_HOST debe ser una IP privada de la Wi-Fi local (nunca 0.0.0.0 ni pública)")
    permitidas = [x.strip() for x in os.environ.get("LC_LAB_ALLOW_IPS", "").split(",") if x.strip()]
    if not permitidas or any(not ipaddress.ip_address(x).is_private for x in permitidas):
        raise SystemExit("LC_LAB_ALLOW_IPS: lista blanca de IP privadas de los teléfonos de la prueba")
    return host, ctx, set(permitidas) | {host}


def cierre_automatico(minutos):
    """Modo red temporal: el proceso termina solo al cumplirse el plazo (cierre seguro por defecto)."""
    import threading
    import time

    def _fin():
        time.sleep(minutos * 60)
        print(f"L&C LAB · plazo de {minutos} min cumplido: cierre automático del modo red", flush=True)
        os._exit(0)
    threading.Thread(target=_fin, daemon=True).start()


if __name__ == "__main__":
    HOST, CTX, PERMITIDAS = configuracion()
    if PERMITIDAS:
        minutos = max(1, min(int(os.environ.get("LC_LAB_LAN_MINUTOS", "90")), 240))
        cierre_automatico(minutos)
        print(f"L&C LAB · MODO RED TEMPORAL · se cerrará solo en {minutos} min", flush=True)
    app = create_app()
    if PERMITIDAS:
        app.config["ALLOW_IPS"] = PERMITIDAS
    n = sembrar(app.config["DATABASE"], os.path.dirname(app.config["DATABASE"]))
    # Espejo hacia Supabase (solo si el propietario creó instance/supabase_sync.json); Flask es el único escritor
    from lc_lab.supabase_mirror import arrancar  # noqa: E402
    arrancar(app, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "local.properties"))
    # Puente con LC-Sync-QA (solo si existe instance/qa_bridge.json con una cuenta DESPACHO; nunca service_role)
    from lc_lab import qa_bridge  # noqa: E402
    qa_bridge.arrancar(app)
    esquema = "https" if CTX else "http"
    print(f"L&C LAB · base {app.config['DATABASE']} · usuarios nuevos: {n} · {esquema}://{HOST}:{PORT}"
          + (f" · lista blanca: {sorted(PERMITIDAS)}" if PERMITIDAS else ""), flush=True)
    app.run(host=HOST, port=PORT, debug=False, threaded=True, use_reloader=False)
