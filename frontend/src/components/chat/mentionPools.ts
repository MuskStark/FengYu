import { useEffect, useState } from 'react'
import { services } from '@/services'
import { useSkillsStore } from '@/stores/skills'
import { usePluginsStore } from '@/stores/plugins'
import { useAiSessionStore } from '@/stores/aiSession'
import { wsRowIcon } from '@/lib/wsTree'
import { buildMentionMarkdown } from '@/lib/mentionTriggers'
import type { MentionOption } from '@/lib/mentionSearch'
import type { PromptMentionPayload } from './PromptMentionNode'

/**
 * Candidate pools behind the @/$ panels (React port of the Vue composer's pools): plugins and
 * skills from their mirrors, files from the workspace tree (cached per conversation), flows
 * from the workflow listing. Loads are lazy and cached; failures just hide the group.
 */
export interface MentionPools {
  plugin?: MentionOption[]
  file?: MentionOption[]
  flow?: MentionOption[]
  skill?: MentionOption[]
}

let filePoolCache: { conversationId: number; options: MentionOption[] } | null = null
let flowPoolCache: MentionOption[] | null = null

export function useMentionPools(): MentionPools {
  const skills = useSkillsStore(state => state.skills)
  const loadSkills = useSkillsStore(state => state.load)
  const plugins = usePluginsStore(state => state.plugins)
  const loadPlugins = usePluginsStore(state => state.load)
  const workspaceRoot = useAiSessionStore(state => state.active()?.workspaceRoot ?? null)
  const backendId = useAiSessionStore(state => state.active()?.backendId ?? null)
  const [pools, setPools] = useState<MentionPools>({})

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      void loadSkills()
      void loadPlugins()
      const next: MentionPools = {
        plugin: plugins
          .filter(plugin => plugin.enabled !== false)
          .map(plugin => ({
            id: `plugin:${plugin.id}`,
            category: 'plugin' as const,
            label: plugin.name,
            description: plugin.description ?? '',
            value: plugin.id,
            markdown: buildMentionMarkdown('plugin', plugin.name, plugin.id),
            icon: 'mdi-puzzle-outline',
          })),
        skill: skills
          .filter(skill => skill.enabled)
          .map(skill => ({
            id: `skill:${skill.id}`,
            category: 'skill' as const,
            label: skill.name,
            description: skill.description ?? '',
            value: skill.id,
            markdown: buildMentionMarkdown('skill', skill.name, skill.id),
            icon: 'mdi-auto-fix',
          })),
      }
      const ai = useAiSessionStore.getState()
      const conv = ai.conversations.find(item => item.id === ai.activeId)
      if (conv?.workspaceRoot && conv.backendId != null) {
        if (!filePoolCache || filePoolCache.conversationId !== conv.backendId) {
          try {
            const tree = await services.workspace.tree(conv.backendId)
            filePoolCache = {
              conversationId: conv.backendId,
              options: tree.nodes.map(node => ({
                id: `file:${node.path}`,
                category: 'file' as const,
                label: node.name,
                description: node.path,
                value: node.path,
                markdown: buildMentionMarkdown('file', node.name, node.path, node.dir),
                icon: wsRowIcon(node, false),
                isDirectory: node.dir,
              })),
            }
          } catch {
            filePoolCache = null
          }
        }
        next.file = filePoolCache?.options
      }
      if (!flowPoolCache) {
        try {
          const workflows = await services.workflow.list()
          flowPoolCache = workflows.map(flow => ({
            id: `flow:${flow.id}`,
            category: 'flow' as const,
            label: flow.name,
            description: flow.description ?? '',
            value: flow.id,
            markdown: `[@${flow.name}](flow://${flow.id})`,
            icon: 'mdi-vector-polyline',
          }))
        } catch {
          flowPoolCache = null
        }
      }
      next.flow = flowPoolCache ?? undefined
      if (!cancelled) setPools(next)
    }
    void load()
    return () => { cancelled = true }
    // Reload when the workspace binding changes (file pool scope) — pools otherwise stable.
  }, [skills, plugins, loadSkills, loadPlugins, workspaceRoot, backendId])

  return pools
}

export function toPayload(option: MentionOption): PromptMentionPayload {
  return {
    id: option.id,
    category: option.category,
    label: option.label,
    description: option.description,
    value: option.value,
    markdown: option.markdown,
    icon: option.icon,
  }
}
