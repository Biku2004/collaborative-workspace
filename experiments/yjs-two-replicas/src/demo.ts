import { concurrentInserts, concurrentInsertAndDelete, dependentUpdatesOutOfOrder,
  deleteBeforeInsert, equalVectorsWithMissingDeletion, utf16Positions, independentlySeededText } from './scenarios.js';

for (const [title, run] of [
  ['Concurrent inserts + duplicate delivery', concurrentInserts],
  ['Concurrent insert and delete', concurrentInsertAndDelete],
  ['Dependent update delivered first', dependentUpdatesOutOfOrder],
  ['Deletion delivered before insertion', deleteBeforeInsert],
  ['Equal vectors, missing deletion, then repair', equalVectorsWithMissingDeletion],
  ['UTF-16 positions', utf16Positions],
  ['Independent seeding duplicates content', independentlySeededText],
] as const) {
  console.log(`\n${title}`);
  console.log(JSON.stringify(run(), null, 2));
}
