-- Writer identity and lease prevent cleanup from deleting a slow but active uncommitted pack.
alter table git_packs add column write_token varchar(36);
alter table git_packs add column write_lease_until timestamp(6);
create index idx_pack_repo_lease
    on git_packs (repository_name, committed, write_lease_until);
