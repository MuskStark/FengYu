import { describe, expect, it } from 'vitest'
import { groupConversations, sortForView } from './sidebarProjects'
import type { Conversation } from '@/stores/aiSession'

function conv(id: number, workspaceRoot: string | null, createdAt = id, updatedAt?: number): Conversation {
  return {
    id, backendId: id, title: `c${id}`, turns: [], createdAt, loaded: false, draft: '',
    draftMentions: [], draftAttachments: [], scopeId: null, resources: [], outputTarget: null,
    workspaceRoot, attaching: 0, seenArtifactIds: new Set(), unsaved: false, updatedAt,
  }
}

describe('sidebar conversation grouping', () => {
  it('groups workspace conversations by root and keeps plain ones ungrouped', () => {
    const { projects, ungrouped } = groupConversations([
      conv(1, '/home/u/api'),
      conv(2, null),
      conv(3, '/home/u/web'),
      conv(4, '/home/u/api'),
    ])

    expect(projects.map(p => p.root)).toEqual(['/home/u/api', '/home/u/web'])
    // Activity sort is the default, so the newest conversation leads inside a group.
    expect(projects[0].conversations.map(c => c.id)).toEqual([4, 1])
    expect(ungrouped.map(c => c.id)).toEqual([2])
  })

  it('orders groups by their newest conversation', () => {
    const { projects } = groupConversations([
      conv(1, '/old', 100),
      conv(2, '/new', 200),
      conv(3, '/old', 300),
    ])

    expect(projects.map(p => p.root)).toEqual(['/old', '/new'])
    expect(projects[0].latestAt).toBe(300)
  })

  it('names groups by the root basename and disambiguates collisions with the parent', () => {
    const { projects } = groupConversations([
      conv(1, '/work/backend'),
      conv(2, '/home/backend'),
      conv(3, '/work/api'),
    ])

    const names = projects.map(p => p.name).sort()
    expect(names).toEqual(['api', 'backend · home', 'backend · work'])
  })

  it('handles empty and all-plain lists', () => {
    expect(groupConversations([])).toEqual({ projects: [], ungrouped: [] })
    const onlyPlain = groupConversations([conv(1, null), conv(2, null)])
    expect(onlyPlain.projects).toEqual([])
    expect(onlyPlain.ungrouped).toHaveLength(2)
  })

  it('sorts by last activity when known and falls back to creation time', () => {
    const { projects, ungrouped } = groupConversations([
      conv(1, '/p', 100, 500),
      conv(2, '/p', 400),
      conv(3, null, 300, 200),
    ])

    // Within the group and in the flat list, the updatedAt-500 row leads the newer-created one.
    expect(projects[0].conversations.map(c => c.id)).toEqual([1, 2])
    expect(projects[0].latestAt).toBe(500)
    expect(ungrouped.map(c => c.id)).toEqual([3])
  })

  it('orders groups alphabetically under the creation-time sort', () => {
    const { projects } = groupConversations([
      conv(1, '/zed', 100),
      conv(2, '/alpha', 50),
    ], 'created')

    expect(projects.map(p => p.name)).toEqual(['alpha', 'zed'])
  })

  it('sortForView mirrors the grouping order for the flat view', () => {
    const sorted = sortForView([conv(1, null, 100), conv(2, null, 50, 900)], 'updated')
    expect(sorted.map(c => c.id)).toEqual([2, 1])
    const created = sortForView([conv(1, null, 100), conv(2, null, 50, 900)], 'created')
    expect(created.map(c => c.id)).toEqual([2, 1])
  })
})
