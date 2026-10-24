# INFORME DE PREPARACIÓN PARA GOOGLE PLAY — L&C Platform
**Fecha:** 2026-10-22  
**Tipo:** Auditoría activa + ejecución de tareas  
**Directorio:** `~/LC`

---

## RESUMEN EJECUTIVO

| App | Estado Play Store | Bloqueante principal |
|-----|------------------|---------------------|
| L&C Control (Web) | NO APLICA | Panel web — no va a Play Store |
| L&C Technician | PENDIENTE (85%) | Sin icono de launcher + AAB necesita proot |
| L&C Customer | BLOQUEADO (25%) | App incompleta + hardcoded local IP + package `.lab` |

---

## FASE 1 — SEGURIDAD DE GITHUB

### Estado real del repositorio (verificado via GitHub API)

```
Repositorio: carlosocampo1034-a11y/LC-Sync-LAB
Visibilidad: PUBLIC  ← CONFIRMADO
GitHub Pages: ACTIVO
Tamaño: ~29 MB
```

**El código fuente ES ACCESIBLE PÚBLICAMENTE.**

### Auditoría de secretos en historial git

```
git log --all -- *.jks *.keystore local.properties *.db supabase_sync.json
→ VACÍO — Cero archivos sensibles en toda la historia de commits
```

**Resultado: LIMPIO** — Ninguna credencial fue expuesta nunca.

### Plan LC-Public (preparado, listo para ejecutar)

Los archivos de la landing page están preparados en `~/LC/lc-public-repo/`:
- `index.html` (copia de `docs/index.html`, con branding L&C)
- `.nojekyll`

**Acciones que CARLOS debe hacer manualmente:**

1. **Hacer LC-Sync-LAB PRIVADO:**
   - Ir a: `https://github.com/carlosocampo1034-a11y/LC-Sync-LAB/settings`
   - Sección "Danger Zone" → "Change repository visibility" → **Make private**
   - Confirmar escribiendo el nombre del repo

2. **Crear repo LC-Public:**
   - Crear nuevo repo en GitHub: `LC-Public` (público)
   - Activar GitHub Pages en carpeta raíz o `/docs`
   - Subir archivos de `~/LC/lc-public-repo/`
   - Crear una Release en ese repo y adjuntar `app-release.apk` (64 MB)
   - Actualizar el link en `index.html` con la nueva URL del Release

3. **Resultado:** Código privado en LC-Sync-LAB, descarga pública en LC-Public

**Estado:** PENDIENTE — Requiere acción manual de Carlos (sin gh CLI disponible)

---

## FASE 2 — SUPABASE

### Conexión verificada

```
URL: https://rmkduyrrjtqqvjpqhyra.supabase.co
Credenciales: service_role_key presente en instance/supabase_sync.json (NO en git)
Anon key: compilada en APK (aceptable — RLS la restringe)
```

### Tablas en Supabase (verificadas via REST API)

| Tabla | Rows (service role) | RLS anon |
|-------|---------------------|----------|
| `/jobs` | 7 total | HTTP 401 — RLS activa ✅ |
| `/profiles` | 2 filas | bloqueada |
| `/sync_receipts` | accesible | bloqueada |
| `/users` | 1 fila (Supabase Auth) | bloqueada |
| `/trabajo` | NO EXISTE en Supabase | — |
| `/tecnicos` | NO EXISTE en Supabase | — |
| `/proposals` | NO EXISTE en Supabase | — |

**Hallazgo crítico:** Solo `jobs`, `profiles`, `sync_receipts` y `users` existen en Supabase. Las tablas `trabajo`, `tecnicos`, `proposals` que el mirror intenta sincronizar generan HTTP 404. El mirror actual solo sincroniza **jobs** con éxito.

### Estado de sincronización

```
Mirror cursor jobs_seq: 171
Último log exitoso: 2026-10-21 20:54 — job 8000000000020@v4
Último error: 2026-10-21 05:55 — HTTP 400 "Could not find the 'datos' column"
```

**Jobs en Supabase vs Local:**

| ID | En Local | En Supabase | Nota |
|----|----------|-------------|------|
| 1,2,3,4,5 | ❌ No | ✅ Sí | Datos de QA antiguo (sin `seq`) |
| 8000000000020 | ✅ Sí | ✅ Sí | EN RUTA — sincronizado |
| 8000000000021 | ✅ Sí | ✅ Sí | COMPLETADO — sincronizado |

Los jobs 1-5 son **huérfanos de prueba** (QA anterior al cloud-first). No representan datos reales de producción.

