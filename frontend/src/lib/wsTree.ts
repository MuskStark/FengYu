import type { WorkspaceTreeNode } from '@/services/types'

/** One assembled row of the workspace tree: a server node with its children attached. */
export interface WsTreeRow extends WorkspaceTreeNode {
  children: WsTreeRow[]
}

/**
 * File-type icons for the workspace tree. Extension-based and purely decorative — the row's
 * text (name) always carries the meaning.
 */
export function wsRowIcon(row: WorkspaceTreeNode, expanded: boolean): string {
  if (row.dir) return expanded ? 'mdi-folder-open-outline' : 'mdi-folder-outline'
  const ext = row.name.split('.').pop()?.toLowerCase() ?? ''
  if (['ts', 'tsx', 'js', 'jsx', 'mjs', 'java', 'py', 'go', 'rs', 'c', 'h', 'cpp', 'hpp', 'cc', 'cs', 'kt', 'swift', 'rb', 'php'].includes(ext)) {
    return 'mdi-file-code-outline'
  }
  if (['json', 'yml', 'yaml', 'toml', 'ini', 'properties', 'xml', 'html', 'vue', 'css', 'scss', 'md'].includes(ext)) {
    return 'mdi-code-json'
  }
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg', 'ico', 'pdf'].includes(ext)) {
    return 'mdi-file-image-outline'
  }
  return 'mdi-file-document-outline'
}
