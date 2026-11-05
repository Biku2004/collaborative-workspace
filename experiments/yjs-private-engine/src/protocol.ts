import contract from '@workspace/rich-text-schema-v1/contract.json' with { type: 'json' };

export const LIMITS = Object.freeze({ bodyBytes: 65536, richBodyBytes: 131072, updates: 16, updateBytes: 8192, totalBytes: 32768, richCheckpointBytes: 65536,
  outputBytes: 65536, richOutputBytes: 262144, textUnits: 16384, activeWorkers: 2, deadlineMs: 3000 });

export class InputError extends Error {
  constructor(readonly code: string) { super(code); }
}

export function parseRequest(value: unknown, rich = false): Uint8Array[] {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new InputError('INVALID_REQUEST');
  const request = value as Record<string, unknown>;
  if (Object.keys(request).some(key => key !== 'version' && key !== 'updates') || request.version !== 1 ||
      !Array.isArray(request.updates) || request.updates.length > LIMITS.updates) throw new InputError('INVALID_REQUEST');
  let total = 0;
  return request.updates.map((encoded: unknown, index: number) => {
    const maximum = rich && index === 0 ? LIMITS.richCheckpointBytes : LIMITS.updateBytes;
    if (typeof encoded !== 'string' || encoded.length > Math.ceil(maximum / 3) * 4 ||
        !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(encoded)) throw new InputError('INVALID_UPDATE');
    const bytes = Buffer.from(encoded, 'base64');
    if (!bytes.length || bytes.length > maximum || bytes.toString('base64') !== encoded) throw new InputError('INVALID_UPDATE');
    total += bytes.length;
    if (total > (rich ? LIMITS.richCheckpointBytes + LIMITS.updateBytes : LIMITS.totalBytes)) throw new InputError('TOO_LARGE');
    return bytes;
  });
}

/** Version 2 is private and explicitly identifies the experimental rich schema. */
export function parseRichRequest(value: unknown): { updates: Uint8Array[]; commit: boolean } {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new InputError('INVALID_REQUEST');
  const request = value as Record<string, unknown>;
  if (Object.keys(request).some(key => key !== 'version' && key !== 'format' && key !== 'updates' && key !== 'mode') ||
      request.version !== 2 || request.format !== contract.format ||
      (request.mode !== undefined && request.mode !== 'commit')) throw new InputError('INVALID_REQUEST');
  const updates = parseRequest({ version: 1, updates: request.updates }, true);
  const commit = request.mode === 'commit';
  if (commit && updates.length !== 2) throw new InputError('INVALID_REQUEST');
  return { updates, commit };
}
