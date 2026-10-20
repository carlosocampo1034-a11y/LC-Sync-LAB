# L&C — FLUJO MAESTRO COMPLETO
# Visión de producto: de cliente nuevo a job cerrado, cobrado y documentado
# Este archivo es la BRÚJULA del proyecto. No se modifica sin aprobación.
# Última actualización: 2026-10-06

=====================================================================
## PRINCIPIO CENTRAL

L&C NO será solo una app de técnico.
Será un sistema completo de Field Service con:
  - Centro de Mando (Control)
  - Técnico (App Android offline-first)
  - Cliente (App/Web/Portal)
  - Backend (API + Postgres + Auth + Sync)

Preparado para: HVAC, plomería, electricidad, electrodomésticos 
y futuros servicios.

=====================================================================
## 1. ESTRUCTURA GENERAL

┌─────────────────────┐
│   L&C CONTROL       │
│ Centro de mando     │
│ Call Center/Admin   │
└─────────┬───────────┘
          │
          ▼
┌─────────────────────┐
│    BACKEND L&C      │
│ API + PostgreSQL    │
│ Auth + Sync + Rules │
└─────┬────────┬──────┘
      │        │
┌─────▼────┐ ┌─▼─────────────┐
│TECHNICIAN│ │  CUSTOMER     │
│Android   │ │ App/Web/Portal│
│offline   │ │ Cliente       │
└──────────┘ └───────────────┘

La app actual (~/LC) es principalmente L&C TECHNICIAN, pero se 
construye pensando en la arquitectura completa.

=====================================================================
## 2. ROLES DEL SISTEMA

### Control Central / Call Center
- crear clientes
- buscar clientes existentes
- crear propiedades
- crear llamadas/Jobs
- elegir fecha/hora
- asignar técnicos
- cambiar técnicos
- ver mapa/estado
- aprobar partes
- aprobar descuentos
- revisar propuestas
- administrar planes
- ver pagos
- resolver excepciones
- cerrar/reabrir Jobs según permisos

### Técnico
- ver sus trabajos
- navegar
- llamar al cliente
- registrar llegada
- revisar acceso
- identificar equipo
- escanear placa
- diagnosticar
- hacer inspección
- agregar findings
- solicitar partes
- crear trabajo/materiales
- generar propuesta
- tomar firmas
- cobrar
- documentar
- cerrar el servicio

### Cliente
- solicitar servicio
- escoger disponibilidad
- subir fotos
- consultar estado
- ver técnico asignado
- ver equipos
- revisar propuesta
- aprobar/rechazar
- firmar
- pagar
- recibir factura
- ver historial
- solicitar otra visita

### Supervisor / Admin
- pricing
- descuentos
- refunds
- inventory
- technician management
- coverage
- contract pricing
- audit
- dispute handling

### Contractor externo
- flujo parecido al técnico con controles adicionales:
- credenciales
- seguro
- zonas
- especialidades
- disponibilidad
- comisión
- aceptación/rechazo del Job

=====================================================================
## 3. ENTRADA DE UN CLIENTE (3 formas)

### Desde Call Center:
llamada → buscar teléfono/email/dirección → cliente encontrado o nuevo
→ seleccionar/crear Property → crear Job

### Desde el técnico (nuevo cliente en campo):
Técnico → Nuevo cliente → datos básicos → Property → Equipment → Job
(Sujeto a permisos/configuración de empresa)

### Desde app/web del cliente:
Registro → teléfono/email → dirección → Property → solicitud de 
servicio → creación de Job

Todos usan las mismas entidades centrales.

=====================================================================
## 4. JERARQUÍA FUNDAMENTAL

Customer
   ├── Property 1
   │      ├── Equipment A
   │      ├── Equipment B
   │      └── Jobs históricos
   └── Property 2
          ├── Equipment C
          └── Jobs históricos

REGLA DE ORO: NUNCA duplicar clientes o equipos porque ocurrió otra 
llamada. El historial pertenece al mismo cliente/equipo.

=====================================================================
## 5. CUSTOMER

Customer:
- id, nombre, apellido, telefono, email
- preferredContact, language
- billingAddress
- notes, createdAt, updatedAt

