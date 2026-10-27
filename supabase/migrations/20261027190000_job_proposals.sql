-- Propuestas Good/Better/Best: el técnico las envía, el cliente las aprueba o rechaza desde Customer.
-- Mismo contrato que lab/backend/lc_lab/proposals.py (revisiones, vigencia 7 días, decisión sobre el sha visto).
-- Las apps nunca tocan la tabla: todo pasa por lc-api (service_role). RLS activa y sin políticas.

create table if not exists public.job_proposals (
  id bigint generated always as identity primary key,
  job_id bigint not null references public.jobs(id) on delete cascade,
  origin_device text not null,
  local_id bigint not null,
  revision integer not null,
  opciones jsonb not null,
  contenido_sha256 text not null,
  estado text not null default 'PENDIENTE'
    check (estado in ('PENDIENTE', 'APROBADA', 'RECHAZADA', 'VENCIDA', 'SUPERADA')),
  opcion_elegida text,
  total_aceptado numeric,
  firmante text,
  firma_sha256 text,
  firma_png text,
  solicitada_por uuid references auth.users(id),
  decidida_por uuid references auth.users(id),
  decided_at bigint,
  expires_at bigint not null,
  created_at bigint not null default public.lc_now_ms(),
  updated_at bigint not null default public.lc_now_ms(),
  unique (origin_device, local_id, revision)
);
create index if not exists job_proposals_job_idx on public.job_proposals(job_id);
create index if not exists job_proposals_solicitada_idx on public.job_proposals(solicitada_por);
create index if not exists job_proposals_decidida_idx on public.job_proposals(decidida_por);
alter table public.job_proposals enable row level security;

-- Forma que leen las apps. Con p_tecnico no se incluyen las opciones (el técnico ya las tiene) sino el localId.
create or replace function public.lc_proposal_json(p public.job_proposals, p_tecnico boolean)
returns jsonb language sql stable set search_path = ''
as $$
  select jsonb_build_object(
    'id', p.id, 'jobId', p.job_id, 'revision', p.revision,
    'estado', case when p.estado = 'PENDIENTE' and public.lc_now_ms() > p.expires_at then 'VENCIDA' else p.estado end,
    'contenidoSha256', p.contenido_sha256, 'opcionElegida', p.opcion_elegida, 'totalAceptado', p.total_aceptado,
    'firmante', p.firmante, 'decidedAt', p.decided_at, 'expiresAt', p.expires_at, 'createdAt', p.created_at,
    'firmaSha256', p.firma_sha256)
  || case when p_tecnico then jsonb_build_object('localId', p.local_id) else jsonb_build_object('opciones', p.opciones) end;
$$;

-- Alta de una propuesta del técnico (idempotente por dispositivo + localId + contenido).
create or replace function public.lc_proposal_save(p_actor uuid, p_job bigint, p_device text, p_local bigint,
                                                   p_opciones jsonb, p_sha text)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
  v_role text;
  v_job public.jobs%rowtype;
  v_cur public.job_proposals%rowtype;
  v_new public.job_proposals%rowtype;
  v_now bigint := public.lc_now_ms();
  v_rev integer;
begin
  select role into v_role from public.profiles where id = p_actor and active;
  if v_role is null or v_role not in ('TECHNICIAN', 'DISPATCHER', 'ADMIN') then
    return jsonb_build_object('http', 403, 'error', 'role_denied');
  end if;
  if p_local is null or p_local <= 0 or p_device is null or length(p_device) not between 1 and 128 then
    return jsonb_build_object('http', 422, 'status', 'REJECTED', 'reason', 'localId inválido');
  end if;
  select * into v_job from public.jobs where id = p_job;
  if not found or (v_role = 'TECHNICIAN' and v_job.technician_id is distinct from p_actor) then
    return jsonb_build_object('http', 404, 'status', 'REJECTED', 'reason', 'orden desconocida o no asignada a este técnico');
  end if;
  if v_job.estado in ('COMPLETADO', 'CANCELADO') then
    return jsonb_build_object('http', 409, 'status', 'REJECTED', 'reason', 'la orden ya está cerrada');
  end if;

  select * into v_cur from public.job_proposals
   where origin_device = p_device and local_id = p_local order by revision desc limit 1;
  if found then
    if v_cur.job_id <> p_job then
      return jsonb_build_object('http', 409, 'status', 'REJECTED', 'reason', 'localId ya usado en otra orden');
    end if;
    if v_cur.contenido_sha256 = p_sha then
      return jsonb_build_object('http', 200, 'status', 'ACCEPTED') || public.lc_proposal_json(v_cur, true);
    end if;
    if v_cur.estado = 'PENDIENTE' then
      update public.job_proposals set estado = 'SUPERADA', updated_at = v_now where id = v_cur.id;
    end if;
    v_rev := v_cur.revision + 1;
  else
    v_rev := 1;
  end if;

  insert into public.job_proposals (job_id, origin_device, local_id, revision, opciones, contenido_sha256,
                                    solicitada_por, expires_at, created_at, updated_at)
  values (p_job, p_device, p_local, v_rev, p_opciones, p_sha, p_actor, v_now + 7 * 24 * 3600 * 1000, v_now, v_now)
  returning * into v_new;
  update public.jobs set updated_at = v_now where id = p_job;   -- el pull de las apps ve el cambio
  return jsonb_build_object('http', 201, 'status', 'ACCEPTED') || public.lc_proposal_json(v_new, true);
