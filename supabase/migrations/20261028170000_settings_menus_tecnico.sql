-- Menús y listas del Técnico y precios que faltaban (Orden 14). Los ids llevan lógica (máquina de estados, categorías de
-- foto, canales, tipos de partida): fixedIds = solo se editan los textos. Aditiva.
select set_config('lc.via', 'seed', true);

insert into public.settings (key, value, type, description, editable_by, visibility, constraints, default_value)
select k, v::jsonb, 'jsonb', d, 'ADMIN', 'AUTH', c::jsonb, v::jsonb from (values
 ('job_actions.list', '[{"id":"GO_ENROUTE","label":"Voy en camino","active":true},{"id":"ARRIVED","label":"Llegué","active":true},{"id":"ASK_APPROVAL","label":"Pedir aprobación al cliente","active":true},{"id":"START_DIRECT","label":"Iniciar reparación directo","active":true},{"id":"CLIENT_APPROVED","label":"Cliente aprobó — Iniciar reparación","active":true},{"id":"COMPLETE","label":"Terminé (Completar)","active":true},{"id":"PART_HOLD","label":"Falta repuesto (Part Hold)","active":true},{"id":"PAUSE","label":"Pausar trabajo","active":true},{"id":"RESUME","label":"Reanudar reparación","active":true},{"id":"CLIENT_CANCELLED","label":"Cliente canceló","active":true},{"id":"NO_ACCESS","label":"Cliente no está en casa","active":true},{"id":"NEED_ORDER","label":"Necesito una orden","active":true}]', 'Acciones del menú de estado del Técnico (solo textos: cada acción lleva lógica de negocio)', '{"kind":"list","fixedIds":true}'),
 ('photo_categories.list', '[{"id":"ANTES","label":"Antes","active":true},{"id":"DESPUÉS","label":"Después","active":true},{"id":"EQUIPO","label":"Equipo","active":true},{"id":"NAMEPLATE","label":"Nameplate","active":true}]', 'Categorías de las fotos de evidencia (solo textos)', '{"kind":"list","fixedIds":true}'),
 ('photo_sources.list', '[{"id":"CAMERA","label":"Tomar foto","active":true},{"id":"GALLERY","label":"Elegir de galería","active":true}]', 'Origen de la foto (solo textos)', '{"kind":"list","fixedIds":true}'),
 ('share_channels.list', '[{"id":"WHATSAPP","label":"WhatsApp","active":true},{"id":"SMS","label":"SMS","active":true},{"id":"EMAIL","label":"Email","active":true}]', 'Canales para avisar al cliente (solo textos)', '{"kind":"list","fixedIds":true}'),
 ('work_item_types.list', '[{"id":"LABOR","label":"Mano de obra","active":true},{"id":"PART","label":"Repuesto","active":true},{"id":"SERVICE","label":"Servicio","active":true},{"id":"FEE","label":"Cargo","active":true},{"id":"OTHER","label":"Otro","active":true}]', 'Tipos de partida de trabajo (solo textos)', '{"kind":"list","fixedIds":true}')
) as t(k, v, d, c);

insert into public.settings (key, value, type, description, editable_by, visibility, constraints, default_value) values
('pricing.tax_percent', '7.0', 'number', 'Impuesto (%) que se aplica por defecto en las propuestas (Broward County: 7 %)', 'ADMIN', 'AUTH', '{"min":0,"max":30}', '7.0');
