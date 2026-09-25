import { cn } from '@/utils'

// All status badges use the semantic muted-teal status tokens, which flip
// between a soft pastel (Light) and a dark tinted surface (Dark) in tokens.css.
const STYLES: Record<string, string> = {
  PENDING: 'bg-status-active-bg text-status-active-text ring-1 ring-inset ring-status-active-border',
  APPROVED: 'bg-status-active-bg text-status-active-text ring-1 ring-inset ring-status-active-border',
  REJECTED: 'bg-status-active-bg text-status-active-text ring-1 ring-inset ring-status-active-border',
  CANCELLED: 'bg-status-active-bg text-status-active-text ring-1 ring-inset ring-status-active-border',
  ACTIVE: 'bg-status-active-bg text-status-active-text ring-1 ring-inset ring-status-active-border',
  INACTIVE: 'bg-status-active-bg text-status-active-text ring-1 ring-inset ring-status-active-border',
  PUBLIC: 'bg-status-active-bg text-status-active-text ring-1 ring-inset ring-status-active-border',
  OPTIONAL: 'bg-status-active-bg text-status-active-text ring-1 ring-inset ring-status-active-border',
  OBSERVED: 'bg-status-active-bg text-status-active-text ring-1 ring-inset ring-status-active-border',
}

export function StatusBadge({ status, className }: { status: string; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium',
        STYLES[status] ?? 'bg-status-active-bg text-status-active-text ring-1 ring-inset ring-status-active-border',
        className,
      )}
    >
      {status.toLowerCase().replace(/_/g, ' ')}
    </span>
  )
}