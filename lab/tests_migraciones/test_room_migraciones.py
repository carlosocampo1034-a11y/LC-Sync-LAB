"""
Pruebas de la cadena de migraciones Room 39 → 40 → 41 → 42 de L&C Technician.

- El SQL se extrae de app/.../LcDatabase.java (el código real, no una copia a mano).
- La base v39 se construye con el esquema exportado por Room (41.json): 39, 40 y 41 tienen el mismo
  esquema porque 39→40 y 40→41 nunca tuvieron DDL (se comprueba en test_39_40_41_sin_ddl).
- El resultado se valida contra 42.json como hace Room (tablas, columnas, tipos, NOT NULL, PK).
- Solo bases ficticias en un directorio temporal. Nunca toca un dispositivo ni la app original.

Ejecutar:  LC_ROOT=~/LC python3 -m pytest -q lab/tests_migraciones
Comparar con el código anterior:  LC_DATABASE_JAVA=<LcDatabase.java antiguo> python3 -m pytest ...
"""
import json
import os
import re
import sqlite3

import pytest

LC_ROOT = os.environ.get("LC_ROOT", os.path.expanduser("~/LC"))
SCHEMAS = os.path.join(LC_ROOT, "app/schemas/com.lc.technician.data.LcDatabase")
JAVA = os.environ.get("LC_DATABASE_JAVA",
                      os.path.join(LC_ROOT, "app/src/main/java/com/lc/technician/data/LcDatabase.java"))

# Nombres que un negocio real puede tener y que la migración antigua borraba, más restos "E2E"
ULTIMA = 47  # última versión de Room (esquema exportado app/schemas/.../47.json)

NOMBRES = ["Ana Ejemplo", "Restaurante La Prueba", "Familia Pruebas", "prueba minúscula",
           "MiamiCliente Holdings", "ClientePrueba SA", "Cliente E2E", "E2E Prueba ilegible",
           "Juan Pérez", None]


# ── utilidades ──────────────────────────────────────────────────────────────

def _schema(version):
    with open(os.path.join(SCHEMAS, f"{version}.json")) as f:
        return json.load(f)["database"]


def _migraciones():
    """{(desde, hasta): [sql, ...]} extraído de LcDatabase.java."""
    src = open(JAVA, encoding="utf-8").read()
    out = {}
    for m in re.finditer(r"MIGRATION_(\d+)_(\d+)\s*=\s*new Migration\(\d+,\s*\d+\)\s*\{(.*?)\n\s{4,8}\};",
                         src, re.S):
        cuerpo = m.group(3)
        sqls = []
        # execSQL("..." + "...") y bucles "ALTER TABLE " + t + "..." (41→42)
        for e in re.finditer(r'execSQL\(\s*((?:"[^"]*"\s*\+?\s*(?:\w+\s*\+\s*)?)+)\)', cuerpo):
            partes = re.findall(r'"([^"]*)"|\+\s*(\w+)\s*\+', e.group(1))
            sqls.append(partes)
        tablas_bucle = re.findall(r'for \(String t : new String\[\]\{([^}]*)\}\)', cuerpo)
        expandidas = []
        for partes in sqls:
            if any(var for _, var in partes):
                nombres = [x.strip().strip('"') for x in tablas_bucle[0].split(",")] if tablas_bucle else []
                for t in nombres:
                    expandidas.append("".join(lit if lit else t for lit, var in partes))
            else:
                expandidas.append("".join(lit for lit, _ in partes))
        out[(int(m.group(1)), int(m.group(2)))] = expandidas
    return out


def _valor(campo, i):
    a = campo["affinity"]
    if a == "INTEGER":
        return i
    if a == "REAL":
        return float(i)
    if a == "BLOB":
        return b"\x00"
    return f"{campo['columnName']}_{i}"


