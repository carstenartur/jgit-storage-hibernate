-- Repository-scoped row locked during ref mutations from independent persistence contexts.
-- Hibernate's HSQLDialect maps Instant/TIMESTAMP_UTC to timestamp without a zone suffix.
create table git_repository_lock (
    repository_name varchar(255) not null,
    created_at timestamp(6) not null,
    primary key (repository_name)
);
