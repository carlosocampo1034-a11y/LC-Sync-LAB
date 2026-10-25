-- LC-Sync-QA · órdenes atómicas e idempotentes (aditiva: no borra tablas ni datos).
-- Sustituye la lógica de escritura de lc-api qa-1, que hacía UPDATE, historial y recibo en
-- llamadas separadas (estado incoherente si una fallaba) y no tenía idempotencia al crear.
-- Las funciones solo las ejecuta service_role (lc-api); el actor se pasa ya autenticado y
-- su rol se lee aquí de profiles, nunca del cliente.

-- Idempotencia de creación: el cliente envía un request_id estable por orden (cola offline).
alter table public.jobs add column if not exists client_request_id text
  check (client_request_id is null or length(client_request_id) between 8 and 128);
create unique index if not exists jobs_created_by_request_uidx
  on public.jobs(created_by, client_request_id) where client_request_id is not null;

-- Vocabulario de estados compartido con Technician (Job.STATUS_*) y Control (jobs.py ESTADOS).
alter table public.jobs add constraint jobs_estado_valido check (estado in (
  'PENDIENTE','EN RUTA','LLEGUÉ','EN REPARACIÓN','ESPERANDO APROBACIÓN','PART HOLD',
  'PAUSADO','COMPLETADO','CANCELADO'));

create or replace function public.lc_now_ms() returns bigint
  language sql stable set search_path = ''
  as $$ select floor(extract(epoch from clock_timestamp()) * 1000)::bigint $$;

-- ── Crear orden ──────────────────────────────────────────────────────────────────────────────
create or replace function public.lc_create_order(p_actor uuid, p_request_id text, p_data jsonb)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
  v_role text;
  v_customer uuid;
  v_cliente text := btrim(coalesce(p_data->>'cliente', ''));
  v_desc text := btrim(coalesce(p_data->>'descripcion', ''));
  v_now bigint := public.lc_now_ms();
  v_row record;
begin
  select role into v_role from public.profiles where id = p_actor;
  if v_role is null or v_role not in ('CUSTOMER', 'DISPATCHER', 'ADMIN') then
    return jsonb_build_object('http', 403, 'error', 'role_denied');
  end if;
  if p_request_id is null or length(p_request_id) not between 8 and 128 then
    return jsonb_build_object('http', 422, 'error', 'request_id_required');
  end if;
  if v_cliente = '' or v_desc = '' then
    return jsonb_build_object('http', 422, 'error', 'cliente_and_descripcion_required');
  end if;

  -- Reintento de la misma solicitud: se devuelve la orden ya creada, nunca una segunda.
  select id, version, estado into v_row from public.jobs
   where created_by = p_actor and client_request_id = p_request_id;
  if found then
    return jsonb_build_object('http', 200, 'id', v_row.id, 'version', v_row.version,
                              'estado', v_row.estado, 'replay', true);
  end if;

  if v_role = 'CUSTOMER' then
    v_customer := p_actor;
  elsif p_data ? 'customer_id' and p_data->>'customer_id' is not null then
    if (p_data->>'customer_id') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       or not exists (select 1 from public.profiles
                       where id = (p_data->>'customer_id')::uuid and role = 'CUSTOMER') then
      return jsonb_build_object('http', 422, 'error', 'invalid_customer');
    end if;
    v_customer := (p_data->>'customer_id')::uuid;
  end if;

  insert into public.jobs (created_by, customer_id, origin, version, estado, cliente, descripcion,
                           direccion, telefono, categoria, client_request_id, created_at, updated_at)
  values (p_actor, v_customer, v_role, 1, 'PENDIENTE', left(v_cliente, 200), left(v_desc, 4000),
          left(nullif(btrim(p_data->>'direccion'), ''), 400), left(nullif(btrim(p_data->>'telefono'), ''), 80),
          left(nullif(btrim(p_data->>'categoria'), ''), 80), p_request_id, v_now, v_now)
  on conflict (created_by, client_request_id) where client_request_id is not null do nothing
  returning id, version, estado into v_row;

  if not found then  -- otra petición concurrente con el mismo request_id ganó la carrera
    select id, version, estado into v_row from public.jobs
     where created_by = p_actor and client_request_id = p_request_id;
    return jsonb_build_object('http', 200, 'id', v_row.id, 'version', v_row.version,
                              'estado', v_row.estado, 'replay', true);
  end if;

  insert into public.job_history (job_id, version, field, actor, old_value, new_value)
  values (v_row.id, 1, 'estado', p_actor, null, 'PENDIENTE');
  return jsonb_build_object('http', 201, 'id', v_row.id, 'version', v_row.version, 'estado', v_row.estado);
