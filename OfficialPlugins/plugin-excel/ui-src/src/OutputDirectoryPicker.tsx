import { useState } from 'react'
import type { FileRef } from '@infinia/plugin-sdk'
import { IconFolderOpen, IconArrowsExchange } from '@tabler/icons-react'
import { GhostButton, ErrorState, PermissionNotice, isPermissionError, useFengYuClient } from '@infinia/plugin-ui'

/**
 * SDK-backed OUTPUT directory picker around `FengYuClient.files.outputDirectory`.
 *
 * The kit's `DirectoryPicker` wraps `files.inputDirectory`, but the Output step needs a
 * WRITE grant on the chosen folder (the split writes there directly on desktop), so this
 * component mirrors the kit picker's behavioral contract — concurrent-click guard, a `null`
 * host result is a normal cancellation, permission-denial vs error routing with retry —
 * against the output-directory bridge.
 */
export function OutputDirectoryPicker({
  value,
  onChange,
  label,
  className,
}: {
  value: FileRef | null
  onChange: (value: FileRef | null) => void
  label: string
  className?: string
}) {
  const client = useFengYuClient()
  const [loading, setLoading] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [permissionDenied, setPermissionDenied] = useState(false)

  const runPick = async () => {
    if (loading) return
    setErrorMessage(null)
    setPermissionDenied(false)
    setLoading(true)
    try {
      const picked = await client.files.outputDirectory()
      if (picked) {
        onChange(picked)
        return
      }
      // A clean null is a normal host-side cancellation — clear, no alert.
      onChange(null)
    } catch (error) {
      const wrapped = error instanceof Error ? error : new Error(String(error))
      setErrorMessage(wrapped.message)
      setPermissionDenied(isPermissionError(wrapped))
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className={className} style={{ display: 'grid', justifyItems: 'start', gap: '0.5rem' }}>
      {value ? (
        <div
          data-picker-selection=""
          aria-live="polite"
          className="flex w-full min-w-0 max-w-[460px] items-center gap-3 rounded-lg border border-line bg-muted-surface py-1.5 pr-1.5 pl-2.5"
        >
          <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-panel text-ink-2">
            <IconFolderOpen size={17} stroke={1.6} />
          </span>
          <code className="min-w-0 flex-1 truncate font-mono text-[12.5px]">{value.name}</code>
          <button
            type="button"
            data-action="pick-directory"
            aria-label={label}
            title={label}
            disabled={loading}
            onClick={() => void runPick()}
            className="grid size-7 shrink-0 place-items-center rounded-md text-ink-2 transition-colors"
            style={{ color: 'var(--c-ink-2)' }}
          >
            <IconArrowsExchange size={16} stroke={1.6} />
          </button>
        </div>
      ) : (
        <GhostButton data-action="pick-directory" disabled={loading} onClick={() => void runPick()}>
          <IconFolderOpen size={15} stroke={1.6} />
          {loading ? '打开中…' : label}
        </GhostButton>
      )}
      {errorMessage && permissionDenied ? (
        <PermissionNotice className="mt-2" message={errorMessage} />
      ) : null}
      {errorMessage && !permissionDenied ? (
        <ErrorState className="mt-2" title="无法打开选择器" message={errorMessage} onRetry={() => void runPick()} />
      ) : null}
    </div>
  )
}
