# 4. Interview handbook: questions and explained answers

Use these answers as explanations, not a script to memorize. A finite list cannot cover every possible question. For each design question: clarify requirements, name the invariant, describe the normal path, analyze a failure, then discuss a trade-off and how you would test it. Separate **our proposed design** from things we have actually built and measured.

## A. Product and basic concepts

### 1. What problem does the project solve?

Teams can edit a shared rich-text document at the same time, continue temporarily offline, and reconnect without silently overwriting each other. The backend authenticates members, enforces permissions, saves accepted updates, distributes changes and supports revisions and recovery. The difficult part is coordinating these responsibilities across failures, not simply opening a WebSocket.

### 2. How is this different from sending the whole document on every keystroke?

Whole-document replacement is expensive and can lose concurrent edits. If Alice and Bob start from `Hello` and each saves a different complete string, the later save can overwrite the earlier one. Our client sends Yjs updates designed to merge. Metadata like a title uses a normal transaction and version check instead because a general-purpose CRDT is unnecessary there.

### 3. What is a client, server, database and replica?

The client is the browser application a user interacts with. The server checks access and coordinates storage and communication. The database holds committed records. A replica is a copy of state, such as a browser's local Yjs document. A local replica may temporarily differ from others during network failure.

### 4. What is the difference between HTTP and WebSocket?

HTTP normally models request and response, useful for login, document lists and metadata. WebSocket establishes a long-lived connection that lets either side send messages; it suits live updates and presence. WebSockets alone do not guarantee persistence, delivery, authorization, or correct merge behavior.

### 5. What is a race condition?

The result depends on how concurrent operations interleave. For example, a write and a permission revocation may both see an old membership unless they coordinate. Our design orders them using database locks and rechecks inside transactions. A concurrency test forces both possible orderings and checks the outcome.

### 6. What is a transaction?

A transaction groups database operations so they commit together or roll back together. Our accepted update, allocated sequence and outbox event commit as one unit. A transaction does not automatically include a separately running Node service or Redis; crossing those boundaries needs explicit recovery.

### 7. What does a Spring controller do? What is dependency injection?

A controller maps an HTTP request to an application call and turns the result into a response. It should not contain all business rules or raw SQL. Spring creates configured objects called beans; constructor injection supplies a controller's service dependency. This makes responsibilities explicit and allows focused tests.

### 8. Why use Spring MVC if this is real time?

The servlet stack supports WebSocket connections and is sufficient for an understandable first implementation. WebFlux may be appropriate with an end-to-end nonblocking workload and measured need, but adds a reactive model and does not make blocking JDBC safe on event-loop threads. Compare under realistic load rather than equating reactive with automatically faster.

## B. Collaboration and CRDTs

### 9. What is a CRDT?

A conflict-free replicated data type defines merge rules that let replicas converge when the required updates eventually reach them. Yjs represents edits as binary updates; its documented update operations tolerate duplicates and different delivery orders. Convergence means agreement on state, not necessarily that the result matches every person's intention. [Yjs updates](https://docs.yjs.dev/api/document-updates)

### 10. What happens when two people insert at the same position?

Both edits have CRDT identities and ordering rules, so replicas receiving the same necessary updates converge to one result. Do not promise that wall-clock time or the first WebSocket frame determines the visual order. We test actual behavior for the pinned editor and library version.

### 11. What happens if one user deletes content while another edits it?

The result depends on the CRDT's and editor binding's semantics, not a universal rule such as “delete wins.” Build a concrete example and test actual operations, including offline divergence and reordered delivery. A deterministic result can still surprise users; design UI and revision recovery around that possibility.

### 12. CRDT versus operational transformation?

OT transforms operations relative to concurrent operations using a coordination and ordering model. A CRDT uses data types and metadata whose merge rules support convergence. Both have nontrivial correctness, memory and product trade-offs, and both can support offline editing with appropriate architecture. We choose Yjs for its existing offline and editor ecosystem, not because OT is universally inferior.

### 13. What is eventual consistency?

Replicas may temporarily disagree but converge after the necessary accepted updates are delivered and processed, assuming compatible rules. It is a statement about a particular data path and assumptions. Permissions, metadata and restore decisions use authoritative database transactions, so labeling the whole system simply “eventually consistent” hides important distinctions.

### 14. What is a state vector? Is it a hash of document content?

A Yjs state vector summarizes known client clocks and helps compute missing updates. It is not a general content checksum and should not be used alone as proof that deletions and complete state match. Our baseline repair uses a checkpoint and committed log tail; we test insert and delete reconciliation explicitly.

### 15. Why not implement the CRDT in Java?

Correct distributed text editing involves ordering, identity, causality, deletion, offline merges and editor semantics. A proven library lets us focus on backend concerns while still learning the model. A Java service can store the binary data, but cannot reconstruct or validate Yjs content without a compatible implementation or separate engine.

### 16. What is the difference between a Yjs update and a WebSocket message?

An update is the CRDT payload describing changes. A WebSocket frame transports bytes; our application envelope adds a protocol version, document, epoch and update ID. These layers are independently versioned and validated. Our envelope is custom; stock `y-websocket` clients are not automatically compatible.

### 17. What does offline-first actually promise?

A previously loaded document can be changed locally, with changes stored in browser persistence when available, then reconciled later if authorization, epoch and schema allow it. Browser storage can be cleared, evicted or unavailable. A new device cannot open an uncached document while offline. A restore or revocation can reject automatic upload; the app must explain this and preserve recoverable local work where possible.

### 18. Why distinguish the pending update queue from the local Yjs document?

The document answers “what can I see locally?” The pending queue answers “which logical updates lack server acknowledgement?” A Yjs state can contain accepted, remote and pending operations together; blindly resending the full local state can hide ACK status and, after restore, reintroduce an old generation. Queue entries need stable IDs and independent persistence.

### 19. Does a valid Yjs update mean safe or valid rich text?

No. Successful parsing is not proof that all dependencies exist, that the editor schema accepts the result, or that the content respects size and security policies. Validate bounded inputs, supported state and editor schema; handle unresolved dependencies explicitly. This remains a feasibility risk to prove in our integration spike.

## C. Persistence, recovery and ordering

### 20. What exactly does `SAVED` mean?

The exact update has been accepted for the current epoch and committed at the configured PostgreSQL durability boundary. It does not mean every peer received it, a search index was updated, or the system survived every imaginable disaster. State which WAL, disk and replication failures the deployment covers.

### 21. What if the server crashes before saving?

There is no successful ACK. The client keeps the update in its pending queue and retries with the same ID. If the client also loses its local storage, recovery is not guaranteed; the UI should not falsely say the change was saved.

### 22. What if the database commits but the ACK is lost?

The client cannot know whether the request succeeded, so it retries with the same update ID and payload. A unique database constraint and stored digest allow the server to return the previous result without creating a second business event. A repeated ID with a different payload is a conflict. This is an uncertain outcome, not evidence of failure.

### 23. Why isn't CRDT idempotence enough?

Applying the same Yjs update twice can be harmless to CRDT content, but duplicate database rows, audit entries, notifications, billing counters or ACKs can still be wrong. Application-level idempotency protects the entire business operation and makes recovery observable.

### 24. Why use a per-document sequence when CRDT updates can arrive out of order?

The sequence is for a committed durable log, checkpoint boundaries, gap detection and replay. It is not the CRDT's identity or proof of human edit order. Allocate it in the same serialized transaction as the insert; otherwise an apparent high-water mark can skip an earlier uncommitted write.

### 25. What is an outbox and why is it needed?

If the database commits and the process crashes before publishing, connected peers may never receive a notification. Write an outbox record with the update in the database transaction. A worker later publishes and retries. Delivery may duplicate, so consumers deduplicate; an outbox does not by itself guarantee every subscriber receives a Pub/Sub event.

### 26. Why isn't Redis Pub/Sub enough for saved edits?

