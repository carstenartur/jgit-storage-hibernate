-- Preserve JGit's logical pack ordering and maintenance metadata across repository reopen.
alter table git_packs add pack_source varchar(32) null;
alter table git_packs add last_modified bigint null;
alter table git_packs add object_count bigint null;
alter table git_packs add delta_count bigint null;
alter table git_packs add index_version integer null;
alter table git_packs add min_update_index bigint null;
alter table git_packs add max_update_index bigint null;
