-- Fresh Oracle Free 23+ installation; separate from the published vendor streams.
-- Security capability schema introduced in 0.11.0.

create table git_security_principal (
    principal_id varchar2(128 char) not null,
    principal_type varchar2(32 char) not null,
    login_name varchar2(256 char),
    display_name varchar2(256 char),
    external_issuer varchar2(512 char),
    external_subject varchar2(512 char),
    status varchar2(32 char) not null,
    created_at timestamp(9) with time zone not null,
    updated_at timestamp(9) with time zone not null,
    entity_version number(19,0) not null,
    security_version number(19,0) not null,
    primary key (principal_id),
    constraint uk_git_sec_principal_login unique (login_name),
    constraint uk_git_sec_principal_external unique (external_issuer, external_subject),
    constraint ck_git_sec_principal_external_pair check (
        (external_issuer is null and external_subject is null)
        or (external_issuer is not null and external_subject is not null)
    )
);

create table git_security_group (
    group_id varchar2(128 char) not null,
    group_name varchar2(256 char) not null,
    description varchar2(1024 char),
    status varchar2(32 char) not null,
    created_at timestamp(9) with time zone not null,
    updated_at timestamp(9) with time zone not null,
    entity_version number(19,0) not null,
    security_version number(19,0) not null,
    primary key (group_id),
    constraint uk_git_sec_group_name unique (group_name)
);

create table git_security_repository_grant (
    grant_id varchar2(128 char) not null,
    repository_name varchar2(255 char) not null,
    subject_type varchar2(32 char) not null,
    subject_id varchar2(128 char) not null,
    permission_name varchar2(32 char) not null,
    effect_name varchar2(16 char) not null,
    created_at timestamp(9) with time zone not null,
    created_by varchar2(128 char) not null,
    entity_version number(19,0) not null,
    security_version number(19,0) not null,
    managed_source_id varchar2(128 char),
    managed_source_instance_id varchar2(128 char),
    managed_entry_key varchar2(256 char),
    managed_policy_version number(19,0),
    primary key (grant_id),
    constraint uk_git_sec_repository_grant unique (
        repository_name, subject_type, subject_id, permission_name, effect_name
    )
);

create index idx_git_sec_grant_subject
    on git_security_repository_grant (subject_type, subject_id, repository_name);
create index idx_git_sec_grant_repository
    on git_security_repository_grant (repository_name, permission_name);

create table git_security_ref_rule (
    rule_id varchar2(128 char) not null,
    repository_name varchar2(255 char) not null,
    ref_pattern varchar2(1024 char) not null,
    permission_name varchar2(32 char) not null,
    effect_name varchar2(16 char) not null,
    priority number(10,0) not null,
    subject_type varchar2(32 char),
    subject_id varchar2(128 char),
    enabled boolean not null,
    created_at timestamp(9) with time zone not null,
    created_by varchar2(128 char) not null,
    entity_version number(19,0) not null,
    security_version number(19,0) not null,
    managed_source_id varchar2(128 char),
    managed_source_instance_id varchar2(128 char),
    managed_entry_key varchar2(256 char),
    managed_policy_version number(19,0),
    primary key (rule_id),
    constraint uk_git_sec_ref_rule unique (repository_name, rule_id),
    constraint ck_git_sec_ref_rule_subject_pair check (
        (subject_type is null and subject_id is null)
        or (subject_type is not null and subject_id is not null)
    )
);

create index idx_git_sec_ref_rule_repository
    on git_security_ref_rule (repository_name, permission_name, priority);
create index idx_git_sec_ref_rule_subject
    on git_security_ref_rule (subject_type, subject_id);

create table git_security_version (
    scope_key varchar2(512 char) not null,
    version_value number(19,0) not null,
    entity_version number(19,0) not null,
    primary key (scope_key)
);

