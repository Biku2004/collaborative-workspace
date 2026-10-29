# API endpoint reference through Lesson 11

This table describes the currently implemented routes, not the planned collaboration API. The `dev` profile is a local-only learning setup. Stored users can log in, but document access is not isolated by workspace or owner.

| Method | Path | Purpose | Access in `dev` | Request and success response |
|---|---|---|---|---|
| GET | `/api/status` | Confirm that the application responds | Public | Returns 200 and `{"status":"running"}`. This is not a database readiness check. |
| GET | `/api/csrf` | Obtain a CSRF token for the current session | Public, `dev` only | Returns 200 with `headerName`, `parameterName`, and `token`; response is marked `no-store`. |
| GET | `/login` | Display Spring Security's login form | Public, `dev` only | HTML login form with a CSRF token. |
| POST | `/login` | Authenticate a stored user or temporary `learner` bootstrap account | Public, `dev` only; CSRF required | Form fields `username`, `password`, and CSRF token. Success normally returns a redirect and session cookie; failure redirects to a login error. |
| GET | `/api/me` | Show the current session identity | Authenticated session, `dev` only | Returns 200 with `username`; identity comes from the authenticated session. |
| GET | `/logout` | Display the logout confirmation form | `dev` profile | Returns a confirmation page; does not itself log the user out. |
| POST | `/logout` | Invalidate the current session | Session and CSRF token | Spring Security invalidates the server-side session, clears the session cookie and redirects to `/login?logout`. |
| POST | `/api/documents` | Create a document | Authenticated session and CSRF token | JSON `{ "title": "Meeting notes" }`. Returns 201, a document body and a `Location` header. A title must be nonblank, at most 200 UTF-16 code units, and contain no NUL character. |
| GET | `/api/documents/{id}` | Retrieve a document | Authenticated session | UUID path parameter. Returns 200 with `id`, `title`, `createdAt`; invalid UUID gives 400 and a missing document gives 404. |
| POST | `/api/dev/users` | Provision a stored user for the learning project | `dev` only; authenticated `learner` bootstrap session and CSRF token | JSON with `email` and `password`. Returns 201 with `id`, normalized `email`, `createdAt`; duplicate email gives 409 and invalid input gives 400. The response never includes the password or hash. |

In the default non-`dev` profile, `GET /api/status` is allowed and protected routes are denied. The local dev profile binds to `127.0.0.1` by default. Exact security responses depend on which filter rejects a request: an anonymous protected API GET returns 401, while a POST without a CSRF token can return 403 before authentication is checked.

For commands, cookies, login, logout and CSRF examples, see [Lesson 8](lessons/08-spring-security.md), [Lesson 10](lessons/10-login-with-a-session.md), and [Lesson 11](lessons/11-logout-and-session-lifetime.md). No endpoint latency measurements or service-level performance claims have been made.
