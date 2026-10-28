// L&C lc-api · LC-Sync-QA. Autenticación y autorización en el servidor en cada petición.
// Las escrituras van a funciones SQL transaccionales (migraciones 20261025170000 y 20261025200000):
//   lc_create_order     → idempotente por (usuario, request_id): un reintento nunca duplica la orden.
//   lc_patch_order      → fila bloqueada, merge de 3 vías, historial + conflictos + recibo en la misma
//                         transacción; despacho sujeto a las transiciones del Control original.
//   lc_resolve_conflict → decisión de despacho sobre un conflicto pendiente.
// qa-4 añade lo que necesita el Control ORIGINAL online: detalle, clientes, técnicos, propiedades,
// descuentos y notificaciones. La clave service_role solo existe en el entorno de la función.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.57.0";

type Rol = "ADMIN" | "DISPATCHER" | "TECHNICIAN" | "CUSTOMER";
type Actor = { id: string; role: Rol; email: string | null };

// CORS solo para el Control publicado (workers.dev) y pruebas locales; las apps Android no usan CORS
const ORIGEN_OK = /^(https:\/\/lc-control-qa\.[a-z0-9-]+\.workers\.dev|http:\/\/(127\.0\.0\.1|localhost)(:\d+)?)$/;
type Out = (status: number, value: unknown) => Response;
// Cabeceras por petición (nunca globales: con peticiones simultáneas se mezclarían los orígenes)
function salida(req: Request): { H: Record<string, string>; out: Out } {
  const o = req.headers.get("origin") || "";
  const H: Record<string, string> = { "content-type": "application/json", "cache-control": "no-store", "vary": "origin" };
  if (ORIGEN_OK.test(o)) {
    Object.assign(H, {
      "access-control-allow-origin": o,
      "access-control-allow-headers": "authorization,apikey,x-client-info,content-type",
      "access-control-allow-methods": "OPTIONS,GET,POST,PATCH",
      "access-control-max-age": "600",
    });
  }
  return { H, out: (status, value) => new Response(JSON.stringify(value), { status, headers: H }) };
}

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false, autoRefreshToken: false },
});

const META = new Set(["version", "device", "updatedAt"]);
const STAFF = ["ADMIN", "DISPATCHER"];
const TEL_RE = /^[\d +().\-]{7,32}$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const CODE_RE = /^[A-Za-z0-9][A-Za-z0-9-]{2,29}$/;
const txt = (v: unknown, max = 400) => (typeof v === "string" ? v.trim().slice(0, max) : "");

async function authenticate(req: Request, out: Out): Promise<Actor | Response> {
  const token = (req.headers.get("authorization") || "").match(/^Bearer (.+)$/i)?.[1];
  if (!token) return out(401, { error: "token_required" });
  const { data, error } = await db.auth.getUser(token);
  if (error || !data.user) return out(401, { error: "invalid_token" });
  const { data: p, error: pe } = await db.from("profiles").select("role,active").eq("id", data.user.id).maybeSingle();
  if (pe || !p || !["ADMIN", "DISPATCHER", "TECHNICIAN", "CUSTOMER"].includes(p.role)) {
    return out(403, { error: "profile_not_authorized" });
  }
  if (p.active === false) return out(403, { error: "account_disabled" });
  return { id: data.user.id, role: p.role as Rol, email: data.user.email ?? null };
}

function hasAccess(u: Actor, j: { customer_id: string | null; technician_id: string | null }): boolean {
  return STAFF.includes(u.role) || (u.role === "CUSTOMER" && j.customer_id === u.id) ||
    (u.role === "TECHNICIAN" && j.technician_id === u.id);
}

/** Respuesta de las funciones SQL: { http, ...cuerpo }. */
function fromRpc(r: Record<string, unknown> | null, out: Out): Response {
  if (!r || typeof r.http !== "number") return out(500, { error: "rpc_failed" });
  const { http, ...body } = r;
  return out(http as number, body);
}

/** Contraseña inicial: 14 caracteres sin ambigüedades; se devuelve UNA vez y nunca se registra. */
function claveSegura(): string {
  const abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
  const b = crypto.getRandomValues(new Uint8Array(14));
  return Array.from(b, (x) => abc[x % abc.length]).join("");
}

async function nombres(ids: (string | null)[]): Promise<Record<string, string>> {
  const u = [...new Set(ids.filter(Boolean))] as string[];
  if (!u.length) return {};
  const { data } = await db.from("profiles").select("id,display_name,email,technician_code").in("id", u);
  return Object.fromEntries((data || []).map((p) => [p.id, p.display_name || p.technician_code || p.email || p.id.slice(0, 8)]));
}

async function miCliente(u: Actor): Promise<number | null> {
  const { data } = await db.from("customers").select("id").eq("user_id", u.id).maybeSingle();
  return data?.id ?? null;
}

async function listarTecnicos() {
  const { data, error } = await db.from("profiles")
    .select("id,display_name,technician_code,email,active,available,specialties,must_change_password")
    .eq("role", "TECHNICIAN").order("active", { ascending: false }).order("technician_code");
  if (error) return null;
  const { data: abiertas } = await db.from("jobs").select("technician_id")
    .not("technician_id", "is", null).not("estado", "in", "(COMPLETADO,CANCELADO)");
  const n: Record<string, number> = {};
  for (const j of abiertas || []) n[j.technician_id] = (n[j.technician_id] || 0) + 1;
  return (data || []).map((t) => ({ ...t, abiertas: n[t.id] || 0 }));
}


// ── Propuestas Good/Better/Best (mismo contrato que lab/backend/lc_lab/proposals.py) ─────────────
const NIVELES = ["GOOD", "BETTER", "BEST"];
const COSTOS = ["costoEquipo", "costoManoObra", "costoMateriales", "costoPermiso", "costoRemocion", "costoAccesorios"];
const DETALLES = ["equipoPropuesto", "especificaciones", "garantia"];
const redondeo = (x: number) => Math.floor(x + 0.5 + 1e-9);   // mitad hacia arriba, al centavo
const num = (v: unknown) => (typeof v === "number" && isFinite(v) ? v : 0);

/** Misma fórmula que la app (ProposalOptionEntity.calcularTotal) y que el Control: en centavos enteros. */
function totalOpcion(o: Record<string, unknown>): number {
  const duros = COSTOS.reduce((a, k) => a + redondeo(num(o[k]) * 100), 0);
  const conMarkup = redondeo(duros * (1 + num(o.markupPorcentaje) / 100));
  const conDesc = conMarkup - redondeo(num(o.descuentoMonto) * 100);
  return Math.max(0, redondeo(conDesc * (1 + num(o.impuestoPorcentaje) / 100))) / 100;
}

