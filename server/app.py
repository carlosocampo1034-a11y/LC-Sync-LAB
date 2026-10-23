"""
LC Technician — Flask sync server + Web Dashboard
Endpoints API: /api/health, /api/sync/push, /api/sync/pull (PUBLIC — Android sync)
               /api/stats, /api/jobs, /api/technicians, /api/work_items,
               /api/memberships, /api/dashboard/* (PROTECTED — login required)
Dashboard:     /dashboard, /jobs, /technicians, /work_items, /memberships
Auth:          /login, /logout
DB: lc_sync.db (SQLite mirror of Room v38)
"""

import sqlite3
import time
import os
import json
import threading
import shutil
from datetime import datetime, timedelta
from functools import wraps
from flask import (Flask, request, jsonify, render_template, redirect,
                   url_for, session, flash, send_file)
import auth_config
import hashlib
import hmac
import secrets

app = Flask(__name__)
app.secret_key = auth_config.get_secret_key()
app.permanent_session_lifetime = timedelta(seconds=auth_config.SESSION_LIFETIME_SECONDS)

DB_PATH = os.path.join(os.path.dirname(__file__), "lc_sync.db")
VERSION = "1.2"


def get_db():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    conn = get_db()
    c = conn.cursor()
    c.executescript("""
        CREATE TABLE IF NOT EXISTS sync_jobs (
            id INTEGER PRIMARY KEY,
            cliente TEXT,
            hora TEXT,
            estado TEXT,
            technicianId TEXT,
            updatedAt INTEGER,
            synced_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS sync_work_items (
            id INTEGER PRIMARY KEY,
            jobId INTEGER,
            tipo TEXT,
            descripcion TEXT,
            cantidad REAL,
            precioUnitario REAL,
            subtotal REAL,
            updatedAt INTEGER,
            synced_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS sync_technicians (
            id TEXT PRIMARY KEY,
            nombre TEXT,
            email TEXT,
            rol TEXT,
            status TEXT,
            activo INTEGER,
            updatedAt INTEGER,
            synced_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS sync_memberships (
            id INTEGER PRIMARY KEY,
            cardNumber TEXT UNIQUE,
            customerId INTEGER,
            coverageTypeId INTEGER,
            status TEXT,
            paymentStatus TEXT,
            issueDate INTEGER,
            expiryDate INTEGER,
            lastServiceDate INTEGER,
            nextServiceDate INTEGER,
            notes TEXT,
            updatedAt INTEGER,
            synced_at INTEGER
        );
        CREATE TABLE IF NOT EXISTS sync_log (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            entity_type TEXT,
            entity_id TEXT,
            action TEXT,
            ts INTEGER
        );
        CREATE TABLE IF NOT EXISTS sync_audit_log (
            id INTEGER PRIMARY KEY,
            action TEXT,
            entityType TEXT,
            entityId INTEGER,
            jobId INTEGER,
            field TEXT,
            oldValue TEXT,
            newValue TEXT,
            actor TEXT,
            timestamp INTEGER,
            extra TEXT,
            updatedAt INTEGER,
            synced_at INTEGER
        );
        CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON sync_audit_log(timestamp DESC);
        CREATE INDEX IF NOT EXISTS idx_audit_action ON sync_audit_log(action);
        CREATE INDEX IF NOT EXISTS idx_audit_entity ON sync_audit_log(entityType);
        CREATE TABLE IF NOT EXISTS sync_membership_check_ins (
            id INTEGER PRIMARY KEY,
            cardId INTEGER,
            jobId INTEGER,
            checkInDate INTEGER,
            technicianName TEXT,
            notes TEXT,
            createdAt INTEGER,
            updatedAt INTEGER,
            synced_at INTEGER
        );
        CREATE INDEX IF NOT EXISTS idx_checkin_card
            ON sync_membership_check_ins(cardId, checkInDate DESC);
        CREATE TABLE IF NOT EXISTS sync_photos (
            id INTEGER PRIMARY KEY,
            jobId INTEGER,
            filename TEXT,
            base64Data TEXT,
            sizeBytes INTEGER,
            mimeType TEXT,
            timestamp INTEGER,
            updatedAt INTEGER,
            synced_at INTEGER
        );
        CREATE INDEX IF NOT EXISTS idx_photo_job
            ON sync_photos(jobId, timestamp DESC);
        CREATE TABLE IF NOT EXISTS devices (
            deviceId TEXT PRIMARY KEY,
            deviceName TEXT,
            firstSeenAt INTEGER DEFAULT 0,
            lastSeenAt INTEGER DEFAULT 0,
            pushCount INTEGER DEFAULT 0,
            platform TEXT DEFAULT 'android'
        );
    """)
    conn.commit()
    # Migrate sync_technicians: add pin_hash for backend-authorised PIN validation
    tech_cols = [row[1] for row in c.execute("PRAGMA table_info(sync_technicians)").fetchall()]
    if "pin_hash" not in tech_cols:
        c.execute("ALTER TABLE sync_technicians ADD COLUMN pin_hash TEXT")
    conn.commit()
    # Add sync_conflicts table
    c.execute("""CREATE TABLE IF NOT EXISTS sync_conflicts (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        entityType TEXT,
        entityId INTEGER,
        clientValue TEXT,
        serverValue TEXT,
        clientUpdatedAt INTEGER,
        serverUpdatedAt INTEGER,
        resolvedAt INTEGER DEFAULT 0,
        resolvedBy TEXT,
        resolution TEXT,
        detectedAt INTEGER DEFAULT 0
    )""")
    c.execute("""CREATE INDEX IF NOT EXISTS idx_conflict_unresolved
        ON sync_conflicts(resolvedAt, entityType)""")
    conn.commit()
    # Migrate sync_memberships if it has the old minimal schema
    existing_cols = [row[1] for row in c.execute("PRAGMA table_info(sync_memberships)").fetchall()]
    new_cols = {
        "cardNumber": "TEXT",
        "customerId": "INTEGER",
        "coverageTypeId": "INTEGER",
        "status": "TEXT",
        "paymentStatus": "TEXT",
        "issueDate": "INTEGER",
        "expiryDate": "INTEGER",
        "lastServiceDate": "INTEGER",
        "nextServiceDate": "INTEGER",
        "notes": "TEXT",
    }
    for col, col_type in new_cols.items():
        if col not in existing_cols:
            c.execute(f"ALTER TABLE sync_memberships ADD COLUMN {col} {col_type}")
    conn.commit()
    conn.close()


init_db()


# ── Auth helpers ──────────────────────────────────────────────────────────────

def role_required(*allowed_roles):
    def decorator(f):
        @wraps(f)
        def decorated_function(*args, **kwargs):
            if not session.get("logged_in"):
                if request.path.startswith("/api/"):
                    return jsonify({"error": "unauthorized"}), 401
                return redirect(url_for("login", next=request.path))
            user_role = session.get("role", "viewer")
            if user_role not in allowed_roles:
                if request.path.startswith("/api/"):
                    return jsonify({"error": "forbidden", "role": user_role}), 403
                return "Acceso denegado", 403
            return f(*args, **kwargs)
        return decorated_function
    return decorator


ALL_ROLES = ("admin", "dispatcher", "viewer")
WRITE_ROLES = ("admin", "dispatcher")


# ── Helper ────────────────────────────────────────────────────────────────────

def ts_to_str(ts):
    if not ts:
        return "—"
    try:
        return datetime.fromtimestamp(ts / 1000).strftime("%d %b %Y %H:%M")
    except Exception:
        return str(ts)


# ── Auth routes ───────────────────────────────────────────────────────────────

@app.route("/login", methods=["GET", "POST"])
def login():
    if session.get("logged_in"):
        return redirect(url_for("dashboard"))
    if request.method == "POST":
        username = request.form.get("username", "").strip()
        password = request.form.get("password", "")
        remember = request.form.get("remember") == "on"
        user = auth_config.verify_user(username, password)
        if user:
            session.permanent = remember
            session["logged_in"] = True
            session["username"] = username
            session["role"] = user["role"]
            session["name"] = user.get("name", username)
            next_url = request.args.get("next") or url_for("dashboard")
            return redirect(next_url)
        return render_template("login.html",
                               error="Usuario o contraseña incorrectos"), 401
    return render_template("login.html")


@app.route("/logout")
def logout():
    session.clear()
    return redirect(url_for("login"))


# ── Health (PUBLIC) ───────────────────────────────────────────────────────────

@app.route("/api/health", methods=["GET"])
def api_health():
    """
    Health check endpoint
    ---
    tags:
      - Health
    responses:
      200:
        description: Server is healthy
        schema:
          type: object
          properties:
            status:
              type: string
            version:
              type: string
            timestamp:
              type: integer
            db:
              type: boolean
    """
    return jsonify({
        "status": "ok",
        "version": VERSION,
        "timestamp": int(time.time() * 1000),
        "db": os.path.exists(DB_PATH)
    })


# ── PBKDF2 helpers (stdlib only — no bcrypt dependency) ──────────────────────
_PBKDF2_ALGO = 'sha256'
_PBKDF2_ITERS = 200_000


def _pbkdf2_hash(value: str) -> str:
    """Hash a PIN or password with PBKDF2-HMAC-SHA256. Prefix: pbkdf2:"""
    salt = secrets.token_bytes(16)
    h = hashlib.pbkdf2_hmac(_PBKDF2_ALGO, value.encode(), salt, _PBKDF2_ITERS)
    return f"pbkdf2:{salt.hex()}:{h.hex()}"


def _pbkdf2_verify(value: str, stored: str) -> bool:
    """Verify against a stored pbkdf2: hash or legacy plain-sha256 hash."""
    if stored.startswith("pbkdf2:"):
        parts = stored.split(":")
        if len(parts) != 3:
            return False
        try:
            salt = bytes.fromhex(parts[1])
            stored_hash = bytes.fromhex(parts[2])
            test = hashlib.pbkdf2_hmac(_PBKDF2_ALGO, value.encode(), salt, _PBKDF2_ITERS)
            return hmac.compare_digest(test, stored_hash)
        except Exception:
            return False
    # Legacy: unsalted SHA-256 (migration only)
    return hmac.compare_digest(hashlib.sha256(value.encode()).hexdigest(), stored)


# ── PIN Validation (PUBLIC — limited rate, no session required) ───────────────
# Rate-limit state: { ip → [timestamp, ...] }
_pin_attempts = {}
_pin_attempts_lock = threading.Lock()
_PIN_LIMIT = 5        # max attempts
_PIN_WINDOW = 60      # seconds


def _pin_rate_ok(ip):
    now = time.time()
    with _pin_attempts_lock:
        times = [t for t in _pin_attempts.get(ip, []) if now - t < _PIN_WINDOW]
        if len(times) >= _PIN_LIMIT:
            return False
        times.append(now)
        _pin_attempts[ip] = times
        return True


@app.route("/api/validate_pin", methods=["POST"])
def api_validate_pin():
    """Validates a technician PIN against the backend registry.
    Returns {"valid": true, "role": "TECNICO"} on match.
    Rate-limited to 5 attempts per 60 s per IP — brute-force deterrent.
    Does NOT return the stored hash or any sensitive data."""
    ip = request.headers.get("X-Forwarded-For", request.remote_addr)
    if not _pin_rate_ok(ip):
        return jsonify({"valid": False, "error": "too_many_attempts"}), 429

    data = request.get_json(silent=True) or {}
    pin = data.get("pin", "").strip()
    if not pin or len(pin) < 4 or len(pin) > 8 or not pin.isdigit():
        return jsonify({"valid": False, "error": "invalid_format"}), 400

    conn = get_db()
    c = conn.cursor()
    rows = c.execute(
        "SELECT id, nombre, rol, pin_hash FROM sync_technicians "
        "WHERE activo=1 AND pin_hash IS NOT NULL"
    ).fetchall()
    conn.close()

    for row in rows:
        try:
            match = _pbkdf2_verify(pin, row["pin_hash"])
        except Exception:
            continue
        if match:
            return jsonify({"valid": True, "role": row["rol"],
                            "userId": row["id"], "nombre": row["nombre"]})

    return jsonify({"valid": False})


@app.route("/api/technicians/<tech_id>/set_pin", methods=["POST"])
@role_required("admin")
def api_set_technician_pin(tech_id):
    """Admin-only: sets or resets a technician's PIN (PBKDF2-hashed server-side)."""
    data = request.get_json(silent=True) or {}
    pin = data.get("pin", "").strip()
    if not pin or len(pin) < 4 or len(pin) > 8 or not pin.isdigit():
        return jsonify({"error": "PIN must be 4–8 digits"}), 400

    pin_hash = _pbkdf2_hash(pin)
    conn = get_db()
    c = conn.cursor()
    c.execute("UPDATE sync_technicians SET pin_hash=? WHERE id=?", (pin_hash, tech_id))
    if c.rowcount == 0:
        conn.close()
        return jsonify({"error": "technician not found"}), 404
    conn.commit()
    conn.close()
    return jsonify({"ok": True})


# ── Sync Push (PUBLIC — Android app) ─────────────────────────────────────────

