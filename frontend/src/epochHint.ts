/** Navigation hints only. Server authorization and epoch checks remain authoritative. */
export function epochHintKey(user: string, workspace: string, document: string, format: string): string {
  return `workspace-epoch:${JSON.stringify([user, workspace, document, format])}`;
}
export function readEpochHint(key: string): { epoch: number; previous?: number } {
  try {
    const value = JSON.parse(localStorage.getItem(key) ?? 'null') as { epoch?: number; previous?: number } | null;
    if (!value || !Number.isSafeInteger(value.epoch) || value.epoch! < 0) return { epoch: 0 };
    return { epoch: value.epoch!, previous: Number.isSafeInteger(value.previous) && value.previous! >= 0 && value.previous! < value.epoch! ? value.previous : undefined };
  } catch { return { epoch: 0 }; }
}
export function saveEpochHint(key: string, epoch: number, previous: number): void {
  // Do not switch editors if the navigation/recovery hint cannot be retained.
  localStorage.setItem(key, JSON.stringify({ epoch, previous }));
}
