-- Repository-scoped row locked during ref mutations from independent persistence contexts.
-- IF NOT EXISTS supports schemas that already received the current mapping during a controlled adoption.
create table if not exists git_repository_lock (
    repository_name varchar(255) not null,
    created_at timestamp(6) with time zone not null,
    primary key (repository_name)
);

comment on table git_repository_lock is
    'Repository-scoped coordination row for cross-SessionFactory JGit ref updates';
