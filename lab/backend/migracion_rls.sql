-- =============================================================================
-- L&C LC-Sync-LAB · MIGRACIÓN RLS COMPLETA
-- Proyecto: rmkduyrrjtqqvjpqhyra
-- Generado: 2026-10-20 — Claude Code
-- Aplicar: Supabase SQL Editor (una sola vez, idempotente)
-- =============================================================================

BEGIN;

-- ─────────────────────────────────────────────────────────────────────────────
-- 0. RESTRICCIÓN CHECK DE ROLES EN PROFILES
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
DECLARE n INT; c TEXT;
BEGIN
  SELECT count(*), max(conname) INTO n, c
  FROM pg_constraint
  WHERE conrelid = 'public.profiles'::regclass
    AND contype = 'c'
    AND pg_get_constraintdef(oid) ~ '\mrole\M';
  IF n > 1 THEN
    RAISE EXCEPTION 'profiles: % restricciones CHECK sobre role; revisar manualmente', n;
  END IF;
  IF c IS NOT NULL THEN
    EXECUTE format('ALTER TABLE public.profiles DROP CONSTRAINT %I', c);
  END IF;
END $$;

ALTER TABLE public.profiles
  ADD CONSTRAINT profiles_role_check
  CHECK (role IN ('ADMIN','DISPATCHER','TECHNICIAN','CUSTOMER','SYNC'));

-- ─────────────────────────────────────────────────────────────────────────────
-- 1. FUNCIONES AUXILIARES DE SEGURIDAD
-- ─────────────────────────────────────────────────────────────────────────────

-- ¿Es el usuario actual del rol especificado?
CREATE OR REPLACE FUNCTION public.lc_tiene_rol(p_role TEXT)
RETURNS BOOLEAN
LANGUAGE SQL STABLE SECURITY DEFINER
SET search_path = public
AS $$
  SELECT EXISTS (
    SELECT 1 FROM public.profiles
    WHERE id = auth.uid() AND role = p_role
  )
