-- Versioned desired-state synchronization for application-managed repository authorization.

alter table git_security_repository_grant add column managed_source_id varchar(128);
alter table git_security_repository_grant add column managed_source_instance_id varchar(128);
alter table git_security_repository_grant add column managed_entry_key varchar(256);
alter table git_security_repository_grant add column managed_policy_version bigint;
alter table git_security_repository_grant add constraint ck_git_sec_grant_managed_tuple check (
    (managed_source_id is null
        and managed_source_instance_id is null
        and managed_entry_key is null
        and managed_policy_version is null)
    or (managed_source_id is not null
        and managed_source_instance_id is not null
        and managed_entry_key is not null
        and managed_policy_version > 0)
);
create unique index uk_git_sec_managed_grant_entry
    on git_security_repository_grant (
        repository_name, managed_source_id, managed_source_instance_id, managed_entry_key
    );

alter table git_security_ref_rule add column managed_source_id varchar(128);
alter table git_security_ref_rule add column managed_source_instance_id varchar(128);
alter table git_security_ref_rule add column managed_entry_key varchar(256);
alter table git_security_ref_rule add column managed_policy_version bigint;
alter table git_security_ref_rule add constraint ck_git_sec_ref_rule_managed_tuple check (
    (managed_source_id is null
        and managed_source_instance_id is null
        and managed_entry_key is null
        and managed_policy_version is null)
    or (managed_source_id is not null
        and managed_source_instance_id is not null
        and managed_entry_key is not null
        and managed_policy_version > 0)
);
create unique index uk_git_sec_managed_ref_rule_entry
    on git_security_ref_rule (
        repository_name, managed_source_id, managed_source_instance_id, managed_entry_key
    );

create table git_security_managed_policy (
    policy_id varchar(128) not null,
    repository_name varchar(255) not null,
    managed_source_id varchar(128) not null,
    managed_source_instance_id varchar(128) not null,
    ownership_mode varchar(32) not null,
    policy_version bigint not null,
    content_digest varchar(64) not null,
    policy_generation bigint not null,
    created_at timestamp(6) not null,
    created_by_principal_id varchar(128) not null,
    updated_at timestamp(6) not null,
    updated_by_principal_id varchar(128) not null,
    last_operation_id varchar(256) not null,
    last_correlation_id varchar(256) not null,
    entity_version bigint not null,
    primary key (policy_id),
    constraint uk_git_sec_managed_policy_source unique (
        repository_name, managed_source_id, managed_source_instance_id
    ),
    constraint ck_git_sec_managed_policy_version check (policy_version > 0),
    constraint ck_git_sec_managed_policy_generation check (policy_generation > 0)
);

create index idx_git_sec_managed_policy_repository
    on git_security_managed_policy (repository_name, policy_generation);
