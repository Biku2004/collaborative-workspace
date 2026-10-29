# 7. Roadmap after Lesson 7

This is a **plan**, not a description of features already implemented. Lesson numbers below continue the [learning checklist](00-learning-checklist.md); the broader architecture guide uses different **step** numbers. One lesson delivers one primary capability, although a difficult lesson may take more than one session. We implement, explain and verify each lesson before moving to the next.

## Where we are now

Lessons 1–7 created a Spring Boot application, a document API, validation and PostgreSQL persistence. Local development uses the existing PostgreSQL server, with separate development and test databases. The current document endpoints have **no authentication or workspace authorization**; they are for local learning. A document currently has no workspace or owner. The frontend and collaboration features are not yet implemented.

Every coding lesson will include: the problem and a small example; required Java, Spring or database concepts; request/data flow and invariants; exact files and complete code; Windows PowerShell commands; expected output; a success and meaningful negative/failure test; common mistakes; interview questions and answers; and a checkpoint before moving on. The implementation may adjust file names as the project grows.

## Milestone A — Identity and safe access

### Lesson 8 — Add Spring Security and protect existing endpoints

- **Build:** Add a security configuration and use a temporary development-only identity so we can learn the filter chain. Protect document APIs and make only the intended status endpoint public. Avoid hard-coded production credentials. Preserve safe cookie/session and CSRF defaults; document the local HTTP setup.
- **Learn:** Authentication versus authorization; security filter chain; 401/403; cookies and CSRF. Discuss why exposing the existing API on the internet is unsafe.
- **Verify:** Anonymous document requests are denied, an authenticated test user can use the API, and a mutation without a valid CSRF token fails. Integration tests use the dedicated test database. Temporary development authentication must be clearly labeled and removed as persisted users are introduced.
- **Interview practice:** Why is authentication not enough to protect a document? Why does a cookie-based application need CSRF protection?

### Lesson 9 — Store users and hash passwords

- **Build:** Add a versioned Flyway migration for users, a repository and a service. Normalize and uniquely constrain email; store a password hash, never a plaintext password. Use a Spring Security password encoder with an appropriate adaptive algorithm and configurable cost; no custom cryptography. Define how a development account is created without committing credentials.
- **Learn:** Hashing versus encryption, salts, uniqueness constraints and why application checks need a database constraint.
- **Verify:** Registration or controlled provisioning succeeds; duplicate normalized emails and invalid input fail; a database inspection shows a hash rather than the original password. Test migration on the test database.
- **Interview practice:** What happens if two registrations for the same email occur concurrently? Why can't a password hash simply be decrypted?

### Lesson 10 — Log in with a session

- **Build:** Authenticate against stored users and establish a server-managed session. Configure secure cookie behavior for the deployment environment, session fixation protection, and explicit login failure behavior. Local HTTP and production HTTPS settings must be distinguished.
- **Learn:** Credential verification, session ID versus password, `HttpOnly`, `Secure`, `SameSite`, and server-side session state.
- **Verify:** Correct credentials establish a session; wrong credentials and an anonymous request fail; a session cannot be used to impersonate another account. No passwords or session cookies appear in logs.
- **Interview practice:** What changes if the session cookie is stolen? Why should we not place passwords in a client-side token?

### Lesson 11 — Log out and manage session lifetime

- **Build:** Invalidate the server-side session on logout; handle expired sessions consistently and retain CSRF protection for logout and other state-changing requests. Document the first-version session-store behavior across application restarts.
- **Learn:** Authentication lifecycle, expiration, logout and session invalidation.
- **Verify:** A previously valid session cannot access protected APIs after logout or expiry; logout without a valid CSRF token is rejected. Two independent browser sessions are tested.
- **Interview practice:** Does deleting a browser cookie alone revoke a server session? What happens to in-memory sessions after a restart?

**Checkpoint A:** We can distinguish two authenticated users, but document-level access is not yet safe. Do not present this checkpoint as a multi-tenant product or deploy it publicly.

## Milestone B — Workspaces, roles and existing documents

### Lesson 12 — Create workspaces

- **Build:** Add workspace and membership tables. `POST /api/workspaces` atomically creates a workspace and makes the authenticated creator its owner; add a user-scoped workspace list.
- **Learn:** Foreign keys, transactions, atomicity, server-derived actor IDs.
- **Verify:** A workspace cannot be committed without its owner; two users see only their own workspace memberships; a rollback leaves neither half-created record.
- **Interview practice:** Why must creating the workspace and owner membership be in one transaction?

### Lesson 13 — Membership and role rules

