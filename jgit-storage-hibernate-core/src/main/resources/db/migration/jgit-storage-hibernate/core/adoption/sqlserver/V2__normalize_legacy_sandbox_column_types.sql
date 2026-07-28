-- Normalize the copied Sandbox mappings to the released Core contract.
-- Drop dependent indexes and constraints before altering indexed nationalized columns.
drop index if exists idx_pack_repo on git_packs;
drop index if exists idx_pack_repo_name on git_packs;
drop index if exists idx_pack_repo_committed on git_packs;
alter table git_packs drop constraint if exists uk_pack_repo_name_ext;

alter table git_packs alter column repository_name nvarchar(255) not null;
alter table git_packs alter column pack_name nvarchar(255) not null;
alter table git_packs alter column pack_extension varchar(32) not null;
alter table git_packs alter column created_at datetimeoffset(7) not null;
alter table git_packs alter column committed_at datetimeoffset(7) null;

alter table git_packs
    add constraint uk_pack_repo_name_ext
    unique (repository_name, pack_name, pack_extension);
create index idx_pack_repo on git_packs (repository_name);
create index idx_pack_repo_name on git_packs (repository_name, pack_name);
create index idx_pack_repo_committed on git_packs (repository_name, committed);

drop index if exists idx_reflog_repo on git_reflog;
drop index if exists idx_reflog_repo_ref on git_reflog;

alter table git_reflog alter column repository_name nvarchar(255) not null;
alter table git_reflog alter column ref_name nvarchar(1024) not null;
alter table git_reflog alter column who_when datetimeoffset(7) not null;

create index idx_reflog_repo on git_reflog (repository_name);
-- The full nationalized key exceeds SQL Server's 1700-byte nonclustered key limit.
create index idx_reflog_repo_ref on git_reflog (repository_name) include (ref_name);