function validarOpciones(opciones: unknown): { ok: Record<string, unknown>[] } | { error: string } {
  if (!Array.isArray(opciones) || opciones.length === 0) return { error: "la propuesta no tiene opciones" };
  const limpias: Record<string, unknown>[] = [];
  for (const raw of opciones) {
    const o = raw as Record<string, unknown>;
    if (!o || typeof o !== "object" || !NIVELES.includes(o.nivel as string)) return { error: "nivel de opción inválido" };
    for (const k of [...COSTOS, "descuentoMonto"]) {
      const v = o[k] ?? 0;
      if (typeof v !== "number" || v < 0 || v > 1_000_000) return { error: `${k} inválido en ${o.nivel}` };
    }
    for (const [k, tope] of [["markupPorcentaje", 300], ["impuestoPorcentaje", 30]] as const) {
      const v = o[k] ?? 0;
      if (typeof v !== "number" || v < 0 || v > tope) return { error: `${k} fuera de rango en ${o.nivel}` };
    }
    const total = totalOpcion(o);
    if (total > 0 && !txt(o.titulo, 200)) return { error: `la opción ${o.nivel} necesita título` };
    for (const k of DETALLES) {
      const v = o[k];
      if (v !== undefined && v !== null && (typeof v !== "string" || v.trim().length > 300)) {
        return { error: `${k} inválido en ${o.nivel} (texto de hasta 300 caracteres)` };
      }
    }
    // tolerancia de 1 centavo: Java y TypeScript pueden redondear distinto un medio centavo; el servidor guarda el suyo
    if (typeof o.totalCalculado === "number" && Math.abs(Math.round(o.totalCalculado * 100) / 100 - total) > 0.0151) {
      return { error: `el total de ${o.nivel} no coincide con el cálculo del servidor` };
    }
    if (total <= 0) continue;   // opción vacía: no se ofrece al cliente
    const l: Record<string, unknown> = {};
    for (const k of [...COSTOS, "markupPorcentaje", "descuentoMonto", "impuestoPorcentaje"]) l[k] = o[k] ?? 0;
    Object.assign(l, { nivel: o.nivel, titulo: txt(o.titulo, 200), descripcion: txt(o.descripcion, 1000), total,
      recomendada: o.recomendada === true });
    for (const k of DETALLES) if (typeof o[k] === "string" && (o[k] as string).trim()) l[k] = (o[k] as string).trim();
    limpias.push(l);
  }
  if (limpias.length === 0) return { error: "ninguna opción tiene importe" };
  limpias.sort((a, b) => NIVELES.indexOf(a.nivel as string) - NIVELES.indexOf(b.nivel as string));
  return { ok: limpias };
}

function estable(v: unknown): string {
  if (Array.isArray(v)) return "[" + v.map(estable).join(",") + "]";
  if (v && typeof v === "object") {
    const o = v as Record<string, unknown>;
    return "{" + Object.keys(o).sort().map((k) => JSON.stringify(k) + ":" + estable(o[k])).join(",") + "}";
  }
  return JSON.stringify(v);
}

async function sha256Hex(data: Uint8Array | string): Promise<string> {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : data;
  const h = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return Array.from(h).map((b) => b.toString(16).padStart(2, "0")).join("");
}

const forma = (p: Record<string, unknown>, tecnico: boolean) => {
  const vencida = p.estado === "PENDIENTE" && Date.now() > Number(p.expires_at);
  const o: Record<string, unknown> = { id: p.id, jobId: p.job_id, revision: p.revision, estado: vencida ? "VENCIDA" : p.estado,
    contenidoSha256: p.contenido_sha256, opcionElegida: p.opcion_elegida, totalAceptado: p.total_aceptado,
    firmante: p.firmante, decidedAt: p.decided_at, expiresAt: p.expires_at, createdAt: p.created_at, firmaSha256: p.firma_sha256 };
  if (tecnico) o.localId = p.local_id; else o.opciones = p.opciones;
  return o;
};
const COLS_PROP = "id,job_id,local_id,revision,opciones,contenido_sha256,estado,opcion_elegida,total_aceptado,firmante,firma_sha256,decided_at,expires_at,created_at";