- **Build:** Add owner, editor and viewer roles and owner-managed membership. Check identity and workspace membership for every membership operation. Prevent removing or demoting the last owner, including concurrent requests.
- **Learn:** Role-based authorization, race conditions, lock ordering and transaction constraints.
- **Verify:** An editor cannot manage members; a nonmember cannot enumerate members; concurrent last-owner removals cannot leave the workspace without an owner.
- **Interview practice:** Why is checking a role only in the user interface insufficient? How would you prevent the last-owner race?

### Lesson 14 — Move document creation into a workspace

- **Build:** Add workspace-scoped document creation and associate every new document with its workspace. Enforce owner/editor creation rights and server-side tenant-scoped queries. Define a safe migration for documents created in Lessons 1–7: keep them inaccessible to ordinary accounts until an explicit owner-approved assignment or separate local migration is performed; do not silently assign them to the first user.
- **Learn:** Database migrations with existing data, foreign keys, tenancy and ownership decisions.
- **Verify:** New documents always have a workspace; viewers and nonmembers cannot create them; old documents are not exposed or deleted during migration. Test migration with representative pre-existing records.
- **Interview practice:** What happens when you add a non-null foreign key to a table that already contains rows? Why is an unguessable document ID not authorization?

### Lesson 15 — Read and list authorized documents

- **Build:** Filter document read and list requests by current membership; return a consistent hidden-resource response. Replace or protect the original unscoped routes so they cannot bypass workspace checks.
- **Learn:** Tenant-scoped SQL, object-level authorization and pagination fundamentals.
- **Verify:** Members can read documents allowed by policy; an authenticated user from another workspace cannot read or list them, including by guessing an ID. Test both new and legacy routes.
- **Interview practice:** What is an insecure direct object reference, and where should it be prevented?

### Lesson 16 — Rename and archive documents

- **Build:** Let an owner or editor rename/archive a document; use an optimistic metadata version to detect conflicting changes. Define the visible behavior of archived documents.
- **Learn:** Optimistic locking, concurrent writes and 409 conflicts.
- **Verify:** Viewer writes fail; stale versions do not silently overwrite a newer title; archived documents follow the documented read/list policy.
- **Interview practice:** When is a version check simpler than a CRDT?

**Checkpoint B:** Workspace boundaries are enforced on every existing document API. Before moving to sockets, test cross-workspace access and migration of old documents end to end.

## Milestone C — Collaboration foundations

### Lesson 17 — Learn Yjs with two local replicas

- **Build:** A small TypeScript/Node experiment with two independent Yjs documents. Exchange binary updates and intentionally deliver them out of order or twice.
- **Learn:** Replicas, CRDTs, convergence, state vectors, deletion semantics and UTF-16 positions. Compare CRDTs and operational transformation without claiming either solves every product problem.
- **Verify:** Concurrent inserts and deletes converge after necessary updates are exchanged; duplicate delivery is harmless. Document surprising outcomes and missing-dependency cases.
- **Interview practice:** Does convergence mean every user's intention is preserved? Why is a state vector not a complete content checksum?

### Lesson 18 — Prototype the private Yjs document engine

- **Build:** A minimal private Node service using a pinned Yjs version to reconstruct supported state and produce fixtures. Java remains responsible for authorization and committed data.
- **Learn:** Process boundaries, binary encoding, timeouts and speculative versus committed state.
- **Verify:** Round-trip representative updates; reject malformed/oversized inputs; check pending dependencies, restart, timeouts and bounded resources. No candidate may be treated as accepted merely because the engine returned success.
- **Interview practice:** Why can't Java infer the content of opaque Yjs bytes? What if the engine succeeds but the database transaction fails?

**Decision gate:** Prove the Java/custom-provider and engine boundary with cross-language fixtures. If it cannot safely support required Yjs behavior, document the findings and evaluate the planned Node/Yjs collaboration-server fallback before building further. Do not claim standard `y-websocket` wire compatibility for a custom protocol.

### Lesson 19 — Authenticate a WebSocket connection

- **Build:** Add a WebSocket endpoint that checks the session, allowed Origin and workspace membership. Establish a minimal versioned `HELLO`/`WELCOME` exchange; no document edits yet.
- **Learn:** HTTP upgrade, persistent connections, browser cookie authentication, Origin checks and connection lifecycle.
- **Verify:** An authorized member connects; anonymous, cross-origin and cross-workspace attempts fail. Close and expiry paths do not leak room membership.
- **Interview practice:** Why does successful authentication at the handshake not permanently authorize every future operation?

### Lesson 20 — Add room membership and bounded messaging

