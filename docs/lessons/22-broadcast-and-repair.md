# Lesson 22 — Broadcast committed updates and repair gaps

## 1. What we built

Lesson 21 recorded a Yjs update and its outbox row before responding `SAVED`. Now a single Java instance can send committed update events to sockets in the same document room. A client can request bounded replay from its **last contiguous sequence**, and periodic `HEAD` messages reveal missed notifications while the socket is still connected.

This is still a local-development learning implementation. A `SAVED` response, an `UPDATE` notification and a successfully applied update mean different things. The server does not know that a browser has applied or displayed an update merely because it tried to send one. The browser provider, rich-text editor, offline queue and multi-instance transport remain later lessons.

## 2. The flow

```text
SUBMIT_UPDATE -> commit update + sequence + checkpoint + outbox -> SAVED
                                              |
                            relay polls committed outbox
                                              -> UPDATE to same-document room
client misses an UPDATE -> periodic HEAD reveals a higher sequence
                       -> SYNC(after = last contiguous applied)
                       <- bounded SYNC_PAGE; repeat while hasMore
```

`SAVED` can arrive before or after the corresponding `UPDATE`; delivery is asynchronous. Notifications can be duplicated, delayed, missed, or arrive out of order. A client must use `(documentId, epoch, sequence)` and apply only a contiguous prefix. Seeing sequence 7 while 5 and 6 are missing does not mean the client has applied through 7.

A client should ignore already-applied sequences; if it sees a gap or a `HEAD` ahead of its last contiguous applied sequence, request `SYNC` from that contiguous position. Apply each returned update in order and advance the cursor only after successful local application. Do not advance to the largest sequence observed. Request further pages while `hasMore` is true, and check subsequent `HEAD` messages because more commits may occur during replay. The browser provider that automates this is a later lesson.

## 3. Follow the code

1. `V9__track_outbox_delivery.sql` adds a timestamp and a partial index for pending outbox rows. Existing committed rows remain eligible for relay after migration.
2. `DocumentUpdateRepository.java` reads a bounded ordered page from the committed update log and locks up to 32 outbox rows with `FOR UPDATE SKIP LOCKED`.
3. `DocumentDeliveryService.java` supplies a consistent database snapshot for a replay page, rejects a cursor beyond the head or an incorrect epoch, detects sequence gaps, and caps pages at eight updates and 14,000 UTF-8 bytes. It also attempts to enqueue committed outbox events and marks them processed in the same relay transaction.
4. `DocumentDeliveryScheduler.java` polls once per second by default. It calls the relay and then sends `HEAD` notifications to currently joined sockets. Tests can disable the scheduler or relay separately to reproduce missing notifications.
5. `DocumentRoomHub.java` limits notifications to the matching workspace/document and rechecks current recipient membership at actual delivery. The existing bounded socket queues can close a slow client; a reconnecting client must replay from its last contiguous position.
6. `WorkspaceSocketHandler.java` accepts `SYNC` only for a joined document and queues the resulting page through that socket's bounded sender.

The database column is named `published_at`, but it records that this instance **attempted to enqueue** the notification. It is not proof of socket delivery, client receipt or client application. An outbox transaction can roll back after a send was queued, producing duplicates; a process can die after marking a row while the socket message is still queued, producing a missed notification. A periodic `HEAD` and replay, plus replay on reconnect, are required for repair. The outbox is not deleted in this lesson.

## 4. Protocol example

Connect and join a document using [Lesson 20](20-document-rooms-and-bounded-messaging.md). A newly initialized document uses epoch 0. Send:

```json
{"type":"SYNC","version":1,"epoch":0,"after":0}
```

The socket responds with a shape like:

```json
{"type":"SYNC_PAGE","version":1,"documentId":"...","epoch":0,"after":1,"head":1,"hasMore":false,"updates":[{"sequence":1,"updateId":"...","userId":"...","payload":"..."}]}
```

`after` in the response is the last sequence included in that page; `head` is the committed head in the page's database snapshot. A later page or `HEAD` can show a newer head. `payload` is canonical Base64-encoded Yjs data. A valid page can contain no updates if the cursor is already at the head. The server responds with `REJECTED` for an invalid cursor or epoch and closes on malformed protocol messages. The page checks membership on read and again before delivery.

