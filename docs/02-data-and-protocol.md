# 2. Data, protocol, and algorithms

This is a design contract, not an existing implementation. The first working vertical slice will refine it with compatibility experiments. All identifiers below are server-generated UUIDs unless explicitly called client-generated.

## 2.1 Start with a concrete edit

Alice types **Hello** while Bob is offline. Her browser first changes its local Yjs document. It then sends an update identified by `updateId`. The server checks Alice's identity and permissions, validates the candidate with the Yjs engine, commits the update, and sends `SAVED`. Bob later reconnects, retrieves the committed state and submits his own queued updates. Their CRDT state can then converge.

Four things that sound similar but differ:

1. **Applied locally**: it is visible in Alice's browser.
2. **Sent**: bytes were handed to the socket; delivery and acceptance are unknown.
3. **Saved**: the server committed those exact bytes within its defined durability boundary.
4. **Seen by Bob**: Bob actually received and applied them. This may happen later.

We should expose local, pending, saved, and rejected states in the UI. A rejected update must remain accessible for recovery; it must not be represented as saved.

## 2.2 Roles and isolation

| Action | Owner | Editor | Viewer |
|---|---:|---:|---:|
| View authorized workspace documents and history | Yes | Yes | Yes |
| Edit document content | Yes | Yes | No |
| Create and rename documents | Yes | Yes | No |
| Save a named revision | Yes | Yes | No |
| Restore a revision | Yes | No | No |
| Manage workspace membership | Yes | No | No |

Initial product policy: workspace members can access all documents in their workspace; no per-document overrides in version 1. Query through workspace membership, never by document ID alone. Protect the last owner from removal. Use 404 for resources hidden by policy; use 403 for an authenticated user who can see a resource but cannot perform an action. Document the chosen policy consistently.

## 2.3 Logical database model

All timestamps are server-generated UTC instants. Use foreign keys, uniqueness constraints, and indexes on foreign-key and replay paths. Listed names are the model; migrations will specify exact types and constraints.

| Table | Key columns and purpose |
|---|---|
| `users` | `id`, normalized unique email, password hash or external identity subject, created time |
| `workspaces` | `id`, name, creator, created time |
| `workspace_members` | `(workspace_id, user_id)` primary key, `role`, `auth_version`; indexed by user |
| `documents` | `id`, `workspace_id`, title, current epoch, next sequence, schema version, timestamps, archived time |
| `document_epochs` | `(document_id, epoch)` primary key, origin revision, created time, status |
| `document_updates` | `(document_id, epoch, sequence)` primary key; `update_id`, actor ID, bytes, SHA-256 digest, accepted time; unique `(document_id, epoch, update_id)` |
| `document_checkpoints` | ID, document/epoch, inclusive sequence, full encoded Yjs state, checksum, state/schema version, created time |
| `document_revisions` | ID, document/epoch, label, author, captured sequence, immutable full state and checksum, created time |
| `outbox_events` | ID, event type/version, aggregate, payload reference, created time, lease/attempt/published fields |
| `audit_events` | actor, workspace, action, target, outcome, timestamp, correlation ID; no document contents or tokens |
| `document_search` | document ID, epoch and indexed sequence, derived text, update time; always join current authorization on search |

Start with update and checkpoint bytes in PostgreSQL. `bytea` makes the atomicity story straightforward. Storage accounting and retention are explicit product decisions; do not discard updates until checkpoints and required historical revisions are independently verified. Add object storage later using upload, verify, then transactional metadata publication; unreferenced uploads are garbage-collected after a safety delay.

For collaboration records the sequence is a **server-assigned log position**, not Yjs's internal client clock and not a claim about human time. Allocate by locking the document row and incrementing its counter in the same transaction that inserts the update. On rollback the increment rolls back. Every operation that changes the document epoch or edits must observe the same lock order. PostgreSQL sequence objects alone can have gaps and allocate before commit, so they do not prove that a visible prefix is complete. [PostgreSQL locking](https://www.postgresql.org/docs/current/explicit-locking.html)

## 2.4 HTTP API sketch

HTTPS, JSON, session cookies, and consistent error bodies are the default. Add cursor pagination and limits to list endpoints. IDs in paths are not authority. CSRF protection applies to cookie-authenticated mutation requests.

