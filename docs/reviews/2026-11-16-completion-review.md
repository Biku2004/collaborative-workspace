# Project completion review — 2026-11-16

Reviewed commit: `8c552cb`. Scope: product completeness and release readiness across the browser, API, collaboration engine, persistence, deployment and recovery. This is a full principal-engineer readiness review based on current source and recorded verification. No application code was changed and no new runtime/security scan was performed for this review.

## Scores and verdict

These are qualitative engineering judgments, not percentages of requirements completed or security certifications.

| Perspective | Score / 10 | Evidence |
|---|---:|---|
| Principal engineer | 7 | Clear authority boundaries, transactional acceptance and durable replay; core rich-text/product acceptance remains open |
| Senior software engineer | 7 | Strict inputs, idempotency, isolation and broad failure tests; several backend operations lack browser flows |
| Security | 6 | Server-side membership/role checks, CSRF, bounded inputs and default-off rich gate; rich metadata provenance remains unresolved and deployment is dev-only |
| Reliability | 6 | Outbox/HEAD repair, overload cleanup and actual recovery drill; combined-load failure and local session state limit availability claims |
| Maintainability | 7 | Feature directories and extensive teaching/runbook documentation; custom provider and version-sensitive Yjs internals increase change risk |
| Production readiness | 3 | No supported non-dev product configuration, complete onboarding flow, automatic off-machine backup operation or demonstrated representative capacity |

Regulatory compliance is not scored: no jurisdiction, data classification or regulatory requirements were supplied. Email addresses, document contents and password hashes require appropriate privacy controls; the dump scripts do not encrypt archives.

**Verdict:** the 37-lesson local learning roadmap is completed. A usable pre-provisioned collaboration prototype exists. The promised self-service rich-text workspace product is not complete, and an Internet-facing production release is not ready. Checked lesson boxes do not prove product acceptance.

## Intent and invariants checked

The README describes a shared rich-text workspace: owners manage membership; users create and edit documents, work offline, reconnect, see collaborators and inspect/restore history. For that product:

- A normal supported launch must allow a permitted user to create at least one supported document format through the UI.
- An owner must be able to create a workspace and manage collaborators without a developer executing private setup/API commands.
- Access and accepted-update authority must stay in Java/PostgreSQL, including after queueing, retry and membership changes.
- `SAVED` must follow the durable transaction; a speculative engine result must not be treated as committed.
- Missed/duplicate notifications must be repairable without treating Redis as the durable log.
- Feature availability, ordinary editing limits, deployment and backup claims must match demonstrated behavior.

The inspected write path preserves the strongest existing guarantees: `DocumentUpdateService` validates a candidate, then locks/rechecks membership, document state and head inside a transaction before saving the update, checkpoint and outbox. Its transaction returns before acknowledgment. Recipient authorization is checked on delivery; the relay marker is not a receipt. These are substantial strengths, but they do not implement the missing user flows below.

## Findings

Priority is relative to the stated release target. P0 below means a blocker for declaring the ordinary user-facing product complete, not a confirmed critical security vulnerability.

### C1 — P0 / High: the normal UI cannot create a document

- **Category/status:** product correctness; confirmed by the source path. No new runtime reproduction in this review.
- **Files:** `frontend/src/app/App.tsx:150` calls `createRichDocument` at line 160; the only creation form is at line 214. `frontend/src/lib/api.ts:64` always sends `RICH_TEXT_V1`. `ops/deploy/start-local.ps1:14` forces rich off. `CollaborationFormatPolicy.java:13` defaults it off. `WorkspaceDocumentController.java:34` rejects unsupported formats, mapped to HTTP 400 by `ApiExceptionHandler.java:68`.
- **Current/expected:** an owner sees a creation form that submits a disabled format. The supported default product should create plain text successfully and clearly identify rich text as unavailable/experimental.
- **Failure scenario/impact:** a provisioned owner with a workspace but no documents enters a title and gets a generic error. Existing-document editing does not repair this new-user dead end.
- **Root cause:** the frontend creation path was switched to rich text while the backend/local release retained the safe default-off gate; no default-configuration creation acceptance test joins those paths.
- **Fix/example:** preserve the rich gate and add a supported creation function. Example request body: `JSON.stringify({ title, format: 'PLAIN_TEXT_V1' })`. The function must retain the current CSRF header, idempotency key and checked response handling. Rich availability should come from an explicit capability contract, not an assumed UI default.
- **Required test/validation:** launch the packaged app with rich disabled; an owner creates, opens and saves a plain document, then a second member reads it. Check viewer denial, retry-key reuse and clear disabled-rich messaging.
- **Regression/rollout:** do not change existing document formats or reuse the same idempotency key across changed format inputs. No database migration is needed; revert the new UI/API wrapper if necessary.
- **Security/privacy:** no exploit is claimed. Do not solve the mismatch by enabling an unvalidated feature globally.

