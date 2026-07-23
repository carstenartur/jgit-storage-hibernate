-- Adopt the pre-library Sandbox/Taxonomy git_packs schema.
-- Run LegacyCoreSchemaAdoption.requireSafeToAdopt first. The preflight rejects
-- duplicate pack identities and partial schemas before this migration changes data.

alter table git_packs add column committed boolean default true not null;
alter table git_packs add column committed_at timestamp(6);

update git_packs
set committed = true,
    committed_at = coalesce(committed_at, created_at);

alter table git_packs
    add constraint uk_pack_repo_name_ext
    unique (repository_name, pack_name, pack_extension);

create index idx_pack_repo_committed on git_packs (repository_name, committed);
