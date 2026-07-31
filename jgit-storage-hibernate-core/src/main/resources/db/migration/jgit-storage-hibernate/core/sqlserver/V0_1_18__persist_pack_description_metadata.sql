-- Preserve JGit's logical pack ordering and maintenance metadata across repository reopen.
alter table git_packs add pack_source varchar(32) null;
alter table git_packs add last_modified bigint null;
alter table git_packs add object_count bigint null;
alter table git_packs add delta_count bigint null;
alter table git_packs add index_version integer null;
alter table git_packs add min_update_index bigint null;
alter table git_packs add max_update_index bigint null;

-- Separate durable repository existence from the row used for short publication locks.
if object_id(N'git_repository_lifecycle', N'U') is null
begin
    create table git_repository_lifecycle (
        repository_name nvarchar(255) not null,
        created_at datetimeoffset(7) not null,
        constraint pk_git_repository_lifecycle primary key (repository_name)
    );
end;

insert into git_repository_lifecycle (repository_name, created_at)
select l.repository_name, min(l.created_at)
from git_repository_lock l
where not exists (
    select 1 from git_repository_lifecycle existing
    where existing.repository_name = l.repository_name
)
group by l.repository_name;

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

if not exists (
    select 1 from sys.foreign_keys
    where name = N'fk_repository_lock_lifecycle'
      and parent_object_id = object_id(N'git_repository_lock')
)
begin
    alter table git_repository_lock with check add constraint fk_repository_lock_lifecycle
        foreign key (repository_name)
        references git_repository_lifecycle (repository_name)
        on delete cascade;
    alter table git_repository_lock check constraint fk_repository_lock_lifecycle;
end;

if not exists (
    select 1 from sys.foreign_keys
    where name = N'fk_pack_repository_lifecycle'
      and parent_object_id = object_id(N'git_packs')
)
begin
    alter table git_packs with check add constraint fk_pack_repository_lifecycle
        foreign key (repository_name)
        references git_repository_lifecycle (repository_name)
        on delete cascade;
    alter table git_packs check constraint fk_pack_repository_lifecycle;
end;
