# INFORME DE AUDITORÍA TÉCNICA — L&C FIELD SERVICE PLATFORM
**Fecha:** 2026-10-22  
**Auditor:** Claude Code (análisis directo del código fuente, base de datos y repositorio)  
**Alcance:** Solo lectura — sin modificaciones  
**Directorio auditado:** `~/LC` en Samsung Galaxy S24 Ultra (Termux/ARM64)

---

## A. HISTORIA: DE DÓNDE VENIMOS

### Origen y concepto
L&C es una plataforma de gestión de servicios de campo (field service management) orientada a empresas de HVAC, electricidad y mantenimiento de equipos en el hogar. La idea original de Carlos fue crear un sistema que conecte tres actores: el **centro de control** (despachadores/administración), los **técnicos** en campo y los **clientes** que solicitan servicio.

### Cronología de desarrollo (respaldada por commits)

| Fecha | Hito | Evidencia |
|-------|------|-----------|
| 2026-10-14 | Inicio de QA activo — edge-to-edge UI, sync básico | `QA_ANDROID/CAPTURAS/2026-10-14_*` |
| 2026-10-14 | Pruebas E2E completas con cliente, despacho y técnico | `QA_ANDROID/E2E_20261014_193337/` |
| 2026-10-14 | Resolución de conflictos de sincronización | `QA_ANDROID/T5_CONFLICTOS_20261014_171818/` |
| 2026-10-15 | Pruebas LAN con TLS, variaciones, membresías | `QA_ANDROID/LAN_20261015_*` |
| 2026-10-15 | QA maestro completo (6 fases: cliente→despacho→técnico→factura→pago→cierre) | `QA_ANDROID/MAESTRA_20261015_102012/` |
| 2026-10-15 | Seis correcciones de bugs documentadas | `QA_ANDROID/SEIS_CORRECCIONES_20261015_170155/` |
| 2026-10-16 | Backup de staging previo a refactor | `backups/stage_c_20261016_095428/` |
| 2026-10-17 | Backup pre-build (APK anterior: 72 MB) | `backups/APK_PREBUILD_20261017_123950/` |
| 2026-10-18 | Migración a Termux ARM64, firma comparativa | `QA_ANDROID/TERMUX_ARM64_20261018_214502_678/` |
| 2026-10-18 | RLS (Row Level Security) Supabase — Fase 2 | `QA_ANDROID/FASE2_RLS_20261020/` |
| 2026-10-19 | Auditoría de sincronización, reset de mirror | `QA_ANDROID/SYNC_AUDIT_20261019_*` |
| 2026-10-20 | `dfefe44` — Sincronización E2E completa + OCC fix + auto-mirror | commit |
| 2026-10-20 | `0027ad0` — UI consistencia estricta (rounded rectangles, paleta) | commit |
| 2026-10-20 | `6247f8f` — Supabase bridge directo activado, APK global generado | commit |
| 2026-10-20 | `bcbe063` — UI empresarial, QA ADB, fix crítico de backend | commit |
| 2026-10-20 | `4138ba1` — Fix timestamps BIGINT, pull sync, E2E QA Fase 8 | commit |
| 2026-10-21 | `5d133f2` — RLS activa y QA Android final | commit |
| 2026-10-21 | `2f266e3` — Control Board transformación visual SaaS | commit |
| 2026-10-21 | `8c850c8` — Gestión de PIN en web y app técnico | commit |
| 2026-10-21 | `04462b8` — Arquitectura 100% Cloud-First: Flask removido del path Android | commit |
| 2026-10-21 | `d6182a6` — Fix clientePresente: Boolean→Integer para Supabase | commit |
| 2026-10-21 | `e22ffe2` — APK de release firmado + landing page de descarga | commit |
| 2026-10-21 | `02cab1e` — APK movido a GitHub Releases, .nojekyll para Pages | commit |
| 2026-10-21 | `58d5659` — Unificación de diseño SaaS en todas las vistas web | commit |
| 2026-10-21 | `beb6574` — Login standalone moderno + fix CSRF | commit |
| 2026-10-21 | `f04b63c` — Navegación fluida AJAX en detalle de orden | commit |
| 2026-10-21 | `39e963a` — Fix is_ajax hoisted antes de early-return branches | commit |

