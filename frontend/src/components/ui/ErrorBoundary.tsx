import { Component, type ErrorInfo, type ReactNode } from 'react'
import { AlertTriangle } from 'lucide-react'
import { Button } from '@/components/ui/Button'

interface ErrorBoundaryProps {
  children: ReactNode
  /** Optional short description shown above the raw error message. */
  title?: string
}

interface ErrorBoundaryState {
  error: Error | null
}

/**
 * Catches render-time exceptions in a route subtree so a single broken
 * component degrades to an actionable message instead of a blank screen. The
 * surrounding application shell (header, sidebar, navigation) is preserved
 * because this boundary is mounted inside the layout, around the outlet.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Never swallow silently: surface the full diagnostic for development.
    console.error('Unhandled render error caught by ErrorBoundary:', error, info.componentStack)
  }

  private reset = () => this.setState({ error: null })

  render() {
    const { error } = this.state
    if (!error) return this.props.children

    return (
      <div role="alert" className="mx-auto max-w-2xl rounded-lg border border-error-2000/30 bg-error-50/60 p-6">
        <div className="flex items-start gap-3">
          <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-error-600" />
          <div className="min-w-0 flex-1">
            <p className="text-sm font-semibold text-error-800">
              {this.props.title ?? 'This page hit an unexpected error.'}
            </p>
            <p className="mt-1 break-words text-xs text-error-700">{error.message}</p>
            <div className="mt-4 flex flex-wrap gap-2">
              <Button size="sm" onClick={this.reset}>Try again</Button>
              <Button size="sm" variant="secondary" onClick={() => window.location.reload()}>
                Reload page
              </Button>
            </div>
          </div>
        </div>
      </div>
    )
  }
}
