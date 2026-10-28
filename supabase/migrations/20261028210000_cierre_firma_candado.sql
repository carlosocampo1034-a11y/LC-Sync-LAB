-- Orden 21 · Fase C: cierre con firma o negativa y candado de seguridad.
-- Una fila por orden. La imagen de la firma va en base64 (PNG); el servidor la valida. Sin acceso directo desde clientes.
create table if not exists public.job_closures (
  job_id bigint primary key references public.jobs(id) on delete cascade,
  kind text not null check (kind in ('FIRMA', 'NEGATIVA')),
  firmante text,
  firma_png text,
  negativa_at timestamptz,
  motivo text,
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  email_status text not null default 'PENDIENTE',
  email_error text,
  email_sent_at timestamptz
);
alter table public.job_closures enable row level security;
revoke all on public.job_closures from anon, authenticated;

create or replace function public.lc_closure_save(p_actor uuid, p_job bigint, p_kind text, p_firmante text,
                                                  p_firma text, p_motivo text)
returns jsonb language plpgsql security definer set search_path = '' as $f$
declare
  v_role text; j public.jobs%rowtype; v_now timestamptz := now(); v_nom text := btrim(coalesce(p_firmante, ''));
begin
  select role into v_role from public.profiles where id = p_actor and active;
  if v_role is null or v_role not in ('TECHNICIAN', 'ADMIN', 'DISPATCHER') then
    return jsonb_build_object('http', 403, 'error', 'role_denied');
  end if;
  select * into j from public.jobs where id = p_job for update;
  if not found or (v_role = 'TECHNICIAN' and j.technician_id is distinct from p_actor) then
    return jsonb_build_object('http', 404, 'error', 'not_found');
  end if;
  if j.estado <> 'EN REPARACIÓN' then
    return jsonb_build_object('http', 409, 'error', 'estado_invalido', 'estado', j.estado);
  end if;
  if length(btrim(coalesce(j.notas, ''))) < 50 then
    return jsonb_build_object('http', 422, 'error', 'diagnostico_corto',
                              'mensaje', 'El diagnóstico debe tener al menos 50 caracteres.');
  end if;
  if not exists (select 1 from public.job_photos where job_id = p_job) then
    return jsonb_build_object('http', 422, 'error', 'sin_fotos', 'mensaje', 'Sube al menos 1 foto de evidencia.');
  end if;
  if p_kind = 'FIRMA' then
    if length(v_nom) < 5 or position(' ' in v_nom) = 0 or length(v_nom) > 120 then
      return jsonb_build_object('http', 422, 'error', 'nombre_completo', 'mensaje', 'Escribe el nombre completo de quien firma.');
    end if;
    if p_firma is null or length(p_firma) not between 600 and 400000 or p_firma not like 'iVBORw0KGgo%' then
      return jsonb_build_object('http', 422, 'error', 'firma_invalida', 'mensaje', 'La firma está vacía o no es válida.');
    end if;
  elsif p_kind = 'NEGATIVA' then
    p_firma := null; v_nom := null;
  else
    return jsonb_build_object('http', 422, 'error', 'kind_invalido');
  end if;
  insert into public.job_closures (job_id, kind, firmante, firma_png, negativa_at, motivo, created_by)
  values (p_job, p_kind, v_nom, p_firma, case when p_kind = 'NEGATIVA' then v_now end,
          left(p_motivo, 300), p_actor)
  on conflict (job_id) do update set kind = excluded.kind, firmante = excluded.firmante, firma_png = excluded.firma_png,
    negativa_at = excluded.negativa_at, motivo = excluded.motivo, created_by = excluded.created_by, created_at = v_now,
    email_status = 'PENDIENTE', email_error = null, email_sent_at = null;
  insert into public.job_history (job_id, version, field, actor, old_value, new_value)
  values (p_job, j.version, case when p_kind = 'FIRMA' then 'firma' else 'negativa_firma' end, p_actor, null,
          case when p_kind = 'FIRMA' then 'Firmó ' || v_nom
               else 'El cliente no quiso firmar · ' || to_char(v_now at time zone 'America/New_York', 'YYYY-MM-DD HH24:MI') end)
  on conflict do nothing;
  return jsonb_build_object('http', 201, 'kind', p_kind, 'at', extract(epoch from v_now) * 1000);
end $f$;
revoke all on function public.lc_closure_save(uuid, bigint, text, text, text, text) from public, anon, authenticated;
grant execute on function public.lc_closure_save(uuid, bigint, text, text, text, text) to service_role;

