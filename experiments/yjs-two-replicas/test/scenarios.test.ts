import assert from 'node:assert/strict';
import test from 'node:test';
import { concurrentInserts, concurrentInsertAndDelete, dependentUpdatesOutOfOrder,
  deleteBeforeInsert, equalVectorsWithMissingDeletion, utf16Positions, independentlySeededText } from '../src/scenarios.js';

test('concurrent inserts converge; duplicate packets do not duplicate edits or echo', () => {
  const result = concurrentInserts();
  assert.deepEqual(result.before, ['Hello A', 'Hello B']);
  assert.equal(result.once[0], result.once[1]);
  assert.match(result.once[0]!, /^Hello (AB|BA)$/);
  assert.deepEqual(result.afterDuplicates, result.once);
  assert.deepEqual(result.vectors[0], result.vectors[1]);
  assert.equal(result.byteLengths.length, 2);
  assert.ok(result.byteLengths.every(size => size > 0));
  assert.equal(result.echoPackets, 0);
});

test('a concurrent insertion unseen by a deletion survives', () => {
  const result = concurrentInsertAndDelete();
  assert.deepEqual(result.before, ['ac', 'abXc']);
  assert.deepEqual(result.after, ['aXc', 'aXc']);
});

test('out-of-order dependent update waits until the missing insertion arrives', () => {
  const result = dependentUpdatesOutOfOrder();
  assert.equal(result.waitingForDependency, '');
  assert.equal(result.afterDuplicate, '');
  assert.deepEqual(result.after, ['AB', 'AB']);
});

test('deletion delivered first takes effect when its insertion arrives', () => {
  const result = deleteBeforeInsert();
  assert.equal(result.beforeInsertion, '');
  assert.deepEqual(result.after, ['ac', 'ac']);
});

test('equal vectors do not prove equal content; differential sync repairs a missed deletion', () => {
  const result = equalVectorsWithMissingDeletion();
  assert.deepEqual(result.beforeRepair, ['ac', 'abc']);
  assert.deepEqual(result.beforeVector, result.vectors[0]);
  assert.deepEqual(result.vectors[0], result.vectors[1]);
  assert.ok(result.repairBytes > 0);
  assert.deepEqual(result.after, ['ac', 'ac']);
});

test('Y.Text offsets use UTF-16 units, not code points or grapheme clusters', () => {
  const result = utf16Positions();
  assert.deepEqual(result.before, { text: 'A😀B', utf16Units: 4, codePoints: 3 });
  assert.deepEqual(result.after, ['AB', 'AB']);
  assert.equal(result.combiningExample.utf16Units, 2);
  assert.equal(result.combiningExample.codePoints, 2);
  assert.equal([...new Intl.Segmenter('en', { granularity: 'grapheme' }).segment(result.combiningExample.text)].length, 1);
});

test('independently recreating equal visible text does not share CRDT history', () => {
  assert.deepEqual(independentlySeededText(), ['HelloHello', 'HelloHello']);
});
