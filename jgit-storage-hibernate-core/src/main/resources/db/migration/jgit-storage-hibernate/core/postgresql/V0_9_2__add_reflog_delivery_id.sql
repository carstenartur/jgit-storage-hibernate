-- Delivery IDs make durable queryable reflog batches replay-safe.
-- IF NOT EXISTS also supports copied/current schemas adopted without Flyway history.

alter table git_reflog
    add column if not exists delivery_id varchar(128);

drop index if exists idx_reflog_repo_delivery;
create index idx_reflog_repo_delivery
    on git_reflog (repository_name, delivery_id)
    where delivery_id is not null;
