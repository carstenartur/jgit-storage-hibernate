from csv import DictReader, DictWriter
from math import isfinite
from pathlib import Path
from statistics import median
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / 'consumer' / '.github' / 'scripts'))
from validate_ranked_scroll_evidence import COLUMNS, histogram


def read_csv(path):
    with path.open(newline='') as source:
        return list(DictReader(source))


def observed_medians(path):
    rows = read_csv(path)
    assert [row['sample'] for row in rows] == ['warmup', '1', '2', '3'], path
    columns = ['verified_export_ms', 'first_fetch_ms', 'all_fetches_ms', 'max_fetch_ms']
    result = {}
    for column in columns:
        values = [float(row[column]) for row in rows[1:]]
        assert all(isfinite(value) and value > 0 for value in values), (path, column)
        result['median_' + column] = round(median(values), 3)
    return result


def ranked_state(folder, documents, budget):
    rows = read_csv(folder / 'retained-hits.csv')
    assert rows[0]['phase'] == 'before_open' and int(rows[0]['live_field_docs']) == 0, folder
    assert rows[-1]['phase'] == 'after_close' and int(rows[-1]['consumed_rows']) == documents, folder
    last_chunk = (documents + 127) // 128
    checkpoints = [('before_open', 0)]
    chunk = 1
    while chunk <= last_chunk:
        checkpoints.append((f'chunk_{chunk}', min(chunk * 128, documents)))
        chunk *= 2
    if checkpoints[-1][1] != documents:
        checkpoints.append((f'chunk_{last_chunk}', documents))
    checkpoints.append(('after_close', documents))
    assert [(row['phase'], int(row['consumed_rows'])) for row in rows] == checkpoints, folder
    for row in rows:
        values = tuple(int(row[column]) for column in COLUMNS[2:])
        assert min(values) >= 0 and values == histogram(folder / f"{row['phase']}.histogram.txt"), folder
    after = int(rows[-1]['live_field_docs'])
    assert after == 0, folder
    maximum = max(int(row['live_field_docs']) for row in rows)
    assert maximum > 0 and (budget is None or maximum <= budget), folder
    env = dict(line.split('=', maxsplit=1) for line in (folder / 'environment.txt').read_text().splitlines())
    assert (int(env['documents']), int(env['chunk-size'])) == (documents, 128), folder
    assert int(env['maximum-heap-bytes']) == 512 * 1024 * 1024, folder
    assert env['java'].startswith('21.0.13+9') and env['lucene'] == '9.12.3', folder
    assert env['hibernate'] == '7.4.14.Final', folder
    assert env['hibernate-search'] == ('8.4.1.Final' if budget is None else '8.4.2-SNAPSHOT'), folder
    assert (int(env['entity-loads']), int(env['prepared-statements'])) == (0, 0), folder
    assert int(env['sessions-opened']) == int(env['sessions-closed']) > 0, folder
    return {'max_sampled_live_field_docs': maximum, 'field_docs_after_release': after}


def summarize(folder):
    documents = int(folder.name)
    data = []
    for variant in ('release', '512', '4096', '16384'):
        budget = None if variant == 'release' else int(variant) + 1
        data.append({'documents': documents, 'variant': variant, 'total_hit_threshold': 'default',
                     **observed_medians(folder / f'timing-{variant}' / 'verified-export-timings.csv'),
                     **ranked_state(folder / f'memory-{variant}', documents, budget)})
    for variant in ('512', '4096', '16384'):
        report = folder / f'threshold1-{variant}' / 'verified-export-timings.csv'
        if report.is_file():
            data.append({'documents': documents, 'variant': variant, 'total_hit_threshold': 1,
                         **observed_medians(report)})
    writer = DictWriter(sys.stdout, fieldnames=list(data[0]))
    writer.writeheader()
    writer.writerows(data)


def summarize_spot_check(folder):
    data = []
    for variant in ('release', '16384'):
        cell = folder / variant
        budget = None if variant == 'release' else 16385
        rows = read_csv(cell / 'verified-export-timings.csv')
        assert len(rows) == 1 and rows[0]['sample'] == '1', cell
        times = {column: float(value) for column, value in rows[0].items() if column != 'sample'}
        assert all(isfinite(value) and value > 0 for value in times.values()), cell
        assert times['first_fetch_ms'] <= times['first_row_ms'] <= times['verified_export_ms'], cell
        assert times['max_fetch_ms'] <= times['all_fetches_ms'] <= times['verified_export_ms'], cell
        data.append({'documents': 1_000_000, 'variant': variant, 'total_hit_threshold': 'default',
                     **{column: round(value, 3) for column, value in times.items()},
                     **ranked_state(cell, 1_000_000, budget)})
    writer = DictWriter(sys.stdout, fieldnames=list(data[0]))
    writer.writeheader()
    writer.writerows(data)


if __name__ == '__main__':
    input_folder = Path(sys.argv[1])
    if input_folder.name == '1000000':
        summarize_spot_check(input_folder)
    else:
        summarize(input_folder)
