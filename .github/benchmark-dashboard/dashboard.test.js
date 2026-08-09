'use strict';

const assert = require('node:assert/strict');
const dashboard = require('./dashboard.js');

assert.equal(
  dashboard.operationAnchor('Hibernate Search full-text query'),
  'benchmark-hibernate-search-full-text-query'
);
assert.equal(
  dashboard.operationAnchor('Hibernate Search runtime burst ready p95'),
  'benchmark-hibernate-search-runtime-burst-ready-p-95'
);
assert.deepEqual(
  dashboard.splitBenchmarkName('Read object — JGit + PostgreSQL'),
  { operation: 'Read object', backend: 'JGit + PostgreSQL' }
);

const points = [
  { bench: { value: 1.25, consumers: ['audio-analyzer'] } },
  { bench: { value: 2.5, consumers: ['taxonomy'] } },
  { bench: { value: 0 } },
  null,
];
assert.deepEqual(
  dashboard.filterPointValues(points, new Set(['audio-analyzer'])),
  [1.25, null, null, null]
);
assert.deepEqual(
  dashboard.filterPointValues(points, new Set([dashboard.UNCLASSIFIED])),
  [null, null, 0, null]
);
assert.equal(
  dashboard.matchesConsumerSelection(
    { consumers: ['audio-analyzer', 'taxonomy'] },
    new Set(['taxonomy'])
  ),
  true
);
assert.equal(
  dashboard.matchesConsumerSelection(
    { consumers: ['audio-analyzer'] },
    new Set(['sandbox'])
  ),
  false
);
assert.equal(
  dashboard.matchesConsumerSelection({}, new Set([dashboard.UNCLASSIFIED])),
  true
);

const data = {
  consumerCatalog: {
    taxonomy: { displayName: 'Taxonomy' },
    'audio-analyzer': { displayName: 'audio-analyzer' },
  },
  entries: {
    core: [{ benches: [{ consumers: ['taxonomy'] }, {}] }],
  },
};
assert.deepEqual(
  dashboard.collectConsumerFilterIds(data),
  ['audio-analyzer', 'taxonomy', dashboard.UNCLASSIFIED]
);
assert.equal(
  dashboard.relevanceLabel(
    { consumers: ['taxonomy', 'audio-analyzer'] },
    data.consumerCatalog
  ),
  'Taxonomy, audio-analyzer'
);
assert.equal(dashboard.relevanceLabel({}, data.consumerCatalog), 'Unclassified');

const fullData = {
  repoUrl: 'https://github.com/carstenartur/jgit-storage-hibernate',
  consumerCatalog: data.consumerCatalog,
  entries: {
    suite: [
      {
        date: 10,
        commit: { id: 'a'.repeat(40) },
        benches: [
          { name: 'query', value: 1.25, consumers: ['audio-analyzer'] },
          { name: 'query', value: 2.5, consumers: ['taxonomy'] },
          { name: 'legacy', value: 7 },
        ],
      },
    ],
  },
};
const filtered = dashboard.filterBenchmarkData(fullData, new Set(['taxonomy']));
assert.deepEqual(filtered.entries.suite[0].benches.map(bench => bench.value), [2.5]);
assert.deepEqual(fullData.entries.suite[0].benches.map(bench => bench.value), [1.25, 2.5, 7]);
assert.equal(
  dashboard.coreScriptUrl(fullData),
  'https://cdn.jsdelivr.net/gh/carstenartur/jgit-storage-hibernate@'
    + 'a'.repeat(40)
    + '/.github/benchmark-dashboard/dashboard-core.js'
);

console.log('dashboard consumer relevance tests passed');
