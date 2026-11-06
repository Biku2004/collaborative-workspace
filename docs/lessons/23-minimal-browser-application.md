# Lesson 23 — Create the minimal browser application

> [Lesson 24](24-plain-text-collaborative-editor.md) adds a plain-text editor after a document is opened.

## 1. What we built

A small React and TypeScript browser application lets an existing stored user sign in, browse their workspaces and see the documents in a selected workspace. It supports pagination, sign-out, loading states and understandable errors. It does not yet open or edit document content; the collaborative editor starts in Lesson 24.

The browser sends requests through a local Vite development proxy to the Spring application. The browser uses the existing server-side session cookie and CSRF protection; no password, session token or API secret is embedded in the frontend or stored in localStorage.

```text
browser (127.0.0.1:5173)
  -> same-origin /api, /login and /logout requests
  -> local Vite development proxy
  -> Spring Security + workspace authorization
  -> local PostgreSQL -> JSON -> browser renders permitted results
```

The proxy is a local-development arrangement, not a production deployment. Both Vite and the backend bind to loopback by default. Vite's [proxy configuration documentation](https://vite.dev/config/server-options#server-proxy) describes how matching requests are forwarded.

## 2. Follow the code

1. `frontend/package.json` and `package-lock.json` pin React, TypeScript, Vite and the browser test tools. `npm ci` installs the lockfile.
2. `frontend/vite.config.ts` serves the UI at `http://127.0.0.1:5173` and proxies `/api`, `/login`, `/logout` and the future WebSocket path to `http://127.0.0.1:8080`. The optional `WORKSPACE_PROXY_TARGET` must be an explicit loopback HTTP origin with a port; it is a local server setting, not a browser secret.
3. `frontend/src/lib/api.ts` fetches CSRF tokens, submits the login form, checks `/api/me`, logs out with CSRF and reads workspace/document pages. A manual redirect is followed by an explicit `/api/me` check, so a login redirect alone is not treated as success.
4. `frontend/src/app/App.tsx` manages the login and list states, checks the current session on page load, shows errors and prevents stale list responses from replacing a newer workspace selection. React renders user-provided titles as text.
5. `frontend/src/app/styles.css` provides the responsive layout and visible keyboard focus. `frontend/index.html` and `src/main.ts` mount the application.
6. `BrowserWorkspaceIntegrationTest.java` starts the actual backend with the dedicated test database, starts a Vite process on a temporary loopback port and runs `frontend/test/workspace.spec.ts` in Chromium. It creates and removes only its own test data.

## 3. Start the backend

In PowerShell, use the existing local development database configured in earlier lessons:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:WORKSPACE_DEV_PASSWORD = Read-Host -MaskInput 'Temporary learner password'
$env:WORKSPACE_WEBSOCKET_ALLOWED_ORIGIN = 'http://127.0.0.1:5173'
mvn spring-boot:run
```

Keep that window running. The allowed WebSocket Origin prepares the backend for the later browser collaboration lesson; Lesson 23 itself only uses HTTP. The learner password is for local account provisioning. Sign into the browser app with a **stored user email and password**, not the `learner` bootstrap account. If necessary, provision a user using [Lesson 9](09-users-and-passwords.md), and create a workspace/document using [Lesson 12](12-create-workspaces.md) and [Lesson 14](14-workspace-document-creation.md).

## 4. Start the frontend

In a second PowerShell window:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\frontend
npm ci
npm run dev
```

Open `http://127.0.0.1:5173` in your browser. Sign in with your stored account, choose a workspace and browse its documents. Use **Load more** when available. Sign out and reload to confirm the old session no longer shows the workspace data.

If the backend uses another local port, set, for example, `$env:WORKSPACE_PROXY_TARGET = 'http://127.0.0.1:18080'` before starting Vite. Vite is configured with a strict port; stop an existing process using 5173 or deliberately change the configuration rather than assuming a different Origin is equivalent. `localhost` and `127.0.0.1` are different hosts for browser cookie and Origin rules.

## 5. Login, cookies and CSRF

1. The application asks `/api/csrf` for a CSRF token. Spring may establish a session cookie.
2. The form posts the email, password and token to `/login`. The password stays in component memory only long enough to submit and is cleared afterward.
3. The browser handles the server's session cookie. The app checks `/api/me` to confirm the authenticated identity.
4. Subsequent same-origin requests carry the eligible cookie. The backend checks membership for workspace and document reads.
5. Sign-out fetches a fresh CSRF token, posts `/logout`, and checks that `/api/me` now rejects the session before clearing the UI.

A 401 from an authenticated API request returns the UI to sign-in. A 403 displays a permission error; a 404 for a workspace explains that it is unavailable or access may have been removed. A failed network request reports that the service cannot be reached. The API response decides access; the frontend's visibility checks are only presentation.

## 6. Verify

Build the app and audit the pinned dependencies:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\frontend
npm ci
npm run build
npm audit
```

For the real browser integration test, install Chromium through Playwright (`npx playwright install chromium`) or set `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` to an installed Chromium/Chrome executable. In `backend`, use the dedicated test database:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_BROWSER_TESTS = 'true'
mvn -B '-Dtest=BrowserWorkspaceIntegrationTest' test
```

The test starts its own backend and local Vite server, creates two test users and their workspaces, and cleans up the records. It checks incorrect and correct login, session restoration on reload, document pagination, a title containing HTML-looking text, permission removal while signed in, account isolation and logout. It checks browser page errors and captures desktop/mobile screenshots under ignored `frontend/test-results`. Its database guard checks the actual database name before Flyway or test writes. These browser tests do not yet exercise WebSocket editing or offline behavior.

## 7. Questions and answers

**Why does the browser still need server-side authorization?** Users can call APIs directly or change the frontend. The backend must check the authenticated user and workspace membership for every protected operation.

**Why fetch a CSRF token if we already have a session cookie?** Browsers may send cookies automatically. CSRF protection requires a token that a malicious unrelated site cannot simply supply in a same-origin request. The backend enforces it on state-changing requests such as login and logout.

**Why check `/api/me` after a login redirect?** Both successful and failed form logins can return redirects. A redirect status alone does not establish that authentication succeeded.

**What happens after membership is removed?** A list already displayed can become stale. When the user requests the workspace again, the server returns 404 and the UI reports that the workspace is unavailable or access changed. Later lessons will handle live updates and revocation more fully.

**Why use a same-origin development proxy?** It lets the browser call relative paths and keeps cookie and CSRF behavior understandable during local development. It does not replace production TLS, deployment configuration or authorization.

## 8. Checkpoint

A stored user can sign in, browse authorized workspaces and paginated document metadata, and sign out from a browser. Exercise: sign in as two users with different workspace memberships and explain why their lists differ. Next is [Lesson 24 — Add a plain-text collaborative editor](../07-next-lessons-roadmap.md); the provider compatibility and safe Yjs validation gate remains open.
