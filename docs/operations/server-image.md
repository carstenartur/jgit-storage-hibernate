# Standalone Docker/OCI Git server

The `jgit-storage-hibernate-server` image exposes a PostgreSQL-backed Git Smart HTTP endpoint plus a small repository-administration and searchable-history API.

Its product boundary is deliberately narrow:

> Push with normal Git. Store Git objects and refs transactionally in PostgreSQL. Query who changed what, where and when.

It is a useful replacement for a simple HTTP Git remote when consumers only need the standard Git protocol. Drop-in compatibility stops at that protocol boundary. It is **not** an image-, configuration- or vendor-API-compatible replacement for GitLab, Gitea, Bitbucket Server or another collaboration platform.

## Compatibility contract

| Consumer expectation | Supported by the standalone image? | Notes |
|---|---:|---|
| `git clone`, `git fetch`, `git pull` and `git push` over Smart HTTP | Yes | Repository URL: `/git/{repository}.git` |
| JGit clients using the same Smart HTTP URL | Yes | Uses JGit's `GitServlet` and normal upload/receive-pack protocol |
| CI or IDE integration that only needs a Git remote URL and Basic authentication | Usually | The integration must not depend on a vendor-specific REST API |
| Replacing another Git-server image without changing Compose variables, volumes or paths | No | This image has its own PostgreSQL, search-index and administration contract |
| GitLab, Gitea, GitHub Enterprise or Bitbucket REST/API clients | No | No vendor API emulation |
| SSH Git transport or SSH keys | No | Smart HTTP only |
| Git LFS | No | LFS endpoints and object storage are not implemented |
| Pull requests, issues, web UI, organizations, runners or webhooks | No | These are outside the server's scope |
| Arbitrary server plugins or server-side Git hooks | No | No runtime plugin or hook ABI is exposed |
| Multi-user administration in the standalone image | No | The first standalone mode has one Basic-auth administrator |

The library modules offer lower-level security and embedding APIs for applications that need a richer identity or authorization model. The ready-made standalone image intentionally remains a bounded single-admin deployment.

## Use a published image

Create an `.env` file next to `compose.yaml` and choose non-demo secrets:

```dotenv
JSH_DATABASE_PASSWORD=replace-with-a-long-random-database-password
JSH_ADMIN_USERNAME=admin
JSH_ADMIN_PASSWORD=replace-with-a-long-random-admin-password

# Evaluation follows the most recent immutable release:
JSH_IMAGE=ghcr.io/carstenartur/jgit-storage-hibernate-server:latest
```

Start PostgreSQL and the Git server:

```bash
docker compose pull
docker compose up -d
curl --fail http://localhost:8080/actuator/health/readiness
```

Create a logical repository, then use it as a normal Git remote:

```bash
curl --fail-with-body \
  -u "$JSH_ADMIN_USERNAME:$JSH_ADMIN_PASSWORD" \
  -X POST http://localhost:8080/api/repositories/demo

git clone http://localhost:8080/git/demo.git
```

The Git client prompts for the configured Basic username and password. Avoid embedding long-lived credentials in the URL, shell history or committed configuration.

For production, replace `latest` with an immutable full version or digest, for example:

```dotenv
JSH_IMAGE=ghcr.io/carstenartur/jgit-storage-hibernate-server:0.11.2
```

or:

```dotenv
JSH_IMAGE=ghcr.io/carstenartur/jgit-storage-hibernate-server@sha256:<manifest-digest>
```

## Build the image from the current checkout

The normal `compose.yaml` deliberately consumes a published image. Development builds are explicit through the override file:

```bash
export JSH_DATABASE_PASSWORD=development-database-password
export JSH_ADMIN_PASSWORD=development-admin-password
export JSH_IMAGE=jgit-storage-hibernate-server:local

docker compose -f compose.yaml -f compose.build.yaml up --build -d
```

This separation prevents an apparently reproducible production deployment from silently rebuilding whatever source happens to be present in a working directory.

## Image tags

| Tag | Mutability | Intended use |
|---|---|---|
| `X.Y.Z` | Immutable release identity | Production and stable integration tests |
| `vX.Y.Z` | Immutable alias of the same release | Direct correspondence with the Git tag |
| `latest` | Moving | Evaluation of the newest released version |
| `edge` | Moving | Current `main`; development and compatibility testing only |
| `sha-<commit>` | Commit-addressable | Diagnosing or pinning a particular `main` build |