@app.route("/api/sync/push", methods=["POST"])
def sync_push():
    data = request.get_json(silent=True) or {}
    now = int(time.time() * 1000)
    accepted = 0
    rejected = 0
    conn = get_db()
    c = conn.cursor()
    try:
        # Register/update device
        device_id = data.get("deviceId", "unknown")
        device_name = data.get("deviceName", "unknown")
        now_ms = now
        existing_device = c.execute("SELECT deviceId FROM devices WHERE deviceId=?",
                                     (device_id,)).fetchone()
        if existing_device:
            c.execute("""UPDATE devices SET lastSeenAt=?, pushCount=pushCount+1, deviceName=?
                         WHERE deviceId=?""", (now_ms, device_name, device_id))
        else:
            c.execute("""INSERT INTO devices (deviceId, deviceName, firstSeenAt, lastSeenAt, pushCount)
                         VALUES (?,?,?,?,1)""", (device_id, device_name, now_ms, now_ms))

        for item in data.get("work_items", []):
            try:
                c.execute("""INSERT OR REPLACE INTO sync_work_items
                    (id,jobId,tipo,descripcion,cantidad,precioUnitario,subtotal,updatedAt,synced_at)
                    VALUES (?,?,?,?,?,?,?,?,?)""",
                    (item.get("id"), item.get("jobId"), item.get("tipo"),
                     item.get("descripcion"), item.get("cantidad"),
                     item.get("precioUnitario"), item.get("subtotal"),
                     item.get("updatedAt", now), now))
                c.execute("INSERT INTO sync_log (entity_type,entity_id,action,ts) VALUES (?,?,?,?)",
                          ("WORK_ITEM", str(item.get("id")), "PUSH", now))
                accepted += 1
            except Exception:
                rejected += 1

        for tech in data.get("technicians", []):
            try:
                c.execute("""INSERT OR REPLACE INTO sync_technicians
                    (id,nombre,email,rol,status,activo,updatedAt,synced_at)
                    VALUES (?,?,?,?,?,?,?,?)""",
                    (tech.get("id"), tech.get("nombre"), tech.get("email"),
                     tech.get("rol"), tech.get("status"), tech.get("activo"),
                     tech.get("updatedAt", now), now))
                c.execute("INSERT INTO sync_log (entity_type,entity_id,action,ts) VALUES (?,?,?,?)",
                          ("TECHNICIAN", str(tech.get("id")), "PUSH", now))
                accepted += 1
            except Exception:
                rejected += 1

        for job in data.get("jobs", []):
            try:
                # Conflict detection for jobs
                job_id = job.get("id")
                job_updated_at = job.get("updatedAt", 0)
                if job_id:
                    existing = c.execute("SELECT updatedAt FROM sync_jobs WHERE id=?", (job_id,)).fetchone()
                    if existing:
                        existing_updated_at = existing[0] if isinstance(existing, tuple) else existing["updatedAt"]
                        if existing_updated_at and existing_updated_at > job_updated_at:
                            try:
                                server_row = c.execute("SELECT * FROM sync_jobs WHERE id=?", (job_id,)).fetchone()
                                server_cols = [d[0] for d in c.description]
                                server_dict = dict(zip(server_cols, server_row)) if server_row else {}
                                c.execute("""INSERT INTO sync_conflicts
                                    (entityType, entityId, clientValue, serverValue,
                                     clientUpdatedAt, serverUpdatedAt, detectedAt)
                                    VALUES (?,?,?,?,?,?,?)""",
                                    ("sync_jobs", job_id, json.dumps(job, default=str),
                                     json.dumps(server_dict, default=str),
                                     job_updated_at, existing_updated_at,
                                     int(time.time() * 1000)))
                            except Exception:
                                pass
                c.execute("""INSERT OR REPLACE INTO sync_jobs
                    (id,cliente,hora,estado,technicianId,updatedAt,synced_at)
                    VALUES (?,?,?,?,?,?,?)""",
                    (job.get("id"), job.get("cliente"), job.get("hora"),
                     job.get("estado"), job.get("technicianId"),
                     job.get("updatedAt", now), now))
                c.execute("INSERT INTO sync_log (entity_type,entity_id,action,ts) VALUES (?,?,?,?)",
                          ("JOB", str(job.get("id")), "PUSH", now))
                accepted += 1
            except Exception:
                rejected += 1

        for m in data.get("memberships", []):
            try:
                c.execute("""INSERT OR REPLACE INTO sync_memberships
                    (id,cardNumber,customerId,coverageTypeId,status,paymentStatus,
                     issueDate,expiryDate,lastServiceDate,nextServiceDate,notes,
                     updatedAt,synced_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (m.get("id"), m.get("cardNumber"), m.get("customerId"),
                     m.get("coverageTypeId"), m.get("status"), m.get("paymentStatus"),
                     m.get("issueDate"), m.get("expiryDate"),
                     m.get("lastServiceDate"), m.get("nextServiceDate"),
                     m.get("notes"), m.get("updatedAt", now), now))
                c.execute("INSERT INTO sync_log (entity_type,entity_id,action,ts) VALUES (?,?,?,?)",
                          ("MEMBERSHIP", str(m.get("id")), "PUSH", now))
                accepted += 1
            except Exception:
                rejected += 1

        for entry in data.get("audit_logs", []):
            try:
                c.execute("""INSERT OR REPLACE INTO sync_audit_log
                    (id,action,entityType,entityId,jobId,field,oldValue,newValue,
                     actor,timestamp,extra,updatedAt,synced_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                    (entry.get("id"), entry.get("action"), entry.get("entityType"),
                     entry.get("entityId"), entry.get("jobId"), entry.get("field"),
                     entry.get("oldValue"), entry.get("newValue"), entry.get("actor"),
                     entry.get("timestamp"), entry.get("extra"),
                     entry.get("updatedAt", now), now))
                accepted += 1
            except Exception:
                rejected += 1

        for ci in data.get("membership_check_ins", []):
            try:
                c.execute("""INSERT OR REPLACE INTO sync_membership_check_ins
                    (id,cardId,jobId,checkInDate,technicianName,notes,
                     createdAt,updatedAt,synced_at)
                    VALUES (?,?,?,?,?,?,?,?,?)""",
                    (ci.get("id"), ci.get("cardId"), ci.get("jobId"),
                     ci.get("checkInDate"), ci.get("technicianName"),
                     ci.get("notes"), ci.get("createdAt"),
                     ci.get("updatedAt", now), now))
                accepted += 1
            except Exception:
                rejected += 1

        for ph in data.get("photos", []):
            try:
                c.execute("""INSERT OR REPLACE INTO sync_photos
                    (id,jobId,filename,base64Data,sizeBytes,mimeType,
                     timestamp,updatedAt,synced_at)
                    VALUES (?,?,?,?,?,?,?,?,?)""",
                    (ph.get("id"), ph.get("jobId"), ph.get("filename"),
                     ph.get("base64Data"), ph.get("sizeBytes"),
                     ph.get("mimeType"), ph.get("timestamp"),
                     ph.get("updatedAt", now), now))
                accepted += 1
            except Exception:
                rejected += 1

        conn.commit()
    finally:
        conn.close()

    return jsonify({"accepted": accepted, "rejected": rejected, "timestamp": now})


# ── Sync Pull (PUBLIC — Android app) ─────────────────────────────────────────

@app.route("/api/sync/pull", methods=["GET"])
def sync_pull():
    since = int(request.args.get("since", 0))
    conn = get_db()
    c = conn.cursor()

    work_items = [dict(r) for r in c.execute(
        "SELECT * FROM sync_work_items WHERE synced_at > ?", (since,)).fetchall()]
    technicians = [dict(r) for r in c.execute(
        "SELECT * FROM sync_technicians WHERE synced_at > ?", (since,)).fetchall()]
    jobs = [dict(r) for r in c.execute(
        "SELECT * FROM sync_jobs WHERE synced_at > ?", (since,)).fetchall()]
    memberships = [dict(r) for r in c.execute(
        "SELECT * FROM sync_memberships WHERE synced_at > ?", (since,)).fetchall()]
    conn.close()

    server_time = int(time.time() * 1000)
    return jsonify({
        "work_items": work_items,
        "technicians": technicians,
        "jobs": jobs,
        "memberships": memberships,
        "server_time": server_time
    })


# ── Stats (PROTECTED) ─────────────────────────────────────────────────────────

@app.route("/api/stats", methods=["GET"])
@role_required(*ALL_ROLES)
def stats():
    conn = get_db()
    c = conn.cursor()
    total_jobs = c.execute("SELECT COUNT(*) FROM sync_jobs").fetchone()[0]
    total_wi   = c.execute("SELECT COUNT(*) FROM sync_work_items").fetchone()[0]
    total_rev  = c.execute("SELECT COALESCE(SUM(subtotal),0) FROM sync_work_items").fetchone()[0]
    active_t   = c.execute("SELECT COUNT(*) FROM sync_technicians WHERE activo=1").fetchone()[0]
    conn.close()
    return jsonify({
        "total_jobs": total_jobs,
        "total_work_items": total_wi,
        "total_revenue": float(total_rev),
        "active_technicians": active_t,
        "timestamp": int(time.time() * 1000)
    })


# ── Dashboard API (PROTECTED) ─────────────────────────────────────────────────

@app.route("/api/dashboard/summary", methods=["GET"])
@role_required(*ALL_ROLES)
def dashboard_summary():
    conn = get_db()
    c = conn.cursor()
    total_jobs   = c.execute("SELECT COUNT(*) FROM sync_jobs").fetchone()[0]
    completed    = c.execute("SELECT COUNT(*) FROM sync_jobs WHERE estado LIKE '%COMPLET%' OR estado LIKE '%REALIZ%'").fetchone()[0]
    pending      = c.execute("SELECT COUNT(*) FROM sync_jobs WHERE estado LIKE '%PEND%' OR estado LIKE '%EN RUT%'").fetchone()[0]
    total_rev    = c.execute("SELECT COALESCE(SUM(subtotal),0) FROM sync_work_items").fetchone()[0]
    active_t     = c.execute("SELECT COUNT(*) FROM sync_technicians WHERE activo=1").fetchone()[0]
    total_wi     = c.execute("SELECT COUNT(*) FROM sync_work_items").fetchone()[0]
    avg_ticket   = (float(total_rev) / total_jobs) if total_jobs > 0 else 0
    conn.close()
    return jsonify({
        "total_jobs": total_jobs,
        "completed_jobs": completed,
        "pending_jobs": pending,
        "total_revenue": float(total_rev),
        "active_technicians": active_t,
        "total_work_items": total_wi,
        "avg_ticket": round(avg_ticket, 2)
    })


@app.route("/api/dashboard/revenue_by_day", methods=["GET"])
@role_required(*ALL_ROLES)
def revenue_by_day():
    days = int(request.args.get("days", 7))
    conn = get_db()
    c = conn.cursor()
    rows = c.execute("""
        SELECT date(synced_at/1000,'unixepoch') as day, COALESCE(SUM(subtotal),0) as amount
        FROM sync_work_items
        WHERE synced_at > ?
        GROUP BY day ORDER BY day ASC
    """, (int((time.time() - days * 86400) * 1000),)).fetchall()
    conn.close()
    return jsonify([{"date": r["day"], "amount": float(r["amount"])} for r in rows])


@app.route("/api/dashboard/jobs_by_status", methods=["GET"])
@role_required(*ALL_ROLES)
def jobs_by_status():
    conn = get_db()
    c = conn.cursor()
    rows = c.execute("SELECT COALESCE(estado,'Sin estado') as status, COUNT(*) as count FROM sync_jobs GROUP BY estado").fetchall()
    conn.close()
    return jsonify([{"status": r["status"], "count": r["count"]} for r in rows])


@app.route("/api/dashboard/top_technicians", methods=["GET"])
@role_required(*ALL_ROLES)
def top_technicians():
    limit = int(request.args.get("limit", 5))
    conn = get_db()
    c = conn.cursor()
    rows = c.execute("""
        SELECT t.nombre, COUNT(j.id) as jobs_assigned
        FROM sync_technicians t
        LEFT JOIN sync_jobs j ON j.technicianId = t.nombre
        GROUP BY t.id ORDER BY jobs_assigned DESC LIMIT ?
    """, (limit,)).fetchall()
    conn.close()
    return jsonify([{"name": r["nombre"], "jobs_assigned": r["jobs_assigned"]} for r in rows])


# ── Jobs API (PROTECTED) ───────────────────────────────────────────────────────

@app.route("/api/jobs", methods=["GET"])
@role_required(*ALL_ROLES)
def api_jobs():
    status = request.args.get("status")
    limit  = int(request.args.get("limit", 100))
    conn = get_db()
    c = conn.cursor()
    if status:
        rows = c.execute("SELECT * FROM sync_jobs WHERE estado LIKE ? ORDER BY id LIMIT ?",
                         (f"%{status}%", limit)).fetchall()
    else:
        rows = c.execute("SELECT * FROM sync_jobs ORDER BY id LIMIT ?", (limit,)).fetchall()
    conn.close()
    return jsonify([dict(r) for r in rows])


ESTADO_MAP = {
    "PENDING": "PENDIENTE",
    "COMPLETED": "COMPLETADO",
    "IN_PROGRESS": "EN_PROGRESO",
    "IN_ROUTE": "EN RUTA",
    "EN_ROUTE": "EN RUTA",
    "CANCELLED": "CANCELADO",
    "CANCELED": "CANCELADO",
}


def normalize_estado(estado):
    if not estado:
        return "PENDIENTE"
    return ESTADO_MAP.get(estado.upper(), estado)


@app.route("/api/jobs", methods=["POST"])
@role_required(*WRITE_ROLES)
def api_create_job():
    data = request.get_json(silent=True) or {}
    now = int(time.time() * 1000)
    raw_estado = data.get("status") or data.get("estado", "PENDIENTE")
    conn = get_db()
    c = conn.cursor()
    c.execute("""INSERT INTO sync_jobs (cliente,hora,estado,technicianId,updatedAt,synced_at)
                 VALUES (?,?,?,?,?,?)""",
              (data.get("customer") or data.get("cliente", ""),
               data.get("time") or data.get("hora", ""),
               normalize_estado(raw_estado),
               data.get("technicianId"), now, now))
    job_id = c.lastrowid
    c.execute("INSERT INTO sync_log (entity_type,entity_id,action,ts) VALUES (?,?,?,?)",
              ("JOB", str(job_id), "CREATE_SERVER", now))
    conn.commit()
    conn.close()
    return jsonify({"id": job_id, "updatedAt": now}), 201


@app.route("/api/jobs/<int:job_id>", methods=["GET"])
@role_required(*ALL_ROLES)
def api_job_detail(job_id):
    conn = get_db()
    c = conn.cursor()
    job = c.execute("SELECT * FROM sync_jobs WHERE id=?", (job_id,)).fetchone()
    items = c.execute("SELECT * FROM sync_work_items WHERE jobId=?", (job_id,)).fetchall()
    conn.close()
    if not job:
        return jsonify({"error": "not found"}), 404
    return jsonify({"job": dict(job), "work_items": [dict(i) for i in items]})


# ── Technicians API (PROTECTED) ───────────────────────────────────────────────

@app.route("/api/technicians", methods=["GET"])
@role_required(*ALL_ROLES)
def api_technicians():
    conn = get_db()
    c = conn.cursor()
    rows = c.execute("SELECT * FROM sync_technicians ORDER BY nombre").fetchall()
    conn.close()
    result = []
    for r in rows:
        d = dict(r)
        d["updatedAt_str"] = ts_to_str(d.get("updatedAt"))
        result.append(d)
    return jsonify(result)


@app.route("/api/technicians", methods=["POST"])
@role_required(*WRITE_ROLES)
def api_create_technician():
    data = request.get_json(silent=True) or {}
    now = int(time.time() * 1000)
    conn = get_db()
    c = conn.cursor()
    c.execute("""INSERT INTO sync_technicians (nombre,email,rol,status,activo,updatedAt,synced_at)
                 VALUES (?,?,?,?,?,?,?)""",
              (data.get("nombre", ""), data.get("email", ""),
               data.get("rol", "TECNICO"), data.get("status", "ACTIVE"),
               data.get("activo", 1), now, now))
    tech_id = str(c.lastrowid)
    conn.commit()
    conn.close()
    return jsonify({"id": tech_id, "updatedAt": now}), 201


# ── Work Items API (PROTECTED) ────────────────────────────────────────────────

@app.route("/api/work_items", methods=["GET"])
@role_required(*ALL_ROLES)
def api_work_items():
    job_id = request.args.get("job_id")
    conn = get_db()
    c = conn.cursor()
    if job_id:
        rows = c.execute("SELECT * FROM sync_work_items WHERE jobId=? ORDER BY id", (job_id,)).fetchall()
    else:
        rows = c.execute("SELECT * FROM sync_work_items ORDER BY jobId, id LIMIT 200").fetchall()
    conn.close()
    return jsonify([dict(r) for r in rows])


# ── Memberships API (PROTECTED) ───────────────────────────────────────────────

@app.route("/api/memberships", methods=["GET"])
@role_required(*ALL_ROLES)
def api_memberships():
    conn = get_db()
    c = conn.cursor()
    rows = c.execute("SELECT * FROM sync_memberships ORDER BY id").fetchall()
    conn.close()
    now_ms = int(time.time() * 1000)
    result = []
    for r in rows:
        d = dict(r)
        d["expired"] = d.get("expiryDate") and d["expiryDate"] < now_ms
        d["expiring_soon"] = (d.get("expiryDate") and
                              0 < d["expiryDate"] - now_ms < 30 * 86400 * 1000)
        result.append(d)
    return jsonify(result)


@app.route("/api/memberships/<int:mem_id>", methods=["GET"])
@role_required(*ALL_ROLES)
def api_membership_detail(mem_id):
    conn = get_db()
    c = conn.cursor()
    row = c.execute("SELECT * FROM sync_memberships WHERE id=?", (mem_id,)).fetchone()
    conn.close()
    if not row:
        return jsonify({"error": "not found"}), 404
    return jsonify(dict(row))


@app.route("/api/memberships/expiring", methods=["GET"])
@role_required(*ALL_ROLES)
def api_memberships_expiring():
    days = int(request.args.get("days", 30))
    now_ms = int(time.time() * 1000)
    cutoff = now_ms + days * 86400 * 1000
    conn = get_db()
    c = conn.cursor()
    rows = c.execute("""SELECT * FROM sync_memberships
                        WHERE status='ACTIVE' AND expiryDate > ? AND expiryDate < ?
                        ORDER BY expiryDate ASC""", (now_ms, cutoff)).fetchall()
    conn.close()
    return jsonify([dict(r) for r in rows])


@app.route("/api/memberships/<int:card_id>/checkins", methods=["GET"])
@role_required(*ALL_ROLES)
def api_membership_checkins(card_id):
    conn = get_db()
    c = conn.cursor()
    rows = c.execute(
        "SELECT * FROM sync_membership_check_ins WHERE cardId=? ORDER BY checkInDate DESC",
        (card_id,)).fetchall()
    conn.close()
    result = []
    for r in rows:
        d = dict(r)
        d["date_str"] = ts_to_str(d.get("checkInDate"))
        result.append(d)
    return jsonify(result)


@app.route("/api/memberships/<int:card_id>/renew", methods=["POST"])
@role_required("admin", "dispatcher")
def api_membership_renew(card_id):
    data = request.get_json() or {}
    days = data.get("days", 365)
    note = data.get("note", "Renovación anual")

    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT * FROM sync_memberships WHERE id=?", (card_id,))
    card = cur.fetchone()
    if not card:
        conn.close()
        return jsonify({"error": "Card not found"}), 404

    # Get column names
    cols = [d[0] for d in cur.description]
    card_dict = dict(zip(cols, card))

    old_expiry_ms = card_dict.get("expiryDate")  # stored as ms timestamp
    now = datetime.now()
    now_ms = int(now.timestamp() * 1000)

    # Calculate new expiry in ms: if active, add days to current expiry; if expired, from now
    if card_dict.get("status") == "ACTIVE" and old_expiry_ms:
        try:
            exp_ms = int(old_expiry_ms)
            if exp_ms > now_ms:
                new_expiry_ms = exp_ms + days * 86400 * 1000
            else:
                new_expiry_ms = now_ms + days * 86400 * 1000
        except Exception:
            new_expiry_ms = now_ms + days * 86400 * 1000
    else:
        new_expiry_ms = now_ms + days * 86400 * 1000

    updated_at_ms = now_ms
    updated_at_str = now.strftime("%Y-%m-%dT%H:%M:%S")

    # Format expiry strings for response
    old_expiry_str = datetime.fromtimestamp(int(old_expiry_ms) / 1000).strftime("%Y-%m-%dT%H:%M:%S") if old_expiry_ms else "—"
    new_expiry_str = datetime.fromtimestamp(new_expiry_ms / 1000).strftime("%Y-%m-%dT%H:%M:%S")

    cur.execute("""UPDATE sync_memberships
                   SET status='ACTIVE', expiryDate=?, updatedAt=?
                   WHERE id=?""", (new_expiry_ms, updated_at_ms, card_id))

    # Register in audit log
    actor = session.get("username", "system")
    detail_str = f"Renovado por {days} días. Expiración: {new_expiry_str}. Nota: {note}"
    cur.execute("""INSERT INTO sync_audit_log
                   (action, entityType, entityId, actor, timestamp, extra)
                   VALUES (?,?,?,?,?,?)""",
                ("RENEW", "MEMBERSHIP", card_id, actor, updated_at_str, detail_str))

    conn.commit()
    conn.close()

    return jsonify({
        "status": "ok",
        "old_expiry": old_expiry_str,
        "new_expiry": new_expiry_str,
        "days_added": days
    })


# ── Backup / Restore API (admin only) ─────────────────────────────────────────

@app.route("/api/backup/info")
@role_required(*ALL_ROLES)
def api_backup_info():
    import glob as glob_mod
    from datetime import datetime as dt
    conn = get_db()
    c = conn.cursor()
    tables = {}
    for tbl in ["sync_jobs", "sync_work_items", "sync_technicians",
                "sync_memberships", "sync_audit_log", "sync_membership_check_ins"]:
        try:
            tables[tbl] = c.execute(f"SELECT COUNT(*) FROM {tbl}").fetchone()[0]
        except Exception:
            tables[tbl] = 0
    conn.close()
    stat = os.stat(DB_PATH)
    backup_dir = os.path.join(os.path.dirname(DB_PATH), "backups")
    backups = []
    if os.path.isdir(backup_dir):
        for p in sorted(glob_mod.glob(os.path.join(backup_dir, "lc_sync_backup_*.db")), reverse=True):
            bs = os.stat(p)
            backups.append({"name": os.path.basename(p), "size_bytes": bs.st_size,
                            "modified": dt.fromtimestamp(bs.st_mtime).isoformat()})
    return jsonify({
        "db_size_bytes": stat.st_size,
        "db_path": DB_PATH,
        "tables": tables,
        "last_modified": dt.fromtimestamp(stat.st_mtime).isoformat(),
        "backups": backups
    })


@app.route("/api/backup/download")
@role_required("admin")
def api_backup_download():
    import shutil, tempfile
    from datetime import datetime as dt
    from flask import send_file as sf
    conn = get_db()
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    conn.close()
    ts = dt.now().strftime("%Y-%m-%d_%H%M%S")
    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=".db")
    tmp.close()
    shutil.copy2(DB_PATH, tmp.name)
    filename = f"lc_sync_{ts}.db"
    return sf(tmp.name, as_attachment=True, download_name=filename,
               mimetype="application/x-sqlite3")


