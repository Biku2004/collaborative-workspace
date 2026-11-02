# Lesson 15 — Read and list authorized documents

> [Lesson 16](16-rename-and-archive-documents.md) adds document version and archived fields. Archived documents remain available through direct member-authorized reads, but normal lists now exclude them.

## 1. What we are building

Alice and Bob belong to different workspaces. Alice can read documents in her workspace, including when she is a viewer. Knowing the UUID of Bob's document must not let her read it. This lesson implements one-document reads and paginated workspace lists, using the session identity and the current membership stored in PostgreSQL.

The invariant is: **a document is returned only if its workspace has a membership row for the signed-in user**. The rule applies to owners, editors and viewers. Unassigned pre-workspace documents are preserved but cannot be read through these routes.

## 2. Request flow and files

```text
HTTP GET + session cookie
  -> Spring Security authenticates the session
  -> controller takes identity from Authentication
  -> service resolves stored user ID and validates pagination
  -> repository queries documents joined to workspace_members
  -> 200 with metadata, or 404 if not accessible
```

Read the code in this order (paths are relative to the repository root):

1. `backend/src/main/java/com/example/collaborativeworkspace/DocumentController.java` and `WorkspaceDocumentController.java`: HTTP routes, authenticated identity, and `Cache-Control: no-store` on successful reads.
2. `DocumentService.java`: resolves the user from the session, applies page bounds and converts query results into a page.
3. `DocumentRepository.java`: SQL that joins membership to the document or workspace. The authorization check is part of the query that retrieves the data.
4. `DocumentCursor.java` and `DocumentPage.java`: cursor encoding/validation and response structure.
5. `backend/src/main/resources/db/migration/V5__index_workspace_document_pages.sql`: creates an index on `(workspace_id, created_at, id)` for the list ordering.
6. `WorkspaceIntegrationTest.java` and `LegacyDocumentMigrationTest.java`: the behavior and migration checks.

Open these files to see the complete implementation; the important SQL ideas are explained below. The controller does not accept a user ID from the request. Spring's `Authentication` represents the established session; the database decides current membership.

## 3. Reading one document

`GET /api/documents/{id}` joins `documents` to `workspace_members` using workspace ID and filters by both document ID and authenticated user ID. A document with no workspace has no matching membership. A nonmember and a caller requesting a nonexistent document both receive 404, so the response does not confirm whether another user's document exists. An anonymous request receives 401.

Membership is checked when the database query executes. A read already in flight can finish around a concurrent removal; this lesson does not implement stronger revocation synchronization for responses already being sent.

## 4. Listing and keyset pagination

`GET /api/workspaces/{workspaceId}/documents` returns:

```json
{
  "items": [
    { "id": "00000000-0000-0000-0000-000000000001", "workspaceId": "00000000-0000-0000-0000-000000000002", "title": "Notes", "createdAt": "2026-01-01T00:00:00Z", "version": 0, "archived": false }
  ],
  "nextCursor": null
}
```

`limit` defaults to 20 and must be between 1 and 100. If another page exists, `nextCursor` is a string; send it back as `cursor` to request the next page. An empty workspace gives an empty `items` array and `nextCursor: null`. Missing or inaccessible workspace gives 404. A malformed cursor, a cursor for a different workspace, or an out-of-range limit gives 400.

Documents are ordered by `(created_at, id)`. The UUID is a tie-breaker when two documents share the same creation timestamp. For the next page the query uses `(created_at, id) > (last_created_at, last_id)` and requests `limit + 1` rows: the extra row determines whether another page exists. The composite index matches the workspace filter and ordering.

The query starts with the membership row and uses a left join to documents. No membership means no rows and therefore 404; an existing membership without matching documents gives an empty page. This includes the case where a valid cursor is past the final document.

The cursor is an opaque position for clients, not a password or proof of authorization. It is encoded, not encrypted or signed, and can be altered. Each request independently checks membership. Pages are separate reads, not a frozen snapshot: concurrent inserts or deletions can change what later pages contain.

## 5. Try it in PowerShell

Start the server and log in as a stored user using [Lesson 12](12-create-workspaces.md); follow [Lesson 14](14-workspace-document-creation.md) to create at least one document. Continue in that PowerShell session with `$base`, `$session`, `$workspaceId`, and `$document` set:

```powershell
# Read the document you created.
Invoke-RestMethod "$base/api/documents/$($document.id)" -WebSession $session

# Ask for one document per page.
$page = Invoke-RestMethod "$base/api/workspaces/$workspaceId/documents?limit=1" -WebSession $session
$page.items
$page.nextCursor

# Run only when a second page exists.
if ($null -ne $page.nextCursor) {
    $next = Invoke-RestMethod "$base/api/workspaces/$workspaceId/documents?limit=1&cursor=$($page.nextCursor)" -WebSession $session
    $next.items
}
```

For a negative check, request `/api/documents/{random UUID}` with the same session: expect 404. Request a document in a workspace where your user is not a member: expect the same 404. Do not assume a workspace ID or document ID alone grants access. These are GETs; unlike POST, they do not require a CSRF header.

## 6. Run the tests

Use the configured **separate test database** and the existing local PostgreSQL server. From `backend` in PowerShell:

```powershell
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
mvn -B test
```

The integration tests exercise owner/editor/viewer access, anonymous and outsider denial, empty lists, invalid parameters, tied timestamps across pages, and access after membership removal. The migration test creates and removes its own randomly named test schema, checks that a preexisting document survives, and checks that the repository cannot read it as a workspace member. Flyway applies V5 automatically on the next application startup.

## 7. Questions you might be asked

**Why check membership in SQL instead of trusting the frontend?** Anyone can send requests directly. The server must enforce access, and joining the membership in the retrieval query makes the data and access decision part of the same database statement.

**Why use 404 rather than 403 for a nonmember?** The two responses can reveal different information. Here 404 deliberately treats nonexistent and inaccessible documents alike. Other operations may use 403 when the caller is already known to be a member but lacks a particular permission.

**Why include the ID in the ordering?** Timestamps are not necessarily unique. Without a stable tie-breaker, equal timestamps can cause documents to be skipped or repeated across pages.

**Why use keyset pagination rather than offset?** The database can seek from an indexed position instead of repeatedly skipping an increasingly large prefix. It also handles changes before the cursor more predictably. It does not provide a historical snapshot or random access to page numbers.

**Can someone forge a cursor?** Yes. Encoding is not a signature. A forged valid position changes where listing starts, but the membership query still controls the data available. Add signing only if a later requirement needs tamper evidence.

**Why don't we assign old documents to the first user?** Their correct owner cannot be inferred safely. Automatic assignment could disclose private information. A future explicit recovery process must decide how to identify and approve their owner.

**Is this production-ready?** No. The user/session flow is a local development setup, and this lesson does not add a frontend, production deployment, rate limiting, a shared session store, or real-time collaboration. No latency targets have been measured.

## 8. Checkpoint and exercise

You can now create and read workspace documents with membership checks and page through their metadata. Exercise: create two documents, request `limit=1`, and explain why the second request returns a different document. Then remove a viewer and observe the next read return 404. Next is Lesson 16 in the [roadmap](../07-next-lessons-roadmap.md).