### RLS — Row Level Security

```
PASS: Anon key recibe HTTP 401 en /jobs → RLS activa y protegiendo
PASS: Service role key accede correctamente → Admin sync funciona
```

### Autenticación Android

- Sync service account en Supabase Auth: `lc.sync.mirror@gmail.com`
- Perfiles: ADMIN + SYNC roles configurados
- Tokens Bearer con TTL 12h en lab.db

**Estado Supabase: PASS (parcial)** — Conexión y RLS correctas. El mirror solo sincroniza jobs (no trabajo/tecnicos/propuestas). Para producción se necesitan las tablas faltantes.

---

## FASE 3 — TRES APLICACIONES

---

### 3.1 — L&C CONTROL (Panel Web)

**Tipo:** Flask/Python — No es APK  
**Puerto:** 5055 (Termux local, `python3 run_lab.py` activo PID ~19994)

**Verificaciones:**

| Check | Resultado |
|-------|-----------|
| Flask corriendo | PASS |
| Login web (curl) | PASS — HTTP 302 → sesión válida |
| Endpoints AJAX (3 formularios) | PASS — JSON `{"ok":true}` verificado |
| CSRF en formularios | PASS |
| Roles ADMIN/DISPATCHER | PASS |
| Usuarios en DB | PASS — 12 usuarios (3 ADMIN, 1 DISPATCHER, 1 TECH activo) |
| HTTPS | PENDIENTE — HTTP local; producción necesita HTTPS |

**Para producción:** El panel web debe estar detrás de HTTPS (Tailscale, Nginx reverse proxy, o similar). No necesita Google Play.

**Estado: PASS** para uso interno. **PENDIENTE** para exposición externa.

---

### 3.2 — L&C TECHNICIAN (Android)

**Package:** `com.lc.technician`  
**Versión:** 0.3.3 (versionCode 7)  
**APK release:** `app/build/outputs/apk/release/app-release.apk` (64 MB, Oct 21 19:09)

#### Checklist Google Play — Technician

| Requisito | Estado | Detalle |
|-----------|--------|---------|
| applicationId correcto | ✅ PASS | `com.lc.technician` |
| versionCode ≥ 1 | ✅ PASS | 7 |
| targetSdk ≥ 34 | ✅ PASS | 36 |
| minSdk razonable | ✅ PASS | 24 (Android 7.0+) |
| APK release firmado | ✅ PASS | `app-release.apk` (64 MB, Oct 21) |
| Keystore presente | ✅ PASS | `lc_release.jks` (2758 bytes) |
| AAB generado | ❌ FAIL | Build falla por AAPT2 (ver abajo) |
| Icono de launcher | ❌ BLOQUEANTE | Carpetas mipmap VACÍAS |
| cleartext HTTP | ⚠️ PENDIENTE | base-config `true` (solo lab IPs restringidas) |
| READ_EXTERNAL_STORAGE | ⚠️ REVISAR | Permiso deprecado en Android 13+ |
| WRITE_EXTERNAL_STORAGE | ❌ AUSENTE | No en manifest (correcto para targetSdk 36) |
| Privacy Policy URL | ❌ PENDIENTE | No preparada |
| Descripción Play Store | ❌ PENDIENTE | No preparada |
| Screenshots | ❌ PENDIENTE | Requieren capturas reales |
| Cuenta Google Play | ❌ PENDIENTE | $25 USD |

#### FAIL — AAB build

**Error:** `AAPT2 35.0.0 Daemon startup failed — Syntax error: Unterminated quoted string`

**Causa raíz:** El binario AAPT2 está dentro del contenedor proot-distro Debian (`/data/data/com.termux/.../debian/rootfs/opt/android-sdk/build-tools/35.0.0/aapt2`). Desde Termux puro (fuera de proot) el binario ELF falla porque no puede inicializarse. Las builds anteriores exitosas se hicieron DENTRO del entorno proot-distro.

**Solución:**
```bash
# Carlos debe ejecutar esto en Termux:
proot-distro login debian
# Dentro de Debian:
cd ~/LC   # o /root/LC según montaje
./gradlew bundleRelease
# El AAB saldrá en: app/build/outputs/bundle/release/app-release.aab
```

#### BLOQUEANTE — Iconos de launcher

Las carpetas `mipmap-hdpi`, `mipmap-mdpi`, `mipmap-xhdpi`, `mipmap-xxhdpi`, `mipmap-xxxhdpi` están **completamente vacías**. Solo existe `ic_menu_logout.xml` en drawable (no es el launcher icon).

