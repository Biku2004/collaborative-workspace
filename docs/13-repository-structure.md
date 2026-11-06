# Repository structure

Production code is grouped by feature. Controllers, services, repositories and request/model types live beside the feature they belong to; their class names identify their responsibility. Shared infrastructure has explicit packages instead of sharing one application-wide package.

```text
backend/src/main/java/com/example/collaborativeworkspace/
├── CollaborativeWorkspaceApplication.java   Spring Boot entry point
├── auth/                                   Login, sessions, CSRF, users and password handling
├── workspaces/                             Workspaces, memberships, roles and access-change events
├── documents/                              Document metadata, creation, listing, rename and archive
│   └── history/                            Checkpoints, named revisions and epoch restore
├── collaboration/
│   ├── engine/                             Private Yjs client, schema contract and materialization
│   ├── updates/                            Durable update acceptance, stream state and update log
│   └── transport/                          WebSockets, rooms, presence, relay and session registry
├── search/                                 Search projection, query API and indexing jobs
└── common/
    ├── api/                                Shared API errors and status endpoint
    ├── crypto/                             Shared content checksum primitive
    ├── idempotency/                        Idempotent request handling
    └── pagination/                         Shared bounded-page support

backend/src/main/resources/
├── application*.properties                 Runtime configuration
└── db/migration/                           Ordered Flyway SQL migrations

backend/src/test/java/com/example/collaborativeworkspace/
├── auth/, documents/, workspaces/           Feature tests
├── collaboration/, search/                 Collaboration and search tests
├── integration/                            Browser, migration and application-wide checks
└── support/                                Dedicated test-database safety guard

frontend/src/
├── main.ts                                 Browser entry point
├── app/                                    App shell, navigation, login view and global styling
├── lib/api.ts                              Same-origin HTTP client and API types
├── features/
│   ├── editor/                             Editor screen and document synchronization provider
│   │   └── rich/                           Rich-text surface, presence and error boundary
│   ├── history/                            Named revisions and revision preview
│   └── offline/                            IndexedDB, local navigation hints and retained drafts
└── shared/yjs/                             Shared snapshot verification

frontend/test/                              Playwright scenarios and isolated editor fixtures
```

Keep the Java application entry point above the feature packages so Spring discovers their components. Java tests that exercise package-private internals remain in the corresponding feature package. Application-wide tests live in `integration`, and shared test-only helpers live in `support`.

When adding a feature, start in its feature directory. A document-history controller belongs in `documents/history`; a search job belongs in `search`. Put code in `common` or frontend `shared` only when it has an actual cross-feature purpose. Avoid generic application-wide `controllers`, `services` and `utils` dumping grounds.

Existing historical lesson snippets may show the original flat Java package. The source files and this guide describe the current layout. Concrete source-path references in the lesson documents have been updated.

The Lesson 33 search implementation remains in progress. Its presence under `search` does not mark that lesson complete.
