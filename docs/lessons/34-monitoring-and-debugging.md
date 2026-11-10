# Lesson 34 — Monitoring and debugging

This lesson makes a slow or failed collaborative edit diagnosable. It adds structured JSON logs, a persisted operation correlation, stage latency histograms, transport pressure counters and cached checkpoint/search/outbox lag. It is a local learning milestone. Lessons 35–37, measured capacity and the existing gated-rich-text limitations remain open.

## What a user gains

Editing and synchronization keep the same protocol. When a problem occurs, the operator can distinguish engine validation, database acceptance, pending relay work and socket transport. A successful save still means the update transaction committed. A successful socket send means the transport call returned; it does **not** prove another browser applied or durably stored the update.

## Implementation and file responsibilities

| Location under the Java application package | Responsibility |
|---|---|
| `observability/WorkspaceTelemetry.java` | Fixed stage/outcome labels, durations, transport counters, correlation scope and allowlisted reason codes |
| `observability/RequestDiagnosticsFilter.java` | Server-generated `X-Correlation-ID`, HTTP status count and content-free request completion log, including security denials |
| `observability/ProjectionMetrics.java` | Scheduled aggregate SQL, cached numeric gauges and snapshot freshness |
| `collaboration/updates/DocumentUpdateService.java` | Timed acceptance, speculative validation and transaction execution including commit |
| `collaboration/updates/DocumentUpdateRepository.java` | Persist/read the correlation with the durable outbox event |
| `collaboration/transport/DocumentDeliveryService.java` | Resume the correlation at relay; time authorized replay |
| `collaboration/transport/DocumentRoomHub.java` | Connection, shared executor and aggregate socket queue gauges |
| `collaboration/transport/BoundedSocketSender.java` | Carry correlation through the async queue, time authorized sends and count pressure/failures |
| `collaboration/engine/YjsEngineClient.java` | Time private-engine HTTP exchanges without recording requests or responses |
| `search/DocumentSearchProjector.java` and schedulers | Index attempt and poll success/failure measurements |

Spring Boot Actuator and the Micrometer Prometheus registry provide the metric endpoints. V19 adds `document_update_outbox.correlation_id`, backfills old events with their existing random event IDs, supplies a UUID default for older callers, and indexes pending-event age. It preserves updates, checkpoints and pending notifications.

## Following one edit

1. `accept` creates a server-generated correlation ID. It includes authorization, speculative validation, bounded retries and the eventual result. A duplicate submission is a successful acceptance but need not create another update or outbox event.
2. `validate` and its nested `engine` exchange use that ID. Validation is speculative and happens outside the write transaction.
3. `commit` wraps `TransactionTemplate.execute`, including lock acquisition, writes and actual transaction completion. The correlation is inserted with the outbox row in that transaction. A rollback leaves no new outbox event. A commit error remains a failure/unknown outcome and the existing stable-update-ID retry contract applies.
4. The relay reads the persisted ID after commit, even following a JVM restart. `relay` measures serialization, enqueue attempts and marking the event attempted inside the relay transaction. Its success is **not** a client receipt or proof that the outer relay transaction has committed.
5. Each targeted socket queue carries the ID explicitly. `socket_send` measures current-access checks and the actual send call on the sender thread. Zero peers produces no socket-send stage. Multiple peers or replayed relay work can produce several stages with the same ID.

These are **correlated log traces with stage durations**, available without a collector. They do not export OpenTelemetry spans or measure client rendering/network round-trip latency. HTTP operations inherit the generated request correlation; each later WebSocket submission starts its own operation. Incoming correlation headers are ignored. Scope cleanup restores nested context and prevents pooled-thread leakage.

After locating an operation ID, filter a captured backend log in PowerShell:

