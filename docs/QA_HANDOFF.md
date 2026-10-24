# L&C — QA Handoff Report (Sesión 5: Integración y QA Real)
**Fecha:** 2026-10-24 (sesión 5)  
**Branch:** feature/customer-visual-v2  
**Ejecutado por:** Claude Code (autónomo)  
**Dispositivo:** SM-S928U · Serial R5CX12Y1XWA · ADB wireless 10.0.0.121:36639  
**PR:** https://github.com/carlosocampo1034-a11y/LC-Sync-LAB/pull/1

---

## Resumen ejecutivo — Sesión 5 (Integración y QA Real)

| Categoría | Estado |
|---|---|
| PR creado — feature/customer-visual-v2 → main | ✓ PR #1 abierto |
| GitHub — branch sincronizado en `0f020e5` | ✓ PASS |
| Technician: wizard de configuración completo (5 pasos) | ✓ PASS |
| Technician: PIN login validado contra backend v1 | ✓ PASS |
| Technician: LAB session inyectada (tec-f5-01 / v2) | ✓ PASS |
| Customer: registro de cuenta via API | ✓ PASS |
| Customer: sesión inyectada y app en estado autenticado | ✓ PASS |
| Customer: orden creada (job 8000000000011 HVAC) | ✓ PASS |
| Control: orden asignada a tec-f5-01 via dispatch | ✓ PASS |
| Technician sync pull: job recibido (v2, PENDIENTE) | ✓ PASS |
| Ciclo servicio: EN RUTA→LLEGUÉ→EN REPARACIÓN→COMPLETADO | ✓ 5/5 ACCEPTED |
| Customer: detalle muestra COMPLETADA + historial completo | ✓ PASS |
| Control board: COMPLETADOS=1 confirmado | ✓ PASS |
| Supabase mirror: job 8000000000011 sincronizado | ✓ PASS |
| Logcat: 0 crashes / 0 errores en ambas apps | ✓ PASS |

**Total sesión 5: 15/15 PASS · 0 FAIL**

### Issues encontrados (no bloqueantes)
| # | Issue | Severidad | Acción |
|---|---|---|---|
| I1 | ADB `input text` falla con `!` y espacios en campos contraseña | Info | Usar API o inyección de sesión como workaround |
| I2 | Customer app: dropdown ¿Dónde? no recarga propiedades nuevas sin reiniciar activity | Baja | Propiedad creada vía API, reinicio resuelve |
| I3 | Technician: pantalla principal muestra datos v1 por defecto; v2 requiere LAB override | Info | Esperado por diseño — LAB y producción son backends separados |
| I4 | Supabase sync_receipts: error datestyle — timestamps 2026 fuera de rango Supabase | Media | Fix en backend: convertir ms → ISO8601 antes de enviar a Supabase |

### Pendientes para producción
| # | Pendiente |
|---|---|
| P-HTTPS | Conectividad HTTPS estable — tunnel público pendiente |
| P-LOGIN-UI | Login Customer desde UI sin workaround de sesión inyectada |
| P-SUPABASE | Fix datestyle en sync_receipts → Supabase |
| P-MOVIL | Pruebas con datos móviles / redes distintas al WiFi local |
| P-CORS | CORS allowlist producción en /guest/quote |
| P-REDIS | Redis para rate-limit en producción |

---

## Resumen ejecutivo — Sesión 4 (Instalación ADB)

| Categoría | Estado |
|---|---|
| Verificación paquetes APK antes de instalar | ✓ PASS |
| Detección incompatibilidad de firmas | ✓ INFO (ver §9) |
| Instalación Technician DEV (`com.lc.technician.dev`) | ✓ PASS |
| Instalación Customer LAB (`com.lc.customer.lab`) | ✓ PASS |
| QA visual — Technician: setup wizard correcto | ✓ PASS |
| QA visual — Customer: home screen dark mode | ✓ PASS |
| Logcat — 0 errores en ambas apps | ✓ PASS |
| Regreso a Termux verificado (screenshot) | ✓ PASS |

**Total sesión 4: 8/8 PASS · 0 FAIL**

### Pendiente (sesión 5+)
| # | Prueba pendiente | Prioridad |
|---|---|---|
| PEnd-1 | Login completo en Technician DEV desde UI | Alta |
| PEnd-2 | Login + solicitud de servicio en Customer LAB desde UI | Alta |
| PEnd-3 | Sincronización Technician ↔ backend desde dispositivo real | Alta |
| PEnd-4 | Funcionamiento entre redes (WiFi distintas / datos móviles) | Media |
| PEnd-5 | Limpieza segura de datos locales post-QA | Media |

