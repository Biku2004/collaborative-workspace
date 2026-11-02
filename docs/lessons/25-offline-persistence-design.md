# Lesson 25 implementation design — local persistence and pending updates

Status: design notes for [Lesson 25](25-offline-persistence.md). The guide describes the implemented behavior and verified limits; these notes preserve the original acceptance considerations.

## Boundaries to preserve

A local edit, a completed IndexedDB transaction, a server commit, and delivery to another browser are four different events. Show those states separately. Browser storage may be denied, evicted, cleared or fail due to quota; a successful local write does not guarantee permanent retention. Never say an edit is saved on the server until the server acknowledges its committed update.

Offline access cannot verify whether a server session has expired or a role has changed. Locally cached data is readable by anyone who can access this browser profile; account-scoped keys prevent accidental mixing but are not encryption or a security boundary. Pending edits may only be uploaded after a fresh authenticated connection and server authorization. Revoked or incompatible updates must remain recoverable locally, not be discarded or uploaded using a different account.

## Proposed data and ordering

- Key records by immutable server user ID, workspace ID, document ID and document epoch. The existing `/api/me` only returns a username; expose a stable stored-user ID without exposing credentials, and handle the local bootstrap account explicitly. Never use an email alone as the identity boundary.
- Store Yjs state and a separate ordered queue of exact update bytes with stable UUIDs in IndexedDB. Store the update and the state needed to reconstruct its dependencies in one completed read-write transaction before attempting transmission. A storage failure stops sending that edit and reports the unsaved condition while preserving the visible draft for copying.
- Retrying an update must use the same UUID and exact bytes. After `SAVED`, atomically preserve reconstructable state and remove that queue entry. A lost acknowledgement causes a retry with the same ID; the server's idempotency check decides whether the original commit already happened.
- Serialize storage operations and avoid a stale snapshot overwriting a later edit. Multiple tabs must not independently overwrite a shared queue or hold conflicting sequence metadata; design explicit writer ownership or use append-only records and atomic operations, and test tab closure and concurrent tabs.
- Do not advance the committed replay cursor for pending local updates. On restoration, rebuild the Yjs replica from persisted state and pending bytes, then synchronize committed server updates from a verified contiguous cursor. Compare document epochs before uploading anything.

## Offline shell and identity

A real offline reload requires more than IndexedDB: the application shell and assets must be available without the network, and the UI needs an explicit last-used local account context. Cache only versioned public app assets; never cache authenticated API responses as authoritative session or membership proof. Clear the active offline context on sign-out, keep drafts under their original account, and disclose that local cached content is not protected against other people using the browser profile. Revalidate session, workspace membership and write role online before retrying.

Define a safe upgrade strategy for cached assets and IndexedDB schema. A stale shell must fail safely when it cannot read a newer schema or protocol. A browser without service workers or IndexedDB should show a clear limitation and must not falsely mark work durable.

## Acceptance tests before marking complete

1. With a real browser, create an edit without server acknowledgement, go offline, reload using the cached app shell and recover the same text and pending update ID; make an additional offline edit and reload again.
2. Restore connectivity and authorization; verify the same update IDs and bytes are retried, both replicas converge, and acknowledgements remove only committed entries. Simulate a lost ACK after server commit and verify no duplicate server operation.
3. Change the active account, revoke membership or change the epoch while a draft is pending. Verify the wrong account cannot upload it and revoked or incompatible work remains locally recoverable with an understandable state.
4. Exercise IndexedDB transaction failure, quota/unavailability, multiple tabs, refresh during a write, and the gap between local persistence and acknowledgement. Verify honest status and no silent loss or cross-account upload.
5. Verify the service worker never caches private API responses and that sign-out disables the active offline context. Document the browser-profile privacy and eviction limits.

Reconnect policy and full reconciliation belong primarily to Lesson 26; Lesson 25 still needs enough authenticated retry to prove stable-ID and lost-ACK behavior. Do not mark either lesson complete merely because basic local storage works.
