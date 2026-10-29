# 0. Our one-feature-at-a-time checklist

We will complete and verify each item before moving to the next. Each lesson will explain the concept, problem, design, complete code, commands, expected result, tests, common mistakes and interview questions. A checkmark means completed; application startup is complete, while the document features remain ahead.

## First: prepare and understand the basics

- [x] Check installed development tools. Java 24.0.2, Maven 3.9.8, Node.js 22.17.0, Docker 28.0.1 and PostgreSQL 18 were found. The existing local PostgreSQL server is configured with dedicated project databases.
- [x] **Lesson 1 — Start a Spring Boot application.** Learn what Java, Maven and Spring Boot do. Create and run the smallest useful application. Explain every file and command, and verify that it starts. See [Lesson 1](lessons/01-start-spring-boot.md).
- [x] **Lesson 2 — First HTTP endpoint.** Tested with isolated local PostgreSQL. See [Lessons 2–7](lessons/02-to-07-documents-and-postgresql.md).
- [x] **Lesson 3 — First document.** Added document model and service; the current implementation uses PostgreSQL instead of temporary in-memory storage.
- [x] **Lesson 4 — Create a document.** Valid creation and retrieval tested against isolated local PostgreSQL.
- [x] **Lesson 5 — Validation and error handling.** Blank title and missing document checked; unit tests cover title validation.
- [x] **Lesson 6 — Connect to local PostgreSQL.** Dedicated development and test databases and a project account are configured on the existing local server.
- [x] **Lesson 7 — Persist documents.** Migration, HTTP integration tests and persistence across application restart verified with the dedicated local databases.

## Then: users and collaboration

The detailed numbered sequence, beginning with Lesson 8, is in the [next lessons roadmap](07-next-lessons-roadmap.md). The items below summarize its milestones.

- [ ] Add authentication and sessions, one feature at a time.
- [ ] Add workspaces and member roles; test that users cannot access another workspace.
- [ ] Associate documents with workspaces and enforce permissions.
- [ ] Learn Yjs with a small plain-text collaboration experiment.
- [ ] Add a private Yjs-compatible document engine and test compatibility.
- [ ] Open an authenticated WebSocket and introduce the collaboration protocol.
- [ ] Save and broadcast updates, then add retry, reconnection and offline support in separate lessons.
- [ ] Add the required TipTap rich-text editor, followed by collaborator presence.
- [ ] Add checkpoints, named revisions and safe restore in separate lessons.
- [ ] Add search, monitoring, multi-instance communication and failure testing as separate milestones.

The [full roadmap](03-implementation-roadmap.md) gives the dependencies, acceptance tests and later scaling milestones. We will check whether each new service is already available and use Docker for missing services as needed. We will not use Docker for your regular local PostgreSQL development database.
