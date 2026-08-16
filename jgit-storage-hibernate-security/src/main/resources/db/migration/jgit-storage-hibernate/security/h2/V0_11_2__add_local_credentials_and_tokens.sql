-- Optional local password and one-way access-token persistence.

create table git_security_local_credential (
    principal_id varchar(128) not null,
    password_algorithm varchar(64) not null,
    password_version integer not null,
    password_hash varchar(2048) not null,
    changed_at timestamp(6) with time zone not null,
    failed_attempt_count integer not null,
    locked_until timestamp(6) with time zone,
    entity_version bigint not null,
    security_version bigint not null,
    primary key (principal_id),
    constraint fk_git_sec_local_credential_principal foreign key (principal_id)
        references git_security_principal (principal_id)
);

create index idx_git_sec_local_credential_locked
    on git_security_local_credential (locked_until);

create table git_security_access_token (
    token_id varchar(128) not null,
    principal_id varchar(128) not null,
    token_prefix varchar(64) not null,
    token_algorithm varchar(64) not null,
    token_version integer not null,
    token_hash varchar(512) not null,
    permission_scopes varchar(512) not null,
    issued_at timestamp(6) with time zone not null,
    expires_at timestamp(6) with time zone,
    last_used_at timestamp(6) with time zone,
    revoked_at timestamp(6) with time zone,
    issued_by varchar(128) not null,
    entity_version bigint not null,
    security_version bigint not null,
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
    audit_id varchar(64) not null,
    occurred_at timestamp(6) with time zone not null,
    operation_name varchar(32) not null,
    outcome_name varchar(16) not null,
    actor_principal_id varchar(128),
    subject_principal_id varchar(128),
    authentication_method varchar(256) not null,
    session_id varchar(256) not null,
    correlation_id varchar(256) not null,
    remote_address_hash varchar(64),
    credential_kind varchar(32) not null,
    credential_id varchar(128),
    reason_code varchar(128) not null,
    failure_type varchar(256),
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
