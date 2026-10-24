// lc-customer — customer-facing operations (JWT required)
// jobs.customer_uuid = Supabase Auth UUID (TEXT) for new jobs
// jobs.customer_id = integer Flask ID for mirrored legacy jobs
// profiles: id, role, technician_code, display_name, customer_id, email
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPA_URL = Deno.env.get("SUPABASE_URL")!;
const SUPA_SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const SUPA_ANON = Deno.env.get("SUPABASE_ANON_KEY")!;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
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

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });

  const token = getToken(req);
  if (!token) return json({ error: "token requerido" }, 401);

  const uc = createClient(SUPA_URL, SUPA_ANON, {
    global: { headers: { Authorization: `Bearer ${token}` } },
    auth: { autoRefreshToken: false, persistSession: false },
  });
  const { data: auth, error: authErr } = await uc.auth.getUser();
  if (authErr || !auth.user) return json({ error: "token inválido" }, 401);

  const userId = auth.user.id; // Supabase Auth UUID
  const url = new URL(req.url);
  const path = url.pathname.replace(/^\/functions\/v1\/lc-customer\/?/, "").replace(/\/$/, "");
  const admin = adminClient();

  try {
    // GET /lc-customer/jobs — shows Supabase-native jobs for this customer
    if (req.method === "GET" && (path === "jobs" || path === "")) {
      const { data, error } = await admin
        .from("jobs")
        .select("id, estado, descripcion, direccion, created_at, updated_at, technician_code, version")
        .eq("customer_uuid", userId)
        .order("created_at", { ascending: false });
      if (error) return json({ error: error.message }, 500);
      return json({ jobs: data ?? [] });
    }

    // GET /lc-customer/jobs/:id
    const jobMatch = path.match(/^jobs\/(\d+)$/);
    if (req.method === "GET" && jobMatch) {
      const jid = parseInt(jobMatch[1]);
      const { data, error } = await admin
        .from("jobs")
        .select("id, estado, descripcion, direccion, technician_code, version, created_at, updated_at")
        .eq("id", jid)
        .eq("customer_uuid", userId)
        .maybeSingle();
      if (error || !data) return json({ error: "trabajo no encontrado" }, 404);
      return json(data);
    }

    // POST /lc-customer/quote — request new service (stores UUID in customer_uuid)
    if (req.method === "POST" && (path === "quote" || path === "jobs")) {
      const body = await req.json().catch(() => ({}));
      const { descripcion, direccion, categoria } = body;
      if (!descripcion || !direccion) {
        return json({ error: "descripcion y direccion requeridos" }, 400);
      }

      const now = new Date().toISOString();
      const { data, error } = await admin
        .from("jobs")
        .insert({
          customer_uuid: userId,
          estado: "PENDIENTE",
          estado_dispatch: "PENDIENTE",
          descripcion,
          direccion,
          categoria: categoria ?? "GENERAL",
          version: 1,
          origin: "CUSTOMER_APP_SUPA",
          created_at: now,
          updated_at: now,
        })
        .select("id, estado, descripcion, direccion, version, created_at")
        .single();

      if (error) return json({ error: error.message }, 500);
      return json(data, 201);
    }

    // GET /lc-customer/me
    if (req.method === "GET" && path === "me") {
      const { data: profile } = await admin
        .from("profiles")
        .select("display_name, role")
        .eq("id", userId)
        .maybeSingle();
      return json({ user_id: userId, email: auth.user.email, ...profile });
    }

    // GET /lc-customer/properties
    if (req.method === "GET" && path === "properties") {
      const { data, error } = await admin
        .from("properties")
        .select("id, direccion, descripcion, created_at")
        .eq("customer_id", userId)
        .order("created_at", { ascending: false });
      if (error) return json({ error: error.message }, 500);
      return json({ properties: data ?? [] });
    }

    // POST /lc-customer/properties
    if (req.method === "POST" && path === "properties") {
      const body = await req.json().catch(() => ({}));
      const { direccion, descripcion } = body;
      if (!direccion) return json({ error: "direccion requerida" }, 400);
      const { data, error } = await admin
        .from("properties")
        .insert({ customer_id: userId, direccion, descripcion })
        .select()
        .single();
      if (error) return json({ error: error.message }, 500);
      return json(data, 201);
    }

    return json({ error: "ruta no encontrada" }, 404);
  } catch (e) {
    console.error("lc-customer error:", e);
    return json({ error: "error interno" }, 500);
  }
});