// ── Push (FCM HTTP v1) ──────────────────────────────────────────────────────────────────────────────
// La cuenta de servicio de Firebase vive SOLO en el secreto FCM_SERVICE_ACCOUNT de esta función (nunca en el repo).
// Sin el secreto, todo lo de push es un no-op y el resto de la API funciona igual. Solo se envían mensajes de DATOS:
// la app decide cómo mostrarlos (con la app cerrada, en segundo plano o abierta).
type CuentaServicio = { project_id: string; client_email: string; private_key: string };
let cuentaCache: CuentaServicio | null | undefined;
function cuentaFcm(): CuentaServicio | null {
  if (cuentaCache !== undefined) return cuentaCache;
  try {
    const raw = Deno.env.get("FCM_SERVICE_ACCOUNT");
    const j = raw ? JSON.parse(raw) : null;
    cuentaCache = j && j.private_key && j.client_email && j.project_id ? j as CuentaServicio : null;
  } catch { cuentaCache = null; }
  return cuentaCache;
}
const b64u = (b: Uint8Array | string) => {
  const u = typeof b === "string" ? new TextEncoder().encode(b) : b;
  return btoa(String.fromCharCode(...u)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};
let accesoCache: { v: string; exp: number } | null = null;
async function accesoFcm(c: CuentaServicio): Promise<string> {
  if (accesoCache && accesoCache.exp > Date.now() + 60_000) return accesoCache.v;
  const t = Math.floor(Date.now() / 1000);
  const cab = b64u(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const cuerpo = b64u(JSON.stringify({ iss: c.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token", iat: t, exp: t + 3600 }));
  const der = Uint8Array.from(atob(c.private_key.replace(/-----[A-Z ]+-----/g, "").replace(/\s+/g, "")), (ch) => ch.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const firma = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(cab + "." + cuerpo)));
  const r = await fetch("https://oauth2.googleapis.com/token", { method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: cab + "." + cuerpo + "." + b64u(firma) }) });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.access_token) throw new Error("fcm_auth_" + r.status);
  accesoCache = { v: j.access_token, exp: Date.now() + (Number(j.expires_in) || 3600) * 1000 };
  return accesoCache.v;
}

/** Envía un mensaje de datos a todos los teléfonos de esos usuarios. Los tokens que FCM da por muertos se borran. */
async function enviarPush(userIds: string[], data: Record<string, string>, ttl = "120s"): Promise<{ configurado: boolean; enviados: number; fallidos: number }> {
  const c = cuentaFcm();
  if (!c) return { configurado: false, enviados: 0, fallidos: 0 };
  if (userIds.length === 0) return { configurado: true, enviados: 0, fallidos: 0 };
  const { data: toks } = await db.from("push_tokens").select("token").in("user_id", userIds);
  let enviados = 0, fallidos = 0;
  if (!toks || toks.length === 0) return { configurado: true, enviados, fallidos };
  const acceso = await accesoFcm(c);
  for (const t of toks) {
    const r = await fetch(`https://fcm.googleapis.com/v1/projects/${c.project_id}/messages:send`, { method: "POST",
      headers: { "content-type": "application/json", authorization: "Bearer " + acceso },
      body: JSON.stringify({ message: { token: t.token, data, android: { priority: "HIGH", ttl } } }) });
    if (r.ok) { enviados++; continue; }
    fallidos++;
    const e = await r.json().catch(() => ({}));
    const estado = e?.error?.status as string | undefined;
    if (r.status === 404 || estado === "NOT_FOUND" || estado === "UNREGISTERED" || estado === "INVALID_ARGUMENT") {
      await db.from("push_tokens").delete().eq("token", t.token);
    } else {
      await db.from("push_tokens").update({ last_error: `${r.status} ${estado ?? ""}`.trim() }).eq("token", t.token);
    }
  }
  return { configurado: true, enviados, fallidos };
}

/** Avisa a los técnicos de cada oferta abierta que aún no se notificó (idempotente: la marca se reclama antes de enviar). */
async function notificarOfertas(): Promise<void> {
  if (!cuentaFcm()) return;
  const ahora = Date.now();
  const { data: ofs } = await db.from("job_offers").select("id,job_id,technician_id,jobs!inner(categoria,direccion)")
    .eq("estado", "OFRECIDA").is("notified_at", null).gt("expires_at", ahora).limit(50);
  if (!ofs || ofs.length === 0) return;
  const { data: mias } = await db.from("job_offers").update({ notified_at: ahora })
    .in("id", ofs.map((o) => o.id)).is("notified_at", null).select("id");
  const propias = new Set((mias || []).map((m) => m.id));
  for (const o of ofs) {
    if (!propias.has(o.id)) continue;
    const j = (o.jobs as unknown) as { categoria: string | null; direccion: string | null };
    await enviarPush([o.technician_id], { tipo: "offer", offer_id: String(o.id), job_id: String(o.job_id),
      titulo: "NUEVA OFERTA DE TRABAJO", cuerpo: [j?.categoria, j?.direccion].filter(Boolean).join(" · ").slice(0, 140) });
  }
}

/** Aviso al técnico al que el Control le asigna una orden a mano. */
async function notificarAsignacion(jobId: number, techId: string): Promise<void> {
  if (!cuentaFcm()) return;
  const { data: j } = await db.from("jobs").select("categoria,direccion,descripcion").eq("id", jobId).maybeSingle();
  await enviarPush([techId], { tipo: "order", job_id: String(jobId), titulo: "Nueva orden asignada",
    cuerpo: [j?.categoria, j?.direccion].filter(Boolean).join(" · ").slice(0, 140) || "Revisa tu lista de órdenes" }, "3600s");
}

/** No retrasa la respuesta HTTP: sigue en segundo plano (EdgeRuntime.waitUntil) y nunca propaga errores. */
function segundoPlano(p: Promise<unknown>) {
  const q = p.catch((e) => console.error("push", e instanceof Error ? e.message : "error"));
  // deno-lint-ignore no-explicit-any
  const er = (globalThis as any).EdgeRuntime;
  if (er && typeof er.waitUntil === "function") er.waitUntil(q);
}

Deno.serve(async (req) => {
  const { H, out } = salida(req);
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: H });
  const url = new URL(req.url);
  const route = (url.pathname.split("/lc-api").pop() || "/").replace(/\/+$/, "") || "/";
  try {
    if (route === "/health" && req.method === "GET") {
      return out(200, { status: "ok", environment: "LC-Sync-QA", version: "qa-8" });
    }
    const user = await authenticate(req, out);
    if (user instanceof Response) return user;
    const staff = STAFF.includes(user.role);
    const body = req.method === "POST" || req.method === "PATCH" ? await req.json().catch(() => null) : null;

    if (route === "/auth/me" && req.method === "GET") {
      const { data: p } = await db.from("profiles").select("display_name,technician_code,must_change_password")
        .eq("id", user.id).maybeSingle();
      // technician_code: la app del técnico lo usa para "Mis Jobs" (las órdenes llegan con technician_code)
      return out(200, { id: user.id, role: user.role, email: user.email, display_name: p?.display_name ?? null,
                        technician_code: user.role === "TECHNICIAN" ? p?.technician_code ?? null : undefined,
                        must_change_password: p?.must_change_password ?? false });
    }
    if ((route === "/protected-test" || route === "/tech-probe") && req.method === "GET") {
      return out(200, { ok: true, role: user.role });
    }

    // ── Técnicos ─────────────────────────────────────────────────────────────────────────────
    if (route === "/technicians" && req.method === "GET") {
      if (!staff) return out(403, { error: "role_denied" });
      const t = await listarTecnicos();
      return t ? out(200, t) : out(500, { error: "list_failed" });
    }
    if (route === "/technicians" && req.method === "POST") {
      if (user.role !== "ADMIN") return out(403, { error: "Solo un administrador puede dar de alta técnicos." });
      const email = txt(body?.email, 120).toLowerCase(), nombre = txt(body?.display_name, 80);
      const code = txt(body?.technician_code, 30).toUpperCase(), esp = txt(body?.specialties, 200) || null;
      let clave = typeof body?.password === "string" ? body.password : "";
      const generada = !clave;
      if (generada) clave = claveSegura();
      if (!EMAIL_RE.test(email)) return out(422, { error: "correo inválido" });
      if (nombre.length < 3) return out(422, { error: "nombre para mostrar de 3 a 80 caracteres" });
      if (!CODE_RE.test(code)) return out(422, { error: "código técnico inválido (3-30: letras, números y -)" });
      if (clave.length < 10) return out(422, { error: "la contraseña debe tener al menos 10 caracteres" });
      const { data: dup } = await db.from("profiles").select("id").eq("technician_code", code).maybeSingle();
      if (dup) return out(409, { error: "ese código técnico ya existe" });
      const { data: nu, error } = await db.auth.admin.createUser({ email, password: clave, email_confirm: true,
        user_metadata: { display_name: nombre } });
      if (error || !nu.user) return out(409, { error: "no se pudo crear la cuenta (¿correo ya registrado?)" });
      const { error: pe } = await db.from("profiles").update({ role: "TECHNICIAN", display_name: nombre,
        technician_code: code, email, specialties: esp, active: true, must_change_password: true }).eq("id", nu.user.id);
      if (pe) { await db.auth.admin.deleteUser(nu.user.id); return out(500, { error: "create_failed" }); }
      await db.from("customers").delete().eq("user_id", nu.user.id);  // el alta por defecto lo creó como cliente
      return out(201, { id: nu.user.id, email, technician_code: code, display_name: nombre, password: clave });
    }
    const mt = route.match(/^\/technicians\/([0-9a-f-]{36})\/(active|password)$/i);
    if (mt && req.method === "POST") {
      if (user.role !== "ADMIN") return out(403, { error: "Solo un administrador puede gestionar técnicos." });
      const { data: t } = await db.from("profiles").select("id,role,email,display_name").eq("id", mt[1]).maybeSingle();
      if (!t || t.role !== "TECHNICIAN") return out(404, { error: "Técnico no encontrado." });
      if (mt[2] === "active") {
        const activo = body?.active === true;
        const { error } = await db.auth.admin.updateUserById(t.id, { ban_duration: activo ? "none" : "876000h" });
        if (error) return out(500, { error: "update_failed" });
        await db.from("profiles").update({ active: activo }).eq("id", t.id);
        const { count } = await db.from("jobs").select("id", { count: "exact", head: true })
          .eq("technician_id", t.id).not("estado", "in", "(COMPLETADO,CANCELADO)");
        return out(200, { id: t.id, active: activo, abiertas: count || 0 });
      }
      const clave = claveSegura();
      const { error } = await db.auth.admin.updateUserById(t.id, { password: clave });
      if (error) return out(500, { error: "update_failed" });
      await db.from("profiles").update({ must_change_password: true }).eq("id", t.id);
      return out(200, { id: t.id, email: t.email, password: clave });
    }

    // ── Clientes (directorio) ────────────────────────────────────────────────────────────────
    if (route === "/customers" && req.method === "GET") {
      if (!staff) return out(403, { error: "role_denied" });
      const { data, error } = await db.from("customers").select("id,user_id,nombre,telefono,email,created_at,updated_at")
        .order("updated_at", { ascending: false }).limit(200);
      if (error) return out(500, { error: "list_failed" });
      const ids = (data || []).map((c) => c.id);
      const { data: props } = await db.from("customer_properties").select("customer_id").in("customer_id", ids);
      const { data: jobs } = await db.from("jobs").select("customer_ref").in("customer_ref", ids);
      const cuenta = (rows: { [k: string]: number }[] | null, k: string) =>
        (rows || []).reduce((a: Record<number, number>, r) => (a[r[k]] = (a[r[k]] || 0) + 1, a), {});
      const np = cuenta(props, "customer_id"), nj = cuenta(jobs, "customer_ref");
      return out(200, (data || []).map((c) => ({ ...c, cuenta_app: !!c.user_id, n_prop: np[c.id] || 0, n_jobs: nj[c.id] || 0 })));
    }
    if (route === "/customers" && req.method === "POST") {
      if (!staff) return out(403, { error: "role_denied" });
      const nombre = txt(body?.nombre, 80), telefono = txt(body?.telefono, 32), email = txt(body?.email, 120).toLowerCase();
      const errs = [];
      if (nombre.length < 2) errs.push("nombre entre 2 y 80 caracteres");
      if (telefono && !TEL_RE.test(telefono)) errs.push("teléfono inválido (dígitos, espacios, +().-, 7-32)");
      if (email && !EMAIL_RE.test(email)) errs.push("email inválido");
      if (errs.length) return out(422, { error: errs.join("; ") });
      // si ya tiene cuenta en la app con ese correo, se enlaza
      let uid: string | null = null;
      if (email) {
        const { data: p } = await db.from("profiles").select("id").eq("role", "CUSTOMER").ilike("email", email).maybeSingle();
        uid = p?.id ?? null;
        if (uid) {
          const { data: ya } = await db.from("customers").select("id").eq("user_id", uid).maybeSingle();
          if (ya) return out(409, { error: "ese cliente ya existe en el directorio", id: ya.id });
        }
      }
      const { data, error } = await db.from("customers").insert({ nombre, telefono: telefono || null,
        email: email || null, user_id: uid, created_by: user.id }).select("id").single();
      return error ? out(500, { error: "create_failed" }) : out(201, { id: data.id, nombre, cuenta_app: !!uid });
    }

    // ── Propiedades y equipos (cliente: las suyas; despacho: ?customer=<id>) ──────────────────
    if (route === "/properties" && req.method === "GET") {
      let cid: number | null;
      if (staff) cid = Number(url.searchParams.get("customer")) || null;
      else if (user.role === "CUSTOMER") cid = await miCliente(user);
      else return out(403, { error: "role_denied" });
      if (!cid) return out(200, []);
      const { data, error } = await db.from("customer_properties")
        .select("id,customer_id,direccion,tipo,tipo_acceso,instrucciones,mascota,contacto_respaldo,created_at,customer_equipment(id,tipo,marca,modelo,notas)")
        .eq("customer_id", cid).order("id");
      return error ? out(500, { error: "list_failed" }) : out(200, data || []);
    }
    if (route === "/properties" && req.method === "POST") {
      if (user.role !== "CUSTOMER") return out(403, { error: "role_denied" });
      const cid = await miCliente(user);
      if (!cid) return out(409, { error: "cliente sin ficha" });
      const direccion = txt(body?.direccion, 400);
      if (direccion.length < 3) return out(422, { error: "dirección requerida" });
      const rid = txt(body?.request_id, 128) || null;
      if (rid) {
        const { data: ya } = await db.from("customer_properties").select("id").eq("customer_id", cid).eq("client_request_id", rid).maybeSingle();
        if (ya) return out(200, { id: ya.id, replay: true });
      }
      const { data, error } = await db.from("customer_properties").insert({ customer_id: cid, direccion,
        tipo: txt(body?.tipo, 40) || null, tipo_acceso: txt(body?.tipo_acceso, 40) || null,
        instrucciones: txt(body?.instrucciones, 400) || null, mascota: txt(body?.mascota, 200) || null,
        contacto_respaldo: txt(body?.contacto_respaldo, 200) || null, client_request_id: rid }).select("id").single();
      return error ? out(500, { error: "create_failed" }) : out(201, { id: data.id });
    }
    const mpe = route.match(/^\/properties\/(\d+)\/equipment$/);
    if (mpe && req.method === "POST") {
      if (user.role !== "CUSTOMER") return out(403, { error: "role_denied" });
      const cid = await miCliente(user);
      const { data: p } = await db.from("customer_properties").select("id,customer_id").eq("id", Number(mpe[1])).maybeSingle();
      if (!p || p.customer_id !== cid) return out(404, { error: "not_found" });
      const tipo = txt(body?.tipo, 60);
      if (!tipo) return out(422, { error: "tipo requerido" });
      const rid = txt(body?.request_id, 128) || null;
      if (rid) {
        const { data: ya } = await db.from("customer_equipment").select("id").eq("property_id", p.id).eq("client_request_id", rid).maybeSingle();
        if (ya) return out(200, { id: ya.id, replay: true });
      }
      const { data, error } = await db.from("customer_equipment").insert({ property_id: p.id, tipo,
        marca: txt(body?.marca, 60) || null, modelo: txt(body?.modelo, 60) || null, notas: txt(body?.notas, 300) || null,
        client_request_id: rid }).select("id").single();
      return error ? out(500, { error: "create_failed" }) : out(201, { id: data.id });
    }

    // ── Notificaciones ───────────────────────────────────────────────────────────────────────
    if (route === "/notifications" && req.method === "GET") {
      const since = url.searchParams.get("since") || "0";
      if (!/^\d{1,15}$/.test(since)) return out(422, { error: "invalid_since" });
      let q = db.from("notifications").select("id,job_id,tipo,titulo,cuerpo,created_at").gt("created_at", Number(since));
      q = staff ? q.or(`user_id.eq.${user.id},user_id.is.null`) : q.eq("user_id", user.id);
      const { data, error } = await q.order("created_at", { ascending: false }).limit(100);
      return error ? out(500, { error: "list_failed" }) : out(200, data || []);
    }

    // ── Conflictos ───────────────────────────────────────────────────────────────────────────
    const mc = route.match(/^\/conflicts\/(\d+)\/resolve$/);
    if (mc && req.method === "POST") {
      if (!staff) return out(403, { error: "role_denied" });
      const { data, error } = await db.rpc("lc_resolve_conflict", { p_actor: user.id, p_conflict: Number(mc[1]),
        p_decision: txt(body?.decision, 20), p_valor: typeof body?.valor === "string" ? body.valor : null,
        p_version: Number.isSafeInteger(body?.version) ? body.version : null });
      return error ? out(500, { error: "resolve_failed" }) : fromRpc(data, out);
    }

    // ── Órdenes ──────────────────────────────────────────────────────────────────────────────
    const collection = route === "/orders" || route === "/jobs";
    const m = route.match(/^\/(?:orders|jobs)\/(\d+)$/);
    const md = route.match(/^\/(?:orders|jobs)\/(\d+)\/(detail|discounts)$/);

    // Lista por rol. ?since=<epoch ms> devuelve lo cambiado desde entonces (recuperación tras reconectar).
    if (collection && req.method === "GET") {
      const since = url.searchParams.get("since");
      if (since !== null && !/^\d{1,15}$/.test(since)) return out(422, { error: "invalid_since" });
      let q = db.from("jobs").select("*");
      if (user.role === "CUSTOMER") q = q.eq("customer_id", user.id);
      if (user.role === "TECHNICIAN") q = q.eq("technician_id", user.id);
      q = since !== null
        ? q.gte("updated_at", Number(since)).order("updated_at", { ascending: true }).limit(500)
        : q.order("updated_at", { ascending: false }).limit(200);
      const { data, error } = await q;
      return error ? out(500, { error: "list_failed" }) : out(200, data || []);
    }

    if (collection && req.method === "POST") {
      if (!body || typeof body !== "object") return out(422, { error: "json_required" });
      const { data, error } = await db.rpc("lc_create_order", {
        p_actor: user.id, p_request_id: typeof body.request_id === "string" ? body.request_id : null, p_data: body,
      });
      if (!error) segundoPlano(notificarOfertas());   // una orden nueva sin técnico genera ofertas
      return error ? out(500, { error: "create_failed" }) : fromRpc(data, out);
    }

    // ── Push: token del teléfono y prueba ─────────────────────────────────────────────────────
    if (route === "/me/push-token" && req.method === "POST") {
      const token = typeof body?.token === "string" ? body.token.trim() : "";
      if (token.length < 20 || token.length > 4096) return out(422, { error: "invalid_token" });
      const plat = ["android", "ios", "web"].includes(body?.platform) ? body.platform : "android";
      // el token es del teléfono: si cambia de usuario (otro inicio de sesión) se reasigna
      const { error } = await db.from("push_tokens").upsert({ token, user_id: user.id, platform: plat,
        app_id: txt(body?.app, 100) || null, updated_at: Date.now(), last_error: null }, { onConflict: "token" });
      return error ? out(500, { error: "save_failed" }) : out(200, { ok: true });
    }
    if (route === "/me/push-token" && req.method === "DELETE") {
      const t = (url.searchParams.get("token") || "").trim();
      if (t.length < 20) return out(422, { error: "invalid_token" });
      await db.from("push_tokens").delete().eq("token", t).eq("user_id", user.id);
      return out(200, { ok: true });
    }
    if (route === "/push/test" && req.method === "POST") {
      if (user.role !== "ADMIN") return out(403, { error: "role_denied" });
      const uid = typeof body?.user_id === "string" ? body.user_id : user.id;
      const { count } = await db.from("push_tokens").select("token", { count: "exact", head: true }).eq("user_id", uid);
      const r = await enviarPush([uid], { tipo: body?.tipo === "offer" ? "offer" : "order", job_id: "0",
        titulo: txt(body?.titulo, 80) || "Prueba de notificación L&C", cuerpo: txt(body?.cuerpo, 140) || "Si ves esto, el push funciona." }, "60s");
      return out(200, { ...r, telefonos: count ?? 0 });
    }

    // ── Ofertas automáticas de trabajo (auto-dispatch) ───────────────────────────────────────
    // Una orden nueva sin técnico se ofrece (trigger SQL) a los técnicos disponibles con la especialidad; el primero
    // que acepta se queda con ella. Estas rutas son el lado del técnico. El despacho manual del Control sigue igual.
    if (route === "/offers" && req.method === "GET") {
      if (user.role !== "TECHNICIAN") return out(403, { error: "role_denied" });
      await db.rpc("lc_dispatch_sweep");   // vence ofertas viejas y reofrece lo que sigue sin técnico
      segundoPlano(notificarOfertas());
      const ahora = Date.now();
      const [{ data, error }, { data: me }] = await Promise.all([
        db.from("job_offers").select("id,job_id,created_at,expires_at,jobs!inner(categoria,descripcion,direccion,ventana_preferida,programado_para,tipo_propiedad,equipo)")
          .eq("technician_id", user.id).eq("estado", "OFRECIDA").gt("expires_at", ahora).order("created_at"),
        db.from("profiles").select("available").eq("id", user.id).maybeSingle(),
      ]);
      if (error) return out(500, { error: "list_failed" });
      return out(200, { serverNow: ahora, available: me?.available ?? true,
        offers: (data || []).map((o) => ({ id: o.id, jobId: o.job_id, createdAt: o.created_at, expiresAt: o.expires_at, job: o.jobs })) });
    }

    const mo = route.match(/^\/offers\/(\d+)\/(accept|reject)$/);
    if (mo && req.method === "POST") {
      if (user.role !== "TECHNICIAN") return out(403, { error: "role_denied" });
      const { data, error } = mo[2] === "accept"
        ? await db.rpc("lc_offer_accept", { p_actor: user.id, p_offer: Number(mo[1]) })
        : await db.rpc("lc_offer_reject", { p_actor: user.id, p_offer: Number(mo[1]), p_motivo: txt(body?.motivo, 200) || null });
      if (!error) segundoPlano(notificarOfertas());   // un rechazo puede abrir la oferta al siguiente técnico
      return error ? out(500, { error: "offer_failed" }) : fromRpc(data, out);
    }

    if (route === "/me/availability" && (req.method === "POST" || req.method === "PATCH")) {
      if (user.role !== "TECHNICIAN") return out(403, { error: "role_denied" });
      if (typeof body?.available !== "boolean") return out(422, { error: "available_required" });
      const { error } = await db.from("profiles").update({ available: body.available }).eq("id", user.id);
      return error ? out(500, { error: "update_failed" }) : out(200, { available: body.available });
    }

    const mdp = route.match(/^\/orders\/(\d+)\/dispatch$/);
    if (mdp && req.method === "POST") {   // el Control (re)lanza la oferta automática de una orden
      if (!staff) return out(403, { error: "role_denied" });
      const { data, error } = await db.rpc("lc_dispatch_job", { p_job: Number(mdp[1]) });
      if (!error) segundoPlano(notificarOfertas());
      return error ? out(500, { error: "dispatch_failed" }) : out(200, { ofertasNuevas: data });
    }

    if (route === "/dispatch/config" && req.method === "GET") {
      if (!staff) return out(403, { error: "role_denied" });
      const { data } = await db.from("dispatch_config").select("enabled,fanout,offer_ttl_seconds").eq("id", 1).maybeSingle();
      return out(200, data);
    }
    if (route === "/dispatch/config" && req.method === "PATCH") {
      if (user.role !== "ADMIN") return out(403, { error: "Solo un administrador cambia el despacho automático." });
      const c: Record<string, unknown> = {};
      if (typeof body?.enabled === "boolean") c.enabled = body.enabled;
      if (Number.isInteger(body?.fanout) && body.fanout >= 1 && body.fanout <= 10) c.fanout = body.fanout;
      if (Number.isInteger(body?.offer_ttl_seconds) && body.offer_ttl_seconds >= 20 && body.offer_ttl_seconds <= 3600) {
        c.offer_ttl_seconds = body.offer_ttl_seconds;
      }
      if (Object.keys(c).length === 0) return out(422, { error: "nothing_to_update" });
      const { error } = await db.from("dispatch_config").update({ ...c, updated_at: Date.now() }).eq("id", 1);
      return error ? out(500, { error: "update_failed" }) : out(200, c);
    }

    // ── Propuestas ───────────────────────────────────────────────────────────────────────────
    if (route === "/proposals" && req.method === "POST") {
      if (user.role !== "TECHNICIAN" && !staff) return out(403, { error: "role_denied" });
      if (!body || typeof body !== "object") return out(422, { error: "json_required" });
      const v = validarOpciones(body.opciones);
      if ("error" in v) return out(422, { status: "REJECTED", reason: v.error });
      const device = typeof body.device === "string" ? body.device : "";
      const { data, error } = await db.rpc("lc_proposal_save", {
        p_actor: user.id, p_job: Number(body.jobId), p_device: device, p_local: Number(body.localId),
        p_opciones: v.ok, p_sha: await sha256Hex(estable(v.ok)),
      });
      return error ? out(500, { error: "proposal_failed" }) : fromRpc(data, out);
    }

    // Estado de las propuestas (el técnico recibe la decisión del cliente). ?since=<epoch ms>
    if (route === "/proposals" && req.method === "GET") {
      const since = url.searchParams.get("since");
      if (since !== null && !/^\d{1,15}$/.test(since)) return out(422, { error: "invalid_since" });
      let q = db.from("job_proposals").select(COLS_PROP + ",jobs!inner(technician_id,customer_id)");
      if (user.role === "TECHNICIAN") q = q.eq("jobs.technician_id", user.id);
      else if (user.role === "CUSTOMER") q = q.eq("jobs.customer_id", user.id);
      if (since !== null) q = q.gte("updated_at", Number(since));
      const { data, error } = await q.order("id", { ascending: true }).limit(500);
      return error ? out(500, { error: "list_failed" })
        : out(200, (data || []).map((p) => forma(p as Record<string, unknown>, user.role === "TECHNICIAN")));
    }

    const mdec = route.match(/^\/customer\/proposals\/(\d+)\/decide$/);
    if (mdec && req.method === "POST") {
      if (user.role !== "CUSTOMER") return out(403, { error: "role_denied" });
      if (!body || typeof body !== "object") return out(422, { error: "json_required" });
      let firmaSha: string | null = null, firmaPng: string | null = null;
      if (body.decision === "APROBADA") {
        const b64 = typeof body.firmaPngBase64 === "string" ? body.firmaPngBase64 : "";
        let bytes: Uint8Array;
        try { bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0)); } catch { return out(400, { error: "firma inválida" }); }
        const magic = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
        if (bytes.length < 8 || bytes.length > 2 * 1024 * 1024 || !magic.every((b, i) => bytes[i] === b)) {
          return out(400, { error: "la firma debe ser una imagen PNG" });
        }
        firmaSha = await sha256Hex(bytes); firmaPng = b64;
      }
      const { data, error } = await db.rpc("lc_proposal_decide", {
        p_actor: user.id, p_pid: Number(mdec[1]), p_decision: txt(body.decision, 20), p_opcion: txt(body.opcion, 10) || null,
        p_firmante: txt(body.firmante, 200) || null, p_sha: txt(body.contenidoSha256, 100),
        p_firma_sha: firmaSha, p_firma_png: firmaPng,
      });
      return error ? out(500, { error: "decide_failed" }) : fromRpc(data, out);
    }

    // ── Mensajes y fotos por orden (qa-8). Acceso solo con hasAccess: un cliente nunca ve órdenes ajenas ──────
    const mm = route.match(/^\/(?:orders|jobs)\/(\d+)\/(messages|photos)$/);
    if (mm) {
      const jobId = Number(mm[1]);
      const { data: job } = await db.from("jobs").select("id,customer_id,technician_id,descripcion").eq("id", jobId).maybeSingle();
      if (!job || !hasAccess(user, job)) return out(404, { error: "not_found" });
      if (mm[2] === "messages" && req.method === "GET") {
        const since = url.searchParams.get("since") || "0";
        if (!/^\d{1,15}$/.test(since)) return out(422, { error: "invalid_since" });
        const { data, error } = await db.from("job_messages").select("id,sender_id,sender_role,body,created_at")
          .eq("job_id", jobId).gt("id", Number(since)).order("id").limit(200);
        if (error) return out(500, { error: "list_failed" });
        return out(200, (data || []).map((x) => ({ id: x.id, de: x.sender_role === "CUSTOMER" ? "CLIENTE" : "L&C",
          propio: x.sender_id === user.id, texto: x.body, ts: x.created_at })));
      }
      if (mm[2] === "messages" && req.method === "POST") {
        if (!body || typeof body !== "object") return out(422, { error: "json_required" });
        const texto = txt(body.texto, 2000);
        if (texto.length < 1) return out(422, { error: "El mensaje está vacío" });
        const cmid = txt(body.clientMsgId, 64) || null;
        let { data: fila, error } = await db.from("job_messages").insert({ job_id: jobId, sender_id: user.id,
          sender_role: user.role, body: texto, client_msg_id: cmid }).select("id,created_at").maybeSingle();
        if (error && (error as { code?: string }).code === "23505" && cmid) {   // reintento: devuelve el mismo mensaje
          const r = await db.from("job_messages").select("id,created_at").eq("job_id", jobId).eq("sender_id", user.id)
            .eq("client_msg_id", cmid).maybeSingle();
          return r.data ? out(200, { id: r.data.id, ts: r.data.created_at, duplicado: true }) : out(500, { error: "send_failed" });
        }
        if (error || !fila) return out(500, { error: "send_failed" });
        const aviso = user.role === "CUSTOMER"
          ? { user_id: null as string | null, titulo: `Mensaje del cliente · orden ${jobId}` }
          : { user_id: job.customer_id as string | null, titulo: "Mensaje de L&C" };
        if (user.role === "CUSTOMER" || aviso.user_id) {
          await db.from("notifications").insert({ user_id: aviso.user_id, job_id: jobId, tipo: "MENSAJE", titulo: aviso.titulo,
            cuerpo: texto.slice(0, 140), created_at: Date.now() });
          if (aviso.user_id) segundoPlano(enviarPush([aviso.user_id], { tipo: "message", job_id: String(jobId),
            titulo: aviso.titulo, cuerpo: texto.slice(0, 140) }));
        }
        return out(201, { id: fila.id, ts: fila.created_at });
      }
      if (mm[2] === "photos" && req.method === "GET") {
        const { data, error } = await db.from("job_photos").select("id,categoria,mime,created_at,path").eq("job_id", jobId).order("id");
        if (error) return out(500, { error: "list_failed" });
        const filas = data || [];
        const { data: urls } = filas.length ? await db.storage.from("job-photos").createSignedUrls(filas.map((f) => f.path), 3600)
          : { data: [] as { signedUrl: string }[] };
        return out(200, filas.map((f, i) => ({ id: f.id, categoria: f.categoria, mime: f.mime, ts: f.created_at,
          url: (urls || [])[i]?.signedUrl || null })));
      }
      if (mm[2] === "photos" && req.method === "POST") {
        if (!body || typeof body !== "object") return out(422, { error: "json_required" });
        const mime = txt(body.mime, 20);
        if (mime !== "image/jpeg" && mime !== "image/png") return out(422, { error: "Solo fotos JPG o PNG" });
        const cat = ["BEFORE", "AFTER", "OTHER"].includes(body.categoria as string) ? body.categoria as string : "BEFORE";
        const cpid = typeof body.clientPhotoId === "number" && Number.isSafeInteger(body.clientPhotoId) && body.clientPhotoId > 0 ? body.clientPhotoId : null;
        if (cpid) {
          const ya = await db.from("job_photos").select("id").eq("job_id", jobId).eq("uploaded_by", user.id).eq("client_photo_id", cpid).maybeSingle();
          if (ya.data) return out(200, { id: ya.data.id, duplicada: true });
        }
        let bytes: Uint8Array;
        try { bytes = Uint8Array.from(atob(typeof body.datosBase64 === "string" ? body.datosBase64 : ""), (c) => c.charCodeAt(0)); }
        catch { return out(400, { error: "foto inválida" }); }
        const jpg = bytes[0] === 0xff && bytes[1] === 0xd8, png = bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e;
        if (bytes.length < 100 || bytes.length > 5 * 1024 * 1024 || (mime === "image/jpeg" ? !jpg : !png)) {
          return out(400, { error: "La foto no es válida o supera 5 MB" });
        }
        const { count } = await db.from("job_photos").select("id", { count: "exact", head: true }).eq("job_id", jobId);
        if ((count ?? 0) >= 12) return out(422, { error: "Máximo 12 fotos por solicitud" });
        const path = `${jobId}/${crypto.randomUUID()}.${mime === "image/png" ? "png" : "jpg"}`;
        const up = await db.storage.from("job-photos").upload(path, bytes, { contentType: mime, upsert: false });
        if (up.error) return out(500, { error: "upload_failed" });
        const ins = await db.from("job_photos").insert({ job_id: jobId, uploaded_by: user.id, categoria: cat, client_photo_id: cpid,
          mime, path, bytes: bytes.length }).select("id").maybeSingle();
        if (ins.error || !ins.data) {
          await db.storage.from("job-photos").remove([path]);
          return out(500, { error: "save_failed" });
        }
        return out(201, { id: ins.data.id });
      }
    }

    if (m && req.method === "GET") {
      const { data, error } = await db.from("jobs").select("*").eq("id", Number(m[1])).maybeSingle();
      if (error) return out(500, { error: "read_failed" });
      if (!data || !hasAccess(user, data)) return out(404, { error: "not_found" });
      const { data: props } = await db.from("job_proposals").select(COLS_PROP).eq("job_id", data.id).order("id");
      const extra: Record<string, unknown> = {};
      if (user.role === "CUSTOMER") {   // lo que el cliente puede ver: estado/cita/técnico (sin notas internas ni despacho)
        const { data: h } = await db.from("job_history").select("field,new_value,created_at").eq("job_id", data.id)
          .in("field", ["estado", "programado_para", "technician_id"]).order("id", { ascending: false }).limit(60);
        extra.historial = (h || []).map((e) => ({ campo: e.field === "estado" ? "Estado" : e.field === "programado_para" ? "Cita" : "Técnico asignado",
          valor: e.new_value, ts: typeof e.created_at === "string" ? Date.parse(e.created_at) : e.created_at }));
        if (data.technician_id) extra.tecnico_nombre = (await nombres([data.technician_id]))[data.technician_id] ?? null;
        const { count } = await db.from("job_photos").select("id", { count: "exact", head: true }).eq("job_id", data.id);
        extra.fotos_count = count ?? 0;
      }
      return out(200, { ...data, ...extra, propuestas: (props || []).map((p) => forma(p as Record<string, unknown>, false)) });
    }

    if (md && md[2] === "detail" && req.method === "GET") {
      if (!staff) return out(403, { error: "role_denied" });
      const id = Number(md[1]);
      const { data: j } = await db.from("jobs").select("*").eq("id", id).maybeSingle();
      if (!j) return out(404, { error: "not_found" });
      const { data: ofs } = await db.from("job_offers").select("id,technician_id,estado,motivo,created_at,expires_at,responded_at")
        .eq("job_id", id).order("id");
      const [{ data: hist }, { data: conf }, { data: desc }] = await Promise.all([
        db.from("job_history").select("version,field,old_value,new_value,actor,created_at").eq("job_id", id).order("id", { ascending: false }),
        db.from("job_conflicts").select("*").eq("job_id", id).eq("estado", "PENDIENTE").order("id"),
        db.from("job_discounts").select("id,monto,motivo,autorizado_por,created_at").eq("job_id", id).order("id"),
      ]);
      const quien = await nombres([...(ofs || []).map((o) => o.technician_id), ...(hist || []).map((h) => h.actor), ...(conf || []).map((c) => c.actor),
        ...(desc || []).map((d) => d.autorizado_por)]);
      let propiedad = null, cliente = null;
      if (j.property_id) propiedad = (await db.from("customer_properties").select("*").eq("id", j.property_id).maybeSingle()).data;
      if (j.customer_ref) cliente = (await db.from("customers").select("id,nombre,telefono,email,user_id").eq("id", j.customer_ref).maybeSingle()).data;
      return out(200, { job: j, propiedad, cliente,
        historial: (hist || []).map((h) => ({ ...h, actor_nombre: quien[h.actor] || "sistema" })),
        conflictos: (conf || []).map((c) => ({ ...c, actor_nombre: quien[c.actor] || "—" })),
        ofertas: (ofs || []).map((o) => ({ ...o, tecnico_nombre: quien[o.technician_id] || "—" })),
        descuentos: (desc || []).map((d) => ({ ...d, autorizado_nombre: quien[d.autorizado_por] || "—" })) });
    }

    if (md && md[2] === "discounts" && req.method === "POST") {
      if (!staff) return out(403, { error: "role_denied" });
      const monto = Number(body?.monto), motivo = txt(body?.motivo, 300);
      if (!(monto > 0 && monto < 100000) || motivo.length < 2) return out(422, { error: "monto y motivo requeridos" });
      const { data: j } = await db.from("jobs").select("id").eq("id", Number(md[1])).maybeSingle();
      if (!j) return out(404, { error: "not_found" });
      const { data, error } = await db.from("job_discounts").insert({ job_id: j.id, monto: Math.round(monto * 100) / 100,
        motivo, autorizado_por: user.id }).select("id").single();
      return error ? out(500, { error: "create_failed" }) : out(201, { id: data.id });
    }

    if (m && req.method === "PATCH") {
      if (!body || typeof body !== "object") return out(422, { error: "json_required" });
      const changes: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(body)) if (!META.has(k)) changes[k] = v;
      const { data, error } = await db.rpc("lc_patch_order", {
        p_actor: user.id, p_job: Number(m[1]),
        p_base: Number.isSafeInteger(body.version) ? body.version : null,
        p_changes: changes,
        p_device: typeof body.device === "string" ? body.device : null,
        p_client_ts: Number.isSafeInteger(body.updatedAt) ? body.updatedAt : null,
      });
      if (!error && data && (data as { http?: number }).http === 200) {
        segundoPlano(notificarOfertas());   // devolver la orden a la cola puede generar ofertas
        const nuevo = changes.technician_id;
        if (typeof nuevo === "string" && nuevo && nuevo !== user.id) segundoPlano(notificarAsignacion(Number(m[1]), nuevo));
      }
      return error ? out(500, { error: "update_failed" }) : fromRpc(data, out);
    }

    return out(404, { error: "route_not_found" });
  } catch (e) {
    console.error("lc-api qa", e instanceof Error ? e.name : "unknown");
    return out(500, { error: "internal_error" });
  }
});
