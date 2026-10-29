# Lesson 11: Log out and manage session lifetime

## 1. What are we building?

In Lesson 10, a stored user could log in and obtain a session. In this lesson we make the end of a session explicit:

- A **CSRF-protected `POST /logout`** invalidates the current server-side session and clears its authentication.
- The response asks the browser to delete its `JSESSIONID` cookie and redirects to `/login?logout`.
- A local development session expires after **30 minutes of inactivity**. An active session is not automatically logged out after 30 minutes of total elapsed time.
- Logging out one browser session does not log out another session, even when both belong to the same user.

This is still a local development checkpoint. The application stores sessions in its own process; application restart signs users out. There is no shared session store, absolute session lifetime, account-wide logout, or workspace authorization yet.

## 2. The problem

Alice signs in on two browsers. When she logs out of the first browser, that browser should no longer access protected endpoints. If somebody saved its old session cookie, replaying the old cookie should not restore access. Her second browser should remain signed in because it has a different session.

We also want an abandoned session to stop working after a period of inactivity. A timeout is a server-side rule; a browser continuing to hold an old cookie does not make the expired session valid again.

```text
Browser A, authenticated                Browser B, authenticated
       │                                        │
       ├─ POST /logout + CSRF token              │
       ▼                                        │
Server invalidates A's session                  │
       │                                        │
       ├─ A: GET /api/me → 401                  └─ B: GET /api/me → 200
       └─ replay A's old cookie → 401
```

## 3. How logout works

Spring Security already provides the `/logout` flow. We explicitly configure its behavior in `SecurityConfiguration.java`:

```java
.logout(logout -> logout.invalidateHttpSession(true)
        .clearAuthentication(true)
        .deleteCookies("JSESSIONID")
        .logoutSuccessUrl("/login?logout"))
```

1. `invalidateHttpSession(true)` invalidates the session on the server.
2. `clearAuthentication(true)` clears the authentication associated with the current request.
3. `deleteCookies("JSESSIONID")` sends a cookie-clearing instruction to the client. The authoritative protection is server-side invalidation: a saved copy of the old cookie must not work.
4. `logoutSuccessUrl(...)` chooses the success redirect.

CSRF protection is still enabled. A POST without the token is rejected and **must not** terminate the session. Spring's `GET /logout` displays a confirmation page; it does not perform the state-changing logout. See [Spring Security: Handling Logouts](https://docs.spring.io/spring-security/reference/servlet/authentication/logout.html).

## 4. How session expiry works

The local development configuration in `backend/src/main/resources/application-dev.properties` now contains:

```properties
server.servlet.session.timeout=30m
```

This is a **server-side idle timeout**: if the session has no qualifying requests for the configured interval, it expires. The configured value is 30 minutes; cleanup timing can vary and the server checks validity when requests arrive. It is distinct from:

- **Cookie lifetime:** how long the browser keeps the cookie. A browser cookie can remain after the server no longer recognizes its session.
- **Absolute session lifetime:** a maximum total age regardless of activity. We have not implemented one.
- **Logout everywhere:** invalidation of all sessions belonging to a user. We have not implemented this either.

Requests that use the session, including background calls, may extend its idle lifetime. This local configuration is not a production policy; actual deployments need explicit timeout choices, secure cookies over HTTPS, and an appropriate session store. [Spring Boot's application properties](https://docs.spring.io/spring-boot/appendix/application-properties/) document the servlet session timeout.

## 5. Files changed

1. `backend/src/main/java/com/example/collaborativeworkspace/SecurityConfiguration.java` — explicit Spring Security logout configuration.
2. `backend/src/main/resources/application-dev.properties` — 30-minute development idle timeout.
3. `backend/src/test/java/com/example/collaborativeworkspace/SessionLifecycleIntegrationTest.java` — real HTTP tests of logout, CSRF, session isolation, replay, and expiry.

The test uses a **test-only** controller to shorten one session's timeout to one second. That controller exists only under `src/test`; it is not part of the application. This permits a real expiry test without waiting 30 minutes. A separate assertion checks that the normal configured timeout is 1,800 seconds.

## 6. Try it in the browser

Start the local development server in PowerShell 7:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:WORKSPACE_DEV_PASSWORD = Read-Host -MaskInput 'Choose a temporary learner password'
mvn spring-boot:run
```

1. Open `http://127.0.0.1:8080/login` and sign in as `learner` with the password you chose, or sign in with a stored account from Lesson 10.
2. Open `http://127.0.0.1:8080/api/me`. You should see the session's identity.
3. Open `http://127.0.0.1:8080/logout` and submit the confirmation form. Spring's form includes the CSRF token.
4. Open `/api/me` again. The API returns **401 Unauthorized** because that session was invalidated.

Use a separate private browser window or browser profile to demonstrate another session staying signed in. Stop the server with Ctrl+C.

### Optional PowerShell logout after logging in

Using the `$session` created by the Lesson 8 or Lesson 9 PowerShell login example:

```powershell
$csrf = Invoke-RestMethod -Uri http://127.0.0.1:8080/api/csrf -WebSession $session
$headers = @{ $csrf.headerName = $csrf.token }
Invoke-WebRequest -Method Post -Uri http://127.0.0.1:8080/logout -WebSession $session -Headers $headers -SkipHttpErrorCheck | Out-Null
Invoke-WebRequest -Uri http://127.0.0.1:8080/api/me -WebSession $session -SkipHttpErrorCheck | Select-Object StatusCode
```

The final status should be `401`. The logout success response normally redirects to `/login?logout`; PowerShell may follow that redirect. Never print or share the session cookie or CSRF token.

## 7. Verify with tests

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
mvn -B test
```

The complete suite passed **7 tests, with no failures** against the separate PostgreSQL test database. The lifecycle tests verify:

- A logout POST without CSRF returns 403 and leaves the session usable.
- A valid logout invalidates the session; its old cookie cannot be replayed.
- A second independently authenticated session remains valid.
- The configured idle timeout is 1,800 seconds, and a test-only shortened idle interval actually expires a session.

When `WORKSPACE_RUN_DB_TESTS` is not set to `true`, the database-backed integration tests are skipped. The short-expiry test validates the mechanism with a one-second test interval; it does not wait 30 minutes in real time.

## 8. Interview questions

**Why do we invalidate the server session instead of only deleting the cookie?** A copied cookie could still be replayed if the server continued to accept its session. Server invalidation removes that authority.

**Why does logout need a CSRF token?** With a cookie-based session, another site could otherwise induce the browser to send a logout request and force the user out. The token shows the request came through the application's expected flow.

**What is the difference between idle and absolute timeout?** Idle timeout measures inactivity since the last relevant request; absolute timeout limits total session age even if the user remains active. This lesson implements the idle timeout only.

**Does logging out on one device log out all devices?** Not here. It invalidates the current session. Account-wide revocation would need a separate session registry or another server-side revocation mechanism.

**What happens when this application restarts?** Its in-memory sessions are lost. Users can log in again; their database records are not deleted.

## 9. Exercise and next step

Sign in in two independent browser sessions, log out of one, and check `/api/me` in both. Explain why the session cookie and the server session are different things. Next, Lesson 12 adds workspaces and makes the creator an owner in a transaction.
