# 5. Verification, security, and operations

This is the verification plan. No tests, load measurements, availability guarantees or deployment are claimed yet. Test examples refer to proposed behavior; the implementation must produce actual evidence before claiming compliance.

## 5.1 What does success mean?

The product is ready for a portfolio demonstration when two authorized users can concurrently edit supported rich text, safely recover from temporary disconnection, see accurate save state and presence, inspect a revision, and restore without reintroducing old-epoch updates. A viewer cannot write; a revoked member cannot continue submitting changes. Application restart and missed notification tests recover all acknowledged updates within the configured database failure model.

Evidence: test results, repeatable commands, logs without document contents, a short architecture diagram, a failure demonstration, measured limits and honest constraints. Features not implemented are marked as future work.

## 5.2 Test layers

| Layer | What to test | Example |
|---|---|---|
| Unit | Pure policy, validation, state machine | Viewer role cannot submit; invalid transition rejected |
| Integration | Real database, transactions, constraints | Two simultaneous retries create one update and one outbox event |
| Contract | Java/TypeScript/Node message compatibility | Shared versioned JSON and binary fixtures, corrupt messages |
| Convergence | Yjs/editor semantics with realistic clients | Two independent replicas edit, delete, reconnect and compare full rich structure |
| Browser end-to-end | Real user flow | Two isolated browser contexts, offline toggle, status and reload |
| Security | All access paths | Wrong tenant cannot read via HTTP, socket, search or revisions |
| Failure/recovery | Crash boundaries and uncertain outcomes | Kill after database commit before ACK, retry same ID |
| Load | Realistic workload with bounded resources | Slow readers and hot rooms while measuring percentiles and memory |
| Migration | Schema and protocol upgrades | Old client gets a clear incompatible-version response; database migration/rollback tested |

Testcontainers launches a real database for integration tests; browser tests must use isolated contexts and should disable or account for Yjs cross-tab communication so passing tests cannot bypass the server. A one-process in-memory test cannot prove multi-instance coordination.

## 5.3 Failure injection matrix

| Failure / race | Expected behavior | Evidence |
|---|---|---|
| Crash before commit | No ACK; local pending update retries | Database has no accepted row; eventual save succeeds |
| Commit succeeds, ACK lost | Outcome is unknown; same ID resolves to original sequence | Exactly one update/outbox effect |
| Duplicate ID with altered bytes | Reject conflict | Existing committed bytes unchanged |
| Engine validation succeeds, DB rolls back | Candidate discarded; no ACK or accepted broadcast | Reconstructed committed state excludes candidate |
| Engine times out/crashes | Bounded failure; pending changes retained; fail closed | Process restart and replay recover accepted state |
| Malformed, huge or unresolved Yjs update | Reject or explicitly defer by protocol; protect resources | CPU/memory limits and no unsafe broadcast |
| Commit succeeds, process crashes before notification | Outbox retries; periodic repair fills missing data | Peer eventually obtains committed update |
| Pub/Sub drops event while connection stays open | Last contiguous cursor detects/repairs gap | Peer converges without reconnect |
| Client sends 100 before 99 | Cursor does not advance past gap | Replay eventually applies both correctly |
| Snapshot job races writes | Snapshot includes exactly committed prefix H | Tail after H exists; reconstructed content matches |
| Checkpoint checksum fails | Ignore corrupted checkpoint; alert/recover from verified data | Recovery test and diagnostic event |
| Restore races live write | Shared document lock orders transactions | Write lands before restore or rejected as stale after it |
| Offline old epoch reconnects | No automatic merge; preserved recovery draft | Restored content stays intact and UI explains next action |
| Revocation races write | Shared membership lock orders decisions | No accepted write ordered after revoke |
| One browser reads slowly | Queue cap triggers disconnect/resync | Bounded memory; other users remain healthy |
| Database unavailable | No false saved ACK; reject/retry and retain local work | No silent data loss; error observable |
| Two instances process same room | Shared database lock and durable replay coordinate | Consistency with both processes and actual DB |
| Local IndexedDB cleared | Tell user unacknowledged local edits may be lost | No misleading saved indicator |

## 5.4 Security test checklist

- Check tenant scope and roles for every API, WebSocket message, history, search and background projection; fuzz document IDs across tenants.
- Validate handshake Origin against explicit allowed origins, authenticated session and expiry; ensure HTTP CSRF defense for cookie-authenticated mutations. Use WSS/HTTPS in deployment.
- Recheck authorization at the correct write boundary. Close sessions and invalidate read leases on revocation; describe the actual delay and in-flight limitation.
- Restrict frame and decoded payload size, message rate, connections per principal, queue length, CPU time, engine memory and reconnect rate. Test malformed encodings and unsupported versions.
- Derive actor and presence identity from server session. Do not log tokens, cookies, raw updates or document text. Redact secrets and sanitize logs.
- Pin and scan dependencies and images; keep Node engine private and use least-privilege database accounts. Restrict editor schema and sanitize rendered content according to its supported nodes.
- Test backup access, audit integrity, secret rotation and retention. Offline copies remain outside server recall; avoid promising remote deletion.

