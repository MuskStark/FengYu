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
import { FlowRunStatusContext, FlowToolCatalogContext, flowNodeTypes } from './nodes'

/**
 * The ReactFlow canvas surface: custom nodes (start/tool/sticky), dotted
 * background, controls, minimap, drag-and-drop from the palette, and the
 * duplicate/cycle connection gate. Programmatic viewport helpers (fitView,
 * screenToFlowPosition) are bridged up to the page through onApi.
 */

export interface FlowCanvasApi {
  fitView: (options?: { padding?: number; duration?: number; maxZoom?: number }) => void
}

interface FlowCanvasProps {
  nodes: Node[]
  edges: Edge[]
  nodeStatus: Record<string, string>
  toolsByName: Map<string, AgentTool>
  interactive: boolean
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
        <ReactFlow
          className="flow-stage"
          nodes={props.nodes}
          edges={props.edges}
          nodeTypes={flowNodeTypes as unknown as NodeTypes}
          minZoom={0.4}
          maxZoom={1.6}
          fitView
          fitViewOptions={{ padding: 0.14, maxZoom: 1 }}
          deleteKeyCode={['Delete', 'Backspace']}
          nodesDraggable={props.interactive}
          nodesConnectable={props.interactive}
          elementsSelectable={props.interactive}
          isValidConnection={isValidConnection}
          defaultEdgeOptions={{ type: 'smoothstep' }}
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
          <Background variant={BackgroundVariant.Dots} gap={16} size={1} className="flow-background" />
          <Controls position="bottom-left" showInteractive={false} />
          <MiniMap
            position="bottom-right"
            pannable
            zoomable
            nodeStrokeWidth={3}
            className="flow-minimap"
          />
        </ReactFlow>
      </FlowToolCatalogContext.Provider>
    </FlowRunStatusContext.Provider>
  )
}
