-- ===================================================
-- L&C LC-Sync-LAB · Migración completa de sincronización
-- Generado: 2026-10-19
-- Aplicar en: Supabase SQL Editor (proyecto rmkduyrrjtqqvjpqhyra)
-- IDEMPOTENTE: usa IF NOT EXISTS, OR REPLACE, etc.
-- ===================================================

-- ─── BLOQUE 1: Roles y funciones (20261018_rol_sync.sql) ─────────────────────

-- L&C · LC-Sync-LAB · identidad técnica SYNC (escritor único: Flask LAB). Mínimo privilegio, sin DELETE.
-- Aplicar como administrador del proyecto. No toca datos existentes ni las políticas de ADMIN/DISPATCHER/TECHNICIAN/CUSTOMER.
begin;

-- 1) Admitir el rol SYNC en profiles.role (se recrea solo la restricción CHECK de role)
do $$
declare n int; c text;
begin
  -- como máximo una restricción CHECK que nombre la columna role; si hay varias se aborta sin cambiar nada
  select count(*), max(conname) into n, c from pg_constraint
   where conrelid = 'public.profiles'::regclass and contype = 'c'
     and pg_get_constraintdef(oid) ~ '\mrole\M';
  if n > 1 then raise exception 'profiles: % restricciones CHECK sobre role; revisar a mano', n; end if;
  if c is not null then execute format('alter table public.profiles drop constraint %I', c); end if;
end $$;
alter table public.profiles add constraint profiles_role_check
  check (role in ('ADMIN','DISPATCHER','TECHNICIAN','CUSTOMER','SYNC'));

-- 2) ¿El usuario actual es SYNC? (security definer: no depende de las políticas de profiles)
create or replace function public.lc_es_sync() returns boolean
  language sql stable security definer set search_path = public
as $$ select exists (select 1 from public.profiles where id = auth.uid() and role = 'SYNC') $$;
revoke all on function public.lc_es_sync() from public, anon;
grant execute on function public.lc_es_sync() to authenticated;

-- 3) jobs: SYNC lee, inserta y actualiza (la guarda de versión la aplica el espejo en el filtro del PATCH)
create policy jobs_sync_select on public.jobs for select to authenticated using (public.lc_es_sync());
create policy jobs_sync_insert on public.jobs for insert to authenticated with check (public.lc_es_sync());
create policy jobs_sync_update on public.jobs for update to authenticated
  using (public.lc_es_sync()) with check (public.lc_es_sync());

-- 4) job_history y sync_receipts: solo SYNC puede leer/insertar (inmutables: sin UPDATE ni DELETE)
grant select, insert on public.job_history, public.sync_receipts to authenticated;
create policy history_sync_select  on public.job_history   for select to authenticated using (public.lc_es_sync());
create policy history_sync_insert  on public.job_history   for insert to authenticated with check (public.lc_es_sync());
create policy receipts_sync_select on public.sync_receipts for select to authenticated using (public.lc_es_sync());
create policy receipts_sync_insert on public.sync_receipts for insert to authenticated with check (public.lc_es_sync());

-- 5) profiles: SYNC ve solo los perfiles TECHNICIAN (para traducir technician_code → technician_id)
create policy profiles_sync_select_tecnicos on public.profiles for select to authenticated
  using (public.lc_es_sync() and role = 'TECHNICIAN');

commit;

-- Verificación (debe devolver 0 filas: el espejo envía job_history.id explícito)
-- select column_name, identity_generation from information_schema.columns
--  where table_schema='public' and table_name='job_history' and column_name='id' and identity_generation='ALWAYS';


-- ─── BLOQUE 2: RLS en job_history y sync_receipts (FALTANTE) ─────────────────

-- Activar RLS (por si acaso no está activado)
alter table public.job_history   enable row level security;
alter table public.sync_receipts enable row level security;

