"""L&C — backend de LABORATORIO (datos ficticios, solo localhost). Independiente de ~/LC/server."""
import json
import os
import secrets

from cryptography.fernet import Fernet
from flask import Flask, jsonify

from .db import close_db, init_db


def _config_local(instance):
    """Claves propias del laboratorio, generadas una vez y guardadas con permisos 600."""
    os.makedirs(instance, mode=0o700, exist_ok=True)
    ruta = os.path.join(instance, "lab_config.json")
    if not os.path.exists(ruta):
        cfg = {"SECRET_KEY": secrets.token_urlsafe(48), "FERNET_KEY": Fernet.generate_key().decode()}
        fd = os.open(ruta, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(cfg, f)
    with open(ruta) as f:
        return json.load(f)


def create_app(instance=None, overrides=None):
    instance = instance or os.environ.get("LC_LAB_INSTANCE") or os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "instance")
    app = Flask(__name__)
    cfg = _config_local(instance)
    app.config.update(
        SECRET_KEY=cfg["SECRET_KEY"], FERNET_KEY=cfg["FERNET_KEY"],
        DATABASE=os.path.join(instance, "lab.db"), UPLOADS=os.path.join(instance, "uploads"),
        ZONA_HORARIA="America/New_York", IMPUESTO=0.07,
        # Inyección de fallos SOLO de laboratorio (instance/fault.json), apagada salvo LC_LAB_FAULTS=1
        LAB_FAULTS=os.environ.get("LC_LAB_FAULTS") == "1",
        MAX_CONTENT_LENGTH=12 * 1024 * 1024,
        SESSION_COOKIE_HTTPONLY=True, SESSION_COOKIE_SAMESITE="Strict",
    )
    if overrides:
        app.config.update(overrides)
    os.makedirs(app.config["UPLOADS"], mode=0o700, exist_ok=True)
    init_db(app.config["DATABASE"])
    app.teardown_appcontext(close_db)

    from . import api_auth_customer, api_ops, api_sync, web_control
    app.register_blueprint(api_auth_customer.bp)
    app.register_blueprint(api_ops.bp)
    app.register_blueprint(api_sync.bp)
    app.register_blueprint(web_control.bp)
    from . import portal
    app.register_blueprint(portal.bp)

    # Lista blanca de IP (solo se usa en el modo red del laboratorio): cualquier otra dirección recibe 403
    @app.before_request
    def _lista_blanca():
        from flask import jsonify, request
        permitidas = app.config.get("ALLOW_IPS")
        if permitidas and request.remote_addr not in permitidas and request.remote_addr != "127.0.0.1":
            app.logger.warning("acceso rechazado desde %s (fuera de la lista blanca)", request.remote_addr)
            return jsonify(error="dispositivo no autorizado en el laboratorio"), 403

    @app.get("/api/v2/health")
    def health():
        return jsonify(ok=True, entorno="LABORATORIO", version="v2")

    @app.errorhandler(413)
    def demasiado_grande(_e):
        return jsonify(error="archivo demasiado grande"), 413

    return app
