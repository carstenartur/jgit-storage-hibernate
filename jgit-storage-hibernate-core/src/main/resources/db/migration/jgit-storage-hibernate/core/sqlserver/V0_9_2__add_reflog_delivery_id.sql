-- Delivery IDs make durable queryable reflog batches replay-safe.
-- The conditional add supports copied/current schemas adopted without Flyway history.

if col_length('git_reflog', 'delivery_id') is null
begin
    alter table git_reflog
        add delivery_id nvarchar(128) null;
end
go

drop index if exists idx_reflog_repo_delivery on git_reflog;
create index idx_reflog_repo_delivery
    on git_reflog (repository_name, delivery_id)
    where delivery_id is not null;
go