Debe tener:
- Múltiples Properties
- Múltiples Contacts (dueño, inquilino, pagador, firmante, 
  property manager, contacto emergencia)

=====================================================================
## 6. PROPERTY

Property contiene info operativa:
- direccion, unit/apartment
- gate/access, codigoAcceso, ubicacionLlave
- clientePresente, mascotas, riesgos
- parking, instrucciones, contactos, notas

El técnico debe ver esto ANTES de llegar.

Ejemplo:
123 Main St Apt 204
Acceso: Gate code 0912
Contacto: Juan Pérez
Mascota: Perro
Notas: Llamar 15 minutos antes.

=====================================================================
## 7. EQUIPMENT

Cada Property puede tener múltiples equipos:
- AC System #1 (Indoor + Outdoor)
- AC System #2 (Indoor + Outdoor)
- Water Heater, Dishwasher, Refrigerator

Campos HVAC:
marca, modelo, serial, tipo, tons, refrigerante, voltaje, fase, 
seer, manufactureDate, ubicacion, heatPump, systemRole, 
pairedEquipmentId, lifecycleStatus, replacedByEquipmentId, scanStatus

Pairing:
INDOOR ↔ OUTDOOR

Lifecycle:
ACTIVE → REPLACED → RETIRED

=====================================================================
## 8. ESCANEO INTELIGENTE DE PLACA

Técnico → Escanear placa → cámara
→ conservar fotografía original
→ OCR local → extraer texto
→ parser → candidatos
→ revisión humana → confirmar
→ Equipment

NUNCA: OCR → modificar Equipment automáticamente

Estados del scan:
- MANUAL
- PENDING_CONFIRMATION
- CONFIRMED

Campos a detectar:
Manufacturer, Model, Serial, Tons, Refrigerant, Voltage, Phase, 
SEER/SEER2, Manufacture date

Más adelante:
manual asociado, wiring diagram, parts diagram, error codes, 
specifications

=====================================================================
## 9. CREACIÓN DEL JOB

Job debe incluir:
- Customer, Property, Equipment
- tipo de servicio, problema
- fecha, ventana horaria, prioridad
- plan/cobertura, deductible
- technician, dispatcher
- access instructions, photos iniciales, notes

Tipos:
SERVICE, MAINTENANCE, INSTALLATION, CALLBACK, WARRANTY, INSPECTION, 
ESTIMATE, EMERGENCY

=====================================================================
## 10. ESTADOS DEL JOB

NEW → SCHEDULED → ASSIGNED → EN_ROUTE → ARRIVED → IN_PROGRESS 
→ WAITING_APPROVAL → WAITING_PARTS → RETURN_VISIT → READY_TO_COMPLETE 
→ COMPLETED

Excepcionales:
NO_ACCESS, STOP_WORK, CUSTOMER_CANCELLED, TECHNICIAN_CANCELLED, 
ON_HOLD

Cada transición registra:
actor, timestamp, oldStatus, newStatus, reason, GPS opcional, notes

=====================================================================
## 11. DISPATCH

Centro de Mando recibe Jobs. Debe ver:
- Jobs de hoy, sin asignar, asignados, atrasados, emergencias
- técnicos activos, libres, capacidad, zonas, especialidad

Flujo:
Job nuevo → buscar técnico elegible → disponibilidad → ubicación 
→ skills → carga → asignar

A futuro: sugerir automáticamente el mejor técnico. NUNCA obligar 
asignación automática sin posibilidad humana de override.

=====================================================================
## 12. APP DEL TÉCNICO — DASHBOARD

MIS TRABAJOS DE HOY

Cada tarjeta:
8:00 AM
Juan Pérez
123 Main St
AC no enfría
GOLD PLAN
[EN RUTA]

Acciones: Navigate, Call, Text, Open Job

El técnico no debería necesitar buscar info en 15 pantallas.

=====================================================================
## 13. LLEGADA AL JOB

Job → En ruta → Llegué → Start Job

Al iniciar:
timestamp, technicianId, JobEvent, TimeEvent

