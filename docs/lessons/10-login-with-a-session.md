# Lesson 10: Log in with a session

## 1. What we built

A user created in Lesson 9 can now sign in with their email address and password in the local development profile. Spring Security checks the password hash stored in PostgreSQL and associates the authenticated identity with a server-side session. `GET /api/me` shows which identity the current session represents.

The temporary `learner` account remains available **only as a local bootstrap account**. It can provision new users through `/api/dev/users`; ordinary stored users cannot. This keeps Lesson 9's user-provisioning flow usable until a proper administration and membership model exists.

**Important boundary:** all signed-in users can currently access the same document API. Users are not yet isolated by workspace or document. The `dev` profile is local-only and does not provide a production authentication system. The default non-development profile still denies protected routes.

## 2. What problem does a session solve?

Without a session, a client might need to send an email and password with every request. Instead:

```text
1. Browser asks for a CSRF token and receives an anonymous session cookie.
2. Browser sends email, password and CSRF token to POST /login.
3. Spring Security loads the matching user and checks the password hash.
4. On success, Spring changes the session ID and associates the user with it.
5. Browser sends the new session cookie on later requests.
6. The server retrieves the identity for the session; GET /api/me shows it.
```

The cookie carries a session identifier, not the user's password. The identity lives in server-side session state. Our first version stores sessions in the application process, so a restart signs users out. Document and account data remain in PostgreSQL.

An attacker who steals a valid session cookie may be able to act as that user until the session is invalidated. `HttpOnly`, HTTPS, correct authorization, session expiry and protection against cross-site scripting are part of a complete defense; a session is not a magic security guarantee.

## 3. The code, step by step

Open the complete source files in `backend/src/main/java/com/example/collaborativeworkspace/`:

1. **`UserRepository.java`** adds `findForAuthentication`. It selects an email and its stored hash using a parameterized SQL query and returns Spring Security `UserDetails`. The hash is used internally, not returned in an API response.
2. **`DevelopmentUserDetailsService.java`** implements Spring's `UserDetailsService`. It selects the temporary `learner` identity or looks up an ASCII email after lowercasing it, matching Lesson 9's normalization policy. It rejects invalid/oversized usernames and returns a generic failure for unknown users. A fresh copy of the bootstrap identity is returned on each login because Spring can erase credentials from an authenticated `UserDetails` object.
3. **`SecurityConfiguration.java`** creates the bootstrap account with `ROLE_BOOTSTRAP`; stored users have `ROLE_USER`. The `/api/dev/users` route requires the bootstrap role. The form-login filter and CSRF protection remain enabled. The configuration explicitly uses the servlet container's `changeSessionId` session-fixation defense.
4. **`BoundedBcryptPasswordEncoder.java`** wraps Spring's BCrypt implementation and rejects a password containing NUL or more than 72 UTF-8 bytes, including during login. This avoids accepting a longer candidate merely because a BCrypt implementation ignores a suffix beyond its input limit. Hashes already written by Lesson 9 remain compatible.
5. **`CurrentUserController.java`** adds the authenticated `GET /api/me` endpoint. It reads the identity from Spring's `Authentication`, not from a client-supplied username, and returns a no-store response such as `{"username":"alice@example.com"}`.
6. **`application-dev.properties`** limits the local listener to `127.0.0.1`, uses `HttpOnly` and `SameSite=Lax` session-cookie settings, and limits session tracking to cookies. Local HTTP deliberately does not set a `Secure` cookie: browsers need HTTPS for that flag. A production deployment must configure HTTPS and secure cookies, but this lesson does not enable a production login path.

Spring Security's [`UserDetailsService`](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/user-details-service.html) loads user details; its authentication provider checks the submitted password with `PasswordEncoder`. A separate [session-fixation protection](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html) changes the session ID after login.

### Why not use the email supplied to `/api/me`?

Anyone can change a URL or JSON field. The only trustworthy identity for an authenticated request is the one established by the server's authentication process. The endpoint ignores query parameters such as `?username=someone-else` and reads `Authentication.getName()` instead. This identifies the caller; it does **not** yet enforce ownership of document data.

## 4. Run it locally

In PowerShell 7, start the server:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:WORKSPACE_DEV_PASSWORD = Read-Host -MaskInput 'Choose a temporary learner password'
mvn spring-boot:run
```

The password you choose belongs to the temporary `learner` account and is used only while this server is running. It is separate from your PostgreSQL password and any stored user's password. Use Ctrl+C to stop the server.

If you don't already have a stored account, follow [Lesson 9](09-users-and-passwords.md) to log in as `learner` and create one. The user is stored in the development database. Then open a private browser window, go to `http://127.0.0.1:8080/login`, enter the stored account's email in the **Username** field, and enter its password. After successful login, visit `http://127.0.0.1:8080/api/me`; the response should contain the normalized email.

Spring's default form may redirect to `/` after login. A 404 at `/` is expected because the application has no homepage. Visit `/api/me` to verify the session. A wrong password redirects to the login page with an error. To test a second user, create it with the bootstrap account and use a separate private browser window or browser profile.

## 5. Verification

Run all unit and database-backed HTTP tests:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
mvn -B test
```

The separate test database is used. The suite checks successful stored-user login, wrong password, unknown user, anonymous access, two independent sessions, server-derived `/api/me` identity, session-ID rotation, `HttpOnly` and `SameSite` cookie attributes, rejection of the old CSRF token after login, and a regular user's denial from the bootstrap provisioning route. It also checks that a password longer than BCrypt's 72-byte limit cannot authenticate as its 72-byte prefix. The full run reported **5 tests passed, 0 failures**. Without `WORKSPACE_RUN_DB_TESTS=true`, the database-backed tests are skipped.

Existing Lesson 9 passwords do not need rehashing for this change; their BCrypt hashes are verified with the same algorithm. The test creates and deletes its own test users.

## 6. Common questions and mistakes

**Why do I get 401 from `/api/me`?** The request has no valid authenticated session. Log in and use the same browser session. An application restart clears in-memory sessions.

**Why does my stored user get 403 when creating another user?** The provisioning endpoint is reserved for `learner` as a temporary bootstrap role. User and workspace administration will be designed separately.

**Why does a POST fail after login even though the user is signed in?** Authentication and CSRF are separate controls. Fetch a fresh token after logging in and send it with the same session cookie.

**What is session fixation?** It is an attempt to make a user authenticate using a session ID already known to an attacker. Rotating the ID at login prevents the old ID from becoming the newly authenticated session.

**Can a different user access my document?** At this checkpoint, yes: authenticated users share the document API. Workspace and document authorization are future lessons and must be implemented before multi-user deployment.

**Why do we still have a temporary account?** It is a local bootstrap mechanism for creating the first stored users. It is explicitly not an administrator model for production. Future lessons will replace it with appropriate provisioning and authorization.

## 7. Exercise and next step

Create two development users, sign in using two independent browser sessions, and inspect `/api/me` in each. Explain why changing a URL query parameter cannot change the server's authenticated identity. Lesson 11 will examine logout and session lifetime in more detail.