@app.route("/api/backup/restore", methods=["POST"])
@role_required("admin")
def api_backup_restore():
    import shutil
    from datetime import datetime as dt
    if "db_file" not in request.files:
        return jsonify({"error": "no file uploaded"}), 400
    f = request.files["db_file"]
    if not f.filename.lower().endswith(".db"):
        return jsonify({"error": "file must be .db"}), 400
    header = f.read(16)
    if not header.startswith(b"SQLite format 3"):
        return jsonify({"error": "not a valid SQLite database"}), 400
    f.seek(0)
    backup_dir = os.path.join(os.path.dirname(DB_PATH), "backups")
    os.makedirs(backup_dir, exist_ok=True)
    ts = dt.now().strftime("%Y-%m-%d_%H%M%S")
    backup_name = f"lc_sync_backup_{ts}.db"
    shutil.copy2(DB_PATH, os.path.join(backup_dir, backup_name))
    f.save(DB_PATH)
    init_db()
    return jsonify({"status": "ok", "backup_auto": backup_name})


@app.route("/api/backup/download_file/<filename>")
@role_required("admin")
def api_backup_download_file(filename):
    import re
    from flask import send_file as sf
    if not re.match(r'^lc_sync_backup_[\w\-]+\.db$', filename):
        return "invalid filename", 400
    path = os.path.join(os.path.dirname(DB_PATH), "backups", filename)
    if not os.path.isfile(path):
        return "not found", 404
    return sf(path, as_attachment=True, download_name=filename,
               mimetype="application/x-sqlite3")


@app.route("/api/backup/delete_file/<filename>", methods=["POST"])
@role_required("admin")
def api_backup_delete_file(filename):
    import re
    if not re.match(r'^lc_sync_backup_[\w\-]+\.db$', filename):
        return jsonify({"error": "invalid"}), 400
    path = os.path.join(os.path.dirname(DB_PATH), "backups", filename)
    if os.path.isfile(path):
        os.remove(path)
    return jsonify({"status": "ok"})


@app.route("/api/export/full_zip", methods=["GET"])
@role_required("admin")
def api_export_full_zip():
    import zipfile
    import io as _io
    from datetime import datetime as _dt

    # Checkpoint WAL
    conn = get_db()
    try:
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    except Exception:
        pass
    conn.close()

    buffer = _io.BytesIO()
    ts = _dt.now().strftime("%Y-%m-%d_%H%M%S")

    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as zf:
        # 1. SQLite DB file
        db_path = DB_PATH
        if os.path.exists(db_path):
            zf.write(db_path, "lc_sync.db")

        # 2. Each table as JSON
        conn = get_db()
        cur = conn.cursor()
        tables = ["sync_jobs", "sync_work_items", "sync_technicians",
                  "sync_memberships", "sync_membership_check_ins",
                  "sync_audit_log", "sync_photos"]
        for tbl in tables:
            try:
                cur.execute("SELECT * FROM {}".format(tbl))
                cols = [d[0] for d in cur.description]
                rows = []
                for row in cur.fetchall():
                    r = dict(zip(cols, row))
                    if tbl == "sync_photos":
                        r.pop("base64Data", None)
                    rows.append(r)
                zf.writestr("{}.json".format(tbl),
                    json.dumps(rows, indent=2, ensure_ascii=False, default=str))
            except Exception as e:
                zf.writestr("{}.error.txt".format(tbl), str(e))
        conn.close()

        # 3. users.json without passwords
        users_path = os.path.join(os.path.dirname(__file__), "users.json")
        if os.path.exists(users_path):
            try:
                with open(users_path) as f:
                    users_data = json.load(f)
                safe_users = []
                for u in (users_data if isinstance(users_data, list) else [users_data]):
                    safe_u = {k: v for k, v in u.items() if k not in ("password", "password_hash")}
                    safe_users.append(safe_u)
                zf.writestr("users_no_passwords.json",
                    json.dumps(safe_users, indent=2, ensure_ascii=False))
            except Exception:
                pass

        # 4. README
        readme = """LC Technician - Export Completo
================================
Fecha: {fecha}

Contenido:
- lc_sync.db: Base de datos SQLite completa
- sync_jobs.json
- sync_work_items.json
- sync_technicians.json
- sync_memberships.json
- sync_membership_check_ins.json
- sync_audit_log.json
- sync_photos.json (sin base64Data)
- users_no_passwords.json
- README.txt: Este archivo
""".format(fecha=_dt.now().isoformat())
        zf.writestr("README.txt", readme)

    buffer.seek(0)
    filename = "lc_export_{}.zip".format(ts)
    return send_file(buffer, as_attachment=True, download_name=filename,
                     mimetype="application/zip")


def _count_tables():
    conn = get_db()
    c = conn.cursor()
    counts = {}
    for tbl in ["sync_jobs", "sync_work_items", "sync_technicians",
                "sync_memberships", "sync_membership_check_ins",
                "sync_audit_log", "sync_photos"]:
        try:
            counts[tbl] = c.execute(f"SELECT COUNT(*) FROM {tbl}").fetchone()[0]
        except Exception:
            counts[tbl] = -1
    conn.close()
    return counts


@app.route("/api/import/full_zip", methods=["POST"])
@role_required("admin")
def api_import_full_zip():
    import zipfile as _zf
    import io as _io
    import shutil as _shutil
    from datetime import datetime as _dt

    if "zip_file" not in request.files:
        return jsonify({"error": "no file uploaded"}), 400

    f = request.files["zip_file"]
    if not f.filename.lower().endswith(".zip"):
        return jsonify({"error": "must be .zip"}), 400

    try:
        zf = _zf.ZipFile(_io.BytesIO(f.read()))
    except Exception as e:
        return jsonify({"error": f"invalid zip: {e}"}), 400

    names = zf.namelist()
    mode = request.form.get("mode", "full")

    # Safety backup before any import
    backup_dir = os.path.join(os.path.dirname(DB_PATH), "backups")
    os.makedirs(backup_dir, exist_ok=True)
    ts = _dt.now().strftime("%Y-%m-%d_%H%M%S")
    auto_backup = f"lc_sync_backup_preimport_{ts}.db"
    if os.path.exists(DB_PATH):
        _shutil.copy2(DB_PATH, os.path.join(backup_dir, auto_backup))

    if mode == "full":
        if "lc_sync.db" not in names:
            return jsonify({"error": "missing lc_sync.db in ZIP"}), 400
        with zf.open("lc_sync.db") as src:
            with open(DB_PATH, "wb") as dst:
                _shutil.copyfileobj(src, dst)
        return jsonify({
            "status": "ok",
            "mode": "full",
            "auto_backup": auto_backup,
            "counts": _count_tables()
        })
    else:
        restored = {}
        for tbl in ["sync_jobs", "sync_work_items", "sync_technicians",
                    "sync_memberships", "sync_membership_check_ins",
                    "sync_audit_log"]:
            json_name = f"{tbl}.json"
            if json_name not in names:
                continue
            try:
                rows = json.loads(zf.read(json_name).decode("utf-8"))
                conn = get_db()
                c = conn.cursor()
                c.execute(f"DELETE FROM {tbl}")
                for r in rows:
                    cols = ",".join(str(k) for k in r.keys())
                    ph = ",".join(["?"] * len(r))
                    c.execute(f"INSERT INTO {tbl} ({cols}) VALUES ({ph})",
                              list(r.values()))
                conn.commit()
                conn.close()
                restored[tbl] = len(rows)
            except Exception as e:
                restored[tbl] = f"error: {e}"
        return jsonify({
            "status": "ok",
            "mode": "tables",
            "auto_backup": auto_backup,
            "restored": restored
        })


# ── Web Dashboard Routes (PROTECTED) ──────────────────────────────────────────

@app.route("/")
def index():
    if not session.get("logged_in"):
        return redirect(url_for("login"))
    return redirect(url_for("dashboard"))


