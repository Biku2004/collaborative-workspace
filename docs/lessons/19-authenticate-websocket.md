# Lesson 19 — Authenticate a WebSocket connection

> [Lesson 20](20-document-rooms-and-bounded-messaging.md) adds JOIN, LEAVE and transient room messages after WELCOME.

## 1. What we built

A stored user can open a WebSocket for a workspace only after the HTTP handshake checks their existing login session, the browser Origin, and current workspace membership. Once connected, the client sends a versioned `HELLO`; the server replies with `WELCOME`.

This is the local `dev` profile. The socket does not join a document room, broadcast messages, edit a document, persist content, or call the Yjs engine. Those are later lessons. A successful handshake does not permanently authorize future operations: later messages must recheck current session, membership and the permissions relevant to the operation.

## 2. Concepts

An **HTTP upgrade** begins as an HTTP request. If the server accepts it, the connection becomes a WebSocket and remains open for messages in both directions. The browser automatically includes eligible session cookies in the handshake; this project gets the authenticated identity from Spring Security, not a user ID supplied in a message.

**Origin** identifies the web page's scheme, host and port. A malicious page can try to open a socket using a victim's cookies, so the server compares `Origin` with a single configured allowed origin. Browsers set this header; non-browser clients can forge it, so Origin is not authentication. Spring's [WebSocket server documentation](https://docs.spring.io/spring-framework/reference/web/websocket/server.html) explains handshake interceptors and origin checks.

```text
browser: session cookie + Origin + workspace ID
    -> Spring Security: stored-user session required
    -> handshake: exact Origin + existing session + current workspace membership
    -> WebSocket opens
    -> client sends {"type":"HELLO","version":1}
    <- server sends {"type":"WELCOME","version":1,"workspaceId":"..."}
```

## 3. Follow the code

1. `backend/pom.xml` adds Spring Boot's MVC WebSocket starter.
2. `SecurityConfiguration.java` already restricts `/api/workspaces/**` to stored-user sessions. The temporary bootstrap account cannot use this route. The default profile remains closed.
3. `WorkspaceSocketConfiguration.java` registers `/api/workspaces/{workspaceId}/socket` in the `dev` profile and configures a single explicit origin. The default is `http://127.0.0.1:8080`; override it with `WORKSPACE_WEBSOCKET_ALLOWED_ORIGIN` if your page uses another origin. Use the actual page origin, including its port; wildcards are rejected. In production, HTTPS and a complete production security configuration would be required.
4. `WorkspaceSocketHandshake.java` requires the exact configured Origin, an existing authenticated HTTP session, a stored user and a current membership for the requested workspace. A nonmember or unknown workspace gets 404. A missing or mismatched Origin gets 403 after authentication; an unauthenticated API request gets 401.
5. `WorkspaceSocketHandler.java` accepts exactly one text message with `type: HELLO` and integer `version: 1`, then sends `WELCOME` with the server-selected workspace ID. It closes invalid/unsupported messages and binary messages. Lesson 21 raises the incoming text-frame limit to 16 KiB for encoded updates; binary messages remain unsupported. It does not accept user IDs, roles or edits from the message.
6. `SocketSessionRegistry.java` tracks sockets by HTTP session and removes them on normal close. An HTTP session-destruction listener closes that session's sockets after logout or when the container detects expiry. Another login session remains separate. The registry tracks connection cleanup and a per-session commit guard; it is not a room or a distributed session store. A small guard remains associated with an HTTP session after its last socket closes and is removed when that HTTP session is destroyed.
7. `WorkspaceSocketIntegrationTest.java` drives real HTTP logins and WebSocket handshakes against the dedicated test database, including close, logout and expiry.

The registry checks session validity when registering a socket, so an invalidation racing with the upgrade cannot leave a socket registered indefinitely. Expiry is handled when the servlet container detects it; it is not a promise of exact-second disconnection. WebSocket traffic in this implementation does not refresh HTTP-session idle time. Connections and sessions are in memory and do not survive an application restart.

## 4. Run and try it

Start the Java backend using the local development setup in [Lesson 12](12-create-workspaces.md). Its default allowed page Origin is `http://127.0.0.1:8080`. Sign in as a **stored user**, and create or select a workspace as described there. Open a page served from that exact origin in the same browser session, such as `http://127.0.0.1:8080/api/me`, and open the browser developer console.

Replace the placeholder with a workspace ID belonging to that user:

```javascript
const workspaceId = 'YOUR-WORKSPACE-UUID';
const socket = new WebSocket(`ws://${location.host}/api/workspaces/${workspaceId}/socket`);
socket.onopen = () => socket.send(JSON.stringify({ type: 'HELLO', version: 1 }));
socket.onmessage = event => console.log('Server:', JSON.parse(event.data));
socket.onclose = event => console.log('Closed:', event.code);
```

The browser console should print `WELCOME`, version 1 and the workspace ID. Run `socket.close()` to close it. If the page is served from a different origin, configure `WORKSPACE_WEBSOCKET_ALLOWED_ORIGIN` to that exact origin **before starting** the backend; `localhost` and `127.0.0.1` are different origins. This example uses local HTTP and `ws`; HTTPS deployments need `wss` and an appropriate production security design.

A handshake from an unrelated Origin is rejected. A signed-in user who is not a workspace member receives 404. Sending a malformed or unsupported `HELLO`, a second HELLO, an invalid room message, or a binary message closes the connection. Do not put a session cookie or password in the message.

## 5. Verify

From `backend` in PowerShell with the separate test database configured:

```powershell
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
mvn -B test
```

The WebSocket integration test checks successful `HELLO`/`WELCOME`, anonymous and nonmember denial, missing/wrong Origin including same-host but unconfigured Origin, missing workspace, invalid version and binary messages, client close cleanup, logout closure, isolation of a second login session, and expiry cleanup. It uses a test-only endpoint to shorten the idle timeout, then makes an HTTP request to trigger expiry detection. Existing authorization and database tests continue to run.

## 6. Questions and answers

**Why check both the session and Origin?** The session identifies the signed-in user. Origin restricts which browser pages may initiate a cookie-authenticated socket. A non-browser client can choose its Origin, so it does not replace identity and permission checks.

**Why check membership before upgrading?** A valid session alone does not imply access to every workspace. The server checks the requested workspace using the identity from the session and rejects inaccessible workspaces without confirming whether they exist.

**Why isn't the handshake enough for all later edits?** A member can be removed or demoted after connection. Every future sensitive operation needs a current server-side permission check and transaction design that accounts for races with revocation. This lesson handles only the initial handshake.

**Does a WebSocket keep the HTTP session alive?** Not in this implementation. Once the container detects expiry, its session-destruction callback closes associated sockets. The timing depends on the servlet container's expiry detection.

**Why separate HTTP sessions in the registry?** Logging out one browser session should close its sockets without closing another independently authenticated session belonging to the same user.

**Is WELCOME an acknowledgement that an edit was saved?** No edit was sent or saved. `WELCOME` only confirms the versioned connection exchange. Durable edit acceptance is a later lesson.

**Does this establish a Yjs or y-websocket protocol?** No. `HELLO`/`WELCOME` is a minimal custom learning protocol. The Yjs/Java provider compatibility gate remains open.

## 7. Checkpoint

You can connect an authenticated member, exchange a minimal versioned greeting, and verify cleanup on close, logout and session expiry. Exercise: change the browser Origin or connect as a nonmember and observe rejection, then explain why both checks are needed. Next is [Lesson 20 — Add room membership and bounded messaging](../07-next-lessons-roadmap.md).