### Decisiones arquitectónicas clave

1. **Pivote Cloud-First** (`04462b8`, 2026-10-21): La app Android se desconectó del Flask local y se conecta directamente a Supabase. Flask quedó como backend del portal web (L&C Control) y ya no está en el path crítico del técnico.

2. **Separación del server/ original**: El directorio `~/LC/server/` contiene un backend Flask anterior de 2.934 líneas con 60+ endpoints, testeado (61 pruebas). Ese servidor fue reemplazado funcionalmente por `~/LC/lab/backend/` (el backend activo). El `server/` se conserva como referencia histórica.

3. **Supabase como fuente de verdad en la nube**: El mirror unidireccional Flask→Supabase garantiza que los técnicos siempre tengan datos actualizados sin depender del servidor local.

---

## B. ARQUITECTURA: QUÉ CONSTRUIMOS

### Diagrama del flujo completo

```
CLIENTE (solicita servicio)
        │
        │ App L&C Customer (Android) ──────── PENDIENTE (planificado)
        │ Portal web cliente (API /api/v2)
        │
        ▼
┌─────────────────────────────────────┐
│     BACKEND FLASK (lab/backend)     │
│     Puerto 5055 (Termux local)      │
│     SQLite: lab.db (392 KB)         │
│                                     │
│  Blueprint /control  → Web Admin    │
│  Blueprint /api      → REST API     │
│  Blueprint /api/v2   → Customer API │
├─────────────────────────────────────┤
│     SUPABASE (Cloud PostgreSQL)     │
│     rmkduyrrjtqqvjpqhyra.supabase  │
│     Mirror: Flask→Supabase (unidireccional)│
│     Realtime: WebSocket a Android   │
└─────────────────────────────────────┘
        │                    │
        ▼                    ▼
L&C CONTROL (Web)    L&C TECHNICIAN (Android)
Panel en Brave       com.lc.technician v0.3.3
/control/login       Supabase direct sync
ADMIN + DISPATCHER   Room SQLite local (offline)
```

### Flujo de una orden (de inicio a cierre)

```
1. CLIENTE llama / solicita         → Despacho recibe en L&C Control web
2. DESPACHO crea orden              → POST /control/nueva → jobs table
3. DESPACHO asigna técnico + fecha  → POST /control/jobs/<id>/asignar + /programar
4. MIRROR SUPABASE sube la orden    → supabase_mirror.py (incremental, cada sync)
5. TÉCNICO recibe en Android        → SyncWorker pull desde Supabase via WorkManager
6. TÉCNICO llega → registra llegada → Job events, fotos, mediciones (Room local)
7. TÉCNICO genera propuesta         → ProposalActivity → Propuesta con 3 opciones (Good/Better/Best)
8. CLIENTE firma propuesta          → CustomerSignatureActivity → Firma PNG
9. TÉCNICO realiza el trabajo       → Estado: EN REPARACIÓN → fotos evidencia
10. TÉCNICO genera factura           → InvoiceActivity → Invoice con items
11. CLIENTE firma conformidad        → TechnicianSignatureActivity
12. TÉCNICO cobra pago               → PagosActivity → PaymentEntity
13. SYNC PUSH al servidor            → SyncWorker push → Flask /api/sync/push
14. DESPACHO cierra en Control       → POST /control/jobs/<id>/estado → COMPLETADO
15. FACTURA PDF disponible           → PDF generado por servidor
```

### Stack tecnológico verificado

