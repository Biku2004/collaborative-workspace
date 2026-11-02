# Lesson 16 — Rename and archive documents

## 1. The problem

Imagine Alice and Bob both open a document called “Planning”. They both see version **0**. Alice renames it to “Launch plan” and saves. If Bob then saves the old version as “Ideas”, should Alice's change silently disappear? In this lesson, the answer is **no**: Bob gets `409 Conflict` and must reload the document before deciding what to do.

An owner or editor can rename or archive a document. A viewer can read but cannot change it. Archiving is a logical state change: the row remains stored, and members can still read it by its direct document ID, but it disappears from the ordinary workspace document list. There is no restore, archived-documents list, deletion, document-content editing, or frontend in this lesson. Archive a test document only; there is no restore endpoint yet.

## 2. Concepts first

- **Metadata** describes a document: in this lesson its title and archived state. It is separate from the rich-text content we will build later.
- **Optimistic locking** assumes conflicting writes are uncommon. A client sends the version it observed; the database changes the row only if that version is still current.
- **Transaction** groups the permission check and write. Our transaction uses a workspace row lock shared with membership changes, so a write that waits behind a committed removal must recheck the new membership.
- **HTTP 409** means the requested operation conflicts with the current state. Here it means a stale metadata version or an archived document; a client should reload and present the current state rather than blindly overwrite it.

The guarantees depend on all membership writers and metadata writers using the same workspace lock, and on metadata writers including the version check. Do not bypass these rules with an ad hoc update. Workspace locking is simple for this learning checkpoint, but serializes workspace writes and could limit throughput; any finer-grained design must preserve the revocation guarantee and a consistent lock order.

## 3. API contract

| Action | Request | Success |
|---|---|---|
| Rename | `PATCH /api/workspaces/{workspaceId}/documents/{documentId}` with `{"title":"Launch plan","version":0}` | `200` and the updated document with version `1` |
| Archive | `POST /api/workspaces/{workspaceId}/documents/{documentId}/archive` with `{"version":1}` | `200`, `archived: true`, version `2` |

Both require a stored-user session, owner/editor membership and a valid CSRF token. A title must be nonblank, at most 200 characters, and contain no NUL; surrounding whitespace is stripped. The version must be present and nonnegative. Bad input is `400`; missing or inaccessible document/workspace is `404`; a viewer with access to the document gets `403`; stale version or already archived is `409`. Anonymous requests with a valid CSRF token get `401`; missing CSRF can be rejected as `403` before authentication. Successful document responses use `Cache-Control: no-store`.

Document creation returns `version: 0` and `archived: false`. A successful rename or archive increments the version, even if a rename uses the same title. Archive is not idempotent in this contract: a repeat returns `409`, including when the current version is sent. Fetch the latest document again after a conflict. A successful write followed by a lost response is ambiguous: reload before deciding whether to retry.

An archived document remains readable using `GET /api/documents/{id}` for current members and returns `archived: true`. The normal `GET /api/workspaces/{id}/documents` list filters it out. A nonmember still receives `404`. Renaming an archived document returns `409`. Archived documents are not silently reassigned or deleted, and unassigned legacy documents remain inaccessible.

## 4. Implementation, step by step

The complete code is in these project files:

1. `backend/src/main/resources/db/migration/V6__document_metadata_version_and_archive.sql` adds `version BIGINT NOT NULL DEFAULT 0` and `archived BOOLEAN NOT NULL DEFAULT false`. Existing documents keep their titles and receive these defaults, including preserved legacy rows. Flyway records the migration once; do not edit an applied migration.
2. `Document.java` exposes both fields. `RenameDocumentRequest.java` and `ArchiveDocumentRequest.java` use Jakarta validation to validate JSON input; `Long` instead of primitive `long` distinguishes a missing version from zero.
3. `WorkspaceDocumentController.java` maps the two workspace-scoped routes, validates the request, uses the server-side session identity, and returns the updated document.
4. `DocumentService.java` starts a `READ_COMMITTED` transaction, obtains the workspace lock, checks the current membership and document's workspace, and rejects viewers. It then invokes the conditional update. Nonmembers and missing resources get the same not-found response.
5. `DocumentRepository.java` uses parameterized SQL for the updates. The list query filters `NOT archived` **inside the left join**, keeping the distinction between an empty authorized workspace and an inaccessible workspace. The single-document read does not exclude archived rows.
6. `DocumentConflictException.java` and `ApiExceptionHandler.java` translate a failed conditional update to HTTP 409. The service has already checked that the document belongs to the workspace and that the actor can edit it.
7. `WorkspaceIntegrationTest.java` exercises HTTP access, conflicts and concurrency; `LegacyDocumentMigrationTest.java` verifies migration of both old unassigned and already-scoped records.

The core rename statement has this shape:

```sql
UPDATE documents
SET title = ?, version = version + 1
WHERE workspace_id = ? AND id = ? AND version = ? AND NOT archived
RETURNING id, workspace_id, title, created_at, version, archived;
```

