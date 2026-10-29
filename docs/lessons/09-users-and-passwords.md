# Lesson 9: Store users and hash passwords

## 1. What did we build?

We added an `app_users` table and a **local-development-only provisioning endpoint**. The temporary `learner` account from Lesson 8 can create an account with an email address and password. The backend stores a BCrypt password hash in PostgreSQL and returns only the account ID, normalized email and creation time.

At the Lesson 9 checkpoint, the new account could not log in. [Lesson 10](10-login-with-a-session.md) now connects stored users to authentication. Provisioning remains a controlled local-development step, not public registration or a production-ready identity service.

## 2. The problem, with an example

Suppose Alice asks to create an account for `Alice@Example.com`. The application needs to remember the account after restarting, without storing the password itself. We also do not want both `Alice@Example.com` and `alice@example.com` to create separate accounts under this project's chosen policy.

```text
learner session + CSRF token
    │ POST /api/dev/users { email, password }
    ▼
controller validates request
    ▼
service normalizes email and hashes password
    ▼
repository inserts parameterized SQL
    ▼
PostgreSQL UNIQUE constraint enforces one normalized email
    ▼
201 response { id, email, createdAt } — no password or hash
```

The endpoint is enabled only in the `dev` profile. It requires the existing `learner` session and a valid CSRF token. In the default profile, the existing deny-by-default security policy remains in place. Local development is bound to the loopback address; do not expose this setup publicly.

## 3. Concepts to understand

### Hashing versus encryption

Encryption is designed to be reversed with a key. Password **hashing** is designed for verification: the server stores a derived value, then checks a later candidate password against it. It does not recover the original password. BCrypt adds a random salt and uses a configurable work factor, making each guess more expensive. A hash is still sensitive data: avoid displaying or logging it and protect database access.

We reuse the Spring Security `PasswordEncoder` from Lesson 8. The development setting `workspace.security.bcrypt-strength` defaults to 10 and accepts 10–16. Higher values cost more CPU; benchmark an appropriate setting before any real deployment. BCrypt is used here to extend the existing lesson setup; [OWASP recommends Argon2id for new systems](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html) when available. We should revisit the algorithm and migration strategy before production.

### Email normalization is a product decision

This project accepts ASCII email addresses, rejects surrounding whitespace, and lowercases the entire address using `Locale.ROOT`. That is a deliberately simplified identity policy, not a universal statement that all email providers treat every address as case-insensitive. PostgreSQL checks that stored values are lowercase and trimmed. The database's unique constraint is authoritative when requests happen concurrently.

### Password limits

