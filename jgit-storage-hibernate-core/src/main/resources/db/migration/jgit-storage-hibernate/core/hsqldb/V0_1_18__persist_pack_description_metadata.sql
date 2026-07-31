-- Preserve JGit's logical pack ordering and maintenance metadata across repository reopen.
alter table git_packs add column pack_source varchar(32);
alter table git_packs add column last_modified bigint;
alter table git_packs add column object_count bigint;
alter table git_packs add column delta_count bigint;
alter table git_packs add column index_version integer;
alter table git_packs add column min_update_index bigint;
alter table git_packs add column max_update_index bigint;

-- Pack rows belong to the same durable repository lifecycle as the coordination row.
-- Backfill historical repositories before adding the foreign key.
insert into git_repository_lock (repository_name, created_at)
select p.repository_name, min(p.created_at)
from git_packs p
where not exists (
    select 1 from git_repository_lock l
    where l.repository_name = p.repository_name
)
group by p.repository_name;

alter table git_packs add constraint fk_pack_repository_lock
    foreign key (repository_name)
    references git_repository_lock (repository_name)
    on delete cascade;
