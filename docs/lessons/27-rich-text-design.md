# Lesson 27 — Rich-text compatibility design (in progress)

Status: **in progress**. A gated rich-text editor, creation API, server path and private-engine validator exist for integration testing. Rich-text creation and collaboration require `workspace.rich.enabled=true`; the gate is disabled by default. The standalone experiment is not an authorization boundary or evidence of production safety.

## Why this needs a new document contract

The existing stored updates use a Yjs root named `content` as `Y.Text`. TipTap's collaboration extension binds a ProseMirror document to a Yjs XML fragment, normally named `default` or a configured field. A valid update for one representation must not be silently interpreted as the other. Changing the editor alone would cause the existing private engine to reject updates; relaxing that engine without a schema check could accept malformed or unsafe rich-text structures.

The first supported rich-text version should contain paragraphs, headings (levels 1–3), bold, italic, bullet lists and ordered lists. It should reject unsupported node names, attributes, marks, embedded objects and invalid ProseMirror hierarchy rather than silently dropping them. Arbitrary HTML, links, images, embeds and extra formatting are out of scope. Bound update sizes, total text, node count, tree depth and checkpoint size before accepting data.

## Design decisions to finish before marking the lesson complete

1. **Immutable format identity:** persist a document's collaboration format/schema version and return it in metadata and the socket handshake. Preserve existing plain-text documents and pending updates under their original format. New rich-text documents must be explicitly identified. Unknown versions must fail closed in both old and new clients. A migration of existing documents needs a separately designed epoch change and a recovery path for offline drafts; do not perform an implicit lossy conversion.
2. **Shared schema:** derive the browser editor and engine validation from the same pinned, versioned schema definition. Prevent frontend and engine dependencies from drifting. TipTap's collaboration extension accepts a Yjs document or a named XML fragment; its collaboration history should be used instead of a second independent undo history.
3. **Validate before commit:** reconstruct the candidate checkpoint plus update in the private worker; reject unsupported Yjs roots, missing dependencies, malformed updates and structures that violate the schema or limits. ProseMirror's parser can normalize or omit data, so inspect raw Yjs structure and attributes before conversion and validate the resulting ProseMirror document as well. No candidate is a committed edit until the existing locked database transaction completes.
4. **Client and recovery:** apply TipTap changes to the same persisted Yjs document and stable-ID queue used by the provider. Restore the correct schema and epoch before replay or retry. Viewers remain read-only, and server authorization remains decisive. Initialize an empty document only once: two tabs should not independently insert duplicate starter content.
5. **Safe rendering and compatibility:** use the supported ProseMirror schema and avoid rendering arbitrary stored HTML. Old plain-text clients must reject rich-text versions instead of submitting incompatible updates. Archived, revoked and epoch-mismatched documents must preserve local work without accepting unauthorized writes.
6. **Verification:** test all supported nodes and marks, concurrent formatting and insertion/deletion in independent browser contexts, offline reload and reconnect, malformed and unsupported nodes/attributes/marks, missing dependencies, byte/depth/node limits, old-schema rejection, viewer and revoked permissions, and preservation of existing plain-text documents. Add a clear failure-injection path for initialization and schema mismatch.

The additive V10 migration labels every existing document `PLAIN_TEXT_V1` without rewriting its Yjs history. Document responses include that label; old stored idempotency responses without it default to the legacy label. V11 permits the rich-text label in storage; V12 makes the label immutable. The creation API accepts an explicit rich format only when the `workspace.rich.enabled` gate is enabled; an omitted format retains the old plain-text behavior. A socket join can specify a format, and the server rejects a mismatch before joining; a legacy join without a format is interpreted as plain text. Format is part of creation idempotency comparisons, so reusing a key for a different format conflicts. Rich drafts have a distinct local-storage key; any future cross-format migration still needs a new epoch.

The contract includes a deterministic, validated Yjs update containing one empty paragraph. The service commits it as sequence 1 atomically with rich-document creation and its outbox record; tests cover retry identity, permission denial and rollback. Clients replay that update before mounting the editor, avoiding two tabs independently inserting starter paragraphs. The fixture uses a fixed Yjs client ID: documents have separate histories, and a test checks that a client with the same ID changes its ID when it applies the initial update.

