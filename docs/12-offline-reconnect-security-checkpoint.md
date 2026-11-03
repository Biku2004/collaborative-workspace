# Security and quality checkpoint — Lessons 25–26

This is a focused review of the offline queue and reconnection changes, not a production security certification or a comprehensive malicious-input assessment. The earlier [checkpoint](11-security-quality-checkpoint.md) describes the state through Lesson 24.

## Evidence and boundaries

- A browser stores a Yjs snapshot and exact pending update IDs and bytes in one IndexedDB record per stored user, workspace, document and epoch. Writes are serialized; a Web Lock prevents two tabs from writing the same account/document record concurrently. A simulated storage failure stops submission and keeps the visible draft in the current tab for copying.
- The production browser test disables networking, reloads from a static-only service-worker cache, edits again and restores connectivity. It verifies stable retry IDs, a lost acknowledgement with one server-log occurrence, a same-profile account switch and a removed member's local recovery. These tests use the real local backend and PostgreSQL, but do not prove browser storage cannot be lost or evicted.
- Automatic reconnect rechecks the session identity, verifies the socket's server-authenticated user ID and joins an authorized room before replay and submission. A real two-user test exercises concurrent offline insert and delete edits and a membership revocation. An epoch-mismatch protocol test checks that a pending ID is not submitted. The server enforces authorization independently of the browser.
- Retry timers, a connection deadline, delayed retries with jitter and stale-socket checks limit common reconnect failures. A protocol test covers increasing retry delays during repeated engine failures. A first-time client cannot edit an incomplete local copy after an interrupted replay.

## Remaining risks and limits

1. **Production blocker — local device trust:** IndexedDB, local metadata and static caches are not encrypted. Anyone able to inspect the browser profile or run script on the origin may read cached drafts. Storage can be denied, evicted, cleared or lost; a local success is not a server backup. Sign-out removes the active shortcut, not the underlying draft records.
2. **Production blocker — Yjs validation and rich text:** The private engine and custom protocol support bounded plain text only. Complete provider interoperability, rich-text schema validation and broad hostile-input testing are still open. Loopback is not an operating-system isolation boundary.
3. **High — service-worker lifecycle:** Only versioned static assets are cached, and tests confirm API paths are not cached. There is no general upgrade/rollback or multi-version compatibility strategy for a production deployment; the UI exposes only the last local document, not a full export or draft manager.
4. **High — delivery and operations:** Session guards, rooms and relay remain single-instance; checkpoint size is bounded to 8 KiB; retention, multi-instance coordination, load measurements, backup/restore drills and observability remain future work. A successful commit does not prove delivery to every collaborator.
5. **Medium — authorization feedback:** Browser WebSocket APIs do not expose the HTTP status of a failed handshake. A revoked client can therefore continue retrying with backoff instead of immediately distinguishing revoked access from a network outage. The server rejects unauthorized joins and writes; locally retained data cannot be recalled from an offline device.
6. **Medium — unverified failure combinations:** Tests cover representative account switches, revocation, epoch mismatch, lost acknowledgements and storage failure, but not every crash point, browser eviction behavior or concurrent failure. Do not infer production readiness from this suite.
