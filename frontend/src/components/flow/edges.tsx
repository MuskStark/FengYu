import { useTranslation } from 'react-i18next'
import { X } from 'lucide-react'
import {
  BaseEdge,
  EdgeLabelRenderer,
  getSmoothStepPath,
  useReactFlow,
  type EdgeProps,
} from '@xyflow/react'

/**
 * Custom canvas edges: the standard smoothstep arrow, plus (a) a delete button
 * rendered onto the path when the edge is selected (revives the previously-dead
 * `flows.deleteEdge` string — deleting no longer requires the keyboard), and
 * (b) a branch-port chip when the edge leaves a control node's named port
 * (e.g. flow_if's true/false), so the compiled runWhen condition is visible.
 */
export function FlowSelectableEdge(props: EdgeProps) {
  const { t } = useTranslation()
  const { deleteElements } = useReactFlow()
  const [path, labelX, labelY] = getSmoothStepPath({
    sourceX: props.sourceX,
    sourceY: props.sourceY,
    sourcePosition: props.sourcePosition,
    targetX: props.targetX,
    targetY: props.targetY,
    targetPosition: props.targetPosition,
  })
  const branch = typeof props.sourceHandleId === 'string' && props.sourceHandleId
    ? props.sourceHandleId
    : null
  return (
    <>
      <BaseEdge id={props.id} path={path} markerEnd={props.markerEnd} />
      <EdgeLabelRenderer>
        {branch && (
          <span
            className="flow-edge__branch nopan"
            style={{ transform: `translate(-50%, -50%) translate(${props.sourceX}px, ${props.sourceY}px)` }}
          >
            {branch}
          </span>
        )}
        {props.selected && (
          <button
            className="flow-edge__delete nopan"
            style={{ transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)` }}
            aria-label={t('flows.deleteEdge')}
            title={t('flows.deleteEdge')}
            onClick={() => deleteElements({ edges: [{ id: props.id }] })}
          >
            <X size={11} />
          </button>
        )}
      </EdgeLabelRenderer>
    </>
  )
}
