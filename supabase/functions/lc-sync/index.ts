// lc-sync — technician offline-first sync (JWT required, TECHNICIAN role)
// Handles: pull assigned jobs, push state transitions, upload evidence
// profiles schema: id, role, technician_code, display_name, customer_id, email
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPA_URL = Deno.env.get("SUPABASE_URL")!;
const SUPA_SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const SUPA_ANON = Deno.env.get("SUPABASE_ANON_KEY")!;

const VALID_STATES = new Set(["PENDIENTE", "EN RUTA", "LLEGUÉ", "EN REPARACIÓN", "COMPLETADO"]);

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

interface SyncJob {
  id: number;
  estado: string;
  version: number;
  updatedAt?: number; // epoch ms — optional (server uses now() as fallback)
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

  const admin = adminClient();

  const { data: profile } = await admin
    .from("profiles")
    .select("role, technician_code")
    .eq("id", auth.user.id)
    .maybeSingle();

  if (!profile || !["TECHNICIAN", "ADMIN"].includes(profile.role)) {
    return json({ error: "rol TECHNICIAN requerido" }, 403);
  }

  const techCode: string = profile.technician_code ?? auth.user.id;

  const url = new URL(req.url);
  const path = url.pathname.replace(/^\/functions\/v1\/lc-sync\/?/, "").replace(/\/$/, "");

  try {
    // GET /lc-sync/pull
    if (req.method === "GET" && (path === "pull" || path === "")) {
      const since = parseInt(url.searchParams.get("since") ?? "0");
      const sinceIso = since > 0 ? new Date(since).toISOString() : new Date(0).toISOString();

      const { data: jobs, error } = await admin
        .from("jobs")
        .select("id, estado, descripcion, direccion, customer_id, version, updated_at, created_at, categoria, prioridad")
        .eq("technician_code", techCode)
        .gte("updated_at", sinceIso)
        .order("updated_at", { ascending: true });

      if (error) return json({ error: error.message }, 500);

      return json({
        jobs: (jobs ?? []).map((j) => ({
          ...j,
          updatedAt: new Date(j.updated_at).getTime(),
        })),
        serverTime: Date.now(),
      });
    }

    // POST /lc-sync/push
    if (req.method === "POST" && path === "push") {
      const body = await req.json().catch(() => ({}));
      const { deviceId, jobs } = body as { deviceId?: string; jobs?: SyncJob[] };

      if (!Array.isArray(jobs) || jobs.length === 0) {
        return json({ error: "array 'jobs' requerido" }, 400);
      }

      const results: Array<{
        id: number;
        status: string;
        reason?: string;
        version?: number;
      }> = [];

      for (const item of jobs) {
        if (typeof item.id !== "number" || !item.estado || typeof item.version !== "number") {
          results.push({ id: item.id, status: "ERROR", reason: "id, estado, version requeridos" });
          continue;
        }

        if (!VALID_STATES.has(item.estado)) {
          results.push({ id: item.id, status: "ERROR", reason: `estado desconocido: ${item.estado}` });
          continue;
        }

        const { data: current, error: fetchErr } = await admin
          .from("jobs")
          .select("id, estado, version, technician_code")
          .eq("id", item.id)
          .maybeSingle();

        if (fetchErr || !current) {
          results.push({ id: item.id, status: "ERROR", reason: "trabajo no encontrado" });
          continue;
        }

        if (current.technician_code !== techCode) {
          results.push({ id: item.id, status: "ERROR", reason: "trabajo no asignado a este técnico" });
          continue;
        }

        if (item.version !== current.version + 1) {
          results.push({
            id: item.id,
            status: "CONFLICT",
            reason: `versión ${item.version} desfasada`,
            version: current.version,
          });
          continue;
        }

        // jobs.updated_at is stored as bigint (epoch ms), not ISO timestamp
        const updatedAt = item.updatedAt ?? Date.now();

        const { data: updated, error: upErr } = await admin
          .from("jobs")
          .update({ estado: item.estado, version: item.version, updated_at: updatedAt })
          .eq("id", item.id)
          .select("id, version")
          .single();

        if (upErr) {
          results.push({ id: item.id, status: "ERROR", reason: upErr.message });
          continue;
        }

        // sync_receipts schema: device_id, job_id, client_updated_at (epoch ms), status, version, ts (auto)
        await admin.from("sync_receipts").insert({
          job_id: item.id,
          device_id: deviceId ?? techCode,
          client_updated_at: item.updatedAt ?? Date.now(),
          status: "ACCEPTED",
          version: item.version,
        });

        results.push({ id: item.id, status: "ACCEPTED", version: updated!.version });
      }

      return json({ results });
    }

    // POST /lc-sync/evidence/:jobId
    const evidenceMatch = path.match(/^evidence\/(\d+)$/);
    if (req.method === "POST" && evidenceMatch) {
      const jid = parseInt(evidenceMatch[1]);

      const { data: job } = await admin
        .from("jobs")
        .select("id, technician_code")
        .eq("id", jid)
        .maybeSingle();

      if (!job || job.technician_code !== techCode) {
        return json({ error: "trabajo no encontrado o no asignado" }, 404);
      }

      const contentType = req.headers.get("content-type") ?? "";
      let fileData: Uint8Array;
      let mimeType = "image/jpeg";
      let ext = "jpg";

      if (contentType.includes("multipart/form-data")) {
        const form = await req.formData();
        const file = form.get("evidence") as File | null;
        if (!file) return json({ error: "campo 'evidence' requerido" }, 400);
        fileData = new Uint8Array(await file.arrayBuffer());
        mimeType = file.type || mimeType;
        ext = (file.name.split(".").pop() ?? ext).toLowerCase();
      } else {
        const b = await req.json().catch(() => ({}));
        if (!b.data) return json({ error: "campo 'data' (base64) o multipart requerido" }, 400);
        const b64 = (b.data as string).replace(/^data:[^;]+;base64,/, "");
        fileData = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
        mimeType = b.mimeType ?? mimeType;
        ext = (b.ext ?? ext).toLowerCase();
      }

      const fileName = `job_${jid}/${techCode}_${Date.now()}.${ext}`;
      const storageAdmin = createClient(SUPA_URL, SUPA_SERVICE, {
        auth: { autoRefreshToken: false, persistSession: false },
      });

      const { error: uploadErr } = await storageAdmin.storage
        .from("evidence")
        .upload(fileName, fileData, { contentType: mimeType, upsert: false });

      if (uploadErr) return json({ error: uploadErr.message }, 500);

      const { data: urlData } = storageAdmin.storage.from("evidence").getPublicUrl(fileName);
      return json({ ok: true, url: urlData.publicUrl });
    }

    return json({ error: "ruta no encontrada" }, 404);
  } catch (e) {
    console.error("lc-sync error:", e);
    return json({ error: "error interno" }, 500);
  }
});