| Method and path | Purpose | Key conditions |
|---|---|---|
| `POST /api/workspaces` | Create workspace; creator becomes owner atomically | Authenticated |
| `GET /api/workspaces` | List memberships | Authenticated |
| `POST /api/workspaces/{w}/members` | Add/change membership | Owner; protect last owner |
| `DELETE /api/workspaces/{w}/members/{user}` | Revoke membership | Owner; records audit |
| `POST /api/workspaces/{w}/documents` | Create a document | Editor/owner |
| `GET /api/documents/{d}` | Metadata, permission, epoch, editor schema | Member |
| `PATCH /api/documents/{d}` | Rename/archive | Editor/owner; optimistic version for metadata |
| `GET /api/documents/{d}/revisions` | List revision metadata | Member |
| `POST /api/documents/{d}/revisions` | Create immutable revision | Editor/owner; request idempotency |
| `POST /api/documents/{d}/restore` | Restore a selected revision | Owner; expected epoch and idempotency |
| `GET /api/search?q=...` | Search authorized documents | Member, filter through current permissions |
| `GET /api/documents/{d}/collaboration` | Open WebSocket on same origin | Validate session, origin and membership |

Authentication: use a secure, HttpOnly, same-site session cookie for the initial same-origin browser application; production uses TLS. Spring Security session management and a configured external identity provider are separate deployment choices. Browser WebSocket does not let JavaScript set an arbitrary Authorization header; cookie handshake plus strict Origin checks is practical here. The handshake is not permanent authorization. [MDN constructor](https://developer.mozilla.org/en-US/docs/Web/API/WebSocket/WebSocket), [OWASP WebSocket security](https://cheatsheetseries.owasp.org/cheatsheets/WebSocket_Security_Cheat_Sheet.html)

## 2.5 WebSocket protocol v1

Use a custom TypeScript provider and Java protocol, not an assumption of compatibility with `y-websocket`. Define this protocol in a versioned document and run cross-language fixture tests. Initial JSON control messages and base64 Yjs payloads prioritize readability; binary framing can be introduced after profiling, with strict frame and decoded-size limits. Base64 increases payload size. Session identity is derived from the server, never from a client-supplied actor ID.

Client → server: `HELLO` (version, document ID, known epoch and last contiguous sequence); `SUBMIT_UPDATE` (epoch, update ID, payload); `PRESENCE` (bounded display/cursor data); `SYNC_REQUEST` (epoch and contiguous cursor). Server → client: `WELCOME` (epoch, role, schema version); `SYNC_BEGIN`, `CHECKPOINT`, `UPDATE`, `SYNC_END`; `SAVED` (update ID, sequence); `REJECTED` (update ID, reason); `PRESENCE`; `EPOCH_CHANGED`; `ACCESS_REVOKED`; `ERROR`.

```json
{"version":1,"type":"SUBMIT_UPDATE","documentId":"...","epoch":3,"updateId":"...","payload":"<base64 Yjs update>"}
```

Error vocabulary: `UNAUTHENTICATED`, `FORBIDDEN`, `STALE_EPOCH`, `BAD_PAYLOAD`, `UNSUPPORTED_SCHEMA`, `TOO_LARGE`, `RATE_LIMITED`, `RETRY_LATER`, `IDEMPOTENCY_CONFLICT`. Protocol errors specify whether to retry, reauthenticate, preserve a local draft, or upgrade. Invalid payloads are never marked saved.

`updateId` is generated and stored alongside the offline queue before first send and reused on retry. The server stores a digest: same ID and bytes returns the original result after current authorization is checked; same ID and different bytes is a conflict. Keep deduplication records for the supported retry horizon even if old update payloads are compacted. `clientSequence` is optional diagnostics, not the durable cursor.

## 2.6 Commit flow and revocation race

1. Authenticate session and bound incoming size/rate. Decode base64 and compute a digest. Obtain current committed state as a speculative candidate. Validate bounded decoding and supported document/schema using the private Yjs engine; run it with deadlines and bounded resources.
2. Begin database transaction. Lock workspace membership row and document row in a consistent documented order. Recheck role, epoch, schema, idempotency and the committed head used for candidate validation. Membership revocation uses the same membership lock; epoch switch uses the same document lock. If the head changed, discard candidate and retry boundedly. Avoid holding database locks over remote engine calls.
3. Increment the document sequence, insert the exact update and its digest plus an outbox notification, then commit. Database failure yields no `SAVED`; discard speculative state. A commit outcome lost to a connection failure is **unknown**: retry the same ID and look it up.
4. After commit, ACK and distribute the committed update. A post-commit outbox dispatcher repairs the commit-to-publish crash window. Consumers may receive duplicates; apply idempotently. The engine's cache must be updated only from committed data, with sequence checks, or invalidated/rebuilt on uncertainty.

Validation is an early risk: a Yjs binary can be syntactically valid while depending on other missing structures; successful decoding alone does not establish that the intended rich-text state is safe, valid, or fully materialized. Establish exact accepted-update rules and dependency limits with tests in the compatibility spike. Bound unresolved dependencies and reject or defer safely; never broadcast unvalidated speculative state. Malicious authorized writers can still create harmful content within granted privileges; rich-text sanitization, schema checks, quotas, and product policy remain necessary.

## 2.7 Initial sync and repair

For a newly joined client, authorize, choose a fixed committed high-water mark H, read a checkpoint at or before H and the complete committed tail through H, then send `SYNC_END(H)`. Subscribe before or buffer live notifications while loading; deduplicate and order updates by sequence. Track the **last contiguous** applied sequence, not the largest seen. Periodically query the durable log from that position while the socket remains open, and on reconnect; this repairs lost pub/sub or local notification messages. If retained data no longer covers the cursor, load a fresh checkpoint and tail. Handle paging and size limits.

The client loads IndexedDB for `(user, workspace, document, epoch, schema)` and must not send unsaved content before the server confirms access and matching epoch. Local data can be evicted, cleared, or unavailable; offline operation is best effort. For a new browser with no local copy, offline open is not promised. Accepted server state and the local pending outbox are different records. A remote update applied by the provider must not be re-enqueued as a local edit.

Yjs state vectors can reduce transferred data, but a vector by itself is not a checksum for all deletes. For v1, a complete checkpoint plus log-tail replay is the correctness baseline; optional state-vector optimization must prove inserts **and deletions** synchronize correctly. [Yjs update API](https://docs.yjs.dev/api/document-updates)

## 2.8 Checkpoints, revisions, and restoration

**Checkpoint** speeds replay; **revision** is a user-visible immutable historical state. At fixed committed sequence H, materialize checkpoint plus tail through H, verify resulting encoded state, checksum and schema; publish it transactionally. Concurrent updates after H stay in the tail. Do not delete the old state until verification, retention checks, and a tested rollback window have passed. Yjs `mergeUpdates` alone does not garbage-collect deleted content. [Yjs updates](https://docs.yjs.dev/api/document-updates)

Create a revision at a fixed committed H; explicitly explain that edits still pending on a client may be excluded. Store full reconstructable encoded state, not only a Yjs historical snapshot marker. For restore, capture the revision, create a **fresh Y.Doc** from its supported materialized content, preserving supported rich-text structure but creating new CRDT identities. Verify with round-trip tests. In one transaction lock document, compare expected epoch, create next epoch and its full initial checkpoint, move the current epoch pointer, write an audit/outbox event, and commit. Notify or disconnect old sessions; reauthorization and epoch checks stop their writes. Restore must race safely with compaction and membership changes.

An old-epoch client must never merge its old Yjs bytes into the new document automatically. Preserve the old local data as a recovery draft; offer copy/export/manual comparison if the user is still authorized. If access is revoked, block server reads and writes; previously downloaded browser data cannot be reliably recalled. A later migration to rich text must define versioned ProseMirror schema, allowed nodes/marks, migration, and behavior when a client version is incompatible. Freeze a compatible feature set before broad testing.

## 2.9 Presence and comments

Presence is soft state, scoped to an authorized session, with heartbeat expiration, throttling, size caps, and server-controlled identity. It has no durable ACK. Cursor positions should use Yjs relative positions, not fixed offsets; they may resolve to null after deletion. Clean up on disconnect and timeout. Do not trust client-supplied user names or let one session impersonate another. [Yjs relative positions](https://docs.yjs.dev/api/relative-positions)

Later comments use a separate authorized database model with relative-position anchors, orphan handling, moderation, and epoch-aware behavior; they are not needed for the initial core release.
