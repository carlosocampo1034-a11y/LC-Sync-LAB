-- Configuración dinámica (settings) + auditoría. Diseño: documentacion/DISENO_SETTINGS_LC.md (Orden 13-B) con las
-- opciones recomendadas. Escritura SOLO por lc_setting_set / lc_setting_reset (service_role); lectura por lc-api y, para
-- Realtime, un SELECT limitado a lo no sensible. Aditiva: no toca tablas existentes.
-- Los secretos (Stripe, Firebase, service_role, DB) NO van aquí: variables de entorno.

create table if not exists public.settings (
  id            uuid        primary key default gen_random_uuid(),
  key           text        not null unique check (key ~ '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$'),
  value         jsonb       not null,
  type          text        not null check (type in ('number','string','boolean','jsonb')),
  category      text        not null generated always as (split_part(key, '.', 1)) stored,
  description   text        not null,
  editable_by   text        not null check (editable_by in ('ADMIN','DISPATCHER','ADMIN+DISPATCHER')),
  visibility    text        not null default 'AUTH' check (visibility in ('PUBLIC','AUTH','STAFF')),
  constraints   jsonb       not null default '{}'::jsonb,
  default_value jsonb       not null,
  version       integer     not null default 1,
  updated_by    uuid        references auth.users(id) on delete set null,
  updated_at    timestamptz not null default now(),
  created_at    timestamptz not null default now(),
  constraint settings_type_matches_value check (
    jsonb_typeof(value) = case type when 'number' then 'number' when 'string' then 'string'
                                    when 'boolean' then 'boolean' else jsonb_typeof(value) end)
);
create index if not exists settings_category_idx on public.settings (category);
create index if not exists settings_updated_idx  on public.settings (updated_at);
alter table public.settings enable row level security;
revoke all on public.settings from anon, authenticated;

create table if not exists public.settings_audit (
  id              uuid        primary key default gen_random_uuid(),
  setting_key     text        not null,
  old_value       jsonb,
  new_value       jsonb,
  changed_by      uuid        references auth.users(id) on delete set null,
  changed_at      timestamptz not null default now(),
  ip_address      text,
  user_agent      text,
  via             text        not null default 'api' check (via in ('api','sql','seed','reset')),
  reason          text,
  setting_version integer
);
create index if not exists settings_audit_key_idx on public.settings_audit (setting_key, changed_at desc);
alter table public.settings_audit enable row level security;
revoke all on public.settings_audit from anon, authenticated;

-- Lectura directa SOLO de lo no sensible (Realtime respeta RLS y necesita una política de SELECT)
drop policy if exists settings_read on public.settings;
create policy settings_read on public.settings for select to authenticated
  using (visibility in ('PUBLIC','AUTH') or (select public.lc_is_staff()));
grant select on public.settings to authenticated;
do $$ begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime')
     and not exists (select 1 from pg_publication_tables where pubname = 'supabase_realtime' and tablename = 'settings') then
    alter publication supabase_realtime add table public.settings;
  end if;
end $$;

-- ── Triggers: versión/fecha y auditoría (también captura cambios por SQL directo) ───────────────────
create or replace function public.lc_settings_touch_fn() returns trigger language plpgsql set search_path = '' as $$
begin
  new.version := old.version + 1;
  new.updated_at := now();
  return new;
end $$;

create or replace function public.lc_settings_audit_fn() returns trigger language plpgsql security definer set search_path = '' as $$
declare
  v_actor uuid := nullif(current_setting('lc.actor', true), '')::uuid;
  v_via text := coalesce(nullif(current_setting('lc.via', true), ''), case when nullif(current_setting('lc.actor', true), '') is null then 'sql' else 'api' end);
begin
  if tg_op = 'UPDATE' and new.value is not distinct from old.value then return new; end if;
  insert into public.settings_audit (setting_key, old_value, new_value, changed_by, ip_address, user_agent, via, reason, setting_version)
  values (coalesce(new.key, old.key), case when tg_op = 'INSERT' then null else old.value end,
          case when tg_op = 'DELETE' then null else new.value end, v_actor,
          nullif(current_setting('lc.ip', true), ''), nullif(current_setting('lc.ua', true), ''), v_via,
          nullif(current_setting('lc.reason', true), ''), case when tg_op = 'DELETE' then old.version else new.version end);
  return coalesce(new, old);