CREATE OR REPLACE FUNCTION public.lc_patch_order(p_actor uuid, p_job bigint, p_base integer, p_changes jsonb, p_device text, p_client_ts bigint)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
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
  -- mismas reglas que api_ops.TRANSICIONES / SOLO_DESPACHO del Control original
  v_trans constant jsonb := '{"PENDIENTE":["EN RUTA","CANCELADO"],"EN RUTA":["LLEGUÉ","PENDIENTE","PAUSADO"],
    "LLEGUÉ":["EN REPARACIÓN","ESPERANDO APROBACIÓN","PAUSADO"],
    "EN REPARACIÓN":["ESPERANDO APROBACIÓN","PART HOLD","PAUSADO","COMPLETADO"],
    "ESPERANDO APROBACIÓN":["EN REPARACIÓN","PART HOLD","PAUSADO","CANCELADO"],
    "PART HOLD":["EN REPARACIÓN","PENDIENTE","PAUSADO"],"PAUSADO":["EN RUTA","LLEGUÉ","EN REPARACIÓN","PENDIENTE"],
    "COMPLETADO":["EN REPARACIÓN"],"CANCELADO":["PENDIENTE"]}';
begin
  select role into v_role from public.profiles where id = p_actor and active;
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
    else array['estado', 'estado_dispatch', 'notas', 'prioridad', 'horas', 'programado_para', 'technician_id',
               'hora', 'zona_horaria'] end;
  select coalesce(array_agg(distinct field), '{}') into v_touched
    from public.job_history where job_id = p_job and version > p_base;

  for v_key, v_val in select key, value from jsonb_each(p_changes) loop
    if not (v_key = any(v_allowed)) then
      return jsonb_build_object('http', 403, 'error', 'field_denied', 'field', v_key);
    end if;
    v_new := v_val #>> '{}';
    -- Candado de cierre (Orden 21): el técnico no puede completar ni pedir repuesto sin diagnóstico (mín. 50
    -- caracteres), al menos 1 foto y la firma del cliente o el registro de que no quiso firmar.
    if v_key = 'estado' and v_role = 'TECHNICIAN' and v_new in ('COMPLETADO', 'PART HOLD')
       and v_new is distinct from v_job.estado then
      if length(btrim(coalesce(case when p_changes ? 'notas' then p_changes->>'notas' else v_job.notas end, ''))) < 50
         or not exists (select 1 from public.job_photos where job_id = p_job)
         or not exists (select 1 from public.job_closures where job_id = p_job) then
        return jsonb_build_object('http', 422, 'error', 'cierre_incompleto',
                                  'mensaje', 'Falta evidencia, diagnóstico o firma. No puedes cerrar.');
      end if;
    end if;
    if v_key = 'estado' and (v_new is null or v_new not in ('PENDIENTE','EN RUTA','LLEGUÉ','EN REPARACIÓN',
        'ESPERANDO APROBACIÓN','PART HOLD','PAUSADO','COMPLETADO','CANCELADO')
        or (v_role = 'TECHNICIAN' and v_new in ('PENDIENTE', 'CANCELADO'))) then
      return jsonb_build_object('http', 422, 'error', 'invalid_estado');
    elsif v_key = 'estado' and v_role in ('ADMIN', 'DISPATCHER') and v_new <> v_job.estado
          and not (v_trans -> v_job.estado) ? v_new then
      return jsonb_build_object('http', 422, 'error', 'transition_denied',
                                'from', v_job.estado, 'to', v_new);
    elsif v_key = 'horas' and v_new is not null and v_new !~ '^\d{1,8}(\.\d{1,2})?$' then
      return jsonb_build_object('http', 422, 'error', 'invalid_horas');
    elsif v_key = 'programado_para' and v_new is not null and v_new !~ '^\d{1,15}$' then
      return jsonb_build_object('http', 422, 'error', 'invalid_programado_para');
    elsif v_key in ('notas', 'prioridad', 'estado_dispatch') and length(coalesce(v_new, '')) > 4000 then
      return jsonb_build_object('http', 422, 'error', 'value_too_long', 'field', v_key);
    elsif v_key in ('hora', 'zona_horaria') and length(coalesce(v_new, '')) > 64 then
      return jsonb_build_object('http', 422, 'error', 'value_too_long', 'field', v_key);
    elsif v_key = 'technician_id' and v_new is not null then
      if v_new !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
        return jsonb_build_object('http', 422, 'error', 'invalid_technician');
      end if;
      select id, technician_code into v_tid, v_tcode from public.profiles
       where id = v_new::uuid and role = 'TECHNICIAN' and active;
      if not found then
        return jsonb_build_object('http', 422, 'error', 'invalid_technician');
      end if;
    end if;

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
      hora            = case when v_apply ? 'hora'            then v_apply->>'hora'                       else hora end,
      zona_horaria    = case when v_apply ? 'zona_horaria'    then v_apply->>'zona_horaria'               else zona_horaria end,
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
$function$;
