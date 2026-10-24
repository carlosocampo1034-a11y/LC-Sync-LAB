// lc-dispatch — control board operations (DISPATCHER or ADMIN role required)
// profiles schema: id, role, technician_code, display_name, customer_id, email
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPA_URL = Deno.env.get("SUPABASE_URL")!;
const SUPA_SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const SUPA_ANON = Deno.env.get("SUPABASE_ANON_KEY")!;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, PUT, OPTIONS",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

function getToken(req: Request): string | null {
  const h = req.headers.get("Authorization") ?? req.headers.get("apikey") ?? "";
  return h.replace(/^Bearer\s+/i, "") || null;
}

function adminClient() {
  return createClient(SUPA_URL, SUPA_SERVICE, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
}

async function requireDispatcher(req: Request): Promise<{ userId: string } | Response> {
  const token = getToken(req);
  if (!token) return json({ error: "token requerido" }, 401);

  const uc = createClient(SUPA_URL, SUPA_ANON, {
    global: { headers: { Authorization: `Bearer ${token}` } },
    auth: { autoRefreshToken: false, persistSession: false },
  });
  const { data: auth, error } = await uc.auth.getUser();
  if (error || !auth.user) return json({ error: "token inválido" }, 401);

  const admin = adminClient();
  const { data: profile } = await admin
    .from("profiles")
    .select("role")
    .eq("id", auth.user.id)
    .maybeSingle();

  if (!profile || !["DISPATCHER", "ADMIN"].includes(profile.role)) {
    return json({ error: "rol DISPATCHER requerido" }, 403);
  }
  return { userId: auth.user.id };
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });

  const authResult = await requireDispatcher(req);
  if (authResult instanceof Response) return authResult;

  const url = new URL(req.url);
  const path = url.pathname.replace(/^\/functions\/v1\/lc-dispatch\/?/, "").replace(/\/$/, "");
  const admin = adminClient();

  try {
    // GET /lc-dispatch/jobs
    if (req.method === "GET" && (path === "jobs" || path === "")) {
      const estado = url.searchParams.get("estado");
      let query = admin
        .from("jobs")
        .select("id, estado, estado_dispatch, descripcion, direccion, customer_id, technician_code, version, created_at, updated_at, prioridad, categoria")
        .order("created_at", { ascending: false });
      if (estado) query = query.eq("estado", estado);
      const { data, error } = await query;
      if (error) return json({ error: error.message }, 500);
      return json({ jobs: data ?? [] });
    }

    // POST /lc-dispatch/jobs/:id/assign
    const assignMatch = path.match(/^jobs\/(\d+)\/assign$/);
    if (req.method === "POST" && assignMatch) {
      const jid = parseInt(assignMatch[1]);
      const body = await req.json().catch(() => ({}));
      // Accept either technicianId (Flask compat) or technician_code
      const techCode: string = body.technicianId ?? body.technician_code ?? "";

      if (!techCode) return json({ error: "technicianId requerido" }, 400);

      const { data: job, error: jobErr } = await admin
        .from("jobs")
        .select("id, estado, version")
        .eq("id", jid)
        .maybeSingle();

      if (jobErr || !job) return json({ error: "trabajo no encontrado" }, 404);
      if (job.estado !== "PENDIENTE") {
        return json({ error: `no se puede asignar trabajo en estado ${job.estado}` }, 409);
      }

      const { data: updated, error: upErr } = await admin
        .from("jobs")
        .update({
          technician_code: techCode,
          estado: "EN RUTA",
          estado_dispatch: "ASIGNADO",
          version: job.version + 1,
          updated_at: new Date().toISOString(),
        })
        .eq("id", jid)
        .select("id, estado, technician_code, version")
        .single();

      if (upErr) return json({ error: upErr.message }, 500);
      return json(updated);
    }

    // GET /lc-dispatch/technicians
    if (req.method === "GET" && path === "technicians") {
      const { data, error } = await admin
        .from("profiles")
        .select("id, technician_code, display_name, email")
        .eq("role", "TECHNICIAN");
      if (error) return json({ error: error.message }, 500);
      return json({ technicians: data ?? [] });
    }

    // GET /lc-dispatch/jobs/:id
    const jobMatch = path.match(/^jobs\/(\d+)$/);
    if (req.method === "GET" && jobMatch) {
      const jid = parseInt(jobMatch[1]);
      const { data, error } = await admin
        .from("jobs")
        .select("id, estado, estado_dispatch, descripcion, direccion, customer_id, technician_code, version, created_at, updated_at")
        .eq("id", jid)
        .maybeSingle();
      if (error || !data) return json({ error: "trabajo no encontrado" }, 404);
      return json(data);
    }

    return json({ error: "ruta no encontrada" }, 404);
  } catch (e) {
    console.error("lc-dispatch error:", e);
    return json({ error: "error interno" }, 500);
  }
});
