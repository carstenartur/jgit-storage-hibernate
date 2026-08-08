-- Add an application-assigned ORM/Search identifier while retaining the historical identity column.

alter table git_commit_index
    add column projection_key varchar(36);

update git_commit_index
set projection_key = concat('legacy-', cast(id as varchar(29)))
where projection_key is null;

alter table git_commit_index
    alter column projection_key set not null;

alter table git_commit_index
    add constraint uk_commit_projection_key unique (projection_key);