create table git_security_group_member (
    membership_id varchar2(128 char) not null,
    group_id varchar2(128 char) not null,
    principal_id varchar2(128 char) not null,
    created_at timestamp(9) with time zone not null,
    created_by varchar2(128 char) not null,
    security_version number(19,0) not null,
    primary key (membership_id),
    constraint uk_git_sec_group_member unique (group_id, principal_id),
    constraint fk_git_sec_member_group foreign key (group_id)
        references git_security_group (group_id),
    constraint fk_git_sec_member_principal foreign key (principal_id)
        references git_security_principal (principal_id)
);

create index idx_git_sec_member_principal
    on git_security_group_member (principal_id);

create index idx_git_sec_member_group
    on git_security_group_member (group_id);


-- Append-only repository authorization audit introduced in 0.11.1.

create table git_security_access_audit (
    audit_id varchar2(64 char) not null,
    occurred_at timestamp(9) with time zone not null,
    principal_id varchar2(128 char) not null,
    authentication_method varchar2(256 char) not null,
    session_id varchar2(256 char) not null,
    correlation_id varchar2(256 char) not null,
    repository_name varchar2(255 char) not null,
    operation_name varchar2(32 char) not null,
    ref_name varchar2(1024 char),
    old_object_id varchar2(64 char),
    new_object_id varchar2(64 char),
    outcome_name varchar2(16 char) not null,
    reason_code varchar2(128 char) not null,
    evidence_id varchar2(256 char),
    policy_version number(19,0) not null,
    failure_type varchar2(256 char),
    primary key (audit_id),
    constraint ck_git_sec_audit_policy_version check (policy_version >= 0),
    constraint ck_git_sec_audit_failure check (
        (outcome_name = 'FAILED' and failure_type is not null)
        or (outcome_name <> 'FAILED' and failure_type is null)
    )
);

create index idx_git_sec_audit_repository
    on git_security_access_audit (repository_name, occurred_at);
create index idx_git_sec_audit_principal
    on git_security_access_audit (principal_id, occurred_at);
create index idx_git_sec_audit_correlation
    on git_security_access_audit (correlation_id, occurred_at);
create index idx_git_sec_audit_outcome
    on git_security_access_audit (outcome_name, occurred_at);


-- Optional local password and one-way access-token persistence.

create table git_security_local_credential (
    principal_id varchar2(128 char) not null,
    password_algorithm varchar2(64 char) not null,
    password_version number(10,0) not null,
    password_hash varchar2(2048 char) not null,
    changed_at timestamp(9) with time zone not null,
    failed_attempt_count number(10,0) not null,
    locked_until timestamp(9) with time zone,
    entity_version number(19,0) not null,
    security_version number(19,0) not null,
    primary key (principal_id),
    constraint fk_git_sec_local_credential_principal foreign key (principal_id)
        references git_security_principal (principal_id)
);

create index idx_git_sec_local_credential_locked
    on git_security_local_credential (locked_until);

create table git_security_access_token (
    token_id varchar2(128 char) not null,
    principal_id varchar2(128 char) not null,
    token_prefix varchar2(64 char) not null,
    token_algorithm varchar2(64 char) not null,
    token_version number(10,0) not null,
    token_hash varchar2(512 char) not null,
    permission_scopes varchar2(512 char) not null,
    issued_at timestamp(9) with time zone not null,
    expires_at timestamp(9) with time zone,
    last_used_at timestamp(9) with time zone,
    revoked_at timestamp(9) with time zone,
    issued_by varchar2(128 char) not null,
    entity_version number(19,0) not null,
    security_version number(19,0) not null,
    primary key (token_id),
    constraint uk_git_sec_access_token_prefix unique (token_prefix),
    constraint fk_git_sec_access_token_principal foreign key (principal_id)
        references git_security_principal (principal_id)
);

create index idx_git_sec_access_token_principal
    on git_security_access_token (principal_id, issued_at);
create index idx_git_sec_access_token_expiry
    on git_security_access_token (expires_at, revoked_at);

