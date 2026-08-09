'use strict';

((root, factory) => {
  const api = factory();
  if (typeof module === 'object' && module.exports) {
    module.exports = api;
  }
  if (root && root.document) {
    const start = () => api.initialize(root);
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', start, { once: true });
    } else {
      start();
    }
  }
})(typeof window !== 'undefined' ? window : null, () => {
  const NAME_SEPARATOR = ' — ';
  const UNCLASSIFIED = '__unclassified__';
  // The commit-pinned core renderer retains the original Chart.js datasets and anchors.

  function splitBenchmarkName(name) {
    const separatorIndex = name.lastIndexOf(NAME_SEPARATOR);
    if (separatorIndex < 0) {
      return { operation: name, backend: 'Result' };
    }
    return {
      operation: name.slice(0, separatorIndex),
      backend: name.slice(separatorIndex + NAME_SEPARATOR.length),
    };
  }

  function operationAnchor(operation) {
    const slug = operation
      .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
      .replace(/([A-Z]+)([A-Z][a-z])/g, '$1-$2')
      .replace(/([A-Za-z])([0-9])/g, '$1-$2')
      .replace(/([0-9])([A-Za-z])/g, '$1-$2')
      .replace(/[^A-Za-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .toLowerCase();
    return 'benchmark-' + slug;
  }

  function benchmarkRelevanceIds(bench) {
    return Array.isArray(bench && bench.consumers) && bench.consumers.length > 0
      ? [...new Set(bench.consumers.map(String))]
      : [UNCLASSIFIED];
  }

  function matchesConsumerSelection(bench, selectedConsumerIds) {
    const selected = selectedConsumerIds instanceof Set
      ? selectedConsumerIds
      : new Set(selectedConsumerIds || []);
    return benchmarkRelevanceIds(bench).some(consumer => selected.has(consumer));
  }

  function filterPointValues(points, selectedConsumerIds) {
    return points.map(point => point && matchesConsumerSelection(point.bench, selectedConsumerIds)
      ? point.bench.value
      : null);
  }

  function collectConsumerFilterIds(benchmarkData) {
    const ids = new Set(Object.keys(benchmarkData.consumerCatalog || {}));
    let hasUnclassified = false;
    for (const entries of Object.values(benchmarkData.entries || {})) {
      for (const entry of entries || []) {
        for (const bench of entry.benches || []) {
          const relevance = benchmarkRelevanceIds(bench);
          if (relevance.includes(UNCLASSIFIED)) {
            hasUnclassified = true;
          } else {
            relevance.forEach(id => ids.add(id));
          }
        }
      }
    }
    const result = [...ids].sort((left, right) => left.localeCompare(right));
    if (hasUnclassified) {
      result.push(UNCLASSIFIED);
    }
    return result;
  }

  function consumerDisplayName(consumerId, catalog) {
    if (consumerId === UNCLASSIFIED) {
      return 'Unclassified';
    }
    const consumer = catalog && catalog[consumerId];
    return consumer && consumer.displayName ? consumer.displayName : consumerId;
  }

  function relevanceLabel(bench, catalog) {
    return benchmarkRelevanceIds(bench)
      .map(id => consumerDisplayName(id, catalog))
      .join(', ');
  }

  function filterBenchmarkData(benchmarkData, selectedConsumerIds) {
    const filtered = {
      ...benchmarkData,
      entries: {},
    };
    for (const [suiteName, entries] of Object.entries(benchmarkData.entries || {})) {
      const filteredEntries = [];
      for (const entry of entries || []) {
        const benches = (entry.benches || []).filter(
          bench => matchesConsumerSelection(bench, selectedConsumerIds)
        );
        if (benches.length > 0) {
          filteredEntries.push({ ...entry, benches });
        }
      }
      if (filteredEntries.length > 0) {
        filtered.entries[suiteName] = filteredEntries;
      }
    }
    return filtered;
  }

  function latestSourceCommit(benchmarkData) {
    let latest = null;
    for (const entries of Object.values(benchmarkData.entries || {})) {
      for (const entry of entries || []) {
        if (!entry || !entry.commit || !entry.commit.id) {
          continue;
        }
        if (latest === null || Number(entry.date || 0) > latest.date) {
          latest = { id: String(entry.commit.id), date: Number(entry.date || 0) };
        }
      }
    }
    return latest && latest.id;
  }

  function coreScriptUrl(benchmarkData) {
    const commit = latestSourceCommit(benchmarkData);
    const repositoryUrl = String(benchmarkData.repoUrl || '').replace(/\/$/, '');
    if (!commit || !repositoryUrl.startsWith('https://github.com/')) {
      return null;
    }
    const repositoryPath = repositoryUrl.slice('https://github.com/'.length);
    return 'https://cdn.jsdelivr.net/gh/' + repositoryPath + '@' + commit
      + '/.github/benchmark-dashboard/dashboard-core.js';
  }

  function selectedFromLocation(root, available) {
    const parameter = new URL(root.location.href).searchParams.get('consumers');
    if (parameter === null) {
      return new Set(available);
    }
    const requested = new Set(parameter.split(',').map(value => value.trim()).filter(Boolean));
    return new Set(available.filter(id => requested.has(id)));
  }

  function updateLocation(root, selected, available) {
    const url = new URL(root.location.href);
    if (selected.size === available.length && available.every(id => selected.has(id))) {
      url.searchParams.delete('consumers');
    } else {
      url.searchParams.set('consumers', [...selected].join(','));
    }
    root.location.assign(url.toString());
  }

  function installStyles(root) {
    const style = root.document.createElement('style');
    style.textContent = `
      .consumer-relevance-filter { margin: 16px 0; padding: 10px 12px 12px; border: 1px solid #9ca3af; border-radius: 6px; }
      .consumer-relevance-filter legend { padding: 0 6px; font-weight: 700; }
      .consumer-relevance-options { display: flex; flex-wrap: wrap; gap: 8px 20px; margin: 4px 0 10px; }
      .consumer-relevance-option { display: inline-flex; align-items: center; white-space: nowrap; }
      .consumer-relevance-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
      .consumer-relevance-details { margin: 10px 0 0; font-size: .9rem; }
      .consumer-relevance-details code { overflow-wrap: anywhere; }
      .dashboard-load-error { padding: 12px; border: 1px solid #b91c1c; border-radius: 6px; }
    `;
    root.document.head.appendChild(style);
  }

  function renderFilter(root, fullData, available, selected) {
    const fieldset = root.document.createElement('fieldset');
    fieldset.className = 'consumer-relevance-filter';
    const legend = root.document.createElement('legend');
    legend.textContent = 'Consumer relevance';
    fieldset.appendChild(legend);

    const options = root.document.createElement('div');
    options.className = 'consumer-relevance-options';
    fieldset.appendChild(options);
    const catalog = fullData.consumerCatalog || {};
    for (const consumerId of available) {
      const label = root.document.createElement('label');
      label.className = 'consumer-relevance-option';
      const checkbox = root.document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.value = consumerId;
      checkbox.checked = selected.has(consumerId);
      checkbox.addEventListener('change', () => {
        if (checkbox.checked) {
          selected.add(consumerId);
        } else {
          selected.delete(consumerId);
        }
        updateLocation(root, selected, available);
      });
      label.appendChild(checkbox);
      label.appendChild(root.document.createTextNode(
        ' ' + consumerDisplayName(consumerId, catalog)
      ));
      const consumer = catalog[consumerId];
      label.title = consumerId === UNCLASSIFIED
        ? 'History published before consumer relevance metadata was available.'
        : consumer
          ? consumer.repository + '@' + String(consumer.ref).slice(0, 7)
            + '; modules: ' + (consumer.modules || []).join(', ')
          : 'Historical relevance tag not present in the current consumer catalog.';
      options.appendChild(label);
    }

    const actions = root.document.createElement('div');
    actions.className = 'consumer-relevance-actions';
    const all = root.document.createElement('button');
    all.type = 'button';
    all.textContent = 'Select all';
    all.addEventListener('click', () => updateLocation(root, new Set(available), available));
    const none = root.document.createElement('button');
    none.type = 'button';
    none.textContent = 'Clear selection';
    none.addEventListener('click', () => updateLocation(root, new Set(), available));
    const note = root.document.createElement('span');
    note.textContent = 'Tags identify affected library consumers; measurements were not run inside those applications.';
    actions.append(all, none, note);
    fieldset.appendChild(actions);

    const controls = root.document.querySelector('.controls');
    if (controls && controls.parentNode) {
      controls.parentNode.insertBefore(fieldset, controls);
    } else {
      root.document.body.insertBefore(fieldset, root.document.getElementById('main'));
    }
  }

  function appendRelevanceDetails(root, filteredData) {
    const catalog = filteredData.consumerCatalog || {};
    const operations = new Map();
    for (const entries of Object.values(filteredData.entries || {})) {
      for (const entry of entries || []) {
        for (const bench of entry.benches || []) {
          const operation = splitBenchmarkName(bench.name).operation;
          const key = JSON.stringify({
            consumers: benchmarkRelevanceIds(bench),
            contract: bench.contract || '',
            requiredModules: bench.requiredModules || [],
          });
          if (!operations.has(operation)) {
            operations.set(operation, new Map());
          }
          operations.get(operation).set(key, bench);
        }
      }
    }
    for (const [operation, variants] of operations) {
      const card = root.document.getElementById(operationAnchor(operation));
      if (!card) {
        continue;
      }
      const details = root.document.createElement('details');
      details.className = 'consumer-relevance-details';
      const summary = root.document.createElement('summary');
      summary.textContent = 'Consumer relevance evidence';
      details.appendChild(summary);
      const list = root.document.createElement('ul');
      for (const bench of variants.values()) {
        const item = root.document.createElement('li');
        item.textContent = relevanceLabel(bench, catalog)
          + (bench.contract ? ' — ' + bench.contract : '')
          + (Array.isArray(bench.requiredModules) && bench.requiredModules.length
            ? ' — modules: ' + bench.requiredModules.join(', ')
            : '');
        list.appendChild(item);
      }
      details.appendChild(list);
      card.appendChild(details);
    }
  }

  function restoreFullDownload(root, fullData) {
    const button = root.document.getElementById('download-button');
    if (!button) {
      return;
    }
    button.onclick = () => {
      const link = root.document.createElement('a');
      link.href = 'data:application/json;charset=utf-8,'
        + encodeURIComponent(JSON.stringify(fullData, null, 2));
      link.download = 'benchmark_data.json';
      link.click();
    };
  }

  function reportLoadFailure(root, message) {
    const main = root.document.getElementById('main');
    if (!main) {
      return;
    }
    const error = root.document.createElement('p');
    error.className = 'dashboard-load-error';
    error.textContent = message;
    main.appendChild(error);
  }

  function initialize(root) {
    const fullData = root.BENCHMARK_DATA;
    if (!fullData || !fullData.entries) {
      return;
    }
    const available = collectConsumerFilterIds(fullData);
    const selected = selectedFromLocation(root, available);
    installStyles(root);
    renderFilter(root, fullData, available, selected);
    root.BENCHMARK_DATA = filterBenchmarkData(fullData, selected);

    const source = coreScriptUrl(fullData);
    if (!source) {
      reportLoadFailure(root, 'Could not determine the immutable dashboard renderer for this benchmark history.');
      return;
    }
    const script = root.document.createElement('script');
    script.src = source;
    script.async = false;
    script.addEventListener('load', () => {
      restoreFullDownload(root, fullData);
      appendRelevanceDetails(root, root.BENCHMARK_DATA);
    });
    script.addEventListener('error', () => {
      reportLoadFailure(root, 'Could not load the commit-pinned benchmark renderer.');
    });
    root.document.head.appendChild(script);
  }

  return {
    UNCLASSIFIED,
    benchmarkRelevanceIds,
    collectConsumerFilterIds,
    coreScriptUrl,
    filterBenchmarkData,
    filterPointValues,
    initialize,
    matchesConsumerSelection,
    operationAnchor,
    relevanceLabel,
    splitBenchmarkName,
  };
});
