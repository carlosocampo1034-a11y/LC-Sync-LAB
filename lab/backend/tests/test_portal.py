"""Portal web del cliente (Safari): archivos estáticos, cabeceras de seguridad y mismo backend."""
import os
import re

PORTAL = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "lc_lab", "static", "portal")


def test_portal_sirve_la_app_con_cabeceras_estrictas(client):
    r = client.get("/portal/")
    assert r.status_code == 200 and r.mimetype == "text/html"
    assert r.headers["Content-Type"].count("charset") == 1
    csp = r.headers["Content-Security-Policy"]
    assert "script-src 'self'" in csp and "unsafe-inline" not in csp and "frame-ancestors 'none'" in csp
    assert r.headers["X-Content-Type-Options"] == "nosniff" and r.headers["Cache-Control"] == "no-store"
    for f, tipo in (("app.js", "text/javascript"), ("app.css", "text/css"), ("manifest.webmanifest", "application/manifest+json"),
                    ("icon-180.png", "image/png")):
        x = client.get("/portal/" + f)
        assert x.status_code == 200 and x.mimetype == tipo, f


def test_portal_no_sirve_nada_fuera_de_su_carpeta(client):
    for ruta in ("/portal/../lc_lab/db.py", "/portal/%2e%2e/run_lab.py", "/portal/x.py", "/portal/sub/app.js",
                 "/portal/../instance/lab.db", "/portal/..%2finstance%2ftls%2fservidor.key"):
        assert client.get(ruta).status_code == 404, ruta


def test_portal_sin_scripts_en_linea_ni_innerhtml_con_datos():
    html = open(os.path.join(PORTAL, "index.html")).read()
    assert not re.search(r"<script(?![^>]*\bsrc=)", html), "sin scripts en línea (la CSP los bloquearía)"
    assert " on" not in re.sub(r'"[^"]*"', "", html).lower().replace("content", ""), "sin manejadores en línea"
    js = open(os.path.join(PORTAL, "app.js")).read()
    assert ".innerHTML" not in js and ".outerHTML" not in js and "insertAdjacentHTML" not in js and "eval(" not in js
    assert "/api/v2" in js and "localhost" not in js and "http://" not in js, "mismo origen, sin direcciones fijas"
