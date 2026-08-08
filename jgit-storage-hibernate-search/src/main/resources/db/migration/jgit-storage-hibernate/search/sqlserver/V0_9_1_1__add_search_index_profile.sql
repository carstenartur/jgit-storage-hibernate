-- Persist the semantic Search indexing profile; existing rows used the CONTENT behavior.

alter table git_commit_index
    add index_profile nvarchar(32) null;

update git_commit_index
set index_profile = 'content-v1'
where index_profile is null;

alter table git_commit_index
    alter column index_profile nvarchar(32) not null;
