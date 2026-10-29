# Lesson 8: Protect the document API with Spring Security

## What we are building

Before this lesson, anyone who could reach the local server could create or read documents. We now require a login for the document API in a **local development profile**. A temporary account lets us learn the security flow before database-backed users arrive in Lesson 9.

The application is still a learning checkpoint: every authenticated development user has access to all documents; there are no workspaces or per-document permissions yet. Do not expose this profile to the internet. In the default profile, the application allows only the public status endpoint and denies other requests.

## Concepts first

**Authentication** answers “who are you?” We verify the temporary username and password. **Authorization** answers “may this identity perform this action?” For now, a valid development identity is allowed to use document endpoints; real workspace rules come later.

**Session:** After a successful form login, the server associates an opaque session identifier in a browser cookie with an authenticated identity. The cookie is not the password. For this lesson sessions are managed by the application process and are lost on restart.

**CSRF:** A browser may attach its cookies automatically when a page sends a request. A malicious site could attempt to cause a state-changing request using that behavior. Spring Security requires an additional CSRF token on requests such as `POST`. A token is not a substitute for authentication or permission checks. Spring's default login and logout POSTs also require CSRF protection. [Spring Security CSRF documentation](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)

**Filter chain:** Spring Security checks incoming requests in a series of filters before a controller handles them. Authentication, CSRF and authorization each have their own responsibilities. A request rejected here does not reach the document controller.

## The request flow

```text
Browser or API client
   │  GET /api/csrf → token and session cookie
   │  POST /login + username + password + CSRF token
   ▼
Spring Security checks token and credentials
   │  successful login → authenticated session
   │  GET /api/csrf again → fresh token after authentication
   │  POST /api/documents + session cookie + token
   ▼
DocumentController → DocumentService → PostgreSQL
```

With the `dev` profile, `GET /api/status` and `GET /api/csrf` are public. An anonymous `GET` to `/api/documents/{id}` receives 401. A state-changing request without a valid CSRF token receives 403; since CSRF is checked before authorization, an anonymous POST without a token may also receive 403 rather than 401. Other paths require authentication except Spring's login page and its handling. In the default profile, only `GET /api/status` is allowed and other requests are denied.

## Files and complete code

The complete, runnable source is in the project, so you can open each file alongside this explanation:

1. `backend/pom.xml` adds `spring-boot-starter-security`. Spring Boot manages its compatible library versions.
2. `backend/src/main/java/com/example/collaborativeworkspace/SecurityConfiguration.java` defines the security filter chains, the development account and the BCrypt encoder.
3. `backend/src/main/java/com/example/collaborativeworkspace/CsrfController.java` exposes the token and its header name for a same-origin client. It uses `Cache-Control: no-store`.
4. `backend/src/main/resources/application-dev.properties` binds the dev server to `127.0.0.1` and configures `HttpOnly` and `SameSite=Lax` for the session cookie.
5. `backend/src/test/java/com/example/collaborativeworkspace/DocumentHttpIntegrationTest.java` exercises the actual login, CSRF, API and PostgreSQL flow. `ClosedByDefaultIntegrationTest.java` verifies that the default profile denies document access.

### Read `SecurityConfiguration.java` in order

- `@Configuration` marks a class that supplies Spring-managed objects; each `@Bean` method creates one.
- `@Profile("dev")` enables temporary development beans only when the `dev` profile is active. The username is `learner`; its password comes from `WORKSPACE_DEV_PASSWORD`, which you choose when starting the application. No password is committed in the source. A missing or blank password prevents the development account from starting.
- `BCryptPasswordEncoder` encodes the password in memory and is also used by the authentication provider to check it. The account and its hash are temporary and are recreated on restart; we have not created a users table.
- The dev filter chain permits the status and CSRF GET endpoints and requires authentication for remaining application requests. `formLogin` enables Spring's built-in `/login` page and login processing. The API uses a 401 entry point for unauthenticated API reads; browser requests to other protected paths can be redirected to login.
- CSRF remains enabled. Error dispatches are permitted so Spring can render errors, not to grant access to the original protected request.
- `@Profile("!dev")` selects the default closed configuration when `dev` is not active. It supplies no temporary users and denies all requests except the status GET. This is a safe starting behavior, not a complete production identity system.

`application-dev.properties` limits this teaching server to the local loopback address by default. Do not override the address to expose it publicly. Local HTTP does not provide transport encryption; production will require HTTPS, secure cookies, persisted identities and workspace authorization.

### Why a CSRF endpoint?

