-- L&C schema migration v2 — audited against real Supabase schema
-- SAFE: only additive DDL. Does NOT drop existing policies or columns.
-- Verified schema (2026-10-24):
--   profiles: id UUID, role TEXT, technician_code TEXT, display_name TEXT, created_at, customer_id
--   jobs: id BIGINT (no default!), version BIGINT, seq BIGINT, estado TEXT,
--         estado_dispatch TEXT, technician_code TEXT, customer_id (integer FK),
--         updated_at BIGINT (epoch ms), created_at BIGINT (epoch ms), ...
--   sync_receipts: device_id, job_id, client_updated_at BIGINT, status, version, ts TIMESTAMPTZ
--   storage.buckets: empty (no buckets yet)
-- Existing RLS behavior (tested):
--   anon → permission denied on jobs (good)
--   DISPATCHER → sees all jobs (correct for role)
--   TECHNICIAN → sees own jobs via technician_code (correct)
--   CUSTOMER → sees 0 jobs (correct, no integer customer_id match)

-- ─── 1. jobs.id: sequence for Edge Function-created jobs ─────────────────────
-- Flask uses counter starting at 8000000000000 (~8000000000049 current max).
-- Edge Functions use sequence starting at 9000000000001 — safe gap from Flask range.
-- If jobs.id already has a default (future schema change), this is a no-op.
CREATE SEQUENCE IF NOT EXISTS jobs_edge_id_seq
  START WITH 9000000000001
  INCREMENT BY 1
  NO CYCLE;

-- Only set the default if the column currently has no default
DO $$
BEGIN
  IF (SELECT column_default FROM information_schema.columns
      WHERE table_schema='public' AND table_name='jobs' AND column_name='id') IS NULL THEN
    ALTER TABLE public.jobs ALTER COLUMN id SET DEFAULT nextval('jobs_edge_id_seq');
  END IF;
END
$$;

-- ─── 2. profiles — add email column (for technician_code → email lookup) ──────
ALTER TABLE IF EXISTS public.profiles
  ADD COLUMN IF NOT EXISTS email TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS profiles_email_idx ON public.profiles (email)
  WHERE email IS NOT NULL;

-- ─── 3. jobs — add customer_uuid for Supabase-native (non-Flask) jobs ─────────
ALTER TABLE IF EXISTS public.jobs
  ADD COLUMN IF NOT EXISTS customer_uuid TEXT;

CREATE INDEX IF NOT EXISTS jobs_customer_uuid_idx ON public.jobs (customer_uuid)
  WHERE customer_uuid IS NOT NULL;

-- ─── 4. sync_receipts — add unique index for idempotent upsert ───────────────
-- upsert(job_id, version) prevents duplicate receipts when client retries
CREATE UNIQUE INDEX IF NOT EXISTS sync_receipts_job_version_idx
  ON public.sync_receipts (job_id, version);

-- ─── 5. properties table ─────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.properties (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_id TEXT NOT NULL,  -- Supabase Auth UUID stored as TEXT
  direccion   TEXT NOT NULL,
  descripcion TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ─── 6. Enable RLS (idempotent) ──────────────────────────────────────────────
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sync_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.properties ENABLE ROW LEVEL SECURITY;

-- ─── 7. RLS policies — additive only, named to avoid duplicates ──────────────
-- NOTE: Existing unnamed/differently-named policies remain in place.
-- We add named policies that complement the existing behavior.

-- profiles: read own row
DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename='profiles' AND policyname='lc_profiles_self_read'
  ) THEN
    CREATE POLICY lc_profiles_self_read ON public.profiles
      FOR SELECT USING (auth.uid() = id);
  END IF;
END $$;

-- profiles: service_role full access (needed by Edge Functions)
DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename='profiles' AND policyname='lc_profiles_service_all'
  ) THEN
    CREATE POLICY lc_profiles_service_all ON public.profiles
      FOR ALL USING (auth.role() = 'service_role');
  END IF;