It delivers to subscribers currently listening and can lose messages for disconnected subscribers. Store accepted edits durably first. Use Pub/Sub for fast notification, and reconcile against the durable log periodically and on reconnect. [Redis Pub/Sub](https://redis.io/docs/latest/develop/pubsub/)

### 27. How does a connected client recover a missed message?

It tracks the last **contiguous** server sequence and periodically checks the durable log, not merely on socket reconnect. If the next sequence is missing, it requests a tail. If retention removed the tail, it loads a suitable full checkpoint and subsequent records. Duplicate events are harmless at the content and application layers.

### 28. What is a checkpoint? How is it different from a revision?

A checkpoint stores reconstructable state at an inclusive sequence to speed recovery. A named revision is a user-visible historical record with its own retention promise. Compaction reads through a fixed committed cutoff; later edits remain in the tail. A historical marker is not necessarily a standalone backup.

### 29. How do you restore without old offline work undoing the restore?

Create a fresh CRDT document from the revision's materialized supported content, advance the epoch transactionally and reject old-epoch updates. Do not automatically merge the old local Yjs state. Keep it as a recoverable draft for an authorized user. The cost is that offline work may require manual reconciliation.

### 30. What happens if compaction fails midway?

Until the new checkpoint metadata is committed, the old verified checkpoint and log remain the recovery path. If an object upload succeeded but publication failed, it is an orphan to clean up later. If checksums fail, stop using the checkpoint and alert. Never delete old data before verification and the configured retention window.

### 31. What is the difference between a crash guarantee and disaster recovery?

Synchronous WAL commit can protect against an application or database process crash within the storage assumptions. Async replica promotion may lose recently committed writes; loss of all copies requires backups or stronger replication. Define recovery point objective (how much data may be lost) and recovery time objective (how long restoration may take), then test them.

## D. Security, performance and operations

### 32. Authentication versus authorization?

Authentication establishes who is requesting. Authorization decides what that principal may do to a specific resource now. A valid session or unguessable document ID does not prove workspace membership. Check access on HTTP, WebSocket, search, revisions and exports.

### 33. How does a browser authenticate a WebSocket?

Our initial same-origin design uses the browser's secure session cookie during the handshake, plus a strict Origin allowlist and current membership checks. The standard browser constructor does not offer an arbitrary Authorization header parameter. CSRF defenses remain necessary for HTTP mutations; long-lived sockets also need expiry and revocation handling.

### 34. What if permission is revoked while a write is being processed?

Both write and revoke coordinate through the same membership lock and a documented lock order. If the write commits first, it was accepted before revocation; if revocation commits first, the later write fails. For receiving, close sessions and use authorization checks or bounded leases. Bytes already received or in flight cannot be recalled, nor can an offline copy be reliably erased.

### 35. Can users forge presence identities?

They should not. The server derives identity from the authenticated session and scopes presence to a permitted document, with limits and expiry. Client-supplied display metadata is treated as untrusted. Cursor offsets should be relative positions; they can become unresolvable when associated content is deleted.

### 36. What is backpressure and how do you handle a slow browser?

When production exceeds consumption, queues grow. Bound incoming messages, engine work and per-session outbound buffers; throttle low-value presence and disconnect an overloaded session before memory is exhausted. On reconnect it can recover from durable state. Browser WebSocket itself does not automatically solve backpressure.

### 37. How do you scale from one instance to several?

Keep PostgreSQL as authority, add instances and Redis notification channels, then repair missed notifications from the log. Ensure per-document serialization and dedupe work across processes, not just inside a Java map. Measure connections, queue depth, write latency, database load and hot rooms. Later room ownership needs leases and fencing so a stale owner cannot continue writing.

### 38. Why not add Kafka from day one? What would it change?

PostgreSQL already provides the initial atomic permission check, update append and ACK boundary. Kafka can be useful for replayable downstream processing when the volume or decoupling need is demonstrated. `acks=all` alone is not a complete durability plan: replica count, minimum in-sync replicas, failure configuration, retention and consumer behavior matter. A Kafka-first write boundary is a different design, not a drop-in switch.

### 39. How would you test it?

Test domain rules, real PostgreSQL transactions, cross-language protocol fixtures, browser convergence, offline/reconnect, permission boundaries and restore races. Inject lost ACKs, crash after commit, missed notifications and corrupt checkpoints. Run load tests with explicit hardware and workload. A unit test that merely mocks the database cannot prove transaction or lock behavior.

### 40. What metrics and SLOs would you use?

Measure commit and end-to-end delivery latency, active sockets, pending queue size, missed sequence repairs, failed/rejected writes, checkpoint age and database resource use. Start with explicit targets and measurement windows; distinguish an objective from observed performance. For example, p95 commit latency under a stated load is measurable, while “zero data loss in all failures” is not a credible unconditional claim.

### 41. What would you say to a recruiter in one minute?

“I am building a collaborative workspace with a Java backend and a Yjs-based rich-text editor. Users can edit together, work temporarily offline and safely reconnect. The backend controls workspace access, commits edits before marking them saved, and supports revisions. The interesting engineering work is handling lost acknowledgements, permission changes and restoring history without letting stale offline edits silently overwrite it. I validate those guarantees with failure tests.” Use present tense only for features actually completed.

### 42. What would a principal engineer challenge in this design?

They would ask for the exact linearization point, failure model, capacity limits, multi-instance authorization ordering, how partial Yjs dependencies are validated, and whether checkpoints and history can truly be reconstructed. They would also question the operational cost of the Java/Node split. A strong answer identifies what is proven, what is a design hypothesis and which test or simpler alternative resolves the uncertainty.
