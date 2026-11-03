import { parentPort, workerData } from 'node:worker_threads';
import { reconstruct } from './reconstruct.js';
import { InputError } from './protocol.js';
import contract from '@workspace/rich-text-schema-v1/contract.json' with { type: 'json' };

try {
  if (Array.isArray(workerData)) parentPort!.postMessage({ ok: true, result: reconstruct(workerData as Uint8Array[]) });
  else {
    const input = workerData as { format: string; updates: Uint8Array[] };
    if (input.format !== contract.format) throw new InputError('INVALID_REQUEST');
    const { reconstructRichCandidate } = await import('./richSchema.js');
    const candidate = reconstructRichCandidate(input.updates);
    parentPort!.postMessage({ ok: true, result: { version: 2, status: 'candidate', format: input.format,
      initialized: candidate.initialized, document: candidate.json ?? null, checkpoint: Buffer.from(candidate.checkpoint).toString('base64') } });
  }
} catch (error) {
  parentPort!.postMessage({ ok: false, code: error instanceof InputError ? error.code : 'ENGINE_FAILURE' });
}