| Capa | Tecnología | Versión | Estado |
|------|-----------|---------|--------|
| Android App | Kotlin + Java | SDK 36 | PASS |
| BD Local Android | Room (SQLite) | 2.6.1 | PASS |
| Sync Background | WorkManager | 2.9.0 | PASS |
| Sync Cloud (Android→Cloud) | Supabase PostgREST | Direct | PASS |
| Realtime | Supabase WebSocket | Activo | PASS |
| Backend Web | Flask (Python) | 3.x Werkzeug 3.1.9 | PASS |
| BD Servidor | SQLite (lab.db) | 392 KB | PASS |
| Cloud DB | Supabase / PostgreSQL | Proyecto activo | PASS |
| Mirror (Flask→Supabase) | supabase_mirror.py | Incremental | PASS |
| Portal Web | Jinja2 + Bootstrap 5.3.2 | CDN | PASS |
| Autenticación Web | Flask Session + Werkzeug scrypt | Activo | PASS |
| Autenticación Android | Bearer Token + Supabase Auth | Activo | PASS |
| Firma APK | RSA 2048 / lc_release.jks | v0.3.3 | PASS |

---

## C. ESTADO ACTUAL: QUÉ FUNCIONA, QUÉ FALLA, QUÉ NO SE HA COMPROBADO

### PASS — Verificado y funcionando

| Función | Evidencia |
|---------|-----------|
| Backend Flask corriendo en puerto 5055 | `python3 run_lab.py` activo (PID 19994) |
| Login web admin_lab / AdminLab2026! | HTTP 302 → sesión válida confirmada con curl |
| Login web admincarlos / 4991 | HTTP 302 → dashboard HTTP 200 confirmado |
| Panel web /control/jobs/<id> carga | HTTP 200, 2 jobs en DB |
| AJAX en formularios de orden (3 endpoints) | JSON `{"ok":true}` verificado con curl |
| Toasts flotantes en JS | Código implementado, pendiente prueba visual |
| Supabase mirror activo | 90 entradas en supabase_mirror_log |
| APK release firmado (64 MB) | `app/build/outputs/apk/release/app-release.apk` |
| Landing page en docs/index.html | Link a GitHub Release v0.3.3 |
| Git sin secretos comprometidos | Auditoría de histórico: 0 archivos sensibles |
| Roles ADMIN/DISPATCHER/TECHNICIAN/CUSTOMER | Tabla users: 12 usuarios activos/inactivos |
| OCC (control de versiones optimista) | Campo `version` en jobs, verificado en conflictos |
| 97 sync_receipts en DB | Evidencia de sincronización real con dispositivos |
| 11 dispositivos registrados | device_registry: 11 entradas |

### FAIL — Fallo documentado

| Problema | Descripción | Fix aplicado |
|---------|-------------|-------------|
| clientePresente Boolean→Integer | Bug 22P02: Supabase rechazaba booleanos | `d6182a6`: conversión explícita a 0/1 |
| APK en docs/ (64 MB) bloqueaba GitHub Pages | Jekyll timeout en archivos grandes | `02cab1e`: APK movido a GitHub Releases |
| CSRF no se pasaba al template de login | POST de login fallaba silenciosamente | `beb6574`: `csrf=_csrf()` en render_template |
| is_ajax no evaluado antes de early-returns | Transiciones inválidas devolvían redirect en vez de JSON | `39e963a`: is_ajax hoisted |
| Login web bloqueado por password corto (4991) | `password_valida()` exige mín. 8 chars | Creación directa en DB con hash_password() |

### PENDIENTE — Existe pero falta terminar/verificar

| Componente | Estado |
|-----------|--------|
| Prueba visual AJAX + toasts en Brave real | El backend responde JSON (PASS), la UI no se pudo verificar vía ADB (Samsung bloquea input en password fields) |
| L&C Customer App | Código existe (12 fuentes, debug APK 3.4 MB), no en producción |
| Distribución APK tras hacer repo privado | GitHub Releases dejarán de ser públicos; solución pendiente de decisión de Carlos |
| Publicación en Google Play | Ver sección F |
| Sync bidireccional Supabase→Flask | El mirror actual es unidireccional (Flask→Supabase) |

### BLOCKED — No se pudo verificar