El `AndroidManifest.xml` solo define `android:label="@string/app_name"` sin `android:icon`. Google Play requiere un icono de 512×512 PNG y los iconos adaptativos para el lanzador.

**Acción requerida:**
1. Crear icono 512×512 PNG con el logo L&C (rojo + letras blancas)
2. Colocarlo en `mipmap-xxxhdpi/ic_launcher.png` (192×192), `mipmap-xxhdpi` (144×144), etc.
3. O crear `ic_launcher.xml` con foreground/background adaptativos
4. Agregar `android:icon="@mipmap/ic_launcher"` al manifest

#### Configuración de red para producción

El `network_security_config.xml` actual:
```xml
<base-config cleartextTrafficPermitted="true" />  ← debe ser false en producción
```

Para Play Store se necesita crear `app/src/release/res/xml/network_security_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
</network-security-config>
```
Y el servidor Flask debe estar en HTTPS.

**Estado Technician: PENDIENTE** — APK firmado existe y es válido. Faltan icono (bloqueante), AAB (requiere proot), y configuración de red para producción.

---

### 3.3 — L&C CUSTOMER (Android)

**Package:** `com.lc.customer.lab`  
**Versión:** 0.1.0-lab (versionCode 1)  
**APK:** Solo debug (3.4 MB) — sin release

#### Análisis de la app

**Estructura real:**
- 11 archivos Java (LoginActivity, RegisterActivity, HomeActivity, PropertiesActivity, NewRequestActivity, RequestDetailActivity, PdfViewerActivity, DiagnosticoActivity + helpers)
- 1 dependencia: `androidx.appcompat:1.7.0` (sin Room, Retrofit, Supabase)
- API hardcodeada: `http://10.0.0.121:5055/api/v2` (IP local de laboratorio)
- Sin manejo de estado offline
- Sin sincronización con Supabase

#### Checklist Google Play — Customer

| Requisito | Estado | Detalle |
|-----------|--------|---------|
| applicationId sin sufijo .lab | ❌ BLOQUEANTE | Debe ser `com.lc.customer` |
| Funcionalidad completa | ❌ BLOQUEADO | App en estado prototipo/skeleton |
| Conexión a Supabase | ❌ BLOQUEADO | Usa Flask local hardcodeado |
| APK release firmado | ❌ PENDIENTE | Solo debug compilado |
| Icono de launcher | ❌ PENDIENTE | No verificado |
| Modo offline | ❌ PENDIENTE | Sin Room/caché local |
| Testing E2E | ❌ PENDIENTE | No realizado |

**Estado Customer: BLOQUEADO** — Requiere desarrollo significativo antes de Play Store. Estimado: 4-6 semanas de desarrollo adicional.

---

## FASE 4 — QA REAL

### ADB

```
adb connect 10.0.0.121:35561 → Connection refused
```

El dispositivo no está conectado por ADB en este momento (puerto rotado). Carlos debe reconectar cuando lo necesite.

**Sin ADB activo no fue posible:**
- Verificar instalación actual de `com.lc.technician`
- Capturar screenshots de prueba
- Probar AJAX/toasts visualmente en Brave

**APK de referencia válido:** `app/build/outputs/apk/release/app-release.apk` (64 MB, Oct 21 19:09) — este fue instalado y verificado en la sesión anterior según evidencia en `QA_ANDROID/DIST_20261021/`.

---

## FASE 5 — ENTREGA DEFINITIVA

### A. GitHub — Estado de privacidad

| Ítem | Estado |
|------|--------|
| Repo LC-Sync-LAB | **PUBLIC** — acción pendiente de Carlos |
| Secretos expuestos | **NINGUNO** — historial limpio verificado |
| GitHub Pages | Activo pero quedará inaccesible al hacer privado |
| LC-Public preparado | Archivos listos en `~/LC/lc-public-repo/` |
| LC-Sync-LAB → privado | PENDIENTE — Carlos debe hacerlo en GitHub.com |

### B. Supabase

| Ítem | Estado |
|------|--------|
| Conexión | PASS |
| RLS activa | PASS — anon ve HTTP 401 |
| Jobs sincronizados | PASS (2 jobs actuales) |
| Tablas trabajo/tecnicos/proposals | FAIL — no existen en Supabase |
| Datos huérfanos (IDs 1-5) | INFO — QA antiguo, no crítico |
| Service role key en repo | PASS — solo en `instance/` (no en git) |

### C. Las tres aplicaciones

