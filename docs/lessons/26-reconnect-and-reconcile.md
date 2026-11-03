# Lesson 26 — Reconnect and reconcile

## What we are building

A plain-text editor can recover a connection without a page reload. When the connection drops, it keeps locally persisted pending updates and retries with bounded exponential backoff and a small random delay. When the browser signals that it is online, it can retry sooner. A browser's online signal is only a hint: a server can still be unreachable or deny access.

Every attempt checks the current HTTP session and verifies that its immutable user ID matches the local draft. The server's WebSocket greeting provides the authenticated user ID as a second check. The provider joins the authorized document, checks its epoch and replays missing committed updates **before** submitting pending local updates. It sends the original ID and exact bytes, one update at a time. The server remains the authority for membership and write permissions.

## The state machine

```text
connecting -> syncing -> ready -> offline -> connecting
                   |                 |
                   v                 v
                waiting           failed (access, epoch, invalid state)
```

`ready` means replay has caught up and editing can be submitted. `offline` means the client has a complete local baseline and can accept bounded local edits; those edits still need server confirmation. `waiting` means no complete local copy exists, so editing remains disabled while the connection retries. `syncing` temporarily disables edits while the missing committed prefix is applied. `failed` stops automatic retries for a condition requiring attention, such as a rejected update, an account mismatch or an incompatible epoch; the existing local draft can be copied.

The reconnect delay begins around one second and doubles up to 30 seconds, with up to 250 ms of jitter. An identity check and WebSocket handshake have deadlines. A save or replay timeout closes the connection and retries; the acknowledgement may have been lost after a commit, so the update ID must not change. Late events from an obsolete socket are ignored. On destruction, timers, event handlers and the socket are cleaned up.

## Why the order matters

1. **Verify identity.** A cached browser profile is not a current session. A different account must never upload another account's pending bytes.
2. **Join and authorize.** A workspace or document might have become inaccessible while offline. A client-side role or an old local shortcut cannot grant access.
3. **Check the epoch and replay.** An epoch marks a generation of document history. Applying an old pending update after an incompatible restore could corrupt the intended history. Replaying from the last contiguous sequence fills gaps; seeing sequence 9 does not mean sequence 8 was applied.
4. **Retry pending IDs in order.** The Yjs operations may depend on earlier local operations. Stable IDs let the server identify a retry after an ambiguous network failure. The UI calls an update saved only after the server acknowledgement and the corresponding local queue transaction.

This implementation currently supports epoch 0 only. If the server reports another epoch, or explicitly rejects the epoch, the provider stops and retains the local draft rather than attempting a cross-generation merge. User-facing merge or export of incompatible rich-text drafts is not implemented. Permission changes are checked by the backend, including at the write boundary. If a WebSocket handshake fails, browser APIs do not reveal its HTTP status; the client may retry with backoff rather than knowing immediately whether the network or permission caused it.

## Walk through the code

- `frontend/src/provider.ts`: `attemptConnection` checks `/api/me`; `scheduleRetry` controls backoff; `connect` sets up the socket and ignores events from older sockets; `goOffline` clears in-flight state without discarding pending updates. `WELCOME`, `JOINED`, `SYNC_PAGE` and `SAVED` advance the protocol.
- `frontend/src/Editor.tsx`: the `waiting` state explains why an incomplete first copy cannot be edited; `offline` permits local editing with a durability warning.
- `frontend/test/a-reconnect.spec.ts`: independent users start from the same server document, disconnect, make concurrent delete-only and insertion edits, reconnect without reloading and converge. A subsequent revoked member's pending work must not be uploaded.
- `frontend/test/partial-sync.spec.ts`: interruption during the first replay does not enable editing on an incomplete baseline. A simulated changed epoch rejects reconciliation before the pending ID is sent.
- The integration fixture runs these browser flows against the actual local Java service, private Node Yjs engine and dedicated PostgreSQL test database. The simulation for the epoch mismatch is a protocol-level test; it is not a full restore implementation.

## Verify it yourself

Start the services and frontend as described in [Lesson 25](25-offline-persistence.md). Open the same document in two separately authenticated browser contexts. Disconnect both, delete characters in one tab and insert text in the other, and wait for each tab's local-save status. Restore connectivity without reloading. Both should return to a synchronized state and show the same merged content. Remove a member while they are offline and observe that their local work remains pending and does not reach the server while access is absent.

For the automated integration run, build the private engine and the frontend, load the dedicated test database environment from `backend/.env.local.ps1`, enable `WORKSPACE_RUN_DB_TESTS`, `WORKSPACE_RUN_YJS_ENGINE_TESTS` and `WORKSPACE_RUN_BROWSER_TESTS`, then run `mvn -B test` from `backend`. The browser fixture starts temporary loopback services and deletes its test records afterward.

## Questions and answers

**Why use both an HTTP identity check and a WebSocket greeting?** The session may change between the HTTP request and the WebSocket handshake. The greeting confirms which server-authenticated account actually owns that socket. Both checks are client-side precautions; server-side permission checks are still essential.

**What if a user loses permission while offline?** The local browser may still hold their cached text and pending edits. On reconnection the server denies access or rejects the write; the client retains recoverable local work. A disconnected device cannot be forced to forget data it already received.

**Why not retry immediately in a tight loop?** During an outage, thousands of clients retrying continuously can make recovery harder. Exponential backoff reduces pressure, and jitter makes clients less likely to reconnect in lockstep. The present values are learning defaults, not measured production capacity settings.

**What if an acknowledgement is lost?** The client cannot infer whether the database committed. It retries the same ID and bytes after reauthorization and replay. The server's duplicate handling returns the prior result rather than creating another committed operation.

**Does convergence mean rich text is safe?** No. These tests cover the bounded plain-text Yjs engine and selected failure scenarios. Rich-text schemas, broader malicious-input validation, multi-instance operation, load limits and operational recovery remain separate gates.
