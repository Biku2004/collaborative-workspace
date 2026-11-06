# Lesson 31 — Restore a revision with a new epoch

Status: **local learning criteria complete**. This remains a local learning feature with the existing default-off rich-text gate.

## The problem

Imagine that the current text is “Later content” and a revision contains “Original”. Applying the revision bytes to the current Yjs document would merge the two histories. An offline browser could then upload structures from before the restore and bring back content the owner intended to replace.

An **epoch** is a document generation number. An ordinary edit changes the sequence inside one epoch. A restore creates a fresh document history, advances the epoch and begins at sequence zero. Every upload carries its epoch. The server rejects an upload from an older epoch even when its update ID was previously accepted.

## The implementation

The private engine materializes supported plain text or the restricted rich schema into a new `Y.Doc`. It drops old structural position history, chooses a client identity absent from the source checkpoint and validates the resulting encoded state again. The loopback-only `/v3/fresh-state` endpoint accepts version 3, a supported format and one canonical Base64 checkpoint. Request/response limits are 128/256 KiB; decoded state limits remain 8 KiB plain or 64 KiB rich. Existing worker and deadline limits apply. The engine produces a speculative candidate and has no authority to commit a restore.

Java checks current workspace access, editor/owner role, archive state, enabled format, the source revision's document scope and checksum, and the requested expected epoch and head. It validates fresh state outside the transaction while holding the shared snapshot budget. The publication transaction locks workspace then document, checks authorization and session validity again, and compares both expected values. It changes the stream to the next epoch at head zero, inserts the full immutable sequence-zero checkpoint and writes an idempotent restore audit event in V17. All three changes commit together. Old updates, outbox rows, checkpoints and revisions remain retained.

Two race orderings are intentional:

- If an edit commits first, restore returns `409 HEAD_CHANGED`. The user reviews the new head before replacing it.
- If restore commits first, the old writer fails with `EPOCH_CHANGED`. It cannot publish its already-validated candidate.

`requestId` belongs to the authenticated actor and document. A retry with the same revision and expected boundary returns the original event after checking current write access. Reusing it with other input conflicts. A second restore advances the epoch again; a retry never creates another epoch.

The restored state can exceed the per-edit 8 KiB limit. `GET .../baseline` therefore returns the current epoch's sequence-zero state, checksum, format and observed head over HTTP. The browser verifies the size and checksum before applying it. It then replays bounded socket updates starting after sequence zero. Checkpoint reconstruction also begins from that immutable baseline. The normal per-update and WebSocket frame limits stay unchanged.

## Browser recovery

The provider's epoch is fixed for its lifetime. Local storage keys already contain user, workspace, document, format and epoch; they now use the negotiated epoch instead of a hard-coded zero. Each reconnect verifies identity and fetches a baseline before sending pending work. Periodic server heads reveal restores to connected peers; an epoch rejection also refreshes the version metadata. Older delayed outbox frames cannot be applied to a newer document.

On detecting a restore, the browser freezes its old document and pending queue. **Keep old draft and open restored version** first waits for a local storage write, then creates a new provider and Yjs document for the new epoch. Old update IDs and bytes never enter that provider. A scoped local navigation hint survives reload, but grants no access and cannot override server epoch checks.

**View previous local draft** loads a separate read-only Yjs document without a provider. Select and copy the desired fragment, then paste it into the current editor as a new edit. This is deliberate recovery of materialized content, not automatic merging of old CRDT history. The most recent previous epoch is linked in the UI; still-older stored keys are retained without a browser history-management UI. Browser storage remains subject to eviction and is not a backup.

In Named revisions, select a preview, choose **Review restore**, inspect the revision name and current committed boundary, then choose **Restore this revision**. The server checks the boundary again. Pending edits from every client remain in their previous local drafts. Revision selection is disabled during review/publication so the confirmation cannot display one name while submitting another revision.

## Files and flow

- `RestoreRevisionRequest`, `DocumentRestoreController`, `DocumentRestoreService` and `DocumentRestoreRepository`: input, authenticated transport, speculative validation and atomic audit/publication.
- `V17__document_restore_events.sql`: actor-scoped idempotency and retained source/boundary metadata. No destructive migration or history pruning.
- `YjsEngineClient` and `experiments/yjs-private-engine/src/freshState.ts`: bounded conversion to fresh history.
- `DocumentCheckpointService`: reconstruction from a new epoch's sequence-zero baseline.
- `frontend/src/provider.ts`, `Editor.tsx`, `epochHint.ts`, `LocalDraftPreview.tsx` and `RevisionsPanel.tsx`: fixed-epoch editing, explicit replacement and disconnected local recovery.
- `verifiedState.ts`: shared size/checksum verification for baseline and revision preview.

## Run and verify

