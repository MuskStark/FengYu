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

/**
 * Canvas editing engine for the flow builder: bounded undo/redo over structural
 * snapshots (nodes + edges; drags commit one entry on pointer-up), node/edge
 * change wiring, connection validation, and the node mutation verbs the palette
 * and inspector drive. Data-only edits (inspector fields) bypass the history.
 */

interface CanvasEntry {
  nodes: FlowCanvasNode[]
  edges: Edge[]
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

export function useFlowCanvasEditor(args: {
  nodes: FlowCanvasNode[]
  edges: Edge[]
  setNodes: (updater: (current: FlowCanvasNode[]) => FlowCanvasNode[]) => void
  setEdges: (updater: (current: Edge[]) => Edge[]) => void
  setSelectedNodeId: (updater: (current: string | null) => string | null) => void
  setPaletteOpen: (open: boolean) => void
}): FlowCanvasEditor {
  const { nodes, edges, setNodes, setEdges, setSelectedNodeId, setPaletteOpen } = args
  const [historyCounts, setHistoryCounts] = useState({ undo: 0, redo: 0 })

  const canvasMutable = useRef<CanvasEntry>({ nodes: [], edges: [] })
  const canvasRef = canvasMutable as RefObject<CanvasEntry>
  // Latest-canvas mirror for async callers (assigned during render like a ref).
  canvasMutable.current = { nodes, edges }
  const undoStack = useRef<CanvasEntry[]>([])
  const redoStack = useRef<CanvasEntry[]>([])
  const applyingHistory = useRef(false)
  const dragSnapshot = useRef<CanvasEntry | null>(null)
  const nodeSequence = useRef(0)
  const noteSequence = useRef(0)

  const cloneCanvas = useCallback((): CanvasEntry => ({
    nodes: JSON.parse(JSON.stringify(canvasMutable.current.nodes)) as FlowCanvasNode[],
    edges: JSON.parse(JSON.stringify(canvasMutable.current.edges)) as Edge[],
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
