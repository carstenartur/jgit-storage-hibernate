# jgit-storage-hibernate server

The standalone server exposes a normal JGit Smart HTTP remote while storing Git packs, refs and
reflogs in PostgreSQL. Successful pushes also update the searchable history projection. It is aimed
at versioned application data, auditable history and integration environments rather than at
reproducing a complete forge such as GitLab, Gitea or GitHub.

## Compatibility at a glance

The image is **Git protocol compatible**, not **forge- or plug-in compatible**.

| Existing dependency | Replacement status |
|---|---|
| Git CLI, JGit or an IDE using authenticated Smart HTTP clone/fetch/push | Compatible |
| A service that only needs an HTTP Git remote | Usually compatible after changing the remote URL and credentials |
| GitLab, Gitea, Gerrit, Bitbucket or GitBucket REST APIs and plug-ins | Not compatible |
| SSH remotes, Git LFS or a mounted directory of bare repositories | Not provided |
| Pull requests, issues, web code review, repository browsing, hooks or forge webhooks | Not provided |
| Multi-user forge identity and administration | Not provided by this single-admin server image |

Replacing a plain Smart HTTP server can therefore be practical. Replacing a forge requires an
explicit feature and data migration; changing only the Docker image is not sufficient.

## Run the published release

The repository Compose file defaults to the released `0.11.2` image:

```text
ghcr.io/carstenartur/jgit-storage-hibernate-server:0.11.2
```

From the repository root, create a private local configuration and set both passwords:

```bash
cp .env.example .env
chmod 600 .env
${EDITOR:-vi} .env

docker compose config --quiet
docker compose pull postgres git-server
docker compose up --no-build -d
curl --fail http://localhost:8080/actuator/health/readiness
```

Create and clone a repository without placing credentials in shell history, process arguments or the
remote URL. Both commands prompt for the configured administrator password:

```bash
curl --user admin --request POST \
  http://localhost:8080/api/repositories/demo

git clone http://localhost:8080/git/demo.git
```

Use the username configured by `JSH_ADMIN_USERNAME` when it is not `admin`. Git credential helpers
can store credentials for repeated operations; do not embed a password in a checked-in remote URL.

Stop the deployment without deleting data:

```bash
docker compose down
```

Delete the named volumes only when the PostgreSQL Git history and local search projection are meant
to be destroyed:

```bash
docker compose down --volumes
```

## Choose an image reference deliberately

A numeric version tag is the normal stable choice. The publication workflow treats it as
first-write immutable: a rerun must find the same source revision, version label, architectures and
manifest digest or it fails instead of overwriting the tag.

`latest` and `edge` are moving compatibility aliases for the newest published release. They are
convenient for evaluation, but not suitable for reproducible deployments or tests.

For the strongest supply-chain pin, use the manifest digest recorded by the release workflow or GHCR:

```bash
JSH_SERVER_IMAGE='ghcr.io/carstenartur/jgit-storage-hibernate-server:0.11.2@sha256:<manifest-digest>' \
  docker compose pull git-server
```

Set `JSH_SERVER_IMAGE` in `.env` to keep that selection for subsequent Compose commands.

The image supports `linux/amd64` and `linux/arm64`, runs as a non-root user, contains an OCI SBOM and
provenance attestation, and exposes container port `8080`.

## Build the current source instead

Published-image deployment and source builds are intentionally separated. The base Compose file has
no build section, so `docker compose up` cannot silently replace a release image with a local build.
Use the development override explicitly:

```bash
docker compose -f compose.yaml -f compose.build.yaml \
  up --build -d postgres git-server
```

The override builds `jgit-storage-hibernate-server:local`. Continue to supply both Compose files for
commands that address that development deployment:

```bash
docker compose -f compose.yaml -f compose.build.yaml down
```

The root `.dockerignore` excludes Git metadata, credentials, build outputs and IDE/runtime state from
the build context. BuildKit also retains the Maven dependency cache between local builds.

## Configuration

### Compose-only settings