def _insertar(c, schema, tabla, i, **valores):
    ent = next(e for e in schema["entities"] if e["tableName"] == tabla)
    fila = {}
    for f in ent["fields"]:
        col = f["columnName"]
        if col in valores:
            fila[col] = valores[col]
        elif f.get("notNull"):
            fila[col] = _valor(f, i)
        else:
            fila[col] = None  # dato histórico incompleto
    cols = ",".join(f"`{k}`" for k in fila)
    c.execute(f"INSERT INTO `{tabla}` ({cols}) VALUES ({','.join('?' * len(fila))})", list(fila.values()))


def _base_v39(path):
    s41 = _schema(41)
    c = sqlite3.connect(path)
    for e in s41["entities"]:
        c.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for idx in e.get("indices", []):
            c.execute(idx["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    for q in s41.get("setupQueries", []):
        c.execute(q)
    c.execute("PRAGMA user_version = 39")
    for i, nombre in enumerate(NOMBRES, start=1):
        _insertar(c, s41, "customers", i, id=i, nombre=nombre)
        _insertar(c, s41, "properties", i, id=i, customerId=i, direccion=f"{i} Calle Ficticia")
        _insertar(c, s41, "equipment", i, id=i, propertyId=i)
        _insertar(c, s41, "jobs", i, id=1000 + i, cliente=nombre, customerId=i, propertyId=i,
                  equipmentId=i, estado="PENDIENTE", synced=0)
        _insertar(c, s41, "photos", i, id=i, jobId=1000 + i)
        _insertar(c, s41, "signatures", i, id=i, jobId=1000 + i, proposalId=i)
        _insertar(c, s41, "audit_log", i, id=i, entityType="JOB", entityId=1000 + i, jobId=1000 + i,
                  newValue=nombre)
        _insertar(c, s41, "job_events", i, id=i, jobId=1000 + i)
    # trabajo con casi todo nulo (registro histórico incompleto)
    _insertar(c, s41, "jobs", 99, id=1099, cliente=None, estado=None, synced=0)
    c.commit()
    return c


def _conteos(c):
    return {t: c.execute(f"SELECT COUNT(*) FROM `{t}`").fetchone()[0]
            for t in ("jobs", "customers", "properties", "equipment", "photos", "signatures",
                      "audit_log", "job_events")}


def _migrar(c, desde=39, hasta=ULTIMA, fallar_en=None):
    """Como Room: cada migración en una transacción; si falla, rollback y la versión no avanza."""
    migs = _migraciones()
    c.isolation_level = None
    v = desde
    while v < hasta:
        sqls = migs[(v, v + 1)]
        c.execute("BEGIN")
        try:
            for n, q in enumerate(sqls):
                if fallar_en == (v, n):
                    raise RuntimeError(f"interrupción simulada en {v}->{v+1} tras {n} sentencias")
                c.execute(q)
            if fallar_en == (v, len(sqls)):
                raise RuntimeError(f"interrupción simulada en {v}->{v+1} antes del COMMIT")
            c.execute(f"PRAGMA user_version = {v + 1}")
            c.execute("COMMIT")
        except Exception:
            c.execute("ROLLBACK")
            raise
        v += 1


def _validar_contra_esquema(c, version):
    """Equivalente a la validación de Room: cada tabla y columna esperada existe con el mismo tipo/NOT NULL/PK."""
    s = _schema(version)
    errores = []
    for e in s["entities"]:
        real = {r[1]: r for r in c.execute(f"PRAGMA table_info(`{e['tableName']}`)")}
        if not real:
            errores.append(f"falta tabla {e['tableName']}")
            continue
        pk = set(e["primaryKey"]["columnNames"])
        for f in e["fields"]:
            r = real.get(f["columnName"])
            if r is None:
                errores.append(f"{e['tableName']}.{f['columnName']} falta")
                continue
            _, _, tipo, notnull, _, espk = r
            if tipo.upper() != f["affinity"]:
                errores.append(f"{e['tableName']}.{f['columnName']} tipo {tipo} != {f['affinity']}")
            if bool(notnull) != bool(f.get("notNull")):
                errores.append(f"{e['tableName']}.{f['columnName']} notNull distinto")
            if (espk > 0) != (f["columnName"] in pk):
                errores.append(f"{e['tableName']}.{f['columnName']} PK distinta")
        for extra in set(real) - {f["columnName"] for f in e["fields"]}:
            errores.append(f"{e['tableName']}.{extra} sobra")
    return errores


def _huerfanos(c):
    q = {
        "jobs→customers": "SELECT COUNT(*) FROM jobs WHERE customerId IS NOT NULL AND customerId NOT IN (SELECT id FROM customers)",
        "properties→customers": "SELECT COUNT(*) FROM properties WHERE customerId NOT IN (SELECT id FROM customers)",
        "photos→jobs": "SELECT COUNT(*) FROM photos WHERE jobId NOT IN (SELECT id FROM jobs)",
        "signatures→jobs": "SELECT COUNT(*) FROM signatures WHERE jobId NOT IN (SELECT id FROM jobs)",
        "audit_log→jobs": "SELECT COUNT(*) FROM audit_log WHERE entityType='JOB' AND jobId NOT IN (SELECT id FROM jobs)",
        "customers sin jobs": "SELECT COUNT(*) FROM customers WHERE id NOT IN (SELECT customerId FROM jobs WHERE customerId IS NOT NULL)",
    }
    return {k: c.execute(v).fetchone()[0] for k, v in q.items()}


@pytest.fixture
def db(tmp_path):
    c = _base_v39(str(tmp_path / "lc_v39.db"))
    yield c
    c.close()


# ── pruebas ─────────────────────────────────────────────────────────────────

def test_cadena_registrada_completa():
    migs = _migraciones()
    assert all((v, v + 1) in migs for v in range(2, ULTIMA)), f"falta algún eslabón 2→{ULTIMA}"


def test_39_40_41_sin_ddl():
    """Justifica construir v39 con el esquema 41: esas migraciones nunca cambiaron el esquema."""
    migs = _migraciones()
    for k in ((39, 40), (40, 41)):
        assert not any(re.match(r"\s*(CREATE|ALTER|DROP)", q, re.I) for q in migs[k]), k


def test_ninguna_orden_se_elimina_por_nombre(db):
    antes = _conteos(db)
    nombres_antes = sorted(map(str, (r[0] for r in db.execute("SELECT cliente FROM jobs"))))
    _migrar(db)
    assert _conteos(db) == antes
    assert sorted(map(str, (r[0] for r in db.execute("SELECT cliente FROM jobs")))) == nombres_antes
    for n in ("Restaurante La Prueba", "Familia Pruebas", "prueba minúscula", "MiamiCliente Holdings",
              "ClientePrueba SA", "Cliente E2E"):
        assert db.execute("SELECT COUNT(*) FROM jobs WHERE cliente = ?", (n,)).fetchone()[0] == 1, n


def test_fotos_firmas_y_auditoria_se_conservan(db):
    antes = _conteos(db)
    _migrar(db)
    despues = _conteos(db)
    for t in ("photos", "signatures", "audit_log", "job_events", "customers", "properties", "equipment"):
        assert despues[t] == antes[t], t


def test_relaciones_sin_huerfanos(db):
    assert _huerfanos(db) == {k: 0 for k in _huerfanos(db)}
    _migrar(db)
    assert _huerfanos(db) == {k: 0 for k in _huerfanos(db)}


def test_resultado_coincide_con_ultimo_esquema(db):
    _migrar(db)
    assert db.execute("PRAGMA user_version").fetchone()[0] == ULTIMA
    assert _validar_contra_esquema(db, ULTIMA) == []
    assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"


def test_datos_incompletos_sobreviven(db):
    _migrar(db)
    fila = db.execute("SELECT cliente, estado, latitud FROM jobs WHERE id = 1099").fetchone()
    assert fila == (None, None, None)


@pytest.mark.parametrize("punto", [(39, 0), (40, 0), (41, 0), (41, 3), (42, 0)])
def test_interrupcion_no_pierde_datos_y_se_puede_reanudar(db, punto):
    antes = _conteos(db)
    v0 = db.execute("PRAGMA user_version").fetchone()[0]
    migs = _migraciones()
    # posición válida: entre sentencias o justo antes del COMMIT (las no-op solo tienen esta)
    punto = (punto[0], min(punto[1], len(migs[(punto[0], punto[0] + 1)])))
    with pytest.raises(RuntimeError):
        _migrar(db, fallar_en=punto)
    v = db.execute("PRAGMA user_version").fetchone()[0]
    assert v == punto[0], "la versión no debe avanzar en la migración interrumpida"
    assert _conteos(db) == antes
    _migrar(db, desde=v)  # reanudar desde donde quedó
    assert db.execute("PRAGMA user_version").fetchone()[0] == ULTIMA
    assert _conteos(db) == antes
    assert _validar_contra_esquema(db, ULTIMA) == []
    assert v0 == 39


def test_43_44_conserva_partidas_y_las_deja_vigentes(db):
    """La anulación lógica no oculta ni borra partidas existentes: todas quedan con anulado = 0."""
    esquema = _schema(41)   # la base v39 de prueba se construye con el esquema exportado 41
    for i in range(1, 4):
        _insertar(db, esquema, "work_items", i)
    db.commit()
    antes = db.execute("SELECT COUNT(*) FROM work_items").fetchone()[0]
    assert antes == 3
    _migrar(db)
    cols = [r[1] for r in db.execute("PRAGMA table_info(work_items)")]
    assert "anulado" in cols and "anuladoAt" in cols
    assert db.execute("SELECT COUNT(*) FROM work_items WHERE anulado = 0").fetchone()[0] == antes


def test_44_45_conserva_propuestas_y_crea_cola_de_facturas(db):
    """Propuestas y firmas existentes intactas; las propuestas viejas quedan 'nada que enviar' (syncState = 1)."""
    esquema = _schema(41)
    for i in range(1, 3):
        _insertar(db, esquema, "proposals", i, id=i, jobId=1000 + i, estado="ACCEPTED")
    db.commit()
    firmas = db.execute("SELECT COUNT(*) FROM signatures").fetchone()[0]
    _migrar(db)
    assert db.execute("SELECT COUNT(*) FROM proposals WHERE syncState = 1").fetchone()[0] == 2
    assert db.execute("SELECT COUNT(*) FROM proposals WHERE serverId IS NULL").fetchone()[0] == 2
    assert db.execute("SELECT COUNT(*) FROM signatures").fetchone()[0] == firmas
    cols = [r[1] for r in db.execute("PRAGMA table_info(invoice_sync)")]
    assert {"jobId", "clientInvoiceId", "syncState", "numero", "total"} <= set(cols)


def test_45_46_repuestos_firmas_y_pagos_se_conservan_sin_enviarse(db):
    """Datos previos intactos: repuestos vigentes (anulado = 0) y nada marcado para enviar (syncState = 1)."""
    esquema = _schema(41)
    for i in range(1, 3):
        _insertar(db, esquema, "job_parts", i, id=i, jobId=1000 + i, partId=i, cantidad=1)
        _insertar(db, esquema, "payments", i, id=f"pago-{i}", jobId=1000 + i, status="PAID")
    db.commit()
    firmas = db.execute("SELECT COUNT(*) FROM signatures").fetchone()[0]
    _migrar(db)
    assert db.execute("SELECT COUNT(*) FROM job_parts WHERE anulado = 0 AND syncState = 1").fetchone()[0] == 2
    assert db.execute("SELECT COUNT(*) FROM payments WHERE syncState = 1 AND status = 'PAID'").fetchone()[0] == 2
    assert db.execute("SELECT COUNT(*) FROM signatures WHERE syncState = 1").fetchone()[0] == firmas
    assert "pdfSyncState" in [r[1] for r in db.execute("PRAGMA table_info(invoice_sync)")]
