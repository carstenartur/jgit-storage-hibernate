# Maven Central publishing

Maven Central is the primary public release channel starting with the first release produced by this publishing path. GitHub Packages remains available as an optional secondary channel and continues to host development snapshots.

The repository contains the complete build and release automation, but a repository administrator must perform the Central account, namespace, token and signing-key setup once. Secret values must never be committed.

## Distribution contract

| Channel | Purpose | Credentials required by consumers |
|---|---|---|
| Maven Central | Immutable public releases | none |
| GitHub Packages | Optional secondary release copy and snapshots | GitHub token |
| GitHub Release | Release notes and attached convenience artifacts | none for public downloads |

Normal `mvn verify` builds activate neither publishing profile and need no publishing credentials.

## One-time Central Portal setup

1. Sign in to the [Central Publisher Portal](https://central.sonatype.com/) with the GitHub account that owns the repository.
2. Confirm that namespace `io.github.carstenartur` is present and verified. The Maven `groupId` in this project is exactly that namespace.
3. Generate a Central Portal user token. The Portal presents a token username and token password; they are not the normal GitHub credentials.
4. Store the token values as repository Actions secrets:

   ```text
   MAVEN_CENTRAL_USERNAME
   MAVEN_CENTRAL_PASSWORD
   ```

The official registration, namespace and token documentation is maintained at:

- <https://central.sonatype.org/register/central-portal/>
- <https://central.sonatype.org/register/namespace/>
- <https://central.sonatype.org/publish/generate-portal-token/>

### Portal login troubleshooting

Use the current Publisher Portal at `central.sonatype.com`; do not use the retired OSSRH, Jira, `oss.sonatype.org`, or `s01.oss.sonatype.org` login pages for this publishing path.

When **Continue with GitHub** does not complete or returns to the sign-in page:

1. open a private/incognito browser window;
2. allow cookies and pop-ups for both `central.sonatype.com` and `github.com`;
3. temporarily disable strict tracking protection or content blockers for those two sites;
4. confirm that the GitHub account has an accessible, verified email address;
5. retry the GitHub login using the `carstenartur` account rather than a different personal or organization identity.

The Portal also supports a dedicated username/password account. GitHub social login is preferred here because it normally provisions the verified `io.github.carstenartur` namespace automatically. If login succeeds but that namespace is absent, or if the redirect loop remains after the browser checks above, contact `central-support@sonatype.com` and include:

- the account email address;
- GitHub username `carstenartur`;
- expected namespace `io.github.carstenartur`;
- the exact error, timestamp, browser and a screenshot.

Never send support a password, Portal token, PGP private key, private-key backup, or GitHub secret value.

## One-time signing-key setup

Central requires detached PGP signatures for the POM, primary JAR, source JAR and Javadoc JAR of every published component.

Create a dedicated release-signing key on an administrator workstation:

```bash
gpg --full-generate-key
gpg --list-secret-keys --keyid-format=long
```

Use a signing-capable RSA key with a strong passphrase and a maintained identity. Publish the public key to a keyserver supported by Central, for example:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <FINGERPRINT>
```

Export the transferable secret key in ASCII-armored form:

```bash
gpg --armor --export-secret-keys <FINGERPRINT> > central-private-key.asc
```

Store the complete file contents and its passphrase as repository Actions secrets:

```text
MAVEN_CENTRAL_GPG_PRIVATE_KEY
MAVEN_CENTRAL_GPG_PASSPHRASE
```

After confirming that the secrets were saved, securely remove the exported file. Do not add it to Git, a release artifact, a build cache or a Maven settings file.

Central's signing requirements and GPG guidance are maintained at:

- <https://central.sonatype.org/publish/requirements/>
- <https://central.sonatype.org/publish/requirements/gpg/>

### Browser-only setup with GitHub Codespaces

Do not use an arbitrary web-based OpenPGP key generator: the page can observe or retain the private key. A safer browser-only option is a private GitHub Codespace, where the commands execute in an isolated terminal and the private key can be transferred directly into repository Actions secrets without placing it in the repository or workflow logs.

1. Open this repository on GitHub, choose **Code → Codespaces**, and create a Codespace for the Central publishing branch.
2. Open its browser terminal and check that GnuPG and GitHub CLI are available:

   ```bash
   gpg --version || (sudo apt-get update && sudo apt-get install -y gnupg)
   gh auth status
   ```

3. Generate a dedicated two-year signing key without placing the passphrase in shell history:

   ```bash
   read -r -p "Signing name: " KEY_NAME
   read -r -p "Signing email: " KEY_EMAIL
   read -r -s -p "Signing passphrase: " KEY_PASSPHRASE
   echo

   gpg --batch --pinentry-mode loopback \
     --passphrase "$KEY_PASSPHRASE" \
     --quick-generate-key \
     "$KEY_NAME <$KEY_EMAIL>" rsa3072 sign 2y

   FINGERPRINT=$(gpg --batch --with-colons --list-secret-keys "$KEY_EMAIL" \
     | awk -F: '$1 == "fpr" { print $10; exit }')
   test -n "$FINGERPRINT"
   printf 'Signing-key fingerprint: %s\n' "$FINGERPRINT"
   ```

4. Publish only the public key to a Central-supported keyserver:

   ```bash
   gpg --keyserver keyserver.ubuntu.com --send-keys "$FINGERPRINT"
   ```

5. Export the private key to a temporary file and write both values directly to repository Actions secrets:

   ```bash
   gpg --batch --pinentry-mode loopback \
     --passphrase "$KEY_PASSPHRASE" \
     --armor --export-secret-keys "$FINGERPRINT" \
     > /tmp/central-private-key.asc

   gh secret set MAVEN_CENTRAL_GPG_PRIVATE_KEY \
     --repo carstenartur/jgit-storage-hibernate \
     < /tmp/central-private-key.asc

   printf '%s' "$KEY_PASSPHRASE" \
     | gh secret set MAVEN_CENTRAL_GPG_PASSPHRASE \
         --repo carstenartur/jgit-storage-hibernate
   ```

6. Keep an encrypted offline backup of the key and its revocation certificate in administrator-controlled secure storage. Then remove the temporary export and shell variables:

   ```bash
   shred -u /tmp/central-private-key.asc 2>/dev/null \
     || rm -f /tmp/central-private-key.asc
   unset KEY_PASSPHRASE KEY_NAME KEY_EMAIL FINGERPRINT
   ```

7. Delete the Codespace after confirming the two GitHub secrets exist. Never expose the private key through a workflow artifact, issue comment, pull-request comment, terminal screenshot or Actions log.

The browser/Codespaces path only handles the signing key. The Portal login and Portal user token are still required for `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`.

## Repository implementation

The root POM exposes two opt-in profiles:

- `central-release` attaches source and Javadoc JARs, signs every deployable artifact with `maven-gpg-plugin`, and uses Sonatype's Central Publisher Portal Maven plugin;
- `github-packages` supplies the GitHub Packages `distributionManagement` entries for snapshots and the optional secondary release copy.

The Central plugin is configured to:

- use server ID `central`;
- automatically publish after validation;
- wait until the deployment reaches `published`;
- support `-Dcentral.skipPublishing=true` for a signed local bundle dry run.

Signing uses the Maven GPG plugin's Bouncy Castle signer. It reads only these environment variables:

```text
MAVEN_GPG_KEY
MAVEN_GPG_PASSPHRASE
```

No secret is stored in `pom.xml` or `settings.xml`.

## Credential-free release-contract test

The normal Maven workflow contains a `Maven Central release contract` job. It derives the release version from the current `-SNAPSHOT`, runs the same release script in dry-run mode, generates a short-lived local signing key, and creates a Central bundle without uploading it.

The bundle verifier requires:

- the parent POM and its signature;
- the primary, source and Javadoc JARs for every public JAR module;
- a signature for every POM and JAR;
- release coordinates without `SNAPSHOT`.

This job requires no Central or GitHub Packages credentials. It validates the release mechanics, not ownership of the Central namespace.

A local equivalent is:

```bash
CURRENT_VERSION=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version)
RELEASE_VERSION=${CURRENT_VERSION%-SNAPSHOT} \
DRY_RUN=true \
SKIP_TESTS=true \
PUBLISH_GITHUB_PACKAGES=false \
SOURCE_BRANCH=$(git branch --show-current) \
  .github/scripts/release.sh
