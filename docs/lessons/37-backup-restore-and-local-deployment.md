# Lesson 37 — Back up, restore and run the local application

## What this lesson delivers

Build the browser into the Spring Boot JAR and run that application with its private Node engine from the existing checkout. Back up a PostgreSQL database, restore it into a new isolated database, and verify content, credentials and workspace permissions through the actual packaged application.

This finishes the planned local lesson roadmap. The `dev` profile remains loopback-only; this is a local deployment, with development provisioning and bootstrap access. It is not an Internet deployment or a production availability claim. Rich text keeps its default-off gate and the limitations documented in Lesson 27.

## Package and start

Use PowerShell 7, the installed Java 24/Maven, Node 22 and PostgreSQL 18 tools. From the repository root:

```powershell
./ops/deploy/build-local.ps1
./ops/deploy/start-local.ps1
```

The build installs lockfile dependencies with `npm ci`, builds the private engine and frontend, then runs Maven with the `bundled-ui` profile. `-SkipInstall` can reuse an already installed lockfile dependency tree. Maven's build step skips test execution; verification is a separate command. Build only when the existing application is stopped. The build clears compiled class directories, preserving recovery archives and isolated database directories elsewhere in `target`.

The JAR at `backend/target/collaborative-workspace-0.0.1-SNAPSHOT.jar` includes the built frontend and shared schema contract. The engine still runs separately from `experiments/yjs-private-engine`, including its installed dependencies. This is deployment from the checkout, not a self-contained installation package for a machine without Node or Java.

Use the stored accounts and shared workspaces provisioned in Lessons 9–13. The `learner` principal is for development provisioning/diagnostics, not an ordinary workspace account. There is no public signup flow. Open an existing plain-text document, or create one with the document API's `PLAIN_TEXT_V1` format. The current browser's rich-document creation remains gated, as documented in Lesson 27.

The launcher dot-sources the existing ignored `backend/.env.local.ps1` without printing its values. It starts only its own hidden child processes, waits for readiness, enables local static UI serving and the asynchronous search indexer, and points the browser WebSocket Origin to the same backend. Open `http://127.0.0.1:8080`. Optional `-Port 18080 -EnginePort 18091` selects different ports. Both services bind to loopback. Redis is optional and disabled in this single-instance launch. The rich-text gate remains off.

`-SettingsPath` can select another trusted local settings script; it must never point to an untrusted script. `-CheckOnly` starts both services, verifies API and bundled UI readiness, then stops them. The isolated recovery drill tests that mode against the recovered database.

Ctrl+C ends this launch and its private engine. Logs live in `backend/target/local-app.log` and `local-engine.log`, with `.err` companion files. A port conflict fails readiness; it does not authorize terminating an unrelated process. The application launcher does not start or stop the regular PostgreSQL Windows service.

The new `workspace.local-ui.enabled` property permits anonymous GETs only to `/`, `/index.html`, `/assets/**` and `/sw.js` in the dev profile. APIs still require sessions, CSRF and current membership. Management metrics retain bootstrap-only access. Without the dev profile the application remains closed by default. Static files never contain the database or bootstrap password. The existing service worker caches only the built shell and hashed assets.

## Back up the regular database

