# Lesson 21 — Commit document updates before acknowledging them

## 1. What we built

An owner or editor can submit one **plain-text Yjs update** through a joined document WebSocket. The Java server asks the private Yjs engine to reconstruct a speculative candidate. It then rechecks authorization and the document head inside a PostgreSQL transaction, records the update, advances the sequence and checkpoint, and adds an outbox row. The client receives `SAVED` only after the transaction commits.

This is a local-development learning checkpoint. Lesson 22 will deliver committed updates to peers and repair gaps. This lesson does not broadcast edits, provide a frontend, or implement full rich-text validation. The Java/custom-provider compatibility gate remains open; do not treat this as production-ready collaboration.

## 2. The central rule: commit before acknowledgement

```text
client -> SUBMIT_UPDATE (stable updateId, epoch, exact Yjs bytes)
       -> check current identity, workspace role, document and epoch
       -> read current committed checkpoint and sequence
       -> private Node engine reconstructs a speculative candidate
       -> transaction: lock workspace, recheck actor/session, lock document
       -> recheck epoch, update ID and committed head
       -> insert update + advance checkpoint and sequence + insert outbox row
       -> COMMIT
client <- SAVED (documentId, epoch, sequence, updateId)
```

No database lock is held during the Node call. If the head changed during validation, the server discards the candidate and revalidates from the new head, up to three attempts. If PostgreSQL rolls back, none of the update, checkpoint, sequence or outbox row commits. The server does not send `SAVED` before the transaction finishes.

A lost connection or error around a commit can leave the client uncertain whether it committed. Retry with the **same update ID and identical bytes**. If that update was committed by the same actor, the server returns the original sequence with `duplicate: true`, even if the engine is unavailable. Reusing the ID with different bytes or a different actor gives `UPDATE_ID_CONFLICT`. CRDT idempotence alone does not identify whether an application request was durably committed.

## 3. Prerequisites

Use the existing local PostgreSQL project database and a separate test database. Build and run the private engine from [Lesson 18](18-private-yjs-engine.md) in a separate PowerShell window:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\experiments\yjs-private-engine
npm ci
npm run build
npm start
```

The engine defaults to `http://127.0.0.1:8091`. The Java development profile defaults to the same origin; override `WORKSPACE_YJS_ENGINE_URL` with an explicit loopback HTTP origin and port if needed. Java restricts this learning client to `127.0.0.1`. If the engine is down, new candidates return `ENGINE_UNAVAILABLE`; already committed identical retries do not require the engine.

Start the Java backend using [Lesson 12](12-create-workspaces.md). Flyway applies V8 to the development database on startup. Only workspace members who are owners or editors can submit changes. Viewers can join a room for read-only demonstrations but receive `ACCESS_DENIED` on update submission.

## 4. Protocol example

First log in, connect, exchange `HELLO`/`WELCOME`, and `JOIN` the desired document as described in [Lesson 20](20-document-rooms-and-bounded-messaging.md). New documents use epoch **0**. In the same browser console:

```javascript
const updateId = crypto.randomUUID();
const payload = 'AQEBAAQBB2NvbnRlbnQKSGVsbG8gSmF2YQA='; // fixed learning fixture
const request = { type: 'SUBMIT_UPDATE', version: 1, epoch: 0, updateId, payload };
socket.send(JSON.stringify(request));
```

On success the socket receives an object like:

```json
{"type":"SAVED","version":1,"documentId":"...","epoch":0,"sequence":1,"updateId":"...","duplicate":false}
```

Send the exact same `request` again to simulate a lost acknowledgement. You should get the same sequence with `duplicate: true`. Do not generate a new ID for a retry of the same intended submission. The fixture uses a fixed CRDT client ID only for demonstration; real clients need their own Yjs-generated updates and client identities.

`payload` must be canonical padded Base64 for 1–8192 bytes, and `updateId` must be a canonical UUID. The full WebSocket text frame is limited to 16 KiB. `REJECTED` includes a safe code such as `INVALID_UPDATE`, `ACCESS_DENIED`, `DOCUMENT_ARCHIVED`, `EPOCH_CHANGED`, `UPDATE_ID_CONFLICT`, `RETRY_HEAD_CHANGED` or `ENGINE_UNAVAILABLE`. An unexpected server/database failure closes the socket; the outcome can be unknown, so use the same ID to check by retrying after reconnecting.

## 5. The database model

`V8__document_update_log.sql` adds:

- `document_streams`: one lazily initialized stream per document, with epoch, committed head sequence and a reconstructable checkpoint.
- `document_updates`: the exact submitted bytes, SHA-256 digest, actor, stable update ID, epoch and server sequence. A primary key enforces one sequence per stream/epoch and a unique constraint enforces update ID uniqueness within an epoch.
- `document_update_outbox`: one row for each committed update, in the same transaction. Dispatch and replay are Lesson 22.

