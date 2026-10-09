import { useCallback, useRef, useState, type RefObject } from 'react'
import {
  applyEdgeChanges,
  applyNodeChanges,
  type Connection,
  type Edge,
  type EdgeChange,
  type NodeChange,
} from '@xyflow/react'
import type { AgentTool } from '@/services/types'
import { workflowNodeColor } from '@/lib/flowDisplay'
import {
  defaultArgsText,
  ensureStartNode,
  isStickyNode,
  isToolNode,
  makeFlowEdge,
  maxCanvasIdSequences,
  type FlowCanvasNode,
  type FlowStickyColor,
  type FlowToolData,
} from '@/lib/flowGraph'
import {
  buildFlowClipboard,
  materializeFlowClipboard,
  parseFlowClipboard,
} from '@/lib/flowClipboard'

/**
 * Canvas editing engine for the flow builder: bounded undo/redo over structural
 * snapshots (nodes + edges; drags commit one entry on pointer-up), node/edge
 * change wiring, connection validation, and the node mutation verbs the palette
 * and inspector drive. Data-only edits (inspector fields) bypass the history
 * EXCEPT through {@link beginTrackedEdit}, which coalesces a typing burst into
 * one undo entry.
 */

interface CanvasEntry {
  nodes: FlowCanvasNode[]
  edges: Edge[]
  /** Page-level state restored with the canvas (e.g. the Start schema text). */
  meta?: unknown
}

export interface FlowCanvasEditor {
  /** Latest canvas mirror for async callers (save, draft, guards). */
  canvasRef: RefObject<CanvasEntry>
  handleNodesChange: (changes: NodeChange[]) => void
  handleEdgesChange: (changes: EdgeChange[]) => void
  handleConnect: (connection: Connection) => void
  patchToolData: (nodeId: string, patch: Partial<FlowToolData>) => void
  patchStickyData: (nodeId: string, patch: { content?: string; color?: FlowStickyColor }) => void
  addTool: (tool: AgentTool, position?: { x: number; y: number }) => void
  addStartNode: () => void
  addStickyNote: () => void
  removeNode: (nodeId: string) => void
  removeNodes: (nodeIds: string[]) => void
  /**
   * Coalesced history anchor for data-only edits (inspector fields): pushes one
   * snapshot per burst — a second call for the same key within the window is a
   * no-op, so undo steps back over the whole edit, not every keystroke.
   */
  beginTrackedEdit: (key: string) => void
  /** Copies a selection into the internal clipboard (and the system one, best-effort). */
  copySelection: (selectedIds: Set<string>) => boolean
  /** Pastes the internal clipboard (or a raw payload) with fresh ids. */
  pasteClipboard: (raw?: string | null, offset?: { x: number; y: number }) => string[]
  /** Duplicate = copy + immediate paste (⌘D). */
  duplicateSelection: (selectedIds: Set<string>, offset?: { x: number; y: number }) => string[]
  /** Moves the given nodes by a delta (arrow-key nudge; coalesced undo). */
  nudgeNodes: (nodeIds: string[], delta: { x: number; y: number }) => void
  /** Applies new positions to nodes (auto-layout / alignment) as one undo entry. */
  applyPositions: (positions: Record<string, { x: number; y: number }>) => void
  undoCanvas: () => void
  redoCanvas: () => void
  resetHistory: () => void
  pushHistory: () => void
  onNodeDragStart: () => void
  onNodeDragStop: () => void
  canUndo: boolean
  canRedo: boolean
  /** Advances the node_N/note_N id sequences past authored ids (after load). */
  syncSequences: (nodes: Array<{ id: string }>) => void
}

const TRACKED_EDIT_WINDOW_MS = 700

