-- Writer identity and lease prevent cleanup from deleting a slow but active uncommitted pack.
alter table git_packs add write_token varchar(36) null;
alter table git_packs add write_lease_until datetimeoffset(7) null;
create index idx_pack_repo_lease
    on git_packs (repository_name, committed, write_lease_until);
