-- First migration owned and published with the 0.1.5 artifact line.
-- The physical schema is intentionally unchanged from 0.1.4.
comment on table git_commit_index is
    'Rebuildable generic Git commit search projection; managed by jgit-storage-hibernate-search';
