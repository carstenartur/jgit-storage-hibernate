# jgit-storage-hibernate server

The standalone server exposes a normal JGit Smart HTTP remote while storing Git packs, refs and
reflogs in PostgreSQL. Successful pushes also update the searchable history projection. It is aimed
at versioned application data, auditable history and integration environments rather than at
reproducing a complete forge such as GitLab, Gitea or GitHub.

## Run the published release

The repository Compose file defaults to the immutable `0.11.2` server image:

```text
ghcr.io/carstenartur/jgit-storage-hibernate-server:0.11.2
```

From the repository root:

```bash
export JSH_DATABASE_PASSWORD='replace-database-password'
export JSH_ADMIN_USERNAME='admin'
export JSH_ADMIN_PASSWORD='replace-admin-password'

docker compose pull postgres git-server
docker compose up --no-build -d
curl --fail http://localhost:8080/actuator/health/readiness

curl -u "$JSH_ADMIN_USERNAME:$JSH_ADMIN_PASSWORD" \
  -X POST http://localhost:8080/api/repositories/demo

git clone \
  "http://$JSH_ADMIN_USERNAME:$JSH_ADMIN_PASSWORD@localhost:8080/git/demo.git"
```

`latest` and `edge` are compatibility aliases for the newest published release. Stable deployments
and repeatable tests should use the numeric tag. Override the Compose default deliberately with
`JSH_SERVER_IMAGE`, for example:

```bash
JSH_SERVER_IMAGE=ghcr.io/carstenartur/jgit-storage-hibernate-server:0.11.2 \
  docker compose up --no-build -d
```

The image supports `linux/amd64` and `linux/arm64`, runs as a non-root user and exposes port `8080`.
PostgreSQL data and the Lucene directory must both be persisted.

## Build the current source instead

The same Compose topology can build the checked-out source:

```bash
docker compose build git-server
docker compose up --no-build -d postgres git-server
```

This is the development path. It does not change the documented public image tag and should not be
confused with an immutable release image.

## Git-server compatibility

The server is **Git protocol compatible**, not **forge-plugin compatible**.

A standard Git CLI, JGit client or IDE can use the Smart HTTP URL for ordinary clone, fetch and push.
Applications that only need a remote Git endpoint can therefore switch their remote URL without
learning a project-specific transport protocol.

It is not a drop-in replacement for GitLab, Gitea, Gerrit, Bitbucket Server, GitBucket or similar
Docker images when an installation depends on their plug-in APIs or collaboration features. In
particular, this server does not currently provide:

- SSH transport, Git LFS or filesystem-mounted bare repositories;
- pull requests, issues, web-based code review or repository browsing;
- forge-specific webhooks, server-side hook directories or plug-in extension points;
- compatibility with plug-ins that expect another product's database schema or internal APIs.

A migration from a plain Smart HTTP server is practical when clients only clone/fetch/push and the
Basic-authentication model fits. A migration from a forge requires an explicit feature and data
migration rather than only replacing the container image.

## Configuration

| Environment variable | Default | Purpose |
|---|---|---|
| `JSH_JDBC_URL` | `jdbc:postgresql://localhost:5432/jgit` | PostgreSQL JDBC URL |
| `JSH_JDBC_USERNAME` | `jgit` | Database user |
| `JSH_JDBC_PASSWORD` | `jgit` | Database password |
| `JSH_ADMIN_USERNAME` | `admin` | Basic-authentication user |
| `JSH_ADMIN_PASSWORD` | empty in the application | Required password for authenticated use |
| `JSH_DEFAULT_BRANCH` | `main` | Symbolic `HEAD` target for new repositories |
| `JSH_SEARCH_DIRECTORY` | `/var/lib/jgit-storage/search` | Persistent Lucene directory |
| `JSH_INDEXING_THREADS` | `2` | Bounded projection worker count |
| `JSH_INSPECTION_VIEWS_ENABLED` | `true` | Create read-only PostgreSQL inspection views |
| `JSH_REQUIRE_SECURE_TRANSPORT` | `false` | Reject credentials received over non-secure requests |
| `PORT` | `8080` | HTTP port inside the container |

Change all demonstration credentials before exposing the service. Production deployments should
terminate TLS at the container or a trusted reverse proxy and set
`JSH_REQUIRE_SECURE_TRANSPORT=true` once the application receives the original secure-request state
correctly.

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
