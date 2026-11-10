# Lesson 33 — Search authorized committed content

Status: **local learning criteria complete**. This is a local learning feature, with the existing default-off rich-text gate and private-engine limitations.

## Problem and example

A document list finds titles. Content search should find a document containing “Orion” even when its title is “Meeting notes”. Search must also stop returning that document when workspace access is removed. A stale search index must never become a second source of permission.

The index is a **projection**: data derived from committed document state that can be discarded and rebuilt. It is not the authoritative document, a pending-update queue, a backup or an access-control database. An edit can be saved before search catches up.

## Data flow and invariants

V18 creates `document_search_projection`, with a stored PostgreSQL `tsvector` and GIN index using the explicit `simple` text-search configuration. It records the indexed epoch, inclusive sequence, source checksum, literal text and indexing time. Separate attempt fields retain retry/backoff information without replacing previously indexed content on failure.

The scheduled projector selects at most four pending active documents. Each job uses the shared snapshot budget, reconstructs its captured state through the private engine and compares the resulting checkpoint bytes with the captured bytes. Plain text uses the existing candidate; rich text extracts bounded literal text from validated paragraphs, headings and lists. Formatting marks are not searchable syntax. Block boundaries become newlines. HTML-looking text stays literal text. NUL becomes a space in the projection because PostgreSQL text cannot store NUL; the authoritative Yjs state is unchanged.

Engine work happens outside a database transaction. Publication locks workspace then document and rechecks epoch, sequence, format, archive state and full state bytes. If the head changed while materializing, that candidate is discarded. A published index can become stale after a later edit, which is why query responses include its boundary and current head.

Failures record a sanitized code and a five-second retry delay. A new source epoch/sequence can be retried immediately. Failed documents rotate behind newer/unattempted work. The indexer has no call in update acceptance: a failed materialization does not veto a document commit. It does share bounded engine resources with other background work, so no throughput or availability guarantee is claimed.

## Access-controlled queries and freshness

`GET /api/workspaces/{workspaceId}/search?q=Orion&limit=20` requires current workspace membership. Query validation allows 1–200 non-control characters and a result limit of 1–50. Values are SQL parameters. PostgreSQL `websearch_to_tsquery` handles normal words, phrases and OR queries. Results are ranked and bounded; `hasMore` tells the frontend to ask the user to refine the query rather than presenting an unbounded list.

Both matches and coverage join current document and membership rows. Archived documents, disabled formats and projections from older epochs are excluded. After restore, old-epoch content remains hidden until the new epoch is indexed. Within the same epoch, older committed text may still match, but the response marks it stale and supplies indexed/current sequence numbers. An in-flight HTTP request authorized before revocation can finish; previously received content cannot be recalled. New unauthorized requests receive 404.

Responses include `items`, `hasMore`, `coverage` (`totalDocuments`, `currentDocuments`, `pendingDocuments`) and `indexingEnabled`. Coverage counts active, supported documents with committed streams; empty documents without a stream do not need a content index. The query and coverage use one repeatable-read transaction, so they describe the same database snapshot. Responses are `no-store`.

`POST .../search/rebuild` requires an owner, current session and CSRF token. It clears only that workspace's derived rows under the shared logout/publication guard and returns 202 with the number cleared. Documents, updates, revisions and checkpoints are retained. A rebuild request is not confirmation that indexing finished. The worker repopulates missing rows when enabled; the API can clear rows while indexing is paused, and callers must resume indexing to rebuild them.

## Browser flow

Choose a workspace and use **Search committed text**. The panel displays title buttons, literal excerpts, indexing timestamps, stale sequence labels, pending coverage, paused-indexing status and the top-result limit. Local pending edits are explicitly excluded. Opening a result fetches authorized current document metadata again before opening the editor.

An active query refreshes every two seconds while indexing is enabled and work remains, or every ten seconds otherwise. **Refresh results** checks immediately. A failed or denied refresh clears the old results. Changing query/workspace or unmounting aborts obsolete reads and cancels timers; search data is not stored in browser persistence or the service-worker cache. Owners can request **Rebuild search index** while indexing is enabled. Viewers can search but do not get that action.

The panel is lazy-loaded and follows the existing app styling. Titles and excerpts render as React text, with no HTML interpretation or highlighted HTML insertion. Search is absent from the explicit offline view.

## Enable and verify locally

The Java backend still requires the private Node/Yjs engine and PostgreSQL. In the terminal where the backend is started, enable the optional worker:

```powershell
$env:WORKSPACE_SEARCH_INDEXING_ENABLED = 'true'
# Then start the backend using the existing dev-profile commands.
```