### C2 — P0 / High: owner administration is available in APIs, not the browser

- **Category/status:** product completeness; confirmed static inventory of the application shell and API client.
- **Files:** `frontend/src/app/App.tsx:203` shows “No workspaces yet” without a creation action. `frontend/src/lib/api.ts` has listing and rich creation but no workspace creation, membership-management, rename or archive wrappers. Corresponding controller mutations exist in `WorkspaceController`, `WorkspaceMembershipController` and `WorkspaceDocumentController`.
- **Current/expected:** accounts are provisioned through the development setup; workspace/membership/document metadata administration requires API work. A finished owner workflow needs usable controls for the promised management actions. Public signup is a separate product choice, not automatically required.
- **Failure scenario/impact:** a fresh stored user signs in to an empty workspace list and cannot create a team; an owner cannot add a colleague or rename/archive a document in the browser.
- **Root cause:** browser lessons delivered a thin collaboration client rather than parity with backend administrative capabilities.
- **Fix/example contract:** add typed client actions such as `createWorkspace(name): Promise<Workspace>` and `setMemberRole(workspaceId, email, role): Promise<void>`, with the existing checked/CSRF pattern. Add membership and document metadata views; use current version fields for rename/archive.
- **Required tests/validation:** new owner creates a workspace, adds an existing editor/viewer, edits concurrently, changes/removes a role, renames and archives a document, and observes conflicts/denial correctly. A normal member must not gain owner actions by changing IDs or client state.
- **Regression/rollout:** preserve last-owner checks and backend authorization. Ship each view independently; roll back UI controls without schema changes. Do not publish bootstrap-only user provisioning as ordinary registration.
- **Security/privacy:** API authorization is still the authority. No authorization bypass was established; this finding is absent functionality.

### C3 — P0 / High: rich text is a core promise with an unresolved release gate

- **Category/status:** core-feature acceptance and security-validation gap; disabled state is confirmed. Distinct exploit impact from the historical metadata candidate remains unverified.
- **Files:** `README.md:57` describes the rich-text product; `CollaborationFormatPolicy.java` and `start-local.ps1` disable it; `docs/lessons/27-rich-text-design.md:9`, `:38` and `:133` document metadata provenance, internal APIs and bounded history.
- **Current/expected:** rich text works in gated experiments but is not approved for ordinary enablement. A complete rich-text product needs a justified validation/history design and release criteria, or an explicit product decision to ship a plain-text first version.
- **Failure scenario/impact:** ordinary formatting can grow retained history toward the configured record/checkpoint bounds; well-shaped metadata has not been proved trustworthy merely by shape checks. Passing the 80-edit example does not prove indefinite editing or adversarial safety.
- **Root cause:** custom provider/binding integration relies on selected Yjs internals and finite position-history metadata without a completed long-lived history/provenance solution.
- **Fix/example policy:** retain `workspace.rich.enabled=false` until the compatibility, history-capacity and security gates have evidence. Decide and document the supported feature contract before implementing history redesign; increasing constants alone is insufficient.
- **Required tests/validation:** sustained formatting with ordinary histories, duplicate/reordered/offline updates, checkpoint replay, restore epochs and fresh independent browser convergence. Validate the specific metadata candidate separately before claiming vulnerability or remediation.
- **Regression/rollout:** pin dependencies; use format/epoch compatibility rules for any history change. Existing drafts and revisions must remain readable or have an explicit migration/quarantine path. Keep a format gate for rollback.
- **Security/privacy:** no new exploit validation or security certification occurred. Do not relabel the historical candidate as a proven exploit.

