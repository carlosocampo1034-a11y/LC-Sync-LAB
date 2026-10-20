"""Cadena completa de laboratorio: CLIENTE → SERVIDOR → CENTRO DE MANDO → TÉCNICO → COMPLETADO.
El técnico trabaja por /sync/push y /sync/pull (como la app Android); el cliente por su portal."""
import base64
import time

from conftest import h, registrar_cliente

JPEG = b"\xff\xd8\xff\xe0" + b"1" * 300
PNG = b"\x89PNG\r\n\x1a\n" + b"2" * 80


def test_flujo_completo(client, toks):
    desp, tec = toks["desp"], toks["tec"]

    # 1. el cliente se registra, crea su propiedad y equipo y solicita servicio con foto
    ct = registrar_cliente(client, "cliente_integral", "Cliente Integral Ficticio")
    pid = client.post("/api/v2/customer/properties", json={
        "direccion": "300 Calle Integral Ficticia, Lab City", "tipoPropiedad": "Apartamento",
        "tipoAcceso": "Lockbox", "codigoAcceso": "1357", "mascota": "Gato ficticio",
        "contactoRespaldo": "Vecino ficticio (305) 555-0111"}, headers=h(ct)).get_json()["id"]
    eid = client.post(f"/api/v2/customer/properties/{pid}/equipment", json={"nombre": "Lavadora ficticia",
                      "marca": "MarcaLab", "modelo": "L-200"}, headers=h(ct)).get_json()["id"]
    sol = client.post("/api/v2/customer/requests", json={
        "propertyId": pid, "equipmentId": eid, "descripcion": "La lavadora ficticia no centrifuga",
        "categoria": "ELECTRODOMESTICOS", "ventanaPreferida": "mañana por la tarde",
        "clientRequestId": "int-1"}, headers=h(ct)).get_json()
    jid = sol["id"]
    assert client.post(f"/api/v2/jobs/{jid}/photos", data={"foto": (__import__("io").BytesIO(JPEG), "lav.jpg",
                       "image/jpeg"), "categoria": "BEFORE"}, headers=h(ct)).status_code == 201

    # 2-3. el centro de mando la recibe completa en SIN FECHA
    board = client.get("/api/v2/dispatch/board", headers=h(desp)).get_json()
    recibida = next(j for j in board["grupos"]["SIN_FECHA"] if j["id"] == jid)
    for campo, valor in (("descripcion", "La lavadora ficticia no centrifuga"), ("equipo", "Lavadora ficticia"),
                         ("mascota", "Gato ficticio"), ("codigoAcceso", "1357"), ("tipoAcceso", "Lockbox"),
                         ("ventanaPreferida", "mañana por la tarde")):
        assert recibida[campo] == valor, campo

    # 4. despacho programa para hoy y asigna al técnico
    cita = int(time.time() * 1000) + 3600_000
    client.post(f"/api/v2/dispatch/jobs/{jid}/schedule", json={"programadoPara": cita}, headers=h(desp))
    client.post(f"/api/v2/dispatch/jobs/{jid}/assign", json={"technicianId": "tec-t-01"}, headers=h(desp))

    # 5. el técnico la recibe por pull, con el código de acceso (está asignado)
    p = client.get("/api/v2/sync/pull?since=0&deviceId=dev-A", headers=h(tec)).get_json()
    orden = next(j for j in p["jobs"] if j["id"] == jid)
    assert orden["programadoPara"] == cita and orden["codigoAcceso"] == "1357"
    version, upd = orden["version"], 1_000

    # 6. el técnico avanza los estados desde el teléfono (push con su versión base)
    for estado in ("EN RUTA", "LLEGUÉ", "EN REPARACIÓN"):
        upd += 1
        r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [
            {"id": jid, "updatedAt": upd, "estado": estado, "version": version}]}, headers=h(tec)).get_json()
        assert r["results"][0]["status"] == "ACCEPTED", r
        version = r["results"][0]["version"]

    # 7. el cliente ve los cambios de estado y el técnico asignado
    vista = client.get(f"/api/v2/customer/requests/{jid}", headers=h(ct)).get_json()
    assert vista["estado"] == "EN REPARACIÓN" and vista["tecnicoNombre"] == "Técnico T"
    assert [x["valor"] for x in vista["historial"] if x["campo"] == "Estado"][-3:] == ["EN RUTA", "LLEGUÉ",
                                                                                       "EN REPARACIÓN"]

    # 8. fotos, notas, diagnóstico y mediciones desde el teléfono
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [], "photos": [
        {"id": 901, "jobId": jid, "filename": "despues.jpg", "mimeType": "image/jpeg",
         "base64Data": base64.b64encode(JPEG + b"x").decode(), "categoria": "AFTER"}], "events": [
        {"clientEventId": "n1", "jobId": jid, "tipo": "NOTA", "payload": {"texto": "Correa ficticia gastada"}},
        {"clientEventId": "d1", "jobId": jid, "tipo": "DIAGNOSTICO", "payload": {"texto": "Correa de transmisión"}},
        {"clientEventId": "m1", "jobId": jid, "tipo": "MEDICION", "payload": {"nombre": "RPM", "valor": 0}}]},
        headers=h(tec)).get_json()
    assert r["photos"]["accepted_ids"] == [901] and len(r["events"]["accepted_ids"]) == 3

    # 9. aprobación del cliente con firma, repuesto y factura
    ap = client.post(f"/api/v2/jobs/{jid}/approvals", json={"tipo": "PROPUESTA", "descripcion": "Cambiar correa",
                     "monto": 95.0}, headers=h(tec)).get_json()
    assert client.post(f"/api/v2/approvals/{ap['id']}/decide", json={
        "decision": "APROBADA", "firmante": "Cliente Integral Ficticio",
        "firmaPngBase64": base64.b64encode(PNG).decode()}, headers=h(ct)).status_code == 200
    rp = client.post(f"/api/v2/jobs/{jid}/parts", json={"sku": "T-CAP", "cantidad": 1}, headers=h(tec)).get_json()
    client.post(f"/api/v2/job_parts/{rp['id']}/status", json={"estado": "APROBADO"}, headers=h(desp))
    assert client.post(f"/api/v2/job_parts/{rp['id']}/status", json={"estado": "USADO"},
                       headers=h(tec)).status_code == 200
    upd += 1
    r = client.post("/api/v2/sync/push", json={"deviceId": "dev-A", "jobs": [
        {"id": jid, "updatedAt": upd, "estado": "COMPLETADO", "version": version}]}, headers=h(tec)).get_json()
    assert r["results"][0]["status"] == "ACCEPTED"
    inv = client.post(f"/api/v2/jobs/{jid}/invoice", json={"manoDeObra": [{"horas": 1, "tarifa": 85}]},
                      headers=h(tec)).get_json()
    client.post(f"/api/v2/invoices/{inv['id']}/emit", headers=h(desp))

    # 10. reporte del trabajo con todo lo registrado
    rep = client.get(f"/api/v2/jobs/{jid}/report", headers=h(desp)).get_json()
    assert [e["estado"] for e in rep["estados"]] == ["PENDIENTE", "EN RUTA", "LLEGUÉ", "EN REPARACIÓN", "COMPLETADO"]
    assert len(rep["fotos"]) == 2 and rep["diagnostico"] and rep["mediciones"] and rep["notas"]
    assert rep["aprobaciones"][0]["estado"] == "APROBADA" and rep["aprobaciones"][0]["firma_sha256"]
    assert rep["repuestos"][0]["estado"] == "USADO" and rep["factura"]["estado"] == "EMITIDA"

    # 11. historial disponible: completada en el tablero y visible para el cliente con su factura
    board = client.get("/api/v2/dispatch/board", headers=h(desp)).get_json()
    assert jid in {j["id"] for j in board["grupos"]["COMPLETADOS"]}
    fin = client.get(f"/api/v2/customer/requests/{jid}", headers=h(ct)).get_json()
    assert fin["estado"] == "COMPLETADO" and fin["factura"]["estado"] == "EMITIDA"
    rep_cli = client.get(f"/api/v2/jobs/{jid}/report", headers=h(ct)).get_json()
    assert rep_cli["orden"]["estado"] == "COMPLETADO"
