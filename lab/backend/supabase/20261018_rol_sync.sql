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
