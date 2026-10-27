-- Notificaciones push (FCM): token por teléfono asociado a un usuario, y marca de oferta ya notificada.
-- Una tabla en vez de una columna en profiles: un técnico puede tener varios teléfonos, y un token que cambia de
-- usuario (cierre de sesión + otro inicio) se reasigna en vez de quedar duplicado. Solo lc-api (service_role) la toca.

create table if not exists public.push_tokens (
  token text primary key check (length(token) between 20 and 4096),
  user_id uuid not null references auth.users(id) on delete cascade,
  platform text not null default 'android' check (platform in ('android', 'ios', 'web')),
  app_id text,
  created_at bigint not null default public.lc_now_ms(),
  updated_at bigint not null default public.lc_now_ms(),
  last_error text
);
create index if not exists push_tokens_user_idx on public.push_tokens(user_id);
alter table public.push_tokens enable row level security;

alter table public.job_offers add column if not exists notified_at bigint;
create index if not exists job_offers_pend_notif_idx on public.job_offers(estado, expires_at) where notified_at is null;
