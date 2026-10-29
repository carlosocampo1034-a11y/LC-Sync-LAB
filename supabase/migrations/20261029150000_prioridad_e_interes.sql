-- Orden 68 (Carlos). ADITIVA.
--  1) jobs.prioridad: valores ALTA/MEDIA/BAJA (se conservan NORMAL y URGENTE que ya usaba la app) y valor por defecto MEDIA
--     para las órdenes NUEVAS. Las órdenes existentes quedan sin prioridad (NULL) y la app no la muestra.
--  2) feature_interest: «Notifícame cuando esté listo» de las funciones aún no disponibles (pagos, ofertas, documentos).
select set_config('lc.via', 'seed', true);

alter table public.jobs alter column prioridad set default 'MEDIA';
alter table public.jobs drop constraint if exists jobs_prioridad_valida;
alter table public.jobs add constraint jobs_prioridad_valida
  check (prioridad is null or prioridad in ('ALTA', 'MEDIA', 'BAJA', 'NORMAL', 'URGENTE'));

create table if not exists public.feature_interest (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  feature text not null check (feature in ('PAGOS', 'OFERTAS', 'DOCUMENTOS')),
  created_at bigint not null default public.lc_now_ms(),
  unique (user_id, feature)
);
alter table public.feature_interest enable row level security;
revoke all on public.feature_interest from anon, authenticated;   -- solo la función lc-api (service_role) escribe y lee
