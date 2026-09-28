import { useEffect, useRef, useState } from 'react'
import { EditorContent, useEditor, useEditorState } from '@tiptap/react'
import StarterKit from '@tiptap/starter-kit'
import Placeholder from '@tiptap/extension-placeholder'
import Underline from '@tiptap/extension-underline'
import TextStyle from '@tiptap/extension-text-style'
import Color from '@tiptap/extension-color'
import TextAlign from '@tiptap/extension-text-align'
import Link from '@tiptap/extension-link'
import Table from '@tiptap/extension-table'
import TableRow from '@tiptap/extension-table-row'
import TableHeader from '@tiptap/extension-table-header'
import TableCell from '@tiptap/extension-table-cell'
import {
  ConfirmDialog,
  Select,
  useFengYuI18n,
} from '@infinia/plugin-ui'
import { FontSize } from '../extensions/FontSize'
import { plainTextFromHtml, sanitizeEmailHtml, shouldApplyExternalContent } from '../richText'

export interface RichTextEditorProps {
  value?: string
  disabled?: boolean
  onChange: (html: string) => void
  onPlainText: (plainText: string) => void
}

const FONT_SIZES = ['12px', '14px', '16px', '20px', '24px']

/**
 * tiptap email body with the Word-HTML sanitizer and the IME composition guard
 * (see richText.ts). The React port keeps the exact DOM-level contract of the
 * previous Vue editor: paste is sanitized, emitted HTML is sanitized, and an
 * external `value` change is only written back through setContent when it is a
 * genuine change outside an active composition.
 */
