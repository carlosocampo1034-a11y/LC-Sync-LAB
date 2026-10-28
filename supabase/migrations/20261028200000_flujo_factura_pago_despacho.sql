-- Orden 16: despacho desde settings (dispatch.*), facturas y pagos de PRUEBA, factura automática al completar una orden con
-- propuesta aprobada, textos de estado para el cliente. Aditiva. Los pagos reales (Stripe) siguen pendientes de credenciales.
select set_config('lc.via', 'seed', true);

insert into public.settings (key, value, type, description, editable_by, visibility, constraints, default_value) values
('dispatch.enabled', 'true', 'boolean', 'Despacho automático encendido (si se apaga, las órdenes nuevas no se ofrecen a técnicos)', 'ADMIN+DISPATCHER', 'STAFF', '{}', 'true'),
('dispatch.fanout', '3', 'number', 'Cuántos técnicos reciben la oferta a la vez', 'ADMIN+DISPATCHER', 'STAFF', '{"min":1,"max":10}', '3'),
('dispatch.offer_ttl_seconds', '120', 'number', 'Segundos que dura una oferta antes de pasar al siguiente técnico', 'ADMIN+DISPATCHER', 'STAFF', '{"min":20,"max":3600}', '120'),
('dispatch.fallback_to_generalists', 'true', 'boolean', 'Si ningún técnico tiene la especialidad, ofrecer a cualquier técnico disponible', 'ADMIN+DISPATCHER', 'STAFF', '{}', 'true'),
('payments.mode', '"off"', 'string', 'Pagos: off (sin pagar), simulated («Pagar (prueba)», sin cobro real) o stripe (requiere claves; aún no disponible)', 'ADMIN', 'AUTH', '{"enum":["off","simulated","stripe"]}', '"off"')
on conflict (key) do nothing;

-- texto de estado para el cliente
update public.settings set
  value = (select jsonb_agg(case o->>'id' when 'EN RUTA' then o || '{"customer_label":"Tu técnico va en camino"}'::jsonb
                                         when 'COMPLETADO' then o || '{"customer_label":"Trabajo completado"}'::jsonb else o end) from jsonb_array_elements(value) o),
  default_value = (select jsonb_agg(case o->>'id' when 'EN RUTA' then o || '{"customer_label":"Tu técnico va en camino"}'::jsonb
                                         when 'COMPLETADO' then o || '{"customer_label":"Trabajo completado"}'::jsonb else o end) from jsonb_array_elements(default_value) o)
where key = 'order_states.list';

-- despacho leyendo settings
create or replace function public.lc_dispatch_job(p_job bigint) returns integer
language plpgsql security definer set search_path = ''
as $$
declare
  v_cfg public.dispatch_config%rowtype;
  v_enabled boolean; v_fanout integer; v_ttl integer; v_fallback boolean;
  v_job public.jobs%rowtype;
  v_now bigint := public.lc_now_ms();
  v_open integer;
  v_made integer := 0;
  v_cat text;
  v_spec integer;