Tracking de: travel time, on-site time, pause, parts run, job duration

=====================================================================
## 14. ANTES DEL DIAGNÓSTICO

Técnico ve:
Cliente, Property, Access, Contacts, Plan, Coverage, Equipment, 
Historial, Previous repairs, Previous proposals, Parts used, 
Photos, Notes

Evita repetir diagnóstico que ya se hizo antes.

=====================================================================
## 15. DIAGNÓSTICO

Symptom → Inspection → Measurements → Findings → Diagnosis

Ejemplo HVAC:
Complaint: AC no cooling
Measurements: Supply 57°F, Return 78°F, Delta T 21°F
Pressure: Suction, Liquid
Finding: Capacitor weak
Diagnosis: Outdoor fan capacitor failing

NUNCA confundir medición con diagnóstico.

=====================================================================
## 16. INSPECCIÓN

Ya existen: InspectionActivity, InspectionItemEntity, 
FieldMeasurementEntity

Flujo: Inspection Template → Items → Measurements → Pass/Fail → Finding

Ejemplo:
Drain line PASS
Capacitor FAIL
Contactor PASS
Coil DIRTY

=====================================================================
## 17. FINDING

Finding:
- Outdoor capacitor weak
- Severity: Medium
- Evidence: photo
- Recommended action: Replace capacitor

Finding → WorkItem

=====================================================================
## 18. WORK ITEMS

WorkItemEntity debe representar:
LABOR, MATERIAL, SERVICE, FEE, DISCOUNT

Ejemplo:
Replace dual capacitor
Labor: 1 × $120
Part: 45/5 µF capacitor 1 × $85

=====================================================================
## 19. PARTS

PartEntity, JobPartEntity

Flujo:
Part needed → check truck → check warehouse → check supplier

Si está en truck:
reserve → consume → stock movement

Si no:
Parts Request → approval → supplier → order → hold Job

=====================================================================
## 20. INVENTARIO COMPLETO

Warehouse, Truck, Technician, Part, Stock, Movement, Transfer, 
Request, Purchase, Return, Adjustment

Ejemplo:
Warehouse → transfer → Truck Carlos → consume → Job #1043

Todo movimiento debe ser auditable.

=====================================================================
## 21. SUPPLIERS

Soportar proveedores configurables:
Johnstone, Ferguson, Home Depot, Grainger, local suppliers

NO hardcodear precios como si fueran oficiales.

Arquitectura:
Supplier, SupplierPart, SupplierPrice, Availability, Distance, 
LastUpdated

=====================================================================
## 22. COVERAGE / MEMBERSHIP

Planes futuros: Gold, Silver, Home Warranty, Maintenance, COD

Flujo:
Finding → revisar plan → coverage rules → covered/not covered 
→ deductible → customer responsibility

Reglas configurables desde empresa. NO inventarlas.

=====================================================================
## 23. PRICING

Separar:
- part cost
- labor cost
- overhead
- markup
- discount
- tax
- customer price

Guardar snapshots. NUNCA depender del precio actual de la pieza 
después de propuesta aceptada.

=====================================================================
## 24. PROPOSAL (v1 actual)

GOOD / BETTER / BEST

Ejemplo:
GOOD: Repair capacitor
BETTER: Capacitor + contactor
BEST: System replacement

Cliente puede comparar.

=====================================================================
## 25. PROPOSAL V2 (futuro)

Proposal Version 1, 2, 3...
Una vez firmado: LOCKED

Debe preservar:
parts, labor, pricing, tax, discount, equipment, terms, customer, 
signature, timestamp

No cambia porque después alguien modificó un Part o Equipment.

=====================================================================
## 26. APROBACIÓN DEL CLIENTE

Puede ser en teléfono del técnico o link remoto.

Flujo:
Proposal → customer reviews → selects option → accepts terms → signature

=====================================================================
## 27. PART HOLD / RETURN VISIT

Si faltan piezas:
Proposal accepted → Parts requested → Job = WAITING_PARTS

Cuando llegan:
Parts received → Return Visit → schedule → same Job/history

NO crear cliente nuevo ni perder historial.

