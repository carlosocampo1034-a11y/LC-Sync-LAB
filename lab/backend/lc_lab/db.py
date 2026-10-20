"""Base de datos del laboratorio L&C (SQLite nueva, independiente del backend histórico)."""
import sqlite3
import time

from flask import current_app, g

SCHEMA = """
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('ADMIN','DISPATCHER','TECHNICIAN','CUSTOMER')),
    display_name TEXT NOT NULL,
    customer_id INTEGER REFERENCES customers(id),
    technician_code TEXT,               -- technicianId que usa la app Android del técnico
    active INTEGER NOT NULL DEFAULT 1,
    failed_logins INTEGER NOT NULL DEFAULT 0,
    locked_until INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS tokens (
    token_hash TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id),
    device_id TEXT,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS customers (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    nombre TEXT NOT NULL,
    telefono TEXT,
    email TEXT,
    origin_device TEXT,
    origin_local_id INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE (origin_device, origin_local_id)
);

CREATE TABLE IF NOT EXISTS properties (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    customer_id INTEGER NOT NULL REFERENCES customers(id),
    direccion TEXT NOT NULL,
    tipo_propiedad TEXT,
    lat REAL, lng REAL, ubicacion_fuente TEXT,
    cliente_presente INTEGER,
    tipo_acceso TEXT,
    codigo_acceso_enc TEXT,              -- cifrado (Fernet)
    ubicacion_llave_enc TEXT,            -- cifrado (Fernet)
    instrucciones TEXT,
    mascota TEXT,
    contacto_respaldo TEXT,
    origin_device TEXT,
    origin_local_id INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE (origin_device, origin_local_id)
);

CREATE TABLE IF NOT EXISTS equipment (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    property_id INTEGER NOT NULL REFERENCES properties(id),
    nombre TEXT NOT NULL,
    tipo TEXT, marca TEXT, modelo TEXT, serial TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

-- Orden de servicio. id: el de la app del técnico si nació en el teléfono; si nació en el servidor
-- (cliente o despacho), un id del rango 8e12+ para no chocar con los ids locales del teléfono.
CREATE TABLE IF NOT EXISTS jobs (
    id INTEGER PRIMARY KEY,
    version INTEGER NOT NULL DEFAULT 1,
    seq INTEGER NOT NULL DEFAULT 0,       -- cursor global de cambios para /sync/pull
    origin TEXT NOT NULL CHECK (origin IN ('DEVICE','CUSTOMER','DISPATCH')),
    origin_device TEXT,
    estado TEXT NOT NULL,
    estado_dispatch TEXT,
    prioridad TEXT,
    categoria TEXT,
    customer_id INTEGER REFERENCES customers(id),
    property_id INTEGER REFERENCES properties(id),
    equipment_id INTEGER REFERENCES equipment(id),
    technician_code TEXT,
    cliente TEXT, telefono TEXT, direccion TEXT,
    lat REAL, lng REAL, ubicacion_fuente TEXT,
    hora TEXT, programado_para INTEGER, zona_horaria TEXT, ventana_preferida TEXT,
    descripcion TEXT, equipo TEXT,
    tipo_propiedad TEXT, cliente_presente INTEGER,
    tipo_acceso TEXT, codigo_acceso_enc TEXT, ubicacion_llave_enc TEXT,
    instrucciones TEXT, mascota TEXT, contacto_respaldo TEXT, notas_seguridad TEXT,
    notas TEXT,
    coverage_type TEXT, coverage_auth TEXT,
    client_updated_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_jobs_seq ON jobs(seq);
CREATE INDEX IF NOT EXISTS idx_jobs_tech ON jobs(technician_code);

-- Historial por campo, con la versión resultante (sirve para el merge de 3 vías en sync)
CREATE TABLE IF NOT EXISTS job_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL REFERENCES jobs(id),
    version INTEGER NOT NULL,
    field TEXT NOT NULL,
    old_value TEXT,
    new_value TEXT,
    actor TEXT NOT NULL,
    source TEXT NOT NULL,
    ts INTEGER NOT NULL
);

-- Idempotencia: el mismo push (dispositivo, trabajo, updatedAt del cliente) devuelve el mismo resultado
CREATE TABLE IF NOT EXISTS sync_receipts (
    device_id TEXT NOT NULL,
    job_id INTEGER NOT NULL,
    client_updated_at INTEGER NOT NULL,
    status TEXT NOT NULL,
    version INTEGER,
    detail TEXT,
    ts INTEGER NOT NULL,
    PRIMARY KEY (device_id, job_id, client_updated_at)
);

CREATE TABLE IF NOT EXISTS job_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL REFERENCES jobs(id),
    tipo TEXT NOT NULL CHECK (tipo IN ('NOTA','MEDICION','DIAGNOSTICO','SISTEMA')),
    payload TEXT NOT NULL,
    actor TEXT NOT NULL,
    client_event_id TEXT,
    ts INTEGER NOT NULL,
    UNIQUE (job_id, client_event_id)
);

CREATE TABLE IF NOT EXISTS photos (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL REFERENCES jobs(id),
    uploaded_by TEXT NOT NULL,
    origin_device TEXT,
    origin_local_id INTEGER,
    categoria TEXT,
    filename TEXT NOT NULL,
    mime TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    sha256 TEXT NOT NULL,
    path TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE (origin_device, origin_local_id)
);

CREATE TABLE IF NOT EXISTS approvals (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL REFERENCES jobs(id),
    tipo TEXT NOT NULL CHECK (tipo IN ('PROPUESTA','AUTORIZACION','CIERRE','FACTURA')),
    descripcion TEXT,
    monto REAL,
    estado TEXT NOT NULL CHECK (estado IN ('PENDIENTE','APROBADA','RECHAZADA')),
    solicitada_por TEXT NOT NULL,
    firmante TEXT, rol_firmante TEXT, firma_sha256 TEXT,
    created_at INTEGER NOT NULL,
    decided_at INTEGER
);

CREATE TABLE IF NOT EXISTS parts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sku TEXT NOT NULL UNIQUE,
    nombre TEXT NOT NULL,
    precio REAL NOT NULL,
    stock INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS job_parts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL REFERENCES jobs(id),
    part_id INTEGER NOT NULL REFERENCES parts(id),
    cantidad INTEGER NOT NULL CHECK (cantidad > 0),
    estado TEXT NOT NULL CHECK (estado IN ('SOLICITADO','APROBADO','PEDIDO','RECIBIDO','USADO','CANCELADO')),
    precio_snapshot REAL NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS invoices (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL UNIQUE REFERENCES jobs(id),
    items TEXT NOT NULL,
    subtotal REAL NOT NULL, impuesto REAL NOT NULL, total REAL NOT NULL,
    estado TEXT NOT NULL CHECK (estado IN ('BORRADOR','EMITIDA','PAGADA','ANULADA')),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS technician_availability (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    technician_code TEXT NOT NULL,
    dia TEXT NOT NULL,              -- YYYY-MM-DD
    desde TEXT NOT NULL, hasta TEXT NOT NULL,
    UNIQUE (technician_code, dia, desde)
);

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    entity TEXT NOT NULL,
    entity_id TEXT,
    detail TEXT,
    device TEXT,
    ts INTEGER NOT NULL
);

-- Partidas de trabajo del técnico (MASTER_FLOW §18). Idempotente por (dispositivo, id local).
-- Un borrado en el teléfono no elimina la fila: la marca como anulada (queda auditoría).
CREATE TABLE IF NOT EXISTS job_work_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL REFERENCES jobs(id),
    origin_device TEXT NOT NULL,
    local_id INTEGER NOT NULL,
    tipo TEXT NOT NULL CHECK (tipo IN ('LABOR','PART','SERVICE','FEE','OTHER','DISCOUNT')),
    descripcion TEXT,
    cantidad REAL NOT NULL,
    precio_unitario REAL NOT NULL,
    total REAL NOT NULL,
    client_updated_at INTEGER NOT NULL,
    anulado INTEGER NOT NULL DEFAULT 0,
    actor TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE (origin_device, local_id)
);

-- Conflictos de sincronización: ambos valores se conservan hasta que despacho decide (nunca se borran).
-- Valores de campos secretos (código/llave) se guardan cifrados, como en jobs.
CREATE TABLE IF NOT EXISTS job_conflicts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL REFERENCES jobs(id),
    field TEXT NOT NULL,
    base_version INTEGER NOT NULL,
    server_version INTEGER NOT NULL,
    server_value TEXT,
    client_value TEXT,
    client_value_hash TEXT NOT NULL,
    device_id TEXT,
    actor TEXT NOT NULL,
    estado TEXT NOT NULL CHECK (estado IN ('PENDIENTE','RESUELTO')),
    resolucion TEXT CHECK (resolucion IN ('SERVIDOR','TECNICO','COMBINADO')),
    valor_final TEXT,
    resuelto_por TEXT,
    created_at INTEGER NOT NULL,
    resolved_at INTEGER
);
-- reintentos del mismo cambio no crean conflictos duplicados
CREATE UNIQUE INDEX IF NOT EXISTS uq_conflicto_pendiente ON job_conflicts(job_id, field, device_id, client_value_hash)
    WHERE estado = 'PENDIENTE';

-- Dispositivos: número único por teléfono → espacio de ids propio (1e13 + n·1e8), sin colisiones entre técnicos
CREATE TABLE IF NOT EXISTS device_registry (
    device_number INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL UNIQUE,
    technician_code TEXT,
    first_seen INTEGER NOT NULL
);

-- Propuestas del técnico (Good/Better/Best) con revisiones: el cliente decide sobre un contenido concreto (sha256);
-- una revisión nueva deja la anterior SUPERADA y una aprobación vieja no autoriza el contenido nuevo.
CREATE TABLE IF NOT EXISTS proposals (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL REFERENCES jobs(id),
    origin_device TEXT NOT NULL,
    local_id INTEGER NOT NULL,
    revision INTEGER NOT NULL,
    opciones TEXT NOT NULL,
    contenido_sha256 TEXT NOT NULL,
    estado TEXT NOT NULL CHECK (estado IN ('PENDIENTE','APROBADA','RECHAZADA','VENCIDA','SUPERADA')),
    opcion_elegida TEXT, total_aceptado REAL,
    firmante TEXT, rol_firmante TEXT, firma_sha256 TEXT,
    solicitada_por TEXT NOT NULL,
    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, decided_at INTEGER,
    UNIQUE (origin_device, local_id, revision)
);

-- Firmas capturadas en el teléfono del técnico (conformidad del cliente, técnico, aceptación presencial de propuesta)
CREATE TABLE IF NOT EXISTS job_signatures (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL REFERENCES jobs(id),
    origin_device TEXT NOT NULL, local_id INTEGER NOT NULL,
    tipo TEXT NOT NULL CHECK (tipo IN ('CLIENTE_CONFORMIDAD','TECNICO','PROPUESTA_PRESENCIAL')),
    firmante TEXT NOT NULL, opcion TEXT, total REAL, proposal_id INTEGER,
    png_sha256 TEXT NOT NULL, path TEXT NOT NULL,
    capturada_por TEXT NOT NULL, firmada_at INTEGER NOT NULL, created_at INTEGER NOT NULL,
    UNIQUE (origin_device, local_id)
);

-- Repuestos del inventario del técnico usados en la orden (anulación lógica, orden temporal como work items)
CREATE TABLE IF NOT EXISTS job_parts_used (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL REFERENCES jobs(id),
    origin_device TEXT NOT NULL, local_id INTEGER NOT NULL,
    sku TEXT, nombre TEXT NOT NULL, cantidad REAL NOT NULL CHECK (cantidad > 0), precio_unitario REAL NOT NULL,
    anulado INTEGER NOT NULL DEFAULT 0, client_updated_at INTEGER NOT NULL,
    actor TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
    UNIQUE (origin_device, local_id)
);

-- Pagos REPORTADOS por el técnico; solo despacho los confirma (sin confirmación no hay factura PAGADA)
CREATE TABLE IF NOT EXISTS payments (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL REFERENCES jobs(id),
    invoice_id INTEGER NOT NULL REFERENCES invoices(id),
    origin_device TEXT NOT NULL, local_id INTEGER NOT NULL,
    monto REAL NOT NULL CHECK (monto > 0),
    metodo TEXT NOT NULL CHECK (metodo IN ('EFECTIVO','CHEQUE','TARJETA','ZELLE','TRANSFERENCIA','OTRO')),
    referencia TEXT,
    estado TEXT NOT NULL CHECK (estado IN ('REPORTADO','CONFIRMADO','RECHAZADO')),
    reportado_por TEXT NOT NULL, reportado_at INTEGER NOT NULL,
    revisado_por TEXT, revisado_at INTEGER, motivo TEXT,
    UNIQUE (origin_device, local_id)
);

-- PDF de la factura generado en el teléfono (cada versión se conserva; se sirve la última)
CREATE TABLE IF NOT EXISTS invoice_documents (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    invoice_id INTEGER NOT NULL REFERENCES invoices(id),
    sha256 TEXT NOT NULL, path TEXT NOT NULL, size_bytes INTEGER NOT NULL,
    uploaded_by TEXT NOT NULL, created_at INTEGER NOT NULL,
    UNIQUE (invoice_id, sha256)
);

-- Descuentos autorizados por despacho antes de emitir la factura
CREATE TABLE IF NOT EXISTS job_discounts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL REFERENCES jobs(id),
    monto REAL NOT NULL CHECK (monto > 0), motivo TEXT NOT NULL,
    autorizado_por TEXT NOT NULL, created_at INTEGER NOT NULL, anulado INTEGER NOT NULL DEFAULT 0
);

-- Diferencia entre la factura y la propuesta aprobada: aprobación adicional del cliente ligada a las líneas exactas
CREATE TABLE IF NOT EXISTS invoice_variances (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    job_id INTEGER NOT NULL REFERENCES jobs(id),
    proposal_id INTEGER NOT NULL REFERENCES proposals(id),
    tipo TEXT NOT NULL CHECK (tipo IN ('MAYOR','ALCANCE')),
    aprobado_total REAL NOT NULL, factura_total REAL NOT NULL, diferencia REAL NOT NULL,
    items TEXT NOT NULL, items_sha256 TEXT NOT NULL,
    estado TEXT NOT NULL CHECK (estado IN ('PENDIENTE','APROBADA','RECHAZADA','SUPERADA')),
    solicitada_por TEXT NOT NULL, firmante TEXT, firma_sha256 TEXT,
    created_at INTEGER NOT NULL, decided_at INTEGER
);

CREATE TABLE IF NOT EXISTS counters (name TEXT PRIMARY KEY, value INTEGER NOT NULL);
INSERT OR IGNORE INTO counters VALUES ('job_seq', 0);
INSERT OR IGNORE INTO counters VALUES ('server_job_id', 8000000000000);
"""


