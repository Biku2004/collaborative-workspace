# Security and quality checkpoint — Lessons 19–21

This review covers the local development WebSocket, room and update-commit implementation. It is not a production security certification or an exhaustive dependency vulnerability scan.

## Verified properties

- Workspace WebSocket upgrades use the authenticated stored-user session, an exact configured Origin, and workspace membership. The temporary bootstrap identity is denied. Document-room joins and transient delivery check document/workspace membership; queued delivery rechecks sender and recipient. Browser Origin is not a substitute for authentication.
- Update submission derives actor, workspace and joined document from server-side socket state. Only owners and editors can write. Membership writers and document updates use the workspace lock; the update rechecks the current role after taking it. Session destruction and the short commit transaction share an in-process session guard; the Node engine call remains outside that guard and outside database locks.
- The committed row contains exact payload bytes, the update ID, actor, digest and sequence. The head, update and outbox commit together. A transaction held open before commit is not visible to another connection and produces no `SAVED`; an injected outbox failure rolls back all three records and closes the socket without acknowledging. Concurrent same-ID retries yield one row, and competing different IDs force candidate revalidation when the head changes.
- A real Java/Node/PostgreSQL/WebSocket integration test reconstructs insert/delete fixtures, commits them and checks the saved checkpoint. Separate tests cover out-of-order dependencies, duplicate updates, engine timeout, invalid/oversized engine responses, session expiry and logout, and test-database isolation. These tests use a dedicated local test database and do not constitute a production load or failure-injection campaign.
- The engine rejects a valid Yjs update followed by trailing bytes. Its checks rely on the pinned Yjs/lib0 implementation and selected internal Yjs state, so the tests do not prove complete malicious-input or rich-text schema validation.

## Open risks and planned follow-up

1. **Production blocker — compatibility and validation gate remains open.** The engine supports bounded plain text and checks selected unsupported structures and pending dependencies. Java preserves Yjs bytes, but the custom browser provider, rich-text schema, malicious input behavior and full cross-language synchronization have not been proved. Complete the compatibility experiments before trusting this path with real edits. The engine's loopback endpoint has no authentication and Node worker limits are not an OS isolation boundary.
2. **Production blocker — no delivery or replay yet.** Lesson 21 commits an outbox row but does not relay it. Clients may get `SAVED` without other clients receiving the edit. Lesson 22 must implement bounded replay and repair and verify recovery across a commit-to-notification failure.
3. **High — strict limits and process scope.** A checkpoint cannot exceed 8 KiB; rooms, session guards and socket queues are single-instance and in memory. No multi-instance session synchronization, durability of notification state, load measurements or safe rich-text migration has been established.
4. **High — failure outcome may be unknown.** A database/network failure during commit can leave a client unsure whether its update committed. The socket closes on unexpected failure; the client must retry the same update ID and bytes. There is no completed frontend outbox or end-user recovery experience yet.
5. **Medium — slow sends can exhaust sender workers.** Queues and tracked socket count are bounded, but a blocked transport send can occupy a worker without a hard deadline. No real slow-reader load test or operational alert exists yet.
6. **Medium — session and delivery boundaries are local.** The per-session commit guard coordinates logout with the short transaction in this application process; it is not a distributed session guarantee. A message already in flight cannot be recalled after permission removal, and HTTP idle expiry depends on container detection.
7. **Medium — operations remain incomplete.** Outbox retention, long-term checkpoint compaction, backup/restore drills, alerts and incident runbooks are not implemented. Never claim production readiness on the basis of the current test suite.

## Test commands

Build the private engine, then run `npm test` there. From `backend`, load `.env.local.ps1`, set `WORKSPACE_RUN_DB_TESTS=true` and `WORKSPACE_RUN_YJS_ENGINE_TESTS=true`, and run `mvn -B test`. Review injected test logs in context: the deliberately simulated outbox failure is expected to log its exception class while the transaction rolls back.