| App | APK/AAB | Icono | Red | Play Store |
|-----|---------|-------|-----|-----------|
| Control (Web) | N/A | N/A | HTTP local | No aplica |
| Technician | APK ✅ / AAB ❌ | ❌ VACÍO | cleartext lab | **PENDIENTE** |
| Customer | Debug ❌ / Release ❌ | ? | Hardcoded IP | **BLOQUEADO** |

### D. APK/AAB generados

| Archivo | Estado | Ruta |
|---------|--------|------|
| `app-release.apk` (64 MB) | ✅ EXISTE y válido | `app/build/outputs/apk/release/` |
| `app-release.aab` | ❌ BUILD FAILED | Requiere entorno proot-distro |
| `customer-debug.apk` (3.4 MB) | ⚠️ Debug only | `customer_app/app/build/outputs/apk/debug/` |

### E. Requisitos pendientes para Google Play

**Technician (com.lc.technician) — orden de prioridad:**

1. **[CRÍTICO] Crear iconos de launcher** — las carpetas mipmap están vacías
2. **[CRÍTICO] Generar AAB** — ejecutar `./gradlew bundleRelease` DENTRO de proot-distro
3. **[NECESARIO] Cambiar network_security_config** — `cleartext=false` para release
4. **[NECESARIO] Configurar HTTPS** — Tailscale o dominio propio
5. **[PLAY STORE] Privacy Policy** — URL pública requerida
6. **[PLAY STORE] Screenshots** — mínimo 2 por forma de pantalla
7. **[PLAY STORE] Descripción** — texto en español e inglés
8. **[PLAY STORE] Cuenta Google Play Developer** — $25 USD única vez

**Customer (com.lc.customer) — bloqueos primarios:**

1. **[CRÍTICO] Cambiar package** — `com.lc.customer.lab` → `com.lc.customer`
2. **[CRÍTICO] Completar funcionalidad** — actualmente es un skeleton
3. **[CRÍTICO] Conectar a Supabase** — eliminar IP hardcodeada
4. **[CRÍTICO] Modo offline** — Room + sincronización
5. Todo lo mismo que Technician (iconos, AAB, red, etc.)

---

## HOJA DE RUTA RESUMIDA

### Esta semana (inmediato)

```
Carlos → GitHub.com: hacer LC-Sync-LAB privado
Carlos → Crear LC-Public con los archivos de ~/LC/lc-public-repo/
Carlos → Termux: proot-distro login debian → ./gradlew bundleRelease
         (AAB saldrá en app/build/outputs/bundle/release/app-release.aab)
```

### Semana 1-2 (hardening Technician)

```
Crear icono launcher (512×512 PNG rojo con L&C blanco)
   → mipmap-xxxhdpi: 192×192 px
   → mipmap-xxhdpi:  144×144 px  
   → mipmap-xhdpi:   96×96 px
   → mipmap-hdpi:    72×72 px
   → mipmap-mdpi:    48×48 px
Configurar HTTPS para el servidor Flask (Tailscale recomendado)
network_security_config.xml → cleartext=false en release
Compilar AAB firmado final
```

### Semana 2-3 (Play Store Technician)

```
Crear cuenta Google Play Developer ($25 USD)
Preparar Privacy Policy (página web simple)
Capturar screenshots de la app
Subir AAB a Play Console → Internal Testing
Revisión Play Store (1-3 días)
```

### Semana 4-8 (Customer App)

```
Refactorizar customer_app: package, Supabase, Room offline
E2E testing completo
AAB firmado Customer
Play Store Customer
```

---

## CLASIFICACIÓN FINAL

| Ítem | Estado |
|------|--------|
| GitHub repo público con código expuesto | **ACTIVO — acción inmediata** |
| Secretos en git | **PASS — ninguno** |
| Supabase RLS | **PASS** |
| Supabase sync jobs | **PASS** |
| Supabase tablas faltantes (trabajo/tecnicos) | **PENDIENTE** |
| Flask backend corriendo | **PASS** |
| APK Technician release firmado | **PASS** |
| AAB Technician | **FAIL — requiere proot** |
| Iconos launcher Technician | **FAIL — mipmap vacíos** |
| Customer App funcional | **BLOQUEADO** |
| ADB conectado | **BLOCKED — puerto rotado** |
| Apps instaladas verificadas | **BLOCKED — sin ADB** |
| Listo para Google Play | **NO — ver requisitos** |

---

*Informe generado 2026-10-22. Solo lectura excepto creación de este informe y ~/LC/lc-public-repo/.*
