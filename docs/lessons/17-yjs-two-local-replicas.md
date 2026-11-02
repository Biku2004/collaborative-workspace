# Lesson 17 — Learn Yjs with two local replicas

## 1. The problem

Alice and Bob open the same document. Both type while disconnected. When they reconnect, we want their replicas to converge without simply replacing one person's text with the other's last saved copy.

This lesson is a **local experiment**: two independent `Y.Doc` instances exchange binary updates inside one Node process. The queue simulates disconnection and retransmission. There is no network, persistence, authorization, frontend or Spring integration here; those are later lessons.

## 2. Concepts first

- A **replica** is one local copy of shared state. Alice and Bob each have their own `Y.Doc` and `Y.Text`.
- A **CRDT** is a data type designed to reconcile changes from replicas under defined rules. Given the required updates, these Yjs replicas converge even if updates arrive out of order or more than once.
- A Yjs **update** is a `Uint8Array` of binary data. It encodes Yjs changes; it is not plain text or a complete proof that an edit is safe to accept.
- A **state vector** describes known insertion progress per Yjs client. It helps calculate missing changes, but does not fully represent deletion state and is not a content checksum.
- **Convergence** means replicas reach the same state after receiving all required updates. It does not promise that the result matches every user's intent.

Yjs documents its updates as commutative, associative and idempotent: see the [official update API](https://docs.yjs.dev/api/document-updates). The ordering of simultaneous insertions is determined by CRDT identities; do not assume Alice's text always comes first.

## 3. Files and flow

The complete runnable code is in `experiments/yjs-two-replicas`:

1. `package.json` pins Yjs, TypeScript and Node types. `package-lock.json` fixes the dependency tree.
2. `src/replica.ts` creates a `Y.Doc`, obtains `getText('content')`, captures local `update` events, and applies incoming bytes with `Y.applyUpdate`. The `REMOTE` origin prevents received changes from being added back to this toy outgoing queue. A real multi-peer provider needs an explicit forwarding strategy; this origin filter alone is not a general broadcast protocol.
3. `src/scenarios.ts` contains seven small experiments. Each creates two independent replicas and destroys them afterward.
4. `src/demo.ts` prints the results. `test/scenarios.test.ts` checks the behavior using Node's test runner.
5. `tsconfig.json` enables strict TypeScript checks. `dist` and `node_modules` are generated and ignored by Git.

```text
Alice: local Y.Text edit -> update bytes -> simulated queue
                                              |
Bob:   Y.applyUpdate <- deliver bytes ---------+
```

`drain()` returns and clears the locally generated updates. `deliver()` passes those exact bytes to another replica. The lab intentionally holds, duplicates and reorders them. Both replicas must receive the same initial CRDT history: writing identical visible text independently creates distinct operations.

## 4. Run it on Windows

Node.js 22.17 or newer is required. In PowerShell:

```powershell
cd C:\Users\lenovo\Downloads\collaborate_workspace\experiments\yjs-two-replicas
npm ci
npm test
npm run demo
```

`npm ci` installs the locked dependencies; `npm test` compiles TypeScript and runs the seven tests; `npm run demo` compiles and prints the examples. No PostgreSQL, Docker or running Java server is needed. Yjs client IDs, update sizes, and the order of simultaneous inserts can vary between runs.

## 5. Follow each experiment

### Concurrent inserts and duplicate delivery

Alice seeds `Hello ` once and sends that update to Bob. Alice inserts `A`, Bob inserts `B`, and neither sees the other's edit until delivery. Before synchronization they see `Hello A` and `Hello B`. After exchanging updates, both see either `Hello AB` or `Hello BA`. The test accepts both outcomes and requires equality. Delivering the exact same updates again does not add extra letters. `echoPackets` is zero because this toy harness tags incoming updates with the remote origin.

### Concurrent insert and delete

Starting from `abc`, Alice deletes `b` while Bob inserts `X` after `b`. Before synchronization they see `ac` and `abXc`; afterward both see `aXc`. Alice's deletion covered the `b` she had seen, not Bob's unseen insertion. This is a concrete example of convergence, not a guarantee that the final wording matches the users' intentions.

### A dependency arrives late

Alice inserts `A`, then `B` after it. The lab delivers the second update first. Bob still sees an empty string, even after that second update is repeated; when the first update arrives, Bob sees `AB`. Successfully applying a binary update does not necessarily mean all its intended visible content has materialized. A future server must handle unresolved dependencies deliberately instead of treating decoding as durable acceptance.

### A deletion arrives before its insertion

Alice inserts `abc`, then deletes `b`. Bob receives the deletion before the insertion. He initially sees an empty string; after the insertion arrives, both see `ac`. Repeated delivery does not restore the deleted character. Missing dependencies must eventually arrive for the replicas to converge.

### Equal state vectors can hide a deletion

Both replicas first receive `abc`. Alice deletes `b`, but the deletion update is withheld. Alice sees `ac`; Bob sees `abc`. Their decoded state vectors are equal because this deletion does not advance the insertion progress tracked by the vector. The lab calls `Y.encodeStateAsUpdate(alice.doc, Y.encodeStateVector(bob.doc))` and applies the result to Bob. It includes deletion information and repairs the difference. Do not skip synchronization merely because vectors compare equal.

### UTF-16 positions

`A😀B` contains three Unicode code points but four UTF-16 code units. The emoji occupies two units, so `delete(1, 2)` removes it and leaves `AB`. A combining sequence such as `e` plus a combining accent can be one visible grapheme while occupying two code points and two UTF-16 units. Avoid splitting surrogate pairs or assuming a user-perceived character has length one. The [Y.Text API](https://docs.yjs.dev/api/shared-types/y.text) defines length in UTF-16 units.

### Why independent seeding duplicates text

If Alice and Bob each separately insert `Hello` into empty documents, they have created two independent sets of CRDT operations. After exchange both see `HelloHello`. Initialize once and exchange the resulting state/update, rather than recreating the same plain string independently.

## 6. Interview questions and answers

**What makes these edits concurrent?** Neither replica has received the other's edit when it makes its own. The edits can happen at different wall-clock times and still be concurrent from the replicas' perspective.

**Why doesn't receiving an update twice duplicate its edit?** Yjs associates inserted structures with CRDT identities. Applying the same encoded update again is idempotent. This says nothing about two distinct user submissions that happen to contain identical text.

**Does convergence preserve intent?** No. The data structure produces a consistent result from its rules. It cannot infer whether a user wanted to delete a word including another person's unseen insertion, or whether two overlapping edits make sense together.

**Why is a state vector not a content checksum?** It records insertion progress rather than a full representation of deletions. The equal-vector test shows different visible strings and then repairs the missing deletion using a Yjs differential update.

**Why can an update be accepted by `applyUpdate` without visible text appearing?** Some information it depends on may not have arrived yet. This experiment observes that behavior for two simple updates; it is not a general server-side validation algorithm.

**How does a CRDT differ from operational transformation?** Both can support collaborative editing. CRDTs encode identities and reconciliation rules into their data model; OT transforms operations relative to concurrent operations and commonly relies on an agreed ordering or coordination scheme. Actual implementations differ, and neither approach alone supplies authorization, persistence, recovery, or a good editing experience.

**What if an update is permanently lost?** Replicas may remain incomplete or divergent. A real system needs durable storage, retry, synchronization and recovery. This lab's in-memory arrays disappear on process exit.

**Can Java read these bytes as a text patch?** No. Yjs update bytes encode Yjs structures, not a generic UTF-8 patch. The next lesson investigates a private Yjs-compatible processing boundary and validates its behavior.

## 7. Common mistakes and exercise

- Running `node src/demo.ts` directly bypasses the configured TypeScript build. Use `npm run demo`.
- Comparing visible text alone does not prove equal CRDT history; the independent-seeding example demonstrates why.
- Comparing state vectors alone does not establish equal content.
- Treating remote-origin filtering as a complete multi-client transport can prevent needed forwarding.
- Expecting the same `Hello AB` order on every run makes a flaky test; both valid orders converge.

Exercise: change Bob's concurrent insertion to `Z`, run the tests, and update the expected values deliberately. Then restore the code and rerun the suite. The next step is [Lesson 18 — Prototype the private Yjs document engine](../07-next-lessons-roadmap.md).