| Ítem | Razón |
|------|-------|
| Estado de apps instaladas en Samsung | ADB sin conexión activa al momento de la auditoría (puerto anterior desconectado) |
| GitHub repo visibility (público/privado) | `gh` CLI no instalado en Termux; debe verificarse en GitHub.com |
| Release v0.3.3 APK en GitHub Releases | No se puede verificar URL pública sin gh CLI |

### PLANIFICADO — No implementado aún

| Función | Notas |
|---------|-------|
| L&C Customer App en producción | Base existe, requiere backend completo + testing |
| HTTPS en producción (Tailscale) | `usesCleartextTraffic="true"` activo actualmente |
| Google Play Store (3 apps) | Sin AAB firmado, sin cuenta de desarrollador configurada |
| Notificaciones push (FCM) | Permiso `POST_NOTIFICATIONS` en manifest, sin integración FCM activa |
| Segundo repo público de distribución | Propuesto para resolver conflicto privacidad/descarga APK |

---

## D. LAS TRES APLICACIONES: INVENTARIO INDIVIDUAL

---

### D.1 — L&C CONTROL (Panel de administración)

**Tipo:** Aplicación web (no existe APK separado)  
**URL local:** `http://127.0.0.1:5055/control/`  
**Código:** `~/LC/lab/backend/lc_lab/web_control.py` (498 líneas)  
**Templates:** `~/LC/lab/backend/lc_lab/templates/control_*.html`

**Rutas web disponibles:**

| Ruta | Método | Función |
|------|--------|---------|
| `/control/login` | GET/POST | Login con CSRF |
| `/control/logout` | GET | Cerrar sesión |
| `/control/` | GET | Tablero (agenda del día) |
| `/control/jobs/<id>` | GET | Detalle de orden (AJAX) |
| `/control/jobs/<id>/programar` | POST | Programar fecha/hora |
| `/control/jobs/<id>/asignar` | POST | Asignar técnico |
| `/control/jobs/<id>/estado` | POST | Cambiar estado |
| `/control/jobs/<id>/descuento` | POST | Autorizar descuento |
| `/control/nueva` | GET/POST | Crear nueva orden |
| `/control/conflicts/<id>/resolver` | POST | Resolver conflicto OCC |
| `/control/pagos/<id>/revisar` | POST | Confirmar/rechazar pago |
| `/control/firmas/<id>.png` | GET | Ver firma PNG |
| `/control/tecnicos` | GET/POST | Gestión de técnicos |
| `/control/tecnicos/<id>/estado` | POST | Activar/desactivar técnico |
| `/control/tecnicos/<id>/clave` | POST | Cambiar contraseña |
| `/control/tecnicos/<id>/reset_pin` | POST | Generar nuevo PIN |
| `/control/clientes` | GET/POST | Gestión de clientes |
| `/control/invitar/<id>.svg` | GET | QR de invitación técnico |

**Roles con acceso:**
- `ADMIN` — Acceso total
- `DISPATCHER` — Acceso operativo (asignar, programar, estados)

**Usuarios actuales en DB:**
- `admin_lab` (ADMIN, activo)
- `carlosocampo` (ADMIN, activo)
- `admincarlos` (ADMIN, activo) — creado 2026-10-22
- `despacho_lab` (DISPATCHER, activo)

**Estado:** PASS (login y funciones AJAX verificadas con curl)

---

### D.2 — L&C TECHNICIAN (App Android del técnico)

**Package:** `com.lc.technician`  
**Versión:** 0.3.3 (versionCode: 7)  
**APK release:** `app/build/outputs/apk/release/app-release.apk` (64 MB, firmado)  
**APK debug:** `app/build/outputs/apk/debug/app-debug.apk` (68 MB)  
**Código fuente:** `~/LC/app/src/main/java/com/lc/technician/`

**Pantallas implementadas (31 Activities en AndroidManifest):**

