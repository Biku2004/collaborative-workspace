# Collaborative Workspace — learning and implementation plan

Prepared 29 October 2026. Status: Lessons 1–9 are implemented and verified locally. A temporary development login protects the document API, and user accounts can be provisioned with hashed passwords. Database-backed login, workspace permissions and collaboration are later milestones.

We will assume no prior experience with Spring and explain Java concepts whenever they are needed. We will implement one feature per lesson and verify it before moving on. The goal is a project you can build, debug, and defend in an interview.

The supplied attachment is the project brief. We will provide the beginner-friendly teaching format described below.

## Read in this order

| Guide | What it gives you |
|---|---|
| [0. One-feature-at-a-time checklist](docs/00-learning-checklist.md) | What we do first, what comes next, and the local PostgreSQL setup |
| [Next lessons: 8–37](docs/07-next-lessons-roadmap.md) | One-feature lessons from authentication through rich-text collaboration and operations |
| [Lesson 8: Spring Security](docs/lessons/08-spring-security.md) | Temporary local login, authorization checks, CSRF protection, and how to run and test them |
| [Lesson 9: Users and password hashes](docs/lessons/09-users-and-passwords.md) | Development-only user provisioning, password hashing, validation and database uniqueness |
| [Current API endpoints](docs/08-api-endpoints.md) | Methods, routes, access requirements and expected responses through Lesson 9 |
| [1. Architecture and foundations](docs/01-architecture.md) | Expanded product idea, concepts, recommended architecture, alternatives, and corrections to the brief |
| [2. Data, protocol, and algorithms](docs/02-data-and-protocol.md) | Tables, permissions, API contracts, edit/reconnect/restore flows, and transaction boundaries |
| [3. Feature-by-feature implementation](docs/03-implementation-roadmap.md) | Ordered lessons, Spring topics, files to build, acceptance tests, and stopping points |
| [4. Interview questions and explained answers](docs/04-interview-handbook.md) | Questions from introductory to principal-engineer level, with reasoning and follow-ups |
| [5. Failure tests and operations](docs/05-verification-and-operations.md) | Failure matrix, security tests, SLOs, capacity estimates, deployment, and recovery |
| [6. Research and decision register](docs/06-sources-and-decisions.md) | Primary-source reading list, evidence boundaries, and architecture decisions |

## The project we will actually build

A shared rich-text document workspace where members can edit concurrently, work temporarily offline, reconnect without silently overwriting each other's changes, see collaborators, save named revisions, and restore a revision safely. Owners control membership; viewers cannot write. A browser client demonstrates the Java backend.

The first complete version uses:

- Java 24 and Spring Boot 4.1.2 for the initial lessons, matching the installed JDK; revisit the Java version as the project evolves.
- Spring MVC, Spring WebSocket, Spring Security, explicit SQL through Spring JDBC, and PostgreSQL.
- TypeScript, Yjs, and a TipTap/ProseMirror rich-text editor, following an initial plain-text learning experiment.
- A small private Node/Yjs document engine for binary validation, document reconstruction, and checkpoints.
- PostgreSQL as the authoritative store and initial durability boundary.

Development uses the existing local PostgreSQL installation with separate project and test databases. Check whether another service is already available before adding it, and use Docker for missing services when needed. Local development and test data must stay separate; isolated Docker-based integration tests remain an optional later choice.

The Java application owns authorization, room transport, durable acceptance, history, and business rules. The Node process is a document-processing dependency; it is not an independent authority over permissions or committed edits.

Redis and a second Java instance are later lessons. Kafka is an optional, justified extension for downstream processing. Object storage follows working database-backed checkpoints. Rich text is a core milestone, not an optional extension.

## Why this can stand out

The differentiator is evidence: demonstrate an edit surviving a gateway crash, recovery after a missed broadcast, an old offline client being blocked after a restore, and permission revocation racing safely with a write. Include reproducible measurements and explain what your system does when a dependency fails.

A long technology list is not proof of engineering quality. Each added component must solve a demonstrated problem and have a failure test.

## How each coding lesson will work

1. Explain the user problem with a small example.
2. Introduce only the Java/Spring/database concepts required for that feature.
3. Draw the request or message flow and state its invariant.
4. Compare the simplest reasonable alternatives and choose one.
5. Show the exact files and complete code for this feature, with explanations of unfamiliar annotations and APIs.
6. Provide Windows PowerShell commands and expected output.
7. Run meaningful tests, including a failure or negative case.
8. Explain likely errors, interview questions, and trade-offs.
9. End with a working checkpoint and a small exercise. The following feature begins in the next lesson.

We will not start with all modules or services pre-generated. [Lesson 1](docs/lessons/01-start-spring-boot.md) starts a Spring Boot application. The next lessons introduce an HTTP endpoint, then a document model and deliberately temporary in-memory storage.

## Important boundaries

The architecture beyond the initial security and document API is a researched proposal, and the system has not been benchmarked. Numerical limits are starting hypotheses. A finite question bank cannot contain every possible interview question; the handbook teaches the reasoning needed to answer unfamiliar ones.

The Yjs engine boundary and custom provider require an early compatibility experiment before production claims. A simpler alternative is a conventional Node/Yjs collaboration server with Java handling the business API; we retain that fallback if the Java transport experiment costs more than its learning value.
