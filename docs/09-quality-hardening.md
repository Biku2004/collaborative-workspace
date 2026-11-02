# Code quality fixes after Lesson 16

This checkpoint addresses the five findings in the code review, in order. The application remains a local-development learning project. These changes strengthen the implemented APIs; production account lifecycle, deployment, capacity testing, monitoring and recovery are still later work.

## 1. Reject fractional versions

`application.properties` disables Jackson's floating-point-to-integer coercion. Values such as `0.9`, `-0.9` and `0.0` cannot silently become an integer metadata version. Rename and archive return 400; negative, missing and null versions are also rejected. Existing integer-version success and 409 conflict behavior remain tested. The tests query the row after invalid requests and confirm its title, version and archived flag have not changed.

## 2. Verify database isolation before tests

Every database integration test and migration test calls `TestDatabaseGuard.verifiedUrl()` before application startup, Flyway or fixture writes. It opens read-only connections, reads `current_database()` and requires the explicitly designated `collaborative_workspace_test` database. It rejects a development connection reporting that same database name, including when JDBC URL spelling differs. Missing settings or failed identification stop the tests. Equal database names on different servers are conservatively rejected too.

`TestDatabaseGuardTest` uses the real local database with `127.0.0.1` and `localhost` URLs and verifies that using them as test/development targets is rejected. The guard performs SELECTs only. This is a guard against mistaken configuration, not protection from an administrator deliberately relabeling databases or changing code.

## 3. Use one API error contract

API errors now contain `code` and `message`, and are marked `Cache-Control: no-store`. `ApiExceptionHandler` handles business and MVC failures; Spring Security uses the same shape for API authentication, CSRF and access denial. The default profile remains closed. Browser login and logout retain their HTML/redirect behavior.

| Scenario | Status | Code |
|---|---|---|
| Validation, malformed JSON, bad path/query value | 400 | `INVALID_REQUEST` |
| No API session in development | 401 | `AUTHENTICATION_REQUIRED` |
| Access denied or CSRF rejected | 403 | `ACCESS_DENIED` |
| Missing or inaccessible resource | 404 | `NOT_FOUND` |
| Stale version or archived document | 409 | `DOCUMENT_CONFLICT` |
| Removing/demoting last owner | 409 | `LAST_OWNER` |
| Duplicate email | 409 | `DUPLICATE_USER` |
| Reused creation key with different input | 409 | `IDEMPOTENCY_CONFLICT` |
| Database connection failure, query timeout or lock acquisition failure | 503 | `SERVICE_UNAVAILABLE` |
| Unexpected internal error | 500 | `INTERNAL_ERROR` |

Other framework HTTP errors keep their status and protocol headers (for example, the Allow header on 405) and use the same body shape. Client responses do not include exception text, SQL, stack traces or credentials. Unexpected failures log the exception class; richer correlation and operational diagnostics remain future work. Failure-injection tests exercise resource failure, query timeout and unexpected exceptions through real HTTP requests. They simulate repository failures; they do not stop the PostgreSQL service.

## 4. Make creation retries safe when a key is supplied

Workspace and document creation accept `Idempotency-Key`, containing 1–128 ASCII letters, digits, underscores or hyphens. The key is scoped by authenticated stored user and operation; document operations also include the workspace ID. Send the same key and normalized input whenever retrying the same intended creation. Different input with the same key returns 409. Requests without a key retain ordinary create-on-each-request semantics.

V7 adds `creation_requests`, with a composite primary key. The request record, resource and serialized original response commit in one transaction. An insert conflict and row lock order concurrent retries; a rollback removes the pending record along with the failed creation. A successful retry returns the original 201 response snapshot, including its original metadata values, rather than running creation again. GET the resource to inspect later changes. Current document-editing membership is checked before reading a saved response. Replaying a historical workspace response grants no membership permissions.

`IdempotencyService` requires an existing transaction. Tests cover concurrent workspace and document requests, response loss followed by replay, changed input, permission removal and rollback. Keys and response snapshots are currently retained until the associated user is deleted. There is no automatic expiry: adding one later must explicitly define the retry guarantee because deleting a key allows a later request to create again. Request quotas and retention limits should be designed before public deployment.

Example using the authenticated PowerShell setup from Lesson 12:

```powershell
$headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
$body = @{ name = 'Product team' } | ConvertTo-Json -Compress
$workspace = Invoke-RestMethod -Method Post "$base/api/workspaces" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $body
# Repeat with the SAME body and key after an ambiguous response.
$replayed = Invoke-RestMethod -Method Post "$base/api/workspaces" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $body
$workspace.id -eq $replayed.id
# Use a new key for a different intended creation.
$headers.Remove('Idempotency-Key')
```

## 5. Bound lists and measure workspace lock contention

Workspace and member lists still return JSON arrays. They now accept `limit` (default 20, maximum 100) and optional UUID `after`. They order by workspace ID or member user ID. When more entries exist, `X-Next-Cursor` gives the position to send as `after`; absence means the end. This ordering replaces the earlier timestamp/email ordering. A valid cursor past the end gives an empty array to a current member; nonmembers still receive 404. These are live pages rather than a frozen snapshot. UUID positions do not grant access; each request checks membership.

```powershell
$response = Invoke-WebRequest "$base/api/workspaces?limit=1" -WebSession $session
$response.Content | ConvertFrom-Json
$cursor = $response.Headers['X-Next-Cursor'] | Select-Object -First 1
if ($cursor) {
    Invoke-RestMethod "$base/api/workspaces?limit=1&after=$cursor" -WebSession $session
}
```

The controlled lock test holds one workspace lock, confirms a metadata update cannot finish within 250 ms, verifies a write in another workspace succeeds, then releases the lock and verifies the waiting update commits. The observed wait in the verification run was 269 ms. This deliberately induced wait demonstrates contention and its workspace boundary; it is not an estimate of production latency, throughput or p99 performance. The current workspace lock and permission rechecks remain intact, and the existing concurrent last-owner and revocation tests still run. A finer-grained design should follow representative load measurements and preserve those invariants.

## Verification and rollout

From `backend` in PowerShell:

```powershell
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
mvn -B test
```

The complete verification run passed 21 tests with zero failures, errors or skips. All existing migrations remain unchanged. V7 is applied to the dedicated test database during verification and will apply to development at the next application startup. Back out the application changes if needed while leaving the additive V7 table in place; old application versions will not honor idempotency or the new error/list contracts, so do not mix versions behind a shared public endpoint without a rollout plan. Update clients to follow pagination and consume `code`/`message` rather than the former error format. No production migrations, load tests or dependency vulnerability scan were performed for this checkpoint.
