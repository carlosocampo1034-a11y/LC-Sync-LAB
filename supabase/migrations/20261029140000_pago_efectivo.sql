-- Orden 65 (Carlos): pago en efectivo registrado por el técnico. ADITIVA: solo amplía lo permitido.
--   * payments.method acepta 'CASH' (la columna es text con CHECK, no un enum).
--   * lc_pay_cash(): el técnico de la orden (o despacho) confirma el cobro en efectivo de una orden COMPLETADA con factura emitida:
--     crea el pago CASH/CONFIRMADO, marca la factura PAGADA, deja «Pago en efectivo confirmado» en el historial y avisa al cliente y a Control.
--     La orden sigue en COMPLETADO. Sin factura se rechaza (422), no se fuerza. Reenviar es idempotente.
select set_config('lc.via', 'seed', true);

alter table public.payments drop constraint if exists payments_method_check;
alter table public.payments add constraint payments_method_check check (method in ('TEST', 'STRIPE', 'CASH'));

create or replace function public.lc_pay_cash(p_actor uuid, p_job bigint, p_key text default null) returns jsonb
language plpgsql security definer set search_path = ''
as $f$
declare
  v_role text; j public.jobs%rowtype; inv public.invoices%rowtype; v_pid bigint;
  v_key text := nullif(btrim(coalesce(p_key, '')), '');
  v_now bigint := public.lc_now_ms();
begin
  if v_key is not null and length(v_key) > 100 then return jsonb_build_object('http', 422, 'error', 'idempotency_key_invalida'); end if;
  select role into v_role from public.profiles where id = p_actor and active;
  if v_role is null or v_role not in ('TECHNICIAN', 'ADMIN', 'DISPATCHER') then
    return jsonb_build_object('http', 403, 'error', 'role_denied');
  end if;
  select * into j from public.jobs where id = p_job for update;
  if not found or (v_role = 'TECHNICIAN' and j.technician_id is distinct from p_actor) then
    return jsonb_build_object('http', 404, 'error', 'not_found');
  end if;
  if j.estado not in ('COMPLETADO', 'PAGADO') then
    return jsonb_build_object('http', 422, 'error', 'trabajo_no_completado', 'mensaje', 'El trabajo debe estar completado para cobrar.');
  end if;
  select * into inv from public.invoices where job_id = p_job for update;
  if not found then
    return jsonb_build_object('http', 422, 'error', 'sin_factura', 'mensaje', 'Esta orden aún no tiene factura (requiere una propuesta aprobada).');
  end if;
  if v_key is not null then
    select id into v_pid from public.payments where idempotency_key = v_key and job_id = p_job;
    if found then return jsonb_build_object('http', 200, 'replay', true, 'estado', 'PAGADA', 'numero', inv.number, 'metodo', 'CASH', 'paymentId', v_pid); end if;
  end if;
  if inv.estado = 'PAGADA' then
    return jsonb_build_object('http', 200, 'replay', true, 'estado', 'PAGADA', 'numero', inv.number,
      'metodo', (select method from public.payments where invoice_id = inv.id order by id desc limit 1));
  end if;
  if inv.estado <> 'EMITIDA' then
    return jsonb_build_object('http', 409, 'error', 'factura_no_pagable', 'mensaje', 'La factura no se puede pagar.');
  end if;
  insert into public.payments (invoice_id, job_id, customer_id, amount, method, status, idempotency_key)
  values (inv.id, p_job, j.customer_id, inv.total, 'CASH', 'CONFIRMADO', v_key) returning id into v_pid;
  update public.invoices set estado = 'PAGADA', paid_at = v_now where id = inv.id;
  insert into public.job_history (job_id, version, field, actor, old_value, new_value)
  values (p_job, j.version, 'pago', p_actor, null, 'Pago en efectivo confirmado $' || inv.total || ' (' || inv.number || ')')
  on conflict do nothing;
  if j.customer_id is not null then
    insert into public.notifications (user_id, job_id, tipo, titulo, cuerpo, created_at)
    values (j.customer_id, p_job, 'PAGO', 'Pago en efectivo confirmado', inv.number || ' · $' || inv.total, v_now);
  end if;
  insert into public.notifications (user_id, job_id, tipo, titulo, cuerpo, created_at)
  values (null, p_job, 'PAGO', 'Pago en efectivo · orden ' || p_job, inv.number || ' · $' || inv.total, v_now);
  return jsonb_build_object('http', 200, 'estado', 'PAGADA', 'numero', inv.number, 'monto', inv.total, 'metodo', 'CASH', 'paymentId', v_pid);
end $f$;
revoke all on function public.lc_pay_cash(uuid, bigint, text) from public, anon, authenticated;
grant execute on function public.lc_pay_cash(uuid, bigint, text) to service_role;
