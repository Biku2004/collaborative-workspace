# Lesson 13: Membership and role rules

> Current list endpoints default to 20 entries (maximum 100). Send `limit` and `after`; follow the `X-Next-Cursor` response header until absent. See [quality fixes](../09-quality-hardening.md).

## 1. What we built

Workspace owners can now add an existing user, change a member's role, and remove a member. Any current workspace member can list that workspace's members. A workspace must retain at least one `OWNER`: an attempt to remove or demote the final owner returns **409 Conflict**.

This lesson manages **workspace membership only**. At the Lesson 13 checkpoint, documents were not yet linked to workspaces. [Lesson 14](14-workspace-document-creation.md) subsequently adds role-checked document creation and closes unscoped reads; authorized document reads remain future work. Invitations, audit logging, pagination, and production identity are also future work.

## 2. Roles and rules

| Role | List workspace members | Add, change or remove members | Document permissions |
|---|---|---|---|
| OWNER | Yes | Yes, subject to last-owner protection | Not implemented yet |
| EDITOR | Yes | No | Not implemented yet |
| VIEWER | Yes | No | Not implemented yet |
| Nonmember | No | No | Not implemented yet |

An owner can promote another user to `OWNER`. An owner can remove or demote themselves only if another owner remains. A removed user loses membership access on the next request; their entire login session is not terminated by this lesson. Previously received information cannot be recalled.

An email must identify an **existing stored user**. We do not send invitations or email messages. Email input follows Lesson 9's ASCII and lowercase normalization policy. Listing members returns their IDs, emails and roles to other current members; this is the chosen initial visibility policy.

## 3. Why the last-owner rule needs a transaction

Imagine Alice and Bob are the only two owners. At the same moment, each tries to change their own role to `EDITOR`. If both independently count two owners before updating, both might succeed, leaving zero owners.

Our membership **write** transaction follows this order:

```text
1. Resolve the authenticated actor's stored user ID.
2. Lock the workspace row with SELECT ... FOR UPDATE.
3. Recheck the actor's current membership and OWNER role.
4. Look up the target member and current role.
5. If removing/demoting an owner, count owners while the lock is held.
6. Reject if this would remove the last owner; otherwise update membership.
7. Commit and release the workspace lock.
```