def now_ms():
    return int(time.time() * 1000)


def get_db():
    if "db" not in g:
        from flask import has_request_context, request
        # isolation_level=None: control explícito de transacciones
        g.db = sqlite3.connect(current_app.config["DATABASE"], timeout=15, isolation_level=None)
        g.db.row_factory = sqlite3.Row
        g.db.execute("PRAGMA foreign_keys = ON")
        g.db.execute("PRAGMA busy_timeout = 15000")
        if has_request_context() and request.method in ("POST", "PUT", "PATCH", "DELETE"):
            # Las peticiones que escriben toman el bloqueo de escritura al empezar: con varias a la vez
            # esperan su turno (timeout) en lugar de fallar con "database is locked" al pasar de leer a escribir.
            g.db.execute("BEGIN IMMEDIATE")
    return g.db


def close_db(_exc=None):
    db = g.pop("db", None)
    if db is not None:
        if db.in_transaction:
            db.rollback()   # lo no confirmado explícitamente (errores) no se guarda
        db.close()


def init_db(path):
    c = sqlite3.connect(path)
    c.execute("PRAGMA journal_mode = WAL")   # persistente en el archivo: una sola vez, no por conexión
    c.executescript(SCHEMA)
    # columnas añadidas después (bases existentes): solo ADD COLUMN, nunca se borra nada
    cols = {r[1] for r in c.execute("PRAGMA table_info(invoices)")}
    for col, ddl in (("numero", "TEXT"), ("descuento", "REAL NOT NULL DEFAULT 0"), ("emitida_por", "TEXT"),
                     ("emitida_at", "INTEGER"), ("origin_device", "TEXT"), ("client_invoice_id", "TEXT"),
                     ("items_sha256", "TEXT"), ("aprobado_total", "REAL"), ("diferencia", "REAL"),
                     ("variacion_id", "INTEGER"), ("confirmacion_menor_por", "TEXT"), ("confirmacion_menor_at", "INTEGER")):
        if col not in cols:
            c.execute(f"ALTER TABLE invoices ADD COLUMN {col} {ddl}")
    c.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_invoice_numero ON invoices(numero) WHERE numero IS NOT NULL")
    # alta y gestión de técnicos desde L&C Control (solo ADD COLUMN; created_at ya existía)
    cols = {r[1] for r in c.execute("PRAGMA table_info(users)")}
    for col, ddl in (("must_change_password", "INTEGER NOT NULL DEFAULT 0"), ("specialties", "TEXT NOT NULL DEFAULT ''"),
                     ("updated_at", "INTEGER NOT NULL DEFAULT 0")):
        if col not in cols:
            c.execute(f"ALTER TABLE users ADD COLUMN {col} {ddl}")
    # un código de técnico identifica a una sola persona (la app y la sincronización se basan en él)
    c.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_users_technician_code ON users(technician_code) "
              "WHERE technician_code IS NOT NULL")
    c.commit()
    c.close()


def next_counter(db, name):
    db.execute("UPDATE counters SET value = value + 1 WHERE name = ?", (name,))
    return db.execute("SELECT value FROM counters WHERE name = ?", (name,)).fetchone()[0]


def audit(db, actor, action, entity, entity_id=None, detail=None, device=None):
    db.execute("INSERT INTO audit_log(actor, action, entity, entity_id, detail, device, ts) "
               "VALUES (?,?,?,?,?,?,?)", (actor, action, entity, str(entity_id) if entity_id is not None
                                          else None, detail, device, now_ms()))
