import { useCallback, useEffect, useRef, useState } from 'react'
import { AnimatePresence, motion, useReducedMotion } from 'motion/react'
import { cn } from '@/lib/utils'

/**
 * Aceternity-style intent input (pattern: ui.aceternity.com/components/
 * placeholders-and-vanish-input — reimplemented on our token palette rather than
 * using the vendored file, for two reasons the upstream demo does not care about:
 * the canonical Chinese-IME confirm-Enter must not submit mid-composition, and the
 * pixel dissolve should paint in the theme's own text color instead of upstream's
 * white-plus-CSS-invert trick. The signature stays: rotating example placeholders
 * and the input's text dissolving into pixels the moment it is sent.
 */
export function IntentInput({
  placeholders,
  ariaLabel,
  className,
  onSubmit,
}: {
  /** Rotating example prompts shown while the input is empty. */
  placeholders: string[]
  ariaLabel: string
  className?: string
  /** Fired with the current value the moment the user commits (Enter or the arrow). */
  onSubmit: (value: string) => void
}) {
  const reduceMotion = useReducedMotion()
  const [currentPlaceholder, setCurrentPlaceholder] = useState(0)
  const [value, setValue] = useState('')
  const [animating, setAnimating] = useState(false)
  const intervalRef = useRef<number | null>(null)
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const inputRef = useRef<HTMLInputElement | null>(null)
  const pixelsRef = useRef<Array<{ x: number; y: number; r: number; color: string }>>([])

  useEffect(() => {
    const tick = () => setCurrentPlaceholder(prev => (prev + 1) % placeholders.length)
    const start = () => {
      if (intervalRef.current === null) intervalRef.current = window.setInterval(tick, 3200)
    }
    const onVisibility = () => {
      if (document.visibilityState !== 'visible') {
        if (intervalRef.current !== null) window.clearInterval(intervalRef.current)
        intervalRef.current = null
      } else {
        start()
      }
    }
    start()
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      if (intervalRef.current !== null) window.clearInterval(intervalRef.current)
      document.removeEventListener('visibilitychange', onVisibility)
    }
  }, [placeholders.length])

  /** Rasterizes the current value so the dissolve can scatter its real pixels. */
  const draw = useCallback(() => {
    const input = inputRef.current
    const canvas = canvasRef.current
    if (!input || !canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    canvas.width = 800
    canvas.height = 800
    ctx.clearRect(0, 0, 800, 800)
    const computed = getComputedStyle(input)
    const fontSize = parseFloat(computed.getPropertyValue('font-size'))
    ctx.font = `${fontSize * 2}px ${computed.fontFamily}`
    ctx.fillStyle = computed.color
    ctx.fillText(value, 16, 40)
    const { data } = ctx.getImageData(0, 0, 800, 800)
    const pixels: Array<{ x: number; y: number; r: number; color: string }> = []
    for (let y = 0; y < 800; y++) {
      for (let x = 0; x < 800; x++) {
        const offset = 4 * (y * 800 + x)
        if (data[offset] !== 0 || data[offset + 1] !== 0 || data[offset + 2] !== 0) {
          pixels.push({
            x,
            y,
            r: 1,
            color: `rgba(${data[offset]}, ${data[offset + 1]}, ${data[offset + 2]}, ${data[offset + 3]})`,
          })
        }
      }
    }
    pixelsRef.current = pixels
  }, [value])

  useEffect(() => { draw() }, [value, draw])

  const animate = (start: number) => {
    const frame = (pos: number = 0) => {
      requestAnimationFrame(() => {
        const remaining: typeof pixelsRef.current = []
        for (const pixel of pixelsRef.current) {
          if (pixel.x < pos) {
            remaining.push(pixel)
            continue
          }
          if (pixel.r <= 0) continue
          pixel.x += Math.random() > 0.5 ? 1 : -1
          pixel.y += Math.random() > 0.5 ? 1 : -1
          pixel.r -= 0.05 * Math.random()
          remaining.push(pixel)
        }
        pixelsRef.current = remaining
        const ctx = canvasRef.current?.getContext('2d')
        if (ctx) {
          ctx.clearRect(pos, 0, 800, 800)
          for (const { x, y, r, color } of pixelsRef.current) {
            if (x > pos) {
              ctx.beginPath()
              ctx.rect(x, y, r, r)
              ctx.fillStyle = color
              ctx.strokeStyle = color
              ctx.stroke()
            }
          }
        }
        if (pixelsRef.current.length > 0) frame(pos - 8)
        else {
          setValue('')
          setAnimating(false)
        }
      })
    }
    frame(start)
  }

  const vanishAndSubmit = () => {
    const current = value.trim()
    if (!current || animating) return
    if (reduceMotion) {
      onSubmit(current)
      setValue('')
      return
    }
    setAnimating(true)
    draw()
    const maxX = pixelsRef.current.reduce((max, pixel) => Math.max(max, pixel.x), 0)
    animate(maxX)
    onSubmit(current)
  }

  return (
    <form
      className={cn(
        'intent-input relative h-12 w-full overflow-hidden rounded-full',
        'bg-[color:rgb(var(--v-theme-surface-variant)/0.55)]',
        'ring-1 ring-inset ring-[color:rgb(var(--v-theme-outline-variant))] transition duration-200',
        'focus-within:bg-[color:rgb(var(--v-theme-surface-variant)/0.85)]',
        'focus-within:ring-[color:rgb(var(--v-theme-primary))]',
        value && 'bg-[color:rgb(var(--v-theme-surface-variant)/0.85)]',
        className,
      )}
      onSubmit={(event) => {
        event.preventDefault()
        vanishAndSubmit()
      }}
    >
      <canvas
        aria-hidden="true"
        ref={canvasRef}
        className={cn(
          'pointer-events-none absolute left-8 top-[20%] origin-top-left scale-50 text-base',
          animating ? 'opacity-100' : 'opacity-0',
        )}
      />
      <input
        ref={inputRef}
        type="text"
        value={value}
        aria-label={ariaLabel}
        autoComplete="off"
        disabled={animating}
        onChange={(event) => { if (!animating) setValue(event.target.value) }}
        onKeyDown={(event) => {
          // isComposing: the IME confirm-Enter (Chinese input) must never submit.
          if (event.key === 'Enter' && !event.nativeEvent.isComposing && !animating) {
            event.preventDefault()
            vanishAndSubmit()
          }
        }}
        className={cn(
          'relative z-20 h-full w-full rounded-full border-none bg-transparent pl-10 pr-16',
          'text-sm text-[color:rgb(var(--v-theme-on-surface))] sm:text-base',
          'focus:outline-none focus:ring-0',
          animating && 'text-transparent',
        )}
      />
      <button
        type="submit"
        aria-label={ariaLabel}
        disabled={!value.trim() || animating}
        className={cn(
          'absolute right-2 top-1/2 z-30 flex h-8 w-8 -translate-y-1/2 items-center justify-center rounded-full',
          'bg-[color:rgb(var(--v-theme-primary))] text-white transition duration-200',
          'disabled:bg-[color:rgb(var(--v-theme-outline-variant))] disabled:text-[color:rgb(var(--v-theme-on-surface)/0.4)]',
        )}
      >
        <motion.svg
          xmlns="http://www.w3.org/2000/svg"
          width="24"
          height="24"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="h-4 w-4"
        >
          <path stroke="none" d="M0 0h24v24H0z" fill="none" />
          <motion.path
            d="M5 12l14 0"
            initial={{ strokeDasharray: '50%', strokeDashoffset: '50%' }}
            animate={{ strokeDashoffset: value.trim() ? 0 : '50%' }}
            transition={{ duration: 0.3, ease: 'linear' }}
          />
          <path d="M13 18l6 -6" />
          <path d="M13 6l6 6" />
        </motion.svg>
      </button>
      <div className="pointer-events-none absolute inset-0 flex items-center rounded-full">
        <AnimatePresence mode="wait">
          {!value && (
            <motion.p
              key={`placeholder-${currentPlaceholder}`}
              initial={{ y: 5, opacity: 0 }}
              animate={{ y: 0, opacity: 1 }}
              exit={{ y: -15, opacity: 0 }}
              transition={{ duration: 0.3, ease: 'linear' }}
              className="w-[calc(100%-3.5rem)] truncate pl-10 text-left text-sm text-[color:rgb(var(--v-theme-on-surface)/0.45)] sm:text-base"
            >
              {placeholders[currentPlaceholder]}
            </motion.p>
          )}
        </AnimatePresence>
      </div>
    </form>
  )
}
