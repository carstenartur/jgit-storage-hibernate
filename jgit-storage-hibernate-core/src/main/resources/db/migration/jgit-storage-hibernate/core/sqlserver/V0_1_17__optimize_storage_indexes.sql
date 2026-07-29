-- Remove secondary indexes whose leading columns are already covered by unique constraints.
-- Replace two reflog indexes with one index matching the filter and reverse-id ordering.
drop index idx_pack_repo on git_packs;
drop index idx_pack_repo_name on git_packs;
drop index idx_pack_chunk_pack on git_pack_chunks;
drop index idx_reflog_repo on git_reflog;
drop index idx_reflog_repo_ref on git_reflog;

create index idx_reflog_repo_ref_id
    on git_reflog (repository_name, ref_name, id desc);
