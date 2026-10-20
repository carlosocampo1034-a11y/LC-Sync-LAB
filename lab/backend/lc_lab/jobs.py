"""Órdenes de servicio: campos, historial, merge de 3 vías y agenda (HOY/PRÓXIMOS/ATRASADOS/...)."""
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from flask import current_app

from .db import audit, next_counter, now_ms
from .security import cifrar, descifrar, puede_ver_acceso

ESTADOS = ["PENDIENTE", "EN RUTA", "LLEGUÉ", "EN REPARACIÓN", "ESPERANDO APROBACIÓN", "PART HOLD",
           "PAUSADO", "COMPLETADO", "CANCELADO"]
EN_CURSO = {"EN RUTA", "LLEGUÉ", "EN REPARACIÓN", "ESPERANDO APROBACIÓN", "PART HOLD", "PAUSADO"}
CERRADOS = {"COMPLETADO", "CANCELADO"}

# api (nombres de la app Android) -> columna
CAMPOS = {
    "estado": "estado", "estadoDispatch": "estado_dispatch", "prioridad": "prioridad",
    "categoria": "categoria", "technicianId": "technician_code",
    "cliente": "cliente", "telefono": "telefono", "direccion": "direccion",
    "latitud": "lat", "longitud": "lng", "ubicacionFuente": "ubicacion_fuente",
    "hora": "hora", "programadoPara": "programado_para", "zonaHoraria": "zona_horaria",
    "ventanaPreferida": "ventana_preferida",
    "descripcion": "descripcion", "equipo": "equipo",
    "tipoPropiedad": "tipo_propiedad", "clientePresente": "cliente_presente",
    "tipoAcceso": "tipo_acceso", "instrucciones": "instrucciones", "mascota": "mascota",
    "contactoRespaldo": "contacto_respaldo", "notasSeguridad": "notas_seguridad", "notas": "notas",
    "coverageType": "coverage_type", "coverageAuth": "coverage_auth",
}
SECRETOS = {"codigoAcceso": "codigo_acceso_enc", "ubicacionLlave": "ubicacion_llave_enc"}


def _norm(col, v):
    if v == "":
        return None if col not in ("estado",) else v
    if col == "cliente_presente" and v is not None:
        return 1 if v in (True, 1, "1", "true") else 0
    return v


def validar(datos, creando):
    """Errores de validación de un trabajo entrante (api)."""
    err = []
    e = datos.get("estado")
    if e is not None and e not in ESTADOS:
        err.append(f"estado desconocido: {e}")
    if creando and not (datos.get("cliente") or datos.get("customerLocal")):
        err.append("falta cliente")
    if creando and not datos.get("direccion"):
        err.append("falta dirección")
    lat, lng = datos.get("latitud"), datos.get("longitud")
    if (lat is None) != (lng is None):
        err.append("coordenadas incompletas")
    if lat is not None and not (-90 <= float(lat) <= 90 and -180 <= float(lng) <= 180):
        err.append("coordenadas fuera de rango")
    if lat is not None and datos.get("ubicacionFuente") not in ("GEOCODER", "GPS", "MANUAL_CONFIRMADA"):
        err.append("coordenadas sin fuente verificada")
    pp = datos.get("programadoPara")
    if pp is not None and not isinstance(pp, int):
        err.append("programadoPara debe ser epoch ms")
    return err


def a_api(row, user, db=None):
    """Fila -> JSON, ocultando datos de acceso a quien no corresponde."""
    out = {"id": row["id"], "version": row["version"], "origin": row["origin"],
           "customerId": row["customer_id"], "propertyId": row["property_id"],
           "equipmentId": row["equipment_id"], "updatedAt": row["client_updated_at"] or row["updated_at"],
           "serverUpdatedAt": row["updated_at"], "createdAt": row["created_at"]}
    for api, col in CAMPOS.items():
        v = row[col]
        out[api] = (bool(v) if v is not None else None) if col == "cliente_presente" else v
    ver = user is not None and puede_ver_acceso(user, row)
    for api, col in SECRETOS.items():
        tiene = bool(row[col])
        out[api] = descifrar(row[col]) if (ver and tiene) else ("" if not tiene else None)
        out[api + "Protegido"] = tiene and not ver
    out["categoriaAgenda"] = categoria_agenda(row)
    if db is not None:
        out["conflictosPendientes"] = db.execute("SELECT COUNT(*) FROM job_conflicts WHERE job_id = ? AND "
                                                 "estado = 'PENDIENTE'", (row["id"],)).fetchone()[0]
        if user is not None and user["role"] != "CUSTOMER":
            from . import proposals as PR
            out["propuestas"] = PR.propuestas_de_orden(db, row["id"], tecnico=user["role"] == "TECHNICIAN")
            from . import campo as CA
            inv = db.execute("SELECT * FROM invoices WHERE job_id = ?", (row["id"],)).fetchone()
            out["factura"] = PR.factura_api(inv, db) if inv else None
            out["pagos"] = CA.pagos_de_orden(db, row["id"])
            out["firmas"] = CA.firmas_de_orden(db, row["id"])
            out["descuentoAutorizado"] = CA.descuento_autorizado(db, row["id"])
            out["variaciones"] = PR.variaciones_de_orden(db, row["id"])
    return out


