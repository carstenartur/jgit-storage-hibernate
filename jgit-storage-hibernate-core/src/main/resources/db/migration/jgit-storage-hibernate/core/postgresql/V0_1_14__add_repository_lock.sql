-- Repository-scoped row locked during ref mutations from independent persistence contexts.
create table git_repository_lock (
    repository_name varchar(255) not null,
    created_at timestamp(6) with time zone not null,
    primary key (repository_name)
);

comment on table git_repository_lock is
    'Repository-scoped coordination row for cross-SessionFactory JGit ref updates';
