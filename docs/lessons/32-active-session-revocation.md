# Lesson 32 — Active-session access revocation

Status: **local learning criteria complete**; the project retains its local-only and default-off rich-text limitations.

A membership change and an edit must have a defined order. Existing update and membership transactions already lock the workspace before checking current role and then locking a document. A write ordered after removal or demotion cannot use a role captured before engine validation. Socket writers also share a session guard with logout/session destruction.

The first Lesson 32 slice extends that same guard to HTTP checkpoint publication, named revision creation and revision restore. Each controller obtains the guard from the live session registry before calling its service. Engine validation stays outside the guard. The service holds it only around the complete publication transaction, including commit. Logout's session-destruction listener uses the same guard even when the session has no WebSocket connection.

If logout completes while engine validation is paused, the later publication rechecks the invalid session and returns 403 without changing history. If a valid publication has already started under the guard, logout waits for that transaction to finish and then revokes the session. A successful engine response alone cannot authorize publication. Guard acquisition fails closed if the session expires during acquisition and removes a partially created registry entry.

Real HTTP/engine/database tests exercise both orderings for restore. Existing checkpoint, revision, role-change and socket logout tests cover the shared paths. The initial focused integration run passed **20 tests**. Two additional unit tests passed, checking that HTTP and socket publishers use the identical guard and that invalidation fails closed.

## Active membership changes

`WorkspaceMembershipService` publishes an internal `WorkspaceAccessReduced` event when a member is removed or loses a role capability. `WorkspaceAccessInvalidator` handles it only after transaction commit. A rolled-back change never invalidates a connection. Same-role assignments and promotions do not cause needless disconnects.

The room hub marks every matching workspace/user peer revoked before attempting any transport close. This covers joined rooms and connections that completed HELLO without joining a document. Queued frames are discarded and sockets receive policy close 1008. A slow first close cannot leave the other matched peers authorized. A connection to a different workspace under the same HTTP session stays valid. This closes document connections; it does not log the user out of other workspaces.

Every outgoing reliable or presence frame still checks current membership/document access at dequeue. Membership notification is a latency improvement, not an authority. A handshake racing a membership change must reauthorize at HELLO and JOIN. The periodic head poll checks idle workspace connections too, and closes on missing access or failed reauthorization. A missed callback, direct database change or another instance cannot make a new permission query grant removed access. Cross-instance notification is Lesson 35.

## Read-revocation window

There is no cached permission lease in this version. Socket authorization queries run again before each send. Their results are usable for at most **five seconds from lookup start**, measured with a monotonic clock. The hub checks both elapsed time and the revocation/session flag after the query returns. A lookup that began before revocation and returns too late is discarded; it cannot renew authority. Head and format lookups use the same bound. A failed head refresh closes the connection instead of continuing to deliver with old authorization.

The bound concerns **authorization of a frame**, not when a remote browser receives bytes. One frame per socket may already have passed its check and be in flight when revocation commits; bytes already given to the socket/network cannot be recalled. A process pause or blocked network close can delay physical disconnection. Previously received content, browser caches and in-flight HTTP responses authorized before removal are also outside recall. New HTTP requests query current membership. No end-user network-delivery deadline is claimed.

Writes remain stricter: they recheck current role under the same workspace lock held by membership changes. Validation may finish after revocation, but the candidate cannot commit under the old role. Current HTTP snapshot/revision/restore publication also uses the shared session guard described above.

## Verification and compact review

A real socket/database test performs removal inside a transaction and rolls it back, then confirms the room still works. It commits demotion and checks that joined and idle target connections close without another client message, while the same user's other workspace remains usable. The demoted user can reconnect as a viewer but cannot upload. Removal then closes that viewer connection too. Existing races check a write validated before demotion and logout before/after publication.

Unit tests advance an injected monotonic clock to exercise late authorization results without slow sleeps. They invalidate access during a successful lookup, block a transport close while checking all matched peers are already revoked, and simulate failed/stale head refreshes with no content sent. The browser regression now requires rich editing to stop as soon as demotion closes its connection.