begin
  -- configuración desde settings (dispatch.*); dispatch_config queda solo como respaldo si faltara
  select * into v_cfg from public.dispatch_config where id = 1;
  v_enabled  := coalesce((public.lc_setting('dispatch.enabled') #>> '{}')::boolean, v_cfg.enabled, true);
  v_fanout   := coalesce((public.lc_setting('dispatch.fanout') #>> '{}')::numeric::int, v_cfg.fanout, 3);
  v_ttl      := coalesce((public.lc_setting('dispatch.offer_ttl_seconds') #>> '{}')::numeric::int, v_cfg.offer_ttl_seconds, 120);
  v_fallback := coalesce((public.lc_setting('dispatch.fallback_to_generalists') #>> '{}')::boolean, true);
  if not v_enabled then return 0; end if;
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
  if v_open >= v_fanout then return 0; end if;
  v_cat := public.lc_norm(v_job.categoria);

  -- ¿hay técnicos activos y disponibles con la especialidad de la orden?
  select count(*) into v_spec from public.profiles p
   where p.role = 'TECHNICIAN' and p.active and p.available and public.lc_tech_matches(p.specialties, v_cat);

  with cand as (
    select p.id
      from public.profiles p
     where p.role = 'TECHNICIAN' and p.active and p.available
       and ((v_spec = 0 and v_fallback) or public.lc_tech_matches(p.specialties, v_cat))   -- sin especialistas: cualquiera
       and not exists (select 1 from public.jobs j where j.technician_id = p.id
                        and j.estado in ('EN RUTA', 'LLEGUÉ', 'EN REPARACIÓN', 'ESPERANDO APROBACIÓN'))
       and not exists (select 1 from public.job_offers o where o.technician_id = p.id
                        and o.estado = 'OFRECIDA' and o.expires_at >= v_now)
       and not exists (select 1 from public.job_offers o where o.technician_id = p.id and o.job_id = p_job)
     order by coalesce((select max(o.responded_at) from public.job_offers o
                         where o.technician_id = p.id and o.estado = 'ACEPTADA'), 0), p.id
     limit v_fanout - v_open
  ), ins as (
    insert into public.job_offers (job_id, technician_id, expires_at)
    select p_job, c.id, v_now + v_ttl * 1000 from cand c
    returning 1
  )
  select count(*) into v_made from ins;
  return v_made;
end;
$$;
revoke all on function public.lc_dispatch_job(bigint) from public, anon, authenticated;
grant execute on function public.lc_dispatch_job(bigint) to service_role;

-- ── Facturas y pagos ───────────────────────────────────────────────────────────────────────────────────
create table if not exists public.invoices (
  id          bigserial primary key,
  job_id      bigint not null unique references public.jobs(id) on delete cascade,
  number      text,
  items       jsonb  not null default '[]'::jsonb,
  subtotal    numeric(12,2) not null,
  tax_pct     numeric(6,2)  not null default 0,
  tax         numeric(12,2) not null default 0,
  total       numeric(12,2) not null check (total >= 0),
  estado      text   not null default 'EMITIDA' check (estado in ('EMITIDA','PAGADA','ANULADA')),
  proposal_id bigint,
  created_at  bigint not null default (extract(epoch from clock_timestamp()) * 1000)::bigint,
  paid_at     bigint
);
create table if not exists public.payments (
  id                       bigserial primary key,
  invoice_id               bigint not null references public.invoices(id) on delete cascade,
  job_id                   bigint not null references public.jobs(id) on delete cascade,
  customer_id              uuid,
  amount                   numeric(12,2) not null check (amount > 0),
  currency                 text not null default 'USD',
  method                   text not null default 'TEST' check (method in ('TEST','STRIPE')),
  status                   text not null default 'CONFIRMADO' check (status in ('PENDIENTE','CONFIRMADO','RECHAZADO','REEMBOLSADO')),
  stripe_payment_intent_id text,
  created_at               bigint not null default (extract(epoch from clock_timestamp()) * 1000)::bigint
);
create index if not exists payments_job_idx on public.payments (job_id);
alter table public.invoices enable row level security;
alter table public.payments enable row level security;
revoke all on public.invoices, public.payments from anon, authenticated;
revoke all on sequence public.invoices_id_seq, public.payments_id_seq from anon, authenticated;

-- Factura automática: al completarse una orden con propuesta APROBADA, con las partidas de la opción elegida
create or replace function public.lc_invoice_auto(p_job bigint) returns bigint
language plpgsql security definer set search_path = '' as $$
declare
  j public.jobs%rowtype; p public.job_proposals%rowtype; o jsonb; v_items jsonb := '[]'::jsonb;
  v_duros numeric := 0; v_mk numeric; v_desc numeric; v_base numeric; v_total numeric; v_tax numeric; v_taxpct numeric; v_id bigint;
  c record; v_monto numeric;
begin
  select * into j from public.jobs where id = p_job;
  if not found or j.estado <> 'COMPLETADO' then return null; end if;
  if exists (select 1 from public.invoices where job_id = p_job) then return null; end if;
  select * into p from public.job_proposals where job_id = p_job and estado = 'APROBADA' order by revision desc limit 1;
  if not found then return null; end if;
  select x into o from jsonb_array_elements(p.opciones) x where x->>'nivel' = p.opcion_elegida limit 1;
  if o is null then return null; end if;
  for c in select * from (values ('costoManoObra','Mano de obra',1), ('costoMateriales','Materiales',2), ('costoEquipo','Equipo',3),
                                 ('costoPermiso','Permiso',4), ('costoRemocion','Remoción',5), ('costoAccesorios','Accesorios',6)) t(k, etq, ord) order by ord loop
    v_monto := coalesce((o->>c.k)::numeric, 0);
    if v_monto > 0 then
      v_items := v_items || jsonb_build_array(jsonb_build_object('descripcion', c.etq, 'cantidad', 1, 'precio', v_monto, 'importe', v_monto));
      v_duros := v_duros + v_monto;
    end if;
  end loop;
  v_mk := round(v_duros * coalesce((o->>'markupPorcentaje')::numeric, 0) / 100, 2);
  if v_mk > 0 then
    v_items := v_items || jsonb_build_array(jsonb_build_object('descripcion', 'Margen (' || (o->>'markupPorcentaje') || '%)', 'cantidad', 1, 'precio', v_mk, 'importe', v_mk));
  end if;
  v_desc := coalesce((o->>'descuentoMonto')::numeric, 0);
  if v_desc > 0 then
    v_items := v_items || jsonb_build_array(jsonb_build_object('descripcion', 'Descuento', 'cantidad', 1, 'precio', -v_desc, 'importe', -v_desc));
  end if;
  v_base := v_duros + v_mk - v_desc;
  v_total := coalesce(p.total_aceptado, round(v_base * (1 + coalesce((o->>'impuestoPorcentaje')::numeric, 0) / 100), 2));
  v_tax := round(v_total - v_base, 2);
  v_taxpct := coalesce((o->>'impuestoPorcentaje')::numeric, 0);
  insert into public.invoices (job_id, items, subtotal, tax_pct, tax, total, proposal_id)
  values (p_job, v_items, round(v_base, 2), v_taxpct, v_tax, v_total, p.id) returning id into v_id;
  update public.invoices set number = 'INV-' || lpad(v_id::text, 5, '0') where id = v_id;
  insert into public.job_history (job_id, version, field, actor, old_value, new_value)
  values (p_job, j.version, 'factura', coalesce(j.technician_id, j.created_by), null, 'INV-' || lpad(v_id::text, 5, '0') || ' · total $' || v_total);
  if j.customer_id is not null then
    insert into public.notifications (user_id, job_id, tipo, titulo, cuerpo, created_at)
    values (j.customer_id, p_job, 'FACTURA', 'Factura disponible', 'Tu factura INV-' || lpad(v_id::text, 5, '0') || ' por $' || v_total || ' está lista.', public.lc_now_ms());
  end if;
  return v_id;
end $$;

create or replace function public.lc_jobs_invoice_au() returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if new.estado = 'COMPLETADO' and old.estado is distinct from 'COMPLETADO' then perform public.lc_invoice_auto(new.id); end if;
  return new;
end $$;
drop trigger if exists lc_jobs_invoice_au on public.jobs;
create trigger lc_jobs_invoice_au after update of estado on public.jobs for each row execute function public.lc_jobs_invoice_au();

-- Pago de PRUEBA del cliente (sin cobro real): solo con payments.mode = simulated
create or replace function public.lc_pay_test(p_actor uuid, p_job bigint) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare j public.jobs%rowtype; inv public.invoices%rowtype; v_pid bigint;
begin
  select * into j from public.jobs where id = p_job;
  if not found or j.customer_id is distinct from p_actor then return jsonb_build_object('http', 404, 'error', 'not_found'); end if;
  if coalesce(public.lc_setting('payments.mode') #>> '{}', 'off') <> 'simulated' then
    return jsonb_build_object('http', 409, 'error', 'Los pagos de prueba no están activados.');
  end if;
  select * into inv from public.invoices where job_id = p_job for update;
  if not found then return jsonb_build_object('http', 404, 'error', 'Esta orden aún no tiene factura.'); end if;
  if inv.estado = 'PAGADA' then
    return jsonb_build_object('http', 200, 'replay', true, 'estado', 'PAGADA', 'numero', inv.number);
  end if;
  if inv.estado <> 'EMITIDA' then return jsonb_build_object('http', 409, 'error', 'La factura no se puede pagar.'); end if;
  insert into public.payments (invoice_id, job_id, customer_id, amount, method, status) values (inv.id, p_job, p_actor, inv.total, 'TEST', 'CONFIRMADO') returning id into v_pid;
  update public.invoices set estado = 'PAGADA', paid_at = public.lc_now_ms() where id = inv.id;
  insert into public.job_history (job_id, version, field, actor, old_value, new_value)
  values (p_job, j.version, 'pago', p_actor, null, 'Pago de prueba $' || inv.total || ' confirmado (' || inv.number || ')');
  insert into public.notifications (user_id, job_id, tipo, titulo, cuerpo, created_at)
  values (null, p_job, 'PAGO', 'Pago de prueba recibido · orden ' || p_job, inv.number || ' · $' || inv.total, public.lc_now_ms());
  return jsonb_build_object('http', 200, 'estado', 'PAGADA', 'numero', inv.number, 'monto', inv.total, 'paymentId', v_pid);
end $$;

do $$ declare f text; begin
  foreach f in array array['lc_invoice_auto(bigint)', 'lc_jobs_invoice_au()', 'lc_pay_test(uuid,bigint)'] loop
    execute format('revoke all on function public.%s from public, anon, authenticated', f);
    execute format('grant execute on function public.%s to service_role', f);
  end loop;
end $$;
