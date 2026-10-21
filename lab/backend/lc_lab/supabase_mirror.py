"""Espejo Flask LAB → Supabase (LC-Sync-LAB). Flask es el ÚNICO escritor; las apps solo leen y escuchan Realtime.

- Fuente de verdad: SQLite del laboratorio. Cada cambio de una orden incrementa jobs.seq (contador job_seq),
  así que el espejo recorre `seq > cursor`: incremental, reanudable y sin cola aparte.
- Idempotencia: alta con `on_conflict=id` + ignore-duplicates; actualización solo si la fila remota es más vieja
  (`version < v` o misma versión con `seq < s`). Reenviar un lote nunca duplica ni retrocede una orden.
- Reintentos: el cursor solo avanza tras la respuesta 2xx de Supabase; ante error espera con backoff (hasta 5 min).
- Credenciales: instance/supabase_sync.json (permisos 600), escrito por el propietario. Sin él, el espejo no arranca.
  Nunca se registran claves ni tokens. Los campos cifrados (código de acceso, llave) no salen del servidor.
- Órdenes históricas: al activarse por primera vez el cursor parte del seq actual y solo se replican órdenes
  creadas desde ese momento, salvo `"incluir_historicas": true` en la configuración.
"""
import json
import logging
import os
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

log = logging.getLogger("lc_lab.supabase_mirror")

LOTE = 50
NO_SALEN = {"codigo_acceso_enc", "ubicacion_llave_enc"}   # secretos cifrados: solo en el servidor
COLUMNAS_PROPIAS = {"id", "version", "seq", "estado", "created_at", "updated_at"}

ESTADO_SQL = """
CREATE TABLE IF NOT EXISTS supabase_mirror_state (name TEXT PRIMARY KEY, value INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS supabase_mirror_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, tipo TEXT NOT NULL,
    enviados INTEGER NOT NULL, ok INTEGER NOT NULL, detalle TEXT
);
"""


def _iso(ms):
    return datetime.fromtimestamp((ms or 0) / 1000, tz=timezone.utc).isoformat() if ms else None


def leer_config(instance, local_properties=None):
    """URL y clave publicable existentes (local.properties del proyecto) + identidad SYNC (instance). None si falta algo."""
    ruta = os.path.join(instance, "supabase_sync.json")
    if not os.path.exists(ruta):
        return None
    with open(ruta) as f:
        cfg = json.load(f)
    if local_properties and os.path.exists(local_properties) and not (cfg.get("url") and cfg.get("publishable_key")):
        props = {}
        with open(local_properties) as f:
            for linea in f:
                if "=" in linea and not linea.lstrip().startswith("#"):
                    k, v = linea.split("=", 1)
                    props[k.strip()] = v.strip()
        cfg.setdefault("url", props.get("SUPABASE_URL"))
        cfg.setdefault("publishable_key", props.get("SUPABASE_ANON_KEY"))
    # Modo service_role: no requiere email/password (bypasa RLS completo en servidor)
    if cfg.get("service_role_key"):
        if not cfg.get("url"):
            return None
    elif not all(cfg.get(k) for k in ("url", "publishable_key", "email", "password")):
        return None
    cfg["url"] = cfg["url"].rstrip("/")
    return cfg


