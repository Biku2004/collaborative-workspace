# Security and quality checkpoint — Lessons 22–24

This is a focused review of the local delivery, browser and plain-text editor changes, not a production certification or an exhaustive security scan. The earlier [Lessons 19–21 checkpoint](10-security-quality-checkpoint.md) records the state at that time; its statements that relay and a browser do not exist are historical.

## Evidence

- Replay reads an authorized document using a consistent snapshot, requires consecutive sequence numbers and limits each page; clients track a contiguous cursor. An outbox relay may send duplicates and the periodic head notice triggers repair after a missed notification. A crash fixture exercises a commit followed by process termination before relay.
- The local browser uses the existing server session and CSRF checks through the development proxy. Tests use isolated users and exercise revocation, pagination, account isolation, logout, and HTML-looking titles. React renders document names as text.
- Independent Chromium contexts exercise concurrent edits through the real server and PostgreSQL. A test-only transport barrier ensures both replicas edit before their submissions are released. A disconnected pending edit remains visible in its tab and navigation warns.
- The client validates protocol envelopes, replay ordering and size bounds, suppresses remote update echo, and becomes read-only on failure rather than treating an unacknowledged edit as saved. The server still authorizes writes and delivery; browser controls are not a security boundary.

## Remaining risks

1. **Production blocker:** The custom provider and Node engine only support this bounded plain-text experiment. Full provider compatibility, rich-text schemas, and comprehensive malicious-input validation remain open. The unauthenticated engine must remain isolated; loopback is not a strong local-process security boundary.
2. **Production blocker:** Pending work is memory-only. A reload, browser crash or storage failure can lose unsaved text. There is no automatic reconnect or durable retry. A `SAVED` acknowledgement means committed, not delivered to other clients.
3. **High:** Rooms, session guards and relay assumptions are single-instance; the outbox column marks an attempted enqueue, not successful delivery. Distributed deployments require a different coordination and delivery design. In-flight messages cannot be recalled after revocation.
4. **High:** The 8 KiB checkpoint limit, bounded queue, temporary relay polling and untested slow-reader behavior constrain the prototype. Long-term compaction, outbox retention, capacity testing and operational monitoring are not complete.
5. **Medium:** The plain textarea does not preserve selection across remote edits. Browser storage and same-device account isolation need explicit design and testing before offline support.

Build and audit the frontend, build and test the private engine, and run the backend suite with dedicated test database, engine and browser test flags enabled. Test logs may contain intentionally injected failure messages; assess them in context.