Every membership writer must follow the same workspace-first lock convention. PostgreSQL makes the second writer wait for the first transaction; after the lock is acquired, we read the current role and owner count again. Our transactions explicitly use `READ_COMMITTED`, so the later queries see the committed state after waiting. A database lock only protects code paths that participate in this convention; direct database writes or future code that bypasses it can violate the rule. [PostgreSQL row-lock documentation](https://www.postgresql.org/docs/current/explicit-locking.html)

For member listing, authorization and the member rows are selected in **one SQL statement**: it joins through the requesting user's membership. That avoids a separate check followed by an unrelated read. A response already in flight when a membership is revoked cannot be recalled.

## 4. Files and responsibilities

Open these complete source files in `backend/src/main/java/com/example/collaborativeworkspace/`:

1. `WorkspaceRole.java` — the `OWNER`, `EDITOR`, and `VIEWER` values.
2. `WorkspaceMember.java` — a safe response containing a user ID, email, and workspace role; it contains no password hash.
3. `ChangeWorkspaceMemberRequest.java` — validates the target email and requires a valid role. Client input identifies the **target**, never the authenticated actor.
4. `WorkspaceMembershipController.java` — exposes the three endpoints, obtains the actor from the authenticated session, validates input, and returns no-store responses.
5. `WorkspaceMembershipService.java` — owns authorization, transactions, lock ordering, and the last-owner invariant. It checks membership and role **after** acquiring the workspace lock for each write.
6. `WorkspaceRepository.java` — uses parameterized queries to lock a workspace, read roles, count owners, insert/update or remove memberships, and list members through the actor's membership.
7. `ApiExceptionHandler.java` — maps inaccessible or missing workspace/member resources to 404 and the last-owner conflict to 409. A member without owner authority receives 403.
8. `WorkspaceIntegrationTest.java` — exercises role restrictions, cross-workspace access, removal, input checks, and competing owner changes against PostgreSQL.

The existing migration `V3__create_workspaces.sql` already provided the membership table, foreign keys, a composite primary key and the role-value check. No new migration was required for this lesson. The database check limits role values; the application transaction enforces the last-owner rule for its own write paths.

## 5. API contracts

All routes use a stored-user session in the `dev` profile. Mutations require a CSRF token. A nonmember or an inaccessible workspace gets a generic **404**, avoiding a separate response that confirms the workspace exists. An `EDITOR` or `VIEWER` who is a member but tries to manage membership gets **403**.

### List members

```http
GET /api/workspaces/{workspaceId}/members
```

A current member receives 200 and an array such as:

```json
[{"userId":"<uuid>","email":"alice@example.com","role":"OWNER"}]
```

### Add a member or set their role

```http
POST /api/workspaces/{workspaceId}/members
Content-Type: application/json
X-CSRF-TOKEN: <token>

{"email":"bob@example.com","role":"EDITOR"}
```

Only an owner may call this. The operation creates a membership if absent or updates its role if present; both outcomes return **200** and the member representation. Unknown users return 404 after the caller is authorized. Invalid input returns 400. Demoting the final owner returns 409.

### Remove a member

```http
DELETE /api/workspaces/{workspaceId}/members/{userId}
X-CSRF-TOKEN: <token>
```

Only an owner may call this. Success returns **204 No Content**. A missing member returns 404; removing the last owner returns 409. A retry after a successful removal therefore returns 404, so a client that loses the first response must reconcile the result rather than assume failure.

## 6. Try it in PowerShell

Start the local development server as described in [Lesson 12](12-create-workspaces.md). Create at least two stored users through the `learner` bootstrap flow in [Lesson 9](09-users-and-passwords.md). Sign in as the stored user who owns a workspace using the Lesson 12 PowerShell steps; keep the resulting `$session`, `$base`, and `$headers` variables. Then:

```powershell
$workspaces = Invoke-RestMethod "$base/api/workspaces" -WebSession $session
$workspaceId = $workspaces[0].id
$payload = @{ email = 'bob@example.com'; role = 'EDITOR' } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post "$base/api/workspaces/$workspaceId/members" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $payload
$members = Invoke-RestMethod "$base/api/workspaces/$workspaceId/members" -WebSession $session
$members
```

Replace `bob@example.com` with the actual email of another **existing** stored user and select the correct workspace if you own more than one. The command changes real development data. To remove that specific member, obtain their `userId` from `$members`, then:

```powershell
$memberId = ($members | Where-Object email -eq 'bob@example.com').userId
Invoke-WebRequest -Method Delete "$base/api/workspaces/$workspaceId/members/$memberId" -WebSession $session -Headers $headers
```

Use a fresh CSRF token after login, as shown in Lesson 12. Do not remove an owner as an experiment unless you intend to change the real membership; the server rejects removal of the last one.

## 7. Verify

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
mvn -B test
```

The full suite passed **11 tests, 0 failures**, against the separate PostgreSQL test database. The membership tests check anonymous and nonmember rejection, editor/viewer restrictions, CSRF, invalid role and unknown user, role changes, member removal, and last-owner rejection.

The concurrency test creates two owners and starts two self-demotions. A test barrier brings both requests to the workspace-lock boundary before proceeding. One change succeeds, the other receives 409, and a database query confirms that exactly one owner remains. This tests two overlapping requests through the real application and PostgreSQL; it is not a proof covering direct database edits or future writers that ignore the lock convention. The test removes its own fixtures. Database integration tests are skipped if `WORKSPACE_RUN_DB_TESTS` is not `true`.

## 8. Interview questions

**Why isn't `SELECT count(*)` followed by an update enough?** Two transactions can both observe the same count before either updates. The workspace lock serializes cooperating writers, and each checks the count after it obtains the lock.

**Why recheck the actor's role after taking the lock?** Another owner may have revoked or demoted the actor while their request waited. An earlier check could be stale.

**Why return 404 to a nonmember but 403 to a viewer?** A nonmember should not learn whether a particular workspace exists. A current member can already see it but lacks authority to modify membership.

**Does a role in the database automatically protect documents?** No. Every document read and write must explicitly enforce workspace membership and the relevant role. That implementation comes in Lessons 14–15.

**Does this guarantee that an owner can never disappear?** It protects the implemented application membership-write paths. Direct SQL, future account-deletion workflows and new write paths must preserve the same invariant and lock ordering.

## 9. Exercise and next step

Add a second owner, demote the first owner, and confirm the remaining owner is still listed. Explain what the workspace lock protects. Next, Lesson 14 will associate newly created documents with workspaces and carefully handle documents that were created before workspaces existed.
