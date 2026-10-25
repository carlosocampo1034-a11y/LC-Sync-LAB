create index if not exists job_conflicts_actor_idx on public.job_conflicts(actor); create index if not exists job_history_actor_idx on public.job_history(actor); create index if not exists jobs_created_by_idx on public.jobs(created_by); create index if not exists sync_receipts_job_id_idx on public.sync_receipts(job_id);
;
