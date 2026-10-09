import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Check, GitBranch, Plus } from 'lucide-react'
import { services } from '@/services'
import type { Conversation } from '@/stores/aiSession'
import { useAiSessionStore } from '@/stores/aiSession'
import type { WorkspaceBranch, WorkspaceBranchIssue } from '@/services/types'

/**
 * Branch picker for a conversation's coding workspace (upstream agent design port, 4.1.0):
 * search-filtered local branches, current one checked, create-off-HEAD at the bottom. Switch
 * refusals render inline — git's own blockers (files that would be overwritten, the branch
 * living in another worktree, …) arrive as classified issue codes this maps to i18n strings
 * with the first blocked paths spelled out. The list re-reads on every open so a switch made
 * elsewhere (or a failed one) never leaves a stale snapshot on screen.
 */
export default function WorkspaceBranchMenu({ conv, onClose }: {
  conv: Conversation
  onClose: () => void
}) {
  const { t, i18n } = useTranslation()
  const [branches, setBranches] = useState<WorkspaceBranch[] | null>(null)
  const [loading, setLoading] = useState(true)
  const [requestError, setRequestError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const [search, setSearch] = useState('')
  const [createMode, setCreateMode] = useState(false)
  const [createName, setCreateName] = useState('')
  const rootRef = useRef<HTMLDivElement | null>(null)
  const convRef = useRef(conv)
  convRef.current = conv

  useEffect(() => {
    if (conv.backendId == null) {
      setLoading(false)
      return
    }
    let cancelled = false
    void services.chat.listWorkspaceBranches(convRef.current.backendId!)
      .then(out => { if (!cancelled) setBranches(out.branches) })
      .catch(() => { if (!cancelled) setRequestError(t('aichat.workspaceBranchErrRequest', { error: '' })) })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
    // `t` deliberately not a dep: a locale switch must not refetch the list.
  }, [conv.backendId])

  // Outside click / Escape closes — the picker is a transient popover, not a panel.
  useEffect(() => {
    const onPointerDown = (event: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) onClose()
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [onClose])

  const issueText = (issue: WorkspaceBranchIssue): string => {
    const paths = (issue.paths ?? []).filter(path => path.trim().length > 0)
    const visible = paths.slice(0, 2)
    const extra = paths.length > 2
      ? t('aichat.workspaceBranchMoreFiles', { count: paths.length - 2 })
      : ''
    const joined = visible.join(i18n.language.startsWith('zh') ? '、' : ', ')
    switch (issue.code) {
      case 'invalid-branch-name': return t('aichat.workspaceBranchErrInvalid')
      case 'branch-already-exists': return t('aichat.workspaceBranchErrExists')
      case 'target-branch-not-found': return t('aichat.workspaceBranchErrNotFound')
      case 'tracked-changes-would-be-overwritten':
        return t('aichat.workspaceBranchErrTracked', { paths: joined, extra })
      case 'untracked-changes-would-be-overwritten':
        return t('aichat.workspaceBranchErrUntracked', { paths: joined, extra })
      case 'conflicts-present': return t('aichat.workspaceBranchErrConflicts')
      case 'operation-in-progress': return t('aichat.workspaceBranchErrOngoing')
      case 'branch-in-other-worktree': return t('aichat.workspaceBranchErrWorktree')
      default:
        return issue.detail?.trim() || issue.message || t('aichat.workspaceBranchErrUnknown')
    }
  }

  const switchBranch = async (name: string, create: boolean) => {
    if (pending) return
    if (!create && branches?.some(branch => branch.name === name && branch.current)) {
      onClose()
      return
    }
    setPending(true)
    setMessage(null)
    try {
      const result = await useAiSessionStore.getState()
        .switchWorkspaceBranch(convRef.current, name, create)
      if (result.ok) {
        // The chip has already re-rendered via the store; refresh the list in place so a
        // follow-up failure (e.g. the switch landed but a second one is refused) still
        // shows accurate state without reopening.
        if (branches != null) {
          setBranches(branches.map(branch => ({
            ...branch, current: branch.name === result.branchName,
          })))
        }
        onClose()
        return
      }
      const primary = result.issues[0]
      setMessage(primary ? issueText(primary) : t('aichat.workspaceBranchErrUnknown'))
    } catch (error) {
      setMessage(t('aichat.workspaceBranchErrRequest',
        { error: error instanceof Error ? error.message : '' }))
    } finally {
      setPending(false)
    }
  }

  const submitCreate = () => {
    const name = createName.trim()
    if (name.length === 0) return
    void switchBranch(name, true)
  }

  const needle = search.trim().toLowerCase()
  const filtered = (branches ?? []).filter(
    branch => needle.length === 0 || branch.name.toLowerCase().includes(needle))

  return (
    <div ref={rootRef} className="cx-card composer-menu composer-menu--branch">
      <div className="cx-muted composer-menu__hint">{t('aichat.workspaceBranchSwitchTitle')}</div>
      <input
        className="composer-menu__input"
        value={search}
        placeholder={t('aichat.workspaceBranchSearch')}
        onChange={event => setSearch(event.target.value)}
        autoFocus
      />
      <div className="composer-menu__scroll">
        {loading || requestError
          ? <div className="composer-menu__hint">
              {requestError ?? t('aichat.workspaceBranchLoading')}
            </div>
          : filtered.length === 0
            ? <div className="composer-menu__hint">{t('aichat.workspaceBranchEmpty')}</div>
            : filtered.map(branch => (
                <button
                  key={branch.name}
                  className="cx-btn composer-menu__option"
                  disabled={pending}
                  onClick={() => void switchBranch(branch.name, false)}
                >
                  <GitBranch size={14} />
                  <span className="composer-menu__branch-name">{branch.name}</span>
                  {pending
                    ? <span className="cx-spin" />
                    : branch.current ? <Check size={14} /> : null}
                </button>
              ))}
      </div>
      {message && <div className="composer-menu__message">{message}</div>}
      <div className="composer-menu__create">
        {createMode
          ? (
            <>
              <input
                className="composer-menu__input"
                value={createName}
                placeholder={t('aichat.workspaceBranchCreatePlaceholder')}
                onChange={event => setCreateName(event.target.value)}
                onKeyDown={event => { if (event.key === 'Enter') submitCreate() }}
              />
              <button
                className="cx-btn composer-menu__option composer-menu__option--create"
                disabled={pending || createName.trim().length === 0}
                onClick={submitCreate}
              >
                <Plus size={14} />
                {t('aichat.workspaceBranchCreateGo')}
              </button>
            </>
          )
          : (
            <button
              className="cx-btn composer-menu__item"
              disabled={pending}
              onClick={() => setCreateMode(true)}
            >
              <Plus size={14} />
              {t('aichat.workspaceBranchCreate')}
            </button>
          )}
      </div>
    </div>
  )
}