| Variable | Default | Purpose |
|---|---|---|
| `JSH_SERVER_IMAGE` | released numeric image | Exact tag or digest to run |
| `JSH_DATABASE_PASSWORD` | none; required | PostgreSQL and application database password |
| `JSH_ADMIN_PASSWORD` | none; required | Single administrator Basic-authentication password |
| `JSH_BIND_ADDRESS` | `127.0.0.1` | Host address that publishes port `8080` |
| `JSH_PORT` | `8080` | Published host port |

### Application settings

| Variable | Default | Purpose |
|---|---|---|
| `JSH_JDBC_URL` | `jdbc:postgresql://localhost:5432/jgit` | PostgreSQL JDBC URL |
| `JSH_JDBC_USERNAME` | `jgit` | Database user |
| `JSH_JDBC_PASSWORD` | `jgit` outside Compose | Database password |
| `JSH_ADMIN_USERNAME` | `admin` | Basic-authentication user |
| `JSH_ADMIN_PASSWORD` | none; startup validation rejects blank values | Required password for authenticated use |
| `JSH_DEFAULT_BRANCH` | `main` | Symbolic `HEAD` target for new repositories |
| `JSH_SEARCH_DIRECTORY` | `/var/lib/jgit-storage/search` | Persistent Lucene directory |
| `JSH_INDEXING_THREADS` | `2` | Bounded projection worker count, from 1 through 16 |
| `JSH_INSPECTION_VIEWS_ENABLED` | `true` | Create read-only PostgreSQL inspection views |
| `JSH_REQUIRE_SECURE_TRANSPORT` | `false` | Reject credentials received over requests that are not considered secure |
| `JSH_FORWARD_HEADERS_STRATEGY` | `NONE` | Spring forwarded-header handling; use `FRAMEWORK` only behind a trusted proxy |
| `JSH_MANAGEMENT_ENDPOINTS` | `health,info` | Publicly exposed Actuator endpoints; metrics are opt-in |
| `PORT` | `8080` | HTTP port inside the container |

## Production deployment boundaries

The included Compose topology is a hardened single-node reference deployment, not a complete
production forge or cluster orchestrator.

- It binds to loopback by default and requires explicit non-empty database and administrator
  passwords. The server container is read-only apart from `/tmp` and the search volume, drops Linux
  capabilities, prevents privilege escalation and runs as a non-root user.
- Terminate TLS at the container or a trusted reverse proxy before exposing Git credentials. Behind a
  proxy, block direct client access, set `JSH_FORWARD_HEADERS_STRATEGY=FRAMEWORK`, and then set
  `JSH_REQUIRE_SECURE_TRANSPORT=true`. Trusting forwarded headers while the container is directly
  reachable allows clients to spoof them.
- PostgreSQL contains the authoritative Git packs, refs and reflogs and must be backed up
  transactionally. The Lucene volume should be persisted for normal restarts but is a derived index
  that can be rebuilt.
- The standalone image currently has one administrator identity. Applications needing users, groups,
  per-repository grants, protected refs or token lifecycle should integrate the Security and Smart
  HTTP modules rather than treating this image as a multi-user forge.
- `health` and `info` are the only management endpoints exposed by default. Protect the management
  network before opting into `metrics` or additional Actuator endpoints.

## Search and inspection capabilities

A successful push schedules a coalesced, bounded rebuild of the generic Search projection. Query and
operate it through:

```text
GET  /api/repositories/{name}/changes
GET  /api/repositories/{name}/index-status
POST /api/repositories/{name}/reindex
```

Supported query parameters include `text`, `author`, `committer`, `path`, `pathMode`, `from`, `to`
and `limit`.

The PostgreSQL deployment also creates read-only inspection views:

```text
jsh_inspection.repository
jsh_inspection.reflog
jsh_inspection.commit_history
jsh_inspection.commit_change
```

These views are the standalone server's relational inspection contract. Core's pack/chunk tables are
internal storage details and are not promoted as a public relational Git object model.
