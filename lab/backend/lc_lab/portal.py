"""Portal web de L&C Customer (Safari / iPhone y cualquier navegador). Mismo origen y mismos contratos /api/v2:
no hay clientes ni órdenes paralelos. Solo archivos estáticos con una política de seguridad estricta."""
import os

from flask import Blueprint, abort, send_from_directory

bp = Blueprint("portal", __name__, url_prefix="/portal")
D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static", "portal")
TIPOS = {".html": "text/html", ".js": "text/javascript", ".css": "text/css",   # Flask añade "; charset=utf-8"
         ".webmanifest": "application/manifest+json", ".png": "image/png"}
CSP = ("default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; "
       "manifest-src 'self'; frame-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")


@bp.after_request
def _cabeceras(r):
    r.headers["Content-Security-Policy"] = CSP
    r.headers["X-Content-Type-Options"] = "nosniff"
    r.headers["Referrer-Policy"] = "no-referrer"
    r.headers["Cache-Control"] = "no-store"
    r.headers["Permissions-Policy"] = "geolocation=(), microphone=(), camera=(self)"
    return r


@bp.get("/")
def inicio():
    return _archivo("index.html")


@bp.get("/<path:nombre>")
def _archivo(nombre):
    ext = os.path.splitext(nombre)[1]
    if "/" in nombre or ext not in TIPOS:
        abort(404)
    return send_from_directory(D, nombre, mimetype=TIPOS[ext], max_age=0)