### C4 — P1 / High: capacity and test evidence do not justify unrestricted editing

- **Category/status:** reliability and capacity acceptance; limits and recorded failure are confirmed, exact causal contribution of each concurrent workload is not isolated.
- **Files:** `frontend/src/features/editor/provider.ts:13` limits pending edits to 32 and text to 4,000 UTF-8 bytes. `experiments/yjs-private-engine/src/protocol.ts:3` sets finite payload/checkpoint bounds, two active workers and a three-second deadline. `server.ts:21` returns 503 when busy. `ops/recovery/lesson37-results.json:28` records the combined-run failure. Room limits are at `DocumentRoomHub.java:40`.
- **Current/expected:** these are deliberate learning safeguards: 8 KiB updates, 64 KiB rich checkpoints, 32 local room peers, eight sockets/user and 256/instance. The 16-peer, 12-commit paced experiment is useful evidence, not a maximum supported workload. A release needs a declared, representative document/editing workload and verified degradation/recovery.
- **Failure scenario/impact:** indexing plus structural editing and concurrent recovery work produced `ENGINE_UNAVAILABLE` after 45 of 80 structural edits. Isolated canonical rerun accepted 80. Grouped green per-class reports are not a clean single combined-suite run in that failing configuration.
- **Root cause:** shared bounded processing capacity and narrow tested workloads; the failure establishes a boundary, not proof that raising limits or replacing MVC is the solution.
- **Fix/example invariant:** `accepted edit => durable commit`, with explicit retry/pending status when engine work is busy. Prioritize interactive work or separate background capacity only after measuring contention; maintain finite queues and deadlines.
- **Required tests/validation:** repeat the failing combination under controlled resources; measure engine queue/busy time, edit latency, growing history, indexing lag, pending drafts and convergence after recovery. Establish the exact release matrix and a reproducible clean run.
- **Regression/rollout:** changes can reorder work, increase memory or delay indexing. Preserve stable update IDs and durable log semantics; gate scheduling changes and retain old finite limits for rollback.
- **Security/privacy:** intentional limits are not vulnerabilities; removing them without analysis can create resource-exhaustion risks.

### C5 — P1 / High for external deployment: operational release infrastructure is incomplete

- **Category/status:** deployment/availability; confirmed source configuration and repository inventory, not a failed local deployment.
- **Files:** `SecurityConfiguration.java:75` denies normal requests outside dev; `application-dev.properties:2` binds loopback. `ops/deploy/start-local.ps1:11` selects dev. `SocketSessionRegistry` keeps process-local guards/sockets. Lesson 35 documents process-local HTTP sessions. `ops/observability/alerts.yml:1` labels alerts as proposals. No `.github` CI directory exists in the inspected checkout. Recovery scripts are manual and do not encrypt or transfer backups.
- **Current/expected:** the local native launcher and recovery drill work. External/team deployment needs an explicit environment/authentication/TLS strategy, session affinity or shared-session design, managed process lifecycle, installed monitoring and backup ownership. Automatic off-machine backups and CI are operational requirements to define for that target, not missing numbered local lessons.
- **Failure scenario/impact:** switching off dev produces a mostly inaccessible application; exposing dev directly is not a supported production configuration. Instance restart/routing changes can require login again. A local disk loss can also remove a backup stored only under build output.
- **Root cause:** deliberate local learning scope rather than a production operations implementation.
- **Fix/example configuration boundary:** separate dev provisioning from the supported deployed authentication configuration; require secure cookie/TLS settings in that environment. Example policy: `production startup => validated secrets + HTTPS ingress + no development provisioning`.
- **Required tests/validation:** deploy to the intended environment; exercise login/logout, session expiry, restart and node routing, protected diagnostics, real alert notification and restoration from an independently stored archive. Check keyboard/accessibility and representative-volume migration/rollback as separate release checks; they were not audited here.
- **Regression/rollout:** changing cookie/session topology can log users out or break WebSocket authentication. Stage configuration changes, back up first, retain the old deployment/database during verified cutover, and do not automatically reverse Flyway migrations.
- **Security/privacy:** document/credential-containing backups need access control, encryption and retention for the chosen data classification. No jurisdiction-specific compliance conclusion is made.

