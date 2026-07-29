-- Unique keys already cover repository/pack and pack/chunk leading-column lookups.
drop index if exists idx_pack_repo;
drop index if exists idx_pack_repo_name;
drop index if exists idx_pack_chunk_pack;

-- Normalize current, legacy and pre-library index variants to one ordered access path.
drop index if exists idx_reflog_repo;
drop index if exists idx_reflog_repo_ref;
drop index if exists idx_reflog_repo_ref_id;
create index idx_reflog_repo_ref_id
    on git_reflog (repository_name, ref_name, id desc);
