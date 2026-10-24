// lc-dispatch — control board operations (DISPATCHER or ADMIN role required)
// FIXES:
//   - assign: sets estado_dispatch='ASIGNADO', NOT estado='EN RUTA'
//   - estado stays 'PENDIENTE' until technician self-transitions to 'EN RUTA'
//   - atomic CAS on assign: PATCH WHERE id=? AND version=?(prev) AND estado='PENDIENTE'
//   - state machine: validates allowed transitions
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
    // ─── GET /lc-dispatch/jobs ─────────────────────────────────────────────────
    if (req.method === "GET" && (path === "jobs" || path === "")) {
      const estado = url.searchParams.get("estado");
      const estadoDispatch = url.searchParams.get("estado_dispatch");
      const limit = Math.min(parseInt(url.searchParams.get("limit") ?? "100"), 500);
      const offset = parseInt(url.searchParams.get("offset") ?? "0");

      let query = admin
        .from("jobs")
        .select("id, estado, estado_dispatch, descripcion, direccion, customer_id, technician_code, version, created_at, updated_at, prioridad, categoria")
        .order("created_at", { ascending: false })
        .range(offset, offset + limit - 1);

      if (estado) query = query.eq("estado", estado);
      if (estadoDispatch) query = query.eq("estado_dispatch", estadoDispatch);

      const { data, error } = await query;
      if (error) return json({ error: error.message }, 500);
      return json({ jobs: data ?? [], count: (data ?? []).length, offset, limit });
    }

    // ─── POST /lc-dispatch/jobs/:id/assign ────────────────────────────────────
    // Dispatcher assigns technician: sets estado_dispatch='ASIGNADO', technician_code.
    // Does NOT change estado — technician triggers 'EN RUTA' themselves via lc-sync/push.
    // Atomic CAS: PATCH WHERE id=? AND estado='PENDIENTE' AND version=?(prev)
    const assignMatch = path.match(/^jobs\/(\d+)\/assign$/);
    if (req.method === "POST" && assignMatch) {
      const jid = parseInt(assignMatch[1]);
      const body = await req.json().catch(() => ({}));
      const techCode: string = body.technicianId ?? body.technician_code ?? "";

      if (!techCode) return json({ error: "technicianId requerido" }, 400);

      // Verify technician exists
      const { data: techProfile } = await admin
        .from("profiles")
        .select("id, display_name")
        .eq("technician_code", techCode)
        .eq("role", "TECHNICIAN")
        .maybeSingle();

      if (!techProfile) {
        return json({ error: `técnico '${techCode}' no encontrado` }, 404);
      }

      // Read current job to get version for CAS
      const { data: current, error: readErr } = await admin
        .from("jobs")
        .select("id, estado, version, technician_code")
        .eq("id", jid)
        .maybeSingle();

      if (readErr || !current) return json({ error: "trabajo no encontrado" }, 404);

      if (current.estado !== "PENDIENTE") {
        return json({
          error: `no se puede asignar: trabajo en estado '${current.estado}' (solo PENDIENTE)`,
          currentEstado: current.estado,
        }, 409);
      }

      if (current.technician_code) {
        return json({
          error: `trabajo ya asignado a '${current.technician_code}'`,
          technician_code: current.technician_code,
        }, 409);
      }

      const newVersion = current.version + 1;
      const now = Date.now();

      // CAS: only succeeds if estado AND version still match what we just read
      const { data: updated, error: upErr } = await admin
        .from("jobs")
        .update({
          technician_code: techCode,
          estado_dispatch: "ASIGNADO",   // dispatch state → ASIGNADO
          // estado stays "PENDIENTE" — technician triggers EN RUTA via lc-sync/push
          version: newVersion,
          updated_at: now,
        })
        .eq("id", jid)
        .eq("estado", "PENDIENTE")     // CAS guard #1
        .eq("version", current.version) // CAS guard #2
        .select("id, estado, estado_dispatch, technician_code, version")
        .maybeSingle();

      if (upErr) return json({ error: upErr.message }, 500);

      if (!updated) {
        return json({
          error: "conflicto: el trabajo fue modificado concurrentemente, reintenta",
          code: "CONCURRENT_MODIFICATION",
        }, 409);
      }

      return json(updated);
    }

    // ─── POST /lc-dispatch/jobs/:id/unassign ──────────────────────────────────
    // Reverts a dispatch assignment (only if still PENDIENTE/ASIGNADO)
    const unassignMatch = path.match(/^jobs\/(\d+)\/unassign$/);
    if (req.method === "POST" && unassignMatch) {
      const jid = parseInt(unassignMatch[1]);

      const { data: current } = await admin
        .from("jobs")
        .select("id, estado, version")
        .eq("id", jid)
        .maybeSingle();

      if (!current) return json({ error: "trabajo no encontrado" }, 404);
      if (!["PENDIENTE"].includes(current.estado)) {
        return json({ error: `no se puede desasignar: trabajo en estado '${current.estado}'` }, 409);
      }

      const now = Date.now();
      const { data: updated, error: upErr } = await admin
        .from("jobs")
        .update({
          technician_code: null,
          estado_dispatch: "PENDIENTE",
          version: current.version + 1,
          updated_at: now,
        })
        .eq("id", jid)
        .eq("version", current.version)
        .select("id, estado, estado_dispatch, technician_code, version")
        .maybeSingle();

      if (upErr) return json({ error: upErr.message }, 500);
      if (!updated) return json({ error: "conflicto: el trabajo fue modificado concurrentemente" }, 409);

      return json(updated);
    }

    // ─── GET /lc-dispatch/technicians ──────────────────────────────────────────
    if (req.method === "GET" && path === "technicians") {
      const { data, error } = await admin
        .from("profiles")
        .select("id, technician_code, display_name")
        .eq("role", "TECHNICIAN")
        .not("technician_code", "is", null);
      if (error) return json({ error: error.message }, 500);
      return json({ technicians: data ?? [] });
    }

    // ─── GET /lc-dispatch/jobs/:id ─────────────────────────────────────────────
    const jobMatch = path.match(/^jobs\/(\d+)$/);
    if (req.method === "GET" && jobMatch) {
      const jid = parseInt(jobMatch[1]);
      const { data, error } = await admin
        .from("jobs")
        .select("id, estado, estado_dispatch, descripcion, direccion, customer_id, technician_code, version, created_at, updated_at, prioridad, categoria")
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
