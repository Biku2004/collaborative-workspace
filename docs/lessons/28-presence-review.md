# Lesson 28 — Compact engineering review

Scope: the local, single-instance presence implementation, reviewed for correctness, security, exceptions, resource bounds, compatibility and maintenance. These are subjective learning-prototype assessments, not production approval or a security certification.

| Perspective | Score / 10 | Evidence and limit |
| --- | --- | --- |
| Principal engineering | 6 | Identity and ephemeral delivery have explicit boundaries; aggregate room capacity is unmeasured. |
| Software engineering | 7 | Protocol negotiation and isolated presence storage are tested; editor and socket state still require careful coordination. |
| Security | 5 | Server supplies identity and tests reject impersonation and cross-document delivery; rich position-history provenance remains unresolved. |
| Reliability | 6 | Stale presence expires and disposable traffic is bounded; fan-out pressure and multi-instance delivery remain untested. |
| Maintainability | 6 | Protocol and failure behavior are documented and covered by focused tests; presence touches both editors and the room sender. |
| Production readiness | 2 | The service is a local learning prototype with an unauthenticated loopback engine, no production capacity evidence and a gated rich path. |

## Findings and decisions

- **P0:** None confirmed in the reviewed change. This is not evidence that none exist.
- **P1 — unresolved rich-text position-history provenance:** the earlier partial review found that fabricated metadata can alter relative-position resolution; victim-facing impact remains unverified. The default-off rich gate and documented limitation remain. A future mitigation or justified security decision needs its own evidence; do not bypass the previous safety restriction.
- **P2 — aggregate fan-out:** one permitted connection can cause a separate current-access check for each room recipient. Per-connection and queue limits do not establish safe aggregate database load. Measure this in the overload lesson and design an aggregate limit or other authorized delivery strategy before wider use. Rollback is to disable presence-capable clients; legacy joins do not receive presence messages.
- **P2 — clock change delaying expiry:** review found that using the wall clock could retain stale cursors after a backward adjustment. Presence timing now uses `performance.now()`; a browser test changes the wall clock and advances elapsed time before checking expiry. Regressions to watch include prematurely expiring active peers and failure to refresh after reconnect.
- **P2 — old-client compatibility:** the first integration run exposed an unsolicited departure message sent to clients that did not understand it. Presence now requires explicit join negotiation, with a socket test ensuring a legacy client receives neither updates nor departure messages.
- **P3 — richer plain-text display:** the plain editor displays resolved cursor and selection offsets in the collaborator list; rich text also renders in-document decorations. A visual plain-text overlay may improve usability but needs independent layout, scrolling and accessibility tests.

The full suite's 47 tests and complete browser fixture passed before the elapsed-clock change. The frontend build and focused presence plus offline-shell browser scenarios passed afterward. No database migration was introduced by this lesson. The loopback engine remains unauthenticated and local-only, and the tests do not establish capacity, availability, or safety under every hostile input.
