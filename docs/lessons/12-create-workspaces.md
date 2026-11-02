# Lesson 12: Create workspaces

> Current list endpoints default to 20 entries (maximum 100). Send `limit` and `after`; follow the `X-Next-Cursor` response header until absent. See [quality fixes](../09-quality-hardening.md).

## 1. What are we building?

A **workspace** is the container that will eventually hold a team's documents and members. In this lesson, a stored user can create a workspace and list the workspaces in which they are a member. The creator becomes its first **OWNER**.

At the Lesson 12 checkpoint, membership management and workspace-scoped documents were not yet implemented. [Lesson 13](13-membership-and-roles.md) adds membership management; [Lesson 14](14-workspace-document-creation.md) adds scoped document creation and closes legacy unscoped reads. Authorized document reads and lists remain a later lesson. Do not deploy this learning app as a multi-tenant product.

## 2. The problem, with an example

Alice creates `Product team`. The backend must save both:

1. A workspace named `Product team`.
2. A membership saying Alice is its `OWNER`.

If the second operation fails, the first must not remain by itself. Otherwise, we could create a workspace nobody can manage. A **database transaction** groups both writes: they commit together, or both are rolled back.

Bob should not see Alice's workspace in his list unless he is a member. We do not ask the browser which user ID to list: we derive the identity from the authenticated session.

```text
Browser: authenticated session + CSRF token
   │ POST /api/workspaces { "name": "Product team" }
   ▼
WorkspaceController: validate input and read the authenticated identity
   ▼
WorkspaceService: begin transaction; resolve the stored user's ID
   ├─ insert workspace
   └─ insert OWNER membership
   ▼
PostgreSQL: commit both writes
   ▼
201 response with workspace ID, name, creation time and OWNER role
```

## 3. New concepts

### Workspace, membership and role

A workspace is a team container. A membership connects one user to one workspace and records a role. The database supports `OWNER`, `EDITOR`, and `VIEWER`, but **only owner creation is implemented now**. Lesson 13 will define membership management and enforce the role rules.

### Atomicity and `@Transactional`

Atomicity means the database commits a group of changes as one unit or rolls the group back. The `create` method is marked `@Transactional`; the workspace and membership repositories use the same database transaction. If the owner insert throws a runtime exception, Spring's transaction handling rolls back the earlier workspace insert. The behavior depends on calling the Spring-managed service through its proxy; merely placing an annotation on an arbitrary call does not prove it runs transactionally. [Spring's transaction documentation](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)

### Authentication and authorization

Authentication establishes the signed-in identity. The `USER` role gate permits stored users to access workspace endpoints; the temporary `learner` bootstrap account cannot create or list workspaces. The service resolves the signed-in email to its database user ID. A user ID supplied in JSON is never used to choose the owner.

`GET /api/workspaces` joins `workspace_members` and filters on the signed-in user's ID. This is a membership-scoped list, not yet a complete workspace authorization system. The current list is unpaginated; pagination and limits remain future work.

## 4. Files and responsibilities

The complete code lives in these project files:

1. `backend/src/main/resources/db/migration/V3__create_workspaces.sql` creates `workspaces` and `workspace_members`. Foreign keys connect the tables to stored users. A composite primary key prevents duplicate memberships; a role check restricts stored role values. An index supports listing by user. Flyway applies the migration when the application starts.
2. `CreateWorkspaceRequest.java` validates the workspace name: nonblank, at most 120 Java UTF-16 code units, and no NUL character. The service strips leading and trailing whitespace.
3. `WorkspaceController.java` implements `POST /api/workspaces` and `GET /api/workspaces`. It obtains the identity from Spring Security's `Authentication` object. Responses are marked `Cache-Control: no-store`. There is no individual workspace GET endpoint yet, so creation returns 201 and a body without a `Location` header.
4. `WorkspaceService.java` defines the transaction boundary and calls the repositories. It looks up the current user, inserts the workspace, then inserts the owner membership. Missing stored users are denied rather than treated as another user.
5. `WorkspaceRepository.java` uses parameterized SQL for both writes and the member-filtered list. `UserRepository.java` adds a parameterized lookup from authenticated email to the stored UUID.
6. `SecurityConfiguration.java` restricts workspace routes to authenticated stored users with `ROLE_USER`; state-changing requests still require CSRF.
7. `WorkspaceIntegrationTest.java` tests HTTP behavior and transaction rollback using the separate local PostgreSQL test database.