- **Build:** Route messages within a single-instance document room; bound message size and outbound queues. Session identity is supplied by the server, not trusted from client messages.
- **Learn:** Session versus user versus room; slow consumers and backpressure.
- **Verify:** Two authorized clients receive a test message; another document or workspace cannot receive it; a slow reader cannot create an unbounded queue. This demonstration is not yet durable editing.
- **Interview practice:** What happens if a browser cannot consume messages as fast as the server produces them?

### Lesson 21 — Commit document updates before acknowledging them

- **Build:** Create the document epoch and update-log schema. Validate requests and current permissions, use stable update IDs, commit the update with a per-document sequence, then acknowledge. Keep any engine candidate isolated until commit. Add an outbox record in the same transaction.
- **Learn:** Transaction boundaries, idempotency keys, database uniqueness, durability and the meaning of `SAVED`.
- **Verify:** An ACK never precedes a successful commit; duplicate retries return the original result; a reused ID with different bytes fails; failure between engine validation and commit cannot broadcast uncommitted state.
- **Interview practice:** What if the server commits the update but the ACK is lost? Why is CRDT idempotence alone insufficient?

### Lesson 22 — Broadcast committed updates and repair gaps

- **Build:** Deliver committed updates to peers in one instance and replay the durable log from a client's last contiguous sequence. Add bounded initial sync and periodic repair while connected.
- **Learn:** High-water marks, gap detection, duplicate delivery, replay and outbox processing.
- **Verify:** Two clients converge; an intentionally missed notification is repaired without reconnecting; a crash after commit and before notification is recoverable.
- **Interview practice:** Why is the largest sequence seen different from the last contiguous sequence applied?

## Milestone D — Offline editing and the frontend

### Lesson 23 — Create the minimal browser application

- **Build:** Set up the TypeScript frontend, login/session flow and a simple workspace/document list using the existing API. Use a local development proxy or another explicitly configured same-origin arrangement.
- **Learn:** Frontend/backend separation, browser requests, cookies, CSRF tokens and loading/error states.
- **Verify:** Login and authorized document listing work from a browser; invalid login and denied access show understandable errors. No secrets are embedded in frontend code.
- **Interview practice:** Why does a browser application still need server-side authorization?

### Lesson 24 — Add a plain-text collaborative editor

- **Build:** Connect a simple editor to Yjs and the custom provider, initially as a learning and compatibility slice.
- **Learn:** Local document changes, transaction origin and avoiding update echo loops.
- **Verify:** Two independent browser contexts edit the same authorized document and converge through the server, including insert/delete cases. Ensure tests cannot pass solely through same-browser cross-tab communication.
- **Interview practice:** Why apply a local edit immediately before the server acknowledges it?

### Lesson 25 — Add offline persistence and a pending-update queue

- **Build:** Persist document state in IndexedDB and keep a separate queue of unacknowledged updates with stable IDs. Show local, pending, saved and rejected states.
- **Learn:** Browser storage, durability boundaries, retry identity and storage eviction.
- **Verify:** Offline edits survive reload where IndexedDB remains available; reconnect retries the same IDs; a lost ACK does not duplicate an accepted operation. Explain browser storage limits.
- **Interview practice:** Why is locally visible text not necessarily saved on the server?

### Lesson 26 — Reconnect and reconcile

- **Build:** Restore the connection, reauthorize, compare epochs and replay missing committed updates. Keep rejected local work recoverable rather than silently discarding it.
- **Learn:** Reconnection state machines, transient failures and authorization changes during offline work.
- **Verify:** Two users make concurrent offline edits, reconnect, and converge, including delete-only changes. Revoked or incompatible clients cannot upload; pending work receives an understandable status.
- **Interview practice:** What if a user loses permission while offline?

### Lesson 27 — Add the required rich-text editor

- **Build:** Replace the plain-text learning slice with TipTap/ProseMirror bound to Yjs. Support paragraphs, headings, bold, italic and lists with a versioned schema.
- **Learn:** Editor schema, Yjs shared structures, rich-text rendering and schema compatibility.
- **Verify:** Two browsers concurrently edit supported formatting; offline/reload/reconnect preserve supported structures. Unsupported or old schemas fail clearly. Arbitrary HTML, images and complex embeds remain outside this first scope.
- **Interview practice:** Why does a syntactically valid Yjs update not automatically guarantee a valid or safe rich-text document?

### Lesson 28 — Show collaborator presence

- **Build:** Display server-authenticated collaborator identities and cursor/selection positions using relative positions. Apply rate limits and presence expiry.
- **Learn:** Ephemeral versus durable state, TTLs and positions under concurrent edits.
- **Verify:** Cursors move across two clients; abrupt disconnect expires presence; a client cannot impersonate another user. Presence loss never loses document edits.
- **Interview practice:** Why are cursor updates handled differently from document updates?