def _registrar(db, job_id, version, campo, viejo, nuevo, actor, source, ts):
    if campo in SECRETOS.values():
        viejo, nuevo = ("•••" if viejo else None), ("•••" if nuevo else None)  # nunca en claro en el historial
    db.execute("INSERT INTO job_history(job_id, version, field, old_value, new_value, actor, source, ts) "
               "VALUES (?,?,?,?,?,?,?,?)", (job_id, version, campo,
                                             None if viejo is None else str(viejo),
                                             None if nuevo is None else str(nuevo), actor, source, ts))


def _cambios(row, datos):
    """{columna: nuevo_valor} para los campos que difieren del registro actual (row puede ser None)."""
    cambios = {}
    for api, col in CAMPOS.items():
        if api in datos:
            nuevo = _norm(col, datos[api])
            actual = row[col] if row is not None else None
            if nuevo != actual:
                cambios[col] = nuevo
    for api, col in SECRETOS.items():
        if api in datos and datos[api] is not None:  # None = "no lo sé" (dato protegido): no tocar
            actual = descifrar(row[col]) if row is not None and row[col] else ""
            if (datos[api] or "") != actual:
                cambios[col] = cifrar(datos[api])
    return cambios


def crear(db, datos, origin, actor, source, device=None, job_id=None, extra=None):
    t = now_ms()
    if job_id is None:
        # el contador salta ids ocupados (p. ej. una orden que un teléfono antiguo creó en este rango)
        job_id = next_counter(db, "server_job_id")
        while db.execute("SELECT 1 FROM jobs WHERE id = ?", (job_id,)).fetchone():
            job_id = next_counter(db, "server_job_id")
    cambios = _cambios(None, datos)
    cambios.setdefault("estado", "PENDIENTE")
    cambios.update(extra or {})
    seq = next_counter(db, "job_seq")
    cols = ["id", "version", "seq", "origin", "origin_device", "client_updated_at", "created_at", "updated_at"] + list(cambios)
    vals = [job_id, 1, seq, origin, device, datos.get("updatedAt"), t, t] + list(cambios.values())
    db.execute(f"INSERT INTO jobs({','.join(cols)}) VALUES ({','.join('?' * len(cols))})", vals)
    for col, v in cambios.items():
        _registrar(db, job_id, 1, col, None, v, actor, source, t)
    audit(db, actor, "CREATE", "job", job_id, f"origin={origin}", device)
    return job_id


def campos_cambiados_desde(db, job_id, version_base, excluir_actor=None):
    q = "SELECT DISTINCT field FROM job_history WHERE job_id = ? AND version > ?"
    p = [job_id, version_base]
    if excluir_actor:
        q += " AND actor != ?"
        p.append(excluir_actor)
    return {r[0] for r in db.execute(q, p)}


def valor_en_version(db, job_id, col, version):
    """Valor de una columna tal como estaba en `version` (reconstruido desde job_history)."""
    r = db.execute("SELECT new_value FROM job_history WHERE job_id = ? AND field = ? AND version <= ? "
                   "ORDER BY version DESC, id DESC LIMIT 1", (job_id, col, version)).fetchone()
    return r[0] if r else None


def _como_texto(v):
    return None if v is None else str(v)