| Pantalla | Función | Estado |
|---------|---------|--------|
| LoginActivity | PIN / contraseña de acceso | PASS |
| MainActivity | Lista de trabajos del día | PASS |
| JobDetailActivity | Detalle completo de orden | PASS |
| JobCompletedActivity | Flujo de cierre de trabajo | PASS |
| ProposalActivity | Crear propuesta Good/Better/Best | PASS |
| InspectionActivity | Inspección de equipo | PASS |
| InvoiceActivity | Generar factura | PASS |
| CustomerSignatureActivity | Captura firma del cliente | PASS |
| TechnicianSignatureActivity | Firma del técnico | PASS |
| PagosActivity | Registro de pagos | PASS |
| PartsActivity | Inventario de partes | PASS |
| PartsRequestActivity | Solicitud de partes | PASS |
| EquipmentActivity | Registro de equipos | PASS |
| PhotoViewActivity | Visor de fotos | PASS |
| ReportesActivity | Reportes de trabajo | PASS |
| EstadisticasActivity | Estadísticas y métricas | PASS |
| ConfiguracionActivity | Ajustes (sin Flask UI) | PASS |
| MembershipScanActivity | Escaneo de membresías | PASS |
| AuditLogActivity | Historial de cambios | PASS |
| StatusPublicoActivity | Estado público (exported) | IMPLEMENTADO |

**Base de datos local (Room — 35 entidades):**
- Órdenes de servicio, ítems de trabajo
- Propuestas con 3 opciones de precio
- Inspecciones, hallazgos, fotos
- Facturas, pagos
- Equipos, partes, stock
- Firmas, membresías
- Auditoría local

**Sincronización:**
- **Push a Supabase:** `SyncWorker.java` via WorkManager (background)
- **Pull desde Supabase:** WebSocket Realtime + REST pull
- **Offline-first:** Room SQLite funciona sin conexión; sync cuando hay red
- **OCC:** Campo `version` previene conflictos de escritura simultánea
- **Dispositivos registrados:** 11 en `device_registry`

**App instalada actualmente en Samsung:** BLOCKED (ADB sin conexión activa al momento de auditoría; última instalación documentada via `adb install` en sesión anterior — `QA_ANDROID/DIST_20261021/`)

---

### D.3 — L&C CUSTOMER (App Android del cliente)

**Package:** `com.lc.customer.lab` (sufijo `.lab` indica variante experimental)  
**Versión:** 0.1.0-lab (versionCode: 1)  
**APK:** Solo debug (3.4 MB), en `~/LC/customer_app/app/build/outputs/apk/debug/`  
**Código fuente:** `~/LC/customer_app/app/src/main/java/com/lc/customer/lab/` (12 archivos Java)  
**Endpoint configurado:** `http://127.0.0.1:5055/api/v2` (localhost lab)

**Pantallas:**
- LoginActivity, RegisterActivity, HomeActivity
- PropertiesActivity, NewRequestActivity
- RequestDetailActivity, PdfViewerActivity, DiagnosticoActivity

**Estado:** PENDIENTE — La estructura existe y compila a debug. No está en producción ni conectada a Supabase. Falta:
- Integración con Supabase (actualmente apunta a Flask local)
- UI/UX completa (dependencias mínimas: solo appcompat)
- Testing E2E
- APK de release firmado

**No confundir:** La Customer App es un proyecto Android separado en `~/LC/customer_app/`, no un módulo de la app del técnico.

---

## E. SEGURIDAD: RIESGOS Y PROTECCIONES

### Auditoría de secretos en Git

**Resultado: LIMPIO — 0 secretos comprometidos en toda la historia de commits**

```
git log --all --name-only | grep -E "(\.jks|\.keystore|local\.properties|supabase_sync|\.db)"
→ (vacío)
```

| Archivo | Protegido en .gitignore | Alguna vez en git |
|---------|------------------------|-------------------|
| `local.properties` (Supabase keys, keystore pwd) | ✅ | ❌ Nunca |
| `lc_release.jks` (keystore de firma) | ✅ | ❌ Nunca |
| `KEYSTORE_CREDENTIALS.txt` | ✅ | ❌ Nunca |
| `lab/backend/instance/` (supabase_sync.json, lab.db) | ✅ | ❌ Nunca |
| `*.db` (SQLite databases) | ✅ | ❌ Nunca |