end $$;

drop trigger if exists settings_touch_trg on public.settings;
create trigger settings_touch_trg before update on public.settings for each row execute function public.lc_settings_touch_fn();
drop trigger if exists settings_audit_trg on public.settings;
create trigger settings_audit_trg after insert or update or delete on public.settings for each row execute function public.lc_settings_audit_fn();

-- ── Lectura tipada para otras funciones SQL ────────────────────────────────────────────────────────
create or replace function public.lc_setting(p_key text) returns jsonb
language sql stable security definer set search_path = '' as $$ select value from public.settings where key = p_key $$;

-- ── Validación de listas editables: [{id,label,active?,customer_label?}] ───────────────────────────
-- Reglas: ids únicos y estables; NO se borra ninguna opción existente (solo se desactiva); con fixedIds no se añaden ni se
-- desactivan opciones (estados de la orden: llevan lógica de negocio). Solo texto plano.
create or replace function public.lc_settings_validate_list(p_old jsonb, p_new jsonb, p_constraints jsonb, p_default jsonb)
returns text language plpgsql immutable set search_path = '' as $$
declare e jsonb; ids text[] := '{}'; old_id text; n int;
begin
  if jsonb_typeof(p_new) <> 'array' then return 'La lista debe ser un arreglo'; end if;
  n := jsonb_array_length(p_new);
  if n < 1 or n > 200 then return 'La lista debe tener entre 1 y 200 opciones'; end if;
  for e in select * from jsonb_array_elements(p_new) loop
    if jsonb_typeof(e) <> 'object' or jsonb_typeof(e->'id') <> 'string' or jsonb_typeof(e->'label') <> 'string' then
      return 'Cada opción necesita id y label (texto)'; end if;
    if char_length(e->>'id') not between 1 and 80 or char_length(btrim(e->>'label')) not between 1 and 80 then
      return 'id y label: de 1 a 80 caracteres'; end if;
    if (e->>'id') ~ '[<>\x00-\x1f]' or (e->>'label') ~ '[<>\x00-\x1f]' or coalesce(e->>'customer_label', '') ~ '[<>\x00-\x1f]' then
      return 'Solo texto plano (sin < > ni caracteres de control)'; end if;
    if e ? 'active' and jsonb_typeof(e->'active') <> 'boolean' then return 'active debe ser verdadero/falso'; end if;
    if (e->>'id') = any (ids) then return 'Id repetido: ' || (e->>'id'); end if;
    ids := ids || (e->>'id');
  end loop;
  if not exists (select 1 from jsonb_array_elements(p_new) x where coalesce((x->>'active')::boolean, true)) then
    return 'Debe quedar al menos una opción activa'; end if;
  if p_old is not null and jsonb_typeof(p_old) = 'array' then
    for old_id in select x->>'id' from jsonb_array_elements(p_old) x loop
      if not (old_id = any (ids)) then return 'No se puede borrar «' || old_id || '»: desactívala para no romper órdenes históricas'; end if;
    end loop;
  end if;
  if coalesce((p_constraints->>'fixedIds')::boolean, false) then
    if exists (select 1 from jsonb_array_elements(p_default) d where not ((d->>'id') = any (ids)))
       or n <> jsonb_array_length(p_default) then return 'Esta lista tiene ids fijos: solo se pueden editar los textos'; end if;
    if exists (select 1 from jsonb_array_elements(p_new) x where coalesce((x->>'active')::boolean, true) = false) then
      return 'Esta lista no permite desactivar opciones (llevan lógica de negocio)'; end if;
  end if;
  return null;
end $$;

-- ── Escritura única (valida rol, tipo, rango, secretos, versión, piso de precios) ───────────────────
create or replace function public.lc_setting_set(p_actor uuid, p_key text, p_value jsonb, p_expected_version integer,
                                                p_reason text, p_ip text, p_ua text, p_via text default 'api') returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  v_role text; s public.settings%rowtype; v_err text; v_min numeric; v_max numeric; v_floor numeric; v_txt text; v_expected text;
