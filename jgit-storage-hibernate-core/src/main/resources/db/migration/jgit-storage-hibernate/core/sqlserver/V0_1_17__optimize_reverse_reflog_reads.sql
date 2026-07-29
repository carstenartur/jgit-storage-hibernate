-- ref_name is nvarchar(1024) and cannot be part of a 1700-byte SQL Server index key.
-- Keep it as an included residual predicate while repository/id provide newest-first access.
drop index if exists idx_reflog_repo_ref on git_reflog;
create index idx_reflog_repo_id
    on git_reflog (repository_name, id desc)
    include (ref_name);
