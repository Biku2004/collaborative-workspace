# Lesson 36 — Bound overload and verify recovery

## What we are implementing

Keep collaboration usable when a reader falls behind or one document becomes busy. A slow socket can lose its connection and queued notifications; its saved edits remain in PostgreSQL, and the browser reconnects, verifies access and repairs its state. A busy document has an explicit local participant cap, and its senders yield worker time to other sockets.

This lesson adds bounded failure experiments and a measured local workload. It does not establish a maximum supported user count or production availability. Rich text remains behind its existing default-off gate.

## Limits and behavior

| Resource | Local limit | When exhausted |
|---|---|---|
| Registered connections | 256 per backend, eight per user | Reject the new connection with 1013 |
| Participants in one document room | 32 per backend | Reject JOIN with 1013; existing participants retain their slots |
| Waiting messages per socket | 16 messages and 16,384 UTF-8 payload bytes | Evict disposable presence first, then close an overflowing durable sender with 4500 |
| Waiting presence per socket | Four messages, within the same byte/message budget | Drop presence without closing the durable-edit connection |
| Sender workers | Four workers, 128 waiting tasks | A rejected durable task closes that socket and clears its queue |
| Consecutive sends per worker turn | Four | Requeue the sender at the executor's tail so waiting sockets get a turn |
| Tomcat blocking write | 2,500 ms | Transport failure releases the writer; the browser can reconnect |
| Tomcat abnormal close-frame send | 250 ms | Stop waiting for the close frame to be written |
| Tomcat close handshake | 1,000 ms configured | Tomcat's periodic processing eventually removes an unresponsive closing connection |

Limits are per backend, including room admission. Redis does not implement distributed quotas. A 32-member room caps current fanout at 32 recipients; the waiting payload among those members is at most 512 KiB. Across 256 registered sockets, the corresponding waiting UTF-8 payload budget is 4 MiB, with at most four sender messages in flight. These figures exclude Java objects/string representation, native buffers, closing connections, database work, inbound buffers and browser memory. They are not heap/RSS bounds.

Room admission, departure and removal share an atomic reservation boundary, so concurrent JOINs cannot overbook the room. Closing or leaving releases its reservation. The sender retains a single drain task per socket; yielding after a batch preserves message order while preventing a continuously nonempty queue from monopolizing a worker indefinitely. If continuation scheduling fails, durable messages trigger disconnection; a continuation containing only presence can be discarded without closing the connection.

