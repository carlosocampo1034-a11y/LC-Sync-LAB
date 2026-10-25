"""Puente Control original ↔ LC-Sync-QA (Supabase). Une el Control Flask con las apps QA en una sola plataforma.

- Solo se activa si existe instance/qa_bridge.json (permisos 600) con url, publishable_key, email y password de
  una cuenta DESPACHO de LC-Sync-QA. Nunca usa service_role: todo pasa por lc-api con RLS y control de versión.
- Bajada: GET /orders?since=<updated_at> → cada orden QA se crea o actualiza en el SQLite del Control con
  J.crear / J.aplicar (actor lc-sync-qa), así queda en el historial y el tablero la muestra como cualquier otra.
- Subida: lo que despacho cambia en el Control (técnico, estado, notas, prioridad, fecha) se envía con
  PATCH /orders/<id> y la versión QA conocida. 409 → gana el servidor: se baja la orden y se registra el conflicto.
- Técnicos: se copian de GET /technicians (código QA-T01…) para que el selector del Control los ofrezca.
- Nunca se registran claves ni tokens.
"""
import json
import logging
import os
import secrets
import sqlite3
import threading
import time
import urllib.error
import urllib.request

from . import jobs as J
from .db import audit, now_ms
from .security import hash_password

log = logging.getLogger("lc_lab.qa_bridge")

ACTOR = "lc-sync-qa"
ORIGENES = {"DEVICE", "CUSTOMER", "DISPATCH"}
# estado_dispatch: el Control usa ASSIGNED/QUEUE; las apps QA, ASIGNADO/SIN ASIGNAR
DISPATCH_A_QA = {"ASSIGNED": "ASIGNADO", "QUEUE": "SIN ASIGNAR"}
DISPATCH_DE_QA = {v: k for k, v in DISPATCH_A_QA.items()}
# columna del Control -> campo que acepta lc-api para DESPACHO (lc_patch_order)
SUBIDA = {"estado": "estado", "estado_dispatch": "estado_dispatch", "notas": "notas",
          "prioridad": "prioridad", "programado_para": "programado_para", "technician_code": "technician_id"}

ESQUEMA = """
CREATE TABLE IF NOT EXISTS qa_bridge_map (
    local_id INTEGER PRIMARY KEY REFERENCES jobs(id),
    qa_id INTEGER NOT NULL UNIQUE,
    qa_version INTEGER NOT NULL,
    local_version INTEGER NOT NULL,      -- versión local que ya refleja qa_version
    synced_seq INTEGER NOT NULL          -- seq local tras la última sincronización
);
CREATE TABLE IF NOT EXISTS qa_bridge_state (name TEXT PRIMARY KEY, value INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS qa_bridge_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, tipo TEXT NOT NULL, qa_id INTEGER, detalle TEXT
);
"""


def leer_config(instance):
    ruta = os.path.join(instance, "qa_bridge.json")
    if not os.path.exists(ruta):
        return None
    with open(ruta) as f:
        cfg = json.load(f)
    if cfg.get("service_role_key"):
        raise ValueError("qa_bridge: service_role no permitido; usa una cuenta DESPACHO")
    if not all(cfg.get(k) for k in ("url", "publishable_key", "email", "password")):
        return None
    cfg["url"] = cfg["url"].rstrip("/")
    return cfg


class LcApi:
    """Cliente mínimo de lc-api (urllib). Sesión de la cuenta despacho solo en memoria."""

    def __init__(self, cfg, timeout=20):
        self.cfg, self.timeout = cfg, timeout
        self.at, self.exp = None, 0

    def _req(self, method, url, body=None, token=None):
        h = {"apikey": self.cfg["publishable_key"], "content-type": "application/json", "user-agent": "lc-control"}
        if token:
            h["authorization"] = "Bearer " + token
        data = json.dumps(body).encode() if body is not None else None
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=h),
                                        timeout=self.timeout) as r:
                return r.status, json.loads(r.read() or b"null")
        except urllib.error.HTTPError as e:
            try:
                return e.code, json.loads(e.read() or b"null")
            except ValueError:
                return e.code, None

    def _token(self):
        if self.at and time.time() < self.exp - 60:
            return self.at
        st, b = self._req("POST", self.cfg["url"] + "/auth/v1/token?grant_type=password",
                          {"email": self.cfg["email"], "password": self.cfg["password"]})
        if st != 200 or not b or "access_token" not in b:
            raise RuntimeError(f"login despacho QA HTTP {st}")
        self.at, self.exp = b["access_token"], time.time() + int(b.get("expires_in", 3600))
        return self.at

    def call(self, method, path, body=None):
        st, b = self._req(method, self.cfg["url"] + "/functions/v1/lc-api" + path, body, self._token())
        if st == 401:          # token caducado o revocado: un reintento con sesión nueva
            self.at = None
            st, b = self._req(method, self.cfg["url"] + "/functions/v1/lc-api" + path, body, self._token())
        return st, b


