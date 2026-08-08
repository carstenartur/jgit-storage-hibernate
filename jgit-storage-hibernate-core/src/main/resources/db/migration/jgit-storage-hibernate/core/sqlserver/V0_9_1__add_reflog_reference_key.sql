-- Keep reverse reflog lookup selective while remaining below SQL Server index-key limits.
-- The conditional add supports copied/current schemas adopted without Flyway history.

if col_length('git_reflog', 'ref_name_key') is null
begin
    alter table git_reflog
        add ref_name_key nvarchar(128) null;
end
go

update git_reflog
set ref_name_key = left(ref_name, 128)
where ref_name_key is null;
go

alter table git_reflog
    alter column ref_name_key nvarchar(128) not null;
go

drop index if exists idx_reflog_repo_id on git_reflog;
drop index if exists idx_reflog_repo_ref_key_id on git_reflog;
create index idx_reflog_repo_ref_key_id
    on git_reflog (repository_name, ref_name_key, id desc);
go