The database records `created_by` as well as the membership. At creation, the service writes the same user ID into both. The current lesson's invariant is **atomic creation with an owner**; preventing removal of the last owner across concurrent membership changes belongs to Lesson 13. A foreign key or role check alone does not enforce that future rule.

## 5. API contract

### Create a workspace

```http
POST /api/workspaces
Content-Type: application/json
X-CSRF-TOKEN: <token>

{"name":"Product team"}
```

With an authenticated stored-user session and valid token, the response is `201 Created`:

```json
{"id":"<uuid>","name":"Product team","createdAt":"<timestamp>","role":"OWNER"}
```

Invalid input returns 400. A POST without a valid CSRF token returns 403. Anonymous requests cannot create workspaces, and the temporary `learner` bootstrap account is denied workspace access. The exact status for a denied request depends on whether CSRF or authorization rejects it first.

### List my workspaces

```http
GET /api/workspaces
```

The response is a JSON array of only the workspaces where the authenticated stored user has a membership, with that user's role. A user with none receives `[]`. Anonymous requests are rejected. There is no `GET /api/workspaces/{id}` in this lesson.

## 6. Try it locally

Start the server using the development profile:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:WORKSPACE_DEV_PASSWORD = Read-Host -MaskInput 'Choose a temporary learner password'
mvn spring-boot:run
```

On the first start after this change, Flyway applies migration V3 to the configured development database. If you do not have a stored user yet, create one using the `learner` bootstrap flow in [Lesson 9](09-users-and-passwords.md). Then, in a **second PowerShell 7 window**, log in as that stored user—not as `learner`:

```powershell
$email = Read-Host 'Stored user email'
$password = Read-Host -MaskInput 'Stored user password'
$base = 'http://127.0.0.1:8080'
$csrf = Invoke-RestMethod "$base/api/csrf" -SessionVariable session
$login = @{ username = $email; password = $password; _csrf = $csrf.token }
Invoke-WebRequest -Method Post "$base/login" -WebSession $session -Body $login -SkipHttpErrorCheck | Out-Null
Invoke-RestMethod "$base/api/me" -WebSession $session
$csrf = Invoke-RestMethod "$base/api/csrf" -WebSession $session
$headers = @{ $csrf.headerName = $csrf.token }
$body = @{ name = 'Product team' } | ConvertTo-Json -Compress
Invoke-RestMethod -Method Post "$base/api/workspaces" -WebSession $session -Headers $headers -ContentType 'application/json' -Body $body
Invoke-RestMethod "$base/api/workspaces" -WebSession $session
Remove-Variable password,login
```

The `/api/me` call verifies that login succeeded before creation. Spring's login can redirect to the unimplemented home page, so the example permits that final 404; if authentication failed, `/api/me` rejects the request. This example creates a real workspace in the dedicated development database. To observe isolation, use two independently signed-in stored users and compare their workspace lists. Stop the application with Ctrl+C.

## 7. Verify

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
mvn -B test
```

The full suite passed **9 tests, 0 failures**, using the separate PostgreSQL test database. The workspace integration tests verify anonymous and bootstrap denial, CSRF enforcement, input validation, server-derived ownership even when a client supplies a different `createdBy`, the owner row, and isolated lists for two users.

The rollback test uses a test spy to make the membership insert throw after the real workspace insert. It then queries PostgreSQL and confirms neither row committed. This tests the actual Spring transaction boundary; it is not merely a mocked repository assertion. The tests remove only their own fixture data. Without `WORKSPACE_RUN_DB_TESTS=true`, database integration tests are skipped.

## 8. Common questions

**Why not insert the workspace and owner in separate transactions?** A failure between commits could leave an ownerless workspace. One transaction makes the two writes succeed or fail together.

**Why not trust the `createdBy` field in the request?** A client can change it. We derive the user from the server's authenticated session, then resolve the database user ID.

**Why use a foreign key and a primary key?** Foreign keys prevent memberships referring to nonexistent users or workspaces. The composite membership key prevents duplicate rows for the same user and workspace. These constraints do not replace authorization checks.

**What if two users choose the same workspace name?** The current product policy permits it; workspaces have UUIDs as identities. A unique-name rule would need an explicit scope and a database constraint.

**Are documents now private to workspaces?** No. Documents have not been migrated or associated with workspaces yet. The old document endpoints remain shared among authenticated development users. Lessons 14–15 address that separately.

## 9. Exercise and next step

Create a workspace as one stored user and list workspaces as another. Explain why the creator is an owner even if the request contains another user's ID. Next, Lesson 13 adds membership and role management, including protecting the last owner.
