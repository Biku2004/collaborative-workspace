# Rich-text release contract

Batch C implementation verified locally, 2026-11-16. The default local launcher remains gated while combined-workload/deployment acceptance proceeds in D/E. This contract replaces the pre-upgrade position-history design described in Lesson 27.

## Binding and data compatibility

The frontend and private engine pin TipTap 3.32.3 and `@tiptap/y-tiptap` 3.1.0, with Yjs 13.6.33 and the existing shared schema. The upstream [3.32.3 release notes](https://github.com/ueberdosis/tiptap/releases/tag/v3.32.3) describe removal of persistent position migrations and cleanup of existing entries. The published binding's relative-position resolver no longer consults those records.

`RICH_TEXT_V1` still means the same XML root, nodes and marks. Existing text, formatting, client IDs, revisions and epochs are not silently converted. The shared contract additionally identifies the binding as `TIPTAP_NO_MIGRATIONS_V1`. Rich socket joins must name that binding; the server echoes it and the provider checks the echo before replay. Older or unknown rich bindings receive `CLIENT_UPGRADE_REQUIRED` without joining. Plain-text protocol compatibility is retained. A claimed binding is compatibility negotiation, not trusted proof about client code: the worker enforces the update policy independently.

The engine can read bounded legacy position metadata for historical replay, existing checkpoints and revisions. A new commit may keep an unchanged old record or delete it, but cannot introduce a record or change its value. The current binding ignores and deletes legacy entries when it mounts or receives transactions. Cleanup uses an ordinary validated update and cannot bypass the durable commit, version, membership or epoch checks. Read-only reconstruction is not an update-acceptance API.

The old metadata candidate demonstrated altered relative-position resolution, with distinct victim-facing harm unverified. This release removes dependence on that metadata and prohibits its creation in accepted commits; it does not retrospectively establish an exploit or certify every Yjs input as safe. Raw structure/schema checks, missing-dependency checks, byte/depth/node bounds and worker deadlines remain necessary.

## Drafts, presence and history

Existing rich drafts keep their original IndexedDB namespace and epoch. Their text stays recoverable locally. A pre-upgrade pending operation that introduces migration metadata is rejected and retained, rather than silently stripped or reported saved. Copy its visible text into the updated editor deliberately; do not erase a draft just to make a retry succeed. Existing bounded historical checkpoints remain readable; oversized/corrupt historical state must be recovered from a previously verified revision/backup, not accepted by lifting validation limits.

Presence remains ephemeral. Cursor anchors can stop following content that a structural operation replaces; clients republish their current selections and ignore unresolvable remote positions. Persistent comments/anchors across structural replacements are outside this release's feature contract.

Named revisions, restore epochs and old-draft quarantine remain supported. Removing position migrations eliminates their accumulating records; it does not make CRDT history infinite. The existing finite checkpoint/text/update/room limits remain. A document-limit rejection must retain the pending draft and distinguish it from a server save. Supported workloads and measured bounds belong to Batch D, rather than an unrestricted capacity claim.

## Required acceptance and rollback

- Engine/HTTP validation rejects new and replaced well-shaped records, allows legacy cleanup, preserves content, and handles duplicate cleanup.
- An actual pre-upgrade fixture opens in the updated editor with its text and marks; structural edits generate no new migration records.
- Real independent browsers converge through formatting, offline reload/reconnect, duplicate/reordered delivery, named revisions and epoch restore. Viewers and revoked users remain denied by Java.
- A sustained structural-edit workload records accepted count, checkpoint size, metadata count and pending operations; a new independent browser must load the committed result and edit it.
- All collaborators must update together. An older rich client cannot join. Worker commit policy still rejects metadata additions from callers that spoof the binding name.

If acceptance fails, keep rich disabled and retain all stored formats/drafts. Rolling back to the old binding requires disabling rich first and restarting clients; do not restore the old permissive metadata policy while advertising the new binding. No automatic database downgrade or format conversion is part of rollback.
