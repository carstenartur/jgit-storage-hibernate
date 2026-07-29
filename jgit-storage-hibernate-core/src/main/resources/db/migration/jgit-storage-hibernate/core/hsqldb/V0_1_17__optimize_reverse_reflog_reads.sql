-- Cover repository/ref filtering and newest-first reflog reads with one ordered index.
drop index if exists idx_reflog_repo_ref;
create index idx_reflog_repo_ref_id
    on git_reflog (repository_name, ref_name, id);
