"""CSRF del Control: recién iniciada la sesión (sin token aún) un POST sin csrf no puede aplicarse."""
from lc_lab import jobs as J
from lc_lab.db import get_db

from conftest import PW


def _orden(app):
    with app.app_context():
        db = get_db()
        jid = J.crear(db, {"cliente": "Cliente ficticio", "direccion": "1 Calle Ficticia"}, "DISPATCH", "t", "test")
        db.commit()
        return jid


def test_post_sin_csrf_justo_despues_del_login_rechazado(app, client):
    jid = _orden(app)
    r = client.post("/control/login", data={"username": "desp_t", "password": PW})
    assert r.status_code == 302
    r = client.post(f"/control/jobs/{jid}/asignar", data={"technicianId": "tec-t-01", "version": 1})
    assert r.status_code == 400
    r = client.post(f"/control/jobs/{jid}/asignar", data={"technicianId": "tec-t-01", "version": 1, "csrf": ""})
    assert r.status_code == 400
    with app.app_context():
        assert get_db().execute("SELECT technician_code FROM jobs WHERE id = ?", (jid,)).fetchone()[0] is None


def test_post_con_csrf_valido_se_aplica(app, client):
    jid = _orden(app)
    client.post("/control/login", data={"username": "desp_t", "password": PW})
    client.get(f"/control/jobs/{jid}")                        # pintar la página genera el token
    with client.session_transaction() as s:
        tok = s["csrf"]
    r = client.post(f"/control/jobs/{jid}/asignar", data={"technicianId": "tec-t-01", "version": 1, "csrf": tok})
    assert r.status_code == 302
    with app.app_context():
        assert get_db().execute("SELECT technician_code FROM jobs WHERE id = ?", (jid,)).fetchone()[0] == "tec-t-01"
