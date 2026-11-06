# Lesson 32 — Active-session access revocation (in progress)

A membership change and an edit must have a defined order. Existing update and membership transactions already lock the workspace before checking current role and then locking a document. A write ordered after removal or demotion cannot use a role captured before engine validation. Socket writers also share a session guard with logout/session destruction.

The first Lesson 32 slice extends that same guard to HTTP checkpoint publication, named revision creation and revision restore. Each controller obtains the guard from the live session registry before calling its service. Engine validation stays outside the guard. The service holds it only around the complete publication transaction, including commit. Logout's session-destruction listener uses the same guard even when the session has no WebSocket connection.

If logout completes while engine validation is paused, the later publication rechecks the invalid session and returns 403 without changing history. If a valid publication has already started under the guard, logout waits for that transaction to finish and then revokes the session. A successful engine response alone cannot authorize publication. Guard acquisition fails closed if the session expires during acquisition and removes a partially created registry entry.

Real HTTP/engine/database tests exercise both orderings for restore. Existing checkpoint, revision, role-change and socket logout tests cover the shared paths. The initial focused integration run passed **20 tests**. Two additional unit tests passed, checking that HTTP and socket publishers use the identical guard and that invalidation fails closed.

The remaining work is active membership-change invalidation, explicit bounded read reauthorization behavior (including failure to refresh), regression tests and the final review. Already received data and locally cached drafts cannot be recalled. This lesson is not complete, and the default-off rich-text and local-only engine limitations remain.

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
$env:WORKSPACE_RUN_YJS_ENGINE_TESTS = 'true'
mvn -B '-Dtest=DocumentUpdateEndToEndTest,WorkspaceSocketIntegrationTest,DocumentFormatGuardTest,SessionPublicationGuardTest' test
```

**Why not hold the session lock during engine validation?** Validation may wait for another process. Keeping it outside lets logout cancel work that has not begun publishing and keeps the ordered critical section small.

**Does logout erase a received document?** No. It ends future authorized operations. A browser may retain prior content, which is why local storage is not described as private from others using the same browser profile.
