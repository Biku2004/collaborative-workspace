# Lesson 14: Move document creation into a workspace

> Historical checkpoint: [Lesson 15](15-read-and-list-authorized-documents.md) subsequently enabled membership-authorized document reads and lists.

## 1. What we built

New documents are now created **inside a workspace**. A current workspace `OWNER` or `EDITOR` can create one; a `VIEWER` cannot. The server checks the signed-in user's current membership and inserts the document in one transaction.

Documents created before workspaces existed are preserved as **unassigned legacy documents**. We do not know who owns them, so we do not silently assign them to anyone. They cannot be read through the application until a deliberate recovery and assignment process is designed. The old unscoped document creation route has been removed. For this lesson, the old unscoped GET returns 404 for every document, including newly created ones; authorized document reading and listing are Lesson 15.

This is still the local development application. Workspace membership and write permissions do not make the system production-ready.

## 2. Why this matters

Before this lesson, the endpoint `POST /api/documents` created a document without a workspace. Any authenticated development user could retrieve a document if they knew its ID. That is not an acceptable tenant boundary once we introduce workspace membership.

The new flow is:

```text
POST /api/workspaces/{workspaceId}/documents
   │ authenticated stored user + CSRF token
   ▼
validate title; get user identity from session
   ▼
begin database transaction
   │ resolve stored user ID
   │ lock workspace row
   │ recheck current membership and role
   │ insert document with workspace_id
   ▼
commit → 201 Created
```

The user ID and role do not come from the request body. The workspace ID in the URL identifies a requested resource; it is not evidence of permission.

## 3. What can each role do?

| Caller | Create document in this workspace? | Response |
|---|---|---|
| Owner | Yes | 201 |
| Editor | Yes | 201 |
| Viewer | No | 403 |
| Signed-in nonmember | No | 404 |
| Anonymous client | No | 401 for a request with a valid anonymous CSRF token; a missing token can cause 403 first |
| Temporary `learner` bootstrap account | No | 403; it is not a workspace user |

The generic 404 for a nonmember also applies to a nonexistent workspace; it avoids revealing whether a workspace exists. CSRF protection remains enabled.

## 4. The database migration and legacy data