@app.route("/dashboard")
@role_required(*ALL_ROLES)
def dashboard():
    conn = get_db()
    c = conn.cursor()
    total_jobs = c.execute("SELECT COUNT(*) FROM sync_jobs").fetchone()[0]
    completed  = c.execute("SELECT COUNT(*) FROM sync_jobs WHERE estado LIKE '%COMPLET%' OR estado LIKE '%REALIZ%'").fetchone()[0]
    pending    = c.execute("SELECT COUNT(*) FROM sync_jobs WHERE estado LIKE '%PEND%' OR estado LIKE '%EN RUT%'").fetchone()[0]
    revenue    = c.execute("SELECT COALESCE(SUM(subtotal),0) FROM sync_work_items").fetchone()[0]
    active_t   = c.execute("SELECT COUNT(*) FROM sync_technicians WHERE activo=1").fetchone()[0]
    total_wi   = c.execute("SELECT COUNT(*) FROM sync_work_items").fetchone()[0]
    recent_wi  = c.execute("""SELECT w.*, j.cliente FROM sync_work_items w
                               LEFT JOIN sync_jobs j ON j.id=w.jobId
                               ORDER BY w.synced_at DESC LIMIT 10""").fetchall()
    jobs_status = c.execute("SELECT COALESCE(estado,'Sin estado') as s, COUNT(*) as n FROM sync_jobs GROUP BY estado").fetchall()
    top_techs   = c.execute("""SELECT t.nombre, COUNT(j.id) as cnt FROM sync_technicians t
                                LEFT JOIN sync_jobs j ON j.technicianId=t.nombre
                                GROUP BY t.id ORDER BY cnt DESC LIMIT 5""").fetchall()
    conn.close()
    avg_ticket = round(float(revenue) / total_jobs, 2) if total_jobs > 0 else 0
    return render_template("dashboard.html",
        total_jobs=total_jobs, completed=completed, pending=pending,
        revenue=float(revenue), active_t=active_t, total_wi=total_wi,
        avg_ticket=avg_ticket, recent_wi=recent_wi,
        jobs_status=jobs_status, top_techs=top_techs,
        now=ts_to_str(int(time.time()*1000)),
        current_user=session.get("username", "admin"),
        current_role=session.get("role", "viewer"),
        current_name=session.get("name", "admin"))


@app.route("/jobs")
@role_required(*ALL_ROLES)
def jobs_view():
    status_filter = request.args.get("status", "")
    search = request.args.get("q", "")
    conn = get_db()
    c = conn.cursor()
    query = "SELECT j.*, COALESCE(SUM(w.subtotal),0) as revenue FROM sync_jobs j LEFT JOIN sync_work_items w ON w.jobId=j.id"
    conditions = []
    params = []
    if status_filter:
        conditions.append("j.estado LIKE ?")
        params.append(f"%{status_filter}%")
    if search:
        conditions.append("j.cliente LIKE ?")
        params.append(f"%{search}%")
    if conditions:
        query += " WHERE " + " AND ".join(conditions)
    query += " GROUP BY j.id ORDER BY j.id DESC"
    rows = c.execute(query, params).fetchall()
    conn.close()
    return render_template("jobs.html", jobs=rows, status_filter=status_filter,
                           search=search, current_user=session.get("username", "admin"),
                           current_role=session.get("role", "viewer"),
                           current_name=session.get("name", "admin"))


@app.route("/technicians")
@role_required(*ALL_ROLES)
def technicians_view():
    conn = get_db()
    c = conn.cursor()
    techs = c.execute("""SELECT t.*, COUNT(j.id) as jobs_active
                          FROM sync_technicians t
                          LEFT JOIN sync_jobs j ON j.technicianId=t.nombre
                          GROUP BY t.id ORDER BY t.nombre""").fetchall()
    conn.close()
    return render_template("technicians.html", technicians=techs,
                           current_user=session.get("username", "admin"),
                           current_role=session.get("role", "viewer"),
                           current_name=session.get("name", "admin"))


@app.route("/work_items")
@role_required(*ALL_ROLES)
def work_items_view():
    job_filter = request.args.get("job_id", "")
    conn = get_db()
    c = conn.cursor()
    if job_filter:
        rows = c.execute("""SELECT w.*, j.cliente FROM sync_work_items w
                             LEFT JOIN sync_jobs j ON j.id=w.jobId
                             WHERE w.jobId=? ORDER BY w.jobId, w.id""", (job_filter,)).fetchall()
    else:
        rows = c.execute("""SELECT w.*, j.cliente FROM sync_work_items w
                             LEFT JOIN sync_jobs j ON j.id=w.jobId
                             ORDER BY w.jobId, w.id LIMIT 300""").fetchall()
    total = sum(r["subtotal"] or 0 for r in rows)
    jobs_list = c.execute("SELECT id, cliente FROM sync_jobs ORDER BY id").fetchall()
    conn.close()
    return render_template("work_items.html", items=rows, total=total,
                           job_filter=job_filter, jobs_list=jobs_list,
                           current_user=session.get("username", "admin"),
                           current_role=session.get("role", "viewer"),
                           current_name=session.get("name", "admin"))


@app.route("/memberships")
@role_required(*ALL_ROLES)
def memberships_view():
    status_filter = request.args.get("status", "")
    search = request.args.get("q", "")
    conn = get_db()
    c = conn.cursor()
    query = "SELECT * FROM sync_memberships"
    conditions, params = [], []
    if status_filter:
        conditions.append("status = ?")
        params.append(status_filter)
    if search:
        conditions.append("(cardNumber LIKE ? OR notes LIKE ?)")
        params += [f"%{search}%", f"%{search}%"]
    if conditions:
        query += " WHERE " + " AND ".join(conditions)
    query += " ORDER BY id"
    rows = c.execute(query, params).fetchall()
    conn.close()
    now_ms = int(time.time() * 1000)
    memberships = []
    for r in rows:
        d = dict(r)
        d["issue_str"] = ts_to_str(d.get("issueDate")) if d.get("issueDate") else "—"
        d["expiry_str"] = ts_to_str(d.get("expiryDate")) if d.get("expiryDate") else "—"
        d["expired"] = d.get("expiryDate") and d["expiryDate"] < now_ms
        d["expiring_soon"] = (d.get("expiryDate") and
                              0 < d["expiryDate"] - now_ms < 30 * 86400 * 1000)
        memberships.append(d)
    active = sum(1 for m in memberships if m.get("status") == "ACTIVE")
    return render_template("memberships.html",
                           memberships=memberships, checkins=[],
                           status_filter=status_filter, search=search,
                           active_count=active,
                           current_user=session.get("username", "admin"),
                           current_role=session.get("role", "viewer"),
                           current_name=session.get("name", "admin"))


@app.route("/memberships/<int:card_id>")
@role_required(*ALL_ROLES)
def membership_detail_view(card_id):
    conn = get_db()
    c = conn.cursor()
    card = c.execute("SELECT * FROM sync_memberships WHERE id=?", (card_id,)).fetchone()
    if not card:
        conn.close()
        return "Membresía no encontrada", 404
    card = dict(card)
    now_ms = int(time.time() * 1000)
    card["issue_str"] = ts_to_str(card.get("issueDate")) if card.get("issueDate") else "—"
    card["expiry_str"] = ts_to_str(card.get("expiryDate")) if card.get("expiryDate") else "—"
    card["expired"] = card.get("expiryDate") and card["expiryDate"] < now_ms
    card["expiring_soon"] = (card.get("expiryDate") and
                             0 < card["expiryDate"] - now_ms < 30 * 86400 * 1000)
    rows = c.execute(
        "SELECT * FROM sync_membership_check_ins WHERE cardId=? ORDER BY checkInDate DESC",
        (card_id,)).fetchall()
    checkins = []
    for r in rows:
        d = dict(r)
        d["date_str"] = ts_to_str(d.get("checkInDate"))
        checkins.append(d)
    prev_card = c.execute(
        "SELECT id FROM sync_memberships WHERE id < ? ORDER BY id DESC LIMIT 1",
        (card_id,)).fetchone()
    next_card = c.execute(
        "SELECT id FROM sync_memberships WHERE id > ? ORDER BY id ASC LIMIT 1",
        (card_id,)).fetchone()
    # Renewal history from audit log
    renewal_rows = c.execute(
        """SELECT * FROM sync_audit_log
           WHERE entityType='MEMBERSHIP' AND entityId=? AND action='RENEW'
           ORDER BY timestamp DESC""", (card_id,)).fetchall()
    renewal_cols = [d[0] for d in c.description]
    renewal_history = []
    for rr in renewal_rows:
        rd = dict(zip(renewal_cols, rr))
        # timestamp may be a string like "2026-10-05T..." or integer ms
        ts = rd.get("timestamp", "")
        if ts and str(ts).isdigit():
            try:
                rd["timestamp"] = datetime.fromtimestamp(int(ts) / 1000).strftime("%d %b %Y %H:%M")
            except Exception:
                pass
        rd["details"] = rd.get("extra") or rd.get("details") or ""
        renewal_history.append(rd)
    conn.close()
    return render_template("membership_detail.html",
        card=card, checkins=checkins,
        renewal_history=renewal_history,
        prev_id=prev_card["id"] if prev_card else None,
        next_id=next_card["id"] if next_card else None,
        active="memberships",
        current_user=session.get("username", "admin"),
        current_role=session.get("role", "viewer"),
        current_name=session.get("name", "admin"))


@app.route("/backup")
@role_required("admin")
def backup_view():
    import glob as glob_mod
    from datetime import datetime as dt
    conn = get_db()
    c = conn.cursor()
    tables = {}
    for tbl in ["sync_jobs", "sync_work_items", "sync_technicians",
                "sync_memberships", "sync_audit_log", "sync_membership_check_ins"]:
        try:
            tables[tbl] = c.execute(f"SELECT COUNT(*) FROM {tbl}").fetchone()[0]
        except Exception:
            tables[tbl] = 0
    conn.close()
    stat = os.stat(DB_PATH)
    db_size = stat.st_size
    last_mod = dt.fromtimestamp(stat.st_mtime).strftime("%Y-%m-%d %H:%M:%S")
    backup_dir = os.path.join(os.path.dirname(DB_PATH), "backups")
    backups = []
    auto_backups = []
    if os.path.isdir(backup_dir):
        for p in sorted(glob_mod.glob(os.path.join(backup_dir, "lc_sync_backup_*.db")), reverse=True):
            bs = os.stat(p)
            backups.append({"name": os.path.basename(p),
                            "size_kb": round(bs.st_size / 1024, 1),
                            "modified": dt.fromtimestamp(bs.st_mtime).strftime("%Y-%m-%d %H:%M:%S")})
        for p in sorted(glob_mod.glob(os.path.join(backup_dir, "auto_*.db")), reverse=True):
            bs = os.stat(p)
            auto_backups.append({"name": os.path.basename(p),
                            "size_kb": round(bs.st_size / 1024, 1),
                            "modified": dt.fromtimestamp(bs.st_mtime).strftime("%Y-%m-%d %H:%M:%S")})
    sched_status = {"last_run": _sched_state.get("last_run", "Nunca"),
                    "next_run": _sched_state.get("next_run", "Pendiente"),
                    "count": len(auto_backups)}
    return render_template("backup.html",
        db_size_kb=round(db_size / 1024, 1), last_mod=last_mod,
        tables=tables, backups=backups, auto_backups=auto_backups,
        sched_status=sched_status,
        active="backup",
        current_user=session.get("username", "admin"),
        current_role=session.get("role", "viewer"),
        current_name=session.get("name", "admin"))


# ── Audit Log API (PROTECTED) ─────────────────────────────────────────────────

@app.route("/api/audit_log", methods=["GET"])
@role_required(*ALL_ROLES)
def api_audit_log():
    action     = request.args.get("action", "")
    entity_type = request.args.get("entity_type", "")
    q          = request.args.get("q", "")
    from_ts    = request.args.get("from", 0, type=int)
    to_ts      = request.args.get("to", 0, type=int)
    page       = max(1, request.args.get("page", 1, type=int))
    per_page   = 50
    offset     = (page - 1) * per_page

    conn = get_db()
    c = conn.cursor()
    conditions, params = [], []
    if action:
        conditions.append("action = ?")
        params.append(action)
    if entity_type:
        conditions.append("entityType = ?")
        params.append(entity_type)
    if q:
        conditions.append("(actor LIKE ? OR action LIKE ? OR entityType LIKE ?)")
        params += [f"%{q}%", f"%{q}%", f"%{q}%"]
    if from_ts:
        conditions.append("timestamp >= ?")
        params.append(from_ts)
    if to_ts:
        conditions.append("timestamp <= ?")
        params.append(to_ts)
    where = ("WHERE " + " AND ".join(conditions)) if conditions else ""
    total = c.execute(f"SELECT COUNT(*) FROM sync_audit_log {where}", params).fetchone()[0]
    rows = c.execute(
        f"SELECT * FROM sync_audit_log {where} ORDER BY timestamp DESC LIMIT ? OFFSET ?",
        params + [per_page, offset]).fetchall()
    conn.close()
    return jsonify({
        "total": total, "page": page, "per_page": per_page,
        "entries": [dict(r) for r in rows]
    })


@app.route("/api/audit_log/stats", methods=["GET"])
@role_required(*ALL_ROLES)
def api_audit_log_stats():
    conn = get_db()
    c = conn.cursor()
    total = c.execute("SELECT COUNT(*) FROM sync_audit_log").fetchone()[0]
    by_action = c.execute(
        "SELECT action, COUNT(*) as cnt FROM sync_audit_log GROUP BY action ORDER BY cnt DESC"
    ).fetchall()
    by_entity = c.execute(
        "SELECT entityType, COUNT(*) as cnt FROM sync_audit_log GROUP BY entityType ORDER BY cnt DESC"
    ).fetchall()
    by_actor = c.execute(
        "SELECT actor, COUNT(*) as cnt FROM sync_audit_log GROUP BY actor ORDER BY cnt DESC LIMIT 10"
    ).fetchall()
    conn.close()
    return jsonify({
        "total": total,
        "by_action": [dict(r) for r in by_action],
        "by_entity": [dict(r) for r in by_entity],
        "by_actor": [dict(r) for r in by_actor]
    })