```powershell
$operationId = '<server-generated UUID>'
Get-Content -LiteralPath '.\target\lesson34-full-verification.log' |
    Where-Object { $_.StartsWith('{') } |
    ForEach-Object { try { $_ | ConvertFrom-Json -ErrorAction Stop } catch {} } |
    Where-Object { $_.correlationId -eq $operationId } |
    Select-Object '@timestamp', stage, outcome, reason, durationMs
```

For a known committed update ID, a database operator can retrieve the correlation without reading document contents. Bind the UUID parameters through the database client:

```sql
SELECT o.correlation_id, u.epoch, u.sequence, u.committed_at, o.published_at
FROM document_update_outbox o
JOIN document_updates u USING (document_id, epoch, sequence)
WHERE u.document_id = :document_id AND u.update_id = :update_id;
```

## Metrics and their limits

| Meter in the JSON metrics endpoint | Meaning |
|---|---|
| `workspace.operation` | Histogram/timer, seconds on Prometheus; tags are `stage` and `outcome` (`success`, `rejected`, `failed`) |
| `workspace.http.requests` | Completed HTTP request counts by bounded numeric status; no URL/method/query labels |
| `workspace.transport` | Counters for connection rejection, queue overflow, executor rejection, presence drops, send denial/failure and socket closure |
| `workspace.connections` | Current locally registered sockets, bounded by 256 |
| `workspace.sender.tasks`, `workspace.sender.active` | Shared sender executor's waiting tasks and active workers |
| `workspace.socket.queued.messages`, `workspace.socket.queued.bytes` | Sums of waiting per-socket queues; exclude the one possible in-flight message per socket |
| `workspace.outbox.pending`, `workspace.outbox.oldest.seconds` | Unattempted relay rows and oldest age; an empty queue has age zero |
| `workspace.checkpoint.lag.updates`, `workspace.checkpoint.pending.documents` | Maximum current-epoch head minus latest immutable checkpoint sequence, and documents behind |
| `workspace.search.lag.updates`, `workspace.search.pending.documents`, `workspace.search.failed.documents` | Maximum current-epoch index gap, pending document count and pending documents with recorded index failures |
| `workspace.search.indexing.enabled`, `workspace.delivery.relay.enabled` | 1 for an enabled configured worker, 0 for paused; not proof the worker is healthy |
| `workspace.projection.snapshot.age.seconds` | Time since the last successful aggregate SQL refresh |

Projection metrics refresh every 10 seconds by default, with a two-second JDBC query timeout. Scrapes only read cached numbers. Before the first successful query the lag gauges and snapshot age are `NaN`. A failed poll retains the last values and increments the failed `projection_poll` timer; age keeps growing. This prevents database failure from appearing as a fresh zero backlog. The aggregate is a single statement and does not load text, payloads, email addresses or session data.

Checkpoint/search aggregates include active workspace streams in supported formats. Search missing an index after an epoch transition uses the new epoch's head as its gap; an unindexed empty stream can have zero update gap while still counting as pending. Old-epoch checkpoints cannot reduce current-epoch lag. Checkpoints are requested on demand, so checkpoint lag is informational until a maintenance policy exists. Outbox totals include all pending stored events, including archived/older-epoch notifications.

Nested stages overlap; do not add their durations or failure counters to calculate edit totals. Use `accept` for acceptance rate/failures. Use `engine` and `commit` to diagnose components. Successful duplicates count in acceptance latency. Search publication races returning false count as completed index attempts, not necessarily published projections; lag gauges reveal the remaining work.

## Access and privacy

The dev backend exposes only `GET /actuator/health`, `/actuator/metrics`, `/actuator/metrics/{name}` and `/actuator/prometheus`. They require the existing local **BOOTSTRAP** login (`learner`). An anonymous metrics request returns 401 and an ordinary workspace member receives 403. Log in through the backend's local form with the configured development password, then open the endpoint in that same browser session. The default backend address remains `127.0.0.1`; non-dev requests remain denied. Health details are hidden, and environment/configuration/logfile/heap/session endpoints are not exposed. Do not change metric access to public to configure scraping.

