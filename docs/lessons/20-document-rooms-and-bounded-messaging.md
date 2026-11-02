# Lesson 20 — Add document rooms and bounded messaging

> [Lesson 21](21-commit-before-acknowledgement.md) raises the full incoming frame limit to 16 KiB and adds committed update submissions after JOIN. The test-message content limit remains 512 UTF-8 bytes.

## 1. What we built

After Lesson 19's authenticated workspace WebSocket handshake and `HELLO`, a client can join one document room and send a small, temporary test message to members of that same room. The server supplies the sender's user ID from the authenticated session. The message is not a Yjs update and is not saved.

A **session** is one login, a **socket** is one connection belonging to a session, a **user** may have multiple sessions or sockets, and a **room** groups sockets by workspace and document. One socket can join at most one room at a time. To change rooms, leave first.

The implementation runs in one Java process. It is a learning checkpoint, not a distributed room system or durable collaboration protocol.

## 2. Request flow

```text
stored-user session + allowed Origin
    -> workspace socket handshake + membership check
    -> HELLO / WELCOME
    -> JOIN(documentId): check document belongs to workspace and actor is a member
    -> TEST_MESSAGE: recheck sender; select only peers in same workspace and document
    -> each recipient's bounded queue: recheck both users before sending
    -> LEAVE or socket close: remove room association
```

Owners, editors and viewers can read and send this **non-editing test message**. Archived documents follow the existing direct-read policy and are joinable by members. This permission does not grant a viewer the ability to edit content.

Membership is checked when joining, when a sender submits a message, and again for both sender and recipient when a queued message is about to be sent. An expired HTTP session is also rejected when detected. If membership changes immediately after the final database check, an already in-flight message may still arrive; this lesson does not serialize delivery with revocation or recall bytes already sent. Future durable edits need stronger, transactionally ordered permission checks.

## 3. Protocol, step by step

Connect as described in [Lesson 19](19-authenticate-websocket.md). All messages are JSON text and require integer `version: 1`.

1. Send `{"type":"HELLO","version":1}` and wait for `WELCOME`.
2. Send `{"type":"JOIN","version":1,"documentId":"<UUID>"}` and wait for `JOINED`.
3. Send `{"type":"TEST_MESSAGE","version":1,"message":"hello"}`. Sockets in that document room, including the sender, receive a `TEST_MESSAGE` with `documentId`, `userId` and `message`. The `userId` is server-derived.
4. Send `{"type":"LEAVE","version":1}` and wait for `LEFT`, or close the socket.

The server closes the socket on invalid messages, unknown types or versions, unauthorized joins, sending before joining, attempts to join two rooms, extra fields such as a client-supplied user ID, binary messages, or an unavailable session. A test message must be nonblank and at most 512 UTF-8 bytes; the entire incoming text frame is now limited to 16 KiB. The message is transient: a disconnect, slow consumer, restart or failure can lose it. A received test message is not an acknowledgement of persistence.

## 4. Read the implementation

- `WorkspaceSocketHandshake.java` keeps the server-derived user ID along with workspace and HTTP-session context.
- `WorkspaceSocketHandler.java` validates the message state and shape, checks access, handles `HELLO`, `JOIN`, `TEST_MESSAGE` and `LEAVE`, and never accepts the sender's identity from JSON.
- `DocumentRepository.canRead` checks the workspace/document relationship and membership together in a parameterized SQL query.
- `DocumentRoomHub.java` tracks peers and their current document in one process. It checks sender and recipient again before each queued room delivery. Closing a socket removes its peer and room association.
- `BoundedSocketSender.java` serializes writes to each socket, queues a bounded amount of data and closes the socket on overflow or executor rejection. The shared executor also has a bounded task queue.
- `WorkspaceSocketIntegrationTest.java` verifies actual sockets, separate documents and workspaces, sender identity, revocation and cleanup. `BoundedSocketSenderTest.java` blocks a mock send deliberately to verify queue and byte limits.

Spring's [WebSocket API documentation](https://docs.spring.io/spring-framework/reference/web/websocket/server.html) describes the server handler model and the need to coordinate concurrent sends. Here each socket has a single draining sender.