@app.route("/audit_log")
@role_required(*ALL_ROLES)
def audit_log_view():
    action_filter = request.args.get("action", "")
    entity_filter = request.args.get("entity_type", "")
    q = request.args.get("q", "")
    page = max(1, request.args.get("page", 1, type=int))
    per_page = 50
    offset = (page - 1) * per_page

    conn = get_db()
    c = conn.cursor()
    conditions, params = [], []
    if action_filter:
        conditions.append("action = ?")
        params.append(action_filter)
    if entity_filter:
        conditions.append("entityType = ?")
        params.append(entity_filter)
    if q:
        conditions.append("(actor LIKE ? OR action LIKE ? OR entityType LIKE ?)")
        params += [f"%{q}%", f"%{q}%", f"%{q}%"]
    where = ("WHERE " + " AND ".join(conditions)) if conditions else ""

    total = c.execute(f"SELECT COUNT(*) FROM sync_audit_log {where}", params).fetchone()[0]
    rows = c.execute(
        f"SELECT * FROM sync_audit_log {where} ORDER BY timestamp DESC LIMIT ? OFFSET ?",
        params + [per_page, offset]).fetchall()
    kpi_total = c.execute("SELECT COUNT(*) FROM sync_audit_log").fetchone()[0]
    kpi_creates = c.execute("SELECT COUNT(*) FROM sync_audit_log WHERE action='CREATE'").fetchone()[0]
    kpi_updates = c.execute("SELECT COUNT(*) FROM sync_audit_log WHERE action='UPDATE'").fetchone()[0]
    kpi_deletes = c.execute("SELECT COUNT(*) FROM sync_audit_log WHERE action='DELETE'").fetchone()[0]
    actions = [r[0] for r in c.execute("SELECT DISTINCT action FROM sync_audit_log ORDER BY action").fetchall()]
    entity_types = [r[0] for r in c.execute("SELECT DISTINCT entityType FROM sync_audit_log ORDER BY entityType").fetchall()]
    conn.close()

    entries = []
    for r in rows:
        d = dict(r)
        d["ts_str"] = ts_to_str(d.get("timestamp"))
        entries.append(d)

    total_pages = (total + per_page - 1) // per_page
    return render_template("audit_log.html",
        entries=entries, total=total, page=page, total_pages=total_pages,
        action_filter=action_filter, entity_filter=entity_filter, q=q,
        actions=actions, entity_types=entity_types,
        kpi_total=kpi_total, kpi_creates=kpi_creates,
        kpi_updates=kpi_updates, kpi_deletes=kpi_deletes,
        current_user=session.get("username", "admin"),
        current_role=session.get("role", "viewer"),
        current_name=session.get("name", "admin"))



# ── Job Detail (PROTECTED) ────────────────────────────────────────────────────

@app.route("/jobs/<int:job_id>")
@role_required(*ALL_ROLES)
def job_detail_view(job_id):
    conn = get_db()
    c = conn.cursor()
    job = c.execute("SELECT * FROM sync_jobs WHERE id=?", (job_id,)).fetchone()
    if not job:
        conn.close()
        return "Job no encontrado", 404

    work_items = c.execute(
        "SELECT * FROM sync_work_items WHERE jobId=? ORDER BY id", (job_id,)).fetchall()
    revenue = sum(r["subtotal"] or 0 for r in work_items)

    audit_entries = c.execute(
        "SELECT * FROM sync_audit_log WHERE jobId=? ORDER BY timestamp DESC LIMIT 30",
        (job_id,)).fetchall()

    prev_job = c.execute(
        "SELECT id FROM sync_jobs WHERE id < ? ORDER BY id DESC LIMIT 1", (job_id,)).fetchone()
    next_job = c.execute(
        "SELECT id FROM sync_jobs WHERE id > ? ORDER BY id ASC LIMIT 1", (job_id,)).fetchone()
    photos_rows = c.execute(
        "SELECT id, jobId, filename, sizeBytes, mimeType, timestamp FROM sync_photos WHERE jobId=? ORDER BY timestamp ASC",
        (job_id,)).fetchall()
    conn.close()

    audit = []
    for r in audit_entries:
        d = dict(r)
        d["ts_str"] = ts_to_str(d.get("timestamp"))
        audit.append(d)

    wi_list = [dict(r) for r in work_items]
    job_dict = dict(job)
    photos = [dict(r) for r in photos_rows]

    return render_template("job_detail.html",
        job=job_dict, work_items=wi_list, revenue=revenue,
        audit=audit, photos=photos,
        prev_id=prev_job["id"] if prev_job else None,
        next_id=next_job["id"] if next_job else None,
        current_user=session.get("username", "admin"),
        current_role=session.get("role", "viewer"),
        current_name=session.get("name", "admin"))


@app.route("/api/jobs/<int:job_id>/full", methods=["GET"])
@role_required(*ALL_ROLES)
def api_job_full(job_id):
    conn = get_db()
    c = conn.cursor()
    job = c.execute("SELECT * FROM sync_jobs WHERE id=?", (job_id,)).fetchone()
    if not job:
        conn.close()
        return jsonify({"error": "not found"}), 404
    work_items = c.execute(
        "SELECT * FROM sync_work_items WHERE jobId=? ORDER BY id", (job_id,)).fetchall()
    audit = c.execute(
        "SELECT * FROM sync_audit_log WHERE jobId=? ORDER BY timestamp DESC LIMIT 50",
        (job_id,)).fetchall()
    conn.close()
    return jsonify({
        "job": dict(job),
        "work_items": [dict(r) for r in work_items],
        "audit_log": [dict(r) for r in audit],
        "revenue": sum(r["subtotal"] or 0 for r in work_items)
    })



# ── Analytics API (PROTECTED) ─────────────────────────────────────────────────

@app.route("/api/analytics/overview", methods=["GET"])
@role_required(*ALL_ROLES)
def api_analytics_overview():
    conn = get_db()
    c = conn.cursor()
    now_ms = int(time.time() * 1000)

    total_jobs = c.execute("SELECT COUNT(*) FROM sync_jobs").fetchone()[0]
    by_status = {}
    for r in c.execute("SELECT COALESCE(estado,'Sin estado'), COUNT(*) FROM sync_jobs GROUP BY estado").fetchall():
        by_status[r[0]] = r[1]

    total_rev = float(c.execute("SELECT COALESCE(SUM(subtotal),0) FROM sync_work_items").fetchone()[0])
    avg_ticket = round(total_rev / total_jobs, 2) if total_jobs > 0 else 0

    by_month_jobs = [dict(r) for r in c.execute("""
        SELECT strftime('%Y-%m', datetime(synced_at/1000,'unixepoch')) as month,
               COUNT(*) as count
        FROM sync_jobs GROUP BY month ORDER BY month DESC LIMIT 12
    """).fetchall()]

    by_month_rev = [dict(r) for r in c.execute("""
        SELECT strftime('%Y-%m', datetime(synced_at/1000,'unixepoch')) as month,
               COALESCE(SUM(subtotal),0) as amount
        FROM sync_work_items GROUP BY month ORDER BY month DESC LIMIT 12
    """).fetchall()]

    wi_total = c.execute("SELECT COUNT(*) FROM sync_work_items").fetchone()[0]
    by_type = {}
    for r in c.execute("SELECT COALESCE(tipo,'OTHER'), COUNT(*) FROM sync_work_items GROUP BY tipo").fetchall():
        by_type[r[0]] = r[1]

    tech_total = c.execute("SELECT COUNT(*) FROM sync_technicians WHERE activo=1").fetchone()[0]
    tech_by_jobs = [dict(r) for r in c.execute("""
        SELECT t.nombre as name, COUNT(j.id) as jobs
        FROM sync_technicians t
        LEFT JOIN sync_jobs j ON j.technicianId = t.nombre
        GROUP BY t.id ORDER BY jobs DESC LIMIT 5
    """).fetchall()]

    mem_total = c.execute("SELECT COUNT(*) FROM sync_memberships").fetchone()[0]
    mem_active = c.execute("SELECT COUNT(*) FROM sync_memberships WHERE status='ACTIVE'").fetchone()[0]
    mem_expiring = c.execute("""
        SELECT COUNT(*) FROM sync_memberships
        WHERE status='ACTIVE' AND expiryDate > ? AND expiryDate < ?
    """, (now_ms, now_ms + 30*86400*1000)).fetchone()[0]
    conn.close()

    return jsonify({
        "jobs": {"total": total_jobs, "by_status": by_status, "by_month": by_month_jobs},
        "revenue": {"total": total_rev, "by_month": by_month_rev, "avg_ticket": avg_ticket},
        "technicians": {"total": tech_total, "by_jobs": tech_by_jobs},
        "work_items": {"total": wi_total, "by_type": by_type},
        "memberships": {"total": mem_total, "active": mem_active, "expiring_soon": mem_expiring}
    })


@app.route("/api/analytics/trends", methods=["GET"])
@role_required(*ALL_ROLES)
def api_analytics_trends():
    days = int(request.args.get("days", 30))
    now_ms = int(time.time() * 1000)
    since_ms = now_ms - days * 86400 * 1000
    conn = get_db()
    c = conn.cursor()

    jobs_per_day = [dict(r) for r in c.execute("""
        SELECT date(synced_at/1000,'unixepoch') as date, COUNT(*) as count
        FROM sync_jobs WHERE synced_at >= ?
        GROUP BY date ORDER BY date ASC
    """, (since_ms,)).fetchall()]

    rev_per_day = [dict(r) for r in c.execute("""
        SELECT date(synced_at/1000,'unixepoch') as date,
               COALESCE(SUM(subtotal),0) as amount
        FROM sync_work_items WHERE synced_at >= ?
        GROUP BY date ORDER BY date ASC
    """, (since_ms,)).fetchall()]

    audit_per_day = [dict(r) for r in c.execute("""
        SELECT date(timestamp/1000,'unixepoch') as date, COUNT(*) as count
        FROM sync_audit_log WHERE timestamp >= ?
        GROUP BY date ORDER BY date ASC
    """, (since_ms,)).fetchall()]

    conn.close()
    return jsonify({
        "days": days,
        "jobs_per_day": jobs_per_day,
        "revenue_per_day": rev_per_day,
        "audit_per_day": audit_per_day
    })


@app.route("/api/analytics/top", methods=["GET"])
@role_required(*ALL_ROLES)
def api_analytics_top():
    limit = int(request.args.get("limit", 5))
    conn = get_db()
    c = conn.cursor()

    top_customers = [dict(r) for r in c.execute("""
        SELECT j.cliente as name, COUNT(j.id) as jobs,
               COALESCE(SUM(w.subtotal),0) as revenue
        FROM sync_jobs j
        LEFT JOIN sync_work_items w ON w.jobId = j.id
        WHERE j.cliente IS NOT NULL
        GROUP BY j.cliente ORDER BY revenue DESC LIMIT ?
    """, (limit,)).fetchall()]

    top_technicians = [dict(r) for r in c.execute("""
        SELECT t.nombre as name, COUNT(j.id) as jobs,
               COALESCE(SUM(w.subtotal),0) as revenue
        FROM sync_technicians t
        LEFT JOIN sync_jobs j ON j.technicianId = t.nombre
        LEFT JOIN sync_work_items w ON w.jobId = j.id
        GROUP BY t.id ORDER BY jobs DESC LIMIT ?
    """, (limit,)).fetchall()]

    top_wi_types = [dict(r) for r in c.execute("""
        SELECT COALESCE(tipo,'OTHER') as type,
               COUNT(*) as count,
               COALESCE(SUM(subtotal),0) as revenue
        FROM sync_work_items
        GROUP BY tipo ORDER BY count DESC LIMIT ?
    """, (limit,)).fetchall()]

    conn.close()
    return jsonify({
        "top_customers": top_customers,
        "top_technicians": top_technicians,
        "top_work_item_types": top_wi_types
    })


@app.route("/analytics")
@role_required(*ALL_ROLES)
def analytics_view():
    return render_template("analytics.html",
        current_user=session.get("username", "admin"),
        current_role=session.get("role", "viewer"),
        current_name=session.get("name", "admin"))


# ── Advanced Analytics — Fase 49 ─────────────────────────────────────────────

@app.route("/api/analytics/yoy", methods=["GET"])
@role_required(*ALL_ROLES)
def api_analytics_yoy():
    conn = get_db()
    c = conn.cursor()
    now = datetime.now()

    # Current month
    cur_start = int(datetime(now.year, now.month, 1).timestamp() * 1000)
    cur_end = int(now.timestamp() * 1000)

    # Same month last year
    prev_start = int(datetime(now.year - 1, now.month, 1).timestamp() * 1000)
    prev_end = int(datetime(now.year - 1, now.month, now.day).timestamp() * 1000)

    def get_stats(start, end):
        jobs = c.execute("""SELECT COUNT(*) as cnt FROM sync_jobs
                           WHERE updatedAt BETWEEN ? AND ?""", (start, end)).fetchone()
        job_count = jobs[0] if jobs else 0
        rev = c.execute("""SELECT COALESCE(SUM(subtotal), 0)
                          FROM sync_work_items WHERE updatedAt BETWEEN ? AND ?""",
                       (start, end)).fetchone()
        revenue = rev[0] if rev else 0
        return {"jobs": job_count, "revenue": round(float(revenue), 2)}

    current = get_stats(cur_start, cur_end)
    previous = get_stats(prev_start, prev_end)

    def delta_pct(cur, prev):
        if prev == 0:
            return 100 if cur > 0 else 0
        return round((cur - prev) / prev * 100, 1)

    conn.close()
    return jsonify({
        "current": current,
        "previous": previous,
        "period": "{} vs {}".format(
            now.strftime("%B %Y"),
            datetime(now.year - 1, now.month, 1).strftime("%B %Y")
        ),
        "delta": {
            "jobs": delta_pct(current["jobs"], previous["jobs"]),
            "revenue": delta_pct(current["revenue"], previous["revenue"])
        }
    })


@app.route("/api/analytics/forecast", methods=["GET"])
@role_required(*ALL_ROLES)
def api_analytics_forecast():
    conn = get_db()
    c = conn.cursor()
    now_ms = int(time.time() * 1000)
    thirty_days_ms = 30 * 24 * 3600 * 1000
    start_ms = now_ms - thirty_days_ms

    rows = c.execute("""SELECT COUNT(*) as cnt FROM sync_jobs
                       WHERE updatedAt >= ?""", (start_ms,)).fetchone()
    jobs_last_30 = rows[0] if rows else 0
    daily_rate = jobs_last_30 / 30.0

    rev = c.execute("""SELECT COALESCE(SUM(subtotal), 0)
                      FROM sync_work_items WHERE updatedAt >= ?""", (start_ms,)).fetchone()
    rev_last_30 = rev[0] if rev else 0
    daily_rev = float(rev_last_30) / 30.0

    forecast_jobs = round(daily_rate * 30)
    forecast_revenue = round(daily_rev * 30, 2)

    total_jobs = c.execute("SELECT COUNT(*) FROM sync_jobs").fetchone()[0]
    confidence = min(95, max(20, int(total_jobs * 2.5)))

    conn.close()
    return jsonify({
        "next_30d_jobs": forecast_jobs,
        "next_30d_revenue": forecast_revenue,
        "confidence": confidence,
        "daily_avg_jobs": round(daily_rate, 1),
        "daily_avg_revenue": round(daily_rev, 2),
        "based_on_days": 30
    })


@app.route("/api/analytics/top_customers", methods=["GET"])
@role_required(*ALL_ROLES)
def api_top_customers():
    days = request.args.get("days", 90, type=int)
    limit = request.args.get("limit", 5, type=int)
    conn = get_db()
    c = conn.cursor()
    since_ms = int((time.time() - days * 86400) * 1000)

    rows = c.execute("""
        SELECT j.cliente, COUNT(*) as job_count,
               COALESCE(SUM(wi.subtotal), 0) as revenue,
               MAX(j.updatedAt) as last_service
        FROM sync_jobs j
        LEFT JOIN sync_work_items wi ON wi.jobId = j.id
        WHERE j.updatedAt >= ?
        GROUP BY j.cliente
        ORDER BY revenue DESC, job_count DESC
        LIMIT ?
    """, (since_ms, limit)).fetchall()

    conn.close()
    return jsonify([{
        "cliente": r[0] or "Sin nombre",
        "jobs": r[1],
        "revenue": round(float(r[2]), 2),
        "last_service": r[3]
    } for r in rows])