Release images are built from the corresponding `vX.Y.Z` Git tag, not from the later snapshot branch. `latest` is moved only by release publication. `edge` and `sha-*` are published after the server-image smoke test passes on `main`.

The publication workflows attach OCI source, revision, license and description labels, publish Linux AMD64 and ARM64 manifests, and request build provenance and an SBOM. They finally log out of GHCR and perform an anonymous pull so that an accidentally private package is detected rather than advertised as public.

## Configuration reference

| Variable | Default | Purpose |
|---|---|---|
| `JSH_IMAGE` | `ghcr.io/carstenartur/jgit-storage-hibernate-server:latest` | Image or digest selected by Compose |
| `JSH_PORT` | `8080` | Host port mapped to container port 8080 |
| `JSH_DATABASE_PASSWORD` | none; required by Compose | PostgreSQL password and server JDBC password |
| `JSH_JDBC_URL` | `jdbc:postgresql://localhost:5432/jgit` inside the application | JDBC location; Compose supplies the service URL |
| `JSH_JDBC_USERNAME` | `jgit` | JDBC username |
| `JSH_JDBC_PASSWORD` | `jgit` in the bare application | JDBC password; Compose supplies the required database secret |
| `JSH_ADMIN_USERNAME` | `admin` | Single standalone Basic-auth administrator |
| `JSH_ADMIN_PASSWORD` | none; required by Compose | Administrator password |
| `JSH_DEFAULT_BRANCH` | `main` | Initial symbolic default branch for new repositories |
| `JSH_SEARCH_DIRECTORY` | `/var/lib/jgit-storage/search` | Lucene projection directory |
| `JSH_REQUIRE_SECURE_TRANSPORT` | `false` | Reject authenticated API and Git requests unless the servlet sees a secure connection |
| `JSH_INDEXING_THREADS` | `2` | Bounded asynchronous projection workers |
| `JSH_INSPECTION_VIEWS_ENABLED` | `true` | Create the documented read-only PostgreSQL inspection views |
| `PORT` | `8080` | Internal Spring Boot HTTP port when running the image without the supplied Compose file |

## Persistence, restart and backup

The Compose deployment creates two named volumes:

- `postgres-data` contains the authoritative Git packs, refs, reflog and relational projection data;
- `search-data` contains the Lucene index used for full-text queries.

`docker compose down` stops and removes the containers but retains both volumes. `docker compose down -v` permanently removes them and therefore deletes the repositories. Do not use `-v` as routine shutdown.

Back up PostgreSQL as the authoritative state. The Lucene directory is derived and can be rebuilt with the reindex endpoint, although retaining it avoids the rebuild cost after a restore.

## Git and administration endpoints

| Method and path | Purpose |
|---|---|
| `POST /api/repositories/{name}` | Create a logical repository |
| `GET /api/repositories` | List repositories and projection state |
| `DELETE /api/repositories/{name}` | Delete a repository when no local handle is open |
| `GET /api/repositories/{name}/changes` | Query indexed history |
| `GET /api/repositories/{name}/index-status` | Read projection status |
| `POST /api/repositories/{name}/reindex` | Schedule a full projection rebuild |
| `/git/{name}.git` | Standard Git Smart HTTP clone/fetch/push endpoint |
| `GET /actuator/health/readiness` | Unauthenticated readiness probe |

History-query parameters include `text`, `author`, `committer`, `path`, `pathMode`, `from`, `to` and `limit`.

When inspection views are enabled, PostgreSQL exposes the read-only contract:

```text
jsh_inspection.repository
jsh_inspection.reflog
jsh_inspection.commit_history
jsh_inspection.commit_change
```

Core pack/chunk tables are internal storage details and are not a stable public relational Git-object API.

## Production deployment requirements

The built-in Basic authentication is appropriate for a controlled first deployment, not for exposing credentials over plain HTTP. Terminate TLS in the container platform or a trusted reverse proxy. Configure the servlet container so `request.isSecure()` reflects the original HTTPS connection, then enable:

```dotenv
JSH_REQUIRE_SECURE_TRANSPORT=true
```

Do not trust a caller-controlled forwarding header directly. Also configure request-body, connection, idle and execution-time limits at the proxy or platform, size the PostgreSQL connection pool for concurrent Git and indexing work, protect the database and search volumes, and monitor both readiness and failed projection status.

For internet-facing multi-user collaboration, SSH keys, LFS, pull requests or vendor-compatible APIs, use a collaboration server designed for those capabilities or embed the library modules in an application that supplies the missing identity and product layer.