`V4__scope_documents_to_workspaces.sql` adds a nullable `workspace_id` foreign key to `documents` and an index. PostgreSQL gives pre-existing rows `NULL` for the new column when no default is specified. [PostgreSQL `ALTER TABLE`](https://www.postgresql.org/docs/current/sql-altertable.html)

The migration also adds `CHECK (workspace_id IS NOT NULL) NOT VALID`. In PostgreSQL this leaves old rows unvalidated, while enforcing the check on **new inserts and updated rows**. Thus:

- Old documents are kept with `workspace_id = NULL`; they are not accessible through the current API.
- New inserts must specify a valid workspace ID, even if a developer accidentally tries an unscoped SQL insert.
- Updating an old unassigned row without assigning a workspace will fail the check. A future, explicitly authorized recovery process must assign it safely before updating it.

This is a transitional schema. A database operator can still see legacy data directly; the API restriction is not encryption. We have not created an administrative recovery endpoint or decided which user should own an old document. Before a real production migration, take and test backups, account for locks and migration time, and plan the recovery procedure.

On this computer, we applied V3 and V4 to the dedicated **development** database after testing. Its one pre-existing document remained present and unassigned. The verification application was then stopped.

## 5. Code to read

The complete implementation is in `backend`:

1. `src/main/resources/db/migration/V4__scope_documents_to_workspaces.sql` — adds the foreign key, transitional check and index.
2. `WorkspaceDocumentController.java` — exposes the workspace-scoped POST. It accepts a validated title and obtains the actor from Spring Security. It returns 201 without a `Location` header because there is no document GET endpoint in this lesson.
3. `DocumentService.java` — starts a `READ_COMMITTED` transaction, resolves the stored user, locks the workspace and checks their current role **after the lock**, then inserts the document. Viewers get 403; a nonmember or missing workspace gets 404.
4. `DocumentRepository.java` — uses a parameterized insert that includes `workspace_id`. The `Document` response record now includes `workspaceId`.
5. `DocumentController.java` — no longer offers unscoped POST. Its old GET deliberately returns 404 without looking up or revealing a document.
6. `WorkspaceIntegrationTest.java` — exercises roles, CSRF, validation, old routes, and a revocation race. `LegacyDocumentMigrationTest.java` creates an isolated test schema at migration V3, inserts a legacy document, applies V4, checks preservation and the new-insert constraint, and removes its own schema.

### Why lock the workspace?

Lesson 13's membership changes lock the workspace row before updating a member. This lesson's document creation uses that same lock before checking the member's role. If a revocation commits first, a waiting creation sees the updated membership and is rejected. If creation commits first, it was authorized at that earlier point in the ordering. This requires **all relevant application writers** to use the same lock convention; it does not protect against direct database writes or future code that bypasses the convention.

## 6. API contract

```http
POST /api/workspaces/{workspaceId}/documents
Content-Type: application/json
X-CSRF-TOKEN: <token>

{"title":"Sprint notes"}
```

An authorized owner or editor receives 201 with a body like:

```json
{"id":"<uuid>","workspaceId":"<workspace-uuid>","title":"Sprint notes","createdAt":"<timestamp>"}
```

The response is marked `Cache-Control: no-store`. Titles must be nonblank, at most 200 Java UTF-16 code units, and contain no NUL character. Invalid inputs return 400.

The old `POST /api/documents` is no longer mapped and returns 404. The old `GET /api/documents/{id}` returns 404 to an authenticated caller regardless of whether the document exists. Do not interpret this temporary 404 as deletion: the database row remains. Document read and list endpoints are the next lesson.

## 7. Try it locally

Start the server as described in [Lesson 12](12-create-workspaces.md). In a second PowerShell 7 window, follow that lesson's stored-user login steps to obtain `$base`, `$session`, and a fresh CSRF `$headers`. List your workspaces and create a document in one where you are an owner or editor:

```powershell
$workspaces = Invoke-RestMethod "$base/api/workspaces" -WebSession $session
$workspaceId = $workspaces[0].id
$body = @{ title = 'Sprint notes' } | ConvertTo-Json -Compress
$document = Invoke-RestMethod -Method Post "$base/api/workspaces/$workspaceId/documents" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $body
$document
```

Select the intended workspace rather than blindly using the first one if you have several. This creates a real row in your development database. The document's ID is returned, but the API cannot retrieve it yet. Stop the server with Ctrl+C when finished.

## 8. Verification

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
mvn -B test
```

The suite passed **13 tests, 0 failures**, against the separate local PostgreSQL test database. The tests cover owner and editor creation, viewer and nonmember denial, anonymous/CSRF behavior, invalid titles, correct workspace ID, and closure of old unscoped routes. A controlled race holds an editor-removal transaction open while a document request waits; after removal commits, the request is rejected and no document is inserted.

The migration test starts with a V3 schema and an existing unscoped row, applies V4, verifies the old title and null workspace ID, and confirms a new unscoped insert is rejected. It uses and drops only its own randomly named schema inside the test database. Database integration tests are skipped unless `WORKSPACE_RUN_DB_TESTS=true`.

## 9. Interview questions

**Why not automatically put old documents in the first workspace?** There is no trustworthy ownership information linking them to that workspace. Guessing could expose another person's data. Preserve them and require an explicit recovery decision.

**Why not just check the role before starting the transaction?** A user could be revoked between the check and the insert. Locking the same workspace row as membership changes and rechecking the role establishes an ordering.

**Why return 404 to a nonmember and 403 to a viewer?** A nonmember should not learn whether the workspace exists. A viewer is already a member and can know that they lack permission to write.

**What does `NOT VALID` mean here?** PostgreSQL does not scan and validate existing rows when the constraint is added, but it still checks new inserts and updated rows. This enables an intentional legacy-data transition.

**Does a successful create mean every user can read the document?** No. It only means an authorized write was committed. Reading and listing require their own membership-aware checks in Lesson 15.

## 10. Exercise and next step

Create a document as an owner, then try as a viewer in another session. Explain why the old GET returns 404 even for the owner today. Next, Lesson 15 will add authorized document reads and lists, including tests that a user from another workspace cannot retrieve a document by guessing its ID.
