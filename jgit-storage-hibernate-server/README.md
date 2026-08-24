# jgit-storage-hibernate server

The server turns the library modules into a normal Git Smart HTTP endpoint backed by PostgreSQL. It targets versioned application data, audit queries and integration tests rather than reproducing GitLab or Gitea collaboration features.

## Run the published image

Set required secrets and start the release image plus PostgreSQL from the repository root:

```bash
export JSH_DATABASE_PASSWORD='replace-with-a-long-random-database-password'
export JSH_ADMIN_USERNAME='admin'
export JSH_ADMIN_PASSWORD='replace-with-a-long-random-admin-password'
export JSH_IMAGE='ghcr.io/carstenartur/jgit-storage-hibernate-server:latest'

docker compose pull
docker compose up -d
curl --fail http://localhost:8080/actuator/health/readiness
curl --fail-with-body \
  -u "$JSH_ADMIN_USERNAME:$JSH_ADMIN_PASSWORD" \
  -X POST http://localhost:8080/api/repositories/demo

git clone http://localhost:8080/git/demo.git
```

Use a full `X.Y.Z` tag or digest instead of `latest` for production and stable test suites.

To build the current checkout instead of pulling a published image:

```bash
docker compose -f compose.yaml -f compose.build.yaml up --build -d
```

## Compatibility boundary

| Capability | Standalone image |
|---|---:|
| Normal clone/fetch/push over Git Smart HTTP | Yes |
| Generic CI or IDE plugin that only needs a Git URL and Basic authentication | Usually |
| Drop-in image/configuration replacement for another Git server | No |
| GitLab/Gitea/Bitbucket vendor API compatibility | No |
| SSH, Git LFS, pull requests, issues, web UI, arbitrary hooks or runtime plugins | No |
| Multi-user administration | No; this image currently has one Basic-auth administrator |

The compatibility guarantee is at the standard Git Smart HTTP protocol boundary, not at another product's Docker, environment-variable, filesystem or REST-API boundary. The ready-made image is a bounded single-admin service.

## Search and inspection

A successful push schedules a coalesced, bounded rebuild of the generic Search projection. Query it through:

```text
GET /api/repositories/{name}/changes
GET /api/repositories/{name}/index-status
POST /api/repositories/{name}/reindex
```

Supported query parameters include `text`, `author`, `committer`, `path`, `pathMode`, `from`, `to` and `limit`.

The PostgreSQL deployment also creates read-only inspection views:

```text
jsh_inspection.repository
jsh_inspection.reflog
jsh_inspection.commit_history
jsh_inspection.commit_change
```

These views are the standalone server's inspection contract. Core's pack/chunk tables remain internal storage details and are not promoted as a public relational Git object model.

## Complete operating guide

See [Standalone Docker/OCI Git server](../docs/operations/server-image.md) for image tags, every environment variable, persistence and backup behavior, TLS requirements, API endpoints, local builds and the detailed compatibility matrix.
