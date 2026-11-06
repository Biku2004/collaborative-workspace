# Lesson 33 — Search authorized committed content (in progress)

Search will be a derived projection of committed state. A successful document update must not depend on the indexer, and an index row must never grant access. The search query must join current workspace membership and active document metadata even when its stored text is stale.

The first slice adds bounded, read-only engine materialization for projection work. Plain text uses the existing reconstructed candidate. Rich text reuses the existing response/schema boundary, requires an initialized document and extracts literal text from the allowed paragraph, heading and list structures. Marks do not add searchable syntax. Block boundaries become newlines; HTML-looking text remains literal text. The extractor bounds depth, node count and output size and never mutates the source document.

The remaining work is the PostgreSQL projection and index, asynchronous bounded indexing, current-access search queries, rebuild/failure behavior, browser search and freshness display, race/access tests and the final review. This lesson is not complete and no search endpoint is exposed by the materialization slice. Existing rich-text and private-engine limitations still apply.

The materialization foundation passed **17 focused tests**: real engine/database history flows, bounded extraction (headings, both list types, bold/italic, literal markup, invalid shapes and depth/output limits), and private-client envelope checks. The real rich checkpoint round-trips with the expected text and identical state bytes. These tests do not prove asynchronous indexing or search access control; those still require the projection implementation and its own verification.