Application diagnostics record only generated UUID correlations, fixed event/stage/outcome/reason values, durations and numeric HTTP statuses. They omit paths, query strings, search terms, titles, document text, binary/Base64 updates, emails, cookies, session IDs, passwords, CSRF and authorization headers. Expected update errors map through a fixed reason-code allowlist; unexpected exceptions become `UNAVAILABLE`. Exceptions and stack traces are not attached to diagnostic events. IDs never become metric labels. Default HTTP request metrics are disabled to avoid uncontrolled route labels. Existing framework startup logs remain; adding debug request/body/SQL logging requires a separate privacy review.

Metric or logging runtime failures cannot replace an operation's result or original exception. The queue still enforces its existing size/access limits. This instrumentation does not turn off authorization checks or allow the search worker to participate in accepting edits.

## Debugging and proposed alerts

| Observation | Next evidence to inspect |
|---|---|
| High acceptance latency and high `engine`/`validate` duration | Private engine availability, timeout, contention and candidate size limits |
| High `commit` duration with normal validation | Database/connection-pool availability, lock contention and retry reasons |
| Saved edits, rising pending outbox age | `delivery_poll` failures and relay enablement |
| Fast relay, increasing queue pressure/send failures | Slow peers, authorization checks, shared executor saturation and reconnect/replay |
| Search misses, nonzero pending count | Worker enablement, `search_index` outcomes, failure count, shared snapshot capacity and snapshot freshness |
| Old or unavailable aggregate metrics | `projection_poll` failures and database health; never assume cached values are current |

[`ops/observability/alerts.yml`](../../ops/observability/alerts.yml) proposes target unavailability, sustained acceptance failures, p95 acceptance above 500ms, pending outbox age above 30 seconds, search remaining behind, sustained sender pressure and stale/unavailable diagnostics. Thresholds are examples, **not measured SLOs or capacity guarantees**. Search/outbox rules respect configured pauses. Rejections are separated from infrastructure failures.

A Prometheus server, authenticated scraping/session refresh and notification delivery are not provisioned by this lesson. The rules are reviewable proposals. Do not put the bootstrap password or a session cookie in Git. Validate rules with `promtool check rules ops/observability/alerts.yml` before loading them. `promtool` is not installed in the current environment; no alert firing or notification drill is claimed.

## Verification

Build the frontend first, then run Maven serially against the dedicated test database. Do not print the environment file or credentials:

```powershell
Set-Location frontend
npm run build
Set-Location ../backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
$env:WORKSPACE_RUN_YJS_ENGINE_TESTS = 'true'
$env:WORKSPACE_RUN_BROWSER_TESTS = 'true'
$env:WORKSPACE_RUN_SEARCH_BROWSER_TESTS = 'false'
Remove-Item Env:WORKSPACE_BROWSER_TEST_GREP -ErrorAction SilentlyContinue
mvn -B test
```

`WorkspaceTelemetryTest` verifies nested correlation cleanup, private-error/header/body exclusion and preserving results during meter failure. `ProjectionMetricsTest` verifies unknown initial state, stale snapshot retention and visible poll failure. `ObservabilityIntegrationTest` verifies PostgreSQL aggregates before/after an edit, checkpoint/index catch-up, actual Prometheus exposition, 401/403/bootstrap access and disabled sensitive endpoints. The delivery repair test follows one persisted correlation through acceptance, validation, commit, relay and an actual socket send, while the forced-JVM-crash case checks correlation survival. The migration regression upgrades a pre-V19 outbox event and retains its checkpoint. Queue rejection tests verify counters while retaining bounded-close behavior.

