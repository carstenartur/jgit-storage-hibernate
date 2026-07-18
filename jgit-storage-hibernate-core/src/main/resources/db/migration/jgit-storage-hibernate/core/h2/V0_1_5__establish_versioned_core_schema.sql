-- First migration owned and published with the 0.1.5 artifact line.
-- The physical schema is intentionally unchanged from 0.1.4.
comment on table git_packs is
    'Hibernate-backed JGit pack, index and reftable storage; managed by jgit-storage-hibernate-core';
comment on table git_reflog is
    'Queryable JGit reflog storage; managed by jgit-storage-hibernate-core';
