#!/usr/bin/env bash
# Exercise the real release reactor without production keys or publisher credentials.
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$project_root"
maven_command=${MAVEN_COMMAND:-mvn}
probe_directory=$(mktemp -d)
export GNUPGHOME="$probe_directory/gnupg"
mkdir -m 700 "$GNUPGHOME"
cleanup() {
  gpgconf --homedir "$GNUPGHOME" --kill gpg-agent >/dev/null 2>&1 || true
  rm -rf "$probe_directory"
}
trap cleanup EXIT

probe_settings="$probe_directory/settings.xml"
printf '%s\n' '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" />' > "$probe_settings"
unset MAVEN_GPG_KEY MAVEN_GPG_PASSPHRASE CENTRAL_USERNAME CENTRAL_PASSWORD

# Conflicting channels must fail before signing or deploying anything.
for conflicting_profile in public-repository-release github-packages; do
  if "$maven_command" -B -ntp -s "$probe_settings" -N \
      "-Pmaven-central,$conflicting_profile" validate > "$probe_directory/conflict.log" 2>&1; then
    echo "Conflicting publication profiles were accepted: $conflicting_profile" >&2
    exit 1
  fi
  if ! grep -Fq 'Activate maven-central on its own' "$probe_directory/conflict.log"; then
    cat "$probe_directory/conflict.log" >&2
    exit 1
  fi
done

# Check snapshot rejection against the same parent POM without changing the release tree.
python3 - "$probe_directory/snapshot-pom.xml" <<'PY'
import sys
import xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
ET.register_namespace("", ns["m"])
tree = ET.parse("pom.xml")
tree.find("m:version", ns).text = "0.0.0-SNAPSHOT"
tree.write(sys.argv[1], encoding="utf-8", xml_declaration=True)
PY
if "$maven_command" -B -ntp -s "$probe_settings" -N \
    -f "$probe_directory/snapshot-pom.xml" -Pmaven-central validate > "$probe_directory/snapshot.log" 2>&1; then
  echo 'The Central profile accepted a snapshot project' >&2
  exit 1
fi
if ! grep -Fq 'RequireReleaseVersion' "$probe_directory/snapshot.log"; then
  cat "$probe_directory/snapshot.log" >&2
  exit 1
fi

gpg --batch --pinentry-mode loopback --passphrase '' --quick-generate-key \
  'Central bundle CI fixture <central-bundle@example.invalid>' rsa2048 sign 1d

# The profile's safe default must win over the plugin's unprefixed user property.
# An unreachable publisher URL and empty settings make accidental uploading fail closed.
"$maven_command" -B -ntp -s "$probe_settings" -Pmaven-central -DskipTests \
  -DskipUpload=false -DcentralBaseUrl=http://127.0.0.1:9 deploy
python3 .github/scripts/verify-central-bundle.py \
  target/central-publishing/central-bundle.zip --verify-signatures
