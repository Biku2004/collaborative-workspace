# Lesson 18 — Prototype the private Yjs document engine

## 1. What we built

A small Node/TypeScript service reconstructs a plain-text Yjs document from binary updates. It returns a **candidate** text and Yjs checkpoint, or rejects the request. It binds only to `127.0.0.1`; there is no public route, authentication, database connection or Java integration in this lesson.

The experiment is intentionally limited. Loopback binding reduces network exposure but local processes can still call it. A Node worker thread shares its process and is not an operating-system security sandbox. The limits and checks are learning safeguards, not proof that arbitrary hostile Yjs input is safe. Do not expose the engine publicly or connect it to untrusted traffic.

## 2. Why a separate process?

Yjs update bytes encode CRDT structures, not a generic text patch. Java can store or transmit those bytes, but cannot infer the text just by decoding Base64. Node runs the Yjs library and can apply updates and produce another Yjs update representing the reconstructed state.

```text
future Java gateway                       private Node prototype
identity + membership + committed state -> bounded candidate reconstruction
                         candidate result <-  text + encoded checkpoint
             recheck + database commit -> only then can an update be called saved
```

The arrows show a future boundary, not an implemented integration. A 200 response means only that this prototype produced a candidate. It is not a permission decision, a database commit, a durable acknowledgement, or proof of valid rich-text content. If a database transaction later fails, discard the candidate and do not send `SAVED`.

## 3. Files and flow

The complete code is in `experiments/yjs-private-engine`:

1. `package.json` and `package-lock.json` pin Yjs 13.6.33, lib0 0.2.119 and the TypeScript toolchain.
2. `src/protocol.ts` validates the JSON shape, canonical padded Base64, update count and decoded byte limits.
3. `src/server.ts` accepts loopback HTTP requests, limits active work and body size, starts a worker with a deadline, and returns safe error codes.
4. `src/worker.ts` calls `reconstruct.ts` in a worker thread. The worker receives only the supplied updates; it has no shared document cache.
5. `src/reconstruct.ts` reads updates with a bounded decoder, rejects trailing bytes, checks for pending dependencies and unsupported content, and returns text and an encoded checkpoint. The document is destroyed afterward.
6. `test/engine.test.ts` sends real HTTP requests and checks reconstruction and failures. `test/hanging-worker.ts` lets the timeout and capacity tests reproduce a stuck worker.
7. `fixtures/plain-text.properties` is shared by Node and `backend/src/test/java/com/example/collaborativeworkspace/YjsFixtureTransportTest.java`. Java's standard Base64 decoder/encoder preserves the fixture bytes; Node interprets them as Yjs and obtains `Hello Java`.

**Version-sensitive detail:** the missing-dependency check uses Yjs's internal `doc.store.pendingStructs` and `pendingDs`, and the root map check uses an internal field. These are not stable public APIs. Yjs is pinned, and tests cover a missing insertion and a missing deletion; a version upgrade requires revalidation. These examples do not prove every malformed, adversarial, or unsupported update is detected. Root-type information is not fully encoded, so this is not a complete schema validator.

## 4. Start and call it

In PowerShell, from the repository root:

```powershell
cd experiments/yjs-private-engine
npm ci
npm test
npm run build
npm start
```

The server listens on `127.0.0.1:8091`. Set `YJS_ENGINE_PORT` before `npm start` to use another local port. In a second PowerShell window:

```powershell
$fixture = 'AQEBAAQBB2NvbnRlbnQKSGVsbG8gSmF2YQA='
$body = @{ version = 1; updates = @($fixture) } | ConvertTo-Json -Compress
$result = Invoke-RestMethod -Method Post 'http://127.0.0.1:8091/v1/reconstruct' -ContentType 'application/json' -Body $body
$result
```

Expected fields include `version: 1`, `status: candidate`, `text: Hello Java`, and `checkpoint`, a Base64-encoded Yjs update. To reconstruct the candidate after restarting the process, send its `checkpoint` as the sole update. Stop the server with Ctrl+C. The fixture uses a fixed client ID solely for reproducible test data; live replicas must use Yjs-generated client IDs.

From `backend`, test Java's part of this fixture independently:

```powershell
mvn -B '-Dtest=YjsFixtureTransportTest' test
```

This verifies opaque byte preservation, not a Java HTTP client, authorization, persistence, or a custom provider. The cross-language/provider decision gate remains open.

## 5. Request and response contract