The relay emits `UPDATE` with `documentId`, `epoch`, `sequence`, `updateId`, server-derived `userId` and Base64 `payload`. The periodic message has the form `{"type":"HEAD","version":1,"documentId":"...","epoch":0,"sequence":1}`. Do not treat `HEAD` as the last applied position; it is only the server's observed committed head.

## 5. Try it

Start the private Yjs engine and Java backend following [Lesson 21](21-commit-before-acknowledgement.md). Sign in from two independent browser sessions, join the same workspace/document socket in each as shown in Lesson 20, and submit a supported update from one editor. Both sockets should eventually see an `UPDATE`; the sender also sees `SAVED`.

In a joined socket's console, request replay:

```javascript
socket.send(JSON.stringify({ type: 'SYNC', version: 1, epoch: 0, after: 0 }));
```

Inspect `SYNC_PAGE` in the existing `onmessage` handler. For a multi-page result, send another `SYNC` using the last sequence actually applied to your local Yjs document. This manual example does not implement a browser provider or automatically apply binary updates.

## 6. Verify

Build the private engine, then run both the Node tests and the backend tests with the dedicated local test database:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\experiments\yjs-private-engine
npm ci
npm test

cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
$env:WORKSPACE_RUN_YJS_ENGINE_TESTS = 'true'
mvn -B test
```

The real-engine end-to-end test uses two independent HTTP sessions and WebSockets, receives three committed updates in the second connection and reconstructs the same Yjs text from those bytes. The repair test disables the relay while keeping periodic heads active, commits nine updates, observes the higher head without reconnecting, and replays an eight-update page followed by a second page. It also verifies large-payload page bounds, invalid cursors/epochs, duplicate notifications and detection of a deliberately removed log sequence.

The crash test starts a separate Java process with its relay disabled, commits an update through the real Node engine, forcibly terminates that JVM before relay, then reads the durable log and resumes outbox processing from a different process. This verifies that specific commit-to-notification crash window against the local PostgreSQL test database; it is not a complete disaster-recovery or multi-instance test.

## 7. Questions and answers

**Why do we need replay if there is an outbox?** Queueing a socket message is not client receipt. A crash, network interruption, slow-consumer close or browser failure can lose a notification after the outbox is marked processed. The durable log plus an applied-sequence cursor lets the client repair gaps.

**What is the difference between the largest seen sequence and the last contiguous applied sequence?** If the client has applied 1–4 and sees 7, the largest seen is 7 but the contiguous position is 4. The client must request updates after 4; recording 7 would skip 5 and 6.

**How can duplicates happen?** The relay can queue an event and fail before committing its processed marker. On retry it can queue the same event again. Clients must deduplicate by document, epoch and sequence while applying Yjs updates safely.

**Why bound both the update count and page bytes?** Eight tiny updates and eight 8 KiB updates have very different memory and socket-buffer costs. Bounding both prevents one replay request from constructing an arbitrarily large response.

**Is a `HEAD` a checksum?** No. It is a server log position. Yjs state vectors are also not complete content checksums, particularly for deletions. The client must apply the required ordered updates.

**Can multiple Java instances use this implementation as-is?** No. The outbox locks prevent two relays from processing the same pending row simultaneously, but a processed notification is only queued to sockets in one instance. Room membership, sessions and delivery are local. A multi-instance design needs a shared fan-out and repair strategy.

## 8. Checkpoint and limits

Committed updates can now be relayed and retrieved in bounded pages, and a periodic head supports gap detection. The implementation still has an 8 KiB checkpoint limit, no production-grade rich-text validation, no browser provider, no distributed fan-out, no automatic outbox retention, and no proof against every malicious Yjs update. The cross-language/provider compatibility decision gate remains open.

Exercise: submit several updates, request `SYNC` with `after: 0`, and explain why the next request must use the last update you actually applied rather than the latest `HEAD`. Next is [Lesson 23 — Create the minimal browser application](../07-next-lessons-roadmap.md).
