-- Security capability schema introduced in 0.11.0.

create table git_security_principal (
    principal_id varchar(128) not null,
    principal_type varchar(32) not null,
    login_name varchar(256),
    display_name varchar(256),
    external_issuer varchar(512),
    external_subject varchar(512),
    status varchar(32) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    entity_version bigint not null,
    security_version bigint not null,
    primary key (principal_id),
    constraint uk_git_sec_principal_login unique (login_name),
    constraint uk_git_sec_principal_external unique (external_issuer, external_subject),
    constraint ck_git_sec_principal_external_pair check (
        (external_issuer is null and external_subject is null)
        or (external_issuer is not null and external_subject is not null)
    )
);

create table git_security_group (
    group_id varchar(128) not null,
    group_name varchar(256) not null,
    description varchar(1024),
    status varchar(32) not null,
    created_at timestamp(6) not null,
    updated_at timestamp(6) not null,
    entity_version bigint not null,
    security_version bigint not null,
    primary key (group_id),
    constraint uk_git_sec_group_name unique (group_name)
);

create table git_security_repository_grant (
    grant_id varchar(128) not null,
    repository_name varchar(255) not null,
    subject_type varchar(32) not null,
    subject_id varchar(128) not null,
    permission_name varchar(32) not null,
    effect_name varchar(16) not null,
    created_at timestamp(6) not null,
    created_by varchar(128) not null,
    entity_version bigint not null,
    security_version bigint not null,
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
    rule_id varchar(128) not null,
    repository_name varchar(255) not null,
    ref_pattern varchar(1024) not null,
    permission_name varchar(32) not null,
    effect_name varchar(16) not null,
    priority integer not null,
    subject_type varchar(32),
    subject_id varchar(128),
    enabled boolean not null,
    created_at timestamp(6) not null,
    created_by varchar(128) not null,
    entity_version bigint not null,
    security_version bigint not null,
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
    scope_key varchar(512) not null,
    version_value bigint not null,
    entity_version bigint not null,
    primary key (scope_key)
);

create table git_security_group_member (
    membership_id varchar(128) not null,
    group_id varchar(128) not null,
    principal_id varchar(128) not null,
    created_at timestamp(6) not null,
    created_by varchar(128) not null,
    security_version bigint not null,
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