## What is already supported by evidence

- Commit-before-acknowledgment, current membership checks, idempotent update retries, workspace/document isolation and strict request validation are implemented and covered by tests.
- Two pre-provisioned local browser replicas can collaborate, retain offline drafts, reconnect and repair gaps. Presence, named revisions, safe epoch restore and authorized search have meaningful verification.
- Redis hints can be lost or duplicated; durable log repair and role rechecks remain independent of the bus.
- Bounded sender queues, room admission, overload close/reconnect and a paced 16-peer workload have evidence. These do not demonstrate arbitrary scale.
- Packaging, the launcher smoke test, an isolated native database restore, owner/viewer/outsider permission checks and four backup guards were exercised. The recorded recovery-to-verified-application time is 19.59 seconds on a tiny fixture, with the engine and isolated database already running.
- Current per-class XML reports aggregate to 169 tests with zero failures/errors/skips; the artifact records 31 engine tests, 21 canonical development browser scenarios, three offline scenarios and two focused search scenarios. Those reports combine runs. The initial full combined run failed one browser wrapper; that result is explicitly retained.

No new exhaustive security, dependency/advisory, accessibility, legal-compliance or representative production load audit was performed here. Observed absence of CI follows the current checkout inventory, not a claim about an external CI service.

## Batched completion plan

| Batch | Objective and affected files | Exact change / dependencies | Tests and acceptance / definition of done | Rollback |
|---|---|---|---|---|
| A | Make the default packaged app usable; `App.tsx`, `api.ts`, capability contract if added | Default creation to supported plain text, communicate gated rich availability, retain CSRF/idempotency; no dependency on enabling rich | Fresh default launch: owner creates and edits a document, second user reads, viewer cannot create; no API console required for document creation | Revert UI/client changes; no format conversion or schema change |
| B | Complete owner workflows; app/features/workspaces and document-management modules | Add workspace creation, existing-account membership controls, rename/archive with version conflicts; depends on A's stable client patterns | End-to-end owner/editor/viewer/outsider journeys including revoke, stale version and last-owner denial; all promised management actions accessible | Revert individual UI modules; preserve authoritative APIs/data |
| C | Resolve rich-text release decision; engine validation, schema/provider and history | Choose vetted history/provenance design or formally narrow first release to plain text; keep gate until evidence | Independent-browser safety/convergence and sustained-history criteria pass; unresolved candidate is calibrated without unsupported exploit claims | Keep rich disabled; preserve old format/epoch handling and drafts |
| D | Establish the workload/release matrix; engine scheduling, indexing and tests | Reproduce contention, measure before changing prioritization/resource allocation; create repeatable CI/test entry points | Canonical functional tests and declared combined workloads pass in supported environments; limits and overload recovery are documented | Roll back scheduler/resource policy; retain durable IDs/log and bounded queues |
| E | Deploy safely for the chosen audience; security configuration, ops/runbooks | Implement target authentication/TLS/session/process strategy; install alerts and off-machine backup operation; depends on A–D for any rich product release | Ordinary-user install/onboarding works; restart/routing/alert delivery and independent restore/cutover verified; operations have owners and schedules | Staged rollout to prior app/config; verified database restore only if required, with explicit RPO |

P2 follow-up: retention/privacy policy, scheduled backup monitoring and CI supply-chain checks. P3: optional comments, mentions, exports or additional infrastructure only when product needs justify them. These should not delay fixing the default creation and owner-flow gaps.

Application code was intentionally left unchanged: this request asks for analysis, and production architecture/product scope decisions should not be silently implemented as part of a review.
