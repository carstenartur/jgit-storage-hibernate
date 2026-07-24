-- Normalize the exact pre-library Taxonomy VARCHAR(255) columns to the released Core contract.
-- Run LegacyCoreSchemaAdoption.requireSafeToAdopt first. In particular, the preflight rejects
-- pack_extension values longer than 32 before this migration performs any DDL.
--
-- This is deliberately a follow-up to the released 0.1.8 V1 migration so its Flyway checksum
-- remains stable for databases that already recorded adoption version 1.

alter table git_packs
    alter column pack_extension set data type varchar(32);

alter table git_reflog
    alter column ref_name set data type varchar(1024);
