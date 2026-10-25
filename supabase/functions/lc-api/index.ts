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
    .select("id,display_name,technician_code,email,active,specialties,must_change_password")
    .eq("role", "TECHNICIAN").order("active", { ascending: false }).order("technician_code");
  if (error) return null;
  const { data: abiertas } = await db.from("jobs").select("technician_id")
    .not("technician_id", "is", null).not("estado", "in", "(COMPLETADO,CANCELADO)");
  const n: Record<string, number> = {};
  for (const j of abiertas || []) n[j.technician_id] = (n[j.technician_id] || 0) + 1;
  return (data || []).map((t) => ({ ...t, abiertas: n[t.id] || 0 }));
}

Deno.serve(async (req) => {
  const { H, out } = salida(req);
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: H });
  const url = new URL(req.url);
  const route = (url.pathname.split("/lc-api").pop() || "/").replace(/\/+$/, "") || "/";
  try {
    if (route === "/health" && req.method === "GET") {
      return out(200, { status: "ok", environment: "LC-Sync-QA", version: "qa-4" });
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
      return error ? out(500, { error: "create_failed" }) : fromRpc(data, out);
    }

    if (m && req.method === "GET") {
      const { data, error } = await db.from("jobs").select("*").eq("id", Number(m[1])).maybeSingle();
      if (error) return out(500, { error: "read_failed" });
      if (!data || !hasAccess(user, data)) return out(404, { error: "not_found" });
      return out(200, data);
    }

    if (md && md[2] === "detail" && req.method === "GET") {
      if (!staff) return out(403, { error: "role_denied" });
      const id = Number(md[1]);
      const { data: j } = await db.from("jobs").select("*").eq("id", id).maybeSingle();
      if (!j) return out(404, { error: "not_found" });
      const [{ data: hist }, { data: conf }, { data: desc }] = await Promise.all([
        db.from("job_history").select("version,field,old_value,new_value,actor,created_at").eq("job_id", id).order("id", { ascending: false }),
        db.from("job_conflicts").select("*").eq("job_id", id).eq("estado", "PENDIENTE").order("id"),
        db.from("job_discounts").select("id,monto,motivo,autorizado_por,created_at").eq("job_id", id).order("id"),
      ]);
      const quien = await nombres([...(hist || []).map((h) => h.actor), ...(conf || []).map((c) => c.actor),
        ...(desc || []).map((d) => d.autorizado_por)]);
      let propiedad = null, cliente = null;
      if (j.property_id) propiedad = (await db.from("customer_properties").select("*").eq("id", j.property_id).maybeSingle()).data;
      if (j.customer_ref) cliente = (await db.from("customers").select("id,nombre,telefono,email,user_id").eq("id", j.customer_ref).maybeSingle()).data;
      return out(200, { job: j, propiedad, cliente,
        historial: (hist || []).map((h) => ({ ...h, actor_nombre: quien[h.actor] || "sistema" })),
        conflictos: (conf || []).map((c) => ({ ...c, actor_nombre: quien[c.actor] || "—" })),
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
      return error ? out(500, { error: "update_failed" }) : fromRpc(data, out);
    }

    return out(404, { error: "route_not_found" });
  } catch (e) {
    console.error("lc-api qa", e instanceof Error ? e.name : "unknown");
    return out(500, { error: "internal_error" });
  }
});