=====================================================================
## 28. STOP WORK

Si condición insegura: STOP WORK
Registrar: reason, technician, time, photos, notes, 
customer notification

Ejemplos: electrical hazard, mold, asbestos, unsafe access, 
structural

=====================================================================
## 29. NEED HELP

Técnico puede pedir:
technical support, supervisor, second technician, special equipment

Centro de mando recibe alerta.

=====================================================================
## 30. NO ACCESS

Flujo:
arrive → customer absent → call → text → wait → NO ACCESS

Evidence: timestamp, photo, call attempt, notes

=====================================================================
## 31. EJECUCIÓN DEL TRABAJO

Cuando aprobado:
WorkItem → Start repair → Parts consumed → Labor/time recorded 
→ photos → measurements

=====================================================================
## 32. FINAL VERIFICATION

No basta con decir "terminé". Debe existir FINAL VERIFICATION.

Ejemplo HVAC:
system running, delta T verified, drain checked, thermostat checked, 
no leaks, customer informed

=====================================================================
## 33. FOTOS

Categorías:
BEFORE, AFTER, NAMEPLATE, DIAGNOSTIC, PART, DAMAGE, SAFETY, 
DOCUMENT, SIGNATURE

Conservar: original file, timestamp, job, equipment, technician, 
category

=====================================================================
## 34. PAGO

Métodos futuros:
CARD, TAP TO PAY, APPLE PAY, GOOGLE PAY, CASH, CHECK, ZELLE, BANK, 
OTHER

Separar:
Payment reported ≠ Payment settled

Estados:
REPORTED → PENDING_REVIEW → PROOF_ACCEPTED → 
PENDING_RECONCILIATION → SETTLED → REFUNDED

=====================================================================
## 35. FIRMA

Firma para: proposal, work authorization, completion, invoice, payment

Cada firma registra:
signer, role, timestamp, document/version, image/signature

=====================================================================
## 36. INVOICE

Al final:
Customer, Property, Job, Equipment, Findings, Labor, Parts, Taxes, 
Discount, Payment, Balance

Generar: PDF, email, customer portal

=====================================================================
## 37. REPORTE TÉCNICO FINAL

SERVICE REPORT:
Customer, Property, Technician, Date, Complaint, Equipment, 
Diagnosis, Measurements, Work performed, Parts used, Before photos, 
After photos, Recommendations, Payment, Customer signature

=====================================================================
## 38. CIERRE DEL JOB

Botón claro: CLIENTE ATENDIDO o COMPLETE JOB

Antes debe validar lo necesario.
Después: status = COMPLETED, completedBy, completedAt

NO borrar nada.

=====================================================================
## 39. HISTORIAL

Todo queda relacionado:
Customer → Property → Equipment → Jobs
  ├── inspections
  ├── measurements
  ├── findings
  ├── work items
  ├── parts
  ├── proposals
  ├── signatures
  ├── photos
  ├── payments
  └── reports

Esto crea el valor real del sistema.

=====================================================================
## 40. CLIENTE DESPUÉS DEL SERVICIO

Cliente recibe:
Job completed, invoice, report, receipt, warranty info, 
recommendations

Después: rating, review, tip, follow-up, maintenance reminder

=====================================================================
## 41. CALLBACKS

Si vuelve por el mismo problema:
New Job → linked to previous Job → CALLBACK

NO borrar ni modificar el Job anterior.

Permite medir: callback rate, technician quality, problem recurrence

=====================================================================
## 42. KPIs

Jobs/day, completion rate, average ticket, conversion rate, 
callback rate, first-time fix, technician revenue, labor 
utilization, travel time, parts usage, gross margin, customer 
satisfaction

Separar medición real de estimaciones.

=====================================================================
## 43. AUDIT LOG

Cambios importantes:
who, what, before, after, when, device

Particularmente: Job status, Technician assignment, Equipment 
identity, Pricing, Proposal, Payment, Refund, Coverage, Inventory

=====================================================================
## 44. NOTIFICACIONES

Futuro:
Job assigned, Job reassigned, Technician en route, Technician 
arrived, Proposal ready, Proposal accepted, Part arrived, Return 
visit, Payment required, Job completed

