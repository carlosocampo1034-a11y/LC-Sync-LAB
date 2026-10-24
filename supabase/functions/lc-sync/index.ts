// lc-sync — technician offline-first sync (JWT required, TECHNICIAN role)
// FIXES:
//   - pull: updated_at is BIGINT (epoch ms), filter numerically not ISO
//   - push: atomic CAS via version filter on PATCH (no READ-then-WRITE race)
//   - evidence: private bucket, returns signed URL (not public)
//   - pagination: cursor-based via ?since=<epoch_ms>&limit=<n>
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPA_URL = Deno.env.get("SUPABASE_URL")!;
const SUPA_SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const SUPA_ANON = Deno.env.get("SUPABASE_ANON_KEY")!;
const EVIDENCE_BUCKET = "evidence"; // private bucket
const SIGNED_URL_TTL = 3600; // 1 hour

// States the technician is allowed to set (not PENDIENTE — that's server-only)
const TECH_ALLOWED_STATES = new Set(["EN RUTA", "LLEGUÉ", "EN REPARACIÓN", "COMPLETADO"]);

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
  version: number;     // expected new version (current + 1)
  updatedAt?: number;  // epoch ms from device
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

  if (!profile.technician_code) {
    return json({ error: "perfil de técnico incompleto: technician_code ausente" }, 403);
  }
  const techCode: string = profile.technician_code;

  const url = new URL(req.url);
  const path = url.pathname.replace(/^\/functions\/v1\/lc-sync\/?/, "").replace(/\/$/, "");

  try {
    // ─── GET /lc-sync/pull ─────────────────────────────────────────────────────
    // Cursor pagination: ?since=<epoch_ms>&limit=<n>&cursor=<epoch_ms>
    // updated_at is BIGINT — compare numerically, never as ISO string
    if (req.method === "GET" && (path === "pull" || path === "")) {
      const since = parseInt(url.searchParams.get("since") ?? "0") || 0;
      const limitParam = Math.min(parseInt(url.searchParams.get("limit") ?? "100"), 500);
      // cursor is the last updated_at seen — for next page
      const cursor = parseInt(url.searchParams.get("cursor") ?? String(since)) || since;

      const { data: jobs, error } = await admin
        .from("jobs")
        .select("id, estado, descripcion, direccion, version, updated_at, created_at, categoria, prioridad, technician_code")
        .eq("technician_code", techCode)
        .gte("updated_at", cursor)   // BIGINT >= BIGINT, PostgREST handles numeric
        .order("updated_at", { ascending: true })
        .limit(limitParam);

      if (error) return json({ error: error.message }, 500);

      const list = jobs ?? [];
      const nextCursor = list.length === limitParam
        ? list[list.length - 1].updated_at + 1  // next page starts after last record
        : null;

      return json({
        jobs: list.map((j) => ({
          id: j.id,
          estado: j.estado,
          descripcion: j.descripcion,
          direccion: j.direccion,
          version: j.version,
          updatedAt: j.updated_at,   // already epoch ms (BIGINT)
          createdAt: j.created_at,
          categoria: j.categoria,
          prioridad: j.prioridad,
        })),
        serverTime: Date.now(),
        nextCursor,                  // null = no more pages
        hasMore: nextCursor !== null,
      });
    }

    // ─── POST /lc-sync/push ────────────────────────────────────────────────────
    // Atomic CAS: PATCH WHERE id=? AND technician_code=? AND version=?(prev)
    // If 0 rows affected → conflict. No separate read-then-write.
    if (req.method === "POST" && path === "push") {
      const body = await req.json().catch(() => ({}));
      const { deviceId, jobs } = body as { deviceId?: string; jobs?: SyncJob[] };

      if (!Array.isArray(jobs) || jobs.length === 0) {
        return json({ error: "array 'jobs' requerido" }, 400);
      }
      if (jobs.length > 50) {
        return json({ error: "máximo 50 items por push" }, 400);
      }

      const results: Array<{
        id: number;
        status: string;
        reason?: string;
        version?: number;
      }> = [];

      for (const item of jobs) {
        // Validate payload
        if (
          typeof item.id !== "number" ||
          !item.estado ||
          typeof item.version !== "number" ||
          item.version < 2
        ) {
          results.push({ id: item.id, status: "ERROR", reason: "id (number), estado y version>=2 requeridos" });
          continue;
        }

        if (!TECH_ALLOWED_STATES.has(item.estado)) {
          results.push({
            id: item.id,
            status: "ERROR",
            reason: `estado '${item.estado}' no permitido para técnico. Válidos: EN RUTA, LLEGUÉ, EN REPARACIÓN, COMPLETADO`,
          });
          continue;
        }

        const updatedAt = item.updatedAt ?? Date.now();
        const prevVersion = item.version - 1; // CAS: expected current version on server

        // Atomic CAS: only updates if technician_code AND version match simultaneously
        // If another push already bumped the version, this returns 0 rows → CONFLICT
        const { data: updated, error: upErr } = await admin
          .from("jobs")
          .update({
            estado: item.estado,
            version: item.version,
            updated_at: updatedAt,
          })
          .eq("id", item.id)
          .eq("technician_code", techCode)    // ownership enforced at DB level
          .eq("version", prevVersion)          // CAS — reject stale updates
          .select("id, version, estado")
          .maybeSingle();

        if (upErr) {
          results.push({ id: item.id, status: "ERROR", reason: upErr.message });
          continue;
        }

        if (!updated) {
          // 0 rows matched — either version conflict or ownership violation
          // Fetch current state to return informative conflict detail
          const { data: current } = await admin
            .from("jobs")
            .select("id, version, technician_code, estado")
            .eq("id", item.id)
            .maybeSingle();

          if (!current) {
            results.push({ id: item.id, status: "ERROR", reason: "trabajo no encontrado" });
          } else if (current.technician_code !== techCode) {
            results.push({ id: item.id, status: "ERROR", reason: "trabajo no asignado a este técnico" });
          } else {
            results.push({
              id: item.id,
              status: "CONFLICT",
              reason: `versión ${prevVersion} desfasada (servidor tiene v${current.version})`,
              version: current.version,
            });
          }
          continue;
        }

        // Record receipt (idempotent: unique (job_id, version) prevents duplicates if client retries)
        await admin.from("sync_receipts").upsert(
          {
            job_id: item.id,
            device_id: deviceId ?? techCode,
            client_updated_at: updatedAt,
            status: "ACCEPTED",
            version: item.version,
          },
          { onConflict: "job_id,version", ignoreDuplicates: true }
        ).catch(() => { /* receipt failure non-fatal */ });

        results.push({ id: item.id, status: "ACCEPTED", version: updated.version });
      }

      return json({ results });
    }

    // ─── POST /lc-sync/evidence/:jobId ────────────────────────────────────────
    // Private bucket. Returns signed URL (not public URL).
    const evidenceMatch = path.match(/^evidence\/(\d+)$/);
    if (req.method === "POST" && evidenceMatch) {
      const jid = parseInt(evidenceMatch[1]);

      // Verify job ownership (no separate profile lookup needed — techCode already verified)
      const { data: job } = await admin
        .from("jobs")
        .select("id, technician_code, estado")
        .eq("id", jid)
        .maybeSingle();

      if (!job) return json({ error: "trabajo no encontrado" }, 404);
      if (job.technician_code !== techCode) return json({ error: "trabajo no asignado a este técnico" }, 403);
      if (job.estado === "COMPLETADO") return json({ error: "no se puede adjuntar evidencia a trabajo COMPLETADO" }, 409);

      const contentType = req.headers.get("content-type") ?? "";
      let fileData: Uint8Array;
      let mimeType = "image/jpeg";
      let ext = "jpg";

      if (contentType.includes("multipart/form-data")) {
        const form = await req.formData();
        const file = form.get("evidence") as File | null;
        if (!file) return json({ error: "campo 'evidence' requerido" }, 400);
        // Limit: 10 MB
        if (file.size > 10 * 1024 * 1024) return json({ error: "archivo demasiado grande (máx 10 MB)" }, 413);
        fileData = new Uint8Array(await file.arrayBuffer());
        mimeType = file.type || mimeType;
        ext = (file.name.split(".").pop() ?? ext).toLowerCase().replace(/[^a-z0-9]/g, "");
      } else {
        const b = await req.json().catch(() => ({}));
        if (!b.data) return json({ error: "campo 'data' (base64) o multipart requerido" }, 400);
        const b64 = (b.data as string).replace(/^data:[^;]+;base64,/, "");
        fileData = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
        if (fileData.length > 10 * 1024 * 1024) return json({ error: "archivo demasiado grande (máx 10 MB)" }, 413);
        mimeType = b.mimeType ?? mimeType;
        ext = ((b.ext ?? ext) as string).toLowerCase().replace(/[^a-z0-9]/g, "");
      }

      // Path: job_{id}/{techCode}_{timestamp}.{ext}  — scoped to job + technician
      const fileName = `job_${jid}/${techCode}_${Date.now()}.${ext}`;

      const { error: uploadErr } = await admin.storage
        .from(EVIDENCE_BUCKET)
        .upload(fileName, fileData, { contentType: mimeType, upsert: false });

      if (uploadErr) return json({ error: uploadErr.message }, 500);

      // Return signed URL (private bucket — no public access)
      const { data: signedData, error: signErr } = await admin.storage
        .from(EVIDENCE_BUCKET)
        .createSignedUrl(fileName, SIGNED_URL_TTL);

      if (signErr || !signedData) return json({ ok: true, path: fileName, url: null });

      return json({ ok: true, path: fileName, url: signedData.signedUrl, expiresIn: SIGNED_URL_TTL });
    }

    return json({ error: "ruta no encontrada" }, 404);
  } catch (e) {
    console.error("lc-sync error:", e);
    return json({ error: "error interno" }, 500);
  }
});
