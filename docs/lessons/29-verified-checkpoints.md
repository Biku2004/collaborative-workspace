# Lesson 29 — Verified checkpoints

Status: **local learning criteria complete**. `document_streams.checkpoint` remains the mutable cache used to validate the next update. The new `document_checkpoints` table stores separate immutable, checksum-verified materializations. The browser currently replays the retained update log from sequence zero.

## Fixed boundary and publication

For a document and epoch, capture a committed head sequence `H` together with its format and validation checkpoint. One job selects a fixed boundary `S` at or before `H`: at most 16 updates after the latest verified checkpoint. It materializes the prefix through `S` from that checkpoint and its contiguous update tail, or from the initial state and complete log when no earlier checkpoint exists. A caller can repeat the job until the returned boundary reaches the observed head. The work does not hold database locks while calling the private engine. It validates schema and bounds, verifies stored input checksums and gaps, and computes SHA-256 for the resulting bytes. A digest detects later byte corruption; it is not a signature and does not by itself prove that the correct prefix was reconstructed.

Publish the resulting immutable checkpoint at `(document, epoch, S)` transactionally after rechecking the document, format, epoch and caller's current authority. A writer that commits sequence `H+1` while reconstruction runs must keep that update, its validation cache and its outbox event. Publication never replaces the mutable head with the older checkpoint. Replaying a checkpoint and updates with sequence greater than `S` must recover the same state as the complete committed log.

For the first iteration, retain **all** update and outbox records. This preserves both a safety window and all retry identities, and avoids breaking existing browsers that replay from zero. Deleting old rows or making clients fall back to checkpoint replay requires its own explicit deduplication, outbox and compatibility design; a checksum alone cannot make deletion safe. The checkpoint must be checked on every use, and corrupted bytes must fail closed rather than be returned as a valid snapshot.

The local endpoint accepts a CSRF-protected `POST /api/workspaces/{workspaceId}/documents/{documentId}/checkpoints` from an owner or editor. It returns the inclusive `throughSequence`, the head observed when the job began, the format, epoch and Base64 SHA-256 digest. If the boundary is behind the observed head, repeat the request to advance in another bounded batch. At most one checkpoint job runs at once per backend process, leaving the other private-engine worker slot available for edits; a busy job receives 503 and can be retried. This does not reserve capacity against other edit traffic. A same-boundary retry never overwrites the existing checkpoint. An authorized member can retrieve the latest checkpoint bytes and digest from `GET .../checkpoints/latest`; both responses use `Cache-Control: no-store`.

The publication transaction rechecks membership, role, archive state, epoch and session validity after reconstruction. A session check is not an atomic guarantee that a simultaneous logout cannot race the remaining commit; that broader coordination is part of Lesson 32. The service does not yet schedule checkpoints automatically or prune old checkpoint rows, so repeated publications consume database storage. Multi-instance quotas, automatic retention and recovery operations are future work. Do not run this as an unbounded public service.

## Why retain a safety window?

Imagine reconstruction starts at sequence 20. While it is running, an edit commits at sequence 21. The new checkpoint represents only the prefix through 20; deleting every update because a checkpoint exists would also erase 21. A correct recovery uses the verified snapshot at 20 plus the retained tail beginning at 21. The current version deliberately keeps the entire log, not just a small window: it also needs old update IDs for safe retries, and existing clients still replay from zero.

The checksum answers a narrower question: “Are these the same bytes that were stored with this digest?” It does not authenticate a database against an attacker who can rewrite both bytes and digest, prove a backup exists, or replace schema and prefix validation.

## Code and verification

The migration is [V15](../../backend/src/main/resources/db/migration/V15__verified_document_checkpoints.sql); the implementation is in [DocumentCheckpointService](../../backend/src/main/java/com/example/collaborativeworkspace/DocumentCheckpointService.java), [DocumentCheckpointRepository](../../backend/src/main/java/com/example/collaborativeworkspace/DocumentCheckpointRepository.java) and [DocumentCheckpointController](../../backend/src/main/java/com/example/collaborativeworkspace/DocumentCheckpointController.java). The endpoint uses the existing session and CSRF protections. A viewer may read an authorized snapshot but cannot create one, and a nonmember gets a hidden-resource response.

To run the real-engine and migration checks with the dedicated test database:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\experiments\yjs-private-engine
npm test

cd ..\..\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
$env:WORKSPACE_RUN_YJS_ENGINE_TESTS = 'true'
mvn -B '-Dtest=LegacyDocumentMigrationTest,DocumentUpdateEndToEndTest' test
```

Expect `BUILD SUCCESS` and no skipped tests in the Maven summary. The race test pauses reconstruction, commits a later update, then checks both the published boundary and checkpoint-plus-tail reconstruction. A separate upgrade test starts from V14 and checks that the existing stream, update and outbox survive V15.

## Interview questions

**Why is “delete all updates after creating a snapshot” unsafe?** A snapshot has a precise inclusive boundary. A writer may have committed a later update while the snapshot was being built, and clients may still need old IDs to retry safely. Retention must account for the boundary, retry horizon, existing clients, outbox and recovery window.

**Why compare a snapshot with the captured head when they have the same sequence?** The replayed state and the existing validation cache should represent the same committed prefix. A mismatch is evidence of an inconsistency, so publication fails. When the bounded job stops before the observed head, it validates the shorter prefix instead and reports that more work remains.

**What does the checksum prove?** It lets a reader detect an accidental difference between the snapshot bytes and their saved digest. It does not prove the writer was honest, the schema was correct, or the backup can be restored.

## Verified outcomes and review

- Real-engine tests reconstruct plain and gated rich documents, including deletions and bold formatting.
- A latch pauses reconstruction after capturing its boundary. A later edit commits, and checkpoint plus tail recovers the newer state without losing the update or outbox event.
- Tests reject a corrupt stored checkpoint, missing and altered log entries, epoch changes, role changes and a session becoming invalid before publication. Failed attempts publish nothing.
- An 18-update log advances through 16 and then 18 in bounded jobs. Busy-job rejection and retry preserve the same immutable boundary. HTTP tests cover authorization, CSRF and `no-store`; a format-guard test keeps rich checkpoints behind the existing gate.
- V14-to-V15 upgrade verification preserves the existing validation cache, update and outbox row.

The full serial backend suite passed 54 tests, including the complete browser fixture. After review reduced checkpoint concurrency from two jobs to one, all 13 real-engine and migration tests passed again; the final gate and deletion assertions also passed in a focused two-test run. The frontend production build passed for the preceding presence fix. No browser code changed in this lesson.

The compact review checked lock ordering, speculative engine work, current-role checks, immutable publication, defensive byte copies, digest validation, bounds and sanitized exceptions. It reduced checkpoint concurrency to leave an engine slot available for live edits. Remaining limits are explicit: all history is retained, snapshots grow storage, jobs are manually triggered, HTTP session checks do not yet define a fully ordered logout boundary, and SHA-256 is not a signature. No history rows are deleted by this lesson.

Lesson 30 extends the one-job background budget to cover checkpoint and named-revision work together. The feature remains a local learning prototype, and Lesson 27's security and history limitations still apply.
