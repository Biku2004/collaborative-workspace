import * as Y from 'yjs';
import { deliver, vectorEntries, withReplicas } from './replica.js';

export function concurrentInserts() {
  return withReplicas((alice, bob) => {
    // Seed ONCE, then replicate the CRDT identities as well as the visible text.
    alice.text.insert(0, 'Hello ');
    deliver(alice.drain(), bob);
    alice.text.insert(6, 'A');
    bob.text.insert(6, 'B');
    const before = [alice.text.toString(), bob.text.toString()];
    const fromAlice = alice.drain();
    const fromBob = bob.drain();
    deliver(fromAlice, bob);
    deliver(fromBob, alice);
    const once = [alice.text.toString(), bob.text.toString()];
    // Simulate retransmissions of the exact same CRDT operations.
    deliver(fromAlice, bob);
    deliver(fromBob, alice);
    return { before, once, afterDuplicates: [alice.text.toString(), bob.text.toString()],
      vectors: [vectorEntries(alice.doc), vectorEntries(bob.doc)],
      byteLengths: [...fromAlice, ...fromBob].map(update => update.byteLength),
      echoPackets: alice.drain().length + bob.drain().length };
  });
}

export function concurrentInsertAndDelete() {
  return withReplicas((alice, bob) => {
    alice.text.insert(0, 'abc');
    deliver(alice.drain(), bob);
    alice.text.delete(1, 1); // Alice removes b, without seeing Bob's X.
    bob.text.insert(2, 'X'); // Bob inserts between b and c while offline.
    const before = [alice.text.toString(), bob.text.toString()];
    const fromAlice = alice.drain();
    const fromBob = bob.drain();
    deliver(fromBob, alice);
    deliver(fromAlice, bob);
    return { before, after: [alice.text.toString(), bob.text.toString()] };
  });
}

export function dependentUpdatesOutOfOrder() {
  return withReplicas((alice, bob) => {
    alice.text.insert(0, 'A');
    const first = alice.drain();
    alice.text.insert(1, 'B');
    const second = alice.drain();
    deliver(second, bob); // B refers to structures in the still-missing first update.
    const waitingForDependency = bob.text.toString();
    deliver(second, bob); // Duplicate delivery does not invent the missing A.
    const afterDuplicate = bob.text.toString();
    deliver(first, bob);
    return { waitingForDependency, afterDuplicate, after: [alice.text.toString(), bob.text.toString()] };
  });
}

export function deleteBeforeInsert() {
  return withReplicas((alice, bob) => {
    alice.text.insert(0, 'abc');
    const insertion = alice.drain();
    alice.text.delete(1, 1);
    const deletion = alice.drain();
    deliver(deletion, bob);
    const beforeInsertion = bob.text.toString();
    deliver(insertion, bob);
    deliver(insertion, bob);
    deliver(deletion, bob);
    return { beforeInsertion, after: [alice.text.toString(), bob.text.toString()] };
  });
}

export function equalVectorsWithMissingDeletion() {
  return withReplicas((alice, bob) => {
    alice.text.insert(0, 'abc');
    deliver(alice.drain(), bob);
    const beforeVector = vectorEntries(alice.doc);
    alice.text.delete(1, 1); // Do not deliver this event: simulate packet loss.
    alice.drain();
    const beforeRepair = [alice.text.toString(), bob.text.toString()];
    const vectors = [vectorEntries(alice.doc), vectorEntries(bob.doc)];
    // Even equal vectors must not short-circuit synchronization of deletion data.
    const repair = Y.encodeStateAsUpdate(alice.doc, Y.encodeStateVector(bob.doc));
    bob.receive(repair);
    return { beforeVector, vectors, beforeRepair, repairBytes: repair.byteLength,
      after: [alice.text.toString(), bob.text.toString()] };
  });
}

export function utf16Positions() {
  return withReplicas((alice, bob) => {
    alice.text.insert(0, 'A😀B');
    deliver(alice.drain(), bob);
    const before = { text: alice.text.toString(), utf16Units: alice.text.length,
      codePoints: [...alice.text.toString()].length };
    alice.text.delete(1, 2); // Remove both UTF-16 units of the emoji.
    deliver(alice.drain(), bob);
    return { before, after: [alice.text.toString(), bob.text.toString()],
      combiningExample: { text: 'e\u0301', utf16Units: 'e\u0301'.length, codePoints: [...'e\u0301'].length } };
  });
}

export function independentlySeededText() {
  return withReplicas((alice, bob) => {
    alice.text.insert(0, 'Hello');
    bob.text.insert(0, 'Hello'); // Same visible text, different CRDT operations.
    const fromAlice = alice.drain();
    const fromBob = bob.drain();
    deliver(fromAlice, bob);
    deliver(fromBob, alice);
    return [alice.text.toString(), bob.text.toString()];
  });
}