Stop schema migrations during a backup. Ordinary document edits may continue: [PostgreSQL SQL dumps](https://www.postgresql.org/docs/18/backup-dump.html) use a consistent snapshot. A later edit can legitimately be absent from that snapshot.

From the repository root, choose a new archive filename in an existing private directory:

```powershell
. ./backend/.env.local.ps1
$databaseUri = [uri]($env:WORKSPACE_DB_URL.Substring(5))
$env:PGHOST = '127.0.0.1'
$env:PGPORT = [string]$databaseUri.Port
$env:PGUSER = $env:WORKSPACE_DB_USER
$env:PGPASSWORD = $env:WORKSPACE_DB_PASSWORD
try {
    ./ops/recovery/backup.ps1 -Database $databaseUri.AbsolutePath.TrimStart('/') `
        -Archive 'C:/PrivateBackups/workspace-2026-11-16.dump'
} finally { Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue }
```

Create and restrict `C:/PrivateBackups` yourself before using that example. `WORKSPACE_PG_BIN` can override the default `C:/Program Files/PostgreSQL/18/bin`. Scripts accept only loopback connections. PostgreSQL credentials are inherited through the child environment rather than command arguments. Do not print that environment or check backup files into Git.

`backup.ps1` uses custom-format `pg_dump`, a five-second lock wait limit, and `--no-owner --no-acl`. It writes to a unique partial path, checks the archive's table of contents, then moves it into place without overwriting an existing archive. A JSON sidecar records command start/completion times, byte size and SHA-256. This detects accidental corruption, not malicious replacement of both files. The dump contains document data, account email addresses and password hashes; it is not encrypted by this script. Store archive and sidecar together under appropriate access controls, encryption and off-machine retention.

PostgreSQL roles, their passwords, OS configuration, the private engine, application secrets, browser-local drafts and in-memory HTTP sessions are not in this application database dump. Browser drafts pending confirmation are not server backups. `--no-owner --no-acl` deliberately requires the restore operator to choose destination ownership. Workspace roles live in application tables and are included.

## Restore into a new destination

Use only your own trusted archive. Restoring SQL can execute database code; inspect unknown archives before use. The [pg_restore documentation](https://www.postgresql.org/docs/18/app-pgrestore.html) explains this boundary.

Set `PGHOST`, `PGPORT`, `PGUSER` and `PGPASSWORD` for a local database administrator with permission to create a database and assume the selected application role. Do not give the running application `CREATEDB` merely to operate backups. Then:

```powershell
$destination = 'collaborative_workspace_restore_' + [Guid]::NewGuid().ToString('N').Substring(0,16)
./ops/recovery/restore-isolated.ps1 -Archive 'C:/PrivateBackups/workspace-2026-11-16.dump' `
    -Database $destination -OwnerRole workspace_app
```

Use the real destination application role name; omit `-OwnerRole` only when the connection role should own restored objects. The administrator/application roles must already exist. Restore verifies the sidecar before creating anything and accepts only the generated restore-name pattern. It creates a new database from `template0`, restores in a single transaction with exit-on-error, then runs `ANALYZE`. There is no `--clean`, `DROP DATABASE`, automatic overwrite or automatic cutover. A restore error can leave a newly created empty database for inspection; retry with a new unique name.

Point a separate local application at the new database, verify it, and only then plan a cutover. Restored HTTP sessions are intentionally unavailable: users log in again. Default running application credentials do not have database-creation privileges on the regular local server.

## Repeat the isolated recovery drill

```powershell
./ops/recovery/verify-local.ps1
```

`-SkipBuild` uses the JAR already built by the deployment build script. The drill uses installed PostgreSQL binaries to create a separate, password-protected native instance in a unique `backend/target/recovery-*` directory, on a temporary loopback port. It neither modifies the existing development/test databases nor replaces the regular server. It creates an unprivileged application role; a separate drill administrator creates the databases and controls restore ownership. The administrator password is generated randomly, passed to `initdb` through a temporary file, and that file is deleted after initialization.

The packaged JAR and private engine then run on temporary ports. Chromium checks three phases:

1. **Seed:** provision synthetic owner, viewer and outsider accounts; create a plain-text document through the same-origin API; save text through the bundled browser; create a checksum-verified checkpoint and named revision; wait for authorized search indexing.
2. **Advance after backup:** save a later edit that is intentionally outside the backup snapshot.
3. **Verify recovery:** start the same JAR against the restored database; reject an old session cookie; log in with restored password hashes; verify original text, checkpoint and revision digests; confirm viewer read-only behavior and forbidden mutation with valid CSRF; deny outsider document/history/search access; save a new edit and reopen it.

