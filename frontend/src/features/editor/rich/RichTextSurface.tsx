import { useEffect } from 'react';
import { EditorContent, useEditor, useEditorState } from '@tiptap/react';
import Collaboration from '@tiptap/extension-collaboration';
import StarterKit, { type StarterKitOptions } from '@tiptap/starter-kit';
import contract from '@workspace/rich-text-schema-v1/contract.json';
import type * as Y from 'yjs';
import { richPresence } from './richPresence';
import type { DocumentProvider } from '../provider';

type Props = { document: Y.Doc; readOnly: boolean; provider?: Pick<DocumentProvider, 'getPresence' | 'observePresence' | 'setPresence'> };
const attributes = (readOnly: boolean) => ({ role: 'textbox', 'aria-label': 'Document text', 'aria-multiline': 'true', 'aria-readonly': String(readOnly) });

/** The caller must supply a synchronized, initialized document of the negotiated schema. */
export default function RichTextSurface({ document, readOnly, provider }: Props) {
  const editor = useEditor({
    extensions: [StarterKit.configure(contract.starterKit as Partial<StarterKitOptions>),
      Collaboration.configure({ document, field: contract.root }), ...(provider ? [richPresence(provider)] : [])],
    editable: !readOnly,
    editorProps: { attributes: attributes(readOnly) },
  }, [document, provider]);
  const active = useEditorState({ editor, selector: ({ editor: instance }) => instance ? {
    bold: instance.isActive('bold'), italic: instance.isActive('italic'),
    heading1: instance.isActive('heading', { level: 1 }), heading2: instance.isActive('heading', { level: 2 }),
    heading3: instance.isActive('heading', { level: 3 }), bullet: instance.isActive('bulletList'), ordered: instance.isActive('orderedList'),
  } : null });
  useEffect(() => {
    editor?.setOptions({ editable: !readOnly, editorProps: { attributes: attributes(readOnly) } });
  }, [editor, readOnly]);
  const disabled = !editor || readOnly;
  return <div className="rich-editor">
    <div role="toolbar" aria-label="Text formatting" className="rich-toolbar" onMouseDown={event => event.preventDefault()}>
      <button type="button" disabled={disabled} aria-pressed={active?.bold ?? false} onClick={() => editor?.chain().focus().toggleBold().run()}>Bold</button>
      <button type="button" disabled={disabled} aria-pressed={active?.italic ?? false} onClick={() => editor?.chain().focus().toggleItalic().run()}>Italic</button>
      <button type="button" disabled={disabled} aria-pressed={active?.heading1 ?? false} onClick={() => editor?.chain().focus().toggleHeading({ level: 1 }).run()}>Heading 1</button>
      <button type="button" disabled={disabled} aria-pressed={active?.heading2 ?? false} onClick={() => editor?.chain().focus().toggleHeading({ level: 2 }).run()}>Heading 2</button>
      <button type="button" disabled={disabled} aria-pressed={active?.heading3 ?? false} onClick={() => editor?.chain().focus().toggleHeading({ level: 3 }).run()}>Heading 3</button>
      <button type="button" disabled={disabled} aria-pressed={active?.bullet ?? false} onClick={() => editor?.chain().focus().toggleBulletList().run()}>Bullet list</button>
      <button type="button" disabled={disabled} aria-pressed={active?.ordered ?? false} onClick={() => editor?.chain().focus().toggleOrderedList().run()}>Numbered list</button>
    </div>
    <EditorContent editor={editor} />
  </div>;
}
