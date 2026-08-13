-- Append-only repository authorization audit introduced in 0.11.1.

create table git_security_access_audit (
    audit_id varchar(64) not null,
    occurred_at timestamp(6) with time zone not null,
    principal_id varchar(128) not null,
    authentication_method varchar(256) not null,
    session_id varchar(256) not null,
    correlation_id varchar(256) not null,
    repository_name varchar(255) not null,
    operation_name varchar(32) not null,
    ref_name varchar(1024),
    old_object_id varchar(64),
    new_object_id varchar(64),
    outcome_name varchar(16) not null,
    reason_code varchar(128) not null,
    evidence_id varchar(256),
    policy_version bigint not null,
    failure_type varchar(256),
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
