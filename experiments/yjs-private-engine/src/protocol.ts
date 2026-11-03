import contract from '@workspace/rich-text-schema-v1/contract.json' with { type: 'json' };

export const LIMITS = Object.freeze({ bodyBytes: 65536, updates: 16, updateBytes: 8192, totalBytes: 32768,
  outputBytes: 65536, textUnits: 16384, activeWorkers: 2, deadlineMs: 3000 });

export class InputError extends Error {
  constructor(readonly code: string) { super(code); }
}

export function parseRequest(value: unknown): Uint8Array[] {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new InputError('INVALID_REQUEST');
  const request = value as Record<string, unknown>;
  if (Object.keys(request).some(key => key !== 'version' && key !== 'updates') || request.version !== 1 ||
      !Array.isArray(request.updates) || request.updates.length > LIMITS.updates) throw new InputError('INVALID_REQUEST');
  let total = 0;
  return request.updates.map((encoded: unknown) => {
    if (typeof encoded !== 'string' || encoded.length > Math.ceil(LIMITS.updateBytes / 3) * 4 ||
        !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(encoded)) throw new InputError('INVALID_UPDATE');
    const bytes = Buffer.from(encoded, 'base64');
    if (!bytes.length || bytes.length > LIMITS.updateBytes || bytes.toString('base64') !== encoded) throw new InputError('INVALID_UPDATE');
    total += bytes.length;
    if (total > LIMITS.totalBytes) throw new InputError('TOO_LARGE');
    return bytes;
  });
}

/** Version 2 is private and explicitly identifies the experimental rich schema. */
export function parseRichRequest(value: unknown): Uint8Array[] {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw new InputError('INVALID_REQUEST');
  const request = value as Record<string, unknown>;
  if (Object.keys(request).some(key => key !== 'version' && key !== 'format' && key !== 'updates') ||
      request.version !== 2 || request.format !== contract.format) throw new InputError('INVALID_REQUEST');
  return parseRequest({ version: 1, updates: request.updates });
}