class Supabase:
    """Cliente REST mínimo (urllib, sin dependencias). Sesión solo en memoria."""

    def __init__(self, cfg):
        self.cfg = cfg
        self.at = None
        self.rt = None
        self.exp = 0

    def _http(self, method, path, body=None, headers=None, auth=True):
        srk = self.cfg.get("service_role_key")
        apikey = srk if srk else self.cfg.get("publishable_key", "")
        h = {"apikey": apikey, "Content-Type": "application/json"}
        if auth:
            self._sesion()
            h["Authorization"] = "Bearer " + self.at
        h.update(headers or {})
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.cfg["url"] + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                txt = r.read().decode() or "null"
                return r.status, json.loads(txt)
        except urllib.error.HTTPError as e:
            if e.code == 401 and auth:
                self.at = None   # token rechazado: la próxima vuelta inicia sesión de nuevo
            return e.code, e.read().decode()[:300]

    def _sesion(self):
        # Modo service_role: usa la clave directamente como Bearer, nunca caduca en sesión.
        srk = self.cfg.get("service_role_key")
        if srk:
            if not self.at:
                self.at  = srk
                self.exp = time.time() + 365 * 24 * 3600
            return
        if self.at and time.time() < self.exp - 300:
            return
        if self.rt:
            st, r = self._http("POST", "/auth/v1/token?grant_type=refresh_token",
                               {"refresh_token": self.rt}, auth=False)
            if st == 200:
                return self._guardar(r)
        st, r = self._http("POST", "/auth/v1/token?grant_type=password",
                           {"email": self.cfg["email"], "password": self.cfg["password"]}, auth=False)
        if st != 200:
            raise RuntimeError(f"inicio de sesión SYNC rechazado (HTTP {st})")
        self._guardar(r)

    def _guardar(self, r):
        self.at, self.rt = r["access_token"], r.get("refresh_token")
        self.exp = time.time() + int(r.get("expires_in", 3600))

    def get(self, path):
        return self._http("GET", path)

    def insertar(self, tabla, filas, conflicto):
        return self._http("POST", f"/rest/v1/{tabla}?on_conflict={conflicto}", filas,
                          {"Prefer": "resolution=ignore-duplicates,return=minimal"})

    def actualizar_si_mas_nueva(self, fila):
        filtro = (f"id=eq.{fila['id']}&or=(version.lt.{fila['version']},"
                  f"and(version.eq.{fila['version']},seq.lt.{fila['seq']}))")
        return self._http("PATCH", "/rest/v1/jobs?" + filtro, fila, {"Prefer": "return=minimal"})


