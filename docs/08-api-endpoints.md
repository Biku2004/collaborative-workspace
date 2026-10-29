# API endpoint reference through Lesson 9

This table describes the currently implemented routes, not the planned collaboration API. The `dev` profile is a local-only learning setup. Persistent user accounts created in Lesson 9 cannot log in yet, and document access is not isolated by workspace or owner.

| Method | Path | Purpose | Access in `dev` | Request and success response |
|---|---|---|---|---|
| GET | `/api/status` | Confirm that the application responds | Public | Returns 200 and `{"status":"running"}`. This is not a database readiness check. |
| GET | `/api/csrf` | Obtain a CSRF token for the current session | Public, `dev` only | Returns 200 with `headerName`, `parameterName`, and `token`; response is marked `no-store`. |
| GET | `/login` | Display Spring Security's login form | Public, `dev` only | HTML login form with a CSRF token. |
| POST | `/login` | Authenticate the temporary `learner` account | Public, `dev` only; CSRF required | Form fields `username`, `password`, and CSRF token. Success normally returns a redirect and session cookie; failure redirects to a login error. |
| POST | `/logout` | Invalidate the current session | Session and CSRF token | Spring Security processes logout and normally redirects. Session management is covered more fully in Lesson 11. |
| POST | `/api/documents` | Create a document | Authenticated session and CSRF token | JSON `{ "title": "Meeting notes" }`. Returns 201, a document body and a `Location` header. A title must be nonblank, at most 200 UTF-16 code units, and contain no NUL character. |
| GET | `/api/documents/{id}` | Retrieve a document | Authenticated session | UUID path parameter. Returns 200 with `id`, `title`, `createdAt`; invalid UUID gives 400 and a missing document gives 404. |
| POST | `/api/dev/users` | Provision a stored user for the learning project | `dev` only; authenticated session and CSRF token | JSON with `email` and `password`. Returns 201 with `id`, normalized `email`, `createdAt`; duplicate email gives 409 and invalid input gives 400. The response never includes the password or hash. |

In the default non-`dev` profile, `GET /api/status` is allowed and protected routes are denied. The local dev profile binds to `127.0.0.1` by default. Exact security responses depend on which filter rejects a request: an anonymous protected API GET returns 401, while a POST without a CSRF token can return 403 before authentication is checked.

For commands, cookies, login and CSRF examples, see [Lesson 8](lessons/08-spring-security.md) and [Lesson 9](lessons/09-users-and-passwords.md). No endpoint latency measurements or service-level performance claims have been made.
