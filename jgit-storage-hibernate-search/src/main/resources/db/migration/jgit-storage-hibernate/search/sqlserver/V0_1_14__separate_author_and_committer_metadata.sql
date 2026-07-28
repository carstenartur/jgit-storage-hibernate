-- Separate the original author identity/time from the committer identity/time.
-- Existing commit_time values were author times before 0.1.14, so preserve them as author_time.
-- Reindex projections to populate authoritative committer metadata.

alter table git_commit_index add author_time datetimeoffset(7) null;
alter table git_commit_index add committer_name nvarchar(255) null;
alter table git_commit_index add committer_email nvarchar(255) null;
GO

update git_commit_index set author_time = commit_time where author_time is null;
GO

create index idx_commit_repo_author_time
    on git_commit_index (repository_name, author_time);
create index idx_commit_repo_committer
    on git_commit_index (repository_name, committer_email);
