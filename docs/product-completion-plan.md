# Product completion — implementation tracking

The 37 local lessons are complete. Product completion follows the batches in [the 2026-11-16 review](reviews/2026-11-16-completion-review.md). Lessons and grouped test reports alone are not product acceptance.

| Batch | Scope | Current state |
|---|---|---|
| A | Supported default document creation and explicit format capabilities | Complete locally; verified 2026-11-16 |
| B | Workspace creation, member management, document rename/archive UI | Next batch |
| C | Resolve and verify rich-text/history release gate, or explicit user-approved first-release scope | Required: user confirmed rich text must be included (2026-11-16) |
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

## Batch B acceptance to implement next

- A stored user with no workspaces creates one through the UI and becomes its owner.
- Owners can add existing accounts, list/paginate members, change roles and remove members. Last-owner conflicts are shown clearly and cannot be bypassed by UI state.
- Editors can rename/archive with metadata versions; viewers cannot. Conflicts retain the user's proposed title until they deliberately reload current metadata.
- Owner/editor/viewer/outsider browser journeys include collaboration, live role reduction, revoked access, stale metadata and last-owner denial.
- Mutations use CSRF and existing backend authorization. New UI controls do not replace those checks or publish development-only provisioning as registration.
