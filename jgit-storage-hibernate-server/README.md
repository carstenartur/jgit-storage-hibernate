# jgit-storage-hibernate server

The server turns the library modules into a normal Git Smart HTTP endpoint backed by PostgreSQL.
It deliberately targets versioned application data, audit queries and integration tests rather than
trying to reproduce GitLab or Gitea collaboration features.

## Run

From the repository root:

```bash
docker compose up --build -d
curl --fail http://localhost:8080/actuator/health/readiness
curl -u admin:change-me -X POST http://localhost:8080/api/repositories/demo

git clone http://admin:change-me@localhost:8080/git/demo.git
```

Change the demonstration credentials before exposing the service outside a local machine. Production
deployments should terminate TLS at the container or a trusted reverse proxy and set
`JSH_REQUIRE_SECURE_TRANSPORT=true` once the servlet request correctly reflects the original secure
connection.

## Extra capabilities

A successful push schedules a coalesced, bounded rebuild of the generic Search projection. Query it
through:

```text
GET /api/repositories/{name}/changes
GET /api/repositories/{name}/index-status
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

These views are the standalone server's inspection contract. Core's pack/chunk tables remain internal
storage details and are not promoted as a public relational Git object model.