Use [OWASP WebSocket guidance](https://cheatsheetseries.owasp.org/cheatsheets/WebSocket_Security_Cheat_Sheet.html) as a review reference; apply controls to this actual architecture and its threat model.

## 5.5 Proposed initial measurements and targets

These are **hypotheses for a local portfolio deployment**, not measured guarantees or public production promises. Refine after the implementation and workload are known.

| Indicator | Initial goal and measurement |
|---|---|
| Commit latency | p95 under 250 ms for small updates under the reference workload; server receive to database commit, 5-minute window |
| Peer delivery | p95 under 500 ms for connected peers, excluding documented network outages; commit to peer apply, same window |
| Lost acknowledged edits | Zero in gateway-process crash tests with configured synchronous DB commit; explicitly excludes storage destruction and async failover |
| Stale permission | Write acceptance ordered against revocation in DB; multi-instance read invalidation objective within 5 seconds plus in-flight delivery |
| Recovery | A restarted local application reconstructs a reference document; record actual time and hardware |
| Queue memory | Fixed configurable per-session/room limits; verify through slow-consumer test |

Define sample size, warm-up, error counts, percentile method and what is excluded. Use an SLI to measure actual behavior, an SLO as an agreed objective, and an SLA only if a real contract exists. Do not treat a local database container as highly available.

## 5.6 A worked capacity estimate

Assume 100 concurrent editors, each producing one coalesced update per second averaging 1 KiB encoded payload. Input is about 100 KiB/s, or 8.24 GiB/day if that load continues all day (100 × 1,024 × 86,400 / 2^30). Database indexes, WAL, revisions, replicas, backups, protocol overhead and metadata increase actual storage substantially. With 10 recipients per update, peer outbound payload alone is roughly 1,000 KiB/s before framing and retries. These are assumptions, not forecasts; update size, batching, room distribution and traffic are measured later.

Potential bottlenecks: per-document transaction serialization, database commit throughput, Node engine CPU/memory, socket count, outbound bandwidth, presence frequency, index rebuild and browser memory. A hot room differs from many small rooms; benchmark both. Capacity is constrained by the narrowest measured stage, not just CPU.

## 5.7 Logging, traces, metrics and alerts

Structured logs: timestamp, service, event name, request/trace ID, document pseudonymous identifier, result code and duration. Avoid high-cardinality document or user IDs as metric labels. Record no document bodies or credentials. Traces correlate HTTP, WebSocket processing, database transaction and engine calls without claiming that a browser rendered the update.

Suggested metrics: active sessions, accepted/rejected update counts by reason, commit latency histogram, pending outbox age, queue depth and overflow, sequence gap repairs, checkpoint age/failures, engine timeouts, revocation close delay, DB connections and reconnect counts. Alert on sustained errors, growing durable backlog, inability to commit, repeated checksum failures and resource exhaustion. Alerts require an owner and a runbook; select thresholds after measurements.

## 5.8 Backup and recovery runbook

1. Document deployment topology, DB version, WAL/replication settings, retention and failure assumptions. Encrypt backups and restrict access.
2. Back up database and all referenced immutable objects consistently, including metadata, revisions, epoch and dedupe information. Record restoration point and checksums.
3. In an isolated environment, restore the database and objects, apply compatible migrations, and start a compatible engine and application.
4. Verify membership isolation, checkpoint checksums, revision readability and replay through the recorded committed boundary; compare known test documents.
5. Record time to restore, missing data window and errors. Repeat at a planned interval, and after relevant architecture changes.

Backups prove nothing until a restoration is tested. Async replication, database corruption, bad migrations and an accidental deletion have distinct recovery implications.

## 5.9 Release and rollback

Use additive migrations first, deploy code compatible with both old and new schema, backfill, observe, then remove old columns only after old versions are gone. Version WebSocket protocol and editor schema independently. Incompatible clients receive an actionable upgrade message; preserve their unsent local data. Roll back application versions only when the data schema and protocol support it. Define a repair path for a failed migration before deployment.

For a demonstration, capture: initial state, two isolated clients, offline edit, reconnect, saved indicator, lost-ACK recovery, revoked viewer, and restore with old-epoch quarantine. Link to exact test logs and state the deployment's real failure boundary.