end;
$$;

-- ── Modificar orden (estado, asignación, notas…) con merge de 3 vías ─────────────────────────
-- p_base: versión que el cliente leyó. Campo tocado por otro después de p_base con valor distinto
-- → CONFLICT (no se aplica nada, se registra en job_conflicts). Todo en una transacción con la fila
-- bloqueada: dos escrituras simultáneas se serializan y ninguna se pierde.
create or replace function public.lc_patch_order(p_actor uuid, p_job bigint, p_base integer,
                                                 p_changes jsonb, p_device text, p_client_ts bigint)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
  v_role text;
  v_job public.jobs%rowtype;
  v_old jsonb;
  v_rec record;
  v_allowed text[];
  v_touched text[];
  v_key text;
  v_val jsonb;
  v_new text;
  v_apply jsonb := '{}'::jsonb;
  v_conf text[] := '{}';
  v_next integer;
  v_tcode text;
  v_tid uuid;
begin
  select role into v_role from public.profiles where id = p_actor;
  if v_role is null or v_role not in ('ADMIN', 'DISPATCHER', 'TECHNICIAN') then
    return jsonb_build_object('http', 403, 'error', 'role_denied');
  end if;
  if p_base is null or p_base < 1 or p_device is null or length(p_device) not between 1 and 128
     or p_client_ts is null or p_client_ts <= 0 or jsonb_typeof(p_changes) is distinct from 'object' then
    return jsonb_build_object('http', 422, 'error', 'version_device_updatedAt_required');
  end if;

  select * into v_job from public.jobs where id = p_job for update;
  if not found or (v_role = 'TECHNICIAN' and v_job.technician_id is distinct from p_actor) then
    return jsonb_build_object('http', 404, 'error', 'not_found');
  end if;
  v_old := to_jsonb(v_job);

  -- Idempotencia: con la fila bloqueada, un reintento concurrente ve el recibo del primero.
  select status, version into v_rec from public.sync_receipts
   where user_id = p_actor and device_id = p_device and job_id = p_job and client_updated_at = p_client_ts;
  if found then
    return jsonb_build_object('http', case v_rec.status when 'ACCEPTED' then 200 else 409 end,
                              'id', p_job, 'status', v_rec.status, 'version', v_rec.version, 'replay', true);
  end if;

  if p_base > v_job.version then
    return jsonb_build_object('http', 409, 'id', p_job, 'status', 'CONFLICT',
                              'version', v_job.version, 'motivo', 'future_version');
  end if;

  v_allowed := case v_role
    when 'TECHNICIAN' then array['estado', 'notas', 'horas']
    else array['estado', 'estado_dispatch', 'notas', 'prioridad', 'horas', 'programado_para', 'technician_id'] end;
  select coalesce(array_agg(distinct field), '{}') into v_touched
    from public.job_history where job_id = p_job and version > p_base;

  for v_key, v_val in select key, value from jsonb_each(p_changes) loop
    if not (v_key = any(v_allowed)) then
      return jsonb_build_object('http', 403, 'error', 'field_denied', 'field', v_key);
    end if;
    v_new := v_val #>> '{}';
    -- validación por campo
    if v_key = 'estado' and (v_new is null or v_new not in ('PENDIENTE','EN RUTA','LLEGUÉ','EN REPARACIÓN',
        'ESPERANDO APROBACIÓN','PART HOLD','PAUSADO','COMPLETADO','CANCELADO')
        or (v_role = 'TECHNICIAN' and v_new in ('PENDIENTE', 'CANCELADO'))) then
      return jsonb_build_object('http', 422, 'error', 'invalid_estado');
    elsif v_key = 'horas' and v_new is not null and v_new !~ '^\d{1,8}(\.\d{1,2})?$' then
      return jsonb_build_object('http', 422, 'error', 'invalid_horas');
    elsif v_key = 'programado_para' and v_new is not null and v_new !~ '^\d{1,15}$' then
      return jsonb_build_object('http', 422, 'error', 'invalid_programado_para');
    elsif v_key in ('notas', 'prioridad', 'estado_dispatch') and length(coalesce(v_new, '')) > 4000 then
      return jsonb_build_object('http', 422, 'error', 'value_too_long', 'field', v_key);
    elsif v_key = 'technician_id' and v_new is not null then
      if v_new !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
        return jsonb_build_object('http', 422, 'error', 'invalid_technician');
      end if;
      select id, technician_code into v_tid, v_tcode from public.profiles
       where id = v_new::uuid and role = 'TECHNICIAN';
      if not found then
        return jsonb_build_object('http', 422, 'error', 'invalid_technician');
      end if;
    end if;

    -- comparación normalizada (horas numeric: '1.5' == '1.50')
    if (v_key = 'horas' and (v_old->>'horas')::numeric is not distinct from v_new::numeric)
       or (v_key <> 'horas' and v_old->>v_key is not distinct from v_new) then
      continue;
    end if;
    if v_key = any(v_touched) then
      v_conf := v_conf || v_key;
    else
      v_apply := v_apply || jsonb_build_object(v_key, v_val);
    end if;
  end loop;

  if cardinality(v_conf) > 0 then
    insert into public.job_conflicts (job_id, field, base_version, server_version, server_value, client_value, actor)
    select p_job, f, p_base, v_job.version, v_old->>f, p_changes->>f, p_actor from unnest(v_conf) f;
    insert into public.sync_receipts (user_id, device_id, job_id, client_updated_at, status, version)
    values (p_actor, p_device, p_job, p_client_ts, 'CONFLICT', v_job.version);
    return jsonb_build_object('http', 409, 'id', p_job, 'status', 'CONFLICT', 'version', v_job.version,
                              'conflictFields', to_jsonb(v_conf), 'motivo', 'campos disputados');
  end if;

  v_next := v_job.version;
  if v_apply <> '{}'::jsonb then
    v_next := v_job.version + 1;
    if v_apply ? 'technician_id' and v_apply->>'technician_id' is null then
      v_tcode := null;
    end if;
    update public.jobs set
      estado          = case when v_apply ? 'estado'          then v_apply->>'estado'                     else estado end,
      estado_dispatch = case when v_apply ? 'estado_dispatch' then v_apply->>'estado_dispatch'            else estado_dispatch end,
      notas           = case when v_apply ? 'notas'           then v_apply->>'notas'                      else notas end,
      prioridad       = case when v_apply ? 'prioridad'       then v_apply->>'prioridad'                  else prioridad end,
      horas           = case when v_apply ? 'horas'           then (v_apply->>'horas')::numeric           else horas end,
      programado_para = case when v_apply ? 'programado_para' then (v_apply->>'programado_para')::bigint  else programado_para end,
      technician_id   = case when v_apply ? 'technician_id'   then (v_apply->>'technician_id')::uuid      else technician_id end,
      technician_code = case when v_apply ? 'technician_id'   then v_tcode                                else technician_code end,
      version = v_next,
      updated_at = public.lc_now_ms()
    where id = p_job;
    insert into public.job_history (job_id, version, field, actor, old_value, new_value)
    select p_job, v_next, key, p_actor, v_old->>key, value #>> '{}' from jsonb_each(v_apply);
  end if;

  insert into public.sync_receipts (user_id, device_id, job_id, client_updated_at, status, version)
  values (p_actor, p_device, p_job, p_client_ts, 'ACCEPTED', v_next);
  return jsonb_build_object('http', 200, 'id', p_job, 'status', 'ACCEPTED', 'version', v_next);
end;
$$;

revoke all on function public.lc_now_ms() from public, anon, authenticated;
revoke all on function public.lc_create_order(uuid, text, jsonb) from public, anon, authenticated;
revoke all on function public.lc_patch_order(uuid, bigint, integer, jsonb, text, bigint) from public, anon, authenticated;
grant execute on function public.lc_now_ms() to service_role;
grant execute on function public.lc_create_order(uuid, text, jsonb) to service_role;
grant execute on function public.lc_patch_order(uuid, bigint, integer, jsonb, text, bigint) to service_role;