def aplicar(db, row, datos, actor, source, version_base=None, device=None, extra=None):
    """Aplica cambios a un trabajo existente con merge de 3 vías (base, servidor, cliente):
    - version_base == versión actual → se aplica todo lo que difiera.
    - versión base antigua → por campo:
        * el cliente no lo cambió (su valor == valor en la base) → se conserva el del servidor;
        * lo cambió el cliente y nadie más desde la base → se aplica;
        * lo cambiaron ambos con valores distintos → CONFLICTO: se guardan ambos valores en job_conflicts
          (pendiente de que despacho decida), ese campo NO se modifica y el resto del lote sí se aplica.
    Devuelve (status, version, campos_en_conflicto)."""
    cambios = _cambios(row, datos)
    conflicto = []
    if version_base is not None and version_base < row["version"]:
        # Base efectiva: la última versión escrita por este mismo actor. Sus propios cambios aún no
        # confirmados en el teléfono (p. ej. respuesta perdida o edición en tránsito) no son "de otros":
        # sin esto, volver a un valor anterior se tomaría como "no lo cambió" y se perdería.
        propia = db.execute("SELECT MAX(version) FROM job_history WHERE job_id = ? AND actor = ?",
                            (row["id"], actor)).fetchone()[0]
        if propia is not None and propia > version_base:
            version_base = propia
    if version_base is not None and version_base < row["version"]:
        tocados = campos_cambiados_desde(db, row["id"], version_base, excluir_actor=actor)
        for col in list(cambios):
            if col in SECRETOS.values():
                if col in tocados:
                    conflicto.append(col)
                continue
            base = valor_en_version(db, row["id"], col, version_base)
            if _como_texto(cambios[col]) == base:
                del cambios[col]           # el cliente no lo tocó: se queda el valor del servidor
            elif col in tocados:
                conflicto.append(col)
        if conflicto:
            # Se conservan AMBAS versiones como conflicto pendiente; lo seguro (campos que solo cambió el
            # cliente) se aplica igualmente. El valor del servidor no se toca hasta que despacho resuelva.
            registrar_conflictos(db, row, conflicto, cambios, version_base, actor, device)
            for col in conflicto:
                del cambios[col]
    cambios.update(extra or {})
    estado = "CONFLICT" if conflicto else "ACCEPTED"
    if not cambios:
        return estado, row["version"], sorted(conflicto)
    t = now_ms()
    v = row["version"] + 1
    seq = next_counter(db, "job_seq")
    sets = ", ".join(f"{c} = ?" for c in cambios)
    db.execute(f"UPDATE jobs SET {sets}, version = ?, seq = ?, updated_at = ?, "
               f"client_updated_at = COALESCE(?, client_updated_at) WHERE id = ?",
               list(cambios.values()) + [v, seq, t, datos.get("updatedAt"), row["id"]])
    for c, nuevo in cambios.items():
        _registrar(db, row["id"], v, c, row[c], nuevo, actor, source, t)
    audit(db, actor, "UPDATE", "job", row["id"], ",".join(sorted(cambios)), device)
    return estado, v, sorted(conflicto)


def registrar_conflictos(db, row, campos, cambios, base, actor, device):
    import hashlib
    t = now_ms()
    for col in campos:
        cli = cambios[col]
        cli_txt = None if cli is None else str(cli)
        h = hashlib.sha256((cli_txt or "∅").encode()).hexdigest()
        db.execute("INSERT OR IGNORE INTO job_conflicts(job_id, field, base_version, server_version, server_value, "
                   "client_value, client_value_hash, device_id, actor, estado, created_at) "
                   "VALUES (?,?,?,?,?,?,?,?,?, 'PENDIENTE', ?)",
                   (row["id"], col, base, row["version"], None if row[col] is None else str(row[col]), cli_txt, h,
                    device, actor, t))
    audit(db, actor, "CONFLICT", "job", row["id"], ",".join(campos), device)


def conflictos_pendientes(db, job_id):
    return db.execute("SELECT * FROM job_conflicts WHERE job_id = ? AND estado = 'PENDIENTE' ORDER BY id",
                      (job_id,)).fetchall()


def conflicto_api(c, ver_secretos):
    """Conflicto → JSON. Valores de campos secretos solo descifrados para quien puede verlos."""
    secreto = c["field"] in SECRETOS.values()
    def val(v):
        if v is None:
            return None
        if secreto:
            return descifrar(v) if ver_secretos else "•••"
        return v
    return {"id": c["id"], "jobId": c["job_id"], "campo": c["field"], "valorServidor": val(c["server_value"]),
            "valorTecnico": val(c["client_value"]), "baseVersion": c["base_version"],
            "serverVersion": c["server_version"], "actor": c["actor"], "estado": c["estado"],
            "resolucion": c["resolucion"], "valorFinal": val(c["valor_final"]), "creado": c["created_at"],
            "resuelto": c["resolved_at"], "resueltoPor": c["resuelto_por"]}


