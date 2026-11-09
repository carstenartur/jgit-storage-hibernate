import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PGlite } from '@electric-sql/pglite';

const [migration, jdk = 'java'] = process.argv.slice(2);
const dir = dirname(fileURLToPath(import.meta.url));
const file = join(dir, 'rows.copy');
const db = await PGlite.create();
const copy = "COPY bulk_spike.git_pack_chunks (pack_id, chunk_index, chunk_data, chunk_size)"
  + " FROM '/dev/blob' WITH (FORMAT binary)";
const bytes = 2 * 1024 * 1024 + 257;

try {
  console.log((await db.query("SELECT version()")).rows[0].version);
  await db.exec("CREATE SCHEMA bulk_spike; SET search_path TO bulk_spike");
  for (const name of ['V0_1_4__create_core_schema.sql', 'V0_1_14_1__add_chunked_pack_storage.sql']) {
    await db.exec(await readFile(join(migration, name), 'utf8'));
  }
  await db.exec("SET search_path TO public");

  for (const [mode, expectedCode] of [
    ['success', undefined], ['truncated', '22P04'], ['duplicate', '23505'],
    ['foreign-key', '23503'], ['rollback', 'spike'], ['retry', undefined],
  ]) {
    let parent;
    let expectedHash;
    try {
      await db.transaction(async tx => {
        const res = await tx.query(
          "INSERT INTO bulk_spike.git_packs"
          + " (repository_name, pack_name, pack_extension, file_size, committed, created_at)"
          + " VALUES ('scratch', $1, 'PACK', $2, true, current_timestamp) RETURNING id",
          [mode, bytes],
        );
        parent = res.rows[0].id;
        expectedHash = execFileSync(jdk, [
          '-cp', dir, 'CopyBinaryEncoder', file,
          mode === 'foreign-key' ? '9223372036854775807' : String(parent), mode,
        ], { encoding: 'utf8' }).trim();
        const result = await tx.query(copy, [], { blob: new Blob([await readFile(file)]) });
        assert.equal(result.affectedRows, 3);
        if (mode === 'rollback') {
          throw Object.assign(new Error('injected rollback'), { code: 'spike' });
        }
      });
      assert.equal(expectedCode, undefined, mode + ' unexpectedly committed');
    } catch (e) {
      if (!expectedCode || e.code !== expectedCode) throw e;
    }
    const data = (await db.query(
      "SELECT count(*) AS count, sum(octet_length(chunk_data)) AS size,"
      + " array_agg(chunk_size ORDER BY chunk_index) AS chunk_sizes,"
      + " bool_and(id IS NOT NULL) AS ids_generated,"
      + " encode(sha256(string_agg(chunk_data, ''::bytea ORDER BY chunk_index)), 'hex') AS hash"
      + " FROM bulk_spike.git_pack_chunks WHERE pack_id = $1", [parent],
    )).rows[0];
    const parentCount = (await db.query("SELECT count(*) AS count FROM bulk_spike.git_packs WHERE id = $1", [parent])).rows[0].count;
    if (expectedCode) {
      assert.equal(Number(parentCount), 0);
      assert.equal(Number(data.count), 0);
    } else {
      assert.equal(Number(parentCount), 1);
      assert.equal(Number(data.count), 3);
      assert.equal(Number(data.size), bytes);
      assert.deepEqual(data.chunk_sizes, [1048576, 1048576, 257]);
      assert.equal(data.ids_generated, true);
      assert.equal(data.hash, expectedHash);
    }
    console.log('PASS ' + mode + (expectedCode ? ' rolled back rows' : ' exact bytea, generated IDs and SHA-256'));
  }
} finally {
  await db.close();
}
