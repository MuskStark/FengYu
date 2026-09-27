import type { Conversation } from '@/stores/aiSession'
import { diffLines } from '@/lib/toolActivity'

/**
 * Conversation → Markdown export (local, no server round-trip): user/assistant turns with
 * thinking blocks, tool-call summaries (label + status + diffs), and artifact names. The
 * result downloads as a file named after the conversation title.
 */
export function conversationToMarkdown(conv: Conversation): string {
  const title = conv.title.trim() || 'Conversation'
  const lines: string[] = [`# ${title}`, '']
  const created = new Date(conv.createdAt).toISOString()
  lines.push(`> Exported ${new Date().toISOString()} · started ${created}`, '')
  for (const turn of conv.turns) {
    if (turn.role === 'user') {
      lines.push('## 🧑 User', '', turn.content, '')
      if (turn.attachments.length > 0) {
        lines.push(`*Attachments: ${turn.attachments.map(a => a.name).join(', ')}*`, '')
      }
      continue
    }
    lines.push('## 🤖 Assistant', '')
    if (turn.thinking) {
      lines.push('<details><summary>Thinking</summary>', '', turn.thinking, '', '</details>', '')
    }
    if (turn.activities.length > 0) {
      for (const activity of turn.activities) {
        lines.push(`- \`${activity.label}\` — ${activity.status}`)
        if (activity.diff) {
          lines.push('  ```diff')
          for (const line of diffLines(activity.diff)) lines.push('  ' + line.text)
          lines.push('  ```')
        }
      }
      lines.push('')
    }
    if (turn.content) lines.push(turn.content, '')
    if (turn.artifacts.length > 0) {
      lines.push(`*Artifacts: ${turn.artifacts.map(a => a.name).join(', ')}*`, '')
    }
  }
  return lines.join('\n')
}

/** Downloads the export as a Markdown file (browser + desktop webview). */
export function downloadConversationMarkdown(conv: Conversation): void {
  const markdown = conversationToMarkdown(conv)
  const safeTitle = (conv.title.trim() || 'conversation')
    .replace(/[\\/:*?"<>|]+/g, '_')
    .slice(0, 60)
  const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${safeTitle}.md`
  link.click()
  URL.revokeObjectURL(url)
}
