"""Regression tests for immutable published-release inputs and atomic output."""
import hashlib
import importlib.util
import os
import zipfile
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name('bundle-published-release.py')


class PublishedReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'source'
        self.repo = self.root / 'repo'
        self.source.mkdir()
        self.repo.mkdir()
        (self.source / 'pom.xml').write_text('<project><groupId>example</groupId><artifactId>parent</artifactId><version>1.0</version><packaging>pom</packaging><modules><module>child</module></modules></project>')
        (self.source / 'child').mkdir()
        (self.source / 'child/pom.xml').write_text('<project><parent><groupId>example</groupId><version>1.0</version></parent><artifactId>child</artifactId></project>')
        self.files = {'example/parent/1.0/parent-1.0.pom': b'parent',
                      'example/child/1.0/child-1.0.pom': b'child'}
        for suffix in ('.jar', '-sources.jar', '-javadoc.jar', '-tests.jar'):
            self.files['example/child/1.0/child-1.0' + suffix] = suffix.encode()
        for name, data in self.files.items():
            path = self.repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            Path(str(path) + '.sha1').write_text(hashlib.sha1(data).hexdigest())
        self.git('init', '-q')
        self.commit = self.commit_files()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], stderr=subprocess.DEVNULL).decode().strip()

    def commit_files(self):
        self.git('add', '.')
        self.git('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', 'commit', '-qm', 'fixture')
        return self.git('rev-parse', 'HEAD')

    def load(self):
        self.assertTrue(SCRIPT.exists(), 'Published release bundler is not implemented')
        spec = importlib.util.spec_from_file_location('published_bundle', SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_immutable_objects_include_parent_and_classifier_ignore_dirty_worktree(self):
        # Reading the worktree rather than the pinned Git objects corrupts this result.
        (self.repo / next(iter(self.files))).write_bytes(b'LOCAL MODIFICATION')
        (self.repo / 'unrelated.jar').write_bytes(b'unrelated')
        actual = self.load().published_files(self.repo, self.commit, self.source)
        self.assertEqual(actual, self.files)

    def test_missing_javadoc_rejected_before_signing(self):
        (self.repo / 'example/child/1.0/child-1.0-javadoc.jar').unlink()
        commit = self.commit_files()
        with self.assertRaisesRegex(ValueError, 'Missing reactor artifact'):
            self.load().published_files(self.repo, commit, self.source)

    def test_committed_wrong_checksum_rejected(self):
        (self.repo / 'example/child/1.0/child-1.0.jar.sha1').write_text('0' * 40)
        commit = self.commit_files()
        with self.assertRaisesRegex(ValueError, 'checksum'):
            self.load().published_files(self.repo, commit, self.source)

    def test_failed_signing_preserves_existing_output(self):
        output = self.root / 'bundle.zip'
        output.write_bytes(b'previous verified bundle')
        module = self.load()
        with patch.object(module, 'sign', side_effect=ValueError('Signing failed')):
            with self.assertRaisesRegex(ValueError, 'Signing failed'):
                module.bundle(self.repo, self.commit, self.source, 'A' * 40, output)
        self.assertEqual(output.read_bytes(), b'previous verified bundle')
        self.assertEqual(list(self.root.glob('.central-bundle-*')), [])

    def test_complete_zip_preserves_bytes_and_contains_only_selected_artifacts(self):
        # Signing is the external boundary; ZIP assembly and verification stay real.
        output = self.root / 'bundle.zip'
        module = self.load()
        def fixture_signature(payload, signature, fingerprint):
            signature.write_bytes(b'fixture detached signature')
        with patch.object(module, 'sign', side_effect=fixture_signature):
            module.bundle(self.repo, self.commit, self.source, 'A' * 40, output)
        with zipfile.ZipFile(output) as archive:
            expected = {name + suffix for name in self.files
                        for suffix in ('', '.asc', '.md5', '.sha1', '.sha256', '.sha512')}
            self.assertEqual(set(archive.namelist()), expected)
            for name, data in self.files.items():
                self.assertEqual(archive.read(name), data)
                self.assertEqual(archive.read(name + '.sha256').decode(), hashlib.sha256(data).hexdigest())

    def test_wrong_signing_fingerprint_rejected(self):
        module = self.load()
        results = [subprocess.CompletedProcess([], 0, b'', b''),
                   subprocess.CompletedProcess([], 0, b'[GNUPG:] VALIDSIG ' + b'B' * 40 + b' rest\n', b'')]
        with patch.object(module.subprocess, 'run', side_effect=results):
            with self.assertRaisesRegex(ValueError, 'exact expected'):
                module.sign(self.root / 'artifact', self.root / 'artifact.asc', 'A' * 40)

    def test_passphrase_uses_stdin_and_signing_key_is_pinned(self):
        module = self.load()
        def gpg_boundary(command, **kwargs):
            self.assertNotIn('private passphrase', ' '.join(command))
            self.assertNotIn('MAVEN_GPG_PASSPHRASE', kwargs['env'])
            if '--detach-sign' in command:
                self.assertEqual(command[command.index('--local-user') + 1], 'A' * 40 + '!')
                self.assertEqual(kwargs['input'], b'private passphrase\n')
                self.assertIn('--passphrase-fd', command)
                return subprocess.CompletedProcess(command, 0, b'', b'')
            return subprocess.CompletedProcess(command, 0, b'[GNUPG:] VALIDSIG ' + b'A' * 40 + b' rest\n', b'')
        with patch.dict(os.environ, {'MAVEN_GPG_PASSPHRASE': 'private passphrase'}):
            with patch.object(module.subprocess, 'run', side_effect=gpg_boundary):
                module.sign(self.root / 'artifact', self.root / 'artifact.asc', 'A' * 40)

    def test_requested_release_mismatch_rejected_before_signing(self):
        module = self.load()
        output = self.root / 'bundle.zip'
        with patch.object(module, 'sign', side_effect=AssertionError('Must not sign mismatched release')):
            with self.assertRaisesRegex(ValueError, 'version'):
                module.bundle(self.repo, self.commit, self.source, 'A' * 40, output, version='2.0')
        self.assertFalse(output.exists())

    def test_requested_version_checks_child_modules_too(self):
        child = self.source / 'child/pom.xml'
        child.write_text(child.read_text().replace('<version>1.0</version>', '<version>2.0</version>'))
        with self.assertRaisesRegex(ValueError, 'version 2.0 does not match requested version 1.0'):
            self.load().published_files(self.repo, self.commit, self.source, version='1.0')

    def test_symbolic_commit_not_accepted(self):
        with self.assertRaisesRegex(ValueError, 'commit'):
            self.load().published_files(self.repo, 'HEAD', self.source)


if __name__ == '__main__':
    unittest.main()
