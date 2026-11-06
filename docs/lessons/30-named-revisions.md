# Lesson 30 — Named revisions

A checkpoint helps reconstruction. A named revision records a user-visible historical version that must stay readable after the live document changes. A Yjs state vector or historical marker alone is insufficient: it may still depend on deleted structures elsewhere. Each revision here owns a complete encoded state and its SHA-256 digest.

Status: **local learning criteria complete**. The feature retains the project's existing local-only and gated-rich-text limitations.

## Fixed committed state

The stream query reads the committed sequence, epoch, format and complete validation state together. Saving a revision copies that state before calling the private engine. The engine validates and round-trips the copy; the publication transaction rechecks current membership, role, archive state, format, epoch and session validity. Later edits in the same epoch are allowed to commit while validation runs. They do not change the copied bytes or the revision's inclusive sequence.

The revision snapshot is independent of `document_checkpoints`. It remains reconstructable if a later retention policy removes old checkpoint or update rows. No retention or deletion policy is implemented in this lesson. The existing local-only engine, history limits and rich feature gate remain in force.

The request includes a stable UUID `requestId`. Repeating the same name and request ID returns the original revision, including its original captured sequence, after current authorization is checked. Reusing the ID with another name conflicts. This prevents an ambiguous network retry from silently creating a new historical version at a later head.

## API and browser flow

- `POST /api/workspaces/{workspaceId}/documents/{documentId}/revisions` accepts `{ "name": "Reviewed draft", "requestId": "<UUID>" }`. Owners and editors can create revisions with a valid session and CSRF token.
- `GET .../revisions` lists authorized metadata using bounded keyset pagination, newest first. It accepts `limit` and a document-scoped `cursor`.
- `GET .../revisions/{revisionId}` returns the immutable bytes, checksum and metadata after checking current document access. A viewer can inspect but cannot create; nonmembers cannot list or preview. Responses use `no-store`.

Open **Named revisions** in the editor to save a label or inspect a prior version. The preview verifies the checksum and loads a separate in-memory Yjs document. It has no collaboration provider or pending edit queue, and is rendered read-only. The live editor continues to work independently. The panel explicitly explains that only committed server edits are included: pending local edits may be excluded.

Checkpoint and revision jobs share one background slot per backend process. A busy request fails explicitly and can be retried; the other engine worker slot remains available for edit traffic, though this is not a capacity guarantee. A session validity check before publication does not yet provide a fully ordered simultaneous-logout boundary; that is part of Lesson 32.

## Verification and review

Real-engine tests exercise a revision captured while a later edit commits, stable request retries, name conflicts, pagination, unauthorized reads and corrupted revision bytes. Rich-text tests check that the copied snapshot retains its supported format. The browser test holds a local update, checks that the editor shows `Saving…`, and saves the earlier committed state with the pending-edit warning visible. It then releases the update, saves the formatted state, edits the live document and confirms that the read-only preview remains unchanged. The test also delays the initial list request to check the panel's request state.

The full serial Maven suite passed **55 tests**, including all browser scenarios and offline-shell recovery, with no failures or skips. After final list handling, input, cross-document authorization and migration-preservation checks were added, the focused browser, real-engine, format-guard and migration suite passed **16 tests**. The frontend production build passed.

The compact review checked server-derived identity and capture boundaries, transaction lock ordering, current-role rechecks, idempotent retries, checksum validation, bounded metadata pagination, React effect cleanup and safe rendering. It fixed a false-success status for pending rich edits and kept initial-list responses from replacing newly created rows. Checkpoint and revision work now share a single background budget; this does not guarantee aggregate edit capacity. Names render as text, and preview content goes through the existing restricted editor schema. There is no arbitrary-HTML rendering path.

Remaining operational limits include manual snapshot creation, unbounded historical storage without a retention policy, the unauthenticated loopback engine, the rich position-history security question and the simultaneous-logout boundary scheduled for Lesson 32. The checksum detects changed bytes, not a database attacker who can rewrite bytes and digest together.

## PowerShell checks

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\frontend
npm run build

cd ..\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
$env:WORKSPACE_RUN_YJS_ENGINE_TESTS = 'true'
$env:WORKSPACE_RUN_BROWSER_TESTS = 'true'
Remove-Item Env:WORKSPACE_BROWSER_TEST_GREP -ErrorAction SilentlyContinue
mvn -B test
```

Use the dedicated test database and expect `BUILD SUCCESS` with no skips. Run verification processes serially: the current single-instance outbox relay is not designed for two independent test backends sharing a database.

## Interview questions

**Is a historical marker necessarily a backup?** No. A marker can depend on structures or update rows that later disappear. A standalone revision stores the full reconstructable state, but disaster recovery still requires a tested database backup and restore procedure.

**What if an edit commits while the revision is being validated?** The revision retains the sequence and full state captured at the start. The later edit remains in the live document. The UI must describe the captured boundary honestly.

**Why check authorization again on preview?** A user may lose access after the revision was created. Historical content has the same tenant boundary as current content; knowing a revision ID does not grant access.
