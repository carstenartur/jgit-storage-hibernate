"""Execute the credential and retry gates used by automatic Central dispatch."""
import json
import contextlib
import io
import sys
import urllib.parse
from unittest.mock import patch
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]


def step_run(workflow, name):
    text = (ROOT / '.github/workflows' / workflow).read_text()
    marker = '      - name: ' + name + '\n'
    if marker not in text:
        raise AssertionError('Missing workflow step: ' + name)
    section = text.split(marker, 1)[1].split('      - name:', 1)[0]
    body = section.split('        run: |\n', 1)[1]
    lines = []
    for line in body.splitlines():
        if line.strip() and not line.startswith('          '):
            break
        lines.append(line)
    return textwrap.dedent('\n'.join(lines))


class ReleaseCentralUploadWorkflowTest(unittest.TestCase):
    def gate(self, workflow, name, values=None, jobs=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gh = root / 'gh'
            gh.write_text('#!/bin/sh\ncat "$JOB_FIXTURE"\n')
            gh.chmod(0o755)
            fixture = root / 'jobs.json'
            fixture.write_text(json.dumps([{'jobs': jobs or []}]))
            env = os.environ.copy()
            env.update(GITHUB_OUTPUT=str(root/'output'), GITHUB_STEP_SUMMARY=str(root/'summary'),
                       GITHUB_RUN_ATTEMPT='1', GITHUB_RUN_ID='123', GITHUB_REPOSITORY='example/repo',
                       CENTRAL_MODE='release-upload', MAVEN_GPG_KEY='', MAVEN_GPG_FINGERPRINT='',
                       MAVEN_GPG_PASSPHRASE='', CENTRAL_USERNAME='', CENTRAL_PASSWORD='',
                       JOB_FIXTURE=str(fixture), PATH=str(root)+os.pathsep+env['PATH'])
            env.update(values or {})
            result = subprocess.run(['bash', '-euo', 'pipefail', '-c', step_run(workflow, name)],
                                    env=env, capture_output=True, text=True)
            output = (root/'output').read_text() if (root/'output').exists() else ''
            return result, output

    def credentials(self, values=None):
        return self.gate('maven-central.yml', 'Check optional automatic-upload credentials', values)

    def test_no_credentials_skips_without_failing_release(self):
        result, output = self.credentials()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('ready=false', output)

    def test_partial_credentials_fail_without_printing_values(self):
        result, output = self.credentials({'CENTRAL_USERNAME': 'do-not-log-this'})
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('do-not-log-this', result.stdout + result.stderr + output)
        self.assertNotIn('ready=true', output)

    def test_complete_credentials_allow_inferred_fingerprint_and_empty_passphrase(self):
        result, output = self.credentials({'MAVEN_GPG_KEY':'key',
                                           'CENTRAL_USERNAME':'name', 'CENTRAL_PASSWORD':'token'})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('ready=true', output)

    def test_public_only_import_reports_missing_private_key(self):
        source = (ROOT / '.github/scripts/run-central-published-release.sh').read_text()
        selector = source.split('fingerprint=$(python3', 1)[1].split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            # GnuPG lists no secret keys after importing a public-only certificate.
            listing = Path(directory) / 'keys.txt'
            listing.write_text('')
            for expected in ('', 'A' * 40):
                with self.subTest(expected=expected):
                    result = subprocess.run(
                        [sys.executable, '-', str(listing)], input=selector, text=True,
                        capture_output=True, env=dict(os.environ, MAVEN_GPG_FINGERPRINT=expected))
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn('private-key.asc', result.stderr)
                    self.assertNotIn('MAVEN_GPG_FINGERPRINT', result.stderr)

    def retry(self, jobs):
        return self.gate('release.yml', 'Avoid duplicate Central dispatch on reruns',
                         {'GITHUB_RUN_ATTEMPT':'2'}, jobs)

    def test_retry_after_finalize_failure_can_dispatch(self):
        result, output = self.retry([{'name':'Publish merged immutable release', 'steps':[]}])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('ready=true', output)

    def test_successful_previous_dispatch_is_not_repeated(self):
        result, output = self.retry([{'name':'Dispatch published release to Maven Central', 'steps':[
            {'name':'Dispatch Central upload and automatic publication', 'started_at':'2026-01-01', 'conclusion':'success'}]}])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('ready=false', output)

    def test_ambiguous_previous_dispatch_requires_manual_check(self):
        result, output = self.retry([{'name':'Dispatch published release to Maven Central', 'steps':[
            {'name':'Dispatch Central upload and automatic publication', 'started_at':'2026-01-01', 'conclusion':'failure'}]}])
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('ready=true', output)

    def upload(self, mode, state='PUBLISHED', clock=(0, 0)):
        source = (ROOT / '.github/scripts/run-central-published-release.sh').read_text()
        script = source.split('# Fixed HTTPS endpoint', 1)[1].split("<<'PY'\n", 1)[1].rsplit('\nPY', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            bundle = Path(directory) / 'bundle.zip'
            bundle.write_bytes(b'fixture signed bundle')
            requests = []
            class Response:
                def __init__(self, body): self.body = body
                def __enter__(self): return self
                def __exit__(self, *args): pass
                def read(self, size): return self.body
            class Opener:
                def open(self, request, timeout):
                    requests.append(request)
                    if '/status?' in request.full_url:
                        return Response(json.dumps(dict(deploymentId='12345678-1234-1234-1234-123456789012',
                                                        deploymentState=state)).encode())
                    return Response(b'12345678-1234-1234-1234-123456789012')
            error = None
            with patch.dict(os.environ, {'CENTRAL_USERNAME':'fixture-user', 'CENTRAL_PASSWORD':'fixture-token', 'GITHUB_STEP_SUMMARY':str(Path(directory)/'summary')}), \
                 patch.object(sys, 'argv', ['-', str(bundle), '1.0.0', mode]), \
                 patch('urllib.request.build_opener', return_value=Opener()), \
                 patch('time.monotonic', side_effect=clock), patch('time.sleep'), contextlib.redirect_stdout(io.StringIO()):
                try:
                    exec(compile(script, '<workflow uploader>', 'exec'), {})
                except SystemExit as exc:
                    error = str(exc)
            summary = (Path(directory)/'summary').read_text()
            return requests, summary, error

    def test_upload_mode_controls_publication_and_never_reuploads(self):
        for mode, wanted in [('release-upload', 'AUTOMATIC'), ('upload', 'USER_MANAGED')]:
            with self.subTest(mode=mode):
                requests, summary, error = self.upload(mode)
                self.assertIsNone(error)
                uploads = [request for request in requests if '/upload?' in request.full_url]
                self.assertEqual(len(uploads), 1)
                query = urllib.parse.parse_qs(urllib.parse.urlsplit(uploads[0].full_url).query)
                self.assertEqual(query['publishingType'], [wanted])
                self.assertNotIn('fixture-token', summary)
                self.assertIn('**PUBLISHED**' if mode == 'release-upload' else 'publish manually', summary)
                self.assertEqual(len(requests), 2 if mode == 'release-upload' else 1)

    def test_central_failed_status_fails_child_without_reupload(self):
        requests, summary, error = self.upload('release-upload', state='FAILED')
        self.assertIn('FAILED', error)
        self.assertIn('12345678-1234-1234-1234-123456789012', error)
        self.assertIn('https://central.sonatype.com/publishing/deployments', error)
        self.assertEqual(len([r for r in requests if '/upload?' in r.full_url]), 1)
        self.assertNotIn('**PUBLISHED**', summary)

    def test_status_timeout_reports_uncertainty_without_reupload(self):
        requests, summary, error = self.upload('release-upload', state='VALIDATING', clock=(0, 0, 301))
        self.assertIn('may still complete', error)
        self.assertIn('No reupload', error)
        self.assertEqual(len([r for r in requests if '/upload?' in r.full_url]), 1)
        self.assertNotIn('**PUBLISHED**', summary)

    def test_dispatch_depends_on_successful_real_finalization(self):
        text = (ROOT / '.github/workflows/release.yml').read_text()
        self.assertIn('  central-upload:\n', text)
        job = text.split('  central-upload:\n', 1)[1]
        self.assertIn('needs: finalize', job)
        self.assertIn("needs.finalize.result == 'success'", job)
        self.assertIn('needs.finalize.outputs.release_version', job)
        self.assertIn('gh workflow run maven-central.yml', job)
        self.assertIn('-f mode=release-upload', job)
        self.assertNotIn('secrets.', job)


if __name__ == '__main__':
    unittest.main()
