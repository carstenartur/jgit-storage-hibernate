# Publish a release to Maven Central

Run Maven from the root of a release checkout with the same non-SNAPSHOT version
in all modules. Use JDK 21 and Maven 3.9.x. The `maven-central` profile binds
the Maven GPG plugin to `verify` and the official Sonatype publishing plugin to
`deploy`. Maven builds, signs and publishes the reactor. These commands work
in a local checkout or any CI environment.

| Operation | Maven command |
|---|---|
| Build and sign a local bundle | `mvn -Pmaven-central clean deploy` |
| Upload and wait for validation, then confirm publication in the Portal | `mvn -Pmaven-central -Dcentral.skipUpload=false clean deploy` |
| Publish automatically and wait for Sonatype to report publication | `mvn -Pmaven-central -Dcentral.skipUpload=false -Dcentral.autoPublish=true -Dcentral.waitUntil=published clean deploy` |

Bundle-only mode writes `target/central-publishing/central-bundle.zip`. For an
upload, Maven logs the Sonatype deployment ID. Use this ID to check an
interrupted publication before submitting another deployment.
[Central Portal deployments](https://central.sonatype.com/publishing/deployments)

## Signing and authentication

For uploads, configure server `central` in Maven settings with the two values
of a Central Portal user token for your verified namespace:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>${env.CENTRAL_USERNAME}</username>
      <password>${env.CENTRAL_PASSWORD}</password>
    </server>
  </servers>
</settings>
```

Locally Maven uses the GnuPG keyring by default. Select a key with
`-Dgpg.keyname=FULL_FINGERPRINT` when needed. For an ASCII-armored private key
supplied in `MAVEN_GPG_KEY`, add `-Dgpg.signer=bc` to the same Maven command.
Supply its passphrase in `MAVEN_GPG_PASSPHRASE` and, when needed, its primary
fingerprint in `MAVEN_GPG_KEY_FINGERPRINT`. The passphrase must match the key
exactly. Publish the public key on a Central-supported keyserver.
[Maven GPG plugin](https://maven.apache.org/plugins/maven-gpg-plugin/usage.html) ·
[Sonatype signing requirements](https://central.sonatype.org/publish/requirements/gpg/)

Existing keys from the Codespace setup are usable. Keep `private-key.asc` and
`revocation.rev` in private storage and retain the passphrase separately.
The optional helper for creating or backing up a key is
`.github/scripts/setup-central-signing-key.sh`.

## API compatibility baseline

The release build compares the public API to the version in
`api.baseline.version`. For the first Central release, the previous release
is available in the project's static Maven repository. In an environment
without GitHub access, point Maven at a local copy of that Maven repository
or an internal mirror, for example by adding
`-Dapi.baseline.repository.url=file:///srv/maven/jgit-releases`.
This keeps the compatibility check active. Maven also needs its regular
build dependencies and plugins through a local cache or accessible repositories.

## Release automation

After release finalization the GitHub Release workflow checks out the exact
merged release commit, configures Maven server `central`, and runs the automatic
publication command above with the BC signer. It reads the existing repository
secrets `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `MAVEN_GPG_KEY` and
`MAVEN_GPG_PASSPHRASE`. It maps the optional legacy secret
`MAVEN_GPG_FINGERPRINT` to the Maven GPG plugin's fingerprint variable.

The separate `public-repository-release` profile remains available to stage
the static Maven repository. Activate one publication profile per Maven build.
[Project release process](release-process.md) ·
[Sonatype Maven plugin](https://central.sonatype.org/publish/publish-portal-maven/)