On November 10, 2026, the full regression run passed **75 Java test cases** with zero failures, errors or skips, including the development-browser and production offline-shell fixtures. The frontend production build passed. After the final diagnostic failure-path refinements, a separate search-enabled run passed **6 Java cases**, covering the three diagnostic unit cases, cached-snapshot failure case, metrics/access integration case and browser fixture with the real scheduled-search flow, freshness/error UI flow, rich editor flow and offline shell. The captured full-run output contained **2,169 parsed ECS diagnostic events**; a content-free field/schema check passed and found a complete real-engine correlation through acceptance, validation, commit, relay and socket send. All seven proposed alert rules parsed as YAML; PromQL evaluation/notification delivery remains unverified because no Prometheus/promtool service is installed.

## Review and remaining boundaries

The compact principal-engineer review follows update commit/rollback, session ordering, outbox restart, async authorization, diagnostic exceptions, metric cardinality, cached snapshot freshness and endpoint access. No production certification is implied. Remaining operational limits are aggregate-query cost at unmeasured scale, synchronous local log output, no trace collector, no browser application/receipt telemetry and no installed alert delivery. Existing unauthenticated loopback-engine and gated-rich-text position-history risks remain outside this lesson's changes.

No new P0/P1 defect was identified in this scoped review. P2 deployment work remains a dedicated monitoring identity, a measured retention/access policy and the inherited engine/rich-text boundaries. P3 measurement work includes aggregate query cost and log overhead under load; the scheduled aggregate currently shares the application's scheduler and can delay other scheduled tasks while waiting for database capacity. Do not enable this setup on an unmeasured production workload.

| Perspective | Score | Evidence and remaining limit |
|---|---:|---|
| Principal Engineer | 8/10 | Durable correlation and explicit acceptance/transport boundaries; no client-applied latency evidence |
| Senior Engineer | 8/10 | Small instrumentation module, fixed labels, isolated failures and exercised rollback/restart paths |
| Security | 6/10 | Protected endpoints and allowlisted diagnostic fields; inherited engine/rich risks remain |
| Reliability | 7/10 | Cached freshness, visible poll failure and restart correlation; no installed alert delivery |
| Maintainability | 8/10 | Feature packages and documented metric semantics; instrumentation crosses several execution paths |
| Production readiness | 3/10 | Local identity/setup; multi-instance, load, recovery and monitoring operations remain unverified |

V19 is additive and compatible with old three-argument outbox inserts through its UUID default. Deploy migrations with code as usual. Roll back application code while retaining the applied migration; do not edit Flyway history or remove durable updates. Projection metric polling can be disabled with `WORKSPACE_OBSERVABILITY_PROJECTIONS_ENABLED=false`; worker flags retain their existing meanings. Future production deployment needs a dedicated monitoring identity and retention policy instead of relying on the temporary bootstrap account.

## Interview practice

**Why does a correlation ID belong in the outbox?** A ThreadLocal disappears when the request or process ends. Durable correlation lets a later relay reconnect its diagnostic history to the original committed operation.

**Why do metric labels exclude document/user IDs?** Each value creates new time series. Fixed stage/outcome labels keep memory and monitoring cost bounded; investigate individual operations through correlated logs.

**Can fast relay prove fast collaboration?** No. Relay enqueues; a socket send returns at the transport boundary. Client application, local persistence and network round trips require separate measurements.

**Why cache projection metrics?** Scraping must not execute an aggregate query per viewer. The freshness gauge prevents a failed refresh from masquerading as current evidence.

**What is the difference between an SLI and an SLO?** An SLI is the measured indicator, such as server acceptance latency. An SLO is a justified target over a defined population/window. An example alert threshold alone is not an SLO.

Primary references: [Spring Boot structured logging](https://docs.spring.io/spring-boot/4.2/reference/features/logging.html), [Actuator endpoint exposure and access](https://docs.spring.io/spring-boot/4.2/reference/actuator/endpoints.html), [Prometheus histogram interpretation](https://prometheus.io/docs/tutorials/understanding_metric_types/) and [alert rules and notification boundaries](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/). The application uses its pinned Spring Boot 4.1.2 dependency; the local integration tests verify its actual behavior.
