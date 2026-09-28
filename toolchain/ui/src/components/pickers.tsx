import { useCallback, useEffect, useRef, useState } from 'react'
import type { FileFilter, FileRef } from '@infinia/plugin-sdk'
import { IconFileText, IconFolderOpen, IconArrowsExchange } from '@tabler/icons-react'
import { useFengYuClient } from '../client'
import { cn } from '../lib/utils'
import { GhostButton } from './chrome'
import { ErrorState, PermissionNotice } from './states'

/**
 * A rejection whose message reads as a permission/access denial. Shared by
 * both SDK-backed pickers.
 */
export function isPermissionError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error ?? '')
  return /permission|denied|forbidden|unauthorized|not allowed/i.test(message)
}

/**
 * Shared pick lifecycle (behavioral contract of the Vue 2.x kit):
 * - Concurrent clicks are guarded by `loading`.
 * - A `null` host result is a normal cancellation — `onCancel`, no alert.
 * - Rejections route to permission-denial (no auto-retry) vs error (retry
 *   re-runs the pick).
 */
function useFengYuPick(request: () => Promise<FileRef | null>) {
  const [loading, setLoading] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [permissionDenied, setPermissionDenied] = useState(false)

  const pick = useCallback(async (): Promise<FileRef | null> => {
    if (loading) return null
    setErrorMessage(null)
    setPermissionDenied(false)
    setLoading(true)
    try {
      return await request()
    } catch (error) {
      const wrapped = error instanceof Error ? error : new Error(String(error))
      setErrorMessage(wrapped.message)
      setPermissionDenied(isPermissionError(wrapped))
      return null
    } finally {
      setLoading(false)
    }
  }, [loading, request])

  return { loading, errorMessage, permissionDenied, pick }
}

function PickResult({ errorMessage, permissionDenied, onRetry }: { errorMessage: string | null; permissionDenied: boolean; onRetry: () => void }) {
  if (!errorMessage) return null
  if (permissionDenied) return <PermissionNotice className="mt-2" message={errorMessage} />
  return <ErrorState className="mt-2" title="无法打开选择器" message={errorMessage} onRetry={onRetry} />
}

function SelectionRow({ value, loading, onPick, pickLabel }: { value: FileRef; loading: boolean; onPick: () => void; pickLabel: string }) {
  return (
    <div
      data-picker-selection=""
      aria-live="polite"
      className="flex w-full min-w-0 max-w-[460px] items-center gap-3 rounded-lg border border-line bg-muted-surface py-1.5 pl-2.5 pr-1.5"
    >
      <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-panel text-ink-2">
        <IconFileText size={17} stroke={1.6} />
      </span>
      <span className="grid min-w-0 flex-1">
        <strong className="truncate text-[13px] font-medium">{value.name}</strong>
        <small className="truncate font-mono text-[11px] text-ink-3">
          {value.access}
          {value.size ? ` · ${value.size.toLocaleString()} B` : ''}
        </small>
      </span>
      <button
        type="button"
        data-action="pick-file"
        aria-label={pickLabel}
        title={pickLabel}
        disabled={loading}
        onClick={onPick}
        className="grid size-7 shrink-0 place-items-center rounded-md text-ink-2 transition-colors hover:bg-hover hover:text-ink"
      >
        <IconArrowsExchange size={16} stroke={1.6} />
      </button>
    </div>
  )
}