```

A full dry run omits `SKIP_TESTS=true` and therefore also requires Docker for the PostgreSQL Testcontainers suite. The script intentionally prepares the release version and documentation in the current checkout before it validates the bundle. A local dry run therefore leaves uncommitted release-preparation changes; run it in a disposable worktree or reset those changes after inspection.

## Performing a real release

Run the existing **Release** workflow from `main` with:

```text
release_version = X.Y.Z
next_development_version = X.Y.Z-SNAPSHOT  # optional
skip_tests = false
dry_run = false
publish_github_packages = true             # optional secondary copy
```

The release script:

1. validates versions, branch, metadata and all required secret presence before mutation;
2. prepares the release version and public documentation;
3. runs the complete Maven test suite;
4. creates and validates the signed Central bundle;
5. uploads it to the Central Publisher Portal and waits for publication;
6. verifies every public module from a new empty Maven repository with all GitHub and Central credentials removed from the process environment;
7. optionally publishes the same release version to GitHub Packages;
8. creates and pushes the release commit, tag and GitHub Release;
9. advances the repository to the next development snapshot.

A real release cannot skip tests. The first Central release should use a new immutable version; do not rebuild an existing GitHub-Packages release under the same coordinates.

## Anonymous consumer verification

After Central reports the deployment as published, the release process runs:

```bash
.github/scripts/verify-central-consumption.sh X.Y.Z
```

The script uses:

- an empty temporary local Maven repository;
- a settings file with no servers, mirrors or credentials;
- no GitHub, Central or signing environment variables;
- a consumer POM with every public JAR module;
- an explicit resolution check for the published parent POM.

It fails if Maven contacts GitHub Packages or if any public artifact is absent. The same command can be run later from any clean checkout.

## Failure and recovery

Central artifacts are immutable. If Central publication succeeds but a later GitHub operation fails, inspect the Central deployment, GitHub Packages, Git tag and GitHub Release before retrying. Never rebuild different bytes with the same release version.

GitHub Packages is secondary. It can be disabled for a release with `publish_github_packages=false`; this does not affect anonymous Maven Central consumption.

Rotate a Central token or signing key by replacing the repository secrets. When rotating the signing key, publish the new public key before the next release and keep enough audit information to identify which key signed each historical release.