-- SYNC ya tiene políticas del bloque 1.
-- Agregar acceso de lectura para roles ADMIN y DISPATCHER en job_history
do $$ begin
  if not exists (
    select 1 from pg_policies where tablename='job_history' and policyname='history_admin_select'
  ) then
    execute $p$
      create policy history_admin_select on public.job_history
        for select to authenticated
        using ( exists (select 1 from public.profiles where id = auth.uid() and role in ('ADMIN','DISPATCHER')) )
    $p$;
  end if;
end $$;

-- ─── BLOQUE 3: Perfiles TECHNICIAN para técnicos del laboratorio ──────────────

-- Primero: necesitamos los UUIDs de auth.users para cada técnico LAB.
-- Si los técnicos LAB aún no tienen cuentas Supabase, insertar aquí con
-- una contraseña temporal. Si ya existen, este bloque solo upserta el perfil.

-- Técnicos con IDs de marcador (reemplazar con UUIDs reales de auth.users):
-- Si el técnico no tiene cuenta Supabase todavía, agregar a continuación.
-- Ejemplo para tec-lab-01 (reemplazar <UUID_TEC_LAB_01> con el UUID real):
/*
insert into public.profiles (id, role, display_name, technician_code)
values ('<UUID_TEC_LAB_01>', 'TECHNICIAN', 'Técnico LAB 1', 'tec-lab-01')
on conflict (id) do update set
  role = excluded.role,
  display_name = excluded.display_name,
  technician_code = excluded.technician_code;

insert into public.profiles (id, role, display_name, technician_code)
values ('<UUID_TEC_LAB_02>', 'TECHNICIAN', 'Técnico LAB 2', 'tec-lab-02')
on conflict (id) do update set
  role = excluded.role,
  display_name = excluded.display_name,
  technician_code = excluded.technician_code;
*/

-- ─── BLOQUE 4: Usuario SYNC (escritor único del espejo Flask→Supabase) ────────
-- UUID asignado localmente: 01d95aa8-45be-4617-be91-019c96a8fc1c
-- Email SYNC: lc.sync.mirror@gmail.com
-- NOTA: el password se guarda cifrado con bcrypt. La clave original
--       está en ~/LC/lab/backend/instance/.sync_pending_setup (permisos 600).

-- Crear el usuario en auth.users (si no existe)
do $$ begin
  if not exists (select 1 from auth.users where email = 'lc.sync.mirror@gmail.com') then
    insert into auth.users (
      instance_id,
      id,
      aud,
      role,
      email,
      encrypted_password,
      email_confirmed_at,
      raw_app_meta_data,
      raw_user_meta_data,
      created_at,
      updated_at,
      confirmation_token,
      recovery_token
    ) values (
      '00000000-0000-0000-0000-000000000000',
      '01d95aa8-45be-4617-be91-019c96a8fc1c'::uuid,
      'authenticated',
      'authenticated',
      'lc.sync.mirror@gmail.com',
      crypt('__SYNC_PASSWORD_PLACEHOLDER__', gen_salt('bf')),
      now(),
      '{"provider":"email","providers":["email"]}'::jsonb,
      '{}'::jsonb,
      now(),
      now(),
      '',
      ''
    );
    raise notice 'Usuario SYNC creado: %', '01d95aa8-45be-4617-be91-019c96a8fc1c';
  else
    raise notice 'Usuario SYNC ya existe (email=lc.sync.mirror@gmail.com)';
  end if;
end $$;

-- Perfil SYNC en public.profiles
insert into public.profiles (id, role, display_name)
values ('01d95aa8-45be-4617-be91-019c96a8fc1c'::uuid, 'SYNC', 'LC Mirror Flask→Supabase')
on conflict (id) do update set
  role = 'SYNC',
  display_name = excluded.display_name;

-- ─── BLOQUE 5: Verificación final ─────────────────────────────────────────────

select 'profiles' as tabla, role, count(*) as n
from public.profiles group by role
union all
select 'jobs', 'total', count(*) from public.jobs
union all
select 'job_history', 'total', count(*) from public.job_history
union all
select 'sync_receipts', 'total', count(*) from public.sync_receipts
order by 1, 2;
