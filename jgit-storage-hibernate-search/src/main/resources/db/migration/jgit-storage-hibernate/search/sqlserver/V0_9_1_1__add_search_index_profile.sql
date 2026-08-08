-- Persist the semantic Search indexing profile; existing rows used the CONTENT behavior.
-- SQL Server compiles a migration batch before executing it, so add the non-null column with a
-- temporary default instead of referring to the newly added column in a following UPDATE statement.

alter table git_commit_index
    add index_profile nvarchar(32) not null
        constraint df_git_commit_index_profile default 'content-v1' with values;

alter table git_commit_index
    drop constraint df_git_commit_index_profile;
