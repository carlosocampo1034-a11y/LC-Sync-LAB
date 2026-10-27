-- Oferta automática de trabajos al técnico (auto-dispatch).
-- Una orden PENDIENTE sin técnico se ofrece a los técnicos disponibles y con la especialidad pedida. El primero
-- que acepta se queda con la orden (la fila de la orden se bloquea); el resto de ofertas se cancelan. Si todos
-- rechazan o no responden a tiempo, la oferta pasa a los siguientes. El despacho manual del Control sigue igual
-- y siempre gana: asignar a mano cancela las ofertas abiertas.
-- "ASIGNADA" = estado_dispatch ASSIGNED (así lo escribe el Control original); estado sigue PENDIENTE hasta salir.
-- Solo service_role ejecuta las funciones; RLS activa y sin políticas en las tablas nuevas.

alter table public.profiles add column if not exists available boolean not null default true;

create table if not exists public.dispatch_config (
  id integer primary key default 1 check (id = 1),
  enabled boolean not null default true,
  fanout integer not null default 3 check (fanout between 1 and 10),
  offer_ttl_seconds integer not null default 120 check (offer_ttl_seconds between 20 and 3600),
  updated_at bigint not null default public.lc_now_ms()
);
insert into public.dispatch_config (id) values (1) on conflict (id) do nothing;
alter table public.dispatch_config enable row level security;

create table if not exists public.job_offers (
  id bigint generated always as identity primary key,
  job_id bigint not null references public.jobs(id) on delete cascade,
  technician_id uuid not null references auth.users(id) on delete cascade,
  estado text not null default 'OFRECIDA'
    check (estado in ('OFRECIDA', 'ACEPTADA', 'RECHAZADA', 'EXPIRADA', 'CANCELADA')),
  motivo text,
  created_at bigint not null default public.lc_now_ms(),
  expires_at bigint not null,
  responded_at bigint
);
-- un técnico recibe como máximo una oferta por orden: tras rechazar o vencer no se le vuelve a ofrecer
create unique index if not exists job_offers_job_tech_uq on public.job_offers(job_id, technician_id);
create index if not exists job_offers_tech_estado_idx on public.job_offers(technician_id, estado);
create index if not exists job_offers_job_estado_idx on public.job_offers(job_id, estado);
alter table public.job_offers enable row level security;

create or replace function public.lc_norm(t text) returns text language sql immutable set search_path = ''
as $$ select lower(btrim(translate(coalesce(t, ''), 'ÁÉÍÓÚÜáéíóúüñÑ', 'AEIOUUaeiouunN'))) $$;