The `?` values are bound parameters, not SQL assembled from the title. `UPDATE ... WHERE version = ?` and `version = version + 1` execute atomically. If two clients both submit version 0, at most one can change that version; the other receives 409. In this implementation the workspace lock also orders those operations and membership changes, but it does not replace the version comparison: after waiting, a client's submitted version may still be stale.

## 5. Try it in PowerShell

Start the application using the local development setup in [Lesson 12](12-create-workspaces.md). Log in as a stored owner or editor and create a **new test document** using [Lesson 14](14-workspace-document-creation.md). Continue in the same PowerShell 7 session with `$base`, `$session`, `$headers`, `$workspaceId` and `$document` already set. The login steps refresh the CSRF token after login. `Invoke-RestMethod` calls below change the development database.

```powershell
# Inspect the document and its current version.
$document = Invoke-RestMethod "$base/api/documents/$($document.id)" -WebSession $session
$document | Format-List id, title, version, archived

# Rename using the exact version you just read.
$url = "$base/api/workspaces/$workspaceId/documents/$($document.id)"
$oldVersion = $document.version
$renameBody = @{ title = 'Launch plan'; version = $oldVersion } | ConvertTo-Json -Compress
$renamed = Invoke-RestMethod -Method Patch $url -WebSession $session -Headers $headers -ContentType 'application/json' -Body $renameBody
$renamed | Format-List title, version, archived

# Reusing the old version must fail with HTTP 409.
try {
    Invoke-RestMethod -Method Patch $url -WebSession $session -Headers $headers -ContentType 'application/json' -Body $renameBody
} catch {
    Write-Host "Expected conflict: $([int]$_.Exception.Response.StatusCode)"
}

# Archive with the CURRENT version. There is no restore endpoint yet.
$archiveBody = @{ version = $renamed.version } | ConvertTo-Json -Compress
$archived = Invoke-RestMethod -Method Post "$url/archive" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $archiveBody
$archived | Format-List title, version, archived

# Direct read still works for members; the regular list excludes this document.
Invoke-RestMethod "$base/api/documents/$($document.id)" -WebSession $session
(Invoke-RestMethod "$base/api/workspaces/$workspaceId/documents" -WebSession $session).items
```

Expected for a newly created document: version 0, then renamed version 1, a `409` for the stale retry, then archived version 2 with `archived: True`. Existing documents may start with a higher version; use the versions returned by the API.

## 6. Run the tests

From `backend` in PowerShell, with the dedicated test database already configured:

```powershell
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
mvn -B test
```

The tests use the separate local PostgreSQL test database. They check allowed and denied roles, CSRF, invalid input, stale versions, two simultaneous version-matched renames (one succeeds, one conflicts), archived read/list behavior, and a rename waiting for membership removal. Migration tests use isolated temporary schemas to verify that legacy and existing scoped documents survive. Flyway applies V6 automatically when the development application next starts; running the test suite migrates the test database only.

## 7. Common mistakes

- **Sending an old version:** fetch the latest document and ask the user how to resolve the conflicting intent.
- **Using the version returned before a successful rename to archive:** use the new version in the response.
- **Missing CSRF after login:** obtain a fresh token for the logged-in session as in Lesson 12.
- **Assuming archive means deletion:** the record remains, direct member reads work, and there is currently no restore or archive browser.
- **Putting `NOT archived` in the SQL `WHERE` clause:** that would eliminate the null document row from the left join and can make an empty workspace look inaccessible; it belongs in the join condition.

## 8. Interview questions

**What prevents a lost update?** The conditional update matches the document's current version and increments it atomically. An older version affects no rows and becomes a 409, so the client can resolve the conflict instead of overwriting another writer.

**Why not only read the version and then update?** Another request can update between those separate operations. A version predicate in the actual SQL update closes that gap.

**When is a version check simpler than a CRDT?** For occasional changes to a single metadata field, explicit conflict and user resolution can be easy to reason about. Rich-text collaboration has different requirements: many concurrent fine-grained edits, offline work, and automatic convergence; an optimistic metadata version does not implement those features.

**Why lock the workspace before checking membership?** Membership changes use the same lock. If a removal commits first, a waiting mutation checks membership after the removal and is denied. If the mutation gets the lock first, it may commit before removal. This is an ordering rule, not a guarantee that every in-flight response vanishes instantly.

**Why does archive need a version?** An archive can race with a rename or another archive. Requiring the observed version prevents acting silently on metadata that changed since the user last saw it.

**What is the performance cost?** Every document mutation and membership change in a workspace shares a lock, so busy workspaces can experience contention. Measure before optimizing, and retain permission rechecks and a safe lock order in any redesign.

**Does 409 tell me whether the title or archive state changed?** No. This response deliberately gives a general conflict; an authorized client can GET the document to inspect its latest state. A removed member will receive 404.

## 9. Checkpoint

You can create, read, list, rename and archive workspace documents with role checks and version conflict detection. Exercise: log in as two editors, read the same version in both sessions, rename from one session, and confirm the second session receives 409. The next lesson is [Lesson 17 — Learn Yjs with two local replicas](../07-next-lessons-roadmap.md).
