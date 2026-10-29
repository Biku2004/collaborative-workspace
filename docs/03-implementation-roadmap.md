# 3. Feature-by-feature implementation roadmap

Lessons 1–7 have implemented the initial document API and PostgreSQL persistence. The rows below are high-level architecture steps, not the same numbering as individual lessons. The [next lessons roadmap](07-next-lessons-roadmap.md) breaks the remaining work into one-feature lessons.

## 3.1 Definition of done for every lesson

The lesson explains the problem, prerequisite concepts, choices, flow, invariant, exact files, complete code, PowerShell commands and expected output. We run the feature, test a successful and meaningful negative/failure path, interpret results, and provide common mistakes, interview Q&A and a small exercise. Document limitations and update decisions before proceeding. Ask before making a product decision that changes the agreed behavior.

## 3.2 Milestone A: foundation and a vertical slice

| Step | Feature, prerequisites, likely files | Demonstration / acceptance |
|---|---|---|
| 0 | **Environment**: check installed Java, Maven, Node and local PostgreSQL; explain JDK vs JVM, environment variables and build lifecycle. Java 24 and Maven 3.9.8 are already available; recheck framework compatibility when versions are pinned. Docker is available for missing services when needed. | Tool versions are documented; no existing databases are changed and no secrets are committed. |
| 1 | **Hello document**: generate one Spring Boot application, `DocumentController`, response DTO, service, temporary in-memory store; explain beans, constructor injection, HTTP and JSON. | Create/get one sample document; invalid ID has a clear response; restart loss is explained. |
| 2 | **Validation and errors**: request DTO, Jakarta validation, exception handler. Explain boundary validation and status codes. | Invalid title fails predictably without leaking stack traces. |
| 3 | **PostgreSQL**: use the existing local PostgreSQL installation, with a dedicated project database and user, Flyway migrations and a JDBC repository. Explain SQL, transactions, connection pools and persistence. | Create/get persists across application restart; migration applies cleanly; integration tests use a separate test database and never modify other existing databases. |
| 4 | **Identity and sessions**: Spring Security, password hashing or a local development identity provider, session cookies and CSRF; no hand-built token cryptography. | Two users sign in, anonymous access denied, logout invalidates session; security configuration exercised. |
| 5 | **Workspaces and membership**: service/repository tables and role policy. Explain authorization vs authentication and transaction constraints. | Cross-tenant requests denied; editor cannot change membership; final owner cannot be removed. |
| 6 | **Documents inside workspace**: create/list/rename and archive; tenancy filters and metadata version. | Viewer cannot create or rename; stale rename rejected; archived data follows documented rules. |
| 7 | **Yjs feasibility spike**: isolated Node scripts with two Y.Doc instances; inserts, deletes, duplicate and out-of-order updates, state vectors, missing dependencies, binary round trip and UTF-16. | Reproducible convergence experiment and written limitations. No networking or durability claim yet. |
| 8 | **Private document engine**: minimal bounded local service using pinned Yjs version; API for validating/reconstructing supported state and extracting text. Explain trust and process boundaries. | Known fixtures round-trip; malformed and oversized input fails; timeouts/restart do not corrupt committed state. Decide go/no-go for Java custom provider. |
| 9 | **Socket room**: one Spring WebSocket endpoint, session registry and custom protocol HELLO; explain handshake, Origin, cookies, close and heartbeat. | Authorized clients connect; unauthenticated, cross-origin, cross-workspace clients denied. This step does not yet claim durable edits. |
| 10 | **Single-instance durable plain-text update**: committed append, stable update IDs, ACK after commit and local peer delivery; PostgreSQL transaction and outbox. | Two isolated browsers converge; restart recovers; a lost ACK and duplicate retry create exactly one application record; viewer cannot submit. |
| 11 | **Reconnect and offline queue**: IndexedDB plus persistent outbox, reconnect state machine and gap repair. | Two users edit independently offline then reconnect; compare full content including deletes; pending status persists through reload; epoch and permission rejection preserve recoverable draft. |
| 12 | **Rich-text integration**: TipTap/ProseMirror + Yjs binding, pinned editor schema and minimal formatting toolbar. Explain shared XML model, editor transactions, conflict semantics and schema migration. | Two browsers concurrently edit paragraphs, bold/italic and lists; reload/offline/reconnect preserve supported structure; old/invalid schema rejected visibly. |

The default plain-text spike is a small prerequisite and verification tool; Step 12 is a required core feature. The rich-text editor will initially support paragraphs, headings, bold, italic and lists. Images, arbitrary HTML, tables and complex embeds are deferred until their security and migration rules are designed.

## 3.3 Milestone B: complete user experience and recoverability