Run processes serially against the dedicated test database. Two unrelated test relays sharing a database can consume one another's outbox rows.

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\experiments\yjs-private-engine
npm test
cd ..\..\frontend
npm run build
cd ..\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
$env:WORKSPACE_RUN_YJS_ENGINE_TESTS = 'true'
$env:WORKSPACE_RUN_BROWSER_TESTS = 'true'
Remove-Item Env:WORKSPACE_BROWSER_TEST_GREP -ErrorAction SilentlyContinue
mvn -B test
```

Expected: all selected tests pass with no skips, followed by `BUILD SUCCESS`. The engine suite covers new identities, deleted plain text, headings, marks, lists and invalid/bounded transport. Backend tests cover both edit/restore race orderings, stable retries and conflicting input, old-epoch rejection, preserved old history, session/role changes during engine work, HTTP CSRF and access checks, and migration preservation. The browser scenario restores formatted content while another independent browser edits offline, reloads the new epoch, inspects the quarantined draft and deliberately recovers one fragment.

Verification result: all **56 non-browser test cases** passed in the full serial backend run. All **18 browser scenarios** passed after the fixture setup fix. The final focused restore/rich browser run and all **three production offline-shell scenarios** then passed with no failures or skips. The frontend production build and **31 private-engine tests** passed. The latest retained Java test reports total **57 cases with no failures, errors or skips** across those runs.

## Common mistakes

Do not apply an old checkpoint as an edit to the current document, reset only the sequence, retry a restore against a silently refreshed head, reuse a local draft key across epochs, or pass old pending bytes to the new provider. Do not delete old logs merely because the current stream moved forward. Keep the full baseline separate from a bounded incremental edit.

A SHA-256 digest detects changed bytes. It does not protect against an administrator rewriting both state and digest. The unauthenticated loopback engine and rich position-history concerns remain unresolved. HTTP snapshot/revision/restore session checks are not yet a fully ordered simultaneous-logout boundary; Lesson 32 addresses active access revocation. Historical storage has no retention policy, and aggregate fanout/capacity have not been established.

## Compact engineering review

The reviewed path spans authenticated HTTP, the bounded private engine, PostgreSQL publication, periodic socket heads, IndexedDB and the editor. Server-derived actor IDs, document-scoped revision lookup, current role checks, digest verification, stable request IDs, shared job admission and workspace/document lock ordering are enforced in code. The restore transaction is atomic and the browser never reuses a provider across epochs. Names and plain previews render as text; rich previews use the existing restricted schema. Lazy imports keep history and recovery surfaces out of initial editor rendering. Effects destroy isolated Yjs documents and release storage locks.

Confirmed fixes include the StrictMode storage-lock race in local draft preview, the hard-coded client epoch, checkpoint replay incorrectly treating every sequence-zero boundary as an empty rich document, and the revision selection/restore-review mismatch. Tests now distinguish denied access from transient network loss and restore shared membership even when a browser assertion fails.

No new P0/P1 defect was identified in this compact review. Existing P2 risks remain: the local-only unauthenticated engine, unresolved rich position-history validation, and HTTP publication versus simultaneous logout. Relevant files are the engine server, rich validator, `SocketSessionRegistry` and snapshot/revision/restore controllers. A logout can occur after the current HTTP validity check and before commit; an ordered guard and race tests belong to Lesson 32. Previously received content cannot be recalled. P3 operational work includes retention, metrics and measured aggregate capacity. These are limitations, not a security certification.

| Perspective | Score | Evidence and limit |
|---|---:|---|
| Principal Engineer | 7/10 | Explicit epoch/baseline contract and ordered commit; lifecycle and operational work remain. |
| Senior Engineer | 8/10 | Scoped repositories, defensive bytes, bounded transport and failure tests; the provider state machine is growing. |
| Security | 5/10 | Membership/CSRF and epoch isolation are enforced; inherited gated-rich and loopback boundaries remain unresolved. |
| Reliability | 7/10 | Transactional publication, stable retries and retained drafts; browser storage can be evicted and capacity is unmeasured. |
| Maintainability | 7/10 | Conversion, publication and disconnected recovery are separate; broader provider changes need regression coverage. |
| Production readiness | 3/10 | Local learning milestone only; no production identity/configuration, retention, availability or disaster-recovery claim. |

Roll forward with the additive V17 migration and matched frontend/backend/engine versions. Keep the rich gate disabled by default. After a restore, an older epoch-zero client fails closed and cannot edit that document. Do not drop restore events or sequence-zero checkpoints as an application rollback. Old data remains retained for audit and manual recovery.

## Interview questions

**Why can an offline client undo a restore?** If its old updates are merged into the same history, deleted or replaced structures can reappear. A fresh history plus a server-enforced epoch rejects those updates.

**Why compare head as well as epoch?** An edit can arrive after the user reviews restore without changing epoch. Comparing the expected head prevents replacing an unseen committed edit.

**Why is an engine response not a restore?** Permission, session or head can change while validation runs. The database transaction must recheck those facts and atomically publish all durable records.

**Can we recover a useful offline paragraph?** Yes. Keep the old draft isolated, copy the desired materialized text, then submit it as a new edit in the current epoch. Never replay the previous pending queue into the new document.
