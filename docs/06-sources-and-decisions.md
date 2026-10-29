# 6. Research and decision register

Research accessed for this plan on 29 October 2026. The links are primary documentation except where explicitly identified. Source documents establish capabilities and constraints; the proposed end-to-end architecture, targets and trade-offs are our design judgments. Recheck versions before implementation.

## 6.1 Primary reading list

1. [Yjs introduction](https://docs.yjs.dev/): shared types and network/editor modularity.
2. [Yjs document updates](https://docs.yjs.dev/api/document-updates): binary updates, state vectors, merge operations and garbage collection limitation.
3. [Yjs IndexedDB provider](https://docs.yjs.dev/ecosystem/database-provider/y-indexeddb): offline browser persistence.
4. [Yjs WebSocket provider](https://docs.yjs.dev/ecosystem/connection-provider/y-websocket): provider behavior, awareness and scaling approaches.
5. [Yjs relative positions](https://docs.yjs.dev/api/relative-positions): cursor and anchor behavior.
6. [Y.Doc API](https://docs.yjs.dev/api/y.doc): document lifecycle, transactions and GC configuration.
7. [TipTap collaborative editing guide](https://tiptap.dev/docs/hocuspocus/guides/collaborative-editing): editor and Yjs integration. Our custom backend protocol is a separate integration exercise.
8. [Spring WebSocket server reference](https://docs.spring.io/spring-framework/reference/web/websocket/server.html): servlet WebSocket support and lifecycle.
9. [Spring WebFlux overview](https://docs.spring.io/spring-framework/reference/web/webflux/new-framework.html): reactive model and trade-offs.
10. [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html): currently documented supported runtimes and build tools; versions change.
11. [PostgreSQL explicit locking](https://www.postgresql.org/docs/current/explicit-locking.html): row-level lock behavior.
12. [PostgreSQL asynchronous commit](https://www.postgresql.org/docs/current/wal-async-commit.html): commit durability and configuration.
13. [PostgreSQL high availability](https://www.postgresql.org/docs/current/high-availability.html): replication trade-offs and failover data loss.
14. [Redis Pub/Sub](https://redis.io/docs/latest/develop/pubsub/): at-most-once behavior.
15. [Apache Kafka broker configuration](https://kafka.apache.org/42/configuration/broker-configs/): `min.insync.replicas` and `acks=all` behavior.
16. [OWASP WebSocket security](https://cheatsheetseries.owasp.org/cheatsheets/WebSocket_Security_Cheat_Sheet.html): Origin, authentication, message validation and resource limits.
17. [MDN WebSocket constructor](https://developer.mozilla.org/en-US/docs/Web/API/WebSocket/WebSocket): browser connection API and subprotocols.
18. [MDN WebSocket API](https://developer.mozilla.org/en-US/docs/Web/API/WebSocket): backpressure limitation in browser API.
19. [OpenTelemetry Java](https://opentelemetry.io/docs/languages/java/): instrumentation and observability concepts.
20. [Testcontainers for Java](https://java.testcontainers.org/): real dependencies for integration tests.
21. [Transactional outbox pattern](https://microservices.io/patterns/data/transactional-outbox.html): explanatory pattern reference; the implementation must still address duplicate delivery and ordering.

## 6.2 Initial architecture decision records

### ADR-01: Yjs, not a handwritten text CRDT

**Context:** concurrent offline editing requires well-tested merge behavior and editor integrations. **Decision:** use Yjs. **Alternatives:** custom CRDT for research, OT for a centrally coordinated editor. **Consequences:** compatibility, metadata growth and editor semantics need testing; convergence is not preservation of every human intention. **Revisit:** learning goal changes to algorithm research or Yjs limitations become unacceptable.

### ADR-02: modular Java application plus private Node engine

**Context:** Java business logic cannot interpret Yjs simply by storing bytes. **Decision:** Java owns authorization and commits; a private Yjs-compatible engine reconstructs and validates candidate state. **Alternative:** Node handles all live collaboration; Java supplies a business API. **Consequences:** cross-language protocol, process failures and speculative-state handling add complexity. **Revisit:** compatibility spike fails, operations are too costly, or Java transport adds no learning value.

### ADR-03: PostgreSQL as initial ACK boundary

**Context:** small implementation needs explicit authorization, dedupe and persistence transaction. **Decision:** one database transaction and ACK after commit. **Alternatives:** Kafka-first event log or Redis Streams. **Consequences:** per-document serialization and database throughput limit; durability depends on WAL, storage and replication settings. **Revisit:** sustained measured bottleneck with credible capacity analysis.

### ADR-04: servlet WebSocket plus JDBC

**Context:** teach Spring while making transactions observable. **Decision:** MVC, servlet WebSocket and JDBC. **Alternative:** WebFlux/R2DBC. **Consequences:** explicitly bound executors and queues; no claim that synchronous execution scales infinitely. **Revisit:** load evidence and supported dependencies justify a reactive implementation.

### ADR-05: custom protocol with recovery cursor

**Context:** require application-specific epoch, ACK and permissions. **Decision:** versioned custom provider and protocol; validate fixtures. **Alternative:** exact `y-websocket` protocol with added services. **Consequences:** more implementation and compatibility risk. **Revisit:** spike indicates provider cost dominates project value.

### ADR-06: new epoch on restore

**Context:** old offline changes could reintroduce restored-away content. **Decision:** new CRDT document generation; quarantine old drafts. **Alternative:** merge old offline updates or silently discard them. **Consequences:** manual recovery trade-off and extra storage. **Revisit:** product chooses a different explicit reconciliation experience.

### ADR-07: ephemeral fanout with durable reconciliation

**Context:** Pub/Sub can drop events. **Decision:** database is authoritative, outbox ensures publish attempts, client/gateway periodically reads missing committed records. **Alternative:** durable per-instance subscription system. **Consequences:** repair lag and polling costs; cursor and retention must be correct. **Revisit:** observed volume or stricter delivery needs.
