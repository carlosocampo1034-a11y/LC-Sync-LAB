// lc-api · router de la Edge Function. Mantiene las rutas activas (/health, /protected-test,
// /tech-probe) y añade las de órdenes. No contiene secretos: usa el JWT que Supabase Auth emite
// y lee SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY del entorno de la función (nunca del cliente).
// NO DESPLEGAR sin ejecutar orders.test.ts y sin plan de reversión.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { aplicar, versionValida, receiptKey, type Rol } from "./orders.ts";

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers": "authorization, x-client-info, apikey, content-type",
  "access-control-allow-methods": "GET, POST, PATCH, OPTIONS",
};
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { ...CORS, "content-type": "application/json" } });

const URL_SB = Deno.env.get("SUPABASE_URL")!;
const SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

type Usuario = { id: string; rol: Rol };

async function usuarioDe(req: Request): Promise<Usuario | Response> {
  const auth = req.headers.get("Authorization") ?? "";
  if (!auth.startsWith("Bearer ")) return json(401, { error: "token requerido" });
  const sb = createClient(URL_SB, Deno.env.get("SUPABASE_ANON_KEY") ?? "", {
    global: { headers: { Authorization: auth } },
  });
  const { data, error } = await sb.auth.getUser();
  if (error || !data?.user) return json(401, { error: "token inválido o expirado" });
  const admin = createClient(URL_SB, SERVICE);
  const { data: perfil } = await admin.from("profiles").select("role").eq("id", data.user.id).single();
  const rol = (perfil?.role ?? "CUSTOMER") as Rol;
  return { id: data.user.id, rol };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  const url = new URL(req.url);
  const ruta = url.pathname.replace(/^\/lc-api/, "").replace(/\/+$/, "") || "/";

  // ── sondas existentes (sin cambios de contrato) ──────────────────────────────
  if (ruta === "/health") {
    return json(200, {
      status: "ok", function: "lc-api", version: "13.0-orders",
      routes: ["/health", "/protected-test", "/tech-probe", "POST /orders", "GET /orders/:id",
               "GET /orders?assigned=me", "PATCH /orders/:id"],
    });
  }
  if (ruta === "/protected-test" || ruta === "/tech-probe") {
    const u = await usuarioDe(req);
    if (u instanceof Response) return u;
    return json(200, { ok: true, rol: u.rol, ruta });
  }

  // ── órdenes ─────────────────────────────────────────────────────────────────
  const m = ruta.match(/^\/orders\/(\d+)$/);

  if (ruta === "/orders" && req.method === "POST") {
    const u = await usuarioDe(req);
    if (u instanceof Response) return u;
    if (!["CUSTOMER", "DISPATCHER", "ADMIN"].includes(u.rol)) return json(403, { error: "rol no autorizado" });
    const body = await req.json().catch(() => ({}));
    if (!body?.cliente || !body?.descripcion) return json(422, { error: "cliente y descripcion son obligatorios" });
    const admin = createClient(URL_SB, SERVICE);
    const { data, error } = await admin.from("jobs").insert({
      estado: "PENDIENTE", version: 1, cliente: body.cliente, descripcion: body.descripcion,
      direccion: body.direccion ?? null, telefono: body.telefono ?? null,
      origin: "CUSTOMER", created_by: u.id, client_updated_at: body.updatedAt ?? Date.now(),
    }).select("id, version").single();
    if (error) return json(502, { error: "storage_error", detail: error.message });
    return json(201, { id: data.id, version: data.version, status: "PENDIENTE" });
  }

  if (m && req.method === "GET") {
    const u = await usuarioDe(req);
    if (u instanceof Response) return u;
    const admin = createClient(URL_SB, SERVICE);
    const { data, error } = await admin.from("jobs").select("*").eq("id", Number(m[1])).single();
    if (error || !data) return json(404, { error: "orden no encontrada" });
    if (u.rol === "CUSTOMER" && data.created_by !== u.id) return json(403, { error: "sin permiso" });
    if (u.rol === "TECHNICIAN" && data.technician_code !== null && data.technician_id !== u.id) {
      return json(403, { error: "orden no asignada a este técnico" });
    }
    return json(200, data);
  }

  if (ruta === "/orders" && req.method === "GET" && url.searchParams.get("assigned") === "me") {
    const u = await usuarioDe(req);
    if (u instanceof Response) return u;
    if (u.rol !== "TECHNICIAN") return json(403, { error: "solo técnicos" });
    const admin = createClient(URL_SB, SERVICE);
    const { data, error } = await admin.from("jobs").select("*").eq("technician_id", u.id);
    if (error) return json(502, { error: "storage_error" });
    return json(200, data);
  }

  if (m && req.method === "PATCH") {
    const u = await usuarioDe(req);
    if (u instanceof Response) return u;
    const body = await req.json().catch(() => ({}));
    if (!versionValida(body?.version)) return json(422, { error: "versión del servidor obligatoria (entero >= 1)" });
    const admin = createClient(URL_SB, SERVICE);
    const { data: actual } = await admin.from("jobs").select("*").eq("id", Number(m[1])).single();
    if (!actual) return json(404, { error: "orden no encontrada" });
    if (u.rol === "TECHNICIAN" && actual.technician_id !== u.id) return json(403, { error: "orden no asignada a este técnico" });

    const idem = receiptKey(String(body.device ?? "desconocido"), actual.id, Number(body.updatedAt ?? 0));
    const { data: previo } = await admin.from("sync_receipts").select("status, version")
      .eq("device_id", String(body.device ?? "desconocido")).eq("job_id", actual.id)
      .eq("client_updated_at", Number(body.updatedAt ?? 0)).maybeSingle();
    if (previo) return json(200, { ...previo, id: actual.id, replay: true, receipt: idem });

    const { data: hist } = await admin.from("job_history").select("field")
      .eq("job_id", actual.id).gt("version", body.version);
    const tocados = new Set<string>((hist ?? []).map((h: any) => h.field));
    const cambios: Record<string, unknown> = {};
    for (const k of ["estado", "notas", "prioridad", "horas", "programado_para"]) {
      if (body[k] !== undefined) cambios[k] = body[k];
    }
    const { resultado, cambios: aplicarCampos, conflictos } = aplicar(actual as any, body.version, cambios, tocados, u.rol);

    if (resultado.status === "REJECTED") return json(422, { id: actual.id, ...resultado });
    if (conflictos.length) {
      await admin.from("job_conflicts").insert(conflictos.map((c) => ({
        job_id: actual.id, field: c, base_version: body.version, server_version: actual.version,
        server_value: String((actual as any)[c] ?? ""), client_value: String(cambios[c] ?? ""),
        actor: u.id, estado: "PENDIENTE",
      })));
    }
    if (Object.keys(aplicarCampos).length) {
      const { error } = await admin.from("jobs")
        .update({ ...aplicarCampos, version: actual.version + 1, updated_at: Date.now() })
        .eq("id", actual.id).eq("version", actual.version);
      if (error) return json(409, { id: actual.id, status: "CONFLICT", version: actual.version });
    }
    await admin.from("sync_receipts").insert({
      device_id: String(body.device ?? "desconocido"), job_id: actual.id,
      client_updated_at: Number(body.updatedAt ?? 0), status: resultado.status,
      version: resultado.version ?? null, ts: Date.now(),
    });
    return json(resultado.status === "ACCEPTED" ? 200 : 409, { id: actual.id, ...resultado });
  }

  return json(404, { error: "ruta no encontrada" });
});
