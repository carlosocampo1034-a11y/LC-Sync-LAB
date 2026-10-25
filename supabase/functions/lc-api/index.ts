// L&C lc-api · LC-Sync-QA. Autenticación y autorización en el servidor en cada petición.
// Las escrituras van a funciones SQL transaccionales (migración 20261025170000):
//   lc_create_order  → idempotente por (usuario, request_id): un reintento nunca duplica la orden.
//   lc_patch_order   → fila bloqueada, merge de 3 vías, historial + conflictos + recibo en la misma
//                      transacción; reintento con el mismo (device, updatedAt) devuelve el recibo.
// La clave service_role solo existe en el entorno de la función, nunca en las apps.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.57.0";

type Rol = "ADMIN" | "DISPATCHER" | "TECHNICIAN" | "CUSTOMER";
type Actor = { id: string; role: Rol };

const H = {
  "content-type": "application/json",
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization,apikey,x-client-info,content-type",
  "access-control-allow-methods": "OPTIONS,GET,POST,PATCH",
};
const out = (status: number, value: unknown) => new Response(JSON.stringify(value), { status, headers: H });

const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false, autoRefreshToken: false },
});

const META = new Set(["version", "device", "updatedAt"]);

async function authenticate(req: Request): Promise<Actor | Response> {
  const token = (req.headers.get("authorization") || "").match(/^Bearer (.+)$/i)?.[1];
  if (!token) return out(401, { error: "token_required" });
  const { data, error } = await db.auth.getUser(token);
  if (error || !data.user) return out(401, { error: "invalid_token" });
  const { data: p, error: pe } = await db.from("profiles").select("role").eq("id", data.user.id).maybeSingle();
  if (pe || !p || !["ADMIN", "DISPATCHER", "TECHNICIAN", "CUSTOMER"].includes(p.role)) {
    return out(403, { error: "profile_not_authorized" });
  }
  return { id: data.user.id, role: p.role as Rol };
}

function hasAccess(u: Actor, j: { customer_id: string | null; technician_id: string | null }): boolean {
  return ["ADMIN", "DISPATCHER"].includes(u.role) || (u.role === "CUSTOMER" && j.customer_id === u.id) ||
    (u.role === "TECHNICIAN" && j.technician_id === u.id);
}

/** Respuesta de las funciones SQL: { http, ...cuerpo }. */
function fromRpc(r: Record<string, unknown> | null): Response {
  if (!r || typeof r.http !== "number") return out(500, { error: "rpc_failed" });
  const { http, ...body } = r;
  return out(http as number, body);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: H });
  const url = new URL(req.url);
  const route = (url.pathname.split("/lc-api").pop() || "/").replace(/\/+$/, "") || "/";
  try {
    if (route === "/health" && req.method === "GET") {
      return out(200, { status: "ok", environment: "LC-Sync-QA", version: "qa-2" });
    }
    const user = await authenticate(req);
    if (user instanceof Response) return user;

    if (route === "/auth/me" && req.method === "GET") return out(200, { id: user.id, role: user.role });
    if ((route === "/protected-test" || route === "/tech-probe") && req.method === "GET") {
      return out(200, { ok: true, role: user.role });
    }

    if (route === "/technicians" && req.method === "GET") {
      if (!["ADMIN", "DISPATCHER"].includes(user.role)) return out(403, { error: "role_denied" });
      const { data, error } = await db.from("profiles").select("id,display_name,technician_code")
        .eq("role", "TECHNICIAN").order("display_name");
      return error ? out(500, { error: "list_failed" }) : out(200, data || []);
    }

    const collection = route === "/orders" || route === "/jobs";
    const m = route.match(/^\/(?:orders|jobs)\/(\d+)$/);

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
      const b = await req.json().catch(() => null);
      if (!b || typeof b !== "object") return out(422, { error: "json_required" });
      const { data, error } = await db.rpc("lc_create_order", {
        p_actor: user.id, p_request_id: typeof b.request_id === "string" ? b.request_id : null, p_data: b,
      });
      return error ? out(500, { error: "create_failed" }) : fromRpc(data);
    }

    if (m && req.method === "GET") {
      const { data, error } = await db.from("jobs").select("*").eq("id", Number(m[1])).maybeSingle();
      if (error) return out(500, { error: "read_failed" });
      if (!data || !hasAccess(user, data)) return out(404, { error: "not_found" });
      return out(200, data);
    }

    if (m && req.method === "PATCH") {
      const b = await req.json().catch(() => null);
      if (!b || typeof b !== "object") return out(422, { error: "json_required" });
      const changes: Record<string, unknown> = {};
      for (const [k, v] of Object.entries(b)) if (!META.has(k)) changes[k] = v;
      const { data, error } = await db.rpc("lc_patch_order", {
        p_actor: user.id, p_job: Number(m[1]),
        p_base: Number.isSafeInteger(b.version) ? b.version : null,
        p_changes: changes,
        p_device: typeof b.device === "string" ? b.device : null,
        p_client_ts: Number.isSafeInteger(b.updatedAt) ? b.updatedAt : null,
      });
      return error ? out(500, { error: "update_failed" }) : fromRpc(data);
    }

    return out(404, { error: "route_not_found" });
  } catch (e) {
    console.error("lc-api qa", e instanceof Error ? e.name : "unknown");
    return out(500, { error: "internal_error" });
  }
});