The script stops its own application, engine and isolated PostgreSQL processes in `finally`. The temporary session manifest is removed. Private database files, archive and logs remain in the ignored drill directory for inspection. Do not run `mvn clean` while a drill is active or while its only backup copy is under `target`; move durable backups to a protected destination outside build output.

Run `./ops/recovery/test-guards.ps1 -Archive '<drill-directory>/workspace.dump'` to verify corruption rejection, protected-database name rejection, existing-archive refusal and non-loopback rejection. These checks must fail before any database connection; they use an unreachable test port deliberately.

## Recovery objectives and rollback

**RPO** is the amount of committed work you can afford to lose. With periodic dumps, changes since the most recent usable snapshot can be lost; there is no point-in-time recovery or WAL archive here. Choose a backup cadence based on that loss budget and test off-machine copies. The drill demonstrates this by excluding the post-backup edit while retaining the saved revision.

**RTO** is time until the service is usable after recovery begins. The drill records dump duration, database restore duration, and restore-start-to-browser-verified duration in `results.json`. That last measurement includes JAR startup and verification, with the private engine and isolated PostgreSQL already running. It excludes provisioning a replacement machine, archive transfer, incident investigation and external traffic cutover. A tiny fixture's result is not a production RTO promise.

For an application rollback, stop the current launch, check out the desired reviewed Git commit in a separate directory, install its lockfiles and build it. Preserve the prior JAR, engine version and configuration together. First verify schema compatibility against an isolated restored database. Flyway migrations are forward changes; do not automatically delete migration rows, downgrade a live schema or assume an older application can read a newer schema. If database rollback is necessary, plan a verified restore and explicitly account for edits committed after that backup. Keep the original database intact until the replacement is accepted.

Configuration changes, distributed session failover, continuous off-machine backup automation, unrestricted capacity and rich-text production security remain separate work. The local roadmap's completion does not certify those properties.

## Interview practice

**Why does surviving an application restart not prove disaster recovery?** PostgreSQL can reload committed data from the same intact disk after a restart. A lost disk, corrupted database or lost machine requires an independent usable backup, the required secrets/configuration and a verified replacement environment.

**Does a matching dump checksum prove recovery works?** It proves the bytes match the recorded file. A restore can still fail because of missing roles, incompatible software, insufficient privileges or incomplete operational configuration. The browser drill tests those application-level consequences as well as the database restore.

**Why retain the original database during cutover?** A restored copy might omit later edits or have unexpected permission/schema problems. Keeping the source intact allows investigation and a controlled fallback. A fallback still needs an explicit plan for writes accepted by either side; it is not safe to merge divergent databases automatically.

## Recorded evidence (2026-11-16)

See [the sanitized results artifact](../../ops/recovery/lesson37-results.json). The final drill archived 38,445 bytes: dump 0.43 seconds, restore 2.62 seconds, restore-start to browser-verified recovery 19.59 seconds. All three Chromium phases passed; the packaged launcher readiness check passed; an attempted restore over the existing destination was refused without changing its document sequence. Four additional script guards passed. The normal PostgreSQL development database was not modified, and the drill server's PID file was absent after shutdown.

The private engine's 31 tests passed. Broad regression verification includes database, browser/offline, search, native transport, overload and Redis inter-instance checks. An initial run with indexing enabled during the rich structural workload and a concurrent recovery drill rejected edit 46 with `ENGINE_UNAVAILABLE` (45 of 80 accepted); this is not a green result or a reason to increase limits. The rich-text gate remains off. That configuration's observed contention is retained as capacity evidence; isolated canonical browser and search checks are verified separately.

Final verification contains 169 passing Java cases across the broad suite and isolated browser reruns, with zero failures/errors/skips in the latest per-class reports. The canonical group passed 21 development browser scenarios plus three production offline scenarios; the separate indexing-enabled group passed two search scenarios. The isolated structural workload accepted all 80 edits. The 31 private-engine tests, three packaged recovery phases, launcher smoke check and four script guards passed. These grouped results do not erase the initial combined-run failure described above.
