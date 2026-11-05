# API endpoint reference through Lesson 26

This table describes the currently implemented routes, not the planned collaboration API. The `dev` profile is a local-only learning setup. Stored users can log in; document operations are restricted by workspace membership. Archived documents are readable by direct link but omitted from normal lists.

| Method | Path | Purpose | Access in `dev` | Request and success response |
|---|---|---|---|---|
| GET | `/api/status` | Confirm that the application responds | Public | Returns 200 and `{"status":"running"}`. This is not a database readiness check. |
| GET | `/api/csrf` | Obtain a CSRF token for the current session | Public, `dev` only | Returns 200 with `headerName`, `parameterName`, and `token`; response is marked `no-store`. |
| GET | `/login` | Display Spring Security's login form | Public, `dev` only | HTML login form with a CSRF token. |
| POST | `/login` | Authenticate a stored user or temporary `learner` bootstrap account | Public, `dev` only; CSRF required | Form fields `username`, `password`, and CSRF token. Success normally returns a redirect and session cookie; failure redirects to a login error. |
| GET | `/api/me` | Show the current session identity | Authenticated session, `dev` only | Returns 200 with `username` and the stable stored-user `id` when available; the development bootstrap principal has no stored ID. Identity comes from the authenticated session; the response is `no-store`. |
| POST | `/api/workspaces` | Create a workspace and its owner membership | Stored-user session and CSRF token, `dev` only | JSON `{ "name": "Product team" }`; returns 201 with workspace fields and `role: OWNER`. |
| GET | `/api/workspaces` | List the current user's workspace memberships | Stored-user session, `dev` only | Returns 200 and an array scoped to the signed-in user; `limit` is 1–100 (default 20), `after` is a UUID cursor, and `X-Next-Cursor` provides the next position. |
| GET | `/api/workspaces/{workspaceId}/members` | List a workspace's members | Current workspace member | Returns 200 and member IDs, emails and roles; supports `limit` and `after` with `X-Next-Cursor`; nonmembers receive 404. |
| POST | `/api/workspaces/{workspaceId}/members` | Add an existing user or change their role | Workspace owner and CSRF token | JSON with `email` and `role` (`OWNER`, `EDITOR`, `VIEWER`); returns 200, or 409 if the change would remove the last owner. |
| DELETE | `/api/workspaces/{workspaceId}/members/{userId}` | Remove a member | Workspace owner and CSRF token | Returns 204; 409 protects the last owner, 404 indicates an inaccessible or missing resource. |
| GET | `/logout` | Display the logout confirmation form | `dev` profile | Returns a confirmation page; does not itself log the user out. |
| POST | `/logout` | Invalidate the current session | Session and CSRF token | Spring Security invalidates the server-side session, clears the session cookie and redirects to `/login?logout`. |
| POST | `/api/workspaces/{workspaceId}/documents` | Create a document inside a workspace | Workspace owner or editor, with session and CSRF token | JSON `{ "title": "Meeting notes" }`. Returns 201 and a document body including `workspaceId`. Viewer gets 403; nonmember gets 404. |
| PATCH | `/api/workspaces/{workspaceId}/documents/{documentId}` | Rename an active document | Owner or editor, with CSRF | JSON `{ "title": "New title", "version": 0 }`; returns 200 with incremented version, 409 for a stale version or archived document. |
| POST | `/api/workspaces/{workspaceId}/documents/{documentId}/archive` | Archive an active document | Owner or editor, with CSRF | JSON `{ "version": 0 }`; returns 200 with `archived: true` and incremented version, or 409 for a stale version or already archived document. |
| POST | `/api/documents` | Former unscoped creation route | Removed | Returns 404 for an authenticated request with valid CSRF. |
| GET | `/api/documents/{id}` | Read one document | Any current workspace member | Returns 200 with document metadata, including archived documents; missing, unassigned, and inaccessible documents return 404. |
| GET | `/api/workspaces/{workspaceId}/documents` | List documents in a workspace | Any current workspace member | Returns 200 with active `items` and `nextCursor`; accepts `limit` (1–100, default 20) and optional `cursor`. Inaccessible workspace gives 404, invalid page parameters give 400. |
| GET (WebSocket upgrade) | `/api/workspaces/{workspaceId}/socket` | Open a minimal WebSocket greeting | Stored-user session, exact allowed Origin, current workspace member; `dev` only | Send `{"type":"HELLO","version":1}`; receive `WELCOME` with the workspace ID and authenticated stored-user ID. Then `JOIN`, `TEST_MESSAGE`, and `LEAVE` support temporary document-room messages. `SUBMIT_UPDATE` commits supported updates before `SAVED`; `UPDATE`, `HEAD`, and bounded `SYNC`/`SYNC_PAGE` support single-instance relay and repair. |
| POST | `/api/dev/users` | Provision a stored user for the learning project | `dev` only; authenticated `learner` bootstrap session and CSRF token | JSON with `email` and `password`. Returns 201 with `id`, normalized `email`, `createdAt`; duplicate email gives 409 and invalid input gives 400. The response never includes the password or hash. |