export function useFlowCanvasEditor(args: {
  nodes: FlowCanvasNode[]
  edges: Edge[]
  setNodes: (updater: (current: FlowCanvasNode[]) => FlowCanvasNode[]) => void
  setEdges: (updater: (current: Edge[]) => Edge[]) => void
  setSelectedNodeId: (updater: (current: string | null) => string | null) => void
  setPaletteOpen: (open: boolean) => void
  /** Snapshots page-level state (the Start schema) alongside nodes/edges. */
  captureMeta?: () => unknown
  /** Restores a snapshot taken by captureMeta (undo/redo). */
  restoreMeta?: (meta: unknown) => void
}): FlowCanvasEditor {
  const { nodes, edges, setNodes, setEdges, setSelectedNodeId, setPaletteOpen } = args
  const [historyCounts, setHistoryCounts] = useState({ undo: 0, redo: 0 })

  const canvasMutable = useRef<CanvasEntry>({ nodes: [], edges: [] })
  const canvasRef = canvasMutable as RefObject<CanvasEntry>
  // Latest-canvas mirror for async callers (assigned during render like a ref).
  canvasMutable.current = { nodes, edges }
  // Meta callbacks ride refs so the history closures never go stale while the
  // args object itself is re-created every render.
  const captureMetaRef = useRef(args.captureMeta)
  captureMetaRef.current = args.captureMeta
  const restoreMetaRef = useRef(args.restoreMeta)
  restoreMetaRef.current = args.restoreMeta
  const undoStack = useRef<CanvasEntry[]>([])
  const redoStack = useRef<CanvasEntry[]>([])
  const applyingHistory = useRef(false)
  const dragSnapshot = useRef<CanvasEntry | null>(null)
  const nodeSequence = useRef(0)
  const noteSequence = useRef(0)
  const clipboardRef = useRef<string | null>(null)
  const trackedEdits = useRef(new Map<string, number>())

  const cloneCanvas = useCallback((): CanvasEntry => ({
    nodes: JSON.parse(JSON.stringify(canvasMutable.current.nodes)) as FlowCanvasNode[],
    edges: JSON.parse(JSON.stringify(canvasMutable.current.edges)) as Edge[],
    meta: captureMetaRef.current?.(),
  }), [])

  const publishCounts = useCallback(() => {
    setHistoryCounts({ undo: undoStack.current.length, redo: redoStack.current.length })
  }, [])

  const pushHistory = useCallback(() => {
    if (applyingHistory.current) return
    undoStack.current = [...undoStack.current.slice(-49), cloneCanvas()]
    redoStack.current = []
    publishCounts()
  }, [cloneCanvas, publishCounts])

  const applyEntry = useCallback((entry: CanvasEntry) => {
    applyingHistory.current = true
    try {
      setNodes(() => entry.nodes)
      setEdges(() => entry.edges)
      if (entry.meta !== undefined) restoreMetaRef.current?.(entry.meta)
    } finally {
      applyingHistory.current = false
    }
  }, [setNodes, setEdges])

  const undoCanvas = useCallback(() => {
    const previous = undoStack.current.pop()
    if (!previous) return
    redoStack.current = [...redoStack.current, cloneCanvas()]
    applyEntry(previous)
    publishCounts()
  }, [applyEntry, cloneCanvas, publishCounts])

  const redoCanvas = useCallback(() => {
    const next = redoStack.current.pop()
    if (!next) return
    undoStack.current = [...undoStack.current.slice(-49), cloneCanvas()]
    applyEntry(next)
    publishCounts()
  }, [applyEntry, cloneCanvas, publishCounts])

  const resetHistory = useCallback(() => {
    undoStack.current = []
    redoStack.current = []
    dragSnapshot.current = null
    publishCounts()
  }, [publishCounts])

  const onNodeDragStart = useCallback(() => {
    dragSnapshot.current = cloneCanvas()
  }, [cloneCanvas])

  const onNodeDragStop = useCallback(() => {
    if (dragSnapshot.current) {
      undoStack.current = [...undoStack.current.slice(-49), dragSnapshot.current]
      redoStack.current = []
      publishCounts()
    }
    dragSnapshot.current = null
  }, [publishCounts])

  const handleNodesChange = useCallback((changes: NodeChange[]) => {
    if (changes.some((change) => change.type === 'remove')) pushHistory()
    setNodes((current) => applyNodeChanges(changes, current) as FlowCanvasNode[])
  }, [pushHistory, setNodes])

  const handleEdgesChange = useCallback((changes: EdgeChange[]) => {
    if (changes.some((change) => change.type === 'remove')) pushHistory()
    setEdges((current) => applyEdgeChanges(changes, current))
  }, [pushHistory, setEdges])

  const handleConnect = useCallback((connection: Connection) => {
    if (connection.source === connection.target) return
    pushHistory()
    setEdges((current) => {
      // Re-validate against the freshest list; duplicates never land (cycles
      // are gated by isValidConnection at drag time).
      if (current.some((edge) => edge.source === connection.source
        && edge.target === connection.target)) return current
      return [...current, makeFlowEdge(connection.source, connection.target, connection.sourceHandle)]
    })
  }, [pushHistory, setEdges])

  const patchToolData = useCallback((nodeId: string, patch: Partial<FlowToolData>) => {
    setNodes((current) => current.map((node) => node.id !== nodeId || !isToolNode(node)
      ? node
      : { ...node, data: { ...node.data, ...patch } }))
  }, [setNodes])

  const patchStickyData = useCallback((nodeId: string, patch: { content?: string; color?: FlowStickyColor }) => {
    setNodes((current) => current.map((node) => node.id !== nodeId || !isStickyNode(node)
      ? node
      : { ...node, data: { ...node.data, ...patch } }))
  }, [setNodes])

  const addTool = useCallback((tool: AgentTool, position?: { x: number; y: number }) => {
    pushHistory()
    const order = canvasMutable.current.nodes.length
    const id = `node_${++nodeSequence.current}`
    const node: FlowCanvasNode = {
      id,
      type: 'tool',
      position: position ?? { x: 48 + (order % 3) * 290, y: 48 + Math.floor(order / 3) * 180 },
      data: {
        toolName: tool.name,
        argsText: defaultArgsText(tool),
        description: tool.localizedDescription || tool.description || tool.name,
        requiresApproval: false,
        available: true,
        color: workflowNodeColor(tool),
      },
    }
    setNodes((current) => [...current, node])
    setSelectedNodeId(() => id)
    setPaletteOpen(false)
  }, [pushHistory, setNodes, setSelectedNodeId, setPaletteOpen])

  const addStartNode = useCallback(() => {
    pushHistory()
    setNodes((current) => {
      const next = ensureStartNode(current)
      setSelectedNodeId(() => next.find((node) => node.type === 'start')?.id ?? null)
      return next
    })
    setPaletteOpen(false)
  }, [pushHistory, setNodes, setSelectedNodeId, setPaletteOpen])

  const addStickyNote = useCallback(() => {
    pushHistory()
    setNodes((current) => [...current, {
      id: `note_${++noteSequence.current}`,
      type: 'sticky',
      position: { x: 140 + (noteSequence.current % 5) * 36, y: 120 + (noteSequence.current % 5) * 30 },
      data: { content: '', color: 'yellow' },
    }])
  }, [pushHistory, setNodes])

  const removeNode = useCallback((nodeId: string) => {
    pushHistory()
    setNodes((current) => current.filter((node) => node.id !== nodeId))
    setEdges((current) => current.filter((edge) => edge.source !== nodeId && edge.target !== nodeId))
    setSelectedNodeId((current) => (current === nodeId ? null : current))
  }, [pushHistory, setNodes, setEdges, setSelectedNodeId])

  const removeNodes = useCallback((nodeIds: string[]) => {
    if (!nodeIds.length) return
    const doomed = new Set(nodeIds)
    pushHistory()
    setNodes((current) => current.filter((node) => !doomed.has(node.id)))
    setEdges((current) => current.filter((edge) => !doomed.has(edge.source) && !doomed.has(edge.target)))
    setSelectedNodeId((current) => (current && doomed.has(current) ? null : current))
  }, [pushHistory, setNodes, setEdges, setSelectedNodeId])

  const beginTrackedEdit = useCallback((key: string) => {
    const now = Date.now()
    const last = trackedEdits.current.get(key)
    if (last !== undefined && now - last < TRACKED_EDIT_WINDOW_MS) {
      trackedEdits.current.set(key, now)
      return
    }
    trackedEdits.current.set(key, now)
    pushHistory()
  }, [pushHistory])

  const mintCanvasId = useCallback((kind: 'tool' | 'sticky') =>
    kind === 'tool' ? `node_${++nodeSequence.current}` : `note_${++noteSequence.current}`, [])

  const copySelection = useCallback((selectedIds: Set<string>) => {
    const payload = buildFlowClipboard(canvasMutable.current.nodes, canvasMutable.current.edges, selectedIds)
    if (!payload) return false
    const raw = JSON.stringify(payload)
    clipboardRef.current = raw
    void navigator.clipboard?.writeText(raw).catch(() => {
      // System clipboard unavailable (web without permission) — the internal copy still pastes.
    })
    return true
  }, [])

  const pasteInternal = useCallback((raw: string | null | undefined, offset: { x: number; y: number }): string[] => {
    const payload = parseFlowClipboard(raw) ?? parseFlowClipboard(clipboardRef.current)
    if (!payload) return []
    const pasted = materializeFlowClipboard(payload, mintCanvasId, offset, makeFlowEdge)
    if (!pasted || !pasted.nodes.length) return []
    pushHistory()
    setNodes((current) => [...current, ...pasted.nodes])
    setEdges((current) => [...current, ...pasted.edges])
    const pastedIds = pasted.nodes.map((node) => node.id)
    setSelectedNodeId(() => pastedIds[pastedIds.length - 1] ?? null)
    setPaletteOpen(false)
    return pastedIds
  }, [mintCanvasId, pushHistory, setNodes, setEdges, setSelectedNodeId, setPaletteOpen])

  const pasteClipboard = useCallback((raw?: string | null, offset?: { x: number; y: number }) =>
    pasteInternal(raw ?? clipboardRef.current, offset ?? { x: 36, y: 36 }), [pasteInternal])

  const duplicateSelection = useCallback((selectedIds: Set<string>, offset?: { x: number; y: number }) => {
    const payload = buildFlowClipboard(canvasMutable.current.nodes, canvasMutable.current.edges, selectedIds)
    if (!payload) return []
    return pasteInternal(JSON.stringify(payload), offset ?? { x: 48, y: 48 })
  }, [pasteInternal])

  const nudgeNodes = useCallback((nodeIds: string[], delta: { x: number; y: number }) => {
    if (!nodeIds.length) return
    beginTrackedEdit(`nudge:${nodeIds.slice().sort().join(',')}`)
    setNodes((current) => current.map((node) => nodeIds.includes(node.id)
      ? {
        ...node,
        position: {
          x: Math.round(node.position.x + delta.x),
          y: Math.round(node.position.y + delta.y),
        },
      }
      : node))
  }, [beginTrackedEdit, setNodes])

  const applyPositions = useCallback((positions: Record<string, { x: number; y: number }>) => {
    if (!Object.keys(positions).length) return
    pushHistory()
    setNodes((current) => current.map((node) => (positions[node.id]
      ? { ...node, position: positions[node.id] }
      : node)))
  }, [pushHistory, setNodes])

  const syncSequences = useCallback((nodes: Array<{ id: string }>) => {
    const sequences = maxCanvasIdSequences(nodes)
    nodeSequence.current = Math.max(nodeSequence.current, sequences.node)
    noteSequence.current = Math.max(noteSequence.current, sequences.note)
  }, [])

  return {
    canvasRef,
    handleNodesChange,
    handleEdgesChange,
    handleConnect,
    patchToolData,
    patchStickyData,
    addTool,
    addStartNode,
    addStickyNote,
    removeNode,
    removeNodes,
    beginTrackedEdit,
    copySelection,
    pasteClipboard,
    duplicateSelection,
    nudgeNodes,
    applyPositions,
    undoCanvas,
    redoCanvas,
    resetHistory,
    pushHistory,
    onNodeDragStart,
    onNodeDragStop,
    canUndo: historyCounts.undo > 0,
    canRedo: historyCounts.redo > 0,
    syncSequences,
  }
}