# ── agenda ──────────────────────────────────────────────────────────────────

def _tz(row=None):
    nombre = (row["zona_horaria"] if row is not None and row["zona_horaria"] else None) \
        or current_app.config.get("ZONA_HORARIA", "America/New_York")
    try:
        return ZoneInfo(nombre)
    except Exception:
        return ZoneInfo("America/New_York")


def categoria_agenda(row, ahora_ms=None, dias_proximos=None):
    """EN_CURSO tiene prioridad; luego COMPLETADOS/CANCELADOS; luego por fecha programada."""
    estado = row["estado"]
    if estado in EN_CURSO:
        return "EN_CURSO"
    if estado == "COMPLETADO":
        return "COMPLETADOS"
    if estado == "CANCELADO":
        return "CANCELADOS"
    pp = row["programado_para"]
    if pp is None:
        return "SIN_FECHA"
    tz = _tz(row)
    ahora = datetime.fromtimestamp((ahora_ms or now_ms()) / 1000, tz)
    dia = datetime.fromtimestamp(pp / 1000, tz).date()
    if dia == ahora.date():
        return "HOY"
    if dia < ahora.date():
        return "ATRASADOS"
    return "PROXIMOS"


def dentro_de_proximos(row, dias, hasta_ms=None, ahora_ms=None):
    tz = _tz(row)
    ahora = datetime.fromtimestamp((ahora_ms or now_ms()) / 1000, tz)
    limite = datetime.combine(ahora.date() + timedelta(days=dias + 1), datetime.min.time(), tz)
    tope = hasta_ms if hasta_ms is not None else int(limite.timestamp() * 1000)
    return row["programado_para"] is not None and row["programado_para"] < tope


def resolver_conflicto(db, cid, decision, valor, version, actor):
    """Devuelve (código HTTP, mensaje, extra). No hace commit."""
    from .security import cifrar
    from .db import audit as _audit
    c = db.execute("SELECT * FROM job_conflicts WHERE id = ?", (cid,)).fetchone()
    if c is None:
        return 404, "conflicto no encontrado", {}
    if c["estado"] != "PENDIENTE":
        return 409, "este conflicto ya se resolvió", {}
    row = db.execute("SELECT * FROM jobs WHERE id = ?", (c["job_id"],)).fetchone()
    if version != row["version"]:
        return 409, "la orden cambió; recarga", {"version": row["version"]}
    if decision not in ("SERVIDOR", "TECNICO", "COMBINAR"):
        return 400, "decision: SERVIDOR, TECNICO o COMBINAR", {}
    col = c["field"]
    secreto = col in SECRETOS.values()
    if decision == "SERVIDOR":
        final = row[col]
    elif decision == "TECNICO":
        final = c["client_value"]
    else:
        if not isinstance(valor, str) or not valor.strip():
            return 400, "COMBINAR requiere 'valor'", {}
        final = cifrar(valor) if secreto else valor
    t = now_ms()
    if decision != "SERVIDOR" and final != row[col]:
        v = row["version"] + 1
        db.execute(f"UPDATE jobs SET {col} = ?, version = ?, seq = ?, updated_at = ? WHERE id = ?",
                   (final, v, next_counter(db, "job_seq"), t, row["id"]))
        _registrar(db, row["id"], v, col, row[col], final, actor, "resolucion-conflicto", t)
    else:
        # sin cambio de valor: misma versión, pero nueva secuencia para que el pull avise al teléfono
        db.execute("UPDATE jobs SET seq = ? WHERE id = ?", (next_counter(db, "job_seq"), row["id"]))
    db.execute("UPDATE job_conflicts SET estado = 'RESUELTO', resolucion = ?, valor_final = ?, resuelto_por = ?, "
               "resolved_at = ? WHERE id = ?", ({"COMBINAR": "COMBINADO"}.get(decision, decision),
                                               None if final is None else str(final), actor, now_ms(), cid))
    _audit(db, actor, "RESOLVE_CONFLICT", "job_conflict", cid, decision)
    return 200, "ok", {}
