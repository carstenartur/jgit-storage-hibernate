-- Keep reverse reflog lookup selective without relying on a 1,024-character index key.

alter table git_reflog
    add column ref_name_key varchar(128);

update git_reflog
set ref_name_key = substring(ref_name, 1, 128)
where ref_name_key is null;

alter table git_reflog
    alter column ref_name_key set not null;

drop index if exists idx_reflog_repo_ref_id;
drop index if exists idx_reflog_repo_ref_key_id;
create index idx_reflog_repo_ref_key_id
    on git_reflog (repository_name, ref_name_key, id desc);
