-- Repository-scoped row locked during ref mutations from independent persistence contexts.
-- OBJECT_ID supports schemas that already received the current mapping during controlled adoption.
if object_id(N'git_repository_lock', N'U') is null
begin
    create table git_repository_lock (
        repository_name nvarchar(255) not null,
        created_at datetimeoffset(7) not null,
        constraint pk_git_repository_lock primary key (repository_name)
    );
end;