$$;
REVOKE ALL ON FUNCTION public.lc_tiene_rol(TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.lc_tiene_rol(TEXT) TO authenticated;

-- ¿Es el usuario ADMIN o DISPATCHER?
CREATE OR REPLACE FUNCTION public.lc_es_control()
RETURNS BOOLEAN
LANGUAGE SQL STABLE SECURITY DEFINER
SET search_path = public
AS $$
  SELECT EXISTS (
    SELECT 1 FROM public.profiles
    WHERE id = auth.uid() AND role IN ('ADMIN','DISPATCHER')
  )
$$;
REVOKE ALL ON FUNCTION public.lc_es_control() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.lc_es_control() TO authenticated;

-- ¿Es el usuario SYNC? (escritor del espejo Flask→Supabase)
CREATE OR REPLACE FUNCTION public.lc_es_sync()
RETURNS BOOLEAN
LANGUAGE SQL STABLE SECURITY DEFINER
SET search_path = public
AS $$
  SELECT EXISTS (
    SELECT 1 FROM public.profiles
    WHERE id = auth.uid() AND role = 'SYNC'
  )
$$;
REVOKE ALL ON FUNCTION public.lc_es_sync() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.lc_es_sync() TO authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- 2. REVOCAR PERMISOS EXCESIVOS DEL ROL ANON
-- ─────────────────────────────────────────────────────────────────────────────
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public FROM anon;
REVOKE ALL ON SCHEMA public FROM anon;

-- anon solo puede invocar funciones explícitamente autorizadas (ninguna actualmente)
-- Para login/auth, Supabase usa el endpoint /auth/v1 (no REST directo)

-- ─────────────────────────────────────────────────────────────────────────────
-- 3. TABLA: public.jobs
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE public.jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.jobs FORCE ROW LEVEL SECURITY;

-- ADMIN/DISPATCHER: acceso completo
DROP POLICY IF EXISTS jobs_control_all   ON public.jobs;
CREATE POLICY jobs_control_all ON public.jobs
  FOR ALL TO authenticated
  USING (public.lc_es_control())
  WITH CHECK (public.lc_es_control());

-- TECHNICIAN: solo sus propias órdenes asignadas
DROP POLICY IF EXISTS jobs_technician_select ON public.jobs;
CREATE POLICY jobs_technician_select ON public.jobs
  FOR SELECT TO authenticated
  USING (
    public.lc_tiene_rol('TECHNICIAN')
    AND technician_code = (
      SELECT technician_code FROM public.profiles WHERE id = auth.uid()
    )
  );

DROP POLICY IF EXISTS jobs_technician_update ON public.jobs;
CREATE POLICY jobs_technician_update ON public.jobs
  FOR UPDATE TO authenticated
  USING (
    public.lc_tiene_rol('TECHNICIAN')
    AND technician_code = (
      SELECT technician_code FROM public.profiles WHERE id = auth.uid()
    )
  )
  WITH CHECK (
    public.lc_tiene_rol('TECHNICIAN')
    AND technician_code = (
      SELECT technician_code FROM public.profiles WHERE id = auth.uid()
    )
  );

-- CUSTOMER: solo sus propias órdenes
DROP POLICY IF EXISTS jobs_customer_select ON public.jobs;
CREATE POLICY jobs_customer_select ON public.jobs
  FOR SELECT TO authenticated
  USING (
    public.lc_tiene_rol('CUSTOMER')
    AND customer_id = (
      SELECT customer_id FROM public.profiles WHERE id = auth.uid()
    )
  );

-- SYNC: lectura, inserción y actualización (espejo Flask→Supabase)
DROP POLICY IF EXISTS jobs_sync_select ON public.jobs;
CREATE POLICY jobs_sync_select ON public.jobs
  FOR SELECT TO authenticated
  USING (public.lc_es_sync());

DROP POLICY IF EXISTS jobs_sync_insert ON public.jobs;
CREATE POLICY jobs_sync_insert ON public.jobs
  FOR INSERT TO authenticated
  WITH CHECK (public.lc_es_sync());

DROP POLICY IF EXISTS jobs_sync_update ON public.jobs;
CREATE POLICY jobs_sync_update ON public.jobs
  FOR UPDATE TO authenticated
  USING (public.lc_es_sync())
  WITH CHECK (public.lc_es_sync());

-- ─────────────────────────────────────────────────────────────────────────────
-- 4. TABLA: public.profiles
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.profiles FORCE ROW LEVEL SECURITY;

-- Cada usuario ve y edita únicamente su propio perfil
DROP POLICY IF EXISTS profiles_own ON public.profiles;
CREATE POLICY profiles_own ON public.profiles
  FOR ALL TO authenticated
  USING (id = auth.uid())
  WITH CHECK (id = auth.uid());

-- ADMIN puede ver y modificar todos los perfiles
DROP POLICY IF EXISTS profiles_admin_all ON public.profiles;
CREATE POLICY profiles_admin_all ON public.profiles
  FOR ALL TO authenticated
  USING (public.lc_tiene_rol('ADMIN'))
  WITH CHECK (public.lc_tiene_rol('ADMIN'));

-- SYNC puede ver perfiles TECHNICIAN (para traducir technician_code → id)
DROP POLICY IF EXISTS profiles_sync_technicians ON public.profiles;
CREATE POLICY profiles_sync_technicians ON public.profiles
  FOR SELECT TO authenticated
  USING (
    public.lc_es_sync()
    AND role = 'TECHNICIAN'
  );

-- ─────────────────────────────────────────────────────────────────────────────
-- 5. TABLA: public.users (tabla propia del backend Flask, no auth.users)
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.users FORCE ROW LEVEL SECURITY;

-- Solo ADMIN y SYNC pueden leer/escribir
DROP POLICY IF EXISTS users_admin_all ON public.users;
CREATE POLICY users_admin_all ON public.users
  FOR ALL TO authenticated
  USING (public.lc_es_control() OR public.lc_es_sync())
  WITH CHECK (public.lc_es_control() OR public.lc_es_sync());

-- ─────────────────────────────────────────────────────────────────────────────
-- 6. TABLA: public.sync_receipts
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE public.sync_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.sync_receipts FORCE ROW LEVEL SECURITY;

-- SYNC escribe recibos; ADMIN/DISPATCHER los lee (inmutables)
DROP POLICY IF EXISTS receipts_sync_insert ON public.sync_receipts;
CREATE POLICY receipts_sync_insert ON public.sync_receipts
  FOR INSERT TO authenticated
  WITH CHECK (public.lc_es_sync());

DROP POLICY IF EXISTS receipts_sync_select ON public.sync_receipts;
CREATE POLICY receipts_sync_select ON public.sync_receipts
  FOR SELECT TO authenticated
  USING (public.lc_es_sync() OR public.lc_es_control());

-- ─────────────────────────────────────────────────────────────────────────────
-- 7. TABLA: public.job_history (si existe)
-- ─────────────────────────────────────────────────────────────────────────────
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.tables
    WHERE table_schema = 'public' AND table_name = 'job_history'
  ) THEN
    EXECUTE 'ALTER TABLE public.job_history ENABLE ROW LEVEL SECURITY';
    EXECUTE 'ALTER TABLE public.job_history FORCE ROW LEVEL SECURITY';

    -- SYNC inserta historial
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='job_history' AND policyname='history_sync_insert') THEN
      EXECUTE $p$
        CREATE POLICY history_sync_insert ON public.job_history
          FOR INSERT TO authenticated
          WITH CHECK (public.lc_es_sync())
      $p$;
    END IF;

    -- ADMIN/DISPATCHER/SYNC leen historial
    IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='job_history' AND policyname='history_read') THEN
      EXECUTE $p$
        CREATE POLICY history_read ON public.job_history
          FOR SELECT TO authenticated
          USING (public.lc_es_control() OR public.lc_es_sync())
      $p$;
    END IF;
  END IF;
