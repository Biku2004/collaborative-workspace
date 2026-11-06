# Lesson 28 — Collaborator presence

Presence answers “who is here and where are they looking?” It is temporary: a missing cursor is inconvenient, but losing a confirmed document edit is not. **Status: local learning criteria complete.** The selection timing regression found during revalidation is fixed and covered by a deterministic browser test. The limitations below remain open.

## Design

Each browser opts in to presence when joining its document room. The server attaches the authenticated account ID and email and generates an ID for that connection; clients cannot supply any of those identity fields. Workspace members can already see member emails through the membership API. Different browser sessions for one account receive different connection IDs.

The client sends two bounded Base64-encoded Yjs relative positions. They identify an anchor and a head relative to the shared document instead of assuming that a numeric offset stays correct after another user's insertion. Rich-text clients render a cursor and selection through the existing TipTap/Yjs mapping. Plain-text clients show the resolved cursor or selection offsets in the collaborator list.

Presence is sent directly to other opted-in clients in the same authorized room, never inserted into `document_updates`, the outbox, IndexedDB, or the pending edit queue. The server permits at most 10 messages per connection per second and discards excess. The outbound queue drops temporary messages under pressure and can evict them for durable messages. Peers refresh their last cursor periodically; receiving browsers use a monotonic elapsed-time clock to expire stale peers after about 15–20 seconds even if a disconnect notification is lost or the wall clock changes. A normal leave or disconnect also sends a best-effort departure notification. Old clients do not opt in and do not receive the new messages.

The current implementation is single-instance and best-effort. It does not guarantee that every intermediate cursor position is seen, and presence may disappear during a network interruption or queue pressure. Membership checks still occur for the sender and recipient; the broader active-session revocation guarantees are Lesson 32. Cross-instance delivery is Lesson 35. Rich-text presence inherits the default-off rich-text gate and the unresolved position-history limitations in [Lesson 27](27-rich-text-design.md).

The per-connection rate limit is not a room-wide or deployment-wide capacity guarantee. Every accepted message can fan out to many recipients, with a current access check for each delivery; large rooms can therefore put substantial pressure on the database even though individual queues are bounded. Measure and address aggregate load before use beyond the local learning setup; overload testing is Lesson 36.

## Why relative positions?

Suppose Alice's cursor is after `A` in `AB`, at offset 1. If Bob inserts `X` before `A`, keeping the numeric offset 1 points before `A`. A relative position is anchored to the shared Yjs structure; when that anchor is available in the receiving replica, it can resolve after `A` at the new offset 2. A position may be temporarily unresolvable while its document update is still in transit, and it must be ignored until usable.

## Follow a cursor movement

1. The browser joins with `presence: true`. The handshake already authenticated its account and the room join checks that the account can read the document. A legacy join does not opt in.
2. The editor turns its selection endpoints into Yjs relative positions. `DocumentProvider` sends a bounded `PRESENCE` message only while connected, with a short throttle and periodic refresh. It does not call the document update persistence path.
3. `WorkspaceSocketHandler` validates the shape and encoded size, limits the sending connection, checks document access and adds the account and connection identity. A message attempting to provide an identity field is rejected.
4. `DocumentRoomHub` sends the observation to opted-in peers in the same document. `BoundedSocketSender` treats it as disposable and gives durable messages priority if the queue fills.
5. The receiving provider validates and decodes the positions, keeps them only in memory and drops stale entries. The editor resolves a position against its own current Yjs state. A missing or invalid position is ignored, not treated as a document synchronization failure.

## Run the checks

Use the existing local PostgreSQL **test** database. The browser fixture starts its own local engine and server; build the frontend first so the separate offline-shell checks have current assets.

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\frontend
npm run build

cd ..\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
$env:WORKSPACE_RUN_YJS_ENGINE_TESTS = 'true'
$env:WORKSPACE_RUN_BROWSER_TESTS = 'true'
Remove-Item Env:WORKSPACE_BROWSER_TEST_GREP -ErrorAction SilentlyContinue
mvn -B test
```

Expect `BUILD SUCCESS`, with no skipped tests in the Maven summary. For a targeted browser debugging run, set `$env:WORKSPACE_BROWSER_TEST_GREP = 'independent offline insert|rich editors synchronize'` and run `mvn -B '-Dtest=BrowserWorkspaceIntegrationTest' test`; remove the variable before a full verification. The focused filter does not prove the complete browser suite passed.

For a manual experiment, follow the three-window setup in [Lesson 27](27-rich-text-design.md), open the same rich document in two separately signed-in browser profiles and select text in one. The other profile should show that account and the selection. Closing or disconnecting one profile removes its presence immediately when the departure is delivered, or after expiry when it is missed. Type and save an edit after the disappearance to check that document synchronization continues. Rich-text use is still experimental and requires the explicit gate.

## Common problems

- **No collaborator is visible:** confirm both users joined the same document and are still authorized; a different document or an old client does not exchange presence. The first refresh may take a few seconds.
- **A cursor briefly disappears:** presence can be dropped under load, or its referenced document update may not have reached the other browser yet. A later refresh or synchronization can make it visible again.
- **A test fails or is skipped:** confirm all three `WORKSPACE_RUN_*` flags and that a Chromium browser is available. Do not use the development database for the fixture.

## Verification and review

Revalidation found that a synchronous presence redraw could run after a native cursor movement but before the browser's `selectionchange` event. The editor then restored its stale full-text selection, so the next typed character replaced the text. A deterministic harness reproduced `!` instead of `Hello!` before the fix and preserved `Hello!` afterward. Presence redraws now use the pinned Yjs binding's queued metadata helper. The complete browser fixture passed with this regression, real two-client editing, expiry, the 80-edit workload and offline-shell recovery. Socket tests cover authenticated identity, isolation and rejection of client-supplied identity fields; queue and rate tests exercise bounded behavior. These checks are examples, not a production security certification.

The compact engineering review traced authentication, room isolation, queue pressure, expiry, malformed positions and reconnect behavior. It identified and fixed compatibility with non-participating older clients and a wall-clock dependency in expiry. The remaining aggregate fan-out risk is described above. The rich-text position-history concern remains unresolved and the rich-text feature stays gated. See [the review notes](28-presence-review.md) for scope and evidence.

## Interview questions

**Why should cursor messages not be stored as document edits?** They are frequent, replaceable observations. Writing every movement to the durable log adds load and ties document reliability to presence. A durable edit must survive a restart; a cursor can expire and be refreshed.

**Why can the server not trust a user ID in a presence message?** An authenticated user could name another account. The identity must come from the authenticated connection, and the server must still check access to the document before sending to peers.

**Why do we need both a disconnect message and expiry?** A graceful close can remove a cursor promptly. A network failure can lose that notification, so receivers also remove stale observations after a bounded interval.

## Lesson 32 follow-up: delayed selectionchange

Queued metadata alone was insufficient when native `selectionchange` arrived after the queued redraw. Lesson 32 adds a deterministic delayed-notification regression that fails with the prior queued implementation. Presence redraw now waits for the DOM and ProseMirror selections to agree, retries on editor/selection/composition events and cancels pending work on cleanup. The immediate and delayed native-selection tests both pass. See [Lesson 32](32-active-session-revocation.md) for the verification record.
