-- Add an application-assigned ORM/Search identifier while retaining the historical identity column.

alter table git_commit_index
    add projection_key varchar(36) null;

go

update git_commit_index
set projection_key = concat('legacy-', id)
where projection_key is null;

go

alter table git_commit_index
    alter column projection_key varchar(36) not null;

go

alter table git_commit_index
    add constraint uk_commit_projection_key unique (projection_key);

go
