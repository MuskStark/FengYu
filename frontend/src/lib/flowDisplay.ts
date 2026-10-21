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

/** Node colors for the task-verb categories (generate/judge/fetch/send/files/other). */
export const WORKFLOW_TOOL_NODE_COLORS: Record<string, string> = {
  generate: '#8e7cc3',
  judge: '#7c6cc4',
  fetch: '#66a8c4',
  send: '#4DDBBB',
  files: '#d4a373',
  other: '#4DD0E1',
}

export const WORKFLOW_NODE_DEFAULT_COLOR = '#666666'

/**
 * Task-verb classification for the palette and canvas: a non-technical user browses by what a
 * tool DOES (generate / fetch / send / judge / files), not by which plugin ships it. Resolution
 * order is fixed so the mapping is deterministic:
 *
 *  1. host flow nodes (flow_llm → generate, flow_if → judge);
 *  2. known plugin families by pluginId (email/browser/excel/python/skills, and `mcp` for
 *     MCP server tools whose names carry no keyword);
 *  3. name keywords covering the common verbs — MCP tools are classified here too;
 *  4. anything else lands in `other`.
 */
export function workflowToolCategory(tool: Pick<AgentTool, 'name' | 'pluginId'>): string {
  const name = tool.name.toLowerCase()
  const pluginId = (tool.pluginId || '').toLowerCase()
  if (name.includes('flow_llm')) return 'generate'
  if (name.includes('flow_if')) return 'judge'

  if (pluginId.includes('email')) return 'send'
  if (pluginId.includes('browser')) return 'fetch'
  if (pluginId.includes('excel')) return 'files'
  if (pluginId.includes('python')) return 'generate'
  if (pluginId.includes('skill')) return 'generate'

  const id = `${pluginId} ${name}`
  if (/(^|[^a-z])(send|mail|email|notify|push|publish|reply|sms)([^a-z]|$)/.test(id)) return 'send'
  if (/(^|[^a-z])(fetch|get|search|query|list|read|browse|scrape|crawl|watch)([^a-z]|$)/.test(id)) return 'fetch'
  if (/(^|[^a-z])(file|excel|csv|json|sheet|archive|parse|format|convert)([^a-z]|$)/.test(id)) return 'files'
  if (/(^|[^a-z])(generate|create|write|translate|summarize|draft|compose|run|exec|eval)([^a-z]|$)/.test(id)) return 'generate'
  return 'other'
}

export function workflowNodeColor(tool: Pick<AgentTool, 'name' | 'pluginId'> &
  { flowNode?: { color?: string } | null }): string {
  return tool.flowNode?.color
    ?? WORKFLOW_TOOL_NODE_COLORS[workflowToolCategory(tool)]
    ?? WORKFLOW_NODE_DEFAULT_COLOR
}