---

## Resumen ejecutivo — Sesión 3 (Cloud Sync)

| Categoría | Estado |
|---|---|
| Fix "Sin asignar" — LabSession.resolverNombre | ✓ CORREGIDO + BUILD OK |
| Customer App — API_BASE desde BuildConfig | ✓ PASS |
| Customer App — URL configurable en Settings | ✓ PASS |
| ARM64 build — aapt2FromMavenOverride | ✓ PASS |
| E2E T1: HVAC ciclo completo (sync v2) | ✓ PASS |
| E2E T2: Plomería multi-event push | ✓ PASS |
| E2E T3: Idempotency replay=True | ✓ PASS |
| E2E T4: Customer auth → request → board | ✓ PASS |
| E2E T5: Offline bundle recovery | ✓ PASS |
| Cleanup post-E2E | ✓ PASS |

**Total sesión 3: 10/10 PASS · 0 FAIL**

---

## Resumen ejecutivo — Sesión 2 (QA Original)

| Categoría | Estado |
|---|---|
| Limpieza de datos ficticios | ✓ PASS |
| Seguridad de credenciales | ✓ PASS |
| Corrección seed.py | ✓ PASS |
| E2E HVAC completo (5 pasos) | ✓ 5/5 PASS |
| E2E Plomería completo (5 pasos) | ✓ 5/5 PASS |
| Control Board — consistencia | ✓ PASS |
| Customer App — arranque limpio | ✓ PASS |
| Technician App — setup inicial | ✓ PASS |
| Cleanup post-QA | ✓ PASS |
| Backend sin errores | ✓ PASS |

**Total sesión 2: 10/10 categorías PASS · 0 FAIL**

---

## 1. Limpieza operativa (sesión anterior)

Ejecutada en `2026-10-24 07:58`. Respaldo íntegro en:  
`backups/limpieza_20261024_075811/` (lab.db, lc_sync.db, creds, supabase_sync)

| Tabla | Filas eliminadas |
|---|---|
| jobs | 29 |
| customers | 24 |
| properties | 10 |
| equipment | 3 |
| photos | 7 |
| job_history | 265 |
| audit_log | 691 |
| sync_receipts | 100 |
| supabase_mirror_log | 219 |
| tokens | 43 |
| device_registry | 15 |
| Archivos físicos (uploads/) | 42 archivos |

---

## 2. Correcciones de código

### seed.py — eliminación de técnicos automáticos
**Problema:** Al arrancar, `seed.py` creaba `tecnico_lab` y `tecnico_lab2` automáticamente,  
contradiciendo el estado de "sistema recién instalado".  
**Fix:** `USUARIOS_GESTION` contiene solo `admin_lab` y `despacho_lab`. Los técnicos  
se crean manualmente desde el portal de control.  
**Estado:** ✓ Verificado — 0 técnicos automáticos tras reinicio.

### Credenciales rotadas
**Problema:** Contraseñas de lab aparecieron en salida de consola (sesión anterior).  
**Fix:** Rotación completa via `generate_password_hash` + `secrets.token_urlsafe(18)`.  
Cuentas personales (carlosocampo, admincarlos) restauradas desde backup.  
Archivo: `instance/credenciales_lab.txt` (modo 600).  
Tokens anteriores: invalidados (DELETE FROM tokens).

### API route — campo de asignación
**Problema encontrado:** Endpoint `POST /api/v2/dispatch/jobs/{id}/assign` espera  
`technicianId` (camelCase), no `technician_code`.  
**Impacto:** Solo afecta clientes que llaman al API directamente; las apps usan el SDK correcto.  
**Estado:** Documentado. No requiere cambio de backend (es el contrato correcto del API).

---

## 3. Tests E2E — 5 pruebas completas

### Test 1: Orden HVAC — Cliente: María González QA
| Paso | Acción | Resultado |
|---|---|---|
| T1.1 | Guest crea solicitud HVAC via `/guest/quote` | ✓ job_id=8000000000001 PENDIENTE |
| T1.2 | Dispatch asigna a tec_qa_hvac (tec-qa-01) | ✓ ASSIGNED, technicianId=tec-qa-01 |
| T1.3 | Técnico sync pull | ✓ 1 job recibido |
| T1.4 | Ciclo: EN RUTA → LLEGUÉ → EN REPARACIÓN → COMPLETADO | ✓ 4/4 estados OK |
| T1.5 | Nota diagnóstico insertada | ✓ event_id creado, actor=tec_qa_hvac |