In the default non-`dev` profile, `GET /api/status` is allowed and protected routes are denied. The local dev profile binds to `127.0.0.1` by default. Exact security responses depend on which filter rejects a request: an anonymous protected API GET returns 401, while a POST without a CSRF token can return 403 before authentication is checked.

For commands, cookies, login, logout and workspace examples, see [Lesson 8](lessons/08-spring-security.md), [Lesson 10](lessons/10-login-with-a-session.md), [Lesson 11](lessons/11-logout-and-session-lifetime.md), [Lesson 12](lessons/12-create-workspaces.md), [Lesson 13](lessons/13-membership-and-roles.md), [Lesson 14](lessons/14-workspace-document-creation.md), [Lesson 15](lessons/15-read-and-list-authorized-documents.md), and [Lesson 16](lessons/16-rename-and-archive-documents.md). No endpoint latency measurements or service-level performance claims have been made.

Creation of workspaces and documents accepts an optional `Idempotency-Key` header. For reliable retries, send the same key and normalized input: concurrent or later retries return the original 201 body. Reusing a key with different input gives 409 `IDEMPOTENCY_CONFLICT`. Keys contain 1–128 ASCII letters, digits, underscores or hyphens and are scoped by user and operation (including workspace for documents). Document creation rechecks current editing permission even on replay. Without the header, each request creates a new resource.

All API errors use `{ "code": "...", "message": "..." }` with `Cache-Control: no-store`. Expected codes include `INVALID_REQUEST`, `AUTHENTICATION_REQUIRED`, `ACCESS_DENIED`, `NOT_FOUND`, `DOCUMENT_CONFLICT`, `LAST_OWNER`, `DUPLICATE_USER`, `IDEMPOTENCY_CONFLICT`, `SERVICE_UNAVAILABLE` (503), and `INTERNAL_ERROR` (500). Login/logout HTML and redirect flows retain their existing behavior. See the [quality fixes guide](09-quality-hardening.md) for verification and limitations.

The WebSocket greeting and session cleanup are explained in [Lesson 19](lessons/19-authenticate-websocket.md).

The room protocol and its bounds are explained in [Lesson 20](lessons/20-document-rooms-and-bounded-messaging.md).

The versioned update/acknowledgement contract is described in [Lesson 21](lessons/21-commit-before-acknowledgement.md).

The committed relay and replay contract is described in [Lesson 22](lessons/22-broadcast-and-repair.md).

The local browser login and list flow is described in [Lesson 23](lessons/23-minimal-browser-application.md).

Lesson 27 is in progress: document metadata includes `collaborationFormat`. Existing documents remain `PLAIN_TEXT_V1`; migrations preserve their history. Rich-text creation, replay and writes require the `workspace.rich.enabled` gate, which is disabled by default.

Document creation accepts an optional `format` field. Omitting it and explicitly sending `PLAIN_TEXT_V1` have the same meaning and idempotency behavior. With the gate enabled, `RICH_TEXT_V1` creates an initialized rich-text document; unknown or disabled formats return 400. Reusing an idempotency key with a different format returns 409.

Socket `JOIN` accepts an optional `format` field. A join without it represents a legacy plain-text client; a matching join returns that format in `JOINED`. A mismatch or a disabled format receives `REJECTED` with `SCHEMA_UNSUPPORTED` before the socket joins a document room. The writer and replay services also enforce the format gate.
