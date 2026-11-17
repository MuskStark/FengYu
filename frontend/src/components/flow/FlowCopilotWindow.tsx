import { useCallback, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { GripVertical, Sparkles } from 'lucide-react'

/**
 * The copilot's floating window over the FULL builder canvas: the AI is a
 * side-seat assistant, not a docked rail. The window drags anywhere over the
 * stage (clamped so its header always stays reachable), resizes by a hairline
 * left-edge handle, collapses to nothing while the toolbar/launcher pill can
 * bring it back, and remembers its geometry in localStorage.
 */

const GEOMETRY_KEY = 'fengyu.flow-copilot-window.v1'
const MIN_W = 320
const MAX_W = 560

interface Geometry {
  x: number | null // null = default anchor (right side, vertically centered)
  y: number | null
  w: number
}

const DEFAULT_GEOMETRY: Geometry = { x: null, y: null, w: 380 }

function loadGeometry(): Geometry {
  try {
    const raw = window.localStorage.getItem(GEOMETRY_KEY)
    if (!raw) return DEFAULT_GEOMETRY
    const parsed = JSON.parse(raw) as Partial<Geometry>
    return {
      x: Number.isFinite(parsed.x) ? parsed.x as number : null,
      y: Number.isFinite(parsed.y) ? parsed.y as number : null,
      w: Number.isFinite(parsed.w)
        ? Math.min(MAX_W, Math.max(MIN_W, parsed.w as number))
        : DEFAULT_GEOMETRY.w,
    }
  } catch {
    return DEFAULT_GEOMETRY
  }
}

export function FlowCopilotWindow(props: {
  open: boolean
  onToggle: () => void
  title: string
  children: React.ReactNode
}) {
  const { t } = useTranslation()
  const [geometry, setGeometry] = useState<Geometry>(loadGeometry)
  const hostRef = useRef<HTMLDivElement | null>(null)
  const headerRef = useRef<HTMLDivElement | null>(null)
  const dragRef = useRef<
    | { kind: 'move'; px: number; py: number; x: number; y: number }
    | { kind: 'resize'; px: number; w: number }
    | null
  >(null)

  // The window positions itself absolutely inside the stage wrapper (its offset
  // parent). Default anchor: pinned to the right edge, vertically centered.
  const stage = () => hostRef.current?.parentElement
  const rightAnchored = geometry.x === null || geometry.y === null

  const persist = useCallback((next: Geometry) => {
    setGeometry(next)
    try {
      window.localStorage.setItem(GEOMETRY_KEY, JSON.stringify(next))
    } catch {
      /* private browsing — the placement still applies for this session */
    }
  }, [])

  // Keep the window inside the stage when the stage shrinks under it.
  useEffect(() => {
    const onResize = () => {
      const bounds = stage()
      if (!bounds || geometry.x === null || geometry.y === null) return
      const maxW = Math.min(MAX_W, bounds.clientWidth - 16)
      const maxX = Math.max(0, bounds.clientWidth - Math.min(geometry.w, maxW) - 8)
      const maxY = Math.max(0, bounds.clientHeight - 72)
      const x = Math.min(geometry.x, maxX)
      const y = Math.min(geometry.y, maxY)
      if (x !== geometry.x || y !== geometry.y) persist({ ...geometry, x, y })
    }
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [geometry, persist])

  /** Pointer-driven move/resize; clamps keep the header reachable and the
   *  width inside its band. */
  const onHeaderPointerDown = (event: React.PointerEvent) => {
    if (event.button !== 0) return
    const bounds = stage()
    if (!bounds) return
    if (geometry.x === null || geometry.y === null) {
      // First drag off the default anchor: materialize current coordinates.
      const box = hostRef.current?.getBoundingClientRect()
      const base = bounds.getBoundingClientRect()
      if (!box) return
      const current = {
        x: box.left - base.left,
        y: box.top - base.top,
        w: geometry.w,
      }
      dragRef.current = { kind: 'move', px: event.clientX, py: event.clientY, x: current.x, y: current.y }
      headerRef.current?.setPointerCapture(event.pointerId)
      setGeometry(current)
      return
    }
    dragRef.current = { kind: 'move', px: event.clientX, py: event.clientY, x: geometry.x, y: geometry.y }
    headerRef.current?.setPointerCapture(event.pointerId)
  }
  const onResizePointerDown = (event: React.PointerEvent) => {
    if (event.button !== 0) return
    event.stopPropagation()
    dragRef.current = { kind: 'resize', px: event.clientX, w: geometry.w }
    ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  }
  const onPointerMove = (event: React.PointerEvent) => {
    const drag = dragRef.current
    const bounds = stage()
    if (!drag || !bounds) return
    if (drag.kind === 'move') {
      const maxX = Math.max(0, bounds.clientWidth - 120)
      const maxY = Math.max(0, bounds.clientHeight - 56)
      persist({
        ...geometry,
        x: Math.min(maxX, Math.max(-Math.min(geometry.w, MAX_W) + 120, drag.x + event.clientX - drag.px)),
        y: Math.min(maxY, Math.max(0, drag.y + event.clientY - drag.py)),
      })
    } else {
      const w = Math.min(MAX_W, Math.max(MIN_W, drag.w + (drag.px - event.clientX)))
      persist({ ...geometry, w })
    }
  }
  const onPointerUp = (event: React.PointerEvent) => {
    dragRef.current = null
    const element = event.currentTarget as HTMLElement
    if (element.hasPointerCapture(event.pointerId)) element.releasePointerCapture(event.pointerId)
  }

  if (!props.open) {
    // Collapsed: a quiet launcher pill keeps the copilot one click away from
    // anywhere on the canvas.
    return (
      <button
        className="flow-copilot-pill"
        aria-label={t('flows.copilotOpen')}
        title={props.title}
        onClick={props.onToggle}
      >
        <Sparkles size={15} />
        <span>{t('flows.copilotPill')}</span>
      </button>
    )
  }

  return (
    <div
      ref={hostRef}
      className="flow-copilot-window"
      style={rightAnchored ? { width: geometry.w } : {
        width: geometry.w,
        left: geometry.x!,
        top: geometry.y!,
      }}
    >
      <div
        ref={headerRef}
        className="flow-copilot-window__grip"
        onPointerDown={onHeaderPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
        onDoubleClick={() => persist({ ...geometry, x: null, y: null })}
        aria-label={t('flows.copilotDragHint')}
        title={t('flows.copilotDragHint')}
        role="presentation"
      >
        <GripVertical size={13} />
      </div>
      <div
        className="flow-copilot-window__resize"
        role="presentation"
        onPointerDown={onResizePointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerUp}
      />
      {props.children}
    </div>
  )
}
