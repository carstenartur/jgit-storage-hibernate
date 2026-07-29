-- Generic search projection schema produced by jgit-storage-hibernate-search 0.1.4.

create table git_commit_index (
    id bigint identity(1,1) not null,
    repository_name nvarchar(255) not null,
    object_id varchar(40) not null,
    short_message nvarchar(2048) null,
    full_message nvarchar(max) null,
    author_name nvarchar(255) null,
    author_email nvarchar(255) null,
    commit_time datetimeoffset(7) null,
    changed_paths nvarchar(max) null,
    changed_text nvarchar(max) null,
    constraint pk_git_commit_index primary key (id),
    constraint uk_commit_repo_object unique (repository_name, object_id)
);

create index idx_commit_repo on git_commit_index (repository_name);
create index idx_commit_repo_time on git_commit_index (repository_name, commit_time);
create index idx_commit_repo_author on git_commit_index (repository_name, author_email);
