/** Check size and digest before giving a server snapshot to Yjs. */
export async function verifiedState(checkpoint: string, sha256: string, format: string): Promise<Uint8Array> {
  if (format !== 'PLAIN_TEXT_V1' && format !== 'RICH_TEXT_V1') throw new Error('Unsupported format');
  const maximum = format === 'PLAIN_TEXT_V1' ? 8192 : 65536;
  if (typeof checkpoint !== 'string' || checkpoint.length > Math.ceil(maximum / 3) * 4 || typeof sha256 !== 'string' || sha256.length !== 44) throw new Error('Invalid state');
  const binary = atob(checkpoint);
  if (!binary.length || binary.length > maximum || btoa(binary) !== checkpoint) throw new Error('Invalid state');
  const bytes = Uint8Array.from(binary, char => char.charCodeAt(0));
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  let encoded = ''; for (const byte of digest) encoded += String.fromCharCode(byte);
  if (btoa(encoded) !== sha256) throw new Error('State checksum failed');
  return bytes;
}
