-- L&C schema migration — additive only, never drops existing columns
-- Existing: jobs (integer customer_id, epoch-ms timestamps), profiles (no email col),
--           sync_receipts (device_id, job_id, client_updated_at, status, version, ts)
-- Adds: customer_uuid to jobs, email to profiles, properties table, RLS policies

-- ─── jobs — add UUID-based customer link for Supabase-native jobs ─────────────
ALTER TABLE IF EXISTS public.jobs
  ADD COLUMN IF NOT EXISTS customer_uuid TEXT;  -- Supabase Auth user UUID for new jobs

CREATE INDEX IF NOT EXISTS jobs_customer_uuid_idx ON public.jobs (customer_uuid)
  WHERE customer_uuid IS NOT NULL;

-- ─── profiles — add email for auth→profile lookup ─────────────────────────────
ALTER TABLE IF EXISTS public.profiles
  ADD COLUMN IF NOT EXISTS email TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS profiles_email_idx ON public.profiles (email)
  WHERE email IS NOT NULL;

-- ─── properties — customer service addresses ──────────────────────────────────
CREATE TABLE IF NOT EXISTS public.properties (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id TEXT NOT NULL,  -- Supabase Auth user UUID (TEXT for compatibility)
  direccion   TEXT NOT NULL,
  descripcion TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ─── Enable RLS ───────────────────────────────────────────────────────────────
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sync_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.properties ENABLE ROW LEVEL SECURITY;

-- ─── profiles RLS ─────────────────────────────────────────────────────────────
DROP POLICY IF EXISTS "profiles_self_read" ON public.profiles;
CREATE POLICY "profiles_self_read" ON public.profiles
  FOR SELECT USING (auth.uid() = id);

DROP POLICY IF EXISTS "profiles_service_all" ON public.profiles;
CREATE POLICY "profiles_service_all" ON public.profiles
  FOR ALL USING (auth.role() = 'service_role');

-- ─── jobs RLS ─────────────────────────────────────────────────────────────────
-- Customers see only their own jobs (new UUID-keyed jobs)
DROP POLICY IF EXISTS "jobs_customer_read" ON public.jobs;
CREATE POLICY "jobs_customer_read" ON public.jobs
  FOR SELECT USING (customer_uuid = auth.uid()::TEXT);

DROP POLICY IF EXISTS "jobs_customer_insert" ON public.jobs;
CREATE POLICY "jobs_customer_insert" ON public.jobs
  FOR INSERT WITH CHECK (customer_uuid = auth.uid()::TEXT);

-- Service role has full access (Edge Functions use service_role_key)
DROP POLICY IF EXISTS "jobs_service_all" ON public.jobs;
CREATE POLICY "jobs_service_all" ON public.jobs
  FOR ALL USING (auth.role() = 'service_role');

-- ─── sync_receipts RLS ────────────────────────────────────────────────────────
DROP POLICY IF EXISTS "receipts_service_all" ON public.sync_receipts;
CREATE POLICY "receipts_service_all" ON public.sync_receipts
  FOR ALL USING (auth.role() = 'service_role');

-- ─── properties RLS ───────────────────────────────────────────────────────────
DROP POLICY IF EXISTS "properties_owner" ON public.properties;
CREATE POLICY "properties_owner" ON public.properties
  FOR ALL USING (customer_id = auth.uid()::TEXT);

DROP POLICY IF EXISTS "properties_service_all" ON public.properties;
CREATE POLICY "properties_service_all" ON public.properties
  FOR ALL USING (auth.role() = 'service_role');

-- ─── Storage bucket for evidence ──────────────────────────────────────────────
INSERT INTO storage.buckets (id, name, public)
VALUES ('evidence', 'evidence', true)
ON CONFLICT (id) DO NOTHING;

DROP POLICY IF EXISTS "evidence_auth_upload" ON storage.objects;
CREATE POLICY "evidence_auth_upload" ON storage.objects
  FOR INSERT WITH CHECK (
    bucket_id = 'evidence' AND auth.role() = 'authenticated'
  );

DROP POLICY IF EXISTS "evidence_public_read" ON storage.objects;
CREATE POLICY "evidence_public_read" ON storage.objects
  FOR SELECT USING (bucket_id = 'evidence');

DROP POLICY IF EXISTS "evidence_service_all" ON storage.objects;
CREATE POLICY "evidence_service_all" ON storage.objects
  FOR ALL USING (bucket_id = 'evidence' AND auth.role() = 'service_role');