| Step | Feature and key concept | Acceptance |
|---|---|---|
| 13 | Presence: cursor/selection with relative positions, bounded frequency and TTL. | Two users see authenticated collaborator identities; abrupt loss expires presence; malformed/spoofed data rejected. |
| 14 | Checkpoints: inclusive cutoff, checksum, worker retries, concurrency. | Writes during compaction survive, restore from checkpoint + tail yields identical supported document; corruption detected. |
| 15 | Named revisions: fixed committed boundary, immutable full content. | Revision lists and previews are permission checked; a revision excludes unsaved local work and says so. |
| 16 | Restore with new epoch: lock, new state identity, audit, old-client handling. | Concurrent edit/restore has an explainable transaction order; stale offline changes never silently reappear; preserved draft can be recovered by an authorized user. |
| 17 | Permission revocation: lock ordering, session closure and read lease. | A write racing revocation is ordered deterministically; no accepted write after revocation; socket closes within the documented bound. |
| 18 | Search: committed-state projection, ACL filtering, freshness indicator. | Unauthorized document never appears; lag and projection failure do not affect durable edits; rebuild works. |
| 19 | Product polish: pending/saved/offline/rejected indicators, accessibility, basic document list. | Keyboard usage, clear errors, no silent draft loss; empty/loading states and common failure paths tested. |

## 3.4 Milestone C: engineering depth after the single instance works

| Step | Feature | Acceptance |
|---|---|---|
| 20 | Observability: structured logs, redacted trace IDs, latency histograms, queue depth and lag. | Trace a test edit; no tokens or document text in logs; dashboard and alert thresholds documented. |
| 21 | Multi-instance fanout: second Java instance, Redis Pub/Sub for notifications and awareness; database remains authoritative. | Different instances converge; drop Redis notifications without closing sockets and periodic repair closes gaps; outbox replay tolerates duplicates. |
| 22 | Backpressure and overload: bounded queues, rate/size limits, priority and resync. | Slow consumer is disconnected without exhausting server memory; reconnect rebuilds correctly; pending edits are preserved. |
| 23 | Reliability: crash injection, DB loss, backup/restore drill and capacity test. | Measured results, failure windows, recovery time and configured durability boundary documented honestly. |
| 24 | Deployment: container images, TLS reverse proxy, managed dependencies/secrets, rolling upgrade. | Reproducible deployment and rollback, schema compatibility and smoke test; no public release required. |

Optional extensions in separate decision records: object storage for large checkpoints; Kafka for a proven downstream event/replay need; anchored comments; export; hierarchical document permissions; per-room ownership with fencing. Each adds its own failure tests.

## 3.5 Proposed repository as it grows

```text
collaborative-workspace/
  README.md                         # setup and learning index
  docs/                             # these guides, ADRs, runbooks
  backend/                          # one Java application
    src/main/java/.../              # identity, workspace, document, collaboration
    src/main/resources/db/migration/
    src/test/                       # integration and unit tests
  document-engine/                  # private Node + Yjs service and fixture tests
  web/                              # TypeScript, Yjs and rich-text client
  contracts/                        # socket schema and shared fixtures
  infra/                            # Compose, later deployment definitions
  tests/                            # multi-client and failure experiments
```

Create folders only when needed. Spring packages should follow feature boundaries; controllers call application services, which use domain rules and repositories. The Node engine is not externally exposed or authorized to commit data. The final structure may change after the spike.

Local development uses the existing PostgreSQL server rather than a PostgreSQL Docker container. Test data belongs in a separate dedicated local test database. For other dependencies, first check whether they are installed and usable; use Docker for missing services as they become necessary. Optional isolated container-based integration testing may be introduced later with a clear explanation of its purpose.

## 3.6 Learning dependencies

Java objects/interfaces → HTTP/JSON → Spring beans/controllers → SQL/transactions → sessions and authorization → WebSocket lifecycle → Yjs/CRDT → persistence and retries → rich-text schema → distributed operations. You do not need to understand all of them before starting; each lesson introduces its own prerequisites. If Java basics are unfamiliar, we can start with a short bridge lesson before Step 0.

## 3.7 Risk and contingency gates

- **After Step 7–8:** confirm that engine validation, pending dependencies, encoding, and candidate isolation work for the pinned Yjs versions. If not, use a conventional Node/Yjs collaboration server and keep Java responsible for the business API, authorization and persistence orchestration; write a new decision record and adapt guarantees. Do not pretend arbitrary `y-websocket` clients can talk to a custom Java protocol.
- **After Step 11:** prove pending updates survive reload, retry, deduplication and reconnect, including delete-only updates, before implementing rich text.
- **After Step 12:** verify editor schema behavior under concurrent invalid structures. Limit supported content or add an authoritative validation strategy if meaningful validation cannot be shown.
- **After Step 16:** verify old-epoch data is quarantined and recoverable before enabling restore.
- **Before multi-instance:** demonstrate ordered durable replay and per-document lock behavior with two processes, not just an in-memory lock.

## 3.8 The first actual coding lesson

Lessons 1–7 are complete; see the [learning checklist](00-learning-checklist.md). The next lesson introduces Spring Security, followed by identity, workspaces, collaboration and the frontend in the [numbered roadmap](07-next-lessons-roadmap.md).
