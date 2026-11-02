import { parentPort, workerData } from 'node:worker_threads';
import { reconstruct } from './reconstruct.js';
import { InputError } from './protocol.js';

try {
  parentPort!.postMessage({ ok: true, result: reconstruct(workerData as Uint8Array[]) });
} catch (error) {
  parentPort!.postMessage({ ok: false, code: error instanceof InputError ? error.code : 'ENGINE_FAILURE' });
}