/** SDK-backed file picker around `FengYuClient.files.open`. */
export function FilePicker({
  value,
  onChange,
  onCancel,
  onError,
  extensions,
  filters,
  label = '选择文件',
  className,
}: {
  value?: FileRef | null
  onChange: (value: FileRef | null) => void
  onCancel?: () => void
  onError?: (error: Error) => void
  extensions?: string[]
  filters?: FileFilter[]
  label?: string
  className?: string
}) {
  const client = useFengYuClient()
  const request = useCallback(
    () => client.files.open({ extensions: extensions ?? [], filters: filters ?? [] }),
    [client, extensions, filters],
  )
  const { loading, errorMessage, permissionDenied, pick } = useFengYuPick(request)

  const runPick = async () => {
    const failedBefore = Boolean(errorMessage)
    const result = await pick()
    if (result) {
      onChange(result)
      return
    }
    // `pick` keeps failures in `errorMessage` (reported via PickErrorBridge);
    // a clean null is a normal host-side cancellation.
    if (!failedBefore && !errorMessage) {
      onChange(null)
      onCancel?.()
    }
  }

  return (
    <div className={cn('grid justify-items-start gap-2', className)}>
      {value ? (
        <SelectionRow value={value} loading={loading} onPick={runPick} pickLabel={label} />
      ) : (
        <GhostButton data-action="pick-file" disabled={loading} onClick={runPick}>
          <IconFileText size={15} stroke={1.6} />
          {loading ? '打开中…' : label}
        </GhostButton>
      )}
      <PickResult errorMessage={errorMessage} permissionDenied={permissionDenied} onRetry={runPick} />
      <PickErrorBridge errorMessage={errorMessage} onError={onError} />
    </div>
  )
}

/** Bridge that forwards pick errors to `onError` exactly once per failure. */
function PickErrorBridge({ errorMessage, onError }: { errorMessage: string | null; onError?: (error: Error) => void }) {
  const lastReported = useRef<string | null>(null)
  useEffect(() => {
    if (errorMessage && errorMessage !== lastReported.current) {
      lastReported.current = errorMessage
      onError?.(new Error(errorMessage))
    }
    if (!errorMessage) lastReported.current = null
  }, [errorMessage, onError])
  return null
}

/** SDK-backed directory picker around `FengYuClient.files.inputDirectory`. */
export function DirectoryPicker({
  value,
  onChange,
  onCancel,
  onError,
  label = '选择目录',
  className,
}: {
  value?: string | null
  onChange: (value: string | null) => void
  onCancel?: () => void
  onError?: (error: Error) => void
  label?: string
  className?: string
}) {
  const client = useFengYuClient()
  const request = useCallback(async (): Promise<FileRef | null> => {
    const directory = await client.files.inputDirectory()
    return directory ? ({ name: directory, access: 'path' } as unknown as FileRef) : null
  }, [client])
  const { loading, errorMessage, permissionDenied, pick } = useFengYuPick(request)

  const runPick = async () => {
    const result = await pick()
    if (result) {
      onChange(result.name)
      return
    }
    onChange(null)
    onCancel?.()
  }

  return (
    <div className={cn('grid justify-items-start gap-2', className)}>
      {value ? (
        <div
          data-picker-selection=""
          aria-live="polite"
          className="flex w-full min-w-0 max-w-[460px] items-center gap-3 rounded-lg border border-line bg-muted-surface py-1.5 pl-2.5 pr-1.5"
        >
          <span className="grid size-8 shrink-0 place-items-center rounded-lg bg-panel text-ink-2">
            <IconFolderOpen size={17} stroke={1.6} />
          </span>
          <code className="min-w-0 flex-1 truncate font-mono text-[12.5px]">{value}</code>
          <button
            type="button"
            data-action="pick-directory"
            aria-label={label}
            title={label}
            disabled={loading}
            onClick={runPick}
            className="grid size-7 shrink-0 place-items-center rounded-md text-ink-2 transition-colors hover:bg-hover hover:text-ink"
          >
            <IconArrowsExchange size={16} stroke={1.6} />
          </button>
        </div>
      ) : (
        <GhostButton data-action="pick-directory" disabled={loading} onClick={runPick}>
          <IconFolderOpen size={15} stroke={1.6} />
          {loading ? '打开中…' : label}
        </GhostButton>
      )}
      <PickResult errorMessage={errorMessage} permissionDenied={permissionDenied} onRetry={runPick} />
      <PickErrorBridge errorMessage={errorMessage} onError={onError} />
    </div>
  )
}
