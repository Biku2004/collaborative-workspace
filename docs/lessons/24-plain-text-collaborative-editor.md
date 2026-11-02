# Lesson 24 — Add a plain-text collaborative editor

> [Lesson 25](25-offline-persistence.md) adds local draft persistence and manual retries; descriptions below record the Lesson 24 state.

## 1. What we built

Two authenticated workspace members can open the same document in separate browser contexts and collaborate on **plain text**. Each tab owns an independent `Y.Doc`. A small custom provider connects it to the Java WebSocket protocol, replays committed updates, submits local Yjs changes, and applies committed remote changes.

This is an online learning slice. It is not `y-websocket`, a production provider, a rich-text editor, or an offline persistence feature. The private Yjs engine is still constrained to bounded plain text; the full compatibility and malicious-input validation gate remains open.

## 2. Before you run it

Start the private Node engine from [Lesson 18](18-private-yjs-engine.md), the Java backend from [Lesson 23](23-minimal-browser-application.md), and the Vite frontend at `http://127.0.0.1:5173`. Configure the backend's `WORKSPACE_WEBSOCKET_ALLOWED_ORIGIN` to that exact frontend origin before starting Java. Use stored accounts, a workspace shared between them, and an existing document. Owners and editors can write; viewers can open the document read-only.

For a simple test, use one regular browser window and one private window so they have separate sessions. Sign in as different workspace members, open the same document and type. A local change appears immediately in the typing tab; it becomes **Saved** only when the server acknowledges the committed update. Another tab receives committed changes through the server's relay.

If the socket closes or saving fails, the editor becomes read-only. Pending text remains only in that open tab so it can be copied; navigating away or reloading can lose it. The app warns before in-app navigation and asks the browser to warn on page unload when changes are pending. Offline persistence, automatic reconnect and robust recovery are later lessons.

## 3. How a change travels

```text
local textarea -> Y.Text transaction -> binary Yjs update + stable updateId
    -> authenticated document socket -> SUBMIT_UPDATE
    -> private engine validates candidate -> PostgreSQL commits update + outbox
    <- SAVED after commit; local pending entry is removed
    -> outbox relay -> UPDATE -> other tab applies Yjs bytes with REMOTE origin
```

The provider suppresses outbound submission when applying a remote update. That origin marker prevents an incoming edit from becoming a new local submission and echoing back indefinitely. The server, not the browser, determines user identity and authorizes each write.

## 4. Follow the code

1. `frontend/src/provider.ts` contains the online-only `PlainTextProvider`. It owns one `Y.Doc`, one `Y.Text`, one WebSocket and an in-memory queue of pending submissions. It has no localStorage, IndexedDB or cross-tab transport.
2. On connection it sends `HELLO`, then `JOIN`, then requests `SYNC` from sequence 0. Editing is enabled only after the required replay is applied. The provider keeps the **last contiguous sequence applied**, validates replay pages and ignores duplicate sequences.
3. `HEAD` and out-of-order `UPDATE` messages can reveal a gap. The provider requests another bounded `SYNC` from its contiguous position; it does not advance a cursor merely because a larger sequence was seen. Only one replay request is in flight at a time.
4. A local text change is converted into a Yjs transaction. The diff compares Unicode code points, then calculates Yjs positions in UTF-16 units, avoiding a boundary through an emoji surrogate pair. Combining sequences may still consist of multiple code points; cursor/selection preservation is future work.
5. Each generated update gets a UUID and is submitted in order, one at a time. A matching `SAVED` removes the head of the queue. A timeout, rejection, malformed message, unsupported epoch or disconnect freezes the editor and preserves pending visible text in the current tab. There is no automatic retry in this lesson.
6. `frontend/src/Editor.tsx` owns the provider lifecycle, renders status, provides a labeled textarea, and warns about pending changes. `App.tsx` opens the selected document and checks before leaving it.
7. `frontend/test/editor.spec.ts` creates two independent Chromium browser contexts. A test-only network barrier holds each client's update until both have edited, then releases them through the real server. No same-browser cross-tab synchronization is used.

The provider caps pending updates at 32, checks outgoing socket buffering, and uses ten-second synchronization/save deadlines. The learning textarea accepts at most 4,000 UTF-8 bytes. The backend accepts at most 8 KiB per update and the engine currently limits checkpoints to 8 KiB. If an edit produces an oversized update or the queue limit is exceeded, the editor stops rather than pretending it was saved. These are constraints for this prototype, not final product limits.

## 5. Run and verify

After starting the engine, backend and frontend as above, open the same document as two editor/owner users. Change text in both windows, wait for `Saved` or `Up to date`, and verify they converge. Remove one member while connected; its editor should lose the connection and stop accepting input. UI role information is only presentation: the backend remains responsible for authorization.

For automated verification, first install and build the private engine and the frontend, and install Playwright Chromium or configure an installed Chrome executable. Then run against the dedicated PostgreSQL test database:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\experiments\yjs-private-engine
npm ci
npm run build

cd C:\Users\lenovo\Downloads\collaborate_workspace\frontend
npm ci
npm run build

cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
$env:WORKSPACE_RUN_YJS_ENGINE_TESTS = 'true'
$env:WORKSPACE_RUN_BROWSER_TESTS = 'true'
mvn -B '-Dtest=BrowserWorkspaceIntegrationTest' test
```

The test starts a private engine, backend and Vite server on temporary loopback ports. It creates two real users and independent browser contexts, then forces concurrent inserts and a concurrent insert/delete before releasing their network messages. It waits for the two visible texts to converge, removes one user's workspace membership and checks that later text is not received. It also simulates a dropped connection with an unsent local draft and checks that the draft remains visible and navigation warns. Test records are removed afterward.

This is a concrete compatibility experiment through browser → custom provider → Java → private Yjs engine → PostgreSQL → relay → another browser. It does not prove full Yjs provider interoperability, arbitrary rich-text schemas, offline recovery, multi-instance operation, production throughput, or safety against all malformed/malicious updates.

## 6. Questions and answers

**Why show a local edit before the server responds?** Waiting for a round trip on every keystroke makes editing feel slow. The local Yjs document can update immediately, while the UI separately marks the update as pending until the server commits it.

**Why does `SAVED` not mean every collaborator has received the edit?** It confirms the server-side transaction committed. Broadcast, client receipt and client application happen afterward and can fail independently; replay from the last contiguous sequence repairs gaps.

**Why tag remote Yjs transactions?** Applying a server update also raises Yjs update events. The origin tag lets this provider avoid re-submitting those remote bytes as if they were a new local user action.

**Why maintain a contiguous sequence instead of the largest number observed?** If updates 1 and 3 arrive but 2 is missing, the applied prefix ends at 1. Treating 3 as the cursor would skip update 2 during recovery.

**What happens when the network fails after a local edit?** This lesson stops editing and keeps the pending text in memory in that tab, with a warning to copy it. It does not promise survival after a reload or automatically retry a lost acknowledgement; persistent pending updates and reconnection are Lessons 25–26.

**Why can a viewer not simply be protected by a read-only textarea?** A user can alter browser code or send protocol messages directly. The backend checks the current role again at the update transaction boundary.

## 7. Checkpoint

Two independent browser contexts can make concurrent plain-text edits and converge through the server. Exercise: explain each state in the path from a local keystroke to `SAVED`, then disconnect one tab during an unsent edit and observe that its visible text is not proof of server persistence. Next is [Lesson 25 — Add offline persistence and a pending-update queue](../07-next-lessons-roadmap.md).
