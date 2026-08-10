/** Minimal shape of the CDP Accessibility.getFullAXTree response that we consume. */
export interface CdpAxNode {
  nodeId: string
  role: { type?: string; value?: string }
  name: { value?: string }
  childIds?: string[]
  ignored?: boolean
}
export interface CdpAxTree {
  nodes: CdpAxNode[]
}

/**
 * Format a CDP accessibility tree into a YAML-ish string resembling Playwright's
 * `ariaSnapshot()`: `role "name":` with children indented two spaces per level.
 * Semantic equivalence only — not byte-identical to Playwright's output.
 */
export function formatA11yTree(tree: CdpAxTree): string {
  if (!tree?.nodes?.length) return ''
  const byId = new Map<string, CdpAxNode>()
  for (const n of tree.nodes) if (!n.ignored) byId.set(n.nodeId, n)
  // The first node with role rootWebArea (or the first node) is the root.
  let root: CdpAxNode | undefined = tree.nodes.find((n) => n.role?.type === 'rootWebArea' || n.role?.value === 'rootWebArea')
  if (!root) root = tree.nodes[0]
  const lines: string[] = []
  walk(root, 0, byId, lines, new Set<string>())
  return lines.join('\n')
}

function walk(node: CdpAxNode, depth: number, byId: Map<string, CdpAxNode>, out: string[], seen: Set<string>): void {
  if (seen.has(node.nodeId)) return
  seen.add(node.nodeId)
  const role = node.role?.type || node.role?.value || 'unknown'
  const name = node.name?.value ?? ''
  const indent = '  '.repeat(depth)
  out.push(name ? `${indent}${role} "${name}":` : `${indent}${role}:`)
  for (const childId of node.childIds ?? []) {
    const child = byId.get(childId)
    if (child) walk(child, depth + 1, byId, out, seen)
  }
}
