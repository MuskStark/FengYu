import { shallowRef, type Ref } from 'vue'

export interface FlowCanvasHistoryEntry<N, E> {
  nodes: N[]
  edges: E[]
}

/**
 * Bounded undo/redo history for a controlled Vue Flow canvas.
 *
 * The composable owns structural snapshots only; node configuration remains reactive in the
 * inspector. Dragging records the pre-drag state on pointer-down and commits it on pointer-up so
 * dozens of intermediate position events still produce a single undo operation.
 */
export function useFlowCanvasHistory<N, E>(
  nodes: Ref<N[]>,
  edges: Ref<E[]>,
  limit = 50,
) {
  const undoStack = shallowRef<FlowCanvasHistoryEntry<N, E>[]>([])
  const redoStack = shallowRef<FlowCanvasHistoryEntry<N, E>[]>([])
  let applyingHistory = false
  let dragStartSnapshot: FlowCanvasHistoryEntry<N, E> | null = null

  function cloneCanvas(): FlowCanvasHistoryEntry<N, E> {
    const snapshot: unknown = { nodes: nodes.value, edges: edges.value }
    return JSON.parse(JSON.stringify(snapshot)) as FlowCanvasHistoryEntry<N, E>
  }

  /** Capture the current canvas before a mutation. */
  function pushHistory() {
    if (applyingHistory) return
    undoStack.value = [...undoStack.value, cloneCanvas()].slice(-limit)
    redoStack.value = []
  }

  function applyHistoryEntry(entry: FlowCanvasHistoryEntry<N, E>) {
    applyingHistory = true
    try {
      nodes.value = entry.nodes
      edges.value = entry.edges
    } finally {
      applyingHistory = false
    }
  }

  function undoCanvas() {
    const previous = undoStack.value.at(-1)
    if (!previous) return
    undoStack.value = undoStack.value.slice(0, -1)
    redoStack.value = [...redoStack.value, cloneCanvas()]
    applyHistoryEntry(previous)
  }

  function redoCanvas() {
    const next = redoStack.value.at(-1)
    if (!next) return
    redoStack.value = redoStack.value.slice(0, -1)
    undoStack.value = [...undoStack.value, cloneCanvas()].slice(-limit)
    applyHistoryEntry(next)
  }

  function resetHistory() {
    undoStack.value = []
    redoStack.value = []
    dragStartSnapshot = null
  }

  function onNodeDragStart() {
    if (!applyingHistory) dragStartSnapshot = cloneCanvas()
  }

  function onNodeDragStop() {
    if (dragStartSnapshot && !applyingHistory) {
      undoStack.value = [...undoStack.value, dragStartSnapshot].slice(-limit)
      redoStack.value = []
    }
    dragStartSnapshot = null
  }

  return {
    undoStack,
    redoStack,
    pushHistory,
    undoCanvas,
    redoCanvas,
    resetHistory,
    onNodeDragStart,
    onNodeDragStop,
  }
}
