-- Categorías oficiales del negocio (decisión de Carlos, Orden 15): SOLO cuatro.
--   1 Plomería · 2 Electricidad básica · 3 Aire Acondicionado (A/C) · 4 Appliances / Electrodomésticos
-- Las demás (OTRO, PISCINAS) se DESACTIVAN, no se borran (órdenes históricas). El servidor rechaza categorías
-- inexistentes o desactivadas y el despacho automático no ofrece órdenes de categorías fuera de la lista.
select set_config('lc.via', 'seed', true);

update public.settings set
  value = '[{"id":"PLOMERIA","label":"Plomería","active":true,"price_group":"plumbing"},{"id":"ELECTRICIDAD","label":"Electricidad básica","active":true,"price_group":"electricity"},{"id":"HVAC","label":"Aire Acondicionado (A/C)","active":true,"price_group":"hvac"},{"id":"ELECTRODOMESTICOS","label":"Appliances / Electrodomésticos","active":true,"price_group":"general"},{"id":"OTRO","label":"Otro","active":false,"price_group":"general"},{"id":"PISCINAS","label":"Piscinas","active":false,"price_group":"general"}]'::jsonb,
  default_value = '[{"id":"PLOMERIA","label":"Plomería","active":true,"price_group":"plumbing"},{"id":"ELECTRICIDAD","label":"Electricidad básica","active":true,"price_group":"electricity"},{"id":"HVAC","label":"Aire Acondicionado (A/C)","active":true,"price_group":"hvac"},{"id":"ELECTRODOMESTICOS","label":"Appliances / Electrodomésticos","active":true,"price_group":"general"},{"id":"OTRO","label":"Otro","active":false,"price_group":"general"}]'::jsonb,
  description = 'Categorías oficiales de servicio (solo 4 activas; price_group = grupo de precios: hvac, plumbing, electricity o general)'
where key = 'categories.list';

update public.settings set
  value = '[{"id":"PLOMERIA","label":"Plomería","active":true},{"id":"ELECTRICIDAD","label":"Electricidad básica","active":true},{"id":"HVAC","label":"Aire Acondicionado (A/C)","active":true},{"id":"ELECTRODOMESTICOS","label":"Appliances / Electrodomésticos","active":true}]'::jsonb,
  default_value = '[{"id":"PLOMERIA","label":"Plomería","active":true},{"id":"ELECTRICIDAD","label":"Electricidad básica","active":true},{"id":"HVAC","label":"Aire Acondicionado (A/C)","active":true},{"id":"ELECTRODOMESTICOS","label":"Appliances / Electrodomésticos","active":true}]'::jsonb
where key = 'specialties.list';

create or replace function public.lc_category_ok(p text) returns boolean
language sql stable security definer set search_path = '' as $$
  select not exists (select 1 from public.settings where key = 'categories.list')
      or exists (select 1 from public.settings s, jsonb_array_elements(s.value) o
                  where s.key = 'categories.list' and o->>'id' = p and coalesce((o->>'active')::boolean, true))
$$;
revoke all on function public.lc_category_ok(text) from public, anon, authenticated;
grant execute on function public.lc_category_ok(text) to service_role;

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
  -- solo las categorías oficiales (settings categories.list, activas)
  if nullif(btrim(p_data->>'categoria'), '') is not null and not public.lc_category_ok(btrim(p_data->>'categoria')) then
    return jsonb_build_object('http', 422, 'error', 'categoria_invalida', 'mensaje', 'Esa categoría de servicio no existe.');
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
          left(coalesce(nullif(btrim(p_data->>'telefono'), ''), (select c.telefono from public.customers c where c.id = v_cref)), 80),
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

  -- el despacho automático solo opera con las categorías oficiales; el resto se asigna a mano desde Control
  if nullif(btrim(coalesce(v_job.categoria, '')), '') is not null and not public.lc_category_ok(btrim(v_job.categoria)) then
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