### Test 2: Orden Plomería — Cliente: Roberto Díaz QA
| Paso | Acción | Resultado |
|---|---|---|
| T2.1 | Guest crea solicitud Plomería via `/guest/quote` | ✓ job_id=8000000000003 PENDIENTE |
| T2.2 | Dispatch asigna a tec_qa_plom (tec-qa-02) | ✓ ASSIGNED, technicianId=tec-qa-02 |
| T2.3 | Técnico sync pull | ✓ 1 job recibido |
| T2.4 | Ciclo: EN RUTA → LLEGUÉ → EN REPARACIÓN → COMPLETADO | ✓ 4/4 estados OK |
| T2.5 | Nota diagnóstico insertada | ✓ event_id creado, actor=tec_qa_plom |

### Tests 3–5: Consistencia de datos entre las tres apps
| App | Verificación | Estado |
|---|---|---|
| Control Board `/control/` | 2 COMPLETADOS, 0 activas, 0 sin fecha | ✓ PASS (screenshot + HTML) |
| Control Board — Job HVAC | María González QA / tec-qa-01 / COMPLETADO | ✓ PASS |
| Control Board — Job Plomería | Roberto Díaz QA / tec-qa-02 / COMPLETADO | ✓ PASS |
| Customer App | Welcome screen sin sesión (limpia) | ✓ PASS |
| Technician App | Setup inicial (correcto para instalación limpia) | ✓ PASS |
| API `/api/v2/dispatch/board` | `{"COMPLETADOS":2}` todas las demás en 0 | ✓ PASS |

---

## 4. Evidencias visuales (ADB screenshots)

| Archivo | Contenido |
|---|---|
| `clean_customer_sm.png` | Customer app — welcome sin datos |
| `clean_technician_sm.png` | Technician app — setup inicial |
| `clean_control_sm.png` | Control — pantalla login limpia |
| `qa_ctrl_board_sm.png` | Control board — 2 Completados visibles |
| `qa_ctrl_board2_sm.png` | Control board scroll — ambas órdenes COMPLETADO |
| `qa_tech_session_sm.png` | Technician app — setup (instalación limpia) |

---

## 5. Estado final del sistema

### Base de datos (lab.db)
| Verificación | Estado |
|---|---|
| jobs | 0 filas |
| customers | 0 filas |
| properties, equipment, photos | 0 filas cada una |
| job_history, audit_log | 0 filas |
| tokens, device_registry | 0 filas |
| Próximo job ID | 8000000000001 |
| job_seq counter | 0 |

### Usuarios activos
| ID | Usuario | Rol |
|---|---|---|
| 1 | admin_lab | ADMIN |
| 2 | despacho_lab | DISPATCHER |
| 15 | carlosocampo | ADMIN |
| 20 | admincarlos | ADMIN |

### Backends
| Backend | Puerto | Estado |
|---|---|---|
| LAB v2 | 5055 | ✓ UP |
| v1 server | 5000 | ✓ UP |

### Supabase / GitHub
- Mirror: unidireccional Flask→Supabase, `incluir_historicas=false`
- Datos eliminados localmente NO reaparecen vía sync
- `instance/` excluido de git (`.gitignore`)
- Credenciales nunca en commits

---

## 9. Sesión 4 — Instalación ADB (2026-10-24)

### Identificadores de paquete verificados

| APK (build local) | Paquete | Variante |
|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `com.lc.technician.dev` | LAB debug — NO es producción |
| `customer_app/app/build/outputs/apk/debug/app-debug.apk` | `com.lc.customer.lab` | LAB debug — NO es producción |

> **Distinción producción vs LAB:** Los sufijos `.dev` y `.lab` garantizan coexistencia con versiones de producción. No comparten datos ni se sobreescriben entre sí.

### Incompatibilidad de firmas (INFO)

`adb install -r` falló con `INSTALL_FAILED_UPDATE_INCOMPATIBLE` en ambas apps.  
**Causa:** Los APKs anteriores fueron firmados con el debug keystore de otro entorno (Android Studio externo). El keystore activo es `~/.android/debug.keystore` generado en Termux.  
**Acción:** Desinstalación limpia + instalación fresca. Solo se perdió caché local (tokens de sesión y preferencias); los datos de negocio viven en el backend (lab.db, lc_sync.db).  
**Para el futuro:** Mientras se use el mismo Termux como entorno de build, el keystore es estable y no volverá a ocurrir.

### Resultados de instalación

