#!/usr/bin/env bash
# Run personally in a Codespaces terminal; never in CI or through chat.
set -euo pipefail
umask 077
fail() { printf '%s\n' "$*" >&2; exit 1; }
[[ -t 0 && -t 1 ]] || fail 'Run this interactively in your personal terminal.'
command -v gpg >/dev/null || fail 'Install GnuPG first (on Debian/Ubuntu: sudo apt-get install gnupg).'
command -v flock >/dev/null || fail 'Install util-linux for the setup lock before continuing.'
repository=carstenartur/jgit-storage-hibernate
state="$HOME/.local/share/jgit-storage-hibernate/central-key"
export GNUPGHOME="$state/gnupg" GPG_TTY
GPG_TTY=$(tty)
[[ ! -L "$state" && ! -L "$GNUPGHOME" ]] || fail 'Refusing a symbolic-link key directory.'
mkdir -p "$GNUPGHOME"
chmod 700 "$state" "$GNUPGHOME"
[[ ! -L "$state/.setup.lock" ]] || fail 'Refusing a symbolic-link setup lock.'
exec 9>"$state/.setup.lock"
flock -n 9 || fail 'Another signing-key setup is running; wait for it to finish.'
for file in fingerprint.txt public-key.asc private-key.asc revocation.rev; do
  [[ ! -L "$state/$file" ]] || fail "Refusing symbolic link: $state/$file"
  [[ ! -e "$state/$file" || -f "$state/$file" ]] || fail "Not a regular backup file: $file"
  [[ ! -e "$state/$file" || -s "$state/$file" ]] || fail "Existing backup is empty; recover it before continuing: $file"