-- Crea ofertas para una orden hasta completar "fanout" ofertas abiertas. Devuelve cuántas creó.
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

  with cand as (
    select p.id
      from public.profiles p
     where p.role = 'TECHNICIAN' and p.active and p.available
       -- especialidad (profiles.specialties, texto separado por comas que ya edita el Control):
       -- vacía = generalista; sin categoría en la orden = cualquiera
       and (btrim(coalesce(p.specialties, '')) = '' or v_cat = '' or exists (
              select 1 from unnest(regexp_split_to_array(public.lc_norm(p.specialties), '\s*[,;/]\s*')) s
               where s <> '' and (v_cat like '%' || s || '%' or s like '%' || v_cat || '%')))
       -- disponible: sin trabajo en curso y sin otra oferta abierta
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

-- Vence ofertas viejas y reofrece las órdenes que siguen sin técnico. Se llama cuando alguien consulta ofertas.
create or replace function public.lc_dispatch_sweep() returns integer
language plpgsql security definer set search_path = ''
as $$
declare
  r record;
  v_total integer := 0;
begin
  update public.job_offers set estado = 'EXPIRADA', responded_at = public.lc_now_ms()
   where estado = 'OFRECIDA' and expires_at < public.lc_now_ms();
  for r in select id from public.jobs
            where estado = 'PENDIENTE' and technician_id is null and origin <> 'TECHNICIAN'
              and coalesce(estado_dispatch, 'QUEUE') = 'QUEUE'
            order by created_at limit 50 loop
    v_total := v_total + public.lc_dispatch_job(r.id);
  end loop;
  return v_total;
end;
$$;

create or replace function public.lc_offer_accept(p_actor uuid, p_offer bigint) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_role text;
  v_code text;
  v_o public.job_offers%rowtype;
  v_job public.jobs%rowtype;
  v_now bigint := public.lc_now_ms();
  v_next integer;
begin
  select role, technician_code into v_role, v_code from public.profiles where id = p_actor and active;
  if v_role is distinct from 'TECHNICIAN' then return jsonb_build_object('http', 403, 'error', 'role_denied'); end if;
  select * into v_o from public.job_offers where id = p_offer and technician_id = p_actor;
  if not found then return jsonb_build_object('http', 404, 'error', 'oferta_no_encontrada'); end if;

  -- la fila de la orden es el punto de serialización: solo uno la toma
  select * into v_job from public.jobs where id = v_o.job_id for update;
  select * into v_o from public.job_offers where id = p_offer for update;
  if v_o.estado = 'ACEPTADA' then
    return jsonb_build_object('http', 200, 'id', v_o.job_id, 'version', v_job.version, 'replay', true);
  end if;
  if v_o.estado <> 'OFRECIDA' then
    return jsonb_build_object('http', 409, 'error', 'oferta_cerrada',
                              'mensaje', 'Esta oferta ya no está disponible (la tomó otro técnico o fue asignada).');
  end if;
  if v_o.expires_at < v_now then
    update public.job_offers set estado = 'EXPIRADA', responded_at = v_now where id = v_o.id;
    return jsonb_build_object('http', 409, 'error', 'oferta_vencida', 'mensaje', 'La oferta venció.');
  end if;
  if v_job.technician_id is not null or v_job.estado <> 'PENDIENTE' then
    update public.job_offers set estado = 'CANCELADA', motivo = 'orden ya asignada', responded_at = v_now where id = v_o.id;
    return jsonb_build_object('http', 409, 'error', 'oferta_cerrada', 'mensaje', 'La orden ya fue asignada.');
  end if;
  if exists (select 1 from public.jobs j where j.technician_id = p_actor
              and j.estado in ('EN RUTA', 'LLEGUÉ', 'EN REPARACIÓN', 'ESPERANDO APROBACIÓN')) then
    return jsonb_build_object('http', 409, 'error', 'tecnico_ocupado', 'mensaje', 'Termina tu trabajo en curso antes de aceptar otro.');
  end if;

  update public.job_offers set estado = 'ACEPTADA', responded_at = v_now where id = v_o.id;
  v_next := v_job.version + 1;
  update public.jobs set technician_id = p_actor, technician_code = v_code, estado_dispatch = 'ASSIGNED',
         version = v_next, updated_at = v_now where id = v_job.id;
  insert into public.job_history (job_id, version, field, actor, old_value, new_value) values
    (v_job.id, v_next, 'technician_id', p_actor, null, p_actor::text),
    (v_job.id, v_next, 'estado_dispatch', p_actor, v_job.estado_dispatch, 'ASSIGNED');
  update public.job_offers set estado = 'CANCELADA', motivo = 'tomada por otro técnico', responded_at = v_now
   where job_id = v_job.id and estado = 'OFRECIDA';
  return jsonb_build_object('http', 200, 'id', v_job.id, 'version', v_next, 'estado_dispatch', 'ASSIGNED');
end;
$$;

create or replace function public.lc_offer_reject(p_actor uuid, p_offer bigint, p_motivo text) returns jsonb
language plpgsql security definer set search_path = ''
as $$
declare
  v_role text;
  v_o public.job_offers%rowtype;
  v_now bigint := public.lc_now_ms();
  v_n integer;
begin
  select role into v_role from public.profiles where id = p_actor and active;
  if v_role is distinct from 'TECHNICIAN' then return jsonb_build_object('http', 403, 'error', 'role_denied'); end if;
  select * into v_o from public.job_offers where id = p_offer and technician_id = p_actor for update;
  if not found then return jsonb_build_object('http', 404, 'error', 'oferta_no_encontrada'); end if;
  if v_o.estado = 'RECHAZADA' then return jsonb_build_object('http', 200, 'replay', true); end if;
  if v_o.estado <> 'OFRECIDA' then
    return jsonb_build_object('http', 409, 'error', 'oferta_cerrada', 'mensaje', 'Esta oferta ya no está disponible.');
  end if;
  update public.job_offers set estado = 'RECHAZADA', motivo = left(nullif(btrim(coalesce(p_motivo, '')), ''), 200),
         responded_at = v_now where id = v_o.id;
  v_n := public.lc_dispatch_job(v_o.job_id);   -- la oferta pasa al siguiente técnico disponible
  return jsonb_build_object('http', 200, 'siguientes', v_n);
end;
$$;

-- Nueva orden sin técnico → ofertas. Asignación manual, cancelación o cambio de estado → se cierran las ofertas abiertas.
create or replace function public.lc_jobs_dispatch_ai() returns trigger
language plpgsql security definer set search_path = ''
as $$
begin
  if new.technician_id is null and new.estado = 'PENDIENTE' and new.origin <> 'TECHNICIAN' then
    perform public.lc_dispatch_job(new.id);
  end if;
  return new;
end;
$$;

create or replace function public.lc_jobs_dispatch_au() returns trigger
language plpgsql security definer set search_path = ''
as $$
begin
  if new.technician_id is not null or new.estado <> 'PENDIENTE' then
    update public.job_offers set estado = 'CANCELADA', motivo = 'asignada o cerrada fuera de la oferta',
           responded_at = public.lc_now_ms()
     where job_id = new.id and estado = 'OFRECIDA';
  elsif new.origin <> 'TECHNICIAN' and coalesce(new.estado_dispatch, 'QUEUE') = 'QUEUE'
        and (old.technician_id is not null or old.estado is distinct from 'PENDIENTE'
             or coalesce(old.estado_dispatch, 'QUEUE') <> 'QUEUE') then
    perform public.lc_dispatch_job(new.id);   -- el Control la devolvió a la cola de despacho
  end if;
  return new;
end;
$$;

drop trigger if exists lc_jobs_dispatch_ai on public.jobs;
create trigger lc_jobs_dispatch_ai after insert on public.jobs
  for each row execute function public.lc_jobs_dispatch_ai();
drop trigger if exists lc_jobs_dispatch_au on public.jobs;
create trigger lc_jobs_dispatch_au after update of technician_id, estado, estado_dispatch on public.jobs
  for each row execute function public.lc_jobs_dispatch_au();

do $$
declare f text;
begin
  foreach f in array array[
    'lc_norm(text)', 'lc_dispatch_job(bigint)', 'lc_dispatch_sweep()', 'lc_offer_accept(uuid, bigint)',
    'lc_offer_reject(uuid, bigint, text)', 'lc_jobs_dispatch_ai()', 'lc_jobs_dispatch_au()'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
    execute format('grant execute on function public.%s to service_role', f);
  end loop;
end $$;