## Milestone E — History, restore and search

### Lesson 29 — Create verified checkpoints

- **Build:** Reconstruct a full state through a fixed committed sequence and publish a checksum-verified checkpoint. Keep the update tail and safety window.
- **Learn:** Snapshot boundaries, compaction races, recovery and garbage collection limits.
- **Verify:** Writes arriving during compaction are retained; checkpoint plus tail reconstructs the document; a corrupted checkpoint is rejected.
- **Interview practice:** Why is “delete all updates after creating a snapshot” unsafe?

### Lesson 30 — Save and inspect named revisions

- **Build:** Store an immutable reconstructable revision at a fixed committed sequence and add authorized listing and preview.
- **Learn:** Checkpoint versus user-visible revision and retention.
- **Verify:** Revision content is stable after later edits; unauthorized users cannot inspect it; UI explains that still-pending local edits may not be included.
- **Interview practice:** Is a historical marker necessarily a standalone backup?

### Lesson 31 — Restore a revision safely

- **Build:** Restore supported materialized content into a fresh Yjs document and atomically advance the document epoch. Preserve prior history and quarantine old-epoch local drafts.
- **Learn:** Generations, optimistic concurrency, restore/edit races and reconciliation trade-offs.
- **Verify:** A restore racing with an edit has a defined ordering; old offline updates are rejected instead of silently restoring old content; an authorized user can recover a local draft manually.
- **Interview practice:** Why can an offline client undo a restore if old updates are merged automatically?

### Lesson 32 — Revoke access during active sessions

- **Build:** Coordinate permission changes with update commits; close or invalidate active sessions and define a bounded read-revocation window.
- **Learn:** Lock ordering, authorization leases and in-flight data limitations.
- **Verify:** A write ordered after revocation is rejected; connected clients lose access within the documented bound. State honestly that previously received data cannot be recalled.
- **Interview practice:** What if a permission change races an edit?

### Lesson 33 — Search authorized content

- **Build:** Index committed text asynchronously in PostgreSQL and filter results through current membership. Show or document index freshness.
- **Learn:** Derived projections, eventual indexing, access-controlled queries and rebuilds.
- **Verify:** Unauthorized content never appears in search; index failure or lag does not reject durable edits; rebuild produces correct results.
- **Interview practice:** Why must a search index not become the authority for access control?

## Milestone F — Operate and scale with evidence

### Lesson 34 — Add observability

- **Build:** Structured, redacted logs, traces and metrics for updates, errors, queues and checkpoint lag.
- **Learn:** SLI, SLO, percentiles, correlation IDs and metric cardinality.
- **Verify:** Trace a test edit without logging its content or credentials; record metrics and proposed alert conditions.
- **Interview practice:** How would you distinguish database latency from peer delivery latency?

### Lesson 35 — Run two backend instances

- **Build:** Add Redis Pub/Sub for low-latency inter-instance notification and ephemeral presence, using an existing local service if available or Docker if needed. Keep PostgreSQL authoritative.
- **Learn:** At-most-once delivery, outbox retries and reconciliation.
- **Verify:** Users connected to different instances converge; intentionally dropping a notification is repaired from the durable log; duplicate delivery is safe.
- **Interview practice:** Why is Redis Pub/Sub alone not a durable edit log?

### Lesson 36 — Test overload and recovery

- **Build:** Bound per-session and room queues and define overload/reconnect behavior; add focused load and failure experiments.
- **Learn:** Backpressure, slow consumers, hot rooms and failure budgets.
- **Verify:** A slow client is disconnected without unbounded memory growth and can resynchronize; document measured workloads and limitations.
- **Interview practice:** What would you measure before choosing WebFlux or partitioning a hot room?

### Lesson 37 — Backup, restore and deploy locally

- **Build:** Document and practice database backup/restoration, application configuration and containerized deployment where useful. Keep the existing PostgreSQL development setup intact.
- **Learn:** Recovery point/time objectives, secret handling, schema compatibility and rollback.
- **Verify:** Restore a backup into an isolated environment, verify known documents and permissions, and document measured recovery time. Deployment is not a claim of production availability.
- **Interview practice:** How does a process restart guarantee differ from disaster recovery?

## After the core roadmap

Comments, mentions, exports, object storage, Kafka and explicit room ownership are optional extensions. Each needs its own decision, access rules and failure tests. Kafka should solve a demonstrated downstream/replay problem; it is not required to mark the first product complete.

## Next lesson

Start with **Lesson 8: add Spring Security and protect the existing endpoints**. We will first explain the current risk, then implement the smallest secure teaching checkpoint and test both authenticated and anonymous requests. We will not claim workspace isolation until Lessons 12–15 are complete.