| Paso | Comando | Resultado |
|---|---|---|
| Verificar instaladas | `pm list packages \| grep com.lc` | `com.lc.technician.dev`, `com.lc.customer.lab` presentes |
| Technician — install -r | `adb install -r app-debug.apk` | FAIL — firma incompatible |
| Technician — uninstall | `adb uninstall com.lc.technician.dev` | Success |
| Technician — install | `adb install app-debug.apk` | **Success** |
| Customer — install -r | `adb install -r app-debug.apk` | FAIL — firma incompatible |
| Customer — uninstall | `adb uninstall com.lc.customer.lab` | Success |
| Customer — install | `adb install app-debug.apk` | **Success** |

### QA Visual

| App | Pantalla observada | Estado |
|---|---|---|
| LC Technician DEV | Setup wizard — "COMENZAR CONFIGURACIÓN" (instalación limpia, correcto) | ✓ PASS |
| LC Customer LAB | Home dark — categorías Plomería/Electricidad/HVAC/Hogar, Órdenes, Chat, Ajustes | ✓ PASS |
| Termux | Sesión Claude Code en primer plano tras `am start com.termux/.HomeActivity` | ✓ PASS |

### Logcat post-instalación

| App | Errores | Excepciones | Crashes | Ruido normal |
|---|---|---|---|---|
| `com.lc.technician.dev` | 0 | 0 | 0 | Samsung GameManager (ok) |
| `com.lc.customer.lab` | 0 | 0 | 0 | Samsung GameManager (ok) |

### Evidencias (screenshots ADB)

| Archivo | Contenido |
|---|---|
| `qa_tech_ready_sm.png` | Technician DEV — setup wizard correcto |
| `qa_cust_install_sm.png` | Customer LAB — home screen dark mode |
| `qa_back_termux_sm.png` | Termux en primer plano verificado |

---

## 10. Sesión 5 — Integración y QA Real (2026-10-24)

### GitHub
- PR #1 creado: `feature/customer-visual-v2` → `main`
- Branch remoto sincronizado en `0f020e5`
- Sin merge a main hasta completar verificaciones de producción

### Supabase vs Flask local — Auditoría de operaciones
| Operación | Supabase | Flask LAB v2 local |
|---|---|---|
| Creación de órdenes (jobs) | ✓ Mirror automático | Fuente de verdad |
| Actualización de estado (sync push) | ✓ Mirror automático | Fuente de verdad |
| Historial de eventos (job_history) | ✓ Mirror automático | Fuente de verdad |
| sync_receipts (log de push) | ✗ Error datestyle (timestamps 2026) | OK local |
| Usuarios/autenticación | ✗ No sincroniza — queda en Flask | Flask |
| Propiedades de clientes | ✗ No sincroniza actualmente | Flask |
| Device registry | ✗ No sincroniza actualmente | Flask |

**Regla activa:** `incluir_historicas=false` — registros eliminados localmente no reaparecen vía sync.

### Permisos por rol verificados (API v2)
| Rol | Endpoint | Resultado |
|---|---|---|
| CUSTOMER | `POST /customer/requests` | ✓ Solo ve sus propias órdenes |
| TECHNICIAN | `GET /sync/pull` | ✓ Solo recibe órdenes asignadas a su technicianId |
| ADMIN/DISPATCHER | `GET /dispatch/board` | ✓ Ve todas las órdenes |
| Sin autenticar | Cualquier endpoint protegido | ✓ 401 Unauthorized |

### Evidencias (screenshots sesión 5)
| Archivo | Contenido |
|---|---|
| `qa_wiz0_sm.png` → `qa_wiz4_sm.png` | Wizard Technician — 5 páginas |
| `qa_tech_logged2_sm.png` | Technician — login con PIN exitoso |
| `qa_cust_in_sm.png` | Customer — sesión activa "Hola, Cliente" |
| `qa_cust_detail_sm.png` | Customer — detalle orden COMPLETADA + historial |
| `qa_tech_f5_main_sm.png` | Technician — pantalla principal post-login |

### Seguridad — verificaciones sesión 5
- `supabase_sync.json` y credenciales: fuera de git (instance/ excluido)
- URL del servidor no visible en pantallas de usuario en Customer app
- Tokens JWT: no aparecen en logcat
- Sesión inyectada via ADB `run-as` (acceso debug): solo en entorno LAB

---

## 6. Pendientes conocidos (actualizado sesión 5)

