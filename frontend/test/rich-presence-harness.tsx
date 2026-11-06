import { createRoot } from 'react-dom/client';
import * as Y from 'yjs';
import contract from '@workspace/rich-text-schema-v1/contract.json';
import RichTextSurface from '../src/RichTextSurface';
import type { Presence } from '../src/provider';

let notify: (() => void) | undefined;

export function mount(element: HTMLElement) {
  const doc = new Y.Doc();
  Y.applyUpdate(doc, Uint8Array.from(atob(contract.initialUpdate), char => char.charCodeAt(0)));
  let peers: Presence[] = [];
  const listeners = new Set<(peers: Presence[]) => void>();
  const provider = {
    getPresence: () => peers,
    observePresence(listener: (peers: Presence[]) => void) { listeners.add(listener); listener(peers); return () => { listeners.delete(listener); }; },
    setPresence() {},
  };
  notify = () => {
    const position = Y.createRelativePositionFromTypeIndex(doc.getXmlFragment(contract.root), 0);
    peers = [{ presenceId: 'remote', userId: 'remote', email: 'remote@example.com', anchor: position, head: position, seen: performance.now() }];
    for (const listener of listeners) listener(peers);
  };
  createRoot(element).render(<RichTextSurface document={doc} readOnly={false} provider={provider} />);
}

/** A native selection moves before its asynchronous selectionchange notification. */
export function insertAtNativeEnd(element: HTMLElement) {
  const editor = element.querySelector('[contenteditable="true"]')!;
  const range = document.createRange(); range.selectNodeContents(editor); range.collapse(false);
  const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range);
  notify?.();
  document.execCommand('insertText', false, '!');
}

/** Model browser selectionchange arriving later than a queued presence transaction. */
export async function insertAtDelayedNativeEnd(element: HTMLElement) {
  const block = (event: Event) => event.stopImmediatePropagation();
  document.addEventListener('selectionchange', block, true);
  try {
    const editor = element.querySelector('[contenteditable="true"]')!;
    const range = document.createRange(); range.selectNodeContents(editor); range.collapse(false);
    const selection = window.getSelection()!; selection.removeAllRanges(); selection.addRange(range);
    notify?.();
    await new Promise(resolve => setTimeout(resolve, 40));
    document.execCommand('insertText', false, '!');
  } finally {
    document.removeEventListener('selectionchange', block, true);
    document.dispatchEvent(new Event('selectionchange'));
  }
}
