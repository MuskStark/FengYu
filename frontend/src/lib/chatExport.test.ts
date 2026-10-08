import { describe, expect, it } from 'vitest'
import { conversationToMarkdown } from './chatExport'
import type { Conversation } from '@/stores/aiSession'

function conversationFixture(): Conversation {
  return {
    id: 1,
    backendId: 7,
    title: 'Fix the login bug',
    turns: [
      {
        id: 1, role: 'user', content: '帮我修登录 bug', thinking: '', streaming: false,
        confirmations: [], activities: [], attachments: [], artifacts: [],
      },
      {
        id: 2, role: 'assistant', content: '已修复。', thinking: '分析栈轨迹…', streaming: false,
        confirmations: [], activities: [
          {
            id: 't1', name: 'edit_file', label: 'Edit src/auth.ts', status: 'completed', detail: '',
            args: {}, output: '', startedAt: 0,
            diff: '@@ -1 +1 @@\n-old\n+new',
          },
        ], attachments: [], artifacts: [],
      },
    ],
    createdAt: Date.UTC(2026, 8, 1),
    updatedAt: Date.UTC(2026, 8, 1),
    loaded: true,
    draft: '', draftMentions: [], draftAttachments: [],
    scopeId: null, resources: [], outputTarget: null, workspaceRoot: null,
    attaching: 0, seenArtifactIds: new Set(), unsaved: false,
    queue: [], streaming: false, usage: null, pinned: false, archived: false,
  }
}

describe('conversation markdown export', () => {
  it('renders title, user/assistant turns, thinking, and tool diffs', () => {
    const markdown = conversationToMarkdown(conversationFixture())
    expect(markdown).toContain('# Fix the login bug')
    expect(markdown).toContain('## 🧑 User')
    expect(markdown).toContain('帮我修登录 bug')
    expect(markdown).toContain('## 🤖 Assistant')
    expect(markdown).toContain('<details><summary>Thinking</summary>')
    expect(markdown).toContain('分析栈轨迹…')
    expect(markdown).toContain('`Edit src/auth.ts` — completed')
    expect(markdown).toContain('```diff')
    expect(markdown).toContain('+new')
    expect(markdown).toContain('已修复。')
  })

  it('falls back to a generic title for untitled conversations', () => {
    const conversation = conversationFixture()
    conversation.title = ''
    expect(conversationToMarkdown(conversation)).toContain('# Conversation')
  })
})