begin
  select role into v_role from public.profiles where id = p_actor and active;
  if v_role is null or v_role not in ('ADMIN','DISPATCHER') then
    return jsonb_build_object('http', 403, 'error', 'role_denied');
  end if;
  select * into s from public.settings where key = p_key for update;
  if not found then return jsonb_build_object('http', 404, 'error', 'setting_no_existe'); end if;
  if v_role = 'DISPATCHER' and s.editable_by not like '%DISPATCHER%' then
    return jsonb_build_object('http', 403, 'error', 'Esta configuración solo la cambia un administrador.');
  end if;
  if p_expected_version is not null and p_expected_version <> s.version then
    return jsonb_build_object('http', 409, 'error', 'version_cambio', 'mensaje', 'Otra persona cambió este valor; recarga para verlo.', 'version', s.version, 'value', s.value);
  end if;
  v_expected := case s.type when 'jsonb' then jsonb_typeof(p_value) else s.type end;
  if p_value is null or jsonb_typeof(p_value) is distinct from v_expected then
    return jsonb_build_object('http', 422, 'error', 'Tipo de valor inválido (se esperaba ' || s.type || ')');
  end if;
  v_txt := p_value::text;
  if v_txt ~* '(sk_(live|test)_|whsec_|-----BEGIN|eyJ[A-Za-z0-9_-]{10,}\.eyJ)' then
    return jsonb_build_object('http', 422, 'error', 'Parece un secreto: las claves van en variables de entorno, no en settings.');
  end if;
  if s.type = 'number' then
    v_min := (s.constraints->>'min')::numeric; v_max := (s.constraints->>'max')::numeric;
    if v_min is not null and (p_value #>> '{}')::numeric < v_min then return jsonb_build_object('http', 422, 'error', 'El mínimo es ' || v_min); end if;
    if v_max is not null and (p_value #>> '{}')::numeric > v_max then return jsonb_build_object('http', 422, 'error', 'El máximo es ' || v_max); end if;
    -- Piso de precios: diagnóstico y hora nunca bajan del piso de service call
    if p_key ~ '^pricing\.[a-z_]+\.(diagnostic|hourly)$' then
      v_floor := (public.lc_setting('pricing.service_call_floor') #>> '{}')::numeric;
      if (p_value #>> '{}')::numeric < v_floor then
        return jsonb_build_object('http', 422, 'error', 'No puede ser menor al piso de $' || v_floor || ' (pricing.service_call_floor).');
      end if;
    elsif p_key = 'pricing.service_call_floor' then
      if exists (select 1 from public.settings x where x.key ~ '^pricing\.[a-z_]+\.(diagnostic|hourly)$'
                  and (x.value #>> '{}')::numeric < (p_value #>> '{}')::numeric) then
        return jsonb_build_object('http', 422, 'error', 'Hay precios por debajo de ese piso; súbelos primero.');
      end if;
    end if;
  elsif s.type = 'string' then
    if char_length(p_value #>> '{}') > coalesce((s.constraints->>'maxLength')::int, 300) then
      return jsonb_build_object('http', 422, 'error', 'Texto demasiado largo'); end if;
    if s.constraints ? 'enum' and not (s.constraints->'enum') @> to_jsonb(p_value #>> '{}') then
      return jsonb_build_object('http', 422, 'error', 'Valor no permitido'); end if;
  elsif s.type = 'jsonb' and s.constraints->>'kind' = 'list' then
    v_err := public.lc_settings_validate_list(s.value, p_value, s.constraints, s.default_value);
    if v_err is not null then return jsonb_build_object('http', 422, 'error', v_err); end if;
  end if;

  perform set_config('lc.actor', p_actor::text, true);
  perform set_config('lc.via', coalesce(p_via, 'api'), true);
  perform set_config('lc.ip', coalesce(p_ip, ''), true);
  perform set_config('lc.ua', coalesce(left(p_ua, 300), ''), true);
  perform set_config('lc.reason', coalesce(left(p_reason, 300), ''), true);
  update public.settings set value = p_value, updated_by = p_actor where key = p_key returning * into s;
  return jsonb_build_object('http', 200, 'key', s.key, 'value', s.value, 'version', s.version, 'updated_at', s.updated_at);
end $$;

create or replace function public.lc_setting_reset(p_actor uuid, p_key text, p_ip text, p_ua text) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare s public.settings%rowtype; r jsonb;
begin
  select * into s from public.settings where key = p_key;
  if not found then return jsonb_build_object('http', 404, 'error', 'setting_no_existe'); end if;
  if (select role from public.profiles where id = p_actor and active) is distinct from 'ADMIN' then
    return jsonb_build_object('http', 403, 'error', 'Solo un administrador restablece valores.');
  end if;
  -- una lista no puede perder opciones que ya existen: se restablecen solo los textos de las que están en el valor inicial
  if s.type = 'jsonb' and s.constraints->>'kind' = 'list' then
    return jsonb_build_object('http', 422, 'error', 'Las listas no se restablecen (podrían perder opciones). Edita las opciones una a una.');
  end if;
  r := public.lc_setting_set(p_actor, p_key, s.default_value, null, 'Restablecido al valor inicial', p_ip, p_ua, 'reset');
  return r;
end $$;

do $$ declare f text; begin
  foreach f in array array['lc_setting(text)', 'lc_settings_validate_list(jsonb,jsonb,jsonb,jsonb)',
    'lc_setting_set(uuid,text,jsonb,integer,text,text,text,text)', 'lc_setting_reset(uuid,text,text,text)',
    'lc_settings_touch_fn()', 'lc_settings_audit_fn()'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
    execute format('grant execute on function public.%s to service_role', f);
  end loop;
end $$;

-- ═══════════════════ SEMILLA ═══════════════════
select set_config('lc.via', 'seed', true);

insert into public.settings (key, value, type, description, editable_by, visibility, constraints, default_value) values
-- Precios (Broward County / Florida). Piso de service call $50: regla «$10 menos que la competencia sin bajar de $50».
('pricing.service_call_floor',          '50.0', 'number', 'Piso mínimo (USD) de visita/diagnóstico y hora. Ningún precio puede ser menor.', 'ADMIN', 'AUTH', '{"min":0,"max":500}', '50.0'),
('pricing.hvac.diagnostic',             '90.0', 'number', 'HVAC: precio de la visita de diagnóstico (USD)', 'ADMIN', 'AUTH', '{"min":0,"max":1000}', '90.0'),
('pricing.hvac.hourly',                 '85.0', 'number', 'HVAC: precio por hora (USD)', 'ADMIN', 'AUTH', '{"min":0,"max":1000}', '85.0'),
('pricing.hvac.emergency_multiplier',   '1.5',  'number', 'HVAC: multiplicador en emergencias', 'ADMIN', 'AUTH', '{"min":1,"max":5}', '1.5'),
('pricing.plumbing.diagnostic',         '65.0', 'number', 'Plomería: precio de la visita de diagnóstico (USD)', 'ADMIN', 'AUTH', '{"min":0,"max":1000}', '65.0'),
('pricing.plumbing.hourly',             '50.0', 'number', 'Plomería: precio por hora (USD)', 'ADMIN', 'AUTH', '{"min":0,"max":1000}', '50.0'),
('pricing.plumbing.emergency_multiplier','1.5', 'number', 'Plomería: multiplicador en emergencias', 'ADMIN', 'AUTH', '{"min":1,"max":5}', '1.5'),
('pricing.electricity.diagnostic',      '65.0', 'number', 'Electricidad: precio de la visita de diagnóstico (USD)', 'ADMIN', 'AUTH', '{"min":0,"max":1000}', '65.0'),
('pricing.electricity.hourly',          '50.0', 'number', 'Electricidad: precio por hora (USD)', 'ADMIN', 'AUTH', '{"min":0,"max":1000}', '50.0'),
('pricing.electricity.emergency_multiplier','1.5','number','Electricidad: multiplicador en emergencias', 'ADMIN', 'AUTH', '{"min":1,"max":5}', '1.5'),
('pricing.general.diagnostic',          '50.0', 'number', 'General (electrodomésticos y otros): visita de diagnóstico (USD)', 'ADMIN', 'AUTH', '{"min":0,"max":1000}', '50.0'),
('pricing.general.hourly',              '50.0', 'number', 'General: precio por hora (USD)', 'ADMIN', 'AUTH', '{"min":0,"max":1000}', '50.0'),
('pricing.markup_percent',              '7.0',  'number', 'Margen (%) aplicado por defecto a las propuestas', 'ADMIN', 'AUTH', '{"min":0,"max":100}', '7.0');

-- Listas editables (desplegables). Los ids se conservan: el servidor y las órdenes antiguas los usan.
insert into public.settings (key, value, type, description, editable_by, visibility, constraints, default_value)
select k, v::jsonb, 'jsonb', d, 'ADMIN', 'AUTH', c::jsonb, v::jsonb from (values
 ('categories.list', '[{"id":"ELECTRODOMESTICOS","label":"Electrodomésticos","active":true},{"id":"HVAC","label":"A/C y Calefacción","active":true},{"id":"PLOMERIA","label":"Plomería","active":true},{"id":"ELECTRICIDAD","label":"Electricidad","active":true},{"id":"OTRO","label":"Otro","active":true}]', 'Categorías de servicio que el cliente elige al solicitar', '{"kind":"list"}'),
 ('specialties.list', '[{"id":"HVAC","label":"HVAC","active":true},{"id":"PLOMERIA","label":"Plomería","active":true},{"id":"ELECTRICIDAD","label":"Electricidad","active":true},{"id":"ELECTRODOMESTICOS","label":"Electrodomésticos","active":true}]', 'Especialidades de los técnicos (el despacho las compara con la categoría de la orden)', '{"kind":"list"}'),
 ('order_states.list', '[{"id":"PENDIENTE","label":"Pendiente","customer_label":"Recibida","active":true},{"id":"EN RUTA","label":"En ruta","customer_label":"Técnico en camino","active":true},{"id":"LLEGUÉ","label":"Llegué","customer_label":"Técnico en el lugar","active":true},{"id":"EN REPARACIÓN","label":"En reparación","customer_label":"En reparación","active":true},{"id":"ESPERANDO APROBACIÓN","label":"Esperando aprobación","customer_label":"Esperando tu aprobación","active":true},{"id":"PART HOLD","label":"Falta repuesto","customer_label":"Esperando repuesto","active":true},{"id":"PAUSADO","label":"Pausado","customer_label":"Pausada","active":true},{"id":"COMPLETADO","label":"Completado","customer_label":"Completada","active":true},{"id":"CANCELADO","label":"Cancelado","customer_label":"Cancelada","active":true}]', 'Estados de la orden: solo se editan los textos (los estados llevan lógica de negocio)', '{"kind":"list","fixedIds":true}'),
 ('property_types.list', '[{"id":"Casa","label":"Casa","active":true},{"id":"Apartamento","label":"Apartamento","active":true},{"id":"Oficina","label":"Oficina","active":true},{"id":"Hotel","label":"Hotel","active":true},{"id":"Otro","label":"Otro","active":true}]', 'Tipos de propiedad del cliente', '{"kind":"list"}'),
 ('access_types.list', '[{"id":"Ninguno","label":"Ninguno","active":true},{"id":"Código puerta","label":"Código puerta","active":true},{"id":"Garaje","label":"Garaje","active":true},{"id":"Lockbox","label":"Lockbox","active":true},{"id":"Vecino","label":"Vecino","active":true},{"id":"Llave escondida","label":"Llave escondida","active":true}]', 'Formas de acceso a la propiedad', '{"kind":"list"}'),
 ('refrigerants.list', '[{"id":"R-410A","label":"R-410A","active":true},{"id":"R-22","label":"R-22","active":true},{"id":"R-32","label":"R-32","active":true},{"id":"R-454B","label":"R-454B","active":true},{"id":"Otro","label":"Otro","active":true}]', 'Refrigerantes del equipo (Técnico)', '{"kind":"list"}'),
 ('voltages.list', '[{"id":"115V","label":"115V","active":true},{"id":"208/230V","label":"208/230V","active":true},{"id":"220V","label":"220V","active":true},{"id":"230V","label":"230V","active":true},{"id":"460V","label":"460V","active":true},{"id":"Otro","label":"Otro","active":true}]', 'Voltajes del equipo (Técnico)', '{"kind":"list"}'),
 ('phases.list', '[{"id":"Monofásico","label":"Monofásico","active":true},{"id":"Trifásico","label":"Trifásico","active":true}]', 'Fases eléctricas del equipo (Técnico)', '{"kind":"list"}'),
 ('system_roles.list', '[{"id":"INDOOR","label":"Interior (INDOOR)","active":true},{"id":"OUTDOOR","label":"Exterior (OUTDOOR)","active":true}]', 'Parte del sistema: interior/exterior (Técnico)', '{"kind":"list","fixedIds":true}'),
 ('lifecycle_statuses.list', '[{"id":"ACTIVE","label":"Activo","active":true},{"id":"REPLACED","label":"Reemplazado","active":true},{"id":"RETIRED","label":"Retirado","active":true}]', 'Ciclo de vida del equipo (Técnico)', '{"kind":"list","fixedIds":true}'),
 ('inspection_types.list', '[{"id":"VOLTAGE","label":"Voltaje","active":true},{"id":"AMPERAGE","label":"Amperaje","active":true},{"id":"TEMPERATURE","label":"Temperatura","active":true},{"id":"PRESSURE","label":"Presión","active":true},{"id":"SUPERHEAT","label":"Sobrecalentamiento","active":true},{"id":"SUPERCOOL","label":"Subenfriamiento","active":true},{"id":"STATIC_PRESSURE","label":"Presión estática","active":true}]', 'Tipos de medición de la inspección (Técnico)', '{"kind":"list"}'),
 ('inspection_units.list', '[{"id":"V","label":"V","active":true},{"id":"A","label":"A","active":true},{"id":"°F","label":"°F","active":true},{"id":"PSI","label":"PSI","active":true}]', 'Unidades de medición (Técnico)', '{"kind":"list"}'),
 ('inspection_resolutions.list', '[{"id":"Repair","label":"Reparar","active":true},{"id":"Replace","label":"Reemplazar","active":true},{"id":"Part Required","label":"Requiere repuesto","active":true},{"id":"Order Part","label":"Pedir repuesto","active":true},{"id":"Proposal","label":"Propuesta","active":true},{"id":"No Fault Found","label":"Sin falla","active":true}]', 'Resoluciones de un hallazgo de inspección (Técnico)', '{"kind":"list"}'),
 ('finding_areas.list', '[{"id":"HVAC_INDOOR","label":"HVAC Interior","active":true},{"id":"HVAC_OUTDOOR","label":"HVAC Exterior","active":true},{"id":"ELECTRICAL","label":"Eléctrico","active":true},{"id":"PLUMBING","label":"Plomería","active":true},{"id":"APPLIANCE","label":"Electrodoméstico","active":true},{"id":"DUCTWORK","label":"Ductos","active":true},{"id":"CONTROLS","label":"Controles","active":true},{"id":"OTHER","label":"Otro","active":true}]', 'Áreas de un hallazgo (Técnico)', '{"kind":"list"}'),
 ('payment_methods.list', '[{"id":"EFECTIVO","label":"Efectivo","active":true},{"id":"CHEQUE","label":"Cheque","active":true},{"id":"TARJETA","label":"Tarjeta","active":true},{"id":"ZELLE","label":"Zelle","active":true},{"id":"TRANSFERENCIA","label":"Transferencia","active":true},{"id":"OTRO","label":"Otro","active":true}]', 'Métodos de pago que registra el técnico', '{"kind":"list"}')
) as t(k, v, d, c);
