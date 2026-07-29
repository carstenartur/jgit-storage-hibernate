-- Unique keys already cover repository/pack and pack/chunk leading-column lookups.
drop index if exists idx_pack_repo;
drop index if exists idx_pack_repo_name;
drop index if exists idx_pack_chunk_pack;

-- Replace two reflog append indexes with one filter-and-order index.
drop index if exists idx_reflog_repo;
drop index if exists idx_reflog_repo_ref;
create index idx_reflog_repo_ref_id
    on git_reflog (repository_name, ref_name, id desc);