The first sequence is 1; the initial empty stream is epoch 0 and head 0. The sequence is a server log position, not a timestamp or a Yjs client clock. Legacy documents without a workspace remain unassigned and cannot be edited through this path. The migration creates additive tables and does not silently assign old documents.

The stored SHA-256 digest is evidence of the payload; duplicate requests are compared against the actual stored bytes as well as actor and update ID. Sequence allocation and the outbox insert are part of the same database transaction. No downstream worker consumes the outbox yet.

## 6. Follow the implementation

1. `YjsEngineClient.java` sends bounded JSON requests to the local Node engine with a deadline. `BoundedBodySubscriber.java` cancels oversized HTTP responses. Engine responses are validated before they become candidates; bytes in the `Candidate` record are defensively copied.
2. `experiments/yjs-private-engine/src/reconstruct.ts` applies the pinned Yjs update format and rejects trailing bytes, unresolved insert/delete dependencies, detected unsupported structures and size violations. Its pending-dependency checks rely on version-specific Yjs internals and are not a complete proof against arbitrary hostile updates.
3. `DocumentUpdateService.java` checks access, recognizes an already-committed duplicate, reconstructs outside the transaction, then uses `TransactionTemplate` for the workspace/document locks, rechecks, update/head/outbox writes and commit. It returns `Saved` only after `execute` returns successfully.
4. `DocumentUpdateRepository.java` contains parameterized SQL. `V8__document_update_log.sql` provides database constraints. Document and membership writers use the existing workspace lock order.
5. `WorkspaceSocketHandler.java` obtains user, workspace and joined document from server-side session state. It validates a `SUBMIT_UPDATE`; after the service returns it queues `SAVED` to that socket, subject to a current read check. No update is broadcast here.
6. `YjsEngineBoundaryTest.java`, `YjsEngineClientTest.java`, `DocumentUpdateEndToEndTest.java` and `WorkspaceSocketIntegrationTest.java` verify the process boundary, failure handling, commit ordering and concurrency.

The local session-destruction listener and update commit share a per-session guard during the short database transaction. If a commit already holds that guard, logout waits for it to finish before its session-destruction callback returns; if invalidation happens first, the session recheck prevents the write. The guard is not held during the Node call. Membership removal is ordered against writes with the workspace lock. This is a single-process mechanism, not a distributed session or cancellation guarantee. Network delivery cannot be recalled after it has begun.

## 7. Run the tests

Build the engine once first, as in section 3. Then in PowerShell:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\experiments\yjs-private-engine
npm test

cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
$env:WORKSPACE_RUN_YJS_ENGINE_TESTS = 'true'
mvn -B test
```

The opt-in Java/Node tests start their own loopback engine process and stop it after use. The full path test sends actual Yjs insert/delete fixtures over a WebSocket through the real Node engine into the separate PostgreSQL test database; it checks the checkpoint and retries an update ID. Other tests hold the transaction before commit and check that neither another database connection nor the client sees a saved update; inject an outbox failure and confirm rollback and no acknowledgement; exercise conflicting IDs, viewers, engine failure, concurrent duplicates, head changes, removal during validation and logout racing with a commit. The real engine fixture and injected-failure tests complement one another; neither proves all possible failure modes.

## 8. Interview questions

**What if the database commits but the acknowledgement is lost?** The client cannot infer failure from silence. It retries the same update ID and exact bytes. The committed row identifies the original sequence; the server returns it without writing a duplicate.

**Why validate outside the transaction?** A Node call can be slow or unavailable. Holding database locks during it would block membership and document changes. Capture the head, validate speculatively, then lock and compare the head before committing; if it changed, validate again.

**Why an outbox?** Committing a row and sending a notification are separate actions. An outbox row committed with the update creates durable work for later delivery. Lesson 22 must implement the relay and replay; this row alone does not broadcast anything.

**Why recheck permissions?** A user may be removed or demoted while the engine validates. Membership changes and this write acquire the same workspace lock; the write rechecks the current role after acquiring it.

**Why can an engine response not mean `SAVED`?** The engine doesn't commit PostgreSQL and doesn't decide permissions. Its response represents a candidate. A subsequent transaction can fail, race with another change, or have an unknown outcome.

**What if two writers use the same update ID?** The unique database constraint and the lock order prevent duplicate records. Matching actor and bytes return the original sequence; a conflicting actor or different payload is rejected.

## 9. Limits and checkpoint

The prototype supports a single active epoch and bounded plain-text checkpoints. It does not yet implement restore, shared multi-instance transport, browser editing, complete rich-text/schema validation, outbox delivery, backpressure for durable broadcasts, or a full threat-resistant Yjs validation boundary. The fixed 8 KiB checkpoint limit means larger documents are rejected until a later design addresses reconstruction and compaction safely.

Exercise: submit the fixture, repeat it with the same ID, then change the ID and observe that the server treats it as a distinct submission. Next is [Lesson 22 — Broadcast committed updates and repair gaps](../07-next-lessons-roadmap.md).
