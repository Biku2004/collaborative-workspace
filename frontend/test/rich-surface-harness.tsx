import { StrictMode, useState } from 'react';
import { createRoot } from 'react-dom/client';
import * as Y from 'yjs';
import contract from '@workspace/rich-text-schema-v1/contract.json';
import RichTextSurface from '../src/features/editor/rich/RichTextSurface';

/** Test-only in-memory transport; the end-to-end server test is a separate gate. */
export function mountRichSurfaceHarness(element: HTMLElement) {
  const left = new Y.Doc(), right = new Y.Doc();
  const initial = Uint8Array.from(atob(contract.initialUpdate), char => char.charCodeAt(0));
  Y.applyUpdate(left, initial); Y.applyUpdate(right, initial);
  const remote = Symbol('test-remote');
  left.on('update', (update: Uint8Array, origin: unknown) => { if (origin !== remote) Y.applyUpdate(right, update, remote); });
  right.on('update', (update: Uint8Array, origin: unknown) => { if (origin !== remote) Y.applyUpdate(left, update, remote); });
  function Harness() {
    const [viewer, setViewer] = useState(false);
    return <><button type="button" onClick={() => setViewer(value => !value)}>Toggle viewer</button>
      <section data-testid="left"><RichTextSurface document={left} readOnly={false} /></section>
      <section data-testid="right"><RichTextSurface document={right} readOnly={viewer} /></section></>;
  }
  const root = createRoot(element); root.render(<StrictMode><Harness /></StrictMode>);
  return () => { root.unmount(); left.destroy(); right.destroy(); };
}
