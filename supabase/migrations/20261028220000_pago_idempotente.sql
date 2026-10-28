-- Orden 29 · Resiliencia de red: clave de idempotencia por intento de pago.
-- Aditivo: una columna nueva (nullable) y un índice único parcial. Un reintento con la misma clave devuelve el pago ya
-- registrado en lugar de crear otro; sin clave, el comportamiento anterior (factura PAGADA = «replay») no cambia.
alter table public.payments add column if not exists idempotency_key text;
create unique index if not exists payments_idempotency_key_uq on public.payments (idempotency_key) where idempotency_key is not null;

drop function if exists public.lc_pay_test(uuid, bigint);
create or replace function public.lc_pay_test(p_actor uuid, p_job bigint, p_key text default null)
 returns jsonb language plpgsql security definer set search_path to ''
as $function$
declare j public.jobs%rowtype; inv public.invoices%rowtype; v_pid bigint; v_key text := nullif(btrim(coalesce(p_key, '')), '');
begin
  if v_key is not null and length(v_key) > 100 then return jsonb_build_object('http', 422, 'error', 'idempotency_key_invalida'); end if;
  select * into j from public.jobs where id = p_job;
  if not found or j.customer_id is distinct from p_actor then return jsonb_build_object('http', 404, 'error', 'not_found'); end if;
  if coalesce(public.lc_setting('payments.mode') #>> '{}', 'off') <> 'simulated' then
    return jsonb_build_object('http', 409, 'error', 'Los pagos de prueba no están activados.');
  end if;
  select * into inv from public.invoices where job_id = p_job for update;
  if not found then return jsonb_build_object('http', 404, 'error', 'Esta orden aún no tiene factura.'); end if;
  -- misma clave ya usada en esta orden: es el mismo intento reenviado, se devuelve el mismo resultado
  if v_key is not null then
    select id into v_pid from public.payments where idempotency_key = v_key and job_id = p_job and customer_id = p_actor;
    if found then
      return jsonb_build_object('http', 200, 'replay', true, 'estado', 'PAGADA', 'numero', inv.number, 'paymentId', v_pid);
    end if;
  end if;
  if inv.estado = 'PAGADA' then
    return jsonb_build_object('http', 200, 'replay', true, 'estado', 'PAGADA', 'numero', inv.number);
  end if;
  if inv.estado <> 'EMITIDA' then return jsonb_build_object('http', 409, 'error', 'La factura no se puede pagar.'); end if;
  insert into public.payments (invoice_id, job_id, customer_id, amount, method, status, idempotency_key)
  values (inv.id, p_job, p_actor, inv.total, 'TEST', 'CONFIRMADO', v_key) returning id into v_pid;
  update public.invoices set estado = 'PAGADA', paid_at = public.lc_now_ms() where id = inv.id;
  insert into public.job_history (job_id, version, field, actor, old_value, new_value)
  values (p_job, j.version, 'pago', p_actor, null, 'Pago de prueba $' || inv.total || ' confirmado (' || inv.number || ')');
  insert into public.notifications (user_id, job_id, tipo, titulo, cuerpo, created_at)
  values (null, p_job, 'PAGO', 'Pago de prueba recibido · orden ' || p_job, inv.number || ' · $' || inv.total, public.lc_now_ms());
  return jsonb_build_object('http', 200, 'estado', 'PAGADA', 'numero', inv.number, 'monto', inv.total, 'paymentId', v_pid);
end $function$;
revoke all on function public.lc_pay_test(uuid, bigint, text) from public, anon, authenticated;
grant execute on function public.lc_pay_test(uuid, bigint, text) to service_role;