@app.route("/api/analytics/top_services", methods=["GET"])
@role_required(*ALL_ROLES)
def api_top_services():
    days = request.args.get("days", 90, type=int)
    limit = request.args.get("limit", 5, type=int)
    conn = get_db()
    c = conn.cursor()
    since_ms = int((time.time() - days * 86400) * 1000)

    rows = c.execute("""
        SELECT COALESCE(tipo, descripcion, 'Servicio general') as service_type,
               COUNT(*) as count,
               COALESCE(SUM(subtotal), 0) as revenue
        FROM sync_work_items
        WHERE updatedAt >= ?
        GROUP BY service_type
        ORDER BY revenue DESC, count DESC
        LIMIT ?
    """, (since_ms, limit)).fetchall()

    conn.close()
    return jsonify([{
        "service": r[0],
        "count": r[1],
        "revenue": round(float(r[2]), 2)
    } for r in rows])


# ── AuditLog CSV Export (admin + dispatcher only) ────────────────────────────

@app.route("/api/audit_log/export")
@role_required("admin", "dispatcher")
def api_audit_log_export():
    import csv, io
    action = request.args.get("action", "")
    entity_type = request.args.get("entity_type", "")
    q = request.args.get("q", "")
    from_ts = request.args.get("from", 0, type=int)
    to_ts = request.args.get("to", 0, type=int)

    conn = get_db()
    c = conn.cursor()
    conditions, params = [], []
    if action:
        conditions.append("action = ?"); params.append(action)
    if entity_type:
        conditions.append("entityType = ?"); params.append(entity_type)
    if q:
        conditions.append("(actor LIKE ? OR action LIKE ? OR entityType LIKE ?)")
        params += [f"%{q}%", f"%{q}%", f"%{q}%"]
    if from_ts:
        conditions.append("timestamp >= ?"); params.append(from_ts)
    if to_ts:
        conditions.append("timestamp <= ?"); params.append(to_ts)
    where = ("WHERE " + " AND ".join(conditions)) if conditions else ""
    rows = c.execute(
        f"SELECT * FROM sync_audit_log {where} ORDER BY timestamp DESC",
        params).fetchall()
    conn.close()

    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow(["ID", "Fecha", "Accion", "Entidad", "EntidadID",
                     "JobID", "Actor", "Campo", "ValorAnterior", "ValorNuevo", "Extra"])
    for r in rows:
        d = dict(r)
        writer.writerow([
            d.get("id"), ts_to_str(d.get("timestamp")),
            d.get("action"), d.get("entityType"), d.get("entityId"),
            d.get("jobId"), d.get("actor"), d.get("field"),
            d.get("oldValue"), d.get("newValue"), d.get("extra")
        ])

    from datetime import date
    filename = f"audit_log_{date.today().isoformat()}.csv"
    from flask import Response
    return Response(
        output.getvalue(),
        mimetype="text/csv",
        headers={"Content-Disposition": f"attachment; filename={filename}"}
    )


# ── Photos API (PROTECTED) ────────────────────────────────────────────────────

@app.route("/api/jobs/<int:job_id>/photos", methods=["GET"])
@role_required(*ALL_ROLES)
def api_job_photos(job_id):
    conn = get_db()
    c = conn.cursor()
    rows = c.execute(
        "SELECT id, jobId, filename, sizeBytes, mimeType, timestamp FROM sync_photos WHERE jobId=? ORDER BY timestamp ASC",
        (job_id,)).fetchall()
    conn.close()
    return jsonify([dict(r) for r in rows])


@app.route("/api/photos/<int:photo_id>/image", methods=["GET"])
@role_required(*ALL_ROLES)
def api_photo_image(photo_id):
    import base64
    from flask import Response, make_response
    conn = get_db()
    c = conn.cursor()
    row = c.execute("SELECT base64Data, mimeType FROM sync_photos WHERE id=?", (photo_id,)).fetchone()
    conn.close()
    if not row or not row["base64Data"]:
        return "Photo not found", 404
    try:
        img_bytes = base64.b64decode(row["base64Data"])
        mime = row["mimeType"] or "image/jpeg"
        resp = make_response(img_bytes)
        resp.headers["Content-Type"] = mime
        resp.headers["Cache-Control"] = "public, max-age=86400"
        return resp
    except Exception:
        return "Invalid image data", 500


# ── Sync Notifications API (PROTECTED) ────────────────────────────────────────

@app.route("/api/sync/notifications", methods=["GET"])
@role_required(*ALL_ROLES)
def api_sync_notifications():
    since = request.args.get("since", 0, type=int)
    now_ms = int(time.time() * 1000)
    if since == 0:
        since = now_ms - 5 * 60 * 1000  # default: last 5 min

    conn = get_db()
    c = conn.cursor()

    # Recently updated jobs (that have a technician assigned)
    jobs_rows = c.execute("""
        SELECT id, cliente, hora, technicianId, updatedAt
        FROM sync_jobs
        WHERE technicianId IS NOT NULL
          AND updatedAt >= ?
        ORDER BY updatedAt DESC LIMIT 10
    """, (since,)).fetchall()
    jobs_cols = [d[0] for d in c.description]

    # Recent RENEW actions in audit log
    renewal_rows = c.execute("""
        SELECT entityId, timestamp, actor
        FROM sync_audit_log
        WHERE action = 'RENEW' AND entityType = 'MEMBERSHIP'
          AND timestamp >= ?
        ORDER BY timestamp DESC LIMIT 10
    """, (since,)).fetchall()
    renewal_cols = [d[0] for d in c.description]

    conn.close()

    return jsonify({
        "since": since,
        "now": now_ms,
        "jobs": [dict(zip(jobs_cols, r)) for r in jobs_rows],
        "renewals": [dict(zip(renewal_cols, r)) for r in renewal_rows]
    })


# ── Maintenance API (PROTECTED — admin only) ──────────────────────────────────

_SERVER_START = time.time()


@app.route("/api/maintenance/status", methods=["GET"])
@role_required("admin")
def api_maintenance_status():
    import sys, shutil
    conn = get_db()
    c = conn.cursor()
    tables = {}
    for t in ["sync_jobs", "sync_work_items", "sync_technicians", "sync_memberships",
              "sync_audit_log", "sync_membership_check_ins", "sync_photos"]:
        tables[t] = c.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
    conn.close()

    db_size = os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0
    wal_path = DB_PATH + "-wal"
    wal_size = os.path.getsize(wal_path) if os.path.exists(wal_path) else 0
    db_mtime = datetime.fromtimestamp(os.path.getmtime(DB_PATH)).strftime("%Y-%m-%d %H:%M:%S") if os.path.exists(DB_PATH) else "—"

    backup_dir = os.path.join(os.path.dirname(DB_PATH), "backups")
    backups = []
    if os.path.isdir(backup_dir):
        backups = [f for f in os.listdir(backup_dir) if f.endswith(".db")]
    backup_total_kb = sum(
        os.path.getsize(os.path.join(backup_dir, f)) // 1024
        for f in backups if os.path.exists(os.path.join(backup_dir, f))
    )
    last_backup = sorted(backups)[-1] if backups else None

    disk = shutil.disk_usage(os.path.dirname(DB_PATH))

    return jsonify({
        "server": {
            "flask_version": VERSION,
            "uptime_seconds": int(time.time() - _SERVER_START),
            "python_version": sys.version.split()[0],
            "pid": os.getpid()
        },
        "db": {
            "size_bytes": db_size,
            "wal_size_bytes": wal_size,
            "tables": tables,
            "last_modified": db_mtime
        },
        "backups": {
            "count": len(backups),
            "total_size_kb": backup_total_kb,
            "last_backup": last_backup
        },
        "disk": {
            "free_kb": disk.free // 1024,
            "used_kb": disk.used // 1024
        }
    })


@app.route("/api/maintenance/vacuum", methods=["POST"])
@role_required("admin")
def api_maintenance_vacuum():
    size_before = os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0
    conn = get_db()
    conn.execute("VACUUM")
    conn.close()
    size_after = os.path.getsize(DB_PATH) if os.path.exists(DB_PATH) else 0
    saved = max(0, size_before - size_after)
    return jsonify({
        "status": "ok",
        "size_before_kb": size_before // 1024,
        "size_after_kb": size_after // 1024,
        "saved_kb": saved // 1024
    })


@app.route("/api/maintenance/clean_logs", methods=["POST"])
@role_required("admin")
def api_maintenance_clean_logs():
    log_dir = os.path.dirname(DB_PATH)
    cutoff = time.time() - 7 * 86400
    deleted = 0
    freed_kb = 0
    for fname in os.listdir(log_dir):
        if fname.startswith("flask_") and fname.endswith(".log"):
            fpath = os.path.join(log_dir, fname)
            if os.path.getmtime(fpath) < cutoff:
                freed_kb += os.path.getsize(fpath) // 1024
                os.remove(fpath)
                deleted += 1
    return jsonify({"status": "ok", "deleted": deleted, "freed_kb": freed_kb})


@app.route("/api/maintenance/logs", methods=["GET"])
@role_required("admin")
def api_maintenance_logs():
    lines_n = int(request.args.get("lines", 50))
    log_dir = os.path.dirname(DB_PATH)
    log_files = sorted(
        [f for f in os.listdir(log_dir) if f.startswith("flask_") and f.endswith(".log")],
        key=lambda f: os.path.getmtime(os.path.join(log_dir, f)),
        reverse=True
    )
    if not log_files:
        return jsonify({"lines": [], "source": None})
    log_path = os.path.join(log_dir, log_files[0])
    try:
        with open(log_path, "r", errors="replace") as f:
            all_lines = f.readlines()
        last = [l.rstrip() for l in all_lines[-lines_n:]]
        return jsonify({"lines": last, "source": log_files[0]})
    except Exception as e:
        return jsonify({"lines": [], "source": log_files[0], "error": str(e)})


# ── Maintenance View (PROTECTED — admin only) ─────────────────────────────────

@app.route("/maintenance")
@role_required("admin")
def maintenance_view():
    conn = get_db()
    c = conn.cursor()
    tables = {}
    for t in ["sync_jobs", "sync_work_items", "sync_technicians", "sync_memberships",
              "sync_audit_log", "sync_membership_check_ins", "sync_photos"]:
        tables[t] = c.execute(f"SELECT COUNT(*) FROM {t}").fetchone()[0]
    conn.close()

    db_size_kb = os.path.getsize(DB_PATH) // 1024 if os.path.exists(DB_PATH) else 0
    wal_path = DB_PATH + "-wal"
    wal_size_kb = os.path.getsize(wal_path) // 1024 if os.path.exists(wal_path) else 0
    db_mtime = datetime.fromtimestamp(os.path.getmtime(DB_PATH)).strftime("%d %b %Y %H:%M") if os.path.exists(DB_PATH) else "—"

    backup_dir = os.path.join(os.path.dirname(DB_PATH), "backups")
    backups = []
    if os.path.isdir(backup_dir):
        backups = sorted([f for f in os.listdir(backup_dir) if f.endswith(".db")], reverse=True)
    backup_total_kb = sum(
        os.path.getsize(os.path.join(backup_dir, f)) // 1024
        for f in backups if os.path.exists(os.path.join(backup_dir, f))
    )

    import sys, shutil
    disk = shutil.disk_usage(os.path.dirname(DB_PATH))
    uptime_s = int(time.time() - _SERVER_START)
    uptime_str = f"{uptime_s // 3600}h {(uptime_s % 3600) // 60}m {uptime_s % 60}s"

    log_dir = os.path.dirname(DB_PATH)
    log_lines = []
    log_source = None
    log_files = sorted(
        [f for f in os.listdir(log_dir) if f.startswith("flask_") and f.endswith(".log")],
        key=lambda f: os.path.getmtime(os.path.join(log_dir, f)),
        reverse=True
    )
    if log_files:
        log_source = log_files[0]
        try:
            with open(os.path.join(log_dir, log_files[0]), "r", errors="replace") as f:
                all_lines = f.readlines()
            log_lines = [l.rstrip() for l in all_lines[-50:]]
        except Exception:
            pass

    return render_template("maintenance.html",
        tables=tables, db_size_kb=db_size_kb, wal_size_kb=wal_size_kb, db_mtime=db_mtime,
        backup_count=len(backups), backup_total_kb=backup_total_kb,
        last_backup=backups[0] if backups else None,
        disk_free_gb=round(disk.free / (1024**3), 2), disk_used_gb=round(disk.used / (1024**3), 2),
        uptime_str=uptime_str, python_version=sys.version.split()[0],
        server_pid=os.getpid(), flask_version=VERSION,
        log_lines=log_lines, log_source=log_source,
        current_user=session.get("username", "admin"),
        current_role=session.get("role", "viewer"),
        current_name=session.get("name", "admin"),
        active="maintenance")


# ── Devices API (PROTECTED) ───────────────────────────────────────────────────

@app.route("/api/devices", methods=["GET"])
@role_required("admin", "dispatcher", "viewer")
def api_devices():
    conn = get_db()
    c = conn.cursor()
    rows = c.execute("SELECT * FROM devices ORDER BY lastSeenAt DESC").fetchall()
    cols = [d[0] for d in c.description]
    conn.close()
    return jsonify([dict(zip(cols, r)) for r in rows])


@app.route("/devices")
@role_required("admin")
def devices_view():
    conn = get_db()
    c = conn.cursor()
    rows = c.execute("SELECT * FROM devices ORDER BY lastSeenAt DESC").fetchall()
    cols = [d[0] for d in c.description]
    devices = [dict(zip(cols, r)) for r in rows]
    conn.close()
    now_ms = int(time.time() * 1000)
    return render_template("devices.html", devices=devices, now_ms=now_ms,
                           current_role=session.get("role"),
                           current_user=session.get("username", "admin"),
                           current_name=session.get("name", "admin"),
                           active="devices")


# ── Incremental Export (PROTECTED — admin only) ───────────────────────────────