Input must be nonblank and between 12 and 72 Java UTF-16 code units; the UTF-8 encoding must also be at most **72 bytes**, and NUL characters are rejected. This extra byte check matters because a character such as `é` can use more than one UTF-8 byte. BCrypt implementations commonly have a 72-byte limit; rejecting longer inputs avoids silently accepting truncated passwords. See [OWASP's BCrypt input guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).

These are teaching constraints, not a complete password policy: breach-password screening, rate limits, recovery, email verification and real-world account lifecycle are future work.

## 4. Files and code flow

All complete code is in the project; open the files in this order:

1. `backend/src/main/resources/db/migration/V2__create_users.sql` — creates `app_users` with UUID primary key, unique normalized `email`, `password_hash`, and database-generated `created_at`. Flyway records the migration version and applies it on startup.
2. `CreateUserRequest.java` — validates the request using `@NotBlank`, `@Email`, `@Size` and an ASCII pattern. The password is marked write-only, and the class has no generated password-bearing `toString`.
3. `DevelopmentUserController.java` — exposes `POST /api/dev/users` only with the `dev` profile, calls the service, and returns HTTP 201 with a `UserAccount` response. Existing security filters require authentication and CSRF.
4. `UserService.java` — verifies the BCrypt byte limit, converts email to lowercase, generates a UUID, and hashes the password with `PasswordEncoder` before calling the repository.
5. `UserRepository.java` — inserts values with JDBC parameters and maps only safe response fields. A database duplicate-key exception becomes `DuplicateUserException`, which the error handler maps to 409.
6. `UserAccount.java` — the response record; it intentionally contains no password or password hash. `InvalidUserInputException.java` and `DuplicateUserException.java` represent error conditions.
7. `SecurityConfiguration.java` — keeps the temporary login and defines the configurable BCrypt work factor. `ApiExceptionHandler.java` provides generic 400 errors and a 409 duplicate error.
8. `DocumentHttpIntegrationTest.java` — tests authenticated provisioning, input validation, concurrency and hash verification against the separate PostgreSQL test database.

**Why both validation and a database constraint?** Application validation provides a useful 400 response, while the database constraint enforces uniqueness even when two requests pass through the application at the same time. A check such as `SELECT first; INSERT later` alone has a race.

## 5. Run the application

Start it with the existing project database settings. Choose a temporary `learner` password; do not reuse a real password.

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:WORKSPACE_DEV_PASSWORD = Read-Host -MaskInput 'Choose a temporary learner password'
mvn spring-boot:run
```

Flyway applies the V2 migration if it has not already been applied. On this machine V2 was verified against both dedicated databases. Keep this window open. In a **second PowerShell 7 window**, enter the same learner password and a different password for the account you are creating:

```powershell
$learnerPassword = Read-Host -MaskInput 'Learner password'
$newPassword = Read-Host -MaskInput 'New account password (at least 12 characters)'
$base = 'http://127.0.0.1:8080'
$csrf = Invoke-RestMethod "$base/api/csrf" -SessionVariable session
$login = @{ username = 'learner'; password = $learnerPassword; _csrf = $csrf.token }
Invoke-WebRequest -Method Post "$base/login" -WebSession $session -Body $login -SkipHttpErrorCheck | Out-Null
$csrf = Invoke-RestMethod "$base/api/csrf" -WebSession $session
$headers = @{ $csrf.headerName = $csrf.token }
$body = @{ email = 'alice@example.com'; password = $newPassword } | ConvertTo-Json -Compress
$account = Invoke-RestMethod -Method Post "$base/api/dev/users" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $body
$account
Remove-Variable learnerPassword,newPassword,login,body
```

Expected response shape (the ID and timestamp will differ):

```json
{"id":"<uuid>","email":"alice@example.com","createdAt":"<timestamp>"}
```

Running it again with the same email, even with different letter case, returns **409 Conflict**. Invalid email or password input returns **400 Bad Request**. Missing session or CSRF token is rejected. The command creates a real account in your **development database**; use a different example email for another attempt. `-SkipHttpErrorCheck` is used for the login redirect: if login fails, the user creation request will be rejected.

## 6. Verify the lesson

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
mvn -B test
```

The integration test uses the separate test database. It verifies: migration V2, anonymous and missing-CSRF rejection, authenticated creation, normalized email, no password in the response, BCrypt verification of the stored hash, incorrect-password mismatch, invalid email and passwords, and two simultaneous requests for the same email producing one 201 and one 409. It deletes its own test account. The full run reported **4 tests passed, 0 failures**. Without the opt-in variable, database integration tests are skipped.

The development database was also migrated and a temporary test account was created and removed. Lesson 10 separately verifies database-backed login.

## 7. Common mistakes

| Problem | What to check |
|---|---|
| 401 or 403 | Log in as `learner`, keep the same session and fetch a new CSRF token after login. |
| 409 | This normalized email already exists in the development database. |
| 400 | Check email format and password length, including its UTF-8 byte length. |
| Cannot log in with the new email | Check the updated [Lesson 10](10-login-with-a-session.md) login instructions and the `dev` profile. |
| Migration fails | Check the PostgreSQL service, project database settings and Flyway output; never delete existing databases to resolve an error. |

## 8. Interview questions

**Why not store encrypted passwords and decrypt them at login?** Authentication only needs verification, so a one-way adaptive password hash avoids maintaining a decryption key capable of recovering all users' passwords.

**Why does the same password produce different hashes?** BCrypt generates a random salt; the salt is stored as part of each encoded hash. Verification uses that stored information.

**What if two requests create the same email simultaneously?** Both can reach the database, but its unique constraint allows only one insert to commit. The other gets a duplicate-key error, translated to HTTP 409.

**Can a hash be safely returned to the browser?** No. Password hashes are sensitive and could be attacked offline. Our response type excludes both the hash and plaintext.

**Is an authenticated development provisioning endpoint a complete registration system?** No. We still need real user authentication, rate limiting, email verification, account recovery, and workspace authorization, among other controls.

## Exercise

Trace one creation request through the controller, service, repository and migration. Explain which layer hashes the password and which layer decides that an email must be unique. Next: **Lesson 10 — authenticate with the stored accounts and establish sessions**.
