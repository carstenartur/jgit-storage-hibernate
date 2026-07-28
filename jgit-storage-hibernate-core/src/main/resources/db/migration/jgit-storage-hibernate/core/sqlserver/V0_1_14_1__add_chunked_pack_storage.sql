-- Preserve existing inline pack payloads while allowing new payloads to be stored in bounded chunks.
alter table git_packs alter column data varbinary(max) null;

create table git_pack_chunks (
    id bigint identity(1,1) not null,
    pack_id bigint not null,
    chunk_index integer not null,
    chunk_data varbinary(max) not null,
    chunk_size integer not null,
    constraint pk_git_pack_chunks primary key (id),
    constraint uk_pack_chunk_index unique (pack_id, chunk_index),
    constraint fk_pack_chunk_pack foreign key (pack_id) references git_packs(id) on delete cascade
);

create index idx_pack_chunk_pack on git_pack_chunks (pack_id);
