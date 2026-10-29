-- Orden 54 (Carlos): estados del técnico con efecto real en el backend. ADITIVA: solo amplía lo permitido.
--   PAGADO               · COMPLETADO → PAGADO con factura emitida y pago de PRUEBA (payments.mode = simulated); sin factura se rechaza (422 sin_factura)
--   REQUIERE GERENTE     · aviso a Control (notifications con user_id null)
--   REPUESTO PRECARGADO  · parte ya asignada
--   CLIENTE NO ESTÁ      · aviso a Control
--   CANCELADO            · ahora lo puede poner el técnico (con motivo en notas «[CANCELACIÓN] …»); libera las ofertas abiertas y avisa a Control
-- Cambio de regla a destacar: antes lc_patch_order rechazaba CANCELADO del técnico (422 invalid_estado). PENDIENTE sigue vedado al técnico.
-- El técnico no puede salir de PAGADO ni de CANCELADO; PAGADO solo desde COMPLETADO.
select set_config('lc.via', 'seed', true);

alter table public.jobs drop constraint if exists jobs_estado_valido;
alter table public.jobs add constraint jobs_estado_valido check (estado in ('PENDIENTE','EN RUTA','LLEGUÉ','EN REPARACIÓN','ESPERANDO APROBACIÓN','PART HOLD','PAUSADO','COMPLETADO','CANCELADO','PAGADO','REQUIERE GERENTE','REPUESTO PRECARGADO','CLIENTE NO ESTÁ'));

-- textos de estado (ids fijos): se agregan los cuatro nuevos si faltan
update public.settings set
  value = value || coalesce((select jsonb_agg(n) from (values
    ('{"id":"PAGADO","label":"Pagado","customer_label":"Pagado","active":true}'::jsonb),
    ('{"id":"REQUIERE GERENTE","label":"Requiere gerente","customer_label":"En revisión","active":true}'::jsonb),
    ('{"id":"REPUESTO PRECARGADO","label":"Repuesto pre-cargado","customer_label":"Repuesto asignado","active":true}'::jsonb),
    ('{"id":"CLIENTE NO ESTÁ","label":"Cliente no está","customer_label":"No pudimos atenderte","active":true}'::jsonb)) t(n)
    where not exists (select 1 from jsonb_array_elements(value) o where o->>'id' = t.n->>'id')), '[]'::jsonb),
  default_value = default_value || coalesce((select jsonb_agg(n) from (values
    ('{"id":"PAGADO","label":"Pagado","customer_label":"Pagado","active":true}'::jsonb),
    ('{"id":"REQUIERE GERENTE","label":"Requiere gerente","customer_label":"En revisión","active":true}'::jsonb),
    ('{"id":"REPUESTO PRECARGADO","label":"Repuesto pre-cargado","customer_label":"Repuesto asignado","active":true}'::jsonb),
    ('{"id":"CLIENTE NO ESTÁ","label":"Cliente no está","customer_label":"No pudimos atenderte","active":true}'::jsonb)) t(n)
    where not exists (select 1 from jsonb_array_elements(default_value) o where o->>'id' = t.n->>'id')), '[]'::jsonb)
