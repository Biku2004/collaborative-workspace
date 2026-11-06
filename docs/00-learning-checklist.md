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

- [x] **Lesson 8 — Spring Security foundation.** See [Lesson 8](lessons/08-spring-security.md). Temporary development login and CSRF protection are verified; persisted identities and workspace authorization remain future lessons.
- [x] **Lesson 9 — Store users and hash passwords.** See [Lesson 9](lessons/09-users-and-passwords.md). Controlled development provisioning is verified; login with stored users is Lesson 10.
- [x] **Lesson 10 — Log in with a session.** See [Lesson 10](lessons/10-login-with-a-session.md). Stored user sessions are tested in the local development profile; workspace authorization remains a later lesson.
- [x] **Lesson 11 — Logout and session lifetime.** See [Lesson 11](lessons/11-logout-and-session-lifetime.md). Logout, CSRF, idle expiry and session isolation are tested.
- [x] **Lesson 12 — Create workspaces.** See [Lesson 12](lessons/12-create-workspaces.md). Atomic workspace/owner creation and membership-scoped lists are tested; document isolation remains future work.
- [x] **Lesson 13 — Membership and role rules.** See [Lesson 13](lessons/13-membership-and-roles.md). Role checks, member visibility and concurrent last-owner protection are tested.
- [x] **Lesson 14 — Workspace-scoped document creation.** See [Lesson 14](lessons/14-workspace-document-creation.md). Owner/editor creation and safe preservation of legacy documents are verified; authorized reads are introduced in Lesson 15.

- [x] **Lesson 15 — Read and list authorized documents.** See [Lesson 15](lessons/15-read-and-list-authorized-documents.md). Members can read workspace documents using keyset pagination; unassigned legacy documents remain inaccessible.

- [x] **Lesson 16 — Rename and archive documents.** See [Lesson 16](lessons/16-rename-and-archive-documents.md). Owner/editor mutations use metadata versions and archived documents are omitted from ordinary lists.

- [ ] Expand authentication and sessions in later lessons.
- [ ] Add workspaces and member roles; test that users cannot access another workspace.
- [ ] Associate documents with workspaces and enforce permissions.
- [x] **Lesson 17 — Two local Yjs replicas.** See [Lesson 17](lessons/17-yjs-two-local-replicas.md). Concurrent changes, reordering, duplicate delivery, deletions and state vectors are tested.
- [x] **Lesson 18 — Prototype a private Yjs document engine.** See [Lesson 18](lessons/18-private-yjs-engine.md). Candidate reconstruction and initial byte transport are tested; the full provider compatibility gate remains open.
- [x] **Lesson 19 — Authenticate a WebSocket.** See [Lesson 19](lessons/19-authenticate-websocket.md). Session, Origin and workspace membership protect a minimal greeting; rooms and edits remain future work.
- [x] **Lesson 20 — Document rooms and bounded messaging.** See [Lesson 20](lessons/20-document-rooms-and-bounded-messaging.md). Temporary test messages are scoped to one document room; durable edits remain later work.
- [x] **Lesson 21 — Commit before acknowledgement.** See [Lesson 21](lessons/21-commit-before-acknowledgement.md). Exact updates, sequence, checkpoint and outbox commit together before SAVED; broadcast and replay remain future lessons.
- [x] **Lesson 22 — Broadcast committed updates and repair gaps.** See [Lesson 22](lessons/22-broadcast-and-repair.md). A single-instance outbox relay, bounded replay and periodic heads are tested.
- [x] **Lesson 23 — Minimal browser application.** See [Lesson 23](lessons/23-minimal-browser-application.md). Stored-user login and authorized paginated lists are browser-tested.
- [x] **Lesson 24 — Plain-text collaborative editor.** See [Lesson 24](lessons/24-plain-text-collaborative-editor.md). Two independent browser contexts converge through the server.
- [x] **Lesson 25 — Offline persistence and a pending queue.** See [Lesson 25](lessons/25-offline-persistence.md). Browser-stored drafts and stable retry IDs are tested; browser storage is not a server durability guarantee.
- [x] **Lesson 26 — Reconnect and reconcile.** See [Lesson 26](lessons/26-reconnect-and-reconcile.md). Automatic reconnect rechecks identity and authorization, replays gaps and retains rejected local work.
- [x] **Lesson 27 — Rich-text editor.** See [Lesson 27](lessons/27-rich-text-design.md). The local learning criteria are verified; the feature remains gated with unresolved security and history limits.
- [x] **Lesson 28 — Collaborator presence.** See [Lesson 28](lessons/28-collaborator-presence.md). Authenticated, bounded ephemeral presence is verified locally; aggregate capacity and multi-instance behavior remain future work.
- [ ] Add checkpoints, named revisions and safe restore in separate lessons.
- [ ] Add search, monitoring, multi-instance communication and failure testing as separate milestones.

The [full roadmap](03-implementation-roadmap.md) gives the dependencies, acceptance tests and later scaling milestones. We will check whether each new service is already available and use Docker for missing services as needed. We will not use Docker for your regular local PostgreSQL development database.
