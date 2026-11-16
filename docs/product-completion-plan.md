# Product completion — implementation tracking

The 37 local lessons are complete. Product completion follows the batches in [the 2026-11-16 review](reviews/2026-11-16-completion-review.md). Lessons and grouped test reports alone are not product acceptance.

| Batch | Scope | Current state |
|---|---|---|
| A | Supported default document creation and explicit format capabilities | Complete locally; verified 2026-11-16 |
| B | Workspace creation, member management, document rename/archive UI | Complete locally; verified 2026-11-16 |
| C | Resolve and verify rich-text/history release gate, or explicit user-approved first-release scope | Rich binding/history implementation verified locally; combined workload and deployed release remain D/E |
| D | Reproducible combined workload and release test matrix | Pending; retain the observed contention failure |
| E | Deployment/authentication/session/monitoring/backup operation for the chosen audience | Target confirmed: shared team/LAN deployment (2026-11-16) |

## Batch A acceptance

- The normal bundled launcher keeps `WORKSPACE_RICH_ENABLED=false`.
- A stored owner in an existing workspace creates plain text through the UI, opens it and saves an edit.
- Format discovery reflects the backend's actual policy. Its endpoint requires a workspace-user session and sends `Cache-Control: no-store`.
- When rich text is disabled, the UI marks it unavailable. Unsupported/malformed capability responses must not enable creation.
- The server's format gate remains authoritative even if a caller sends a rich request directly.
- An ambiguous lost response after creation can be retried with the same key and creates one document. Title/format changes use a different attempt key.
- A second member reads the created document; a viewer has no create control and a valid-CSRF create request is rejected.
- Gated rich tests choose rich explicitly; existing offline/history/search behavior remains supported.

The create form lives in `frontend/src/features/documents`, with its own cancellation, mounted-state and retry-key handling. It is keyed by user and workspace, so an old workspace's asynchronous response cannot populate the current list. No document format is migrated and no schema change is required.

Batch B will supply workspace and membership controls; Batch A uses existing provisioned users/workspaces and does not claim to complete onboarding.


## Batch A verification — 2026-11-16

[Recorded results](reviews/2026-11-16-batch-a-results.json): 168 Java tests passed with no failures/errors/skips, including 23 development browser scenarios and three production offline scenarios. The separate indexing-enabled search group passed two scenarios. The Redis-dependent multi-instance test was excluded from this batch; the cluster protocol was not changed.

The final bundled JAR passed the three-phase native recovery/browser drill and launcher readiness check. Its seed phase now creates plain text through the actual UI, simulates losing a response after the server commits, and proves the retry returns one document using the same key. The recovered viewer can read but cannot create; raw rich creation remains denied under the default gate. Invalid-capability, whitespace-title and format-aware retry behavior have focused client tests.

The React checklist review covered feature extraction, labelled controls, effect cancellation, unmount guards, user/workspace isolation and preservation of server authority. The initial synthetic search fixture needed a capabilities mock because its mocked identity was not a real server session; the corrected focused test passed. No schema change or gate enablement was made.

## Batch B acceptance

- A stored user with no workspaces creates one through the UI and becomes its owner.
- Owners can add existing accounts, list/paginate members, change roles and remove members. Last-owner conflicts are shown clearly and cannot be bypassed by UI state.
- Editors can rename/archive with metadata versions; viewers cannot. Conflicts retain the user's proposed title until they deliberately reload current metadata.
- Owner/editor/viewer/outsider browser journeys include collaboration, live role reduction, revoked access, stale metadata and last-owner denial.
- Mutations use CSRF and existing backend authorization. New UI controls do not replace those checks or publish development-only provisioning as registration.

## Batch B verification — 2026-11-16

[Recorded results](reviews/2026-11-16-batch-b-results.json): the canonical 168 Java tests passed with no failures/errors/skips, including 24 development browser scenarios and three offline scenarios. A separate indexing-enabled run passed the management journey and two search scenarios. The management test additionally verifies editor archive and viewer archive denial. Redis-dependent multi-instance coverage remains excluded because this batch does not change the cluster protocol.

A fresh stored account creates its workspace through the browser; a lost committed response is retried without a duplicate. The owner adds existing editors/viewers, pages through 22 members, sees unknown-account and last-owner errors, and can demote itself only after adding another owner. Editors rename/archive; stale rename proposals survive conflicts until deliberate reload. Live role reduction locks an editor, removal denies workspace/document/member reads, and retained text remains visible locally. UI controls disappear after self-demotion and direct unauthorized requests still fail.

The rebuilt bundled JAR passed the native three-phase backup/restore/browser drill and launcher readiness check. Its seed now performs workspace creation and viewer membership setup through the actual UI. The expected attempt to restore over an existing target is rejected. All drill-owned processes stop afterward; the normal development database is untouched.

The React checklist review covered labelled forms, narrow feature modules, abort/generation guards, unmount guards, retry identity and role/version authority. Mobile member pagination was visually checked at 390px with no horizontal overflow. No schema or stored-format change is required; rolling back the UI leaves existing authoritative APIs/data in place.

## Deployment clarification

The user delegated backup setup after explaining that the LAN choice meant multi-user sharing. The initial target stays one host on a shared home/office network, with browser clients. Batch E should install automatic encrypted local backups outside the checkout and verify recovery. No separate backup machine/share has been supplied: do not claim host-loss protection or verified off-machine storage until an independent destination exists.

## Batch C verification — 2026-11-16

The [release contract](rich-text-release.md) replaces dependence on persistent position migrations with the upstream removal in y-tiptap 3.1.0, pinned with TipTap 3.32.3. Java requires the current rich binding at JOIN; the provider validates its echo. Engine commit validation allows unchanged legacy records or cleanup, and rejects additions/replacements independently of the claimed client binding. Existing schema/format identity and draft namespaces remain.

The canonical 168 Java tests passed with no failures/errors/skips, including 25 development browser scenarios and three production offline scenarios. The structural case accepted 500 of 500 committed heading toggles with zero pending operations and zero migration records; its checkpoint was 31,059 bytes. A new independent browser loaded that committed result and edited it. The old 80-edit Batch B measurement was 58,185 bytes with 80 migration records; the workloads differ and neither establishes unlimited history.

The actual pre-upgrade fixture opens in the new editor with its heading, bold and italic text intact; its migration records are retired. Existing offline, permission, named revision and restore/old-draft quarantine cases pass. The source and HTTP engine tests include rejection of fresh well-shaped metadata and content-preserving cleanup at the legacy record bound. Read-only reconstruction remains separate from accepting a commit.

The default local launcher's rich gate remains off while Batch D establishes combined-load evidence and Batch E supplies the supported LAN configuration. This is a feature-contract/compatibility checkpoint, not an Internet deployment or exhaustive security certification.