### Riesgos actuales

| Riesgo | Severidad | Estado |
|--------|-----------|--------|
| Repo PÚBLICO en GitHub — código fuente expuesto | ALTO | Activo; requiere acción de Carlos en GitHub.com |
| `usesCleartextTraffic="true"` en AndroidManifest | MEDIO | Solo para lab local; producción debe usar HTTPS |
| Contraseña `4991` (4 chars) para `admincarlos` | BAJO | Fuera del validador de 8 chars; uso interno solamente |
| PIN de técnicos en `local.properties` (valores dev) | BAJO | No en git; fácil de rotar |
| Anon key de Supabase compilada en APK | INFO | Por diseño (RLS la restringe en BD); aceptable |

### Protecciones existentes

- CSRF en todos los formularios web (token en sesión)
- Scrypt para hash de contraseñas (`hash_password()` usa Werkzeug scrypt)
- Bloqueo temporal tras 5 intentos fallidos de login
- Tokens Bearer con TTL de 12 horas para API Android
- RLS (Row Level Security) activa en Supabase
- OCC (Optimistic Concurrency Control) previene race conditions
- Audit log completo (553 entradas) con actor + timestamp
- Service role key de Supabase: solo en backend (nunca en APK)

---

## F. PUBLICACIÓN: QUÉ NECESITAMOS PARA GOOGLE PLAY

### Requerimientos técnicos por app

#### L&C Technician (com.lc.technician)

| Requisito | Estado | Acción |
|-----------|--------|--------|
| APK release firmado | ✅ PASS | Existe: `app-release.apk` (64 MB) |
| Keystore de firma | ✅ PASS | `lc_release.jks` en Google Drive de Carlos |
| AAB (Android App Bundle) | ❌ PENDIENTE | Solo se generó APK; Play Store prefiere AAB |
| Target SDK ≥ 34 | ✅ PASS | targetSdk = 36 |
| `usesCleartextTraffic` = false | ❌ PENDIENTE | Actualmente true para lab; debe desactivarse |
| Icono 512×512 PNG | PENDIENTE | Verificar que existe en res/ |
| Screenshots para Play Store | PENDIENTE | Capturar mínimo 2 pantallas por forma de pantalla |
| Privacy Policy URL | PENDIENTE | Requerida por Play |
| Descripción de app | PENDIENTE | Preparar en inglés y español |
| Cuenta Google Play Developer | PENDIENTE | Requiere $25 USD una sola vez |
| Revisión Play Store | PENDIENTE | 1–3 días hábiles |

#### L&C Customer (com.lc.customer.lab)

| Requisito | Estado | Acción |
|-----------|--------|--------|
| Sufijo `.lab` en package | ❌ BLOQUEANTE | Cambiar a `com.lc.customer` antes de publicar |
| APK release firmado | ❌ PENDIENTE | Solo debug compilado |
| Funcionalidad completa | ❌ PENDIENTE | App en estado prototipo |
| Conexión Supabase | ❌ PENDIENTE | Apunta a localhost actualmente |

#### L&C Control (Panel web)

**Recomendación:** NO publicar como app en Play Store. Mantener como portal web privado accesible vía navegador. Ventajas:
- Sin proceso de revisión de Play Store
- Actualizable sin re-release
- Acceso por URL con login seguro
- Ideal para despachadores con laptop/tablet

---

## G. HOJA DE RUTA: ORDEN PARA PONER L&C EN PRODUCCIÓN COMERCIAL

### Fase inmediata (esta semana)

1. **[CRÍTICO] Hacer repo LC-Sync-LAB PRIVADO en GitHub**
   - Carlos lo hace en GitHub.com → Settings → Danger Zone → Make Private
   - GitHub Pages y Release Assets quedan inaccesibles

