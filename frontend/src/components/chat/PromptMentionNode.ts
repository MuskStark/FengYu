import { $applyNodeReplacement, TextNode, type LexicalNode, type Spread } from 'lexical'

/**
 * Inline mention token (port of ZCode's PromptMentionNode): a TextNode in token mode whose
 * text IS the display label, carrying its send-time markdown. Atomic — the caret cannot enter
 * it and one Backspace removes it (Lexical token semantics), with a trailing space inserted
 * after selection by the mention plugin.
 */
export type MentionCategory = 'file' | 'skill' | 'plugin' | 'flow'

export interface PromptMentionPayload {
  id: string
  category: MentionCategory
  label: string
  description: string
  value: string
  markdown: string
  icon: string
}

export class PromptMentionNode extends TextNode {
  __mention: PromptMentionPayload

  static getType(): string {
    return 'prompt-mention'
  }

  static clone(node: PromptMentionNode): PromptMentionNode {
    return new PromptMentionNode(node.__mention, node.__key)
  }

  constructor(payload: PromptMentionPayload, key?: string) {
    super(payload.label, key)
    this.__mention = payload
    this.setMode('token')
  }

  getMention(): PromptMentionPayload {
    return this.__mention
  }

  getMarkdown(): string {
    return this.__mention.markdown
  }

  createDOM(config: Parameters<TextNode['createDOM']>[0]): HTMLElement {
    const dom = super.createDOM(config)
    dom.className = `composer-token composer-token--${this.__mention.category}`
    dom.title = this.__mention.description || this.__mention.label
    return dom
  }

  exportJSON(): Spread<{ mention: PromptMentionPayload; type: string; version: number }, ReturnType<TextNode['exportJSON']>> {
    return {
      ...super.exportJSON(),
      type: PromptMentionNode.getType(),
      mention: this.__mention,
      version: 1,
    }
  }

  static importJSON(json: ReturnType<PromptMentionNode['exportJSON']> & { mention: PromptMentionPayload }): PromptMentionNode {
    return $createPromptMentionNode(json.mention)
  }

  canInsertTextBefore(): boolean {
    return false
  }

  canInsertTextAfter(): boolean {
    return false
  }

  isTextEntity(): boolean {
    return true
  }
}

export function $createPromptMentionNode(payload: PromptMentionPayload): PromptMentionNode {
  return $applyNodeReplacement(new PromptMentionNode(payload))
}

export function $isPromptMentionNode(node: LexicalNode | null | undefined): node is PromptMentionNode {
  return node instanceof PromptMentionNode
}
