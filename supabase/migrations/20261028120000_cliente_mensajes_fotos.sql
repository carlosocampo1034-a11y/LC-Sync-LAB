-- L&C Cliente QA: mensajes por orden y fotos de la solicitud. Aditiva (no toca tablas existentes).
-- Solo lc-api (service_role) lee/escribe; anon y authenticated no tienen acceso directo.
create table if not exists public.job_messages (
  id            bigserial primary key,
  job_id        bigint not null references public.jobs(id) on delete cascade,
  sender_id     uuid   not null,
  sender_role   text   not null check (sender_role in ('ADMIN','DISPATCHER','TECHNICIAN','CUSTOMER')),
  body          text   not null check (char_length(body) between 1 and 2000),
  client_msg_id text,
  created_at    bigint not null default (extract(epoch from clock_timestamp()) * 1000)::bigint
);
create unique index if not exists job_messages_idem on public.job_messages (job_id, sender_id, client_msg_id) where client_msg_id is not null;
create index if not exists job_messages_job on public.job_messages (job_id, id);
alter table public.job_messages enable row level security;
revoke all on public.job_messages from anon, authenticated;
revoke all on sequence public.job_messages_id_seq from anon, authenticated;

create table if not exists public.job_photos (
  id              bigserial primary key,
  job_id          bigint not null references public.jobs(id) on delete cascade,
  uploaded_by     uuid   not null,
  categoria       text   not null default 'BEFORE' check (categoria in ('BEFORE','AFTER','OTHER')),
  client_photo_id bigint,
  mime            text   not null check (mime in ('image/jpeg','image/png')),
  path            text   not null,
  bytes           integer not null check (bytes between 1 and 6000000),
  created_at      bigint not null default (extract(epoch from clock_timestamp()) * 1000)::bigint
);
create unique index if not exists job_photos_idem on public.job_photos (job_id, uploaded_by, client_photo_id) where client_photo_id is not null;
create index if not exists job_photos_job on public.job_photos (job_id, id);
alter table public.job_photos enable row level security;
revoke all on public.job_photos from anon, authenticated;
revoke all on sequence public.job_photos_id_seq from anon, authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('job-photos', 'job-photos', false, 6000000, array['image/jpeg','image/png'])
on conflict (id) do nothing;