@app.route("/api/export/incremental", methods=["GET"])
@role_required("admin")
def api_export_incremental():
    import io as _io
    import zipfile as _zf
    from datetime import datetime as _dt

    conn = get_db()
    c = conn.cursor()

    # Get last incremental export timestamp
    since = 0
    try:
        row = c.execute("""SELECT lastExportAt FROM export_cursors
                           WHERE name='incremental'""").fetchone()
        if row:
            since = row[0] if isinstance(row, tuple) else row["lastExportAt"]
    except Exception:
        since = 0

    now_ms = int(time.time() * 1000)

    # Query recent changes per table
    changes = {}
    tables_cols = {
        "sync_jobs": "updatedAt",
        "sync_work_items": "updatedAt",
        "sync_technicians": "updatedAt",
        "sync_memberships": "updatedAt",
        "sync_membership_check_ins": "checkInDate",
        "sync_audit_log": "timestamp",
    }
    for tbl, ts_col in tables_cols.items():
        try:
            rows = c.execute(f"SELECT * FROM {tbl} WHERE {ts_col} >= ? ORDER BY id",
                             (since,)).fetchall()
            cols = [d[0] for d in c.description]
            changes[tbl] = [dict(zip(cols, r)) for r in rows]
        except Exception:
            changes[tbl] = []

    # Update cursor
    try:
        c.execute("""CREATE TABLE IF NOT EXISTS export_cursors
                     (name TEXT PRIMARY KEY, lastExportAt INTEGER DEFAULT 0)""")
        c.execute("""INSERT OR REPLACE INTO export_cursors (name, lastExportAt)
                     VALUES ('incremental', ?)""", (now_ms,))
    except Exception:
        pass

    conn.commit()
    conn.close()

    # Build ZIP
    buffer = _io.BytesIO()
    ts = _dt.now().strftime("%Y-%m-%d_%H%M%S")

    with _zf.ZipFile(buffer, "w", _zf.ZIP_DEFLATED) as zf:
        for tbl, rows in changes.items():
            zf.writestr(f"{tbl}.json",
                json.dumps(rows, indent=2, ensure_ascii=False, default=str))

        meta = {
            "export_type": "incremental",
            "since_timestamp": since,
            "until_timestamp": now_ms,
            "generated_at": _dt.now().isoformat(),
            "counts": {t: len(r) for t, r in changes.items()},
            "total_records": sum(len(r) for r in changes.values())
        }
        zf.writestr("META.json", json.dumps(meta, indent=2, ensure_ascii=False))

    buffer.seek(0)
    filename = f"lc_incremental_{ts}.zip"
    return send_file(buffer, as_attachment=True, download_name=filename,
                     mimetype="application/zip")


@app.route("/api/export/incremental/reset", methods=["POST"])
@role_required("admin")
def api_export_incremental_reset():
    conn = get_db()
    c = conn.cursor()
    try:
        c.execute("""CREATE TABLE IF NOT EXISTS export_cursors
                     (name TEXT PRIMARY KEY, lastExportAt INTEGER DEFAULT 0)""")
        c.execute("""INSERT OR REPLACE INTO export_cursors (name, lastExportAt)
                     VALUES ('incremental', 0)""")
    except Exception:
        pass
    conn.commit()
    conn.close()
    return jsonify({"status": "ok", "message": "El próximo export será completo"})


# ── Sync Conflicts ──────────────────────────────────────────────────────────

@app.route("/api/conflicts", methods=["GET"])
@role_required("admin", "dispatcher", "viewer")
def api_conflicts():
    resolved = request.args.get("resolved", "0") == "1"
    conn = get_db()
    c = conn.cursor()
    if resolved:
        rows = c.execute("""SELECT * FROM sync_conflicts WHERE resolvedAt > 0
                            ORDER BY resolvedAt DESC LIMIT 100""").fetchall()
    else:
        rows = c.execute("""SELECT * FROM sync_conflicts WHERE resolvedAt = 0
                            ORDER BY detectedAt DESC LIMIT 100""").fetchall()
    cols = [d[0] for d in c.description]
    conn.close()
    return jsonify([dict(zip(cols, r)) for r in rows])


@app.route("/api/conflicts/<int:conf_id>/resolve", methods=["POST"])
@role_required("admin")
def api_conflict_resolve(conf_id):
    data = request.get_json() or {}
    resolution = data.get("resolution", "server")  # "client", "server", "ignore"
    note = data.get("note", "")

    conn = get_db()
    c = conn.cursor()
    conflict = c.execute("SELECT * FROM sync_conflicts WHERE id=?", (conf_id,)).fetchone()
    if not conflict:
        conn.close()
        return jsonify({"error": "conflict not found"}), 404

    cols = [d[0] for d in c.description]
    conf_dict = dict(zip(cols, conflict))

    now_ms = int(time.time() * 1000)
    actor = session.get("username", "admin")

    if resolution == "client":
        try:
            client_data = json.loads(conf_dict.get("clientValue", "{}"))
            if client_data and conf_dict.get("entityType") == "sync_jobs":
                keys = ",".join(f"{k}=?" for k in client_data.keys())
                vals = list(client_data.values()) + [conf_dict["entityId"]]
                c.execute(f"UPDATE sync_jobs SET {keys} WHERE id=?", vals)
        except Exception:
            pass

    c.execute("""UPDATE sync_conflicts SET resolvedAt=?, resolvedBy=?, resolution=?
                 WHERE id=?""", (now_ms, actor, f"{resolution}: {note}", conf_id))
    conn.commit()
    conn.close()

    return jsonify({"status": "ok", "resolution": resolution, "conf_id": conf_id})


@app.route("/conflicts")
@role_required("admin")
def conflicts_view():
    conn = get_db()
    c = conn.cursor()
    pending_count = c.execute(
        "SELECT COUNT(*) FROM sync_conflicts WHERE resolvedAt = 0").fetchone()[0]
    conn.close()
    return render_template("conflicts.html", pending_count=pending_count,
                           current_role=session.get("role"))


# ── PDF Reports ──────────────────────────────────────────────────────────────

@app.route("/api/reports/pdf", methods=["GET"])
@role_required("admin", "dispatcher", "viewer")
def api_reports_pdf():
    import io as _io
    from datetime import datetime as _dt
    try:
        from pdf_helpers import generate_report
    except ImportError:
        return jsonify({"error": "reportlab not installed. Run: pip install reportlab"}), 500

    rtype = request.args.get("type", "general")

    conn = get_db()
    c = conn.cursor()
    sections = []
    title = "LC Technician — Reporte"
    subtitle = f"Generado: {_dt.now().strftime('%Y-%m-%d %H:%M:%S')}"

    if rtype == "general":
        title = "Reporte General"
        job_count = c.execute("SELECT COUNT(*) FROM sync_jobs").fetchone()[0]
        tech_count = c.execute("SELECT COUNT(*) FROM sync_technicians").fetchone()[0]
        audit_count = c.execute("SELECT COUNT(*) FROM sync_audit_log").fetchone()[0]
        member_count = c.execute("SELECT COUNT(*) FROM sync_memberships").fetchone()[0]
        sections.append({"heading": "KPIs", "type": "kpi", "kpis": [
            {"label": "Jobs", "value": job_count},
            {"label": "Técnicos", "value": tech_count},
            {"label": "Membresías", "value": member_count},
            {"label": "Auditoría", "value": audit_count},
        ]})
        rows = c.execute("SELECT id, cliente, hora, estado, technicianId FROM sync_jobs ORDER BY id DESC LIMIT 20").fetchall()
        sections.append({"heading": "Jobs (últimos 20)", "type": "table",
            "headers": ["ID", "Cliente", "Hora", "Estado", "Técnico"],
            "rows": [[str(r[0] or ''), str(r[1] or ''), str(r[2] or ''), str(r[3] or ''), str(r[4] or '')] for r in rows]})

    elif rtype == "jobs":
        title = "Reporte de Jobs"
        rows = c.execute("SELECT id, cliente, hora, estado, technicianId, updatedAt FROM sync_jobs ORDER BY updatedAt DESC").fetchall()
        sections.append({"heading": f"Todos los Jobs ({len(rows)})", "type": "table",
            "headers": ["ID", "Cliente", "Hora", "Estado", "Técnico", "Actualizado"],
            "rows": [[str(r[0] or ''), str(r[1] or ''), str(r[2] or ''), str(r[3] or ''), str(r[4] or ''), str(r[5] or '')] for r in rows]})

    elif rtype == "memberships":
        title = "Reporte de Membresías"
        rows = c.execute("SELECT id, cardNumber, clienteName, status, expiryDate, plan FROM sync_memberships ORDER BY status, expiryDate").fetchall()
        sections.append({"heading": f"Membresías ({len(rows)})", "type": "table",
            "headers": ["ID", "Tarjeta", "Miembro", "Estado", "Vencimiento", "Plan"],
            "rows": [[str(r[i] or '') for i in range(6)] for r in rows]})
        ci_rows = c.execute("SELECT id, cardId, technicianName, checkInDate FROM sync_membership_check_ins ORDER BY checkInDate DESC LIMIT 50").fetchall()
        sections.append({"heading": "Check-ins recientes", "type": "table",
            "headers": ["ID", "Card ID", "Técnico", "Fecha"],
            "rows": [[str(r[i] or '') for i in range(4)] for r in ci_rows]})

    elif rtype == "audit":
        title = "Reporte de Auditoría"
        rows = c.execute("SELECT id, action, entityType, entityId, actor, timestamp FROM sync_audit_log ORDER BY timestamp DESC LIMIT 100").fetchall()
        sections.append({"heading": "Últimas 100 acciones de auditoría", "type": "table",
            "headers": ["ID", "Acción", "Tipo", "Entidad", "Actor", "Timestamp"],
            "rows": [[str(r[i] or '') for i in range(6)] for r in rows]})

    conn.close()

    buf = _io.BytesIO()
    generate_report(title, subtitle, sections, buf)
    buf.seek(0)
    fname = f"lc_report_{rtype}_{_dt.now().strftime('%Y%m%d_%H%M%S')}.pdf"
    return send_file(buf, as_attachment=True, download_name=fname, mimetype="application/pdf")


@app.route("/reports")
@role_required("admin", "dispatcher")
def reports_view():
    return render_template("reports.html", current_role=session.get("role"))


# ── Phase 52: Users CRUD ──────────────────────────────────────────────────────

def save_users(users_dict):
    """Save users dict back to users.json preserving order."""
    users_list = list(users_dict.values())
    with open(auth_config.USERS_FILE, "w") as f:
        json.dump({"users": users_list}, f, indent=2)


@app.route("/users")
@role_required("admin")
def users_view():
    users = auth_config.load_users()
    users_list = [{"username": u["username"], "name": u.get("name", u["username"]),
                   "role": u.get("role", "viewer")} for u in users.values()]
    return render_template("users.html", users=users_list, current_role=session.get("role"))


@app.route("/api/users", methods=["GET"])
@role_required("admin")
def api_users_list():
    users = auth_config.load_users()
    result = [{"username": u["username"], "name": u.get("name", u["username"]),
               "role": u.get("role", "viewer")} for u in users.values()]
    return jsonify(result)


@app.route("/api/users", methods=["POST"])
@role_required("admin")
def api_users_create():
    data = request.get_json(silent=True) or {}
    username = (data.get("username") or "").strip()
    password = data.get("password", "")
    role = data.get("role", "viewer")
    name = (data.get("name") or username).strip()
    if not username or not password:
        return jsonify({"error": "username and password required"}), 400
    if role not in ("admin", "dispatcher", "viewer"):
        return jsonify({"error": "role must be admin/dispatcher/viewer"}), 400
    users = auth_config.load_users()
    if username in users:
        return jsonify({"error": "username already exists"}), 409
    pw_hash = _pbkdf2_hash(password)
    users[username] = {"username": username, "password_hash": pw_hash, "role": role, "name": name}
    save_users(users)
    return jsonify({"status": "created", "username": username}), 201


@app.route("/api/users/<username>", methods=["PUT"])
@role_required("admin")
def api_users_update(username):
    data = request.get_json(silent=True) or {}
    users = auth_config.load_users()
    if username not in users:
        return jsonify({"error": "user not found"}), 404
    user = users[username]
    if "name" in data:
        user["name"] = (data["name"] or username).strip()
    if "role" in data:
        if data["role"] not in ("admin", "dispatcher", "viewer"):
            return jsonify({"error": "invalid role"}), 400
        user["role"] = data["role"]
    if data.get("password"):
        pw_hash = _pbkdf2_hash(data["password"])
        user["password_hash"] = pw_hash
    users[username] = user
    save_users(users)
    return jsonify({"status": "updated", "username": username})


@app.route("/api/users/<username>", methods=["DELETE"])
@role_required("admin")
def api_users_delete(username):
    if username == "admin":
        return jsonify({"error": "cannot delete admin"}), 403
    users = auth_config.load_users()
    if username not in users:
        return jsonify({"error": "user not found"}), 404
    del users[username]
    save_users(users)
    return jsonify({"status": "deleted", "username": username})


# ── Phase 52: Backup Scheduler ────────────────────────────────────────────────

BACKUP_DIR = os.path.join(os.path.dirname(__file__), "backups")
_sched_state = {"last_run": None, "next_run": None, "running": False}
_SCHED_INTERVAL = 86400  # 24 hours