## Experiment completed so far

The isolated experiment reads its schema options from `packages/rich-text-schema-v1/contract.json`, builds a restricted TipTap schema, converts supported ProseMirror JSON into a Yjs XML fragment and verifies a Yjs checkpoint round trip. It also checks concurrent insertion/formatting convergence, missing dependencies, size bounds and representative rejection cases. Concurrent formatting boundaries can differ with update ordering: tests require replicas to converge and the original formatted text to retain its mark, rather than assuming that a simultaneous adjacent insertion is always marked. First run `npm ci` and `npm run build` in `experiments/yjs-private-engine`; then run `npm ci` and `npm test` in `experiments/rich-text-schema`.

An isolated `RichTextSurface` component and browser harness exercise headings, bold, italic, both list types and a read-only viewer. The harness synchronizes two local Yjs instances directly; separate browser integration tests exercise the gated API and collaboration path.

The browser provider negotiates either named format and keeps rich drafts in a separate IndexedDB namespace while retaining the existing plain-text key. The default server configuration rejects rich-text creation, joins, replay and writes. Enable the gate explicitly only for local experimentation while the remaining verification is in progress.

The private engine offers a loopback-only `/v2/reconstruct` endpoint. When the gated rich-text path commits an update, Java sends the previous checkpoint and proposed update in commit mode; the engine rejects changes that delete or replace existing TipTap position-history records. The existing `/v1/reconstruct` endpoint still rejects rich-text updates. The raw validation code relies on selected Yjs internals and has not been subjected to an exhaustive adversarial audit. The engine is unauthenticated on loopback, so other local processes can call either endpoint. Its current limits, including at most 64 position-history records and a 32 KiB rich checkpoint, restrict document size and editing duration. Individual network updates remain limited to 8 KiB. This remains a learning prototype, not a production-ready validator.

## Follow one edit through the system

1. An owner or editor submits a title and explicit `RICH_TEXT_V1` format to `POST /api/workspaces/{workspaceId}/documents`. `WorkspaceDocumentController` checks the feature gate. `DocumentService` checks workspace membership and creates the document, initial Yjs update, stream checkpoint and outbox event in one database transaction. The creation key identifies retries of the same title and format.
2. Each browser requests a session, joins an authorized document room and includes its format in `JOIN`. The server checks that the requested format matches the stored format. The browser replays the shared initial update before enabling the rich-text editor.
3. TipTap translates a supported formatting action into an update to the Yjs XML fragment named `body`. `DocumentProvider` records that update and its stable ID in IndexedDB before sending it to Java. A visible local change is not yet a server save.
4. Java reconstructs a candidate from the last committed checkpoint and the proposed update. The private Node worker checks bounded input, shared roots, attributes, marks and document hierarchy. A worker result is a candidate, not an authorization decision or a database commit.
5. Java locks the relevant records, checks the current session, role, format, epoch and head, and writes the update, new checkpoint and outbox event in one transaction. It sends `SAVED` after that transaction commits. Other authorized browsers receive committed updates or recover them through replay.

The browser, Java service and worker all have different responsibilities. A toolbar being read-only is a usability control; the server's role check is the permission boundary. A correct Yjs merge is a convergence property; the schema and bounded worker checks are separate validation controls.

## Try the gated path locally

Use the existing local PostgreSQL development database and the stored test accounts from the earlier lessons. Do not use a production database or sensitive documents. The three following blocks are for **three separate PowerShell windows**; keep each process running. The first startup after the new migrations applies them to the database configured for that process.

**Window 1 — private document engine**

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\experiments\yjs-private-engine
npm ci
npm run build
npm start
```

Expected: the engine reports that it is listening on `127.0.0.1:8091`. It is a private local process and is not authenticated as a separate service.

**Window 2 — Java backend**

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:WORKSPACE_DEV_PASSWORD = Read-Host -MaskInput 'Local bootstrap password'
$env:WORKSPACE_WEBSOCKET_ALLOWED_ORIGIN = 'http://127.0.0.1:5173'
$env:WORKSPACE_RICH_ENABLED = 'true'
mvn spring-boot:run
```

