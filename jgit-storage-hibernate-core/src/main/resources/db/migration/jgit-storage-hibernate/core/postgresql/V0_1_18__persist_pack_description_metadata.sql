-- Preserve JGit's logical pack ordering and maintenance metadata across repository reopen.
alter table git_packs add column pack_source varchar(32);
alter table git_packs add column last_modified bigint;
alter table git_packs add column object_count bigint;
alter table git_packs add column delta_count bigint;
alter table git_packs add column index_version integer;
alter table git_packs add column min_update_index bigint;
alter table git_packs add column max_update_index bigint;