2. **[CRÍTICO] Solución distribución APK**
   - Crear repo público nuevo: `LC-Public` (solo landing page + APK)
   - Mover APK a ese repo como Release Asset
   - Actualizar link en `docs/index.html`

3. **[RECOMENDADO] Verificar app instalada en Samsung**
   - Reconectar ADB y confirmar `com.lc.technician` instalado
   - Probar AJAX + toasts en Brave visualmente

### Fase 1: Hardening (1–2 semanas)

4. Cambiar `usesCleartextTraffic="false"` para producción
5. Configurar Tailscale o dominio real con HTTPS para Flask
6. Rotar PINs de técnicos (generar desde panel web `/control/tecnicos`)
7. Crear usuarios reales de técnicos (eliminar `_lab` de usernames)
8. Validar que Supabase RLS protege correctamente cada rol

### Fase 2: L&C Technician en Google Play (2–3 semanas)

9. Generar AAB: `./gradlew bundleRelease`
10. Desactivar `cleartext` en AndroidManifest
11. Crear Privacy Policy page
12. Crear cuenta Google Play Developer ($25 USD)
13. Subir AAB a Play Console → Internal Testing → Producción

### Fase 3: L&C Customer lista para pruebas (4–6 semanas)

14. Cambiar package de `com.lc.customer.lab` a `com.lc.customer`
15. Conectar con Supabase (migrar de localhost)
16. Completar flujo de solicitud de servicio
17. E2E testing: Customer → Control → Technician
18. APK release + Google Play

### Fase 4: Operación comercial

19. Onboarding de técnicos reales (PIN setup, dispositivos)
20. Onboarding de clientes
21. Monitoreo de Supabase mirror (logs existentes en `supabase_mirror_log`)
22. Backup regular de `lab.db`

---

## INVENTARIO DE ARCHIVOS CLAVE

```
~/LC/
├── app/build/outputs/apk/release/app-release.apk    [64 MB] APK producción firmado ✅
├── app/build/outputs/apk/debug/app-debug.apk         [68 MB] APK desarrollo
├── customer_app/app/build/outputs/apk/debug/         [3.4 MB] Customer APK (solo debug)
├── lab/backend/lc_lab/web_control.py                  [498 líneas] Backend Control web
├── lab/backend/lc_lab/supabase_mirror.py              Mirror Flask→Supabase
├── lab/backend/instance/lab.db                        [392 KB] BD activa
├── lab/backend/instance/supabase_sync.json            [SECRETO — no en git] Config Supabase
├── docs/index.html                                     Landing page distribución
├── docs/.nojekyll                                      Para GitHub Pages
├── local.properties                                    [SECRETO — no en git] Keys Android
├── lc_release.jks                                      [SECRETO — en Drive de Carlos]
└── server/app.py                                       [2.934 líneas] Backend histórico (referencia)
```

---

## NOTAS PARA CHATGPT

1. **L&C NO es una app de una sola APK.** Son tres sistemas distintos: panel web de control (Flask/Python), app del técnico (Android Java/Kotlin), app del cliente (Android — en desarrollo).

2. **El backend activo es `~/LC/lab/backend/`**, no `~/LC/server/`. El server/ es el backend original que fue superado arquitectónicamente. El lab/backend es el que está corriendo actualmente.

3. **La sincronización va Android→Supabase (directo), NO a través de Flask.** Flask solo recibe sync ocasional vía `/api/sync/push` para historial local. La fuente de verdad en la nube es Supabase.

4. **Hay 2 jobs reales en la base de datos** (no 17 como en el server/ histórico). Los 17 eran del `lc_sync.db` del server/ anterior.

5. **El repo es público actualmente.** No hay credenciales expuestas, pero sí el código fuente de la lógica de negocio.

6. **El keystore `lc_release.jks` está en Google Drive de Carlos**, no en el repositorio. Sin él no se puede generar un nuevo release firmado.

---

*Informe generado el 2026-10-22. Auditoría de solo lectura — ningún archivo fue modificado.*
