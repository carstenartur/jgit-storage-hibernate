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