def preparar(db):
    db.executescript(ESQUEMA)


def _estado(db, name, default=0):
    r = db.execute("SELECT value FROM qa_bridge_state WHERE name = ?", (name,)).fetchone()
    return r[0] if r else default


def _set_estado(db, name, value):
    db.execute("INSERT INTO qa_bridge_state(name, value) VALUES (?, ?) "
               "ON CONFLICT(name) DO UPDATE SET value = excluded.value", (name, value))


def _log(db, tipo, qa_id=None, detalle=None):
    db.execute("INSERT INTO qa_bridge_log(ts, tipo, qa_id, detalle) VALUES (?,?,?,?)", (now_ms(), tipo, qa_id, detalle))


def _datos_desde_qa(o):
    """Orden de lc-api -> nombres api del Control (J.CAMPOS)."""
    d = {"estado": o.get("estado") or "PENDIENTE",
         "estadoDispatch": DISPATCH_DE_QA.get(o.get("estado_dispatch"), o.get("estado_dispatch")),
         "technicianId": o.get("technician_code"),
         "prioridad": o.get("prioridad"), "categoria": o.get("categoria"),
         "cliente": o.get("cliente"), "telefono": o.get("telefono"), "direccion": o.get("direccion"),
         "descripcion": o.get("descripcion"), "notas": o.get("notas")}
    if isinstance(o.get("programado_para"), int):
        d["programadoPara"] = o["programado_para"]
    return d


def sincronizar_tecnicos(db, api):
    st, lista = api.call("GET", "/technicians")
    if st != 200:
        _log(db, "TECNICOS_ERROR", detalle=f"HTTP {st}")
        return {}
    ids = {}
    for t in lista or []:
        code = t.get("technician_code")
        if not code:
            continue
        ids[code] = t["id"]
        fila = db.execute("SELECT id FROM users WHERE technician_code = ? AND role = 'TECHNICIAN'", (code,)).fetchone()
        if fila is None:
            # usuario de referencia para el selector del Control; la contraseña es aleatoria y no se usa
            db.execute("INSERT INTO users(username, password_hash, role, display_name, technician_code, created_at) "
                       "VALUES (?,?,?,?,?,?)", ("qa-" + code.lower(), hash_password(secrets.token_urlsafe(24)),
                                                "TECHNICIAN", t.get("display_name") or code, code, now_ms()))
    return ids


def aplicar_orden_qa(db, o):
    """Crea o actualiza en el Control la orden QA `o`. Devuelve 'CREADA', 'ACTUALIZADA', 'IGUAL' o 'PENDIENTE_LOCAL'."""
    m = db.execute("SELECT * FROM qa_bridge_map WHERE qa_id = ?", (o["id"],)).fetchone()
    datos = _datos_desde_qa(o)
    if m is None:
        libre = db.execute("SELECT 1 FROM jobs WHERE id = ?", (o["id"],)).fetchone() is None
        origen = o.get("origin") if o.get("origin") in ORIGENES else "DISPATCH"
        jid = J.crear(db, datos, origen, ACTOR, "qa-bridge", device="lc-sync-qa", job_id=o["id"] if libre else None)
        row = db.execute("SELECT version, seq FROM jobs WHERE id = ?", (jid,)).fetchone()
        db.execute("INSERT INTO qa_bridge_map(local_id, qa_id, qa_version, local_version, synced_seq) VALUES (?,?,?,?,?)",
                   (jid, o["id"], o["version"], row["version"], row["seq"]))
        return "CREADA"
    row = db.execute("SELECT * FROM jobs WHERE id = ?", (m["local_id"],)).fetchone()
    if row["seq"] > m["synced_seq"]:
        return "PENDIENTE_LOCAL"           # primero se sube el cambio de despacho
    if o["version"] <= m["qa_version"]:
        return "IGUAL"
    st, v, _conf = J.aplicar(db, row, datos, ACTOR, "qa-bridge", version_base=row["version"], device="lc-sync-qa")
    nuevo = db.execute("SELECT seq FROM jobs WHERE id = ?", (row["id"],)).fetchone()["seq"]
    db.execute("UPDATE qa_bridge_map SET qa_version = ?, local_version = ?, synced_seq = ? WHERE local_id = ?",
               (o["version"], v, nuevo, row["id"]))
    return "ACTUALIZADA"


def bajar(db, api):
    cursor = _estado(db, "pull_since")
    st, lista = api.call("GET", f"/orders?since={cursor}")
    if st != 200:
        _log(db, "BAJADA_ERROR", detalle=f"HTTP {st}")
        return 0
    n = 0
    for o in lista or []:
        r = aplicar_orden_qa(db, o)
        if r in ("CREADA", "ACTUALIZADA"):
            n += 1
            _log(db, "BAJADA_" + r, o["id"], f"v{o['version']} {o.get('estado')} {o.get('technician_code') or '-'}")
        cursor = max(cursor, int(o.get("updated_at") or 0))
    _set_estado(db, "pull_since", cursor)
    return n


