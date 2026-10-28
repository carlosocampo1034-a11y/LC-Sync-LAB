-- Despacho automático: una orden nunca debe quedar sin ofertas por falta de un técnico con su especialidad exacta.
-- Bug: lc_dispatch_job exigía coincidencia de especialidad; con técnicos HVAC / Plomería / Electricidad, una orden de
-- ELECTRODOMESTICOS (u OTRO) no coincidía con nadie y se generaban 0 ofertas (el trigger SÍ corría).
-- Regla nueva: si hay técnicos disponibles con la especialidad, se ofrece solo a ellos; si no hay ninguno,
-- se ofrece a cualquier técnico activo y disponible (generalista). El resto de la lógica no cambia.
create or replace function public.lc_tech_matches(p_specialties text, p_cat text) returns boolean
language sql immutable set search_path = ''
as $$
  select btrim(coalesce(p_specialties, '')) = '' or coalesce(p_cat, '') = '' or exists (
    select 1 from unnest(regexp_split_to_array(public.lc_norm(p_specialties), '\s*[,;/]\s*')) s
     where s <> '' and (p_cat like '%' || s || '%' or s like '%' || p_cat || '%'))
$$;
revoke all on function public.lc_tech_matches(text, text) from public, anon, authenticated;
grant execute on function public.lc_tech_matches(text, text) to service_role;

create or replace function public.lc_dispatch_job(p_job bigint) returns integer
language plpgsql security definer set search_path = ''
as $$
declare
  v_cfg public.dispatch_config%rowtype;
  v_job public.jobs%rowtype;
  v_now bigint := public.lc_now_ms();
  v_open integer;
  v_made integer := 0;
  v_cat text;
  v_spec integer;
begin
  select * into v_cfg from public.dispatch_config where id = 1;
  if not found or not v_cfg.enabled then return 0; end if;
  select * into v_job from public.jobs where id = p_job for update;
  if not found or v_job.estado <> 'PENDIENTE' or v_job.technician_id is not null
     or coalesce(v_job.estado_dispatch, 'QUEUE') <> 'QUEUE' or v_job.origin = 'TECHNICIAN' then
    return 0;
  end if;

  update public.job_offers set estado = 'EXPIRADA', responded_at = v_now
   where job_id = p_job and estado = 'OFRECIDA' and expires_at < v_now;
  select count(*) into v_open from public.job_offers where job_id = p_job and estado = 'OFRECIDA';
  if v_open >= v_cfg.fanout then return 0; end if;
  v_cat := public.lc_norm(v_job.categoria);

  -- ¿hay técnicos activos y disponibles con la especialidad de la orden?
  select count(*) into v_spec from public.profiles p
   where p.role = 'TECHNICIAN' and p.active and p.available and public.lc_tech_matches(p.specialties, v_cat);

  with cand as (
    select p.id
      from public.profiles p
     where p.role = 'TECHNICIAN' and p.active and p.available
       and (v_spec = 0 or public.lc_tech_matches(p.specialties, v_cat))   -- sin especialistas: cualquiera
       and not exists (select 1 from public.jobs j where j.technician_id = p.id
                        and j.estado in ('EN RUTA', 'LLEGUÉ', 'EN REPARACIÓN', 'ESPERANDO APROBACIÓN'))
       and not exists (select 1 from public.job_offers o where o.technician_id = p.id
                        and o.estado = 'OFRECIDA' and o.expires_at >= v_now)
       and not exists (select 1 from public.job_offers o where o.technician_id = p.id and o.job_id = p_job)
     order by coalesce((select max(o.responded_at) from public.job_offers o
                         where o.technician_id = p.id and o.estado = 'ACEPTADA'), 0), p.id
     limit v_cfg.fanout - v_open
  ), ins as (
    insert into public.job_offers (job_id, technician_id, expires_at)
    select p_job, c.id, v_now + v_cfg.offer_ttl_seconds * 1000 from cand c
    returning 1
  )
  select count(*) into v_made from ins;
  return v_made;
end;
$$;
revoke all on function public.lc_dispatch_job(bigint) from public, anon, authenticated;
grant execute on function public.lc_dispatch_job(bigint) to service_role;