export default function RichTextEditor({ value = '', disabled = false, onChange, onPlainText }: RichTextEditorProps) {
  const { t } = useFengYuI18n()
  const [pasteNotice, setPasteNotice] = useState(false)
  const [linkDialog, setLinkDialog] = useState(false)
  const [linkHref, setLinkHref] = useState('')

  // Latest-callback refs: useEditor captures the option object once, so the
  // update handler must read props through refs to stay live.
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange
  const onPlainTextRef = useRef(onPlainText)
  onPlainTextRef.current = onPlainText
  const placeholderRef = useRef(t('compose.bodyPlaceholder'))
  placeholderRef.current = t('compose.bodyPlaceholder')

  // IME composition state. While a CJK IME is composing, the parent echoes our
  // emitted HTML back through the controlled prop; writing it back with
  // setContent would tear down the node the IME is writing into and abort the
  // input. See shouldApplyExternalContent.
  const composingRef = useRef(false)
  const lastEmittedHtmlRef = useRef<string | null>(null)
  const valueRef = useRef(value)
  valueRef.current = value

  const editor = useEditor({
    content: sanitizeEmailHtml(value),
    editable: !disabled,
    editorProps: {
      transformPastedHTML: html => sanitizeEmailHtml(html),
      handleDOMEvents: {
        compositionstart: () => { composingRef.current = true },
        compositionend: () => { composingRef.current = false },
      },
    },
    extensions: [
      StarterKit,
      Underline,
      TextStyle,
      FontSize,
      Color,
      TextAlign.configure({ types: ['heading', 'paragraph'] }),
      Link.configure({ openOnClick: false, protocols: ['http', 'https', 'mailto'] }),
      Table.configure({ resizable: false }),
      TableRow,
      TableHeader,
      TableCell,
      Placeholder.configure({ placeholder: () => placeholderRef.current }),
    ],
    onUpdate: ({ editor: current }) => {
      const html = sanitizeEmailHtml(current.getHTML())
      lastEmittedHtmlRef.current = html
      onChangeRef.current(html)
      onPlainTextRef.current(plainTextFromHtml(html))
    },
  })

  const toolbarState = useEditorState({
    editor,
    selector: ({ editor: current }) => ({
      bold: current?.isActive('bold') ?? false,
      italic: current?.isActive('italic') ?? false,
      underline: current?.isActive('underline') ?? false,
      bulletList: current?.isActive('bulletList') ?? false,
      orderedList: current?.isActive('orderedList') ?? false,
    }),
  })
  const toolbar = toolbarState ?? { bold: false, italic: false, underline: false, bulletList: false, orderedList: false }

  // External value → editor (guarded echo/composition write-back).
  useEffect(() => {
    if (!editor) return
    if (!shouldApplyExternalContent(value, lastEmittedHtmlRef.current, composingRef.current)) return
    const clean = sanitizeEmailHtml(value)
    if (sanitizeEmailHtml(editor.getHTML()) !== clean) {
      editor.commands.setContent(clean, false)
    }
  }, [value, editor])

  useEffect(() => { editor?.setEditable(!disabled) }, [disabled, editor])

  function openLinkDialog(): void {
    setLinkHref((editor?.getAttributes('link').href as string | undefined) ?? 'https://')
    setLinkDialog(true)
  }

  function applyLink(): void {
    const href = linkHref.trim()
    if (!href) editor?.chain().focus().unsetLink().run()
    else editor?.chain().focus().extendMarkRange('link').setLink({ href }).run()
    setLinkDialog(false)
  }

  function onPaste(): void {
    setPasteNotice(true)
    window.setTimeout(() => { setPasteNotice(false) }, 4000)
  }

  if (!editor) return <div className="rich-text-editor" data-loading-editor />

  return (
    <div className={'rich-text-editor' + (disabled ? ' rich-text-editor--disabled' : '')}>
      <div className="editor-toolbar" role="toolbar" aria-label={t('editor.toolbar')}>
        <button type="button" className={'editor-tool' + (toolbar.bold ? ' editor-tool--active' : '')} aria-label={t('editor.bold')} onClick={() => editor.chain().focus().toggleBold().run()}><strong>B</strong></button>
        <button type="button" className={'editor-tool' + (toolbar.italic ? ' editor-tool--active' : '')} aria-label={t('editor.italic')} onClick={() => editor.chain().focus().toggleItalic().run()}><em>I</em></button>
        <button type="button" className={'editor-tool' + (toolbar.underline ? ' editor-tool--active' : '')} aria-label={t('editor.underline')} onClick={() => editor.chain().focus().toggleUnderline().run()}><u>U</u></button>
        <Select size="sm" className="editor-select" aria-label={t('editor.heading')} value="" placeholder="H"
          onChange={value => { editor.chain().focus().toggleHeading({ level: Number(value) as 1 | 2 | 3 }).run() }}
          options={[{ value: '1', label: 'H1' }, { value: '2', label: 'H2' }, { value: '3', label: 'H3' }]} />
        <Select size="sm" className="editor-select" aria-label={t('editor.fontSize')} value="" placeholder="Aa"
          onChange={value => { editor.chain().focus().setMark('textStyle', { fontSize: value }).run() }}
          options={FONT_SIZES.map(size => ({ value: size, label: size.replace('px', '') }))} />
        <input type="color" className="editor-color" aria-label={t('editor.color')} defaultValue="#18181b"
          onInput={event => editor.chain().focus().setColor((event.target as HTMLInputElement).value).run()} />
        <button type="button" className="editor-tool" aria-label={t('editor.alignLeft')} onClick={() => editor.chain().focus().setTextAlign('left').run()}>↤</button>
        <button type="button" className="editor-tool" aria-label={t('editor.alignCenter')} onClick={() => editor.chain().focus().setTextAlign('center').run()}>↔</button>
        <button type="button" className="editor-tool" aria-label={t('editor.alignRight')} onClick={() => editor.chain().focus().setTextAlign('right').run()}>↦</button>
        <button type="button" className={'editor-tool' + (toolbar.bulletList ? ' editor-tool--active' : '')} onClick={() => editor.chain().focus().toggleBulletList().run()}>{t('editor.bullets')}</button>
        <button type="button" className={'editor-tool' + (toolbar.orderedList ? ' editor-tool--active' : '')} onClick={() => editor.chain().focus().toggleOrderedList().run()}>{t('editor.numbering')}</button>
        <button type="button" className="editor-tool" onClick={openLinkDialog}>{t('editor.link')}</button>
        <button type="button" className="editor-tool" onClick={() => editor.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()}>{t('editor.table')}</button>
        <button type="button" className="editor-tool" onClick={() => editor.chain().focus().unsetAllMarks().clearNodes().run()}>{t('editor.clear')}</button>
      </div>
      <EditorContent editor={editor} className="editor" onPaste={onPaste} />
      {pasteNotice ? <p className="editor-notice" role="status">{t('compose.wordNormalized')}</p> : null}
      <ConfirmDialog
        open={linkDialog}
        title={t('editor.link')}
        confirmLabel={t('common.confirm')}
        cancelLabel={t('common.cancel')}
        onCancel={() => setLinkDialog(false)}
        onConfirm={applyLink}
      >
        <input
          className="email-input"
          type="text"
          autoFocus
          aria-label={t('editor.linkPrompt')}
          placeholder={t('editor.linkPrompt')}
          value={linkHref}
          onChange={event => setLinkHref(event.target.value)}
          onKeyDown={event => { if (event.key === 'Enter') applyLink() }}
        />
      </ConfirmDialog>
    </div>
  )
}