The environment file supplies local database settings; do not paste its contents or passwords into chat. The bootstrap password is for the temporary development account; the browser uses a stored account. `WORKSPACE_RICH_ENABLED` enables the experimental path for Java processes started from this PowerShell window. To return to the default-off setting, stop Java, run `Remove-Item Env:WORKSPACE_RICH_ENABLED` in that window, and then restart Java. See [Lesson 9](09-users-and-passwords.md) for stored-user provisioning and [Lesson 13](13-membership-and-roles.md) for workspace membership.

**Window 3 — browser application**

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\frontend
npm ci
npm run dev
```

Open `http://127.0.0.1:5173`, sign in as a stored owner or editor, select a workspace and create a rich-text document. Open the same workspace as another authorized user in a separate browser profile or private window. Type text, apply bold, a heading and a list, and check that both editors converge and report a server save. A viewer can read but cannot create or commit changes. The two browser profiles must be separately authenticated; two tabs in the same profile intentionally share a local document lock.

For an actual offline reload, use the production preview steps in [Lesson 25](25-offline-persistence.md), setting the backend's allowed WebSocket origin to the preview origin before starting it. Wait for the service worker and a complete synchronized copy, go offline, edit and wait for local confirmation, then reload and open the local document. Reconnect and verify the edit from another authenticated browser. Vite's development server does not provide the production offline shell.

## Run the automated checks

These commands use the **dedicated test database**, not the development database. The test fixture refuses unsafe database names and starts its own loopback processes; it also needs a Playwright-compatible Chromium browser. Run `npx playwright install chromium` from `frontend` if no compatible browser is already installed.

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\experiments\yjs-private-engine
npm ci
npm test

cd C:\Users\lenovo\Downloads\collaborate_workspace\frontend
npm ci
npm run build

