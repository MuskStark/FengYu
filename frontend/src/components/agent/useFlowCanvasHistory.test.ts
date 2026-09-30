import { ref } from 'vue'
import { describe, expect, it } from 'vitest'
import { useFlowCanvasHistory } from './useFlowCanvasHistory'

describe('useFlowCanvasHistory', () => {
  it('undoes and redoes structural mutations', () => {
    const nodes = ref([{ id: 'one' }])
    const edges = ref<{ id: string }[]>([])
    const history = useFlowCanvasHistory(nodes, edges)

    history.pushHistory()
    nodes.value = [...nodes.value, { id: 'two' }]
    history.undoCanvas()
    expect(nodes.value.map((node) => node.id)).toEqual(['one'])
    expect(history.redoStack.value).toHaveLength(1)

    history.redoCanvas()
    expect(nodes.value.map((node) => node.id)).toEqual(['one', 'two'])
  })

  it('records one pre-drag snapshot and enforces the configured bound', () => {
    const nodes = ref([{ id: 'one', x: 0 }])
    const edges = ref<{ id: string }[]>([])
    const history = useFlowCanvasHistory(nodes, edges, 2)

    history.onNodeDragStart()
    nodes.value[0]!.x = 80
    history.onNodeDragStop()
    history.undoCanvas()
    expect(nodes.value[0]!.x).toBe(0)

    for (let x = 1; x <= 3; x += 1) {
      history.pushHistory()
      nodes.value = [{ id: 'one', x }]
    }
    expect(history.undoStack.value).toHaveLength(2)
    history.resetHistory()
    expect(history.undoStack.value).toHaveLength(0)
    expect(history.redoStack.value).toHaveLength(0)
  })
})
