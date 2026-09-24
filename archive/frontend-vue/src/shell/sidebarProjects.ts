import type { Conversation } from '@/stores/aiSession'

/**
 * ZCode-style sidebar grouping: conversations that carry a coding workspace render under a
 * project header (the workspace root's folder name), everything else stays in the flat
 * "recent" list. Pure display logic — the store keeps its flat conversation array.
 */
export interface ProjectGroup {
  /** Workspace root (absolute) — the grouping key and collapse-state identity. */
  root: string
  /** Display name: the root's basename, disambiguated with its parent folder on collisions. */
  name: string
  /** Newest conversation timestamp in the group (drives group ordering). */
  latestAt: number
  conversations: Conversation[]
}

export interface ConversationGrouping {
  projects: ProjectGroup[]
  ungrouped: Conversation[]
}

/** Sort modes behind the toolbar's view-options menu (ZCode's sortBy: updated/created). */
export type ConversationSortBy = 'updated' | 'created'

function basename(path: string): string {
  const cleaned = path.replace(/[\\/]+$/, '')
  return cleaned.split(/[\\/]/).pop() || cleaned || path
}

function parentName(path: string): string {
  const cleaned = path.replace(/[\\/]+$/, '')
  const segments = cleaned.split(/[\\/]/)
  return segments.length >= 2 ? segments[segments.length - 2] : ''
}

/** Activity key of a conversation: last message time when known, creation time otherwise. */
function activityKey(conversation: Conversation): number {
  return conversation.updatedAt ?? conversation.createdAt
}

/**
 * Group conversations by workspace root and order everything by the chosen sort. Groups order
 * by their most recent member (activity sort) or alphabetically by name (creation sort).
 */
export function groupConversations(
  conversations: Conversation[],
  sortBy: ConversationSortBy = 'updated',
): ConversationGrouping {
  const ordered = [...conversations].sort((a, b) =>
    sortBy === 'updated' ? activityKey(b) - activityKey(a) : a.createdAt - b.createdAt)

  const byRoot = new Map<string, Conversation[]>()
  const ungrouped: Conversation[] = []
  for (const conversation of ordered) {
    const root = conversation.workspaceRoot
    if (!root) {
      ungrouped.push(conversation)
      continue
    }
    const bucket = byRoot.get(root)
    if (bucket) bucket.push(conversation)
    else byRoot.set(root, [conversation])
  }

  const projects: ProjectGroup[] = [...byRoot.entries()].map(([root, list]) => ({
    root,
    name: basename(root),
    latestAt: list.reduce((latest, c) => Math.max(latest, activityKey(c)), 0),
    conversations: list,
  }))
  // Same-basename roots disambiguate with their parent folder so two "backend" projects
  // never read as one; unique names keep the clean short form.
  const names = new Map<string, number>()
  for (const project of projects) names.set(project.name, (names.get(project.name) ?? 0) + 1)
  for (const project of projects) {
    if ((names.get(project.name) ?? 0) > 1) {
      const parent = parentName(project.root)
      if (parent) project.name = `${project.name} · ${parent}`
    }
  }
  projects.sort((a, b) => sortBy === 'updated'
    ? b.latestAt - a.latestAt
    : a.name.localeCompare(b.name, undefined, { numeric: true }))
  return { projects, ungrouped }
}

/** Flat-view ordering (the "chats" tab): same key as the grouping's conversation order. */
export function sortForView(
  conversations: Conversation[],
  sortBy: ConversationSortBy = 'updated',
): Conversation[] {
  return [...conversations].sort((a, b) =>
    sortBy === 'updated' ? activityKey(b) - activityKey(a) : a.createdAt - b.createdAt)
}
