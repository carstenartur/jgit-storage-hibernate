#!/usr/bin/env bash
# Repackage immutable public artifacts using current tooling; never execute release-tag code.
set -euo pipefail
umask 077

fail() { echo "::error::$*" >&2; exit 1; }
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
version=${1:-}
mode=${2:-local}
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail 'release_version must use X.Y.Z (no SNAPSHOT)'
[[ "$mode" == fixture || "$mode" == bundle || "$mode" == upload || "$mode" == local || "$mode" == --check-only ]] || fail 'mode must be local, --check-only, bundle or upload'
if [[ "$mode" == local ]]; then
  [[ "${GITHUB_ACTIONS:-}" != true && -t 0 && -t 1 ]] || fail 'Run the personal bundle command interactively in your terminal; use --check-only for input validation.'
  state="$HOME/.local/share/jgit-storage-hibernate/central-key"
  [[ ! -L "$state" && ! -L "$state/gnupg" && ! -L "$state/fingerprint.txt" ]] || fail 'Refusing a symbolic-link signing-key directory or fingerprint.'
  if [[ -d "$state/gnupg" && ! -s "$state/fingerprint.txt" ]]; then
    echo 'Finishing the backup of your existing signing key; no new key will be created.'
    bash "$root/.github/scripts/setup-central-signing-key.sh" --resume
  fi
  [[ -d "$state/gnupg" && -s "$state/fingerprint.txt" ]] || fail 'Finish the one-time setup first: bash .github/scripts/setup-central-signing-key.sh'
  export MAVEN_GPG_FINGERPRINT
  MAVEN_GPG_FINGERPRINT=$(cat "$state/fingerprint.txt")
  [[ "$MAVEN_GPG_FINGERPRINT" =~ ^([[:xdigit:]]{40}|[[:xdigit:]]{64})$ ]] || fail 'Saved signing fingerprint is invalid; rerun the one-time setup.'
  unset MAVEN_GPG_KEY MAVEN_GPG_PASSPHRASE CENTRAL_USERNAME CENTRAL_PASSWORD
fi
if [[ "$mode" == bundle || "$mode" == upload ]]; then
  [[ "${GITHUB_EVENT_NAME:-}" == workflow_dispatch &&
     "${GITHUB_REF:-}" == "refs/heads/${DEFAULT_BRANCH:-main}" ]] || fail 'Run manually from the default branch'
  [[ -n "${MAVEN_GPG_KEY:-}" ]] || fail 'Set the MAVEN_GPG_KEY repository secret to the armored private signing key'
  if [[ -n "${MAVEN_GPG_FINGERPRINT:-}" ]]; then
    [[ "$MAVEN_GPG_FINGERPRINT" =~ ^([[:xdigit:]]{40}|[[:xdigit:]]{64})$ ]] || fail 'MAVEN_GPG_FINGERPRINT must be a full fingerprint'
  fi
  if [[ "$mode" == upload ]]; then
    [[ -n "${CENTRAL_USERNAME:-}" && -n "${CENTRAL_PASSWORD:-}" ]] || fail 'Upload requires CENTRAL_USERNAME and CENTRAL_PASSWORD repository secrets; select bundle to download without a Portal token'
  fi
fi

cd "$root"
work=$(mktemp -d)
if [[ "$mode" == local ]]; then
  export GNUPGHOME="$state/gnupg" GPG_TTY
  GPG_TTY=$(tty)
else
  export GNUPGHOME="$work/gnupg"
  mkdir -m 700 "$GNUPGHOME"