cd C:\Users\lenovo\Downloads\collaborate_workspace\backend
. .\.env.local.ps1
$env:WORKSPACE_RUN_DB_TESTS = 'true'
$env:WORKSPACE_RUN_YJS_ENGINE_TESTS = 'true'
$env:WORKSPACE_RUN_BROWSER_TESTS = 'true'
mvn -B '-Dtest=YjsEngineClientTest,RichDocumentInitializationTest,DocumentUpdateEndToEndTest,BrowserWorkspaceIntegrationTest' test
```

The engine tests exercise malformed documents, unsupported structures, metadata bounds and commit-mode checks. The database tests cover atomic initialization and update acceptance. The browser fixture exercises two clients, formatting, offline reload, viewer denial and repeated heading changes; its capacity measurement is a workload example, not a production load benchmark. A successful run ends with Maven `BUILD SUCCESS`; investigate failures instead of assuming a skipped test passed.

## Common problems

- **Create returns an error:** check the title, membership role and that the backend process was started with `WORKSPACE_RICH_ENABLED=true`. The normal default is disabled.
- **The editor cannot connect:** confirm that all three processes are running and the backend's allowed WebSocket origin exactly matches the browser origin, including host and port.
- **A test does not run:** check the three `WORKSPACE_RUN_*` flags and test database environment in the same PowerShell session as Maven. Browser tests require a built frontend and engine.
- **A document reaches a limit:** copy the visible local draft before leaving. A local pending draft is not proof of a server save, and this prototype does not yet offer general-purpose rich-text draft export or history compaction.

## Questions and answers

**Why is a valid Yjs update not automatically a safe document?** Yjs checks and merges its own data structures; it does not enforce our product's allowed node types, attributes, formatting marks or document hierarchy. A valid update could contain a node or hidden root that our editor does not support. The engine first checks the raw shared structures and bounds, then checks the resulting ProseMirror tree against the pinned schema. These checks reduce risk but do not prove safety against every possible input.

**Why does the document store a format label?** Plain text uses a different Yjs root and structure from the rich editor. The immutable label makes the interpretation explicit. A client must negotiate a matching format before joining, and existing plain-text history remains readable without being silently converted. A future conversion needs an explicit migration and a plan for offline drafts.

**Why insert one empty paragraph on the server?** The editor needs a valid initial tree. If each client creates its own initial paragraph before receiving the other's update, two concurrent clients can produce duplicate starter content. Creation commits one shared bootstrap in the same transaction as the document and its outbox record. Retrying with the same idempotency key returns the same result rather than initializing again.

**Why validate an edit before the database commit?** The engine reconstructs a candidate from the committed checkpoint and proposed update. The application must still authorize and commit that candidate under its transaction and concurrency rules; a candidate by itself is neither durable nor an acknowledgement. For rich text, commit-mode validation also prevents replacing or deleting existing position-history records. If another writer wins the race, the application must validate again against the newer state.

**Can a rich document have no root paragraph?** The generic reconstruction operation can inspect an uninitialized document, but a committed rich document must contain a valid root tree. Commit-mode validation rejects an update that deletes the final root node. The Java client independently rejects an uninitialized commit candidate, even if the worker returns one.

**Is validating position-history shape enough?** Not necessarily. The security review demonstrated that a fabricated, well-shaped record can change how the pinned binding resolves a relative position. Whether this leads to a distinct victim-facing security impact remains unverified; the focused browser validation was stopped by a cybersecurity safety restriction. The feature remains gated while this unresolved question is assessed, and the existing type, range and append-only checks should not be described as proof of record provenance.

**How do we know an offline edit reached the server?** A local editor can show its own pending draft even if the server has never accepted it. The production-shell test reloads while offline, reconnects, waits for server confirmation, then signs in from a separate browser profile and checks the content and formatting there. The separate profile does not have the first browser's IndexedDB draft.

**What happens at the current limits?** The engine rejects a candidate that exceeds its checkpoint, structure or position-history limits; the Java service translates a recognized size rejection to `DOCUMENT_LIMIT`. The editor explains that the draft was not saved to the server and keeps its local pending work. A synthetic boundary test accepts 64 position-history records and rejects a 65th; it does not establish a fixed number of user actions per record. These learning limits can still be reached during ordinary editing. Long-lived history requires further workload measurements, adversarial testing and a compatible compaction strategy; the current gate should not be treated as a production launch switch.

**Why is the worker response limit larger than the checkpoint limit?** The rich response contains both a Base64 checkpoint and a JSON representation of the document. Base64 expands binary data, and JSON may escape individual text characters into multiple bytes. A test document with a roughly 12 KiB checkpoint produced an 88 KiB response, so a 64 KiB response cap rejected it even though the checkpoint was allowed. The worker and Java client now bound rich responses at 192 KiB; plain-text responses remain bounded at 64 KiB. This response cap is separate from the 32 KiB rich checkpoint and 8 KiB individual update limits, and it remains a limit rather than a guarantee that every allowed tree has a small enough serialized response.

**Measured example:** With the former 8 KiB checkpoint cap, a fresh document containing `Capacity sample` accepted 11 successive heading-2 toggles and rejected the 12th. The rejected local state was 9,228 bytes and had 12 history records, 72 transfers and 240 expanded positions, pointing to the checkpoint cap. After separating the internal rich checkpoint limit (32 KiB) from the 8 KiB network update limit, the same test accepted 40 toggles; its local state was 29,118 bytes, with 40 records, 240 transfers and 800 expanded positions. This workload does not predict capacity for other documents. The browser integration test records `STRUCTURAL_EDIT_MEASUREMENT` for comparison.

## Primary references

- [TipTap collaboration extension](https://tiptap.dev/docs/editor/extensions/functionality/collaboration): configuration with a `Y.Doc` or named XML fragment.
- [TipTap collaborative editing guide](https://tiptap.dev/docs/hocuspocus/guides/collaborative-editing): collaboration binding and disabling the independent undo/redo extension.
- [Yjs XML fragment documentation](https://docs.yjs.dev/api/shared-types/y.xmlfragment): shared XML structure and change events.