The default poll delay is 2000 ms (`workspace.search.poll-ms`). The rich gate stays off by default; enabling search does not enable rich text. Search APIs can inspect existing projections while the worker is paused, and the UI discloses that results may be incomplete.

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\frontend
npm run build
cd ..\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
$env:WORKSPACE_RUN_YJS_ENGINE_TESTS = 'true'
$env:WORKSPACE_RUN_BROWSER_TESTS = 'true'
$env:WORKSPACE_RUN_SEARCH_BROWSER_TESTS = 'true'
$env:WORKSPACE_BROWSER_TEST_GREP = 'scheduled search|search renders|rich editors synchronize'
mvn -B '-Dtest=BrowserWorkspaceIntegrationTest,DocumentUpdateEndToEndTest' test

# General regression suite, without background search contention in unrelated history tests:
$env:WORKSPACE_RUN_SEARCH_BROWSER_TESTS = 'false'
Remove-Item Env:WORKSPACE_BROWSER_TEST_GREP -ErrorAction SilentlyContinue
mvn -B test
```

Run verification serially against the dedicated test database. The search-browser flag enables the actual scheduled worker for that browser fixture and adds the search scenarios. The focused filter also creates the rich document used by the fixture's three production offline-shell checks. Expected result: no failures/skips and `BUILD SUCCESS`.

## Verification and review

The real backend tests cover plain/rich text, current membership in both service and repository queries, parameter binding, bounded input/results, stale sequence reporting, restore epoch hiding, archive exclusion, rebuild, source-head races, busy budget, engine failure/backoff, accepted edits during failed indexing and NUL normalization. HTTP tests cover access, input, CSRF and `no-store`. Migration tests preserve prior checkpoint/outbox data.

The scheduled browser scenario creates and edits a document, waits for the real worker to make it searchable, opens the result, rebuilds and waits for repopulation. A viewer searches successfully, loses membership and then receives no results/document access. An isolated UI scenario checks stale/paused/pending/limited-result messages, disabled rebuild, literal markup rendering and clearing on denial. The production frontend build passed. On November 10, 2026, the full regression suite passed **70 Java test cases** with zero failures, errors or skips, including all 20 existing development-browser scenarios and three production offline-shell scenarios. A separate search-enabled run passed **18 Java cases**: the 17 real-engine/database history/search cases plus the browser fixture containing the scheduled-search scenario, isolated freshness/security UI scenario, rich editor scenario and all three offline-shell checks. The production frontend build passed.

The compact review traced database authority, snapshot publication, SQL parameters, source-format gates, failure isolation, session/CSRF checks, obsolete-request cancellation, timers and literal rendering. No new P0/P1 finding was identified. Operational P3 limits include shared background capacity, no measured large-workspace query latency and possible indexing delay under continuous hot-document churn. Existing P2 boundaries remain the unauthenticated loopback engine and unresolved gated-rich position-history validation. This is not a production security certification.

| Perspective | Score | Evidence and remaining limit |
|---|---:|---|
| Principal Engineer | 8/10 | Rebuildable projection and explicit consistency/access boundaries; scale is unmeasured. |
| Senior Engineer | 8/10 | Bounded jobs, validated publication and complete UI states with regression checks. |
| Security | 6/10 | Current membership, SQL binding, CSRF and safe rendering; inherited engine/rich risks remain. |
| Reliability | 7/10 | Retry/backoff and failure isolation; shared resources and hot-document churn can delay indexing. |
| Maintainability | 8/10 | Search has its own backend package and frontend feature, with shared API transport. |
| Production readiness | 3/10 | Local setup; observability, multi-instance verification, overload and disaster recovery remain. |

Roll out V18 with compatible code and enable the worker after the engine is available. To stop indexing, disable the worker; editing remains independent. The table is derived and can be rebuilt, but do not drop authoritative streams, updates or history. Retaining V18 while rolling back application code is safer than altering applied Flyway migrations.

## Interview questions

**Why must search not decide access?** Its text can be old. Every query must use the current membership/document rows rather than a permission copied when indexing ran.

**Why index asynchronously?** Saving an edit should not wait for a derived search representation. Lag is disclosed and indexing can be retried or rebuilt independently.

**What happens when an edit arrives during indexing?** Publication compares the captured boundary and state under the document lock. A changed head discards that candidate; a later attempt uses the new state.

**Does a search miss mean the document lacks the word?** Not necessarily. Pending indexing, a paused worker or supported tokenization can explain a miss. Coverage and timestamps make that uncertainty visible.

**Is a successful rebuild request proof of completion?** No. It confirms clearing/acceptance. Check coverage and query results after the asynchronous worker repopulates the projection.

Primary references: [PostgreSQL text-search controls](https://www.postgresql.org/docs/18/textsearch-controls.html) and [generated vectors and GIN indexes](https://www.postgresql.org/docs/18/textsearch-tables.html). The implemented behavior is verified against the local PostgreSQL test database.
