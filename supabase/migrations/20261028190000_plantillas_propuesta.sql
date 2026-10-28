-- Plantillas de propuesta del Técnico en settings (Orden 15). Antes estaban fijas en la app (títulos, descripciones,
-- horas y materiales). El importe de mano de obra = horas × pricing.<grupo>.hourly. Editables en Control → Plantillas.
create or replace function public.lc_settings_validate_list(p_old jsonb, p_new jsonb, p_constraints jsonb, p_default jsonb)
returns text language plpgsql immutable set search_path = '' as $$
declare e jsonb; opt jsonb; ids text[] := '{}'; old_id text; n int;
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
    -- plantillas de propuesta: exactamente 3 opciones (Good/Better/Best) con título, descripción, horas y materiales
    if e ? 'options' then
      if jsonb_typeof(e->'options') <> 'array' or jsonb_array_length(e->'options') <> 3 then
        return 'Cada plantilla necesita exactamente 3 opciones (Good, Better, Best)'; end if;
      for opt in select * from jsonb_array_elements(e->'options') loop
        if jsonb_typeof(opt) <> 'object' or jsonb_typeof(opt->'title') <> 'string' or jsonb_typeof(opt->'description') <> 'string'
           or jsonb_typeof(opt->'hours') <> 'number' or jsonb_typeof(opt->'materials') <> 'number' then
          return 'Cada opción necesita título, descripción, horas y materiales'; end if;
        if char_length(btrim(opt->>'title')) not between 1 and 80 or char_length(opt->>'description') > 300 then
          return 'Título de 1 a 80 caracteres y descripción de hasta 300'; end if;
        if (opt->>'title') ~ '[<>\x00-\x1f]' or (opt->>'description') ~ '[<>\x00-\x1f]' then
          return 'Solo texto plano (sin < > ni caracteres de control)'; end if;
        if (opt->>'hours')::numeric not between 0 and 200 or (opt->>'materials')::numeric not between 0 and 100000 then
          return 'Horas entre 0 y 200; materiales entre 0 y 100000'; end if;
      end loop;
    end if;
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


select set_config('lc.via', 'seed', true);
insert into public.settings (key, value, type, description, editable_by, visibility, constraints, default_value)
values ('proposal_templates.list', '[{"id": "MANTENIMIENTO_PREVENTIVO", "label": "Mantenimiento Preventivo", "active": true, "options": [{"title": "Mantenimiento Preventivo - Good", "description": "Revisión básica de filtros y limpieza superficial de unidad", "hours": 3, "materials": 50}, {"title": "Mantenimiento Preventivo - Better", "description": "Inspección completa + limpieza profunda + ajuste de refrigerante", "hours": 4, "materials": 100}, {"title": "Mantenimiento Preventivo - Best", "description": "Mantenimiento total + limpieza de bobinas + revisión eléctrica completa", "hours": 5, "materials": 150}]}, {"id": "REEMPLAZO_COMPRESOR", "label": "Reemplazo de Compresor", "active": true, "options": [{"title": "Reemplazo de Compresor - Good", "description": "Reemplazo de compresor estándar con garantía básica", "hours": 16, "materials": 200}, {"title": "Reemplazo de Compresor - Better", "description": "Reemplazo de compresor eficiencia media + mano de obra premium", "hours": 24, "materials": 300}, {"title": "Reemplazo de Compresor - Best", "description": "Reemplazo de compresor alta eficiencia + garantía extendida 2 años", "hours": 36, "materials": 400}]}, {"id": "LIMPIEZA_DUCTOS", "label": "Limpieza de Ductos", "active": true, "options": [{"title": "Limpieza de Ductos - Good", "description": "Limpieza básica de ductos principales", "hours": 4, "materials": 0}, {"title": "Limpieza de Ductos - Better", "description": "Limpieza completa de ductos + tratamiento antimicrobial", "hours": 6, "materials": 50}, {"title": "Limpieza de Ductos - Best", "description": "Limpieza profunda + sellado + tratamiento antimicrobial + certificación", "hours": 9, "materials": 100}]}]'::jsonb, 'jsonb',
        'Plantillas de propuesta del Técnico: 3 opciones (Good/Better/Best) con título, descripción, horas de mano de obra y materiales ($)',
        'ADMIN', 'AUTH', '{"kind":"list"}', '[{"id": "MANTENIMIENTO_PREVENTIVO", "label": "Mantenimiento Preventivo", "active": true, "options": [{"title": "Mantenimiento Preventivo - Good", "description": "Revisión básica de filtros y limpieza superficial de unidad", "hours": 3, "materials": 50}, {"title": "Mantenimiento Preventivo - Better", "description": "Inspección completa + limpieza profunda + ajuste de refrigerante", "hours": 4, "materials": 100}, {"title": "Mantenimiento Preventivo - Best", "description": "Mantenimiento total + limpieza de bobinas + revisión eléctrica completa", "hours": 5, "materials": 150}]}, {"id": "REEMPLAZO_COMPRESOR", "label": "Reemplazo de Compresor", "active": true, "options": [{"title": "Reemplazo de Compresor - Good", "description": "Reemplazo de compresor estándar con garantía básica", "hours": 16, "materials": 200}, {"title": "Reemplazo de Compresor - Better", "description": "Reemplazo de compresor eficiencia media + mano de obra premium", "hours": 24, "materials": 300}, {"title": "Reemplazo de Compresor - Best", "description": "Reemplazo de compresor alta eficiencia + garantía extendida 2 años", "hours": 36, "materials": 400}]}, {"id": "LIMPIEZA_DUCTOS", "label": "Limpieza de Ductos", "active": true, "options": [{"title": "Limpieza de Ductos - Good", "description": "Limpieza básica de ductos principales", "hours": 4, "materials": 0}, {"title": "Limpieza de Ductos - Better", "description": "Limpieza completa de ductos + tratamiento antimicrobial", "hours": 6, "materials": 50}, {"title": "Limpieza de Ductos - Best", "description": "Limpieza profunda + sellado + tratamiento antimicrobial + certificación", "hours": 9, "materials": 100}]}]'::jsonb)
on conflict (key) do nothing;
