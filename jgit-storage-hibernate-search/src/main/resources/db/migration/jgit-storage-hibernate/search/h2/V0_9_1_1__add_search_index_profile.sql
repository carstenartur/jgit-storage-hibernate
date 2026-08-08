-- Persist the semantic Search indexing profile; existing rows used the CONTENT behavior.

alter table git_commit_index
    add column index_profile varchar(32);

update git_commit_index
set index_profile = 'content-v1'
where index_profile is null;

alter table git_commit_index
    alter column index_profile set not null;
