-- Cada categoría indica de qué grupo de precios cobra (pricing.<grupo>.*). Una categoría nueva sin grupo usa «general».
-- Así el precio que ve el cliente sale de settings y no de un mapa fijo en la app.
select set_config('lc.via', 'seed', true);
update public.settings set
  value = '[{"id":"ELECTRODOMESTICOS","label":"Electrodomésticos","active":true,"price_group":"general"},{"id":"HVAC","label":"A/C y Calefacción","active":true,"price_group":"hvac"},{"id":"PLOMERIA","label":"Plomería","active":true,"price_group":"plumbing"},{"id":"ELECTRICIDAD","label":"Electricidad","active":true,"price_group":"electricity"},{"id":"OTRO","label":"Otro","active":true,"price_group":"general"}]'::jsonb,
  default_value = '[{"id":"ELECTRODOMESTICOS","label":"Electrodomésticos","active":true,"price_group":"general"},{"id":"HVAC","label":"A/C y Calefacción","active":true,"price_group":"hvac"},{"id":"PLOMERIA","label":"Plomería","active":true,"price_group":"plumbing"},{"id":"ELECTRICIDAD","label":"Electricidad","active":true,"price_group":"electricity"},{"id":"OTRO","label":"Otro","active":true,"price_group":"general"}]'::jsonb,
  description = 'Categorías de servicio que el cliente elige al solicitar (price_group = grupo de precios: hvac, plumbing, electricity o general)'
where key = 'categories.list';