end;
$$;

-- Decisión del cliente sobre la versión exacta que vio. Aprobada → la orden pasa de ESPERANDO APROBACIÓN a EN REPARACIÓN.
create or replace function public.lc_proposal_decide(p_actor uuid, p_pid bigint, p_decision text, p_opcion text,
                                                     p_firmante text, p_sha text, p_firma_sha text, p_firma_png text)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
  v_role text;
  v_p public.job_proposals%rowtype;
  v_job public.jobs%rowtype;
  v_now bigint := public.lc_now_ms();
  v_op jsonb;
  v_next integer;
begin
  select role into v_role from public.profiles where id = p_actor and active;
  if v_role is distinct from 'CUSTOMER' then
    return jsonb_build_object('http', 403, 'error', 'role_denied');
  end if;
  select * into v_p from public.job_proposals where id = p_pid for update;
  if not found then return jsonb_build_object('http', 404, 'error', 'propuesta no encontrada'); end if;
  select * into v_job from public.jobs where id = v_p.job_id for update;
  if not found or v_job.customer_id is distinct from p_actor then
    return jsonb_build_object('http', 404, 'error', 'propuesta no encontrada');
  end if;

  if v_p.estado = 'PENDIENTE' and v_now > v_p.expires_at then
    update public.job_proposals set estado = 'VENCIDA', updated_at = v_now where id = v_p.id;
    return jsonb_build_object('http', 409, 'error', 'la propuesta venció; pide una nueva al técnico');
  end if;
  if v_p.estado = 'SUPERADA' then
    return jsonb_build_object('http', 409, 'error', 'esta versión de la propuesta fue reemplazada; revisa la nueva');
  end if;
  if v_p.estado = 'VENCIDA' then
    return jsonb_build_object('http', 409, 'error', 'la propuesta venció; pide una nueva al técnico');
  end if;
  if v_p.estado <> 'PENDIENTE' then
    return jsonb_build_object('http', 409, 'error', 'esta propuesta ya fue decidida');
  end if;
  if p_sha is distinct from v_p.contenido_sha256 then
    return jsonb_build_object('http', 409, 'error', 'la propuesta cambió desde que la abriste; recarga');
  end if;
  if p_decision not in ('APROBADA', 'RECHAZADA') then
    return jsonb_build_object('http', 400, 'error', 'decision debe ser APROBADA o RECHAZADA');
  end if;

  if p_decision = 'APROBADA' then
    select o into v_op from jsonb_array_elements(v_p.opciones) o where o->>'nivel' = p_opcion limit 1;
    if v_op is null then
      return jsonb_build_object('http', 400, 'error', 'elige una de las opciones ofrecidas');
    end if;
    if p_firmante is null or length(btrim(p_firmante)) < 3 then
      return jsonb_build_object('http', 400, 'error', 'escribe tu nombre completo');
    end if;
    update public.job_proposals set estado = 'APROBADA', opcion_elegida = p_opcion,
           total_aceptado = (v_op->>'total')::numeric, firmante = left(btrim(p_firmante), 200),
           firma_sha256 = p_firma_sha, firma_png = p_firma_png, decidida_por = p_actor,
           decided_at = v_now, updated_at = v_now
     where id = v_p.id returning * into v_p;
    if v_job.estado = 'ESPERANDO APROBACIÓN' then
      v_next := v_job.version + 1;
      update public.jobs set estado = 'EN REPARACIÓN', version = v_next, updated_at = v_now where id = v_job.id;
      insert into public.job_history (job_id, version, field, actor, old_value, new_value)
      values (v_job.id, v_next, 'estado', p_actor, v_job.estado, 'EN REPARACIÓN');
    else
      update public.jobs set updated_at = v_now where id = v_job.id;
    end if;
  else
    update public.job_proposals set estado = 'RECHAZADA', firmante = nullif(left(btrim(coalesce(p_firmante, '')), 200), ''),
           decidida_por = p_actor, decided_at = v_now, updated_at = v_now
     where id = v_p.id returning * into v_p;
    update public.jobs set updated_at = v_now where id = v_job.id;
  end if;
  return jsonb_build_object('http', 200, 'propuesta', public.lc_proposal_json(v_p, false));
end;
$$;

revoke all on function public.lc_proposal_json(public.job_proposals, boolean) from public, anon, authenticated;
revoke all on function public.lc_proposal_save(uuid, bigint, text, bigint, jsonb, text) from public, anon, authenticated;
revoke all on function public.lc_proposal_decide(uuid, bigint, text, text, text, text, text, text) from public, anon, authenticated;
grant execute on function public.lc_proposal_json(public.job_proposals, boolean) to service_role;
grant execute on function public.lc_proposal_save(uuid, bigint, text, bigint, jsonb, text) to service_role;
grant execute on function public.lc_proposal_decide(uuid, bigint, text, text, text, text, text, text) to service_role;