A full browser run also exposed a delayed native-selection regression. The earlier queued presence redraw could run before the browser delivered `selectionchange`, restoring the old model selection and replacing selected content on the next keystroke. A new deterministic harness blocks that notification temporarily: the previous code produces “!” instead of “Hello!”. The fixed redraw waits until native DOM anchor/head positions match the ProseMirror selection, then redraws decorations. It retries on selection/editor updates and composition end, with one pending animation frame and cleanup on destroy; it does not busy-poll. All three isolated editor tests pass, including this before/after regression. The existing two-browser formatting/offline and production-shell cases are part of final verification.

A second regression test proves that the previous provider ignored local edits during repair of a complete cached draft. Editing availability is now separate from upload readiness: a complete local copy remains editable while connecting or repairing, and pending updates remain unsent until replay finishes. Explicit denial or an epoch change still freezes it; a first incomplete synchronization stays locked. This avoids transient editor read-only changes during background repair. The test checks local preservation, no early upload, later submission and freezing after denial.

The review traced commit ordering, callback rollback behavior, session lifecycle, tenant scope, queued/in-flight frames and transient failures. Events carry only workspace/user IDs; failures log exception classes, not document contents or session credentials. No new dependency, arbitrary HTML path or database migration is introduced. A callback failure cannot authorize a frame: current access checks and the revoked flag still apply. HTTP guard acquisition removes partial entries when invalidation races acquisition.

No new P0/P1 defect remains identified in this review. Existing P2 boundaries remain the default-off rich position-history validation question and unauthenticated loopback engine. P3 work includes measured aggregate database/fanout capacity, stronger operational telemetry, historical retention and cross-instance notification. Failure to reauthorize intentionally freezes the client; reopening after service/access recovery is necessary. This is a local learning milestone, not a production security certification.

| Perspective | Score | Evidence and limit |
|---|---:|---|
| Principal Engineer | 8/10 | Explicit commit ordering, after-commit invalidation and narrowly defined read bound; scale remains unmeasured. |
| Senior Engineer | 8/10 | Scoped event handling, independent current checks and deterministic failure tests. |
| Security | 6/10 | Current-role and session boundaries are stronger; inherited gated-rich and loopback risks remain. |
| Reliability | 7/10 | Lost callbacks do not grant access, failed refreshes close, and drafts survive; availability and network deadlines are not established. |
| Maintainability | 8/10 | Small typed event, one clock boundary and shared session registry; the provider and room lifecycle still need regression coverage. |
| Production readiness | 3/10 | Local identity/configuration, single instance, no tested disaster recovery or capacity claim yet. |

Roll out matched backend/client versions and retain the default-off rich gate. Rolling back this lesson restores the prior poll/send-check behavior and loses immediate invalidation and HTTP/logout ordering. No schema rollback is required. Do not interpret a connection close as erasure of previously received data.

Verification result: **63 non-browser cases** passed in the full serial run. After the two editor fixes, the final browser fixture passed all **20 development-browser scenarios** and **three production offline-shell scenarios**, with no failures or skips. The production frontend build passed. Retained Java reports at this milestone total **64 cases, zero failures/errors/skips**, across those runs.

## PowerShell verification

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
$env:WORKSPACE_RUN_YJS_ENGINE_TESTS = 'true'
$env:WORKSPACE_RUN_BROWSER_TESTS = 'true'
Remove-Item Env:WORKSPACE_BROWSER_TEST_GREP -ErrorAction SilentlyContinue
mvn -B test
```

**Why not hold the session lock during engine validation?** Validation may wait for another process. Keeping it outside lets logout cancel work that has not begun publishing and keeps the ordered critical section small.

**Does logout erase a received document?** No. It ends future authorized operations. A browser may retain prior content, which is why local storage is not described as private from others using the same browser profile.

**What if a permission change races an edit?** Both publication and membership mutation lock the workspace first. The transaction that gets that lock first defines the ordering. A later writer checks the committed role again and cannot rely on an earlier validation result.

**Why keep send-time checks after adding notifications?** A process can miss an invalidation callback, a second instance may not receive it yet, or a handshake can race the change. Current membership is the authority; notification merely makes closure prompt.

**Does five seconds mean the browser loses all old data in five seconds?** No. It is the maximum usable age of an in-progress socket authorization lookup. Already authorized frames, received documents and local drafts cannot be recalled.
