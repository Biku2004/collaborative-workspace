import { Extension } from '@tiptap/core';
import { Plugin } from '@tiptap/pm/state';
import { Decoration, DecorationSet } from '@tiptap/pm/view';
import { absolutePositionToRelativePosition, relativePositionToAbsolutePosition, ySyncPluginKey } from '@tiptap/y-tiptap';
import type { DocumentProvider } from '../provider';

/** Cursor data never enters the Yjs document or its durable update queue. */
export function richPresence(provider: Pick<DocumentProvider, 'getPresence' | 'observePresence' | 'setPresence'>) {
  return Extension.create({
    name: 'workspacePresence',
    addProseMirrorPlugins() {
      return [new Plugin({
        props: {
          decorations(state) {
            const sync = ySyncPluginKey.getState(state);
            if (!sync?.binding || sync.binding.mapping.size === 0) return DecorationSet.empty;
            const decorations: Decoration[] = [];
            for (const presence of provider.getPresence()) {
              try {
                const anchor = relativePositionToAbsolutePosition(sync.doc, sync.type, presence.anchor, sync.binding.mapping);
                const head = relativePositionToAbsolutePosition(sync.doc, sync.type, presence.head, sync.binding.mapping);
                if (anchor === null || head === null || anchor < 0 || head < 0 || anchor > state.doc.content.size || head > state.doc.content.size) continue;
                if (anchor !== head) decorations.push(Decoration.inline(Math.min(anchor, head), Math.max(anchor, head), { class: 'remote-selection' }));
                decorations.push(Decoration.widget(head, () => {
                  const marker = document.createElement('span');
                  marker.className = 'remote-cursor'; marker.dataset.presenceUser = presence.userId;
                  marker.dataset.label = presence.email;
                  marker.setAttribute('aria-label', `Cursor for ${presence.email}`);
                  return marker;
                }, { key: presence.presenceId, side: 1 }));
              } catch { /* Invalid or not-yet-resolvable remote positions are ignored. */ }
            }
            return DecorationSet.create(state.doc, decorations);
          },
        },
        view(view) {
          let published = false;
          const publish = () => {
            if (!view.hasFocus() && published) return;
            const sync = ySyncPluginKey.getState(view.state);
            if (!sync?.binding || !sync.binding.mapping.size) return;
            try {
              provider.setPresence(absolutePositionToRelativePosition(view.state.selection.anchor, sync.type, sync.binding.mapping),
                absolutePositionToRelativePosition(view.state.selection.head, sync.type, sync.binding.mapping));
              published = true;
            } catch { /* The editor may still be mapping an incoming transaction. */ }
          };
          let subscribed = false, pending = false;
          let frame: number | undefined;
          const schedule = () => {
            if (!pending || frame !== undefined || view.isDestroyed) return;
            frame = requestAnimationFrame(() => {
              frame = undefined;
              if (!pending || view.isDestroyed || view.composing) return;
              // A queued task can still beat selectionchange. Decorations must not write
              // an old model selection back into the DOM while native movement is pending.
              if (view.hasFocus()) {
                const selection = view.dom.ownerDocument.getSelection();
                if (!selection?.anchorNode || !selection.focusNode || !view.dom.contains(selection.anchorNode) || !view.dom.contains(selection.focusNode)) return;
                try {
                  if (view.posAtDOM(selection.anchorNode, selection.anchorOffset) !== view.state.selection.anchor ||
                      view.posAtDOM(selection.focusNode, selection.focusOffset) !== view.state.selection.head) return;
                } catch { return; }
              }
              pending = false;
              view.dispatch(view.state.tr.setMeta('workspacePresence', true));
            });
          };
          const unsubscribe = provider.observePresence(() => {
            if (subscribed && !view.isDestroyed) { pending = true; schedule(); }
          });
          subscribed = true;
          queueMicrotask(() => { if (!view.isDestroyed) publish(); });
          const focus = () => { publish(); schedule(); };
          view.dom.addEventListener('focus', focus);
          view.dom.addEventListener('compositionend', schedule);
          view.dom.ownerDocument.addEventListener('selectionchange', schedule);
          return { update() { publish(); schedule(); }, destroy() {
            unsubscribe(); if (frame !== undefined) cancelAnimationFrame(frame);
            view.dom.removeEventListener('focus', focus); view.dom.removeEventListener('compositionend', schedule);
            view.dom.ownerDocument.removeEventListener('selectionchange', schedule);
          } };
        },
      })];
    },
  });
}