Tomcat's native session properties configure blocking writes and closing behavior. An async-container send timeout alone would not control our blocking `sendMessage` path. See the pinned server's [WebSocket timeout documentation](https://tomcat.apache.org/tomcat-11.0-doc/web-socket-howto.html). The deadlines are transport settings, not an end-to-end latency SLA: database authorization, scheduler delays, worker queuing and native cleanup still take time. Four simultaneously stalled writes can delay other rooms until workers become available. This is bounded shared scheduling, not room-isolated execution.

## What the user sees during overload

1. The server keeps the document update, head and outbox commit separate from peer delivery. A slow recipient cannot roll back a committed edit.
2. Presence can be dropped. Its next refresh or expiry repairs the cursor display.
3. An overflowing durable recipient closes with application code 4500. An admission rejection uses 1013. Both take the browser's existing retry path, with exponential delay and jitter up to roughly 30 seconds between attempts.
4. Before reconnecting, the browser verifies its session and document baseline, including current permissions, format and epoch. It then joins and synchronizes missing committed sequences.
5. Pending local updates retain their IDs for safe retries. Access-denied code 1008 and epoch changes retain their existing protected-draft behavior; overload must not bypass those checks.

Closing a socket is not deleting its document. A relay marker still means an attempted delivery, not confirmed receipt. HEAD/SYNC repair remains necessary even with Redis enabled.

## Find the code

The backend paths below start at `backend/src/main/java/com/example/collaborativeworkspace`:

| File | Responsibility |
|---|---|
| `collaboration/transport/DocumentRoomHub.java` | Atomic per-room admission/release, existing user/global admission and bounded sender executor |
| `collaboration/transport/WorkspaceSocketHandler.java` | Apply transport settings at establishment and use room admission on JOIN/LEAVE |
| `collaboration/transport/SocketTransportLimits.java` | Native Tomcat write/close properties, including decorated sessions |
| `collaboration/transport/BoundedSocketSender.java` | UTF-8 queue limits, presence eviction, four-message turns and continuation rejection |
| `observability/WorkspaceTelemetry.java` | Bounded `room_rejected` transport counter, alongside existing queue/executor counters |

The browser's `frontend/src/features/editor/provider.ts` already implements retry and durable gap repair. No production fault-control endpoint or frontend bypass is introduced.

## Reproducible experiments

Build the private Yjs engine and frontend once using the commands from Lessons 18 and 35. The experiments use the existing dedicated test PostgreSQL database. They refuse to run if database isolation cannot be verified and clean up only their generated fixtures.

From `backend` in PowerShell:

```powershell
. ./.env.local.ps1
$env:WORKSPACE_RUN_LOAD_TESTS = 'true'
$env:WORKSPACE_RUN_OVERLOAD_TESTS = 'true'
mvn -B '-Dtest=BoundedSocketSenderTest,SocketTransportLimitsTest,RoomCapacityTest,HotRoomLoadIntegrationTest,OverloadRecoveryIntegrationTest' test
```

The load and overload flags are opt-in. Ordinary `mvn test` does not launch the new network/browser experiments. Unit capacity and sender tests always run.

**Queue and scheduling failures:** unit tests hold a writer, fill its message or byte budget, and verify one close plus queue cleanup. They cover UTF-8 multibyte accounting, presence eviction, executor rejection, continuation rejection and yielding to a socket from another room. Concurrent room reservations check the cap, release/rejoin and independent room capacity.

**One deliberately slow real browser connection:** `OverloadRecoveryIntegrationTest` starts an actual backend, private Yjs engine, Vite and two independent Chromium contexts. A test-only session decorator holds Bob's committed-update send; valid duplicate committed notifications fill his queue. The test observes the real close, then the browser's automatic reconnect and synchronization, and verifies both replicas converge after a further saved edit. The test also checks native timeout properties on the real session. Its authenticated loopback control surface exists only in test code.

This decorator is intentional. Pausing JavaScript message handling does not reliably make a server socket slow: browser/TCP buffers can absorb this small document workload. The experiment proves overload closure and browser recovery deterministically. It does not prove the native write deadline under every network or operating-system failure.

**A paced hot room:** `HotRoomLoadIntegrationTest` starts 16 actual JDK WebSocket consumers across two accounts, plus one cold-room consumer. Twelve sequential real-engine/database commits fan out to all 16. A cold-room SYNC is sent immediately after each commit while hot-room delivery proceeds. Every consumer must observe contiguous sequences, and neither overflow nor executor rejection is expected for this workload. Transport queues are sampled every 2 ms and fixed limits remain asserted in code.

The same valid Yjs bytes use distinct request IDs, so the workload exercises validation, commits, authorization and fanout without growing document content. Acceptance timing measures the direct Java service call and excludes browser-to-server submission latency. Fanout timing includes acceptance, relay polling and arrival at the slowest of the 16 test listeners. Cold SYNC timing measures request send to listener arrival. None measures browser rendering.

The test prints one content-free `HOT_ROOM_MEASUREMENT` JSON record. The latest recorded measurements and verification results are described below. With only 12 samples, nearest-rank p95 equals the maximum; these values are useful reproduction evidence, not a population estimate.

## Recorded local evidence

Recorded on 2026-11-16; raw values are in [the results artifact](../../ops/load/lesson36-results.json).

| Measurement, milliseconds | p50 | p95 / maximum |
|---|---:|---:|
| Direct service acceptance | 156.3 | 200.6 |
| Submit start to arrival at all 16 hot-room peers | 219.8 | 284.4 |
| Separate-room SYNC | 3.1 | 12.7 |

The sampled aggregate peak was 12 waiting messages, 3,012 UTF-8 bytes and 12 sender tasks. No overflow or executor rejection occurred in this paced workload. In the separate injected-stall experiment, the slow browser reached 16 waiting messages and 3,804 bytes, received close code 4500, then automatically reconnected, repaired committed state and saved another edit. Native deadline properties were checked on three real sessions.

The final regression run passed **168 Java tests with zero failures, errors or skips**, including the new workload/recovery fixtures and the existing browser/offline scenarios. The frontend TypeScript and Vite production build also passed.

Docker remained stopped for this verification. The Redis-dependent `MultiInstanceIntegrationTest` is excluded from the current regression command; its Lesson 35 evidence remains separate. To repeat all remaining checks, also enable the existing database, engine and browser flags from Lesson 35 and run `mvn -B '-Dtest=!MultiInstanceIntegrationTest' test`. The earlier 2026-11-13 full run was interrupted and is not treated as a completed verification.

## Diagnostics and next decisions

Use the protected Lesson 34 metrics: `workspace.connections`, `workspace.sender.tasks`, `workspace.sender.active`, `workspace.socket.queued.messages`, `workspace.socket.queued.bytes`, `workspace.operation` and `workspace.transport`. `room_rejected`, `queue_overflow`, `executor_rejected`, `presence_dropped` and `socket_failed` distinguish admission pressure, waiting-queue pressure and transport failure. Check outbox backlog and age independently of socket counts. Sampled queue peaks can miss shorter spikes; passing queue assertions is stronger than treating a sampled zero as proof of no work.

Before increasing these limits or selecting WebFlux/room partitioning, measure edit rate and payload size, active rooms and fanout distribution, worker wait time, authorization/database query latency, engine saturation, reconnect rate, sustained queue pressure and process memory under slow readers. Repeat across representative network delays, growing documents and permission changes. Async networking alone does not remove expensive authorization, hot-room fanout or durable-log contention.

No schema or dependency change is required. Rollback means restarting the previous application version with its existing local configuration. Runbooks and verified disaster recovery remain **Lesson 37 — backup, restore and local deployment**.