The default Spring login page puts a CSRF token into its HTML form. A JavaScript client or PowerShell script also needs a way to get the token. `GET /api/csrf` returns JSON containing `headerName`, `parameterName`, and `token`, while the corresponding cookie is kept in the client session. Fetch a new token after login because authentication can invalidate the prior token. Never place a CSRF token or a session cookie into logs or share it with another origin.

## Run the local development profile

Open PowerShell in this project's `backend` folder:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:WORKSPACE_DEV_PASSWORD = Read-Host -MaskInput 'Choose a temporary local development password'
mvn spring-boot:run
```

Choose a password for this temporary account; do not reuse your PostgreSQL or other important passwords. `Read-Host -MaskInput` hides the input while you type on PowerShell 7. The value is held in the current process environment, not written into the project. Open `http://127.0.0.1:8080/login`, enter username `learner` and the password you just chose. Stop the server with Ctrl+C. The default login redirects after success; a 404 at `/` after login can be normal because we have no home page yet.

To test a document GET after login, obtain the UUID from a document you already created and visit `http://127.0.0.1:8080/api/documents/<document-id>` in the same browser. Without a document ID, a successful login alone does not create a document.

### Create a document from a separate PowerShell session

In a second PowerShell window, while the server is running, use a session to keep the cookie. Enter the same temporary password when prompted:

```powershell
$password = Read-Host -MaskInput 'Temporary development password'
$csrf = Invoke-RestMethod -Uri http://127.0.0.1:8080/api/csrf -SessionVariable session
$login = @{ username = 'learner'; password = $password; _csrf = $csrf.token }
Invoke-WebRequest -Method Post -Uri http://127.0.0.1:8080/login -WebSession $session -Body $login -SkipHttpErrorCheck | Out-Null
$csrf = Invoke-RestMethod -Uri http://127.0.0.1:8080/api/csrf -WebSession $session
$headers = @{ $csrf.headerName = $csrf.token }
$document = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/api/documents -WebSession $session -Headers $headers -ContentType 'application/json' -Body '{"title":"Security lesson"}'
$document
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/documents/$($document.id)" -WebSession $session
Remove-Variable password,login
```

`-SkipHttpErrorCheck` lets the command follow Spring's login redirect even when the final `/` page is 404. A bad password redirects to a login error; if login fails, the subsequent document request is rejected. Don't paste your actual password into commands or chat. This example requires PowerShell 7, consistent with the tools used for this project.

## Verify the implementation

Run the tests using the **separate test database**:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
mvn -B test
```

The integration test creates a temporary test-only password in the test process. It checks public status, anonymous denial, login without a token, wrong password, successful login, denied write without a token, and successful document creation with a token. It also checks validation and missing documents. A second integration test starts without `dev` and checks that document and CSRF requests are denied. The test database is separate from your development database. Without `WORKSPACE_RUN_DB_TESTS=true`, these database-backed tests are skipped; unit tests still run.

The verified run reported **4 tests, 0 failures, 0 errors, 0 skipped**. Maven/Java produced warnings about older Maven internals and test-agent attachment; those warnings did not fail this run.

## Common problems

| What you see | Likely reason |
|---|---|
| Startup fails because `workspace.dev.password` is missing | Set `WORKSPACE_DEV_PASSWORD` in the same PowerShell window, then start again. |
| Every document request is forbidden | Check that `SPRING_PROFILES_ACTIVE` is `dev`; the default profile intentionally denies document access. |
| Login page reports an error | Check the `learner` username and temporary password. |
| POST returns 403 | Send the CSRF token with the same session cookie; retrieve a fresh token after login. |
| Database connection fails | Run `. .\.env.local.ps1` and check the local PostgreSQL service. |
| 404 after successful login | The login redirects to `/`, but this project does not have a home page yet. |

## Interview questions

**Why is a logged-in user not automatically allowed to access every document?** Authentication identifies the user; the server still needs to check workspace membership and permissions on each relevant path. We add those checks later.

**Why do cookies create CSRF risk?** Browsers attach cookies automatically in relevant requests. A separate token in a form or header helps the server distinguish an intentional request from one initiated by an attacker. Cookie settings and HTTPS also matter.

**Why BCrypt?** Passwords should be verified using a deliberately costly, salted password hash rather than stored in plaintext or protected with a fast general-purpose hash. The right cost must be measured and adjusted as the system evolves.

**What happens on restart?** The temporary account is recreated from the supplied development password, and in-memory sessions are lost. Stored documents remain in PostgreSQL.

## Exercise and next lesson

Start the dev profile, log in, then compare an anonymous document request with an authenticated request. Explain why a POST without the CSRF token fails. Lesson 9 stores users and password hashes; Lesson 10 will connect those users to login. Document ownership and workspace permissions remain separate requirements.