END $$;

-- ─────────────────────────────────────────────────────────────────────────────
-- 8. GRANTS MÍNIMOS PARA EL ROL authenticated
-- ─────────────────────────────────────────────────────────────────────────────
GRANT USAGE ON SCHEMA public TO authenticated;
GRANT SELECT, INSERT, UPDATE ON public.jobs          TO authenticated;
GRANT SELECT, INSERT, UPDATE ON public.profiles      TO authenticated;
GRANT SELECT                 ON public.users         TO authenticated;
GRANT SELECT, INSERT         ON public.sync_receipts TO authenticated;

-- ─────────────────────────────────────────────────────────────────────────────
-- 9. VERIFICACIÓN FINAL (devuelve estado de RLS por tabla)
-- ─────────────────────────────────────────────────────────────────────────────
SELECT
  schemaname,
  tablename,
  rowsecurity AS rls_enabled,
  (SELECT count(*) FROM pg_policies p WHERE p.schemaname=t.schemaname AND p.tablename=t.tablename) AS num_policies
FROM pg_tables t
WHERE schemaname = 'public'
ORDER BY tablename;

COMMIT;

-- =============================================================================
-- INSTRUCCIONES DE APLICACIÓN
-- =============================================================================
-- 1. Abrir: https://supabase.com/dashboard/project/rmkduyrrjtqqvjpqhyra/sql/new
-- 2. Pegar todo el contenido de este archivo
-- 3. Hacer clic en "Run"
-- 4. Verificar que la consulta final muestre rls_enabled=true en todas las tablas
-- 5. Confirmar que anon ya NO puede leer jobs ni users:
--    GET https://rmkduyrrjtqqvjpqhyra.supabase.co/rest/v1/jobs
--    con apikey=<anon_key> debe devolver [] (vacío) o 401
-- =============================================================================
