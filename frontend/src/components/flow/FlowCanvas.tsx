import { useCallback, useEffect, useRef, type DragEvent as ReactDragEvent } from 'react'
import '@xyflow/react/dist/style.css'
import {
  Background,
  BackgroundVariant,
  Controls,
  MiniMap,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Edge,
  type EdgeChange,
  type IsValidConnection,
  type Node,
  type NodeChange,
  type NodeTypes,
  type OnConnect,
  type OnSelectionChangeParams,
} from '@xyflow/react'
import type { AgentTool } from '@/services/types'
import { canConnect } from '@/lib/flowGraph'
import {
  FlowNodeErrorsContext,
  FlowNodeTimingContext,
  FlowRunStatusContext,
  FlowToolCatalogContext,
  flowNodeTypes,
} from './nodes'
import { FlowSelectableEdge } from './edges'

/**
 * The ReactFlow canvas surface: custom nodes (start/tool/sticky), custom
 * selectable edges, dotted background with grid snapping, controls, minimap,
 * drag-and-drop from the palette, and the duplicate/cycle connection gate.
 * Nodes and edges are keyboard-focusable (Tab), enabling arrow-key nudging.
 * Programmatic viewport helpers (fitView, screenToFlowPosition) are bridged up
 * to the page through onApi.
 */

export interface FlowCanvasApi {
  fitView: (options?: { padding?: number; duration?: number; maxZoom?: number }) => void
}

/** Grid step shared by snapping, nudging, and the dotted background. */
export const FLOW_GRID_STEP = 16

const flowEdgeTypes = { smoothstep: FlowSelectableEdge }

// Store-synced props keep a stable identity: StoreUpdater rewrites every tracked
// field into the canvas store whenever the prop value changes, so inline arrays
// and objects here mean one store write per parent render for no reason.
const flowSnapGrid: [number, number] = [FLOW_GRID_STEP, FLOW_GRID_STEP]
const flowDefaultEdgeOptions = { type: 'smoothstep' } as const
const flowDeleteKeys = ['Delete', 'Backspace']

interface FlowCanvasProps {
  nodes: Node[]
  edges: Edge[]
  nodeStatus: Record<string, string>
  nodeErrors: Set<string>
  nodeTiming: Record<string, number>
  toolsByName: Map<string, AgentTool>
  interactive: boolean
  snapToGrid: boolean
  /**
   * Whether Delete/Backspace may remove selection. xyflow listens for these at WINDOW
   * level, so an overlay (run dialog) does not shield the canvas — the Vue-era native
   * <dialog> modality did. Pass false while a modal is open or selection deletion must
   * be impossible (pinned by host-rc.spec.ts).
   */
  deleteEnabled?: boolean
  onNodesChange: (changes: NodeChange[]) => void
  onEdgesChange: (changes: EdgeChange[]) => void
  onConnect: OnConnect
  onNodeClick: (nodeId: string) => void
  onSelectionChange?: (selection: OnSelectionChangeParams) => void
  onNodeDragStart?: () => void
  onNodeDragStop?: () => void
  onDropTool: (toolName: string, position: { x: number; y: number }) => void
  onApi: (api: FlowCanvasApi) => void
}

export function FlowCanvas(props: FlowCanvasProps) {
  return (
    <ReactFlowProvider>
      <CanvasWithBridge {...props} />
    </ReactFlowProvider>
  )
}

function CanvasWithBridge(props: FlowCanvasProps) {
  const { screenToFlowPosition, fitView } = useReactFlow()
  const onApiRef = useRef(props.onApi)
  onApiRef.current = props.onApi

  useEffect(() => {
    onApiRef.current({
      fitView: (options) => {
        void fitView({
          padding: options?.padding ?? 0.14,
          duration: options?.duration ?? 220,
          maxZoom: options?.maxZoom ?? 1,
        })
      },
    })
  }, [fitView])

  const isValidConnection = useCallback<IsValidConnection>(
    (connection) => canConnect(connection, props.edges, { busy: !props.interactive }),
    [props.edges, props.interactive],
  )

  const onDragOver = useCallback((event: ReactDragEvent) => {
    event.preventDefault()
    event.dataTransfer.dropEffect = 'copy'
  }, [])

  const onDrop = useCallback((event: ReactDragEvent) => {
    event.preventDefault()
    const toolName = event.dataTransfer.getData('application/x-fengyu-tool')
    if (!toolName) return
    const position = screenToFlowPosition({ x: event.clientX, y: event.clientY })
    props.onDropTool(toolName, { x: position.x - 90, y: position.y - 36 })
  }, [props, screenToFlowPosition])

  return (
    <FlowRunStatusContext.Provider value={props.nodeStatus}>
      <FlowToolCatalogContext.Provider value={props.toolsByName}>
        <FlowNodeErrorsContext.Provider value={props.nodeErrors}>
          <FlowNodeTimingContext.Provider value={props.nodeTiming}>
            <ReactFlow
              className="flow-stage"
              nodes={props.nodes}
              edges={props.edges}
              nodeTypes={flowNodeTypes as unknown as NodeTypes}
              edgeTypes={flowEdgeTypes}
              minZoom={0.4}
              maxZoom={1.6}
              fitView
              fitViewOptions={{ padding: 0.14, maxZoom: 1 }}
              deleteKeyCode={props.deleteEnabled === false ? null : flowDeleteKeys}
              snapToGrid={props.snapToGrid}
              snapGrid={flowSnapGrid}
              nodesDraggable={props.interactive}
              nodesConnectable={props.interactive}
              nodesFocusable
              edgesFocusable
              elementsSelectable={props.interactive}
              isValidConnection={isValidConnection}
              defaultEdgeOptions={flowDefaultEdgeOptions}
              onNodesChange={props.onNodesChange}
              onEdgesChange={props.onEdgesChange}
              onConnect={props.onConnect}
              onNodeClick={(_, node) => props.onNodeClick(node.id)}
              onSelectionChange={props.onSelectionChange}
              onNodeDragStart={() => props.onNodeDragStart?.()}
              onNodeDragStop={() => props.onNodeDragStop?.()}
              onPaneClick={() => props.onNodeClick('')}
              onDragOver={onDragOver}
              onDrop={onDrop}
            >
              <Background variant={BackgroundVariant.Dots} gap={FLOW_GRID_STEP} size={1} className="flow-background" />
              <Controls position="bottom-left" showInteractive={false} />
              <MiniMap
                position="bottom-right"
                pannable
                zoomable
                nodeStrokeWidth={3}
                className="flow-minimap"
              />
            </ReactFlow>
          </FlowNodeTimingContext.Provider>
        </FlowNodeErrorsContext.Provider>
      </FlowToolCatalogContext.Provider>
    </FlowRunStatusContext.Provider>
  )
}
