import { Component, type ErrorInfo, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { AlertTriangle } from 'lucide-react'

/**
 * Route-level error boundary: a lazy chunk that fails to load (a desktop auto-update
 * swapped the bundle under the running shell) or a render crash inside a page takes
 * down ONLY the route pane, with a reload affordance — not the whole app shell.
 * The boundary lives above <Routes> so it also catches the not-found route.
 */
interface RouteErrorBoundaryProps {
  children: ReactNode
}

interface RouteErrorBoundaryState {
  error: Error | null
}

class RouteErrorBoundaryImpl extends Component<RouteErrorBoundaryProps, RouteErrorBoundaryState> {
  state: RouteErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: Error): RouteErrorBoundaryState {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('[route]', error, info.componentStack)
  }

  render(): ReactNode {
    const { error } = this.state
    if (!error) return this.props.children
    return <RouteErrorView error={error} />
  }
}

function RouteErrorView({ error }: { error: Error }) {
  const { t } = useTranslation()
  return (
    <div className="cx-page" role="alert">
      <div className="cx-card route-error-card">
        <div className="flex items-center gap-2">
          <AlertTriangle size={18} />
          <h2 className="cx-page-title cx-page-title--flush">{t('common.routeErrorTitle')}</h2>
        </div>
        <p className="cx-muted">{t('common.routeErrorBody')}</p>
        {error.message && <p className="cx-muted mono text-ui-sm">{error.message}</p>}
        <button className="cx-btn cx-btn--primary" onClick={() => window.location.reload()}>
          {t('common.reloadPage')}
        </button>
      </div>
    </div>
  )
}

/** useTranslation needs a function component — the class delegates its fallback view. */
export default function RouteErrorBoundary({ children }: RouteErrorBoundaryProps) {
  return <RouteErrorBoundaryImpl>{children}</RouteErrorBoundaryImpl>
}
