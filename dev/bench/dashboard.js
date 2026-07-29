'use strict';

(() => {
  const NAME_SEPARATOR = ' — ';
  const charts = [];
  const backendColors = new Map([
    ['JGit + filesystem', '#1f77b4'],
    ['JGit + HSQLDB (in-memory)', '#9467bd'],
    ['JGit + PostgreSQL', '#d62728'],
    ['JGit + PostgreSQL + HikariCP', '#2ca02c'],
  ]);
  const backendLineStyles = new Map([
    ['JGit + filesystem', { borderDash: [], pointStyle: 'circle' }],
    ['JGit + HSQLDB (in-memory)', { borderDash: [2, 3], pointStyle: 'rectRot' }],
    ['JGit + PostgreSQL', { borderDash: [10, 5], pointStyle: 'triangle' }],
    ['JGit + PostgreSQL + HikariCP', { borderDash: [10, 4, 2, 4], pointStyle: 'rect' }],
  ]);
  const fallbackColors = [
    '#17becf', '#ff7f0e', '#8c564b', '#e377c2', '#7f7f7f', '#bcbd22'
  ];
  const fallbackLineStyles = [
    { borderDash: [6, 3], pointStyle: 'cross' },
    { borderDash: [2, 2], pointStyle: 'star' },
    { borderDash: [12, 4, 2, 4], pointStyle: 'crossRot' },
  ];

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

  function collectOperations(entries) {
    const operations = new Map();
    for (const entry of entries) {
      const { commit, date, tool, benches } = entry;
      for (const bench of benches) {
        const { operation, backend } = splitBenchmarkName(bench.name);
        let backends = operations.get(operation);
        if (backends === undefined) {
          backends = new Map();
          operations.set(operation, backends);
        }
        let points = backends.get(backend);
        if (points === undefined) {
          points = [];
          backends.set(backend, points);
        }
        points.push({ commit, date, tool, bench });
      }
    }
    return operations;
  }

  function colorForBackend(backend, index) {
    return backendColors.get(backend) || fallbackColors[index % fallbackColors.length];
  }

  function lineStyleForBackend(backend, index) {
    return backendLineStyles.get(backend)
      || fallbackLineStyles[index % fallbackLineStyles.length];
  }

  function commitAxis(backends) {
    const commits = new Map();
    for (const points of backends.values()) {
      for (const point of points) {
        const existing = commits.get(point.commit.id);
        if (existing === undefined || point.date < existing.date) {
          commits.set(point.commit.id, point);
        }
      }
    }
    return [...commits.values()].sort((left, right) => left.date - right.date);
  }

  function latestPoint(points) {
    return points.reduce(
      (latest, point) => latest === null || point.date > latest.date ? point : latest,
      null
    );
  }

  function formatValue(value) {
    return Number(value).toLocaleString(undefined, { maximumSignificantDigits: 6 });
  }

  function relativeRatio(point, bestValue) {
    if (bestValue === 0) {
      return point.bench.value === 0 ? 1 : Number.POSITIVE_INFINITY;
    }
    return point.tool === 'customBiggerIsBetter'
      ? bestValue / point.bench.value
      : point.bench.value / bestValue;
  }

  function renderLatestValues(card, backends) {
    const latest = [...backends.entries()]
      .map(([backend, points]) => ({ backend, point: latestPoint(points) }))
      .filter(item => item.point !== null);
    if (latest.length === 0) {
      return;
    }

    const smallerIsBetter = latest[0].point.tool !== 'customBiggerIsBetter';
    const values = latest.map(item => Number(item.point.bench.value));
    const bestValue = smallerIsBetter ? Math.min(...values) : Math.max(...values);

    const note = document.createElement('p');
    note.className = 'chart-note';
    note.textContent = 'Near-identical measurements can overlap. Distinct line patterns and markers, the grouped tooltip, and the latest-value table keep every backend visible.';
    card.appendChild(note);

    const wrapper = document.createElement('div');
    wrapper.className = 'latest-values-wrapper';
    card.appendChild(wrapper);

    const table = document.createElement('table');
    table.className = 'latest-values';
    wrapper.appendChild(table);

    const head = document.createElement('thead');
    const headRow = document.createElement('tr');
    for (const label of ['Backend', 'Latest result', 'vs best', 'Commit']) {
      const cell = document.createElement('th');
      cell.scope = 'col';
      cell.textContent = label;
      headRow.appendChild(cell);
    }
    head.appendChild(headRow);
    table.appendChild(head);

    const body = document.createElement('tbody');
    for (const { backend, point } of latest) {
      const row = document.createElement('tr');

      const backendCell = document.createElement('th');
      backendCell.scope = 'row';
      backendCell.textContent = backend;
      row.appendChild(backendCell);

      const valueCell = document.createElement('td');
      valueCell.textContent = formatValue(point.bench.value) + ' ' + point.bench.unit;
      row.appendChild(valueCell);

      const ratioCell = document.createElement('td');
      const ratio = relativeRatio(point, bestValue);
      ratioCell.textContent = Number.isFinite(ratio) ? ratio.toFixed(2) + '×' : '∞';
      row.appendChild(ratioCell);

      const commitCell = document.createElement('td');
      const commitLink = document.createElement('a');
      commitLink.href = point.commit.url;
      commitLink.target = '_blank';
      commitLink.rel = 'noopener';
      commitLink.textContent = point.commit.id.slice(0, 7);
      commitCell.appendChild(commitLink);
      row.appendChild(commitCell);

      body.appendChild(row);
    }
    table.appendChild(body);
  }

  function renderOperation(parent, operation, backends) {
    const card = document.createElement('section');
    card.className = 'benchmark-chart-card';
    parent.appendChild(card);

    const title = document.createElement('h3');
    title.textContent = operation;
    card.appendChild(title);

    const container = document.createElement('div');
    container.className = 'chart-container';
    card.appendChild(container);

    const canvas = document.createElement('canvas');
    container.appendChild(canvas);

    const axis = commitAxis(backends);
    const labels = axis.map(point => point.commit.id.slice(0, 7));
    const commitIds = axis.map(point => point.commit.id);
    const datasets = [...backends.entries()].map(([backend, points], index) => {
      const byCommit = new Map(points.map(point => [point.commit.id, point]));
      const color = colorForBackend(backend, index);
      const lineStyle = lineStyleForBackend(backend, index);
      return {
        label: backend,
        data: commitIds.map(commitId => {
          const point = byCommit.get(commitId);
          return point === undefined ? null : point.bench.value;
        }),
        pointMeta: commitIds.map(commitId => byCommit.get(commitId) || null),
        borderColor: color,
        backgroundColor: color,
        borderDash: lineStyle.borderDash,
        borderDashOffset: index * 1.5,
        borderWidth: 2.5,
        pointStyle: lineStyle.pointStyle,
        pointRadius: 3.5,
        pointHoverRadius: 6,
        pointBorderWidth: 1.5,
        fill: false,
        lineTension: 0.1,
        spanGaps: true,
      };
    });

    const firstPoint = [...backends.values()].flat()[0];
    const unit = firstPoint === undefined ? '' : firstPoint.bench.unit;
    const chart = new Chart(canvas, {
      type: 'line',
      data: { labels, datasets },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        legend: {
          display: true,
          position: 'bottom',
          labels: { usePointStyle: true },
        },
        hover: {
          mode: 'index',
          intersect: false,
        },
        scales: {
          xAxes: [{
            scaleLabel: { display: true, labelString: 'commit' },
          }],
          yAxes: [{
            type: document.getElementById('scale-select').value,
            scaleLabel: { display: true, labelString: unit },
            ticks: { beginAtZero: true },
          }],
        },
        tooltips: {
          mode: 'index',
          intersect: false,
          callbacks: {
            afterTitle: (items, data) => {
              if (items.length === 0) {
                return '';
              }
              const dataset = data.datasets[items[0].datasetIndex];
              const point = dataset.pointMeta[items[0].index];
              if (point === null) {
                return '';
              }
              return '\n' + point.commit.message + '\n\n' + point.commit.timestamp
                + ' committed by @' + point.commit.committer.username + '\n';
            },
            label: (item, data) => {
              const dataset = data.datasets[item.datasetIndex];
              const point = dataset.pointMeta[item.index];
              if (point === null) {
                return dataset.label + ': no result';
              }
              let label = dataset.label + ': ' + point.bench.value + ' ' + point.bench.unit;
              if (point.bench.range) {
                label += ' (' + point.bench.range + ')';
              }
              return label;
            },
            afterLabel: (item, data) => {
              const point = data.datasets[item.datasetIndex].pointMeta[item.index];
              return point !== null && point.bench.extra ? '\n' + point.bench.extra : '';
            },
          },
        },
        onClick: (_event, activeElements) => {
          if (activeElements.length === 0) {
            return;
          }
          const active = activeElements[0];
          const dataset = active._chart.data.datasets[active._datasetIndex];
          const point = dataset.pointMeta[active._index];
          if (point !== null) {
            window.open(point.commit.url, '_blank');
          }
        },
      },
    });
    charts.push(chart);
    renderLatestValues(card, backends);
  }

  function renderSuite(main, suiteName, entries) {
    const suite = document.createElement('section');
    suite.className = 'benchmark-set';
    main.appendChild(suite);

    const title = document.createElement('h2');
    title.textContent = suiteName;
    suite.appendChild(title);

    const operations = collectOperations(entries);
    for (const [operation, backends] of operations.entries()) {
      renderOperation(suite, operation, backends);
    }
  }

  function initialize() {
    const benchmarkData = window.BENCHMARK_DATA;
    document.getElementById('last-update').textContent =
      new Date(benchmarkData.lastUpdate).toString();

    const repositoryLink = document.getElementById('repository-link');
    repositoryLink.href = benchmarkData.repoUrl;
    repositoryLink.textContent = benchmarkData.repoUrl;

    document.getElementById('download-button').onclick = () => {
      const link = document.createElement('a');
      link.href = 'data:application/json;charset=utf-8,'
        + encodeURIComponent(JSON.stringify(benchmarkData, null, 2));
      link.download = 'benchmark_data.json';
      link.click();
    };

    document.getElementById('scale-select').onchange = event => {
      const scaleType = event.target.value;
      for (const chart of charts) {
        const yAxis = chart.options.scales.yAxes[0];
        yAxis.type = scaleType;
        yAxis.ticks.beginAtZero = scaleType === 'linear';
        chart.update();
      }
    };

    const main = document.getElementById('main');
    for (const [suiteName, entries] of Object.entries(benchmarkData.entries)) {
      renderSuite(main, suiteName, entries);
    }
  }

  initialize();
})();