done
temporary=$(mktemp -d "$state/.setup-XXXXXXXX")
cleanup() {
  unset passphrase confirmation
  gpgconf --homedir "$GNUPGHOME" --kill gpg-agent >/dev/null 2>&1 || true
  gpgconf --homedir "$temporary/check-home" --kill gpg-agent >/dev/null 2>&1 || true
  rm -rf "$temporary"
}
trap cleanup EXIT
trap 'printf "%s\n" "Setup stopped. Existing keys and backups remain in $state; rerun to resume." >&2' ERR
read -rs -p 'Signing-key passphrase (never pasted into chat): ' passphrase </dev/tty; printf '\n' >/dev/tty
read -rs -p 'Repeat passphrase: ' confirmation </dev/tty; printf '\n' >/dev/tty
[[ -n "$passphrase" && "$passphrase" == "$confirmation" ]] || fail 'Passphrases must match and must not be empty.'
unset confirmation
list_keys() {
  gpg --batch --with-colons --list-secret-keys > "$temporary/keys"
  mapfile -t fingerprints < <(awk -F: '$1=="sec"{primary=1} $1=="ssb"{primary=0} $1=="fpr"&&primary{print $10;primary=0}' "$temporary/keys")
}
list_keys
if [[ ${#fingerprints[@]} == 0 ]]; then
  for file in fingerprint.txt public-key.asc private-key.asc revocation.rev; do
    [[ ! -e "$state/$file" ]] || fail 'Backups exist but the persistent key is missing; restore it instead of generating another key.'
  done
  read -r -p 'Public email address for this repository signing key: ' email </dev/tty
  [[ "$email" =~ ^[^\ \<\>]+@[^\ \<\>]+\.[^\ \<\>]+$ ]] || fail 'Enter a valid public email address.'
  printf '%s\n' "$passphrase" | gpg --batch --pinentry-mode loopback --passphrase-fd 0 \
    --quick-generate-key "jgit-storage-hibernate Maven Central <$email>" ed25519 sign 2y
  list_keys
fi
[[ ${#fingerprints[@]} == 1 ]] || fail 'Expected exactly one primary secret key in the dedicated directory; no new key was created.'
awk -F: '$1=="sec" && $4==22 && $17=="ed25519" && index($12,"s") && $2!~/^[red]$/{valid=1} END{exit !valid}' "$temporary/keys" || fail 'The primary must be a usable Ed25519 signing key; no key was replaced.'
grep -Fq 'jgit-storage-hibernate Maven Central' "$temporary/keys" || fail 'The existing key is not the repository signing key.'
fingerprint=${fingerprints[0]}
[[ ! -e "$state/fingerprint.txt" || "$(cat "$state/fingerprint.txt")" == "$fingerprint" ]] || fail 'Saved fingerprint does not match the persistent key.'
printf '%s\n' "$fingerprint" > "$temporary/fingerprint.txt"
gpg --batch --armor --output "$temporary/public-key.asc" --export "$fingerprint"
printf '%s\n' "$passphrase" | gpg --batch --pinentry-mode loopback --passphrase-fd 0 \
  --armor --output "$temporary/private-key.asc" --export-secret-keys "$fingerprint"
revocation="$GNUPGHOME/openpgp-revocs.d/$fingerprint.rev"
[[ -s "$revocation" ]] || fail 'Automatic revocation certificate is missing; the key is preserved. Recover its revocation certificate before continuing.'
cp "$revocation" "$temporary/revocation.rev"
backup="$temporary/private-key.asc"
[[ ! -e "$state/private-key.asc" ]] || backup="$state/private-key.asc"
mkdir -m 700 "$temporary/check-home"
printf '%s\n' "$passphrase" | gpg --homedir "$temporary/check-home" --batch --pinentry-mode loopback --passphrase-fd 0 --import "$backup"
printf 'Central signing backup check\n' > "$temporary/probe"
printf '%s\n' "$passphrase" | gpg --homedir "$temporary/check-home" --batch --pinentry-mode loopback --passphrase-fd 0 \
  --local-user "$fingerprint!" --output "$temporary/probe.sig" --detach-sign "$temporary/probe"
gpg --homedir "$temporary/check-home" --batch --verify "$temporary/probe.sig" "$temporary/probe"
for file in fingerprint.txt public-key.asc private-key.asc revocation.rev; do
  [[ -s "$temporary/$file" ]] || fail "Empty export: $file"
  if [[ ! -e "$state/$file" ]]; then
    ln "$temporary/$file" "$state/$file" # Atomic creation; never overwrite an existing backup.
  fi
  chmod 600 "$state/$file"
done
printf 'Signing fingerprint: %s\nBackups: %s\n' "$fingerprint" "$state"
printf 'In Codespaces: File > Open Folder > %s (then right-click each backup and Download).\n' "$state"
printf '%s\n' 'Download and securely retain these backups before deleting the Codespace. Keep private-key.asc and revocation.rev private.'
read -r -p 'Set this key, passphrase and fingerprint as GitHub Actions secrets now? [y/N] ' answer </dev/tty
if [[ "${answer,,}" == y ]]; then
  if command -v gh >/dev/null && gh auth status >/dev/null 2>&1 &&
     gh secret set MAVEN_GPG_KEY --repo "$repository" < "$state/private-key.asc" &&
     printf '%s' "$passphrase" | gh secret set MAVEN_GPG_PASSPHRASE --repo "$repository" &&
     printf '%s' "$fingerprint" | gh secret set MAVEN_GPG_FINGERPRINT --repo "$repository"; then
    printf '%s\n' 'The three signing secrets were set.'
  else
    printf '%s\n' 'Secrets were not all set. The Codespaces token may lack secrets-write permission.' \
      "Use https://github.com/$repository/settings/secrets/actions manually:" \
      'MAVEN_GPG_KEY = contents of private-key.asc; MAVEN_GPG_PASSPHRASE = your passphrase; MAVEN_GPG_FINGERPRINT = fingerprint.txt.'
  fi
fi
read -r -p 'Publish ONLY the public key to keyserver.ubuntu.com now? [y/N] ' answer </dev/tty
if [[ "${answer,,}" == y ]]; then
  gpg --batch --keyserver hkps://keyserver.ubuntu.com --send-keys "$fingerprint"
  printf '%s\n' 'Public key sent. This does not upload a release or any private key.'
fi
