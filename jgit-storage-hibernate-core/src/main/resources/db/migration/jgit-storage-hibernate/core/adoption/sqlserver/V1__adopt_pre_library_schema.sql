-- Adopt the exact pre-library Sandbox/Taxonomy pack schema without rewriting payload bytes.
alter table git_packs add committed bit null;
alter table git_packs add committed_at datetimeoffset(7) null;

update git_packs
set committed = 1,
    committed_at = created_at;

alter table git_packs alter column committed bit not null;

alter table git_packs
    add constraint uk_pack_repo_name_ext
    unique (repository_name, pack_name, pack_extension);

create index idx_pack_repo_committed
    on git_packs (repository_name, committed);
