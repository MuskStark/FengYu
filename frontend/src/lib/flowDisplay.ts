import type { AgentTool } from '@/services/types'
import type { FlowToolData } from '@/lib/flowGraph'

/**
 * Display helpers for the flow surface (kept beside the graph model): humanized
 * labels, Flowise-style node category colors, and node display titles.
 */

// ── display helpers ───────────────────────────────────────────────────────

/** camelCase/snake_case → a label a non-technical user can scan. */
export function humanizeWorkflowField(name: string): string {
  const spaced = name
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .trim()
  if (!spaced) return name
  const acronyms = new Set(['id', 'url', 'uri', 'html', 'json', 'api', 'http', 'https', 'sql', 'csv', 'pdf'])
  return spaced.split(' ').map((word, index) => {
    if (acronyms.has(word.toLocaleLowerCase())) return word.toLocaleUpperCase()
    return index === 0 ? word.charAt(0).toLocaleUpperCase() + word.slice(1) : word
  }).join(' ')
}

export function humanizeToolName(name: string): string {
  return humanizeWorkflowField(name)
    .split(' ')
    .map((word) => word ? word.charAt(0).toLocaleUpperCase() + word.slice(1) : word)
    .join(' ')
}

/** Display title: author rename > declared label > humanized tool name. */
export function flowNodeTitle(data: FlowToolData, tool?: AgentTool | null): string {
  return data.title
    || tool?.flowNode?.label
    || humanizeToolName(data.toolName)
}

/** Flowise agentflow node colors, assigned per tool category. */
export const WORKFLOW_TOOL_NODE_COLORS: Record<string, string> = {
  ai: '#8e7cc3',
  control: '#7c6cc4',
  browser: '#FF7F7F',
  email: '#4DDBBB',
  excel: '#d4a373',
  python: '#E4B7FF',
  skills: '#FFB938',
  content: '#b8bedd',
  other: '#4DD0E1',
}

export const WORKFLOW_NODE_DEFAULT_COLOR = '#666666'

export function workflowToolCategory(tool: Pick<AgentTool, 'name' | 'pluginId'>): string {
  const id = `${tool.pluginId || ''} ${tool.name}`.toLocaleLowerCase()
  if (id.includes('flow_llm')) return 'ai'
  if (id.includes('flow_if')) return 'control'
  if (id.includes('browser')) return 'browser'
  if (id.includes('email')) return 'email'
  if (id.includes('excel')) return 'excel'
  if (id.includes('python')) return 'python'
  if (id.includes('skill')) return 'skills'
  if (id.includes('markdown')) return 'content'
  return 'other'
}

export function workflowNodeColor(tool: Pick<AgentTool, 'name' | 'pluginId'> &
  { flowNode?: { color?: string } | null }): string {
  return tool.flowNode?.color
    ?? WORKFLOW_TOOL_NODE_COLORS[workflowToolCategory(tool)]
    ?? WORKFLOW_NODE_DEFAULT_COLOR
}