fi
cleanup() {
  unset MAVEN_GPG_PASSPHRASE
  if [[ "$mode" != local ]]; then
    gpgconf --homedir "$GNUPGHOME" --kill all >/dev/null 2>&1 || true
  fi
  git worktree remove --force "$work/release-source" >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT
trap 'echo "::error::Central preparation failed; no automatic upload retry was performed" >&2' ERR

# Fetch public data anonymously; never force-update an existing release tag.
tag="v$version"
publication_ref=refs/remotes/origin/maven-repository
fetch_options=(--no-tags)
if [[ "$(git rev-parse --is-shallow-repository)" == true ]]; then
  # A shallow boundary can masquerade as the manifest's first addition.
  fetch_options+=(--unshallow)
fi
git fetch "${fetch_options[@]}" origin "refs/tags/$tag:refs/tags/$tag" \
  "refs/heads/maven-repository:$publication_ref"
[[ "$(git cat-file -t "refs/tags/$tag")" == tag ]] || fail 'The release must have an annotated immutable release tag'
release_commit=$(git rev-parse --verify "refs/tags/$tag^{commit}")
[[ "$release_commit" =~ ^[0-9a-f]{40}$ ]] || fail 'Could not resolve the immutable release commit'
manifest="releases/$version.json"
mapfile -t additions < <(git log --format=%H --diff-filter=A "$publication_ref" -- "$manifest")
[[ ${#additions[@]} == 1 ]] || fail 'Expected exactly one first-publication commit for the release manifest'
publication_commit=${additions[0]}
git show "$publication_commit:$manifest" > "$work/manifest.json"
python3 - "$work/manifest.json" "$version" <<'PY'
import json, sys
from pathlib import Path
manifest = json.loads(Path(sys.argv[1]).read_text())
if manifest.get("version") != sys.argv[2] or manifest.get("groupId") != "io.github.carstenartur":
    raise SystemExit("Release manifest does not match the requested version/group")
PY
git worktree add --detach "$work/release-source" "$release_commit"

# This mode validates original inputs without opening a key or prompting.
if [[ "$mode" == --check-only ]]; then
  python3 .github/scripts/bundle-published-release.py --repository "$root" \
    --commit "$publication_commit" --project-root "$work/release-source" --version "$version" --check-only
  echo 'Published release inputs verified. No key was used, no bundle was signed and nothing was uploaded.'
  exit 0
fi

if [[ "$mode" == fixture ]]; then
  unset MAVEN_GPG_KEY MAVEN_GPG_FINGERPRINT CENTRAL_USERNAME CENTRAL_PASSWORD
  export MAVEN_GPG_PASSPHRASE=''
  gpg --batch --pinentry-mode loopback --passphrase '' --quick-generate-key \
    'Central published-release CI fixture <central-fixture@example.invalid>' ed25519 sign 1d
  output="$work/fixture-bundle.zip"
elif [[ "$mode" == local ]]; then
  read -rs -p 'Signing-key passphrase (never pasted into chat): ' MAVEN_GPG_PASSPHRASE </dev/tty
  printf '\n' >/dev/tty
  [[ -n "$MAVEN_GPG_PASSPHRASE" ]] || fail 'Enter the passphrase used by the signing-key setup.'
  export MAVEN_GPG_PASSPHRASE
  output="$root/target/central-publishing/central-bundle.zip"
  mkdir -p "$(dirname "$output")"
else
  # An empty passphrase supports an unprotected key without permitting interactive prompts.
  export MAVEN_GPG_PASSPHRASE="${MAVEN_GPG_PASSPHRASE:-}"
  printf '%s\n' "$MAVEN_GPG_KEY" > "$work/key.asc"
  printf '%s\n' "$MAVEN_GPG_PASSPHRASE" > "$work/passphrase"
  unset MAVEN_GPG_KEY
  gpg --batch --pinentry-mode loopback --passphrase-file "$work/passphrase" --import "$work/key.asc"
  rm -f "$work/key.asc" "$work/passphrase"
  output="$root/target/central-publishing/central-bundle.zip"
  mkdir -p "$(dirname "$output")"
fi

gpg --batch --with-colons --list-secret-keys > "$work/keys.txt"
fingerprint=$(python3 - "$work/keys.txt" <<'PY'
import os, sys
from pathlib import Path
fingerprints, primary = [], False
for line in Path(sys.argv[1]).read_text().splitlines():
    fields = line.split(":")
    if fields[0] == "sec":
        primary = True
    elif fields[0] == "ssb":
        primary = False
    elif fields[0] == "fpr" and primary:
        fingerprints.append(fields[9].upper())
        primary = False
expected = os.environ.get("MAVEN_GPG_FINGERPRINT", "").upper()
if expected:
    if expected not in fingerprints:
        raise SystemExit("MAVEN_GPG_FINGERPRINT is not an imported primary secret key")
elif len(fingerprints) == 1:
    expected = fingerprints[0]
else:
    raise SystemExit("Set MAVEN_GPG_FINGERPRINT when the imported backup contains multiple primary keys")
print(expected)
PY
)
python3 .github/scripts/bundle-published-release.py --repository "$root" \
  --commit "$publication_commit" --project-root "$work/release-source" --version "$version" \
  --fingerprint "$fingerprint" --output "$output"
python3 .github/scripts/verify-central-bundle.py "$output" \
  --project-root "$work/release-source" --verify-signatures
unset MAVEN_GPG_PASSPHRASE
if [[ "$mode" == fixture ]]; then
  echo 'Fixture signatures verified; the temporary key and bundle will be deleted, not uploaded.'
  exit 0
fi

python3 - "$output" "$version" "$release_commit" "$publication_commit" "$fingerprint" <<'PY'
import hashlib, json, os, sys
from pathlib import Path
bundle, version, source, publication, fingerprint = sys.argv[1:]
provenance = dict(version=version, releaseCommit=source, publicationCommit=publication,
                  fingerprint=fingerprint, sha256=hashlib.sha256(Path(bundle).read_bytes()).hexdigest())
Path(bundle).with_name("provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
if os.environ.get("GITHUB_OUTPUT"):
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write("bundle_ready=true\n")
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
        summary.write(f"## Signed Central bundle {version}\n\nRelease: `{source}`\n\n"
                      f"Public Maven publication: `{publication}`\n\nSigning fingerprint: `{fingerprint}`\n\n"
                      "Download the signed bundle from this run's artifacts. No private key is included.\n\n")
PY
if [[ "$mode" != upload ]]; then
  printf 'Signed bundle: %s\nIn the Codespaces Explorer: target → central-publishing → central-bundle.zip → Download.\nNothing was uploaded. Upload this ZIP in Central Portal.\n' "$output"
  exit 0
fi

# Fixed HTTPS endpoint, no redirects, no token on argv, and exactly one upload attempt.
python3 - "$output" "$version" <<'PY'
import base64, os, secrets, sys, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None
bundle, version = sys.argv[1:]
token = base64.b64encode((os.environ["CENTRAL_USERNAME"] + ":" + os.environ["CENTRAL_PASSWORD"]).encode()).decode()
boundary = "central-" + secrets.token_hex(24)
body = (f'--{boundary}\r\nContent-Disposition: form-data; name="bundle"; filename="central-bundle.zip"\r\n'
        'Content-Type: application/octet-stream\r\n\r\n').encode() + Path(bundle).read_bytes() + f"\r\n--{boundary}--\r\n".encode()
query = urllib.parse.urlencode(dict(name=f"jgit-storage-hibernate-{version}", publishingType="USER_MANAGED"))
request = urllib.request.Request("https://central.sonatype.com/api/v1/publisher/upload?" + query, data=body,
          headers={"Authorization": "Bearer " + token, "Content-Type": "multipart/form-data; boundary=" + boundary}, method="POST")
try:
    with urllib.request.build_opener(NoRedirect).open(request, timeout=600) as response:
        deployment = str(uuid.UUID(response.read(256).decode().strip()))
except (OSError, ValueError, urllib.error.URLError):
    raise SystemExit("Upload did not return a confirmed deployment ID. No retry was attempted. Check Central Portal before running again.")
print("Uploaded USER_MANAGED deployment " + deployment + "; review validation and click Publish in Central Portal.")
if os.environ.get("GITHUB_STEP_SUMMARY"):
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
        summary.write(f"Uploaded deployment: `{deployment}`\n\n"
                      "[Review validation and publish manually](https://central.sonatype.com/publishing/deployments). "
                      "The workflow does not publish automatically.\n")
PY
