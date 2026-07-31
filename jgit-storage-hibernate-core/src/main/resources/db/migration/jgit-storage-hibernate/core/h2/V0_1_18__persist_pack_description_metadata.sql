-- Preserve JGit's logical pack ordering and maintenance metadata across repository reopen.
alter table git_packs add column pack_source varchar(32);
alter table git_packs add column last_modified bigint;
alter table git_packs add column object_count bigint;
alter table git_packs add column delta_count bigint;
alter table git_packs add column index_version integer;
alter table git_packs add column min_update_index bigint;
alter table git_packs add column max_update_index bigint;

-- Separate durable repository existence from the row used for short publication locks.
create table if not exists git_repository_lifecycle (
    repository_name varchar(255) not null,
    created_at timestamp(6) with time zone not null,
    primary key (repository_name)
);

-- Preserve empty repositories already represented only by their coordination row.
insert into git_repository_lifecycle (repository_name, created_at)
select l.repository_name, min(l.created_at)
from git_repository_lock l
where not exists (
    select 1 from git_repository_lifecycle existing
    where existing.repository_name = l.repository_name
)
group by l.repository_name;

-- Adopt historical repositories that predate the coordination table.
insert into git_repository_lifecycle (repository_name, created_at)
select p.repository_name, min(p.created_at)
from git_packs p
where not exists (
    select 1 from git_repository_lifecycle existing
    where existing.repository_name = p.repository_name
)
group by p.repository_name;

insert into git_repository_lifecycle (repository_name, created_at)
select r.repository_name, min(r.who_when)
from git_reflog r
where not exists (
    select 1 from git_repository_lifecycle existing
    where existing.repository_name = r.repository_name
)
group by r.repository_name;

alter table git_repository_lock add constraint fk_repository_lock_lifecycle
    foreign key (repository_name)
    references git_repository_lifecycle (repository_name)
    on delete cascade;

alter table git_packs add constraint fk_pack_repository_lifecycle
    foreign key (repository_name)
    references git_repository_lifecycle (repository_name)
    on delete cascade;
