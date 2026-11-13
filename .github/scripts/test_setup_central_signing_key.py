"""Real interactive GNU-GPG setup regression; only disposable local certificates.

Run on a Linux host with AF_UNIX support (for example a hosted Actions runner).
No GitHub secrets or keyserver operations are selected by this test.
"""
import errno
import hashlib
import os
from pathlib import Path
import pty
import select
import signal
import socket
import subprocess
import sys
import tempfile
import time
import unittest

SCRIPT = Path(__file__).with_name('setup-central-signing-key.sh').resolve()
PASSPHRASE = 'Disposable fixture passphrase 8372!'


def socket_support():
    try:
        with tempfile.TemporaryDirectory(prefix='gs', dir='/tmp') as directory:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as probe:
                probe.bind(str(Path(directory) / 's'))
        return True
    except OSError as exc:
        if exc.errno in (errno.EPERM, errno.EACCES, errno.EAFNOSUPPORT):
            return False
        raise


REQUESTED = __name__ == '__main__' or os.environ.get('RUN_REAL_GPG_SETUP_TEST') == '1'
SOCKETS_AVAILABLE = socket_support() if REQUESTED else False


@unittest.skipUnless(REQUESTED, 'Real crypto runs once via explicit standalone invocation')
@unittest.skipUnless(SOCKETS_AVAILABLE, 'Kernel disallows AF_UNIX bind; real gpg-agent requires sockets')
class SigningKeySetupTest(unittest.TestCase):
    def setUp(self):
        # A HOME about as short as /home/codespace; the checkout path is long.
        self.temp = tempfile.TemporaryDirectory(prefix='cs', dir='/tmp')
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name)
        self.cwd = self.home / ('long-codespaces-checkout-' * 8)
        self.cwd.mkdir()
        self.state = self.home / '.local/share/jgit-storage-hibernate/central-key'
        self.addCleanup(self.stop_agents)

    def stop_agents(self):
        for home in self.home.rglob('gnupg'):
            subprocess.run(['gpgconf', '--homedir', str(home), '--kill', 'gpg-agent'],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def interactive(self, passphrase=PASSPHRASE, creating=False, resume=False):
        replies = [(b'Signing-key passphrase', passphrase)]
        if creating:
            replies += [(b'Repeat passphrase', passphrase),
                        (b'Public email address', 'fixture@example.invalid')]
        replies += [(b'GitHub Actions secrets now?', 'n'),
                    (b'keyserver.ubuntu.com now?', 'n')]
        pid, fd = pty.fork()
        if pid == 0:
            os.chdir(self.cwd)
            env = {**os.environ, 'HOME': str(self.home), 'LC_ALL': 'C', 'TERM': 'xterm'}
            for name in ('GNUPGHOME', 'GPG_TTY', 'GPG_AGENT_INFO', 'MAVEN_GPG_KEY',
                         'MAVEN_GPG_PASSPHRASE', 'MAVEN_GPG_FINGERPRINT', 'GH_TOKEN', 'GITHUB_TOKEN'):
                env.pop(name, None)
            args = ['bash', str(SCRIPT)] + (['--resume'] if resume else [])
            os.execve('/bin/bash', args, env)
        output, pending, deadline = b'', b'', time.monotonic() + 120
        try:
            while time.monotonic() < deadline:
                if select.select([fd], [], [], .1)[0]:
                    try:
                        chunk = os.read(fd, 65536)
                    except OSError as exc:
                        if exc.errno == errno.EIO:
                            break
                        raise
                    if not chunk:
                        break
                    output += chunk
                    pending += chunk
                    if replies and replies[0][0] in pending:
                        _, reply = replies.pop(0)
                        os.write(fd, reply.encode() + b'\n')
                        pending = b''
                finished, status = os.waitpid(pid, os.WNOHANG)
                if finished:
                    return os.waitstatus_to_exitcode(status), output.decode(errors='replace')
            else:
                os.kill(pid, signal.SIGKILL)
                os.waitpid(pid, 0)
                self.fail('Interactive setup timed out')
            _, status = os.waitpid(pid, 0)
            return os.waitstatus_to_exitcode(status), output.decode(errors='replace')
        finally:
            os.close(fd)

    def test_passphrase_protected_setup_resume_and_wrong_passphrase(self):
        rc, output = self.interactive(creating=True)
        self.assertEqual(rc, 0, output)
        names = ['fingerprint.txt', 'public-key.asc', 'private-key.asc', 'revocation.rev']
        before = {}
        for name in names:
            p = self.state / name
            self.assertTrue(p.stat().st_size > 0, name)
            self.assertEqual(p.stat().st_mode & 0o777, 0o600, name)
            before[name] = hashlib.sha256(p.read_bytes()).hexdigest()
        fingerprint = (self.state / 'fingerprint.txt').read_text().strip()
        self.assertRegex(fingerprint, r'^[A-F0-9]{40}$')
        self.assertTrue((self.state / 'public-key.asc').read_text().startswith('-----BEGIN PGP PUBLIC KEY BLOCK-----'))
        self.assertTrue((self.state / 'private-key.asc').read_text().startswith('-----BEGIN PGP PRIVATE KEY BLOCK-----'))
        self.assertNotIn(PASSPHRASE, output)
        # Resume an interrupted setup with a persistent key but missing metadata.
        (self.state / 'fingerprint.txt').unlink()
        rc, output = self.interactive(resume=True)
        self.assertEqual(rc, 0, output)
        self.assertEqual((self.state / 'fingerprint.txt').read_text().strip(), fingerprint)
        rc, _ = self.interactive(passphrase='Deliberately incorrect fixture passphrase', resume=True)
        self.assertNotEqual(rc, 0)
        rc, output = self.interactive(resume=True)
        self.assertEqual(rc, 0, output)
        for name, expected in before.items():
            self.assertEqual(hashlib.sha256((self.state / name).read_bytes()).hexdigest(), expected)
        self.assertFalse(list(self.state.glob('.setup-*')))

    def test_resume_missing_key_refuses_to_create_one(self):
        rc, output = self.interactive(resume=True)
        self.assertNotEqual(rc, 0)
        self.assertIn('No existing signing key directory', output)
        self.assertFalse(self.state.exists())
        (self.state / 'gnupg').mkdir(parents=True, mode=0o700)
        rc, output = self.interactive(resume=True)
        self.assertNotEqual(rc, 0)
        self.assertIn('No existing private signing key', output)
        self.assertNotIn('Signing-key passphrase', output)
        self.assertFalse((self.state / 'private-key.asc').exists())
        self.assertFalse(list((self.state / 'gnupg').glob('private-keys-v1.d/*.key')))


if __name__ == '__main__':
    result = unittest.main(exit=False).result
    if result.skipped:
        print('ERROR: Explicit real-GPG setup test was skipped; no crypto success claimed.', file=sys.stderr)
    sys.exit(0 if result.wasSuccessful() and not result.skipped else 1)
