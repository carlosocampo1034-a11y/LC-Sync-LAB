-- Prueba en seco de 20261025170000: ejecutar dentro de begin; ... rollback; (termina con RAISE DRYRUN_OK y no deja datos).
do $t$
declare
  c uuid := gen_random_uuid(); d uuid := gen_random_uuid(); t1 uuid := gen_random_uuid(); t2 uuid := gen_random_uuid();
  r jsonb; jid bigint; n int; log text := '';
  procedure_ok boolean;
begin
  insert into auth.users (id, aud, role, email, created_at, updated_at)
  select u, 'authenticated', 'authenticated', 'qa-dryrun-' || u || '@lab.invalid', now(), now() from unnest(array[c,d,t1,t2]) u;
  update public.profiles set role = 'DISPATCHER' where id = d;
  update public.profiles set role = 'TECHNICIAN', technician_code = 'QA-T1-' || left(t1::text, 6) where id = t1;
  update public.profiles set role = 'TECHNICIAN', technician_code = 'QA-T2-' || left(t2::text, 6) where id = t2;

  r := public.lc_create_order(c, 'req-0001-abcdef', '{"cliente":"Cliente Ficticio QA","descripcion":"No enfría","direccion":"Calle Falsa 123"}');
  assert r->>'http' = '201', '01 create ' || r; jid := (r->>'id')::bigint; log := log || '01 ';
  r := public.lc_create_order(c, 'req-0001-abcdef', '{"cliente":"Cliente Ficticio QA","descripcion":"No enfría"}');
  assert r->>'http' = '200' and (r->>'id')::bigint = jid and (r->>'replay')::boolean, '02 replay ' || r; log := log || '02 ';
  select count(*) into n from public.jobs where created_by = c; assert n = 1, '03 duplicados ' || n; log := log || '03 ';
  r := public.lc_create_order(t1, 'req-0002-abcdef', '{"cliente":"x","descripcion":"y"}');
  assert r->>'http' = '403', '04 tech create ' || r; log := log || '04 ';
  r := public.lc_create_order(c, 'short', '{"cliente":"x","descripcion":"y"}');
  assert r->>'http' = '422', '05 request_id ' || r; log := log || '05 ';

  r := public.lc_patch_order(d, jid, 1, jsonb_build_object('technician_id', t1, 'estado_dispatch', 'ASIGNADO'), 'control-web', 1000);
  assert r->>'status' = 'ACCEPTED' and r->>'version' = '2', '06 assign ' || r; log := log || '06 ';
  select count(*) into n from public.jobs where id = jid and technician_code like 'QA-T1-%'; assert n = 1, '07 tcode'; log := log || '07 ';
  r := public.lc_patch_order(t1, jid, 2, '{"estado":"EN RUTA"}', 'tech-dev-1', 2000);
  assert r->>'status' = 'ACCEPTED' and r->>'version' = '3', '08 tech estado ' || r; log := log || '08 ';
  r := public.lc_patch_order(t1, jid, 2, '{"estado":"EN RUTA"}', 'tech-dev-1', 2000);
  assert (r->>'replay')::boolean and r->>'version' = '3', '09 tech replay ' || r; log := log || '09 ';
  select version into n from public.jobs where id = jid; assert n = 3, '10 replay no reaplica ' || n; log := log || '10 ';
  r := public.lc_patch_order(t1, jid, 3, jsonb_build_object('technician_id', t2), 'tech-dev-1', 2001);
  assert r->>'http' = '403' and r->>'error' = 'field_denied', '11 tech reasigna ' || r; log := log || '11 ';
  r := public.lc_patch_order(t2, jid, 3, '{"estado":"LLEGUÉ"}', 'tech-dev-2', 3000);
  assert r->>'http' = '404', '12 otro tecnico ' || r; log := log || '12 ';
  r := public.lc_patch_order(c, jid, 3, '{"notas":"hola"}', 'cust-1', 3000);
  assert r->>'http' = '403', '13 customer patch ' || r; log := log || '13 ';
  r := public.lc_patch_order(t1, jid, 3, '{"estado":"VOLANDO"}', 'tech-dev-1', 2002);
  assert r->>'http' = '422', '14 estado invalido ' || r; log := log || '14 ';
  r := public.lc_patch_order(t1, jid, 3, '{"estado":"CANCELADO"}', 'tech-dev-1', 2003);
  assert r->>'http' = '422', '15 tech cancela ' || r; log := log || '15 ';
  -- dispatcher con base vieja (2): estado lo tocó el técnico en v3 → CONFLICT; nada se aplica
  r := public.lc_patch_order(d, jid, 2, '{"estado":"PAUSADO"}', 'control-web', 1001);
  assert r->>'status' = 'CONFLICT' and r->'conflictFields' ? 'estado', '16 conflicto ' || r; log := log || '16 ';
  select count(*) into n from public.job_conflicts where job_id = jid; assert n = 1, '17 conflicto registrado ' || n; log := log || '17 ';
  -- base vieja pero campo no disputado → se aplica
  r := public.lc_patch_order(d, jid, 2, '{"notas":"Llamar antes"}', 'control-web', 1002);
  assert r->>'status' = 'ACCEPTED' and r->>'version' = '4', '18 merge no disputado ' || r; log := log || '18 ';
  r := public.lc_patch_order(d, jid, 9, '{"notas":"x"}', 'control-web', 1003);
  assert r->>'motivo' = 'future_version', '19 version futura ' || r; log := log || '19 ';
  r := public.lc_patch_order(t1, jid, 4, '{"horas":"1.5"}', 'tech-dev-1', 2004);
  r := public.lc_patch_order(t1, jid, 5, '{"horas":"1.50"}', 'tech-dev-1', 2005);
  assert r->>'version' = '5', '20 horas normalizadas ' || r; log := log || '20 ';
  r := public.lc_patch_order(t1, jid, 5, '{"estado":"COMPLETADO"}', 'tech-dev-1', 2006);
  assert r->>'status' = 'ACCEPTED' and r->>'version' = '6', '21 completar ' || r; log := log || '21 ';
  select count(*) into n from public.job_history where job_id = jid; assert n = 7, '22 historial ' || n; log := log || '22 ';
  select count(*) into n from public.sync_receipts where job_id = jid; assert n = 7, '23 recibos ' || n; log := log || '23 ';

  -- RLS vista por cada rol (como lo vería Realtime / supabase-js)
  perform set_config('request.jwt.claims', json_build_object('sub', t2, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  select count(*) into n from public.jobs where id = jid; assert n = 0, '24 RLS t2 ve orden ajena'; log := log || '24 ';
  execute 'reset role';
  perform set_config('request.jwt.claims', json_build_object('sub', c, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  select count(*) into n from public.jobs where id = jid; assert n = 1, '25 RLS customer'; log := log || '25 ';
  select count(*) into n from public.job_conflicts; assert n = 0, '26 RLS conflicts customer'; log := log || '26 ';
  execute 'reset role';
  perform set_config('request.jwt.claims', json_build_object('sub', t1, 'role', 'authenticated')::text, true);
  execute 'set local role authenticated';
  select count(*) into n from public.jobs where id = jid; assert n = 1, '27 RLS t1'; log := log || '27 ';
  begin
    perform public.lc_patch_order(t1, jid, 6, '{"notas":"x"}', 'hack', 9);
    assert false, '28 authenticated pudo ejecutar lc_patch_order';
  exception when insufficient_privilege then log := log || '28 ';
  end;
  begin
    update public.jobs set estado = 'CANCELADO' where id = jid;
    assert false, '29 authenticated pudo hacer UPDATE directo';
  exception when insufficient_privilege then log := log || '29 ';
  end;
  execute 'reset role';
  raise exception 'DRYRUN_OK %', log;
end $t$;
