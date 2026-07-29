-- Remove secondary indexes whose leading columns are already covered by unique constraints.
-- Replace two reflog indexes with one index matching the filter and reverse-id ordering.
drop index idx_pack_repo;
drop index idx_pack_repo_name;
drop index idx_pack_chunk_pack;
drop index idx_reflog_repo;
drop index idx_reflog_repo_ref;

create index idx_reflog_repo_ref_id
    on git_reflog (repository_name, ref_name, id desc);