create table git_security_identity_audit (
    audit_id varchar2(64 char) not null,
    occurred_at timestamp(9) with time zone not null,
    operation_name varchar2(32 char) not null,
    outcome_name varchar2(16 char) not null,
    actor_principal_id varchar2(128 char),
    subject_principal_id varchar2(128 char),
    authentication_method varchar2(256 char) not null,
    session_id varchar2(256 char) not null,
    correlation_id varchar2(256 char) not null,
    remote_address_hash varchar2(64 char),
    credential_kind varchar2(32 char) not null,
    credential_id varchar2(128 char),
    reason_code varchar2(128 char) not null,
    failure_type varchar2(256 char),
    primary key (audit_id)
);

create index idx_git_sec_identity_audit_subject
    on git_security_identity_audit (subject_principal_id, occurred_at);
create index idx_git_sec_identity_audit_actor
    on git_security_identity_audit (actor_principal_id, occurred_at);
create index idx_git_sec_identity_audit_correlation
    on git_security_identity_audit (correlation_id, occurred_at);
create index idx_git_sec_identity_audit_credential
    on git_security_identity_audit (credential_id, occurred_at);
create index idx_git_sec_identity_audit_operation
    on git_security_identity_audit (operation_name, outcome_name, occurred_at);


-- Versioned desired-state synchronization for application-managed repository authorization.

alter table git_security_repository_grant add constraint ck_git_sec_grant_managed_tuple check (
    (managed_source_id is null
        and managed_source_instance_id is null
        and managed_entry_key is null
        and managed_policy_version is null)
    or (managed_source_id is not null
        and managed_source_instance_id is not null
        and managed_entry_key is not null
        and managed_policy_version is not null
        and managed_policy_version > 0)
);
create unique index uk_git_sec_managed_grant_entry
    on git_security_repository_grant (
        case when managed_source_id is not null then repository_name end,
        case when managed_source_id is not null then managed_source_id end,
        case when managed_source_id is not null then managed_source_instance_id end,
        case when managed_source_id is not null then managed_entry_key end
    );

alter table git_security_ref_rule add constraint ck_git_sec_ref_rule_managed_tuple check (
    (managed_source_id is null
        and managed_source_instance_id is null
        and managed_entry_key is null
        and managed_policy_version is null)
    or (managed_source_id is not null
        and managed_source_instance_id is not null
        and managed_entry_key is not null
        and managed_policy_version is not null
        and managed_policy_version > 0)
);
create unique index uk_git_sec_managed_ref_rule_entry
    on git_security_ref_rule (
        case when managed_source_id is not null then repository_name end,
        case when managed_source_id is not null then managed_source_id end,
        case when managed_source_id is not null then managed_source_instance_id end,
        case when managed_source_id is not null then managed_entry_key end
    );

create table git_security_managed_policy (
    policy_id varchar2(128 char) not null,
    repository_name varchar2(255 char) not null,
    managed_source_id varchar2(128 char) not null,
    managed_source_instance_id varchar2(128 char) not null,
    ownership_mode varchar2(32 char) not null,
    policy_version number(19,0) not null,
    content_digest varchar2(64 char) not null,
    policy_generation number(19,0) not null,
    created_at timestamp(9) with time zone not null,
    created_by_principal_id varchar2(128 char) not null,
    updated_at timestamp(9) with time zone not null,
    updated_by_principal_id varchar2(128 char) not null,
    last_operation_id varchar2(256 char) not null,
    last_correlation_id varchar2(256 char) not null,
    entity_version number(19,0) not null,
    primary key (policy_id),
    constraint uk_git_sec_managed_policy_source unique (
        repository_name, managed_source_id, managed_source_instance_id
    ),
    constraint ck_git_sec_managed_policy_version check (policy_version > 0),
    constraint ck_git_sec_managed_policy_generation check (policy_generation > 0)
);

create index idx_git_sec_managed_policy_repository
    on git_security_managed_policy (repository_name, policy_generation);