## 5. Limits and slow consumers

This checkpoint allows at most 256 tracked sockets per application instance. Each socket has at most 16 queued messages and 16 KiB of queued UTF-8 payload, plus at most one message already being sent. The shared sender pool has four workers and a queue of at most 128 runnable tasks. If a per-socket queue or the shared executor is full, the affected socket is closed and its pending queue cleared. These are fixed learning limits, not measured production capacity targets.

A blocked socket send can occupy one sender worker; the design bounds queue memory but does not guarantee a hard send deadline or fairness if all four workers are blocked. Closing a socket may also take time depending on the underlying transport. The tests simulate a blocked sender with a mock; they are not a network load test. Production work needs realistic slow-reader tests, timeouts, rate limits, monitoring and capacity design.

## 6. Try it in two browser tabs

Start the `dev` backend and sign in as a stored user following [Lesson 12](12-create-workspaces.md). Create a workspace and a document as an owner or editor using [Lesson 14](14-workspace-document-creation.md). Open two browser tabs from the configured allowed Origin, each with a member session. The default is `http://127.0.0.1:8080`; use that origin consistently.

In each tab's developer console, replace both UUID placeholders and run:

```javascript
const workspaceId = 'YOUR-WORKSPACE-UUID';
const documentId = 'YOUR-DOCUMENT-UUID';
const socket = new WebSocket(`ws://${location.host}/api/workspaces/${workspaceId}/socket`);
socket.onopen = () => socket.send(JSON.stringify({ type: 'HELLO', version: 1 }));
socket.onmessage = event => {
  const message = JSON.parse(event.data);
  console.log(message);
  if (message.type === 'WELCOME') {
    socket.send(JSON.stringify({ type: 'JOIN', version: 1, documentId }));
  }
};
```

After both tabs show `JOINED`, run in either tab:

```javascript
socket.send(JSON.stringify({ type: 'TEST_MESSAGE', version: 1, message: 'Hello room' }));
```

Both tabs should receive the event with the server's user ID. Open another socket for a different document and it will not receive this message. To leave, run `socket.send(JSON.stringify({ type: 'LEAVE', version: 1 }))`; to disconnect, run `socket.close()`.

## 7. Verify

From `backend` in PowerShell with the dedicated test database configured:

```powershell
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
mvn -B test
```

The integration test opens four sockets: two in one document, one in a different document in the same workspace and one in another workspace. It checks message isolation, server-derived sender ID, removal of a viewer before another message, unauthorized cross-workspace join, leave and connection cleanup. Separate tests block a mock socket, fill the per-socket queue by message count and bytes, and simulate a full shared executor. Existing handshake, logout, expiry and workspace tests still apply.

## 8. Interview questions

**Why can't a client choose the sender ID?** The session establishes identity. An arbitrary ID in client JSON could impersonate another user, so the server derives the sender from the authenticated session.

**What happens when a client cannot read as fast as we send?** Messages accumulate. Here, a fixed-size per-socket queue and bounded shared executor limit buffering; overflow closes the affected socket. Real systems also need a timeout, reconnect policy, observability and an explicit decision about what can be dropped.

**Why recheck membership on delivery?** A user can be removed after joining or while a message waits in a queue. The recheck reduces stale authorization, but it cannot recall a message already in flight or make the check and network send atomic.

**Is a room a durable document?** No. It is an in-memory routing group. Its test messages are discarded on failures or restart. Future lessons will define commit, acknowledgements and replay for actual document updates.

**Does this work across Java instances?** No. Each instance maintains its own room map and queues. Cross-instance delivery and shared session behavior need a later design and failure tests.

**Can a viewer send the test message?** Yes; the message is an ephemeral demonstration, not a document modification. Actual edit operations must check editor or owner permission at their own transaction boundary.

## 9. Checkpoint

You can join a document room, exchange a bounded temporary message and leave. Exercise: use two documents in the same workspace and explain why their sockets receive different messages. Next is [Lesson 21 — Commit document updates before acknowledging them](../07-next-lessons-roadmap.md).