def subir(db, api, tecnicos):
    n = 0
    for m in db.execute("SELECT m.*, j.seq AS seq_actual, j.version AS v_actual FROM qa_bridge_map m "
                        "JOIN jobs j ON j.id = m.local_id WHERE j.seq > m.synced_seq").fetchall():
        row = db.execute("SELECT * FROM jobs WHERE id = ?", (m["local_id"],)).fetchone()
        campos = J.campos_cambiados_desde(db, row["id"], m["local_version"], excluir_actor=ACTOR)
        cuerpo = {}
        for col in sorted(campos & set(SUBIDA)):
            v = row[col]
            if col == "technician_code":
                if v and v not in tecnicos:
                    _log(db, "SUBIDA_OMITIDA", m["qa_id"], f"técnico {v} no existe en QA")
                    continue
                v = tecnicos.get(v) if v else None
            elif col == "estado_dispatch":
                v = DISPATCH_A_QA.get(v, v)
            cuerpo[SUBIDA[col]] = v
        if not cuerpo:
            db.execute("UPDATE qa_bridge_map SET synced_seq = ?, local_version = ? WHERE local_id = ?",
                       (m["seq_actual"], m["v_actual"], m["local_id"]))
            continue
        cuerpo.update(version=m["qa_version"], device="control-original", updatedAt=now_ms())
        st, b = api.call("PATCH", f"/orders/{m['qa_id']}", cuerpo)
        if st == 200:
            db.execute("UPDATE qa_bridge_map SET qa_version = ?, local_version = ?, synced_seq = ? WHERE local_id = ?",
                       (b["version"], m["v_actual"], m["seq_actual"], m["local_id"]))
            _log(db, "SUBIDA_OK", m["qa_id"], f"v{b['version']} {','.join(sorted(k for k in cuerpo if k not in ('version', 'device', 'updatedAt')))}")
            audit(db, ACTOR, "QA_PUSH", "job", m["local_id"], f"qa v{b['version']}")
            n += 1
        elif st == 409:
            # gana el servidor: se marca sincronizado y la próxima bajada trae su versión
            db.execute("UPDATE qa_bridge_map SET synced_seq = ?, local_version = ? WHERE local_id = ?",
                       (m["seq_actual"], m["v_actual"], m["local_id"]))
            st2, actual = api.call("GET", f"/orders/{m['qa_id']}")
            if st2 == 200:
                aplicar_orden_qa(db, actual)
            _log(db, "SUBIDA_CONFLICTO", m["qa_id"], json.dumps((b or {}).get("conflictFields") or []))
        else:
            _log(db, "SUBIDA_ERROR", m["qa_id"], f"HTTP {st} {(b or {}).get('error', '')}")
    return n


def ciclo(db, api, estado):
    """Un ciclo completo: técnicos (cada 5 min), subida de cambios de despacho y bajada de órdenes QA."""
    db.execute("BEGIN IMMEDIATE")
    try:
        if not estado.get("tecnicos") or time.time() - estado.get("tecnicos_ts", 0) > 300:
            estado["tecnicos"], estado["tecnicos_ts"] = sincronizar_tecnicos(db, api), time.time()
        subidas = subir(db, api, estado["tecnicos"])
        bajadas = bajar(db, api)
        _set_estado(db, "ultimo_ok", now_ms())
        db.execute("COMMIT")
        return subidas, bajadas
    except Exception:
        db.execute("ROLLBACK")
        raise


def arrancar(app):
    instance = os.path.dirname(app.config["DATABASE"])
    cfg = leer_config(instance)
    if not cfg:
        return None
    intervalo = max(2, int(cfg.get("intervalo_s", 4)))
    api = LcApi(cfg)

    def _bucle():
        db = sqlite3.connect(app.config["DATABASE"], timeout=15, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys = ON")
        preparar(db)
        estado, espera = {}, intervalo
        while True:
            try:
                s, b = ciclo(db, api, estado)
                if s or b:
                    log.info("qa_bridge: %d subidas, %d bajadas", s, b)
                espera = intervalo
            except Exception as e:                       # sin red, login, etc.: backoff hasta 2 min
                log.warning("qa_bridge: %s", type(e).__name__ + (f": {e}" if isinstance(e, RuntimeError) else ""))
                espera = min(espera * 2, 120)
            time.sleep(espera)

    threading.Thread(target=_bucle, name="qa_bridge", daemon=True).start()
    log.info("qa_bridge activo contra %s cada %ss", cfg["url"].split("//")[-1].split(".")[0], intervalo)
    return True