END $$;

-- jobs: customer sees own Supabase-native jobs (via customer_uuid)
DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename='jobs' AND policyname='lc_jobs_customer_uuid_read'
  ) THEN
    CREATE POLICY lc_jobs_customer_uuid_read ON public.jobs
      FOR SELECT USING (customer_uuid = auth.uid()::TEXT);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename='jobs' AND policyname='lc_jobs_customer_uuid_insert'
  ) THEN
    CREATE POLICY lc_jobs_customer_uuid_insert ON public.jobs
      FOR INSERT WITH CHECK (customer_uuid = auth.uid()::TEXT);
  END IF;
END $$;

-- jobs: service_role full access (needed by Edge Functions using service key)
DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename='jobs' AND policyname='lc_jobs_service_all'
  ) THEN
    CREATE POLICY lc_jobs_service_all ON public.jobs
      FOR ALL USING (auth.role() = 'service_role');
  END IF;
END $$;

-- sync_receipts: service_role full access
DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename='sync_receipts' AND policyname='lc_receipts_service_all'
  ) THEN
    CREATE POLICY lc_receipts_service_all ON public.sync_receipts
      FOR ALL USING (auth.role() = 'service_role');
  END IF;
END $$;

-- properties: owner access
DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename='properties' AND policyname='lc_properties_owner'
  ) THEN
    CREATE POLICY lc_properties_owner ON public.properties
      FOR ALL USING (customer_id = auth.uid()::TEXT);
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname='public' AND tablename='properties' AND policyname='lc_properties_service_all'
  ) THEN
    CREATE POLICY lc_properties_service_all ON public.properties
      FOR ALL USING (auth.role() = 'service_role');
  END IF;
END $$;

-- ─── 8. Storage: PRIVATE evidence bucket ─────────────────────────────────────
-- public = false → no anonymous access; all access via signed URLs or service_role
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
  'evidence',
  'evidence',
  false,                                          -- PRIVATE — no public URL
  10485760,                                       -- 10 MB limit
  ARRAY['image/jpeg','image/png','image/webp','video/mp4','application/pdf']
)
ON CONFLICT (id) DO UPDATE
  SET public = false,
      file_size_limit = EXCLUDED.file_size_limit,
      allowed_mime_types = EXCLUDED.allowed_mime_types;

-- Storage RLS: authenticated technicians can upload their evidence
-- Technician can only upload to job_{id}/{their_technician_code}_* path
-- Dispatcher/Admin can read all evidence (signed URL via service_role bypasses RLS)
DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname='storage' AND tablename='objects' AND policyname='lc_evidence_technician_insert'
  ) THEN
    CREATE POLICY lc_evidence_technician_insert ON storage.objects
      FOR INSERT WITH CHECK (
        bucket_id = 'evidence'
        AND auth.role() = 'authenticated'
      );
  END IF;
END $$;

DO $$ BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE schemaname='storage' AND tablename='objects' AND policyname='lc_evidence_service_all'
  ) THEN
    CREATE POLICY lc_evidence_service_all ON storage.objects
      FOR ALL USING (bucket_id = 'evidence' AND auth.role() = 'service_role');
  END IF;
END $$;

-- ─── 9. Update QA persona profile emails (post-migration) ────────────────────
-- These UUIDs match auth users created in Fase 9 setup
UPDATE public.profiles SET email = 'tec.fase5@lc-lab.io'
  WHERE id = 'f30c2eba-d511-49a7-b792-f39924e88df1' AND email IS NULL;
UPDATE public.profiles SET email = 'testcust9@lc-lab.io'
  WHERE id = '8fcc79f4-9420-4935-acad-ce13e207b37b' AND email IS NULL;
UPDATE public.profiles SET email = 'dispatch9@lc-lab.io'
  WHERE id = 'd36eecca-3a9e-47ae-a323-b425bb4f90cb' AND email IS NULL;
