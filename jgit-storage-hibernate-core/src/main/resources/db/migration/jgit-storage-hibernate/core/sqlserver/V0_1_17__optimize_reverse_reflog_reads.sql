-- Unique keys already cover repository/pack and pack/chunk leading-column lookups.
drop index if exists idx_pack_repo on git_packs;
drop index if exists idx_pack_repo_name on git_packs;
drop index if exists idx_pack_chunk_pack on git_pack_chunks;

-- ref_name is nvarchar(1024) and cannot be part of a portable SQL Server key.
-- Normalize old and pre-library variants, then keep ref_name as an included residual predicate.
drop index if exists idx_reflog_repo on git_reflog;
drop index if exists idx_reflog_repo_ref on git_reflog;
drop index if exists idx_reflog_repo_id on git_reflog;
create index idx_reflog_repo_id
    on git_reflog (repository_name, id desc)
    include (ref_name);
