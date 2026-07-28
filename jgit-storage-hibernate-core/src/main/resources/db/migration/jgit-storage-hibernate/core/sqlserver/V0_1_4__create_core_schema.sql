-- Schema produced by jgit-storage-hibernate-core 0.1.4 for Microsoft SQL Server.
-- Reftable files are stored in git_packs with pack_extension = 'REFTABLE'.

create table git_packs (
    id bigint identity(1,1) not null,
    repository_name nvarchar(255) not null,
    pack_name nvarchar(255) not null,
    pack_extension varchar(32) not null,
    data varbinary(max) not null,
    file_size bigint not null,
    committed bit not null,
    created_at datetimeoffset(7) not null,
    committed_at datetimeoffset(7) null,
    constraint pk_git_packs primary key (id),
    constraint uk_pack_repo_name_ext unique (repository_name, pack_name, pack_extension)
);

create index idx_pack_repo on git_packs (repository_name);
create index idx_pack_repo_name on git_packs (repository_name, pack_name);
create index idx_pack_repo_committed on git_packs (repository_name, committed);

create table git_reflog (
    id bigint identity(1,1) not null,
    version bigint null,
    repository_name nvarchar(255) not null,
    ref_name nvarchar(1024) not null,
    old_id varchar(40) null,
    new_id varchar(40) null,
    who_name nvarchar(255) null,
    who_email nvarchar(255) null,
    who_when datetimeoffset(7) not null,
    message nvarchar(2000) null,
    constraint pk_git_reflog primary key (id)
);

create index idx_reflog_repo on git_reflog (repository_name);
-- SQL Server cannot key an index with nvarchar(255) + nvarchar(1024) within 1700 bytes.
-- Keep the full values and cover ref_name without truncating the entity contract.
create index idx_reflog_repo_ref on git_reflog (repository_name) include (ref_name);