`POST /v1/reconstruct` accepts `{ "version": 1, "updates": ["<standard padded Base64>"] }`. Zero updates yields an empty candidate. Updates may be reordered or repeated, but all dependencies needed to reconstruct state must be present by the end of the request. No state survives a request; callers must supply the appropriate committed state and candidate updates on every call.

| Result | Status | Meaning |
|---|---:|---|
| `status: candidate` | 200 | A speculative text and checkpoint were reconstructed. |
| `INVALID_REQUEST` or invalid Base64 | 400 | The request shape or encoding was rejected. |
| `TOO_LARGE` | 400, 413 or 422 | Input body, decoded data, materialized text, output or checkpoint exceeded a limit. |
| `INVALID_UPDATE` | 422 | Yjs could not decode/apply the supplied bytes. |
| `MISSING_DEPENDENCY` | 422 | The pinned Yjs implementation reports pending insertion or deletion dependencies. |
| `UNSUPPORTED_DOCUMENT` | 422 | The prototype detected an unsupported root, map, embed or formatting. |
| `BUSY` | 503 | The two active worker slots are occupied. |
| `TIMEOUT` | 504 | The request deadline expired; the worker is terminated. |
| `ENGINE_FAILURE` | 500 | Worker startup or execution failed. |

For the original plain-text v1 path, the limits are a 64 KiB HTTP request body, 16 updates, 8 KiB per update and returned checkpoint, 32 KiB total decoded updates, 16,384 UTF-16 text units and a 64 KiB response. The later rich-text endpoint has a separate 128 KiB HTTP request-body limit. Both paths use two active workers and a three-second default deadline. A v1 checkpoint larger than the per-update input limit is rejected so every successful checkpoint can be submitted again. Worker JavaScript heap settings provide additional bounds, but do not strictly cap all process or external memory. Limits are local starting values, not benchmarked service targets; see [Lesson 27](27-rich-text-design.md) for the distinct rich-text limits.

## 6. Tests and failure behavior

`npm test` compiles the code and runs Node's test runner. It verifies the shared fixture; Unicode insert/delete, duplicate delivery and out-of-order deletion; checkpoint round trip across fresh server instances; pending insertion and deletion rejection; bad JSON/update shapes, malformed Base64 and Yjs bytes, input size and unsupported roots; output checkpoint size; a deliberately stuck worker; and saturation followed by slot release. Tests do not kill the PostgreSQL service or prove container-level resource isolation.

The timeout test starts a worker that never responds. The server returns 504 and does not return a candidate. The overload test holds both slots and expects 503 for a third request. This demonstrates the local mechanism; OS-level process isolation, network restrictions, authentication, monitoring, worker-crash handling under load and production capacity planning remain future work. See [Node's worker documentation](https://nodejs.org/api/worker_threads.html) and [Yjs update API](https://docs.yjs.dev/api/document-updates).

## 7. Interview questions

**Why does Java keep responsibility for authorization?** The engine only receives bytes to reconstruct. It has no trusted user session or workspace membership context and therefore cannot decide whether a user may edit.

**What if the engine succeeds but PostgreSQL commit fails?** The candidate has not become durable. Discard it, report failure or an unknown outcome as appropriate, and never acknowledge it as saved. A lost commit response needs an idempotent lookup, not a blind assumption.

**Why reject pending dependencies?** A successful decoder call can leave operations waiting for missing structures. This simple prototype rejects such a candidate instead of silently returning a partial checkpoint. A future design could defer updates with explicit bounds and recovery, but must test that policy.

**Why a worker deadline?** A synchronous decoder can block an event loop. A separate worker lets this prototype stop waiting and terminate that worker. Worker resource limits are not a full security boundary.

**Why is a state vector not sufficient proof of complete state?** Deletions are not fully represented by insertion clocks. Lesson 17 demonstrates two replicas with equal vectors and different text; the synchronization update still carries deletion information.

**Does a passing fixture prove compatibility with `y-websocket`?** No. It proves one opaque byte round trip and Yjs reconstruction. Provider protocol, synchronization, supported schemas, persistence and failure behavior need dedicated compatibility tests.

## 8. Checkpoint and next step

The private reconstruction prototype works for its constrained plain-text examples. Exercise: send the fixture, restart the server, send the returned checkpoint, and compare the resulting text. Then send malformed Base64 and observe a 400.

Lesson 19 introduces a WebSocket handshake. Before relying on a Java/custom-provider boundary for real edits, the [decision gate](../07-next-lessons-roadmap.md) still requires cross-language synchronization, rich-text schema and failure tests; evaluate the Node collaboration-server fallback if those cannot be supported safely.