def _run_auto_backup():
    """Create auto_*.db backup and clean up old ones (>7 days)."""
    os.makedirs(BACKUP_DIR, exist_ok=True)
    ts = datetime.now().strftime("%Y-%m-%d_%H%M%S")
    dest = os.path.join(BACKUP_DIR, f"auto_{ts}.db")
    try:
        shutil.copy2(DB_PATH, dest)
    except Exception:
        pass
    # Delete auto_* backups older than 7 days
    cutoff = time.time() - 7 * 86400
    for fname in os.listdir(BACKUP_DIR):
        if fname.startswith("auto_") and fname.endswith(".db"):
            fpath = os.path.join(BACKUP_DIR, fname)
            try:
                if os.path.getmtime(fpath) < cutoff:
                    os.remove(fpath)
            except Exception:
                pass
    _sched_state["last_run"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _backup_scheduler_loop():
    """Background thread: run backup every 24h."""
    # Initial delay: 5 minutes after startup
    time.sleep(300)
    while True:
        _sched_state["next_run"] = (datetime.now() + timedelta(seconds=_SCHED_INTERVAL)).strftime("%Y-%m-%d %H:%M:%S")
        _run_auto_backup()
        time.sleep(_SCHED_INTERVAL)


# Start background scheduler thread
_sched_thread = threading.Thread(target=_backup_scheduler_loop, daemon=True)
_sched_thread.start()


@app.route("/api/backup/scheduled/force", methods=["POST"])
@role_required("admin")
def api_backup_scheduled_force():
    _run_auto_backup()
    _sched_state["next_run"] = (datetime.now() + timedelta(seconds=_SCHED_INTERVAL)).strftime("%Y-%m-%d %H:%M:%S")
    return jsonify({"status": "ok", "last_run": _sched_state["last_run"]})


@app.route("/api/backup/scheduled/status", methods=["GET"])
@role_required("admin")
def api_backup_scheduled_status():
    os.makedirs(BACKUP_DIR, exist_ok=True)
    auto_files = []
    for fname in sorted(os.listdir(BACKUP_DIR), reverse=True):
        if fname.startswith("auto_") and fname.endswith(".db"):
            fpath = os.path.join(BACKUP_DIR, fname)
            try:
                sz = os.path.getsize(fpath)
                mtime = datetime.fromtimestamp(os.path.getmtime(fpath)).strftime("%Y-%m-%d %H:%M:%S")
            except Exception:
                sz = 0; mtime = "—"
            auto_files.append({"name": fname, "size_kb": round(sz / 1024, 1), "modified": mtime})
    return jsonify({
        "last_run": _sched_state.get("last_run"),
        "next_run": _sched_state.get("next_run"),
        "count": len(auto_files),
        "backups": auto_files
    })


# ── Phase 53: i18n ES/EN ──────────────────────────────────────────────────────

TRANSLATIONS = {
    "es": {
        "nav.dashboard": "Dashboard", "nav.analytics": "Analytics",
        "nav.jobs": "Jobs", "nav.technicians": "Técnicos",
        "nav.work_items": "Work Items", "nav.memberships": "Membresías",
        "nav.audit": "Auditoría", "nav.backup": "Backup",
        "nav.maintenance": "Mantenimiento", "nav.devices": "Dispositivos",
        "nav.conflicts": "Conflictos", "nav.reports": "Reportes",
        "nav.users": "Usuarios", "nav.logout": "SALIR",
        "dash.title": "Dashboard", "dash.total_jobs": "Total Jobs",
        "dash.revenue": "Revenue", "dash.technicians": "Técnicos",
        "dash.memberships": "Membresías",
        "btn.new": "NUEVO", "btn.edit": "Editar", "btn.delete": "Eliminar",
        "btn.save": "Guardar", "btn.cancel": "Cancelar", "btn.download": "Descargar",
        "jobs.title": "Jobs", "jobs.client": "Cliente", "jobs.status": "Estado",
        "jobs.technician": "Técnico", "jobs.date": "Fecha",
        "members.title": "Membresías", "members.card": "Tarjeta", "members.plan": "Plan",
        "audit.title": "Auditoría", "audit.action": "Acción", "audit.actor": "Actor",
        "lang.current": "ES"
    },
    "en": {
        "nav.dashboard": "Dashboard", "nav.analytics": "Analytics",
        "nav.jobs": "Jobs", "nav.technicians": "Technicians",
        "nav.work_items": "Work Items", "nav.memberships": "Memberships",
        "nav.audit": "Audit Log", "nav.backup": "Backup",
        "nav.maintenance": "Maintenance", "nav.devices": "Devices",
        "nav.conflicts": "Conflicts", "nav.reports": "Reports",
        "nav.users": "Users", "nav.logout": "LOGOUT",
        "dash.title": "Dashboard", "dash.total_jobs": "Total Jobs",
        "dash.revenue": "Revenue", "dash.technicians": "Technicians",
        "dash.memberships": "Memberships",
        "btn.new": "NEW", "btn.edit": "Edit", "btn.delete": "Delete",
        "btn.save": "Save", "btn.cancel": "Cancel", "btn.download": "Download",
        "jobs.title": "Jobs", "jobs.client": "Client", "jobs.status": "Status",
        "jobs.technician": "Technician", "jobs.date": "Date",
        "members.title": "Memberships", "members.card": "Card", "members.plan": "Plan",
        "audit.title": "Audit Log", "audit.action": "Action", "audit.actor": "Actor",
        "lang.current": "EN"
    }
}


def get_lang():
    return session.get("lang", "es")


def tr(key):
    lang = get_lang()
    return TRANSLATIONS.get(lang, TRANSLATIONS["es"]).get(key, key)


@app.context_processor
def inject_tr():
    return {"tr": tr, "current_lang": get_lang()}


@app.route("/set_lang/<lang>")
def set_lang(lang):
    if lang in ("es", "en"):
        session["lang"] = lang
    return redirect(request.referrer or "/dashboard")


# ── Phase 53: Excel Export ─────────────────────────────────────────────────────

@app.route("/api/export/jobs.xlsx")
@role_required("admin", "dispatcher", "viewer")
def export_jobs_xlsx():
    try:
        import openpyxl
        from openpyxl.styles import Font, PatternFill, Alignment
    except ImportError:
        return jsonify({"error": "pip install openpyxl"}), 500
    import io as _io
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Jobs"
    headers = ["ID", "Cliente", "Hora", "Estado", "Técnico", "Updated"]
    header_fill = PatternFill("solid", fgColor="1E3A5F")
    header_font = Font(bold=True, color="FFFFFF")
    for col, h in enumerate(headers, 1):
        cell = ws.cell(row=1, column=col, value=h)
        cell.fill = header_fill
        cell.font = header_font
    conn = get_db()
    rows = conn.execute(
        "SELECT id, cliente, hora, estado, technicianId, updatedAt FROM sync_jobs ORDER BY id"
    ).fetchall()
    conn.close()
    for row in rows:
        ws.append(list(row))
    for col in ws.columns:
        ws.column_dimensions[col[0].column_letter].width = max(
            len(str(c.value or '')) for c in col) + 4
    buf = _io.BytesIO()
    wb.save(buf)
    buf.seek(0)
    return send_file(buf, as_attachment=True, download_name="lc_jobs.xlsx",
                     mimetype="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")


@app.route("/api/export/work_items.xlsx")
@role_required("admin", "dispatcher", "viewer")
def export_work_items_xlsx():
    try:
        import openpyxl
        from openpyxl.styles import Font, PatternFill
    except ImportError:
        return jsonify({"error": "pip install openpyxl"}), 500
    import io as _io
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Work Items"
    headers = ["ID", "JobID", "Tipo", "Descripcion", "Cantidad", "Precio", "Subtotal"]
    header_fill = PatternFill("solid", fgColor="1E3A5F")
    header_font = Font(bold=True, color="FFFFFF")
    for col, h in enumerate(headers, 1):
        cell = ws.cell(row=1, column=col, value=h)
        cell.fill = header_fill
        cell.font = header_font
    conn = get_db()
    rows = conn.execute(
        "SELECT id, jobId, tipo, descripcion, cantidad, precioUnitario, subtotal "
        "FROM sync_work_items ORDER BY jobId, id"
    ).fetchall()
    conn.close()
    for row in rows:
        ws.append(list(row))
    for col in ws.columns:
        ws.column_dimensions[col[0].column_letter].width = max(
            len(str(c.value or '')) for c in col) + 4
    buf = _io.BytesIO()
    wb.save(buf)
    buf.seek(0)
    return send_file(buf, as_attachment=True, download_name="lc_work_items.xlsx",
                     mimetype="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")


# ── Phase 54: OpenAPI / Swagger ────────────────────────────────────────────────

try:
    from flasgger import Swagger

    swagger_template = {
        "info": {"title": "LC Technician API", "version": "1.2",
                 "description": "LC Technician sync and dashboard API"},
        "tags": [
            {"name": "Health"}, {"name": "Sync"}, {"name": "Jobs"},
            {"name": "Audit"}, {"name": "Users"}, {"name": "Backup"},
            {"name": "Analytics"}, {"name": "Reports"}, {"name": "Export"}
        ]
    }
    swagger_config = {
        "headers": [],
        "specs": [{"endpoint": "apispec_1", "route": "/apispec_1.json",
                   "rule_filter": lambda rule: True,
                   "model_filter": lambda tag: True}],
        "static_url_path": "/flasgger_static",
        "swagger_ui": True,
        "specs_route": "/docs"
    }
    swagger = Swagger(app, template=swagger_template, config=swagger_config)
    _SWAGGER_ENABLED = True
except ImportError:
    _SWAGGER_ENABLED = False


# ── Phase 55: Job PDF with embedded signatures ────────────────────────────────

@app.route("/jobs/<int:job_id>/pdf")
@role_required("admin", "dispatcher", "viewer")
def job_pdf(job_id):
    import io as _io
    try:
        from pdf_helpers import generate_job_report_pdf
    except (ImportError, AttributeError):
        return jsonify({"error": "reportlab not installed or generate_job_report_pdf missing"}), 500
    conn = get_db()
    c = conn.cursor()
    job = c.execute("SELECT * FROM sync_jobs WHERE id=?", (job_id,)).fetchone()
    if not job:
        conn.close()
        return jsonify({"error": "Job not found"}), 404
    job_dict = dict(job)
    wis = c.execute("SELECT * FROM sync_work_items WHERE jobId=?", (job_id,)).fetchall()
    wi_list = [dict(w) for w in wis]
    try:
        sigs = c.execute(
            "SELECT * FROM sync_photos WHERE jobId=? AND (type='SIGNATURE_CUSTOMER' OR type='SIGNATURE_TECHNICIAN')",
            (job_id,)
        ).fetchall()
        sig_list = [dict(s) for s in sigs]
    except Exception:
        sig_list = []
    conn.close()
    buf = _io.BytesIO()
    generate_job_report_pdf(job_dict, wi_list, sig_list, buf)
    buf.seek(0)
    return send_file(buf, as_attachment=True,
                     download_name=f"job_{job_id}_report.pdf",
                     mimetype="application/pdf")


# ── Phase 59: Predictive Analytics ──────────────────────────────────────────

@app.route("/api/analytics/predict", methods=["GET"])
@role_required("admin", "dispatcher", "viewer")
def api_analytics_predict():
    """
    Predictive analytics endpoint
    ---
    tags:
      - Analytics
    responses:
      200:
        description: Predictions based on historical trends
    """
    from datetime import datetime as _dt, timedelta as _td
    conn = get_db()
    c = conn.cursor()
    now_ms = int(time.time() * 1000)
    week_ms = 7 * 24 * 3600 * 1000

    # Last 4 weeks job counts for trend
    weekly_counts = []
    for i in range(4, 0, -1):
        start = now_ms - i * week_ms
        end = now_ms - (i - 1) * week_ms
        cnt = c.execute("SELECT COUNT(*) FROM sync_jobs WHERE updatedAt BETWEEN ? AND ?", (start, end)).fetchone()[0]
        weekly_counts.append(cnt)

    # Simple linear trend for next week
    avg_jobs = sum(weekly_counts) / max(len(weekly_counts), 1)
    if len(weekly_counts) >= 2:
        trend = (weekly_counts[-1] - weekly_counts[0]) / max(len(weekly_counts) - 1, 1)
    else:
        trend = 0
    next_week_jobs = max(0, round(avg_jobs + trend))
    confidence_jobs = min(95, max(50, 80 - abs(trend) * 5))

    # Revenue trend
    weekly_revenue = []
    for i in range(4, 0, -1):
        start = now_ms - i * week_ms
        end = now_ms - (i - 1) * week_ms
        rev = c.execute(
            "SELECT COALESCE(SUM(wi.subtotal),0) FROM sync_work_items wi "
            "JOIN sync_jobs j ON wi.jobId=j.id WHERE j.updatedAt BETWEEN ? AND ?",
            (start, end)
        ).fetchone()[0]
        weekly_revenue.append(float(rev or 0))

    avg_rev = sum(weekly_revenue) / max(len(weekly_revenue), 1)
    rev_trend = (weekly_revenue[-1] - weekly_revenue[0]) / max(len(weekly_revenue) - 1, 1) if len(weekly_revenue) >= 2 else 0
    next_week_rev = max(0, round(avg_rev + rev_trend, 2))
    confidence_rev = min(90, max(45, 75 - abs(rev_trend / max(avg_rev, 1)) * 100))

    # Trending services
    curr_services = {}
    for row in c.execute("SELECT tipo, COUNT(*) FROM sync_work_items WHERE tipo IS NOT NULL GROUP BY tipo"):
        curr_services[row[0]] = row[1]

    trending = []
    for svc, cnt in sorted(curr_services.items(), key=lambda x: -x[1])[:5]:
        trending.append({"service": svc, "count": cnt, "delta_pct": 0.0})

    # High risk memberships (expiring in 15 days)
    now_sec = int(time.time())
    risky = []
    try:
        for row in c.execute(
            "SELECT cardNumber, clienteName, expiryDate, status FROM sync_memberships "
            "WHERE status='ACTIVE' AND expiryDate > 0"
        ):
            exp_sec = row[2] / 1000 if row[2] > 1e10 else row[2]
            days_left = int((exp_sec - now_sec) / 86400)
            if days_left <= 15:
                risky.append({"card": row[0], "name": row[1], "days_left": days_left, "status": row[3]})
    except Exception:
        pass

    conn.close()
    return jsonify({
        "next_week_jobs_est": next_week_jobs,
        "confidence_jobs": round(confidence_jobs),
        "next_week_revenue_est": next_week_rev,
        "confidence_revenue": round(confidence_rev),
        "trending_services": trending[:3],
        "high_risk_memberships": sorted(risky, key=lambda x: x["days_left"])[:5],
        "based_on_weeks": len(weekly_counts),
        "generated_at": _dt.now().isoformat()
    })


# ── Phase 60: Setup Wizard ────────────────────────────────────────────────────

SETUP_CONFIG_PATH = os.path.join(os.path.dirname(__file__), "setup_config.json")


def _load_setup_config():
    if os.path.exists(SETUP_CONFIG_PATH):
        try:
            with open(SETUP_CONFIG_PATH) as f:
                return json.load(f)
        except Exception:
            pass
    return {}


def _save_setup_config(cfg):
    with open(SETUP_CONFIG_PATH, 'w') as f:
        json.dump(cfg, f, indent=2)


@app.route("/setup", methods=["GET", "POST"])
def setup_wizard():
    cfg = _load_setup_config()
    # If setup already complete, redirect to login (only on plain GET)
    if cfg.get("setup_complete") and request.method == "GET" and "step" not in request.args:
        return redirect(url_for("login"))

    step = int(request.args.get("step", cfg.get("current_step", 1)))

    if request.method == "POST":
        step = int(request.form.get("step", 1))

        if step == 1:
            cfg["admin_user"] = request.form.get("admin_user", "admin")
            new_pass = request.form.get("admin_pass", "")
            if new_pass and len(new_pass) >= 8:
                cfg["admin_pass_set"] = True
            cfg["current_step"] = 2

        elif step == 2:
            cfg["company_name"] = request.form.get("company_name", "LC Technician")
            cfg["support_phone"] = request.form.get("support_phone", "")
            cfg["current_step"] = 3

        elif step == 3:
            cfg["sync_url"] = request.form.get("sync_url", "http://127.0.0.1:5000")
            cfg["current_step"] = 4

        elif step == 4:
            # Create initial backup
            import shutil as _shutil
            backup_dir = os.path.join(os.path.dirname(DB_PATH), "backups")
            os.makedirs(backup_dir, exist_ok=True)
            from datetime import datetime as _dt2
            backup_name = f"setup_initial_{_dt2.now().strftime('%Y%m%d_%H%M%S')}.db"
            _shutil.copy2(DB_PATH, os.path.join(backup_dir, backup_name))
            cfg["initial_backup"] = backup_name
            cfg["current_step"] = 5
            cfg["setup_complete"] = True

        _save_setup_config(cfg)
        step = cfg.get("current_step", 1)

    return render_template("setup.html", step=step, config=cfg,
                           current_role=session.get("role", ""),
                           current_user=session.get("username", ""))


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=False)