=====================================================================
## 45. OFFLINE FIRST

El técnico debe poder trabajar aunque no tenga Internet.

UI → Repository → Room
Luego: Sync Queue → backend

Cada cambio debe tener idealmente:
localId, serverId, updatedAt, syncStatus

Estados: LOCAL_ONLY, PENDING_SYNC, SYNCED, CONFLICT

=====================================================================
## 46. BACKEND

BFS ya tiene:
Express, Prisma, PostgreSQL, auth, customers, equipment, jobs, 
dispatch, inspection, inventory, membership, proposals, work-items, 
attachments, audit

Objetivo:
L&C Android ↔ BFS/L&C API
Reutilizando/adaptando esa lógica.

=====================================================================
## 47. SEGURIDAD

authentication, roles, permissions, tenant/company isolation, 
audit, secure tokens, encrypted transport, no plaintext credentials

Técnico NO debe tener automáticamente permisos Admin.

=====================================================================
## 48. MULTIEMPRESA

Si se comercializa:
Organization / Company
  Users, Technicians, Customers, Jobs, Inventory, Pricing, Plans

Todo aislado por company/tenant.

Pensarse antes del backend comercial definitivo.

=====================================================================
## 49. MULTIIDIOMA

Diseñar desde ahora para: English, Spanish, Portuguese
No dejar strings hardcoded para siempre.

=====================================================================
## 50. RESPONSIVE

Primera prioridad: Android phone (~400 × 824)
Después: tablets, iPad 10th gen 10.9", Android tablets

No sacrificar experiencia móvil.

=====================================================================
## 51. ROADMAP DE CONSTRUCCIÓN RECOMENDADO

### P0 — Terminar el núcleo técnico
- Fase 7: Cerrar OCR físico correctamente
- Fase 8: Inspection → Finding → WorkItem
- Fase 9: Parts + Inventory
- Fase 10: No Access / Stop Work / Need Help / Final Verification

### P1 — Negocio
- Coverage, Membership, Home Warranty, Pricing, Proposal v2, 
  Parts approval

### P2 — Dinero
- Invoice, Payments, Settlement, Refund, Receipt, Reconciliation

### P3 — Sincronización
- Auth server, Company, Users, Customers, Properties, Equipment, 
  Jobs, Sync engine, Conflict resolution

### P4 — Ecosistema
- Control Center, Customer Portal/App, Contractors, Routes, Maps, 
  Push notifications, Live tracking

### P5 — Inteligencia
- OCR avanzado, manual lookup, model lookup, error-code lookup, 
  diagnostic assistant, parts recommendations, predictive maintenance

=====================================================================
## 52. FLUJO COMPLETO RESUMIDO

CLIENTE → PROPERTY → SERVICE REQUEST → JOB → DISPATCH → TECHNICIAN 
→ EN ROUTE → ARRIVAL → EQUIPMENT → NAMEPLATE OCR → HISTORY 
→ INSPECTION → MEASUREMENTS → FINDINGS → DIAGNOSIS → COVERAGE 
→ WORK ITEMS → PARTS → PRICING → GOOD/BETTER/BEST → CUSTOMER 
APPROVAL → SIGNATURE → PARTS/HOLD/RETURN VISIT → REPAIR → FINAL 
VERIFICATION → BEFORE/AFTER EVIDENCE → PAYMENT → SETTLEMENT 
→ CUSTOMER SIGN-OFF → INVOICE → SERVICE REPORT → COMPLETED 
→ PERMANENT HISTORY → REVIEW/FOLLOW-UP/MAINTENANCE

Alrededor:
AUTH, AUDIT, OFFLINE ROOM, SYNC, NOTIFICATIONS, DISPATCH, INVENTORY, 
PRICING, SECURITY, ANALYTICS

=====================================================================
## REGLA FINAL

Cada fase nueva debe encajar dentro de este flujo, reutilizar lo 
que ya existe y acercarnos a este producto final.

NO construir 50 módulos separados sin conexión.

=====================================================================
FIN DEL FLUJO MAESTRO
=====================================================================
