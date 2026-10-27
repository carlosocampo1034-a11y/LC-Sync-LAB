-- El técnico puede crear una orden desde la app (queda asignada a él, origen TECHNICIAN, cola de despacho).
-- Misma firma y mismo grant (solo service_role) que la versión de 20261025200000.
create or replace function public.lc_create_order(p_actor uuid, p_request_id text, p_data jsonb)
returns jsonb language plpgsql security definer set search_path = ''
as $$
declare
  v_role text;
  v_customer uuid;
  v_cref bigint;
  v_prop public.customer_properties%rowtype;
  v_cliente text := btrim(coalesce(p_data->>'cliente', ''));
  v_desc text := btrim(coalesce(p_data->>'descripcion', ''));
  v_now bigint := public.lc_now_ms();
  v_row record;
  v_tech boolean;
  v_prog bigint;
begin
  select role into v_role from public.profiles where id = p_actor and active;
  if v_role is null or v_role not in ('CUSTOMER', 'DISPATCHER', 'ADMIN', 'TECHNICIAN') then
    return jsonb_build_object('http', 403, 'error', 'role_denied');
  end if;
  if p_request_id is null or length(p_request_id) not between 8 and 128 then
    return jsonb_build_object('http', 422, 'error', 'request_id_required');
  end if;
  if v_cliente = '' or v_desc = '' then
    return jsonb_build_object('http', 422, 'error', 'cliente_and_descripcion_required');
  end if;

  v_tech := v_role = 'TECHNICIAN';
  if v_tech and coalesce(p_data->>'programado_para', '') ~ '^\d{1,15}$' then
    v_prog := (p_data->>'programado_para')::bigint;
  end if;

  select id, version, estado into v_row from public.jobs
   where created_by = p_actor and client_request_id = p_request_id;
  if found then
    return jsonb_build_object('http', 200, 'id', v_row.id, 'version', v_row.version,
                              'estado', v_row.estado, 'replay', true);
  end if;

  if v_tech then
    -- el técnico no conoce los ids de cliente/propiedad de la nube: la orden se asigna a sí mismo
    v_customer := null;
  elsif v_role = 'CUSTOMER' then
    v_customer := p_actor;
    select id into v_cref from public.customers where user_id = p_actor;
  else
    if p_data ? 'customer_id' and p_data->>'customer_id' is not null then
      if (p_data->>'customer_id') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
         or not exists (select 1 from public.profiles
                         where id = (p_data->>'customer_id')::uuid and role = 'CUSTOMER') then
        return jsonb_build_object('http', 422, 'error', 'invalid_customer');
      end if;
      v_customer := (p_data->>'customer_id')::uuid;
      select id into v_cref from public.customers where user_id = v_customer;
    end if;
    if p_data ? 'customer_ref' and p_data->>'customer_ref' is not null then
      if (p_data->>'customer_ref') !~ '^\d{1,15}$' then
        return jsonb_build_object('http', 422, 'error', 'invalid_customer_ref');
      end if;
      select id, user_id into v_cref, v_customer from public.customers where id = (p_data->>'customer_ref')::bigint;
      if v_cref is null then
        return jsonb_build_object('http', 422, 'error', 'invalid_customer_ref');
      end if;
    end if;
  end if;

  if not v_tech and p_data ? 'property_id' and p_data->>'property_id' is not null then
    if (p_data->>'property_id') !~ '^\d{1,15}$' then
      return jsonb_build_object('http', 422, 'error', 'invalid_property');
    end if;
    select * into v_prop from public.customer_properties where id = (p_data->>'property_id')::bigint;
    if not found or v_cref is null or v_prop.customer_id <> v_cref then
      return jsonb_build_object('http', 422, 'error', 'invalid_property');
    end if;
  end if;

  insert into public.jobs (created_by, customer_id, customer_ref, property_id, origin, version, estado,
                           estado_dispatch, prioridad, technician_id, programado_para, cliente, descripcion,
                           direccion, telefono, categoria, equipo, ventana_preferida,
                           tipo_propiedad, tipo_acceso, instrucciones, mascota, contacto_respaldo,
                           client_request_id, created_at, updated_at)
  values (p_actor, v_customer, v_cref, v_prop.id, case when v_role = 'CUSTOMER' then 'CUSTOMER' when v_tech then 'TECHNICIAN' else 'DISPATCH' end,
          1, 'PENDIENTE',
          case when v_role = 'CUSTOMER' then null else 'QUEUE' end,
          case when v_role = 'CUSTOMER' then null else 'NORMAL' end,
          case when v_tech then p_actor else null end, v_prog,
          left(v_cliente, 200), left(v_desc, 4000),
          left(coalesce(nullif(btrim(p_data->>'direccion'), ''), v_prop.direccion), 400),
          left(nullif(btrim(p_data->>'telefono'), ''), 80),
          left(nullif(btrim(p_data->>'categoria'), ''), 80),
          left(nullif(btrim(p_data->>'equipo'), ''), 200),
          left(nullif(btrim(p_data->>'ventana_preferida'), ''), 200),
          coalesce(v_prop.tipo, case when v_tech then left(nullif(btrim(p_data->>'tipo_propiedad'), ''), 80) end),
          coalesce(v_prop.tipo_acceso, case when v_tech then left(nullif(btrim(p_data->>'tipo_acceso'), ''), 80) end),
          coalesce(v_prop.instrucciones, case when v_tech then left(nullif(btrim(p_data->>'instrucciones'), ''), 1000) end),
          coalesce(v_prop.mascota, case when v_tech then left(nullif(btrim(p_data->>'mascota'), ''), 200) end),
          coalesce(v_prop.contacto_respaldo, case when v_tech then left(nullif(btrim(p_data->>'contacto_respaldo'), ''), 200) end),
          p_request_id, v_now, v_now)
  on conflict (created_by, client_request_id) where client_request_id is not null do nothing
  returning id, version, estado into v_row;

  if not found then
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

revoke all on function public.lc_create_order(uuid, text, jsonb) from public, anon, authenticated;
grant execute on function public.lc_create_order(uuid, text, jsonb) to service_role;