where key = 'order_states.list';

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
  v_inv public.invoices%rowtype;
  v_pagar boolean := false;
  -- mismas reglas que api_ops.TRANSICIONES / SOLO_DESPACHO del Control original
  v_trans constant jsonb := '{"PENDIENTE":["EN RUTA","CANCELADO"],
    "EN RUTA":["LLEGUÉ","PENDIENTE","PAUSADO","CLIENTE NO ESTÁ","CANCELADO"],
    "LLEGUÉ":["EN REPARACIÓN","ESPERANDO APROBACIÓN","PAUSADO","CLIENTE NO ESTÁ","REQUIERE GERENTE","CANCELADO"],
    "EN REPARACIÓN":["ESPERANDO APROBACIÓN","PART HOLD","PAUSADO","COMPLETADO","REQUIERE GERENTE","REPUESTO PRECARGADO","CANCELADO"],
    "ESPERANDO APROBACIÓN":["EN REPARACIÓN","PART HOLD","PAUSADO","CANCELADO","REQUIERE GERENTE"],
    "PART HOLD":["EN REPARACIÓN","PENDIENTE","PAUSADO","REPUESTO PRECARGADO","REQUIERE GERENTE","CANCELADO"],
    "PAUSADO":["EN RUTA","LLEGUÉ","EN REPARACIÓN","PENDIENTE","CANCELADO"],
    "COMPLETADO":["EN REPARACIÓN","PAGADO"],"CANCELADO":["PENDIENTE"],"PAGADO":[],
    "REQUIERE GERENTE":["EN REPARACIÓN","PENDIENTE","PAUSADO","CANCELADO"],
    "REPUESTO PRECARGADO":["EN REPARACIÓN","PAUSADO","PART HOLD"],
    "CLIENTE NO ESTÁ":["EN RUTA","PENDIENTE","CANCELADO"]}';
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
    if v_key = 'estado' and (v_new is null or v_new not in ('PENDIENTE','EN RUTA','LLEGUÉ','EN REPARACIÓN','ESPERANDO APROBACIÓN','PART HOLD','PAUSADO','COMPLETADO','CANCELADO','PAGADO','REQUIERE GERENTE','REPUESTO PRECARGADO','CLIENTE NO ESTÁ')
        or (v_role = 'TECHNICIAN' and v_new = 'PENDIENTE')) then   -- Orden 54: el técnico ya puede CANCELAR (con motivo); PENDIENTE sigue siendo solo de despacho
      return jsonb_build_object('http', 422, 'error', 'invalid_estado');
    elsif v_key = 'estado' and v_role = 'TECHNICIAN' and v_new is distinct from v_job.estado
          and (v_job.estado in ('PAGADO', 'CANCELADO')
               or (v_new = 'PAGADO' and v_job.estado <> 'COMPLETADO')
               or (v_new = 'CANCELADO' and v_job.estado in ('COMPLETADO'))) then
      return jsonb_build_object('http', 422, 'error', 'transition_denied', 'from', v_job.estado, 'to', v_new);
    elsif v_key = 'estado' and v_new = 'PAGADO' and v_new is distinct from v_job.estado then
      -- Pago de PRUEBA: solo con payments.mode = simulated y con factura emitida (la genera la propuesta aprobada)
      if coalesce(public.lc_setting('payments.mode') #>> '{}', 'off') <> 'simulated' then
        return jsonb_build_object('http', 409, 'error', 'pagos_no_activados', 'mensaje', 'Los pagos de prueba no están activados.');
      end if;
      select * into v_inv from public.invoices where job_id = p_job;
      if not found then
        return jsonb_build_object('http', 422, 'error', 'sin_factura', 'mensaje', 'Esta orden aún no tiene factura (requiere una propuesta aprobada).');
      end if;
      if v_inv.estado not in ('EMITIDA', 'PAGADA') or (v_inv.estado = 'EMITIDA' and v_inv.total <= 0) then
        return jsonb_build_object('http', 422, 'error', 'factura_no_pagable', 'mensaje', 'La factura no se puede pagar.');
      end if;
      v_pagar := v_inv.estado = 'EMITIDA';
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

    -- Orden 54: efectos de los estados nuevos (misma transacción)
    if v_apply->>'estado' = 'PAGADO' then
      if v_pagar then
        insert into public.payments (invoice_id, job_id, customer_id, amount, method, status)
        values (v_inv.id, p_job, v_job.customer_id, v_inv.total, 'TEST', 'CONFIRMADO');
        update public.invoices set estado = 'PAGADA', paid_at = public.lc_now_ms() where id = v_inv.id;
        insert into public.job_history (job_id, version, field, actor, old_value, new_value)
        values (p_job, v_next, 'pago', p_actor, null, 'Pago de prueba $' || v_inv.total || ' confirmado (' || v_inv.number || ')');
      end if;
      insert into public.notifications (user_id, job_id, tipo, titulo, cuerpo, created_at)
      values (null, p_job, 'PAGO', 'Orden pagada · ' || p_job, 'Cerrada por el técnico (pago de prueba)', public.lc_now_ms());
    elsif v_apply->>'estado' = 'REQUIERE GERENTE' then
      insert into public.notifications (user_id, job_id, tipo, titulo, cuerpo, created_at)
      values (null, p_job, 'GERENTE', 'Requiere gerente · orden ' || p_job, 'El técnico necesita revisión de gerente.', public.lc_now_ms());
    elsif v_apply->>'estado' = 'CLIENTE NO ESTÁ' then
      insert into public.notifications (user_id, job_id, tipo, titulo, cuerpo, created_at)
      values (null, p_job, 'CLIENTE_NO_ESTA', 'Cliente no está · orden ' || p_job, 'El técnico llegó y el cliente no estaba disponible.', public.lc_now_ms());
    elsif v_apply->>'estado' = 'CANCELADO' then
      update public.job_offers set estado = 'CANCELADA', responded_at = public.lc_now_ms()
       where job_id = p_job and estado = 'OFRECIDA';
      insert into public.notifications (user_id, job_id, tipo, titulo, cuerpo, created_at)
      values (null, p_job, 'CANCELACION', 'Orden cancelada por el técnico · ' || p_job,
              left(coalesce(substring(v_apply->>'notas' from '\[CANCELACIÓN\] (.*)$'), substring(v_job.notas from '\[CANCELACIÓN\] (.*)$'), 'Sin motivo indicado'), 200),
              public.lc_now_ms());
    end if;
  end if;

  insert into public.sync_receipts (user_id, device_id, job_id, client_updated_at, status, version)
  values (p_actor, p_device, p_job, p_client_ts, 'ACCEPTED', v_next);
  return jsonb_build_object('http', 200, 'id', p_job, 'status', 'ACCEPTED', 'version', v_next);
end;
$function$
;

CREATE OR REPLACE FUNCTION public.lc_proposal_save(p_actor uuid, p_job bigint, p_device text, p_local bigint, p_opciones jsonb, p_sha text)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
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
  if v_job.estado in ('COMPLETADO', 'CANCELADO', 'PAGADO') then
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
$function$
;

CREATE OR REPLACE FUNCTION public.lc_resolve_conflict(p_actor uuid, p_conflict bigint, p_decision text, p_valor text, p_version integer)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_c public.job_conflicts%rowtype;
  v_job public.jobs%rowtype;
  v_old text;
  v_final text;
  v_next integer;
begin
  if not exists (select 1 from public.profiles where id = p_actor and role in ('ADMIN','DISPATCHER') and active) then
    return jsonb_build_object('http', 403, 'error', 'role_denied');
  end if;
  select * into v_c from public.job_conflicts where id = p_conflict for update;
  if not found then return jsonb_build_object('http', 404, 'error', 'conflicto no encontrado'); end if;
  if v_c.estado <> 'PENDIENTE' then return jsonb_build_object('http', 409, 'error', 'este conflicto ya se resolvió'); end if;
  select * into v_job from public.jobs where id = v_c.job_id for update;
  if p_version is distinct from v_job.version then
    return jsonb_build_object('http', 409, 'error', 'la orden cambió; recarga', 'version', v_job.version);
  end if;
  if p_decision not in ('SERVIDOR', 'TECNICO', 'COMBINAR') then
    return jsonb_build_object('http', 400, 'error', 'decision: SERVIDOR, TECNICO o COMBINAR');
  end if;
  -- solo campos de texto que el despacho puede editar
  if v_c.field not in ('estado', 'notas', 'prioridad', 'estado_dispatch') then
    return jsonb_build_object('http', 422, 'error', 'campo no resoluble desde el Control', 'field', v_c.field);
  end if;
  v_old := to_jsonb(v_job) ->> v_c.field;
  if p_decision = 'SERVIDOR' then v_final := v_old;
  elsif p_decision = 'TECNICO' then v_final := v_c.client_value;
  else
    if p_valor is null or btrim(p_valor) = '' then
      return jsonb_build_object('http', 400, 'error', 'COMBINAR requiere valor');
    end if;
    v_final := left(p_valor, 4000);
  end if;
  if v_c.field = 'estado' and v_final not in ('PENDIENTE','EN RUTA','LLEGUÉ','EN REPARACIÓN',
      'ESPERANDO APROBACIÓN','PART HOLD','PAUSADO','COMPLETADO','CANCELADO','PAGADO','REQUIERE GERENTE','REPUESTO PRECARGADO','CLIENTE NO ESTÁ') then
    return jsonb_build_object('http', 422, 'error', 'invalid_estado');
  end if;
  v_next := v_job.version;
  if v_final is distinct from v_old then
    v_next := v_job.version + 1;
    update public.jobs set
      estado          = case when v_c.field = 'estado'          then v_final else estado end,
      notas           = case when v_c.field = 'notas'           then v_final else notas end,
      prioridad       = case when v_c.field = 'prioridad'       then v_final else prioridad end,
      estado_dispatch = case when v_c.field = 'estado_dispatch' then v_final else estado_dispatch end,
      version = v_next, updated_at = public.lc_now_ms()
    where id = v_job.id;
    insert into public.job_history (job_id, version, field, actor, old_value, new_value)
    values (v_job.id, v_next, v_c.field, p_actor, v_old, v_final);
  else
    update public.jobs set updated_at = public.lc_now_ms() where id = v_job.id;   -- avisa a los teléfonos
  end if;
  update public.job_conflicts set estado = 'RESUELTO',
         resolucion = case p_decision when 'COMBINAR' then 'COMBINADO' else p_decision end,
         valor_final = v_final, resuelto_por = p_actor, resolved_at = public.lc_now_ms()
   where id = p_conflict;
  return jsonb_build_object('http', 200, 'id', v_job.id, 'version', v_next);
end;
$function$
;
