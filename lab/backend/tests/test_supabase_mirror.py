"""Espejo Flask → Supabase con un Supabase simulado en memoria (sin red)."""
import sqlite3

import pytest

from lc_lab.db import now_ms
from lc_lab.supabase_mirror import Espejo

CFG = {"url": "https://x.supabase.co", "publishable_key": "pk", "email": "sync@lab", "password": "x"}


class SupabaseFalso:
    """Imita PostgREST: alta con ignore-duplicates y PATCH con la guarda (version, seq)."""

    def __init__(self):
        self.jobs, self.historial, self.recibos = {}, {}, {}
        self.caido = False
        self.profiles = {"tec-t-01": "uuid-tec-01"}

    def get(self, path):
        code = path.rsplit("technician_code=eq.", 1)[1]
        return 200, ([{"id": self.profiles[code]}] if code in self.profiles else [])

    def insertar(self, tabla, filas, conflicto):
        if self.caido:
            return 503, "sin red"
        destino = {"jobs": self.jobs, "job_history": self.historial, "sync_receipts": self.recibos}[tabla]
        for f in filas:
            clave = tuple(f[k] for k in conflicto.split(","))
            destino.setdefault(clave, dict(f))
        return 201, None

    def actualizar_si_mas_nueva(self, fila):
        if self.caido:
            return 503, "sin red"
        r = self.jobs[(fila["id"],)]
        if (r["version"], r["seq"]) < (fila["version"], fila["seq"]):
            r.update(fila)
        return 204, None


def _orden(db, jid, estado="NUEVO", tec=None, created=None):
    db.execute("UPDATE counters SET value = value + 1 WHERE name = 'job_seq'")
    seq = db.execute("SELECT value FROM counters WHERE name = 'job_seq'").fetchone()[0]
    t = created or now_ms()
    db.execute("INSERT INTO jobs(id, version, seq, origin, estado, technician_code, cliente, codigo_acceso_enc, "
               "created_at, updated_at) VALUES (?,1,?,'CUSTOMER',?,?,'QA ficticio','SECRETO',?,?)",
               (jid, seq, estado, tec, t, t))
    db.commit()


def _cambiar(db, jid, estado):
    db.execute("UPDATE counters SET value = value + 1 WHERE name = 'job_seq'")
    seq = db.execute("SELECT value FROM counters WHERE name = 'job_seq'").fetchone()[0]
    db.execute("UPDATE jobs SET estado = ?, version = version + 1, seq = ?, updated_at = ? WHERE id = ?",
               (estado, seq, now_ms(), jid))
    db.execute("INSERT INTO job_history(job_id, version, field, old_value, new_value, actor, source, ts) "
               "SELECT id, version, 'estado', 'x', ?, 'tec_t', 'DEVICE', ? FROM jobs WHERE id = ?",
               (estado, now_ms(), jid))
    db.commit()


@pytest.fixture
def entorno(app):
    db = sqlite3.connect(app.config["DATABASE"])
    _orden(db, 111, created=now_ms() - 10_000_000)   # histórica: existe antes de activar el espejo
    sb = SupabaseFalso()
    return db, sb, Espejo(app.config["DATABASE"], CFG, cliente=sb)


def test_no_exporta_historicas_ni_secretos(entorno):
    db, sb, esp = entorno
    _cambiar(db, 111, "EN_RUTA")   # aunque cambie, la histórica no sale sin autorización
    _orden(db, 900001, tec="tec-t-01")
    esp.ciclo()
    assert list(sb.jobs) == [(900001,)]
    fila = sb.jobs[(900001,)]
    assert fila["technician_id"] == "uuid-tec-01"
    assert "codigo_acceso_enc" not in fila["datos"] and fila["datos"]["technician_code"] == "tec-t-01"


def test_versiones_historial_y_reenvio_sin_duplicados(entorno):
    db, sb, esp = entorno
    _orden(db, 900002)
    esp.ciclo()
    _cambiar(db, 900002, "ASIGNADO")
    _cambiar(db, 900002, "EN_PROGRESO")
    esp.ciclo()
    assert sb.jobs[(900002,)]["version"] == 3 and sb.jobs[(900002,)]["estado"] == "EN_PROGRESO"
    assert len(sb.historial) == 2
    # reenvío completo desde cero (p. ej. estado local perdido): ni duplica ni retrocede
    db.execute("UPDATE supabase_mirror_state SET value = 0 WHERE name IN ('jobs_seq', 'history_id')")
    db.commit()
    esp.ciclo()
    assert len(sb.jobs) == 1 and len(sb.historial) == 2 and sb.jobs[(900002,)]["version"] == 3


def test_caida_no_avanza_cursor_y_recupera(entorno):
    db, sb, esp = entorno
    _orden(db, 900003)
    sb.caido = True
    with pytest.raises(RuntimeError):
        esp.ciclo()
    assert sb.jobs == {}
    _cambiar(db, 900003, "ASIGNADO")   # cambia mientras está sin red
    sb.caido = False
    esp.ciclo()
    assert sb.jobs[(900003,)]["version"] == 2 and len(sb.jobs) == 1
    ok = db.execute("SELECT ok FROM supabase_mirror_log ORDER BY id").fetchall()
    assert [r[0] for r in ok][:1] == [0] and ok[-1][0] == 1


def test_version_remota_mas_nueva_no_se_pisa(entorno):
    db, sb, esp = entorno
    _orden(db, 900004)
    esp.ciclo()
    sb.jobs[(900004,)].update(version=9, seq=10**9, estado="CERRADO_EN_NUBE")
    _cambiar(db, 900004, "ASIGNADO")
    esp.ciclo()
    assert sb.jobs[(900004,)]["estado"] == "CERRADO_EN_NUBE"


def test_recibos_idempotentes_con_ts_repetido(entorno):
    db, sb, esp = entorno
    _orden(db, 900005)
    t = now_ms()
    db.executemany("INSERT INTO sync_receipts(device_id, job_id, client_updated_at, status, version, ts) "
                   "VALUES ('dev-qa', 900005, ?, 'ACCEPTED', 1, ?)", [(i, t) for i in range(60)])
    db.commit()
    esp.ciclo()
    esp.ciclo()
    assert len(sb.recibos) == 60
    db.execute("UPDATE supabase_mirror_state SET value = 0 WHERE name = 'receipts_rowid'")
    db.commit()
    esp.ciclo()
    esp.ciclo()
    assert len(sb.recibos) == 60