class Espejo:
    def __init__(self, database, cfg, cliente=None):
        self.database = database
        self.cfg = cfg
        self.sb = cliente or Supabase(cfg)
        self._tecnicos = {}
        c = self._db()
        c.executescript(ESTADO_SQL)
        if c.execute("SELECT 1 FROM supabase_mirror_state WHERE name = 'jobs_seq'").fetchone() is None:
            hist = bool(cfg.get("incluir_historicas"))
            seq0 = 0 if hist else c.execute("SELECT value FROM counters WHERE name = 'job_seq'").fetchone()[0]
            hid0 = 0 if hist else (c.execute("SELECT MAX(id) FROM job_history").fetchone()[0] or 0)
            desde = 0 if hist else int(time.time() * 1000)
            c.executemany("INSERT INTO supabase_mirror_state VALUES (?, ?)",
                          [("jobs_seq", seq0), ("history_id", hid0), ("receipts_rowid", 0 if hist else (c.execute("SELECT MAX(rowid) FROM sync_receipts").fetchone()[0] or 0)), ("activado_ms", desde)])
        c.close()

    def _db(self):
        c = sqlite3.connect(self.database, timeout=15, isolation_level=None)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA busy_timeout = 15000")
        return c

    def _estado(self, c, name):
        return c.execute("SELECT value FROM supabase_mirror_state WHERE name = ?", (name,)).fetchone()[0]

    def _avanzar(self, c, name, value):
        c.execute("UPDATE supabase_mirror_state SET value = ? WHERE name = ?", (value, name))

    def _log(self, c, tipo, enviados, ok, detalle=None):
        c.execute("INSERT INTO supabase_mirror_log(ts, tipo, enviados, ok, detalle) VALUES (?,?,?,?,?)",
                  (int(time.time() * 1000), tipo, enviados, 1 if ok else 0, detalle))

    def _uuid_tecnico(self, code):
        if not code:
            return None
        if code not in self._tecnicos:
            st, r = self.sb.get("/rest/v1/profiles?select=id&role=eq.TECHNICIAN&technician_code=eq."
                                + urllib.parse.quote(code))
            if st != 200:
                raise RuntimeError(f"no se pudo leer profiles (HTTP {st})")
            self._tecnicos[code] = r[0]["id"] if r else None
        return self._tecnicos[code]

    def fila(self, row):
        # Supabase usa BIGINT (epoch ms) para todos los timestamps — no convertir a ISO
        return {k: row[k] for k in row.keys() if k not in NO_SALEN}

    def _ids_permitidos(self, c, t="jobs"):
        """Órdenes que pueden salir: todas si se autorizaron las históricas; si no, solo las creadas tras activar."""
        if self.cfg.get("incluir_historicas"):
            return "1 = 1", ()
        return f"{t}.created_at >= ?", (self._estado(c, "activado_ms"),)

    def ciclo(self):
        """Una pasada completa. Devuelve el número de filas enviadas; lanza excepción si Supabase falla."""
        c = self._db()
        try:
            return self._jobs(c) + self._historial(c) + self._recibos(c)
        finally:
            c.close()

    def _jobs(self, c):
        cond, args = self._ids_permitidos(c)
        cursor = self._estado(c, "jobs_seq")
        filas = c.execute(f"SELECT * FROM jobs WHERE seq > ? AND {cond} ORDER BY seq LIMIT ?",
                          (cursor,) + args + (LOTE,)).fetchall()
        if not filas:
            tope = c.execute("SELECT value FROM counters WHERE name = 'job_seq'").fetchone()[0]
            if tope > cursor:   # cambios solo de órdenes no autorizadas: el cursor avanza sin enviar nada
                self._avanzar(c, "jobs_seq", tope)
            return 0
        lote = [self.fila(r) for r in filas]
        st, r = self.sb.insertar("jobs", lote, "id")
        if st not in (200, 201, 204):
            self._log(c, "jobs", len(lote), False, f"alta HTTP {st}: {r}")
            raise RuntimeError(f"alta de jobs HTTP {st}")
        for f in lote:   # las ya existentes se actualizan solo si la remota es más vieja (guarda de versión)
            st, r = self.sb.actualizar_si_mas_nueva(f)
            if st not in (200, 204):
                self._log(c, "jobs", len(lote), False, f"job {f['id']} HTTP {st}: {r}")
                raise RuntimeError(f"actualización del job {f['id']} HTTP {st}")
        self._avanzar(c, "jobs_seq", filas[-1]["seq"])
        self._log(c, "jobs", len(lote), True, ",".join(f"{f['id']}@v{f['version']}" for f in lote))
        return len(lote)

    def _historial(self, c):
        cond, args = self._ids_permitidos(c, "j")
        cursor = self._estado(c, "history_id")
        # solo historial de órdenes ya replicadas (la FK job_id exige que la orden exista en Supabase)
        filas = c.execute(f"SELECT h.* FROM job_history h JOIN jobs j ON j.id = h.job_id "
                          f"WHERE h.id > ? AND j.seq <= ? AND {cond} ORDER BY h.id LIMIT ?",
                          (cursor, self._estado(c, "jobs_seq")) + args + (LOTE,)).fetchall()
        if not filas:
            return 0
        lote = [{"id": h["id"], "job_id": h["job_id"], "version": h["version"], "field": h["field"],
                 "old_value": h["old_value"], "new_value": h["new_value"],
                 "source": f"{h['source']}:{h['actor']}", "ts": h["ts"]} for h in filas]
        st, r = self.sb.insertar("job_history", lote, "id")
        if st == 404:   # tabla aún no creada en Supabase — avanzar cursor y continuar
            self._avanzar(c, "history_id", filas[-1]["id"])
            return 0
        if st not in (200, 201, 204):
            self._log(c, "job_history", len(lote), False, f"HTTP {st}: {r}")
            raise RuntimeError(f"job_history HTTP {st}")
        self._avanzar(c, "history_id", filas[-1]["id"])
        self._log(c, "job_history", len(lote), True)
        return len(lote)

    def _recibos(self, c):
        # cursor por rowid (único y creciente): un ts repetido en el borde de un lote no deja recibos atrás
        cond, args = self._ids_permitidos(c, "j")
        cursor = self._estado(c, "receipts_rowid")
        filas = c.execute(f"SELECT r.rowid AS rid, r.* FROM sync_receipts r JOIN jobs j ON j.id = r.job_id "
                          f"WHERE r.rowid > ? AND j.seq <= ? AND {cond} ORDER BY r.rowid LIMIT ?",
                          (cursor, self._estado(c, "jobs_seq")) + args + (LOTE,)).fetchall()
        if not filas:
            return 0
        lote = [{"device_id": r["device_id"], "job_id": r["job_id"], "client_updated_at": r["client_updated_at"],
                 "status": r["status"], "version": r["version"], "ts": r["ts"]} for r in filas]
        st, r = self.sb.insertar("sync_receipts", lote, "device_id,job_id,client_updated_at")
        if st not in (200, 201, 204):
            self._log(c, "sync_receipts", len(lote), False, f"HTTP {st}: {r}")
            raise RuntimeError(f"sync_receipts HTTP {st}")
        self._avanzar(c, "receipts_rowid", filas[-1]["rid"])
        self._log(c, "sync_receipts", len(lote), True)
        return len(lote)

    def jalar_cambios(self, actor="supabase-pull"):
        """Pull Supabase→SQLite: trae jobs cuyo updated_at remoto > updated_at local.
        Aplica solo cambios de estado/campos usando J.aplicar() con OCC.
        Retorna (aplicados, conflictos)."""
        import sys
        sys.path.insert(0, __file__.rsplit("/lc_lab/", 1)[0])
        import lc_lab.jobs as J

        c = self._db()
        try:
            # Obtener todos los jobs de Supabase más recientes que hace 24h para no traer todo
            desde_ms = int(time.time() * 1000) - 86400 * 1000
            st, filas_sb = self.sb.get(
                f"/rest/v1/jobs?select=id,version,seq,estado,updated_at"
                f"&updated_at=gt.{desde_ms}&order=updated_at.asc&limit=200"
            )
            if st != 200:
                raise RuntimeError(f"pull HTTP {st}: {filas_sb}")

            aplicados = 0
            conflictos = 0
            for sb in filas_sb:
                job_id = sb["id"]
                row = c.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
                if row is None:
                    continue  # job no existe localmente — ignorar (el push lo creará)
                ts_local = row["updated_at"] or 0
                ts_sb    = sb["updated_at"] or 0
                if ts_sb <= ts_local:
                    continue  # local es igual o más nuevo — no hay nada que jalar
                # Hay una versión más nueva en Supabase — obtener fila completa
                st2, rows_full = self.sb.get(
                    f"/rest/v1/jobs?select=*&id=eq.{job_id}"
                )
                if st2 != 200 or not rows_full:
                    continue
                sb_full = rows_full[0]
                cambios = {}
                for campo_api, campo_col in (
                    ("estado","estado"), ("estadoDispatch","estado_dispatch"),
                    ("technicianId","technician_code"), ("prioridad","prioridad"),
                    ("notas","notas"), ("hora","hora"), ("programadoPara","programado_para"),
                ):
                    val_sb = sb_full.get(campo_col)
                    if val_sb is not None and val_sb != row[campo_col]:
                        cambios[campo_api] = val_sb
                if not cambios:
                    continue
                estado_result, _, conf = J.aplicar(
                    c, row, cambios, actor, "supabase",
                    version_base=row["version"]
                )
                if estado_result == "ACCEPTED":
                    aplicados += 1
                    self._log(c, "pull", 1, True, f"job {job_id}: {list(cambios.keys())}")
                else:
                    conflictos += 1
                    self._log(c, "pull", 1, False, f"job {job_id} conflicto: {conf}")
            return aplicados, conflictos
        finally:
            c.close()

    def bucle(self, parar, pausa=3.0):
        espera = pausa
        while not parar.is_set():
            try:
                enviados = self.ciclo()
                espera = 0.2 if enviados else pausa
            except Exception as e:   # sin red, token caducado, 5xx: se reintenta sin perder el cursor
                log.warning("espejo Supabase: %s (reintento en %.0fs)", e, espera)
                parar.wait(espera)
                espera = min(espera * 2, 300)
                continue
            parar.wait(espera)


def arrancar(app, local_properties=None):
    """Arranca el hilo del espejo si hay configuración. Devuelve el Event para detenerlo, o None."""
    cfg = leer_config(os.path.dirname(app.config["DATABASE"]), local_properties)
    if cfg is None:
        app.logger.info("espejo Supabase desactivado (falta instance/supabase_sync.json)")
        return None
    espejo = Espejo(app.config["DATABASE"], cfg)
    parar = threading.Event()
    threading.Thread(target=espejo.bucle, args=(parar,), name="supabase-mirror", daemon=True).start()
    app.logger.info("espejo Supabase activo → %s", cfg["url"])
    return parar


if __name__ == "__main__":   # verificación manual: python3 -m lc_lab.supabase_mirror [instance]
    import sys
    base = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    inst = sys.argv[1] if len(sys.argv) > 1 else os.path.join(base, "instance")
    cfg = leer_config(inst, os.path.join(base, "..", "..", "local.properties"))
    if cfg is None:
        raise SystemExit("falta instance/supabase_sync.json (url, publishable_key, email, password)")
    print("enviados:", Espejo(os.path.join(inst, "lab.db"), cfg).ciclo())
