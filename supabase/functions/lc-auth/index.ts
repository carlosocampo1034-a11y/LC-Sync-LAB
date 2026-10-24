// lc-auth — public auth endpoint, verify_jwt = false
// Handles: login, register, password change
// profiles schema: id, role, technician_code, display_name, created_at, customer_id, email
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPA_URL = Deno.env.get("SUPABASE_URL")!;
const SUPA_SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const SUPA_ANON = Deno.env.get("SUPABASE_ANON_KEY")!;

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "Content-Type": "application/json" },
  });
}

function adminClient() {
  return createClient(SUPA_URL, SUPA_SERVICE, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });

  const url = new URL(req.url);
  const path = url.pathname.replace(/^\/functions\/v1\/lc-auth\/?/, "").replace(/\/$/, "");

  try {
    if (path === "register") return await handleRegister(req);
    if (path === "me/password") return await handlePasswordChange(req);
    return await handleLogin(req);
  } catch (e) {
    console.error("lc-auth error:", e);
    return json({ error: "error interno" }, 500);
  }
});

async function handleLogin(req: Request) {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  const body = await req.json().catch(() => ({}));
  // Support both email and technician_code as login identifier
  const identifier: string = body.email ?? body.username ?? body.technician_code ?? "";
  const password: string = body.password ?? body.contrasena ?? "";

  if (!identifier || !password) {
    return json({ error: "email/password requeridos" }, 400);
  }

  const admin = adminClient();
  let loginEmail = identifier;

  // If not an email, look up by technician_code → get auth user email via Admin API
  if (!identifier.includes("@")) {
    const { data: profile } = await admin
      .from("profiles")
      .select("id")
      .eq("technician_code", identifier)
      .maybeSingle();

    if (!profile?.id) return json({ error: "credenciales inválidas" }, 401);

    // Retrieve actual email from Supabase Auth (avoids needing email column in profiles)
    const { data: authUser, error: auErr } = await admin.auth.admin.getUserById(profile.id);
    if (auErr || !authUser.user?.email) return json({ error: "credenciales inválidas" }, 401);
    loginEmail = authUser.user.email;
  }

  const anonClient = createClient(SUPA_URL, SUPA_ANON, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
  const { data, error } = await anonClient.auth.signInWithPassword({
    email: loginEmail,
    password,
  });

  if (error || !data.session) {
    return json({ error: "credenciales inválidas" }, 401);
  }

  const { data: profile } = await admin
    .from("profiles")
    .select("role, technician_code, display_name")
    .eq("id", data.user.id)
    .maybeSingle();

  return json({
    token: data.session.access_token,
    refresh_token: data.session.refresh_token,
    expires_in: data.session.expires_in,
    role: profile?.role ?? "CUSTOMER",
    technician_code: profile?.technician_code ?? null,
    display_name: profile?.display_name ?? "",
    user_id: data.user.id,
  });
}

async function handleRegister(req: Request) {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  const body = await req.json().catch(() => ({}));
  const email: string = body.email ?? "";
  const password: string = body.password ?? "";
  const nombre: string = body.nombre ?? body.display_name ?? body.name ?? "";

  if (!email || !password || !nombre) {
    return json({ error: "email, password y nombre requeridos" }, 400);
  }
  if (nombre.length < 3) {
    return json({ error: "nombre demasiado corto (mín 3 caracteres)" }, 400);
  }

  const admin = adminClient();
  const { data, error } = await admin.auth.admin.createUser({
    email,
    password,
    email_confirm: true,
    user_metadata: { display_name: nombre, role: "CUSTOMER" },
  });

  if (error) {
    if (error.message.toLowerCase().includes("already registered")) {
      return json({ error: "email ya registrado" }, 409);
    }
    return json({ error: error.message }, 400);
  }

  await admin.from("profiles").upsert({
    id: data.user!.id,
    email,
    display_name: nombre,
    role: "CUSTOMER",
  });

  return json({ ok: true, user_id: data.user!.id }, 201);
}

async function handlePasswordChange(req: Request) {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  const authHeader = req.headers.get("Authorization") ?? "";
  const token = authHeader.replace(/^Bearer\s+/i, "");
  if (!token) return json({ error: "token requerido" }, 401);

  const body = await req.json().catch(() => ({}));
  const actual: string = body.actual ?? body.currentPassword ?? "";
  const nueva: string = body.nueva ?? body.newPassword ?? "";

  if (!actual || !nueva) {
    return json({ error: "campos 'actual' y 'nueva' requeridos" }, 400);
  }

  const uc = createClient(SUPA_URL, SUPA_ANON, {
    global: { headers: { Authorization: `Bearer ${token}` } },
    auth: { autoRefreshToken: false, persistSession: false },
  });
  const { data: userData, error: userErr } = await uc.auth.getUser();
  if (userErr || !userData.user) return json({ error: "token inválido" }, 401);

  // Verify current password by re-auth
  const anonClient = createClient(SUPA_URL, SUPA_ANON, {
    auth: { autoRefreshToken: false, persistSession: false },
  });
  const { error: signInErr } = await anonClient.auth.signInWithPassword({
    email: userData.user.email!,
    password: actual,
  });
  if (signInErr) return json({ error: "la contraseña actual no es correcta" }, 400);

  const admin = adminClient();
  const { error: updateErr } = await admin.auth.admin.updateUserById(userData.user.id, {
    password: nueva,
  });
  if (updateErr) return json({ error: updateErr.message }, 400);

  return json({ ok: true });
}
