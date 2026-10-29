# Lessons 2–7: HTTP, documents and PostgreSQL

These lessons build on Lesson 1. The code has been tested against dedicated development and test databases on your existing local PostgreSQL 18 server. The complete implementation is in the `backend/src` files; the sections below explain each step.

## Lesson 2: Your first HTTP endpoint

An HTTP request has a method (such as `GET`), a path and optional headers or body. A response has a status code and may contain a body. `StatusController` uses `@RestController` to return JSON and `@GetMapping("/api/status")` to associate the method with a URL. Spring converts the returned Java map to JSON. Expected response when the application starts: `{"status":"running"}`. This only says the process answered; it is not a database health guarantee.

**Interview:** Why use GET? It retrieves a representation and should not change application state. What does 200 mean? The server successfully handled this HTTP request, not that all dependencies are healthy.

## Lesson 3: A document and a service

`Document` is a Java record containing a UUID, title and creation timestamp. A record gives us a concise immutable data carrier. `DocumentService` owns the use case; `DocumentController` handles HTTP and `DocumentRepository` handles SQL. Spring creates these objects as beans and injects the repository into the service through its constructor. This keeps HTTP parsing separate from business rules and persistence.

**Interview:** Why a UUID? It provides an identifier that can be generated without a central sequence. It does not grant access or replace authorization. Why dependency injection? Dependencies are explicit and can be replaced in a test.

## Lesson 4: Create and retrieve a document

`POST /api/documents` accepts JSON such as `{"title":"Meeting notes"}`. The service trims the title and creates a UUID. A successful response is `201 Created` with a `Location` header and JSON document. `GET /api/documents/{id}` retrieves it; a missing UUID returns 404. The database records a server-side timestamp.

Start the app in PowerShell from `backend` by loading the local connection settings first:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
mvn spring-boot:run
```

In another PowerShell window:

```powershell
$created = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/documents -ContentType 'application/json' -Body '{"title":"Meeting notes"}'
$created
Invoke-RestMethod -Uri "http://localhost:8080/api/documents/$($created.id)"
```

After restarting the app, run the GET again with the same ID to check persistence. These endpoints are an educational foundation and currently have **no authentication or workspace isolation**. Use them locally only.

**Interview:** Why 201 rather than 200? The request created a resource, and `Location` points to its URL. Why separate request and response types? The client should not choose server-generated fields such as ID or timestamp.

## Lesson 5: Validate inputs and return errors

`@Valid` invokes Jakarta Bean Validation on the request. `@NotBlank` rejects null, empty and whitespace-only titles; `@Size(max=200)` bounds length. `ApiExceptionHandler` maps validation errors, malformed JSON and invalid UUIDs to 400; missing documents return 404. The response contains a short error, not a stack trace. PostgreSQL also has a non-blank check: application validation gives helpful feedback, while the database protects data integrity for other writers.

**Interview:** Why validate in both layers? They protect different boundaries. Can the API trust browser validation? No: a client can bypass the browser. This is only basic input validation; authentication and authorization are future lessons.

## Lesson 6: Connect to local PostgreSQL

PostgreSQL is already running locally. We created two dedicated databases, `collaborative_workspace_dev` and `collaborative_workspace_test`, owned by the project account `collaborative_workspace_app`. Avoid using an existing personal or production database. Flyway applies schema migrations on startup, so configuring the URL to an existing database may change its schema.

Configuration in `backend/src/main/resources/application.properties` reads environment variables `WORKSPACE_DB_URL`, `WORKSPACE_DB_USER`, and `WORKSPACE_DB_PASSWORD`. The local file `backend/.env.local.ps1` sets them and the separate `WORKSPACE_TEST_DB_URL`, `WORKSPACE_TEST_DB_USER`, and `WORKSPACE_TEST_DB_PASSWORD` for the test. It contains a generated password: keep it private and do not commit or share it. `.gitignore` excludes it. Load it with `. .\.env.local.ps1` in each new PowerShell session. This local development setup is not a production secret-management solution.

**Interview:** What is JDBC? Java's API for interacting with relational databases; Spring's `JdbcTemplate` manages much of the resource handling. What is a database connection pool? A managed set of reusable connections. Why not commit passwords? Anyone with repository access could obtain them.

## Lesson 7: Persist documents

Flyway executes the versioned migration `V1__create_documents.sql` to create the documents table. The primary key prevents duplicate IDs; PostgreSQL stores the title and creation timestamp. `DocumentRepository` uses parameterized SQL, passing user data as parameters rather than concatenating it into SQL. `INSERT ... RETURNING` obtains the committed row's fields; retrieval maps a database row back into a `Document`.

Run all tests, including the real PostgreSQL HTTP integration test, from `backend`:

```powershell
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
mvn -B test
```

Without the opt-in variable, the database integration test is skipped. It runs Flyway on the configured test database and deletes only the document it creates; therefore always point it at a dedicated test database. It checks status, creation, retrieval, malformed input, invalid UUID and missing document. Unit tests additionally cover validation and service behavior. The suite passed three tests against the local test database. Separately, a document created in the development database remained available after restarting the application. One demonstration document titled `Lesson 7 persistence check` remains in that project database.

**Interview:** What is a migration? A versioned, repeatable deployment of database schema changes. Why parameterized SQL? It treats input as values rather than SQL syntax and protects against SQL injection. Does a passing unit test prove persistence? No; that requires an integration or end-to-end test using a real database.