| # | Issue | Severidad | Estado |
|---|---|---|---|
| P1 | Technician UI: "Sin asignar" en detalle — mapeo Room→display | Media | ✓ CORREGIDO (sesión 3) |
| P2 | CORS allowlist producción en `/guest/quote` | Alta | Pendiente |
| P3 | Redis para rate-limit en producción (actualmente in-memory) | Media | Pendiente |
| P4 | IA real en ChatActivity (actualmente placeholder) | Baja | Pendiente |
| P5 | LoginActivity + RegisterActivity Customer: dark mode incompleto | Baja | Pendiente |
| P6 | `adb input text` falla con caracteres especiales en login UI | Info | Info |
| P7 | inject_lab_session.sh: no escribe `session_active` en `lc_prefs` — tech app muestra setup | Baja | Pendiente |
| P8 | Túnel HTTPS público: cloudflared DNS failure / localhost.run safebrowse.io | Media | Bloqueado |
| P9 | Technician DEV: login UI completo + sync desde dispositivo real | Alta | Pendiente (sesión 5) |
| P10 | Customer LAB: login + solicitud de servicio desde UI real | Alta | Pendiente (sesión 5) |
| P11 | Pruebas entre redes distintas (datos móviles o WiFi diferente) | Media | Pendiente |
| P12 | Limpieza segura de datos locales post-QA en dispositivo | Media | Pendiente |
| P13 | Login Customer via UI (ADB `!` workaround) — sesión inyectada como alternativa | Media | Pendiente |
| P14 | Supabase sync_receipts: fix datestyle ms→ISO8601 | Media | Pendiente |
| P15 | Customer ¿Dónde? dropdown: refrescar propiedades sin reiniciar activity | Baja | Pendiente |
| P16 | Technician v2 sync en pantalla principal sin necesidad de inyección manual | Media | Pendiente |

---

## 8. Sesión 3 — Cloud Sync (2026-10-24)

### Cambios de código
| Archivo | Cambio |
|---|---|
| `LabSession.java` | `nombre()` + `resolverNombre()` — resuelve código→nombre |
| `JobDetailActivity.java` | AsyncTask para mostrar display name en vez de código |
| `customer_app/Api.java` | Usa `BuildConfig.API_BASE` en vez de IP hardcoded |
| `customer_app/SettingsActivity.java` | Campo "URL del servidor" en sección Avanzado |
| `customer_app/app/build.gradle` | Lee `CUSTOMER_API_BASE` de `local.properties` |
| `gradle.properties` | `android.aapt2FromMavenOverride` para builds ARM64 en Termux |

### E2E API Tests (5/5 PASS)
| Test | Descripción |
|---|---|
| T1 | HVAC: guest/quote → assign → sync/pull → EN RUTA → LLEGUÉ → EN REPARACIÓN → COMPLETADO → board |
| T2 | Plomería: multi-event push (3 eventos en un solo request) |
| T3 | Idempotencia: mismo updatedAt → segunda respuesta tiene `replay=True` |
| T4 | Customer auth: register → login → property → request → visible en board |
| T5 | Bundle offline: 3 state changes en un solo sync/push (recovery) |

### APKs construidos e instalados
- `app/build/outputs/apk/debug/app-debug.apk` — Technician DEV `com.lc.technician.dev` (BUILD SUCCESSFUL · INSTALADO sesión 4)
- `customer_app/app/build/outputs/apk/debug/app-debug.apk` — Customer LAB `com.lc.customer.lab` (BUILD SUCCESSFUL · INSTALADO sesión 4)

### Formato correcto del API sync v2
```
POST /api/v2/sync/device  {"deviceId": "..."}   # Registrar dispositivo (1x)
GET  /api/v2/sync/pull?deviceId=...&since=0     # Descargar órdenes
POST /api/v2/sync/push  {                        # Subir cambios
  "deviceId": "...",
  "jobs": [{"id": 8000000000001, "updatedAt": 1729800000, "estado": "EN RUTA", "version": 1}]
}
```
Estados válidos: `PENDIENTE`, `EN RUTA`, `LLEGUÉ`, `EN REPARACIÓN`, `ESPERANDO APROBACIÓN`, `PART HOLD`, `PAUSADO`, `COMPLETADO`, `CANCELADO`

---

## 7. Reglas de seguridad vigentes

- `instance/` en `.gitignore` — BD, credenciales y configuración Supabase nunca en commits
- `credenciales_lab.txt` y `supabase_sync.json`: modo 600
- Credenciales nunca en logs, reportes ni código
- Tokens JWT invalidan en cada rotación de contraseña (DELETE FROM tokens)
- `incluir_historicas=false` — Supabase no re-sincroniza registros eliminados
- seed.py: crea solo cuentas de gestión mínimas, nunca técnicos de prueba automáticos
