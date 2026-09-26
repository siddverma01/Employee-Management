import { cn } from '@/utils'
import { holidayLabel, getHolidayCellStyle } from '@/constants/holidayStatus'

// Leave request status colors (Light/Dark via CSS variables in tokens.css)
const LEAVE_STATUS_STYLES: Record<string, string> = {
  PENDING: 'bg-status-pending-bg text-status-pending-text ring-1 ring-inset ring-status-pending-border',
  APPROVED: 'bg-status-approved-bg text-status-approved-text ring-1 ring-inset ring-status-approved-border',
  REJECTED: 'bg-status-rejected-bg text-status-rejected-text ring-1 ring-inset ring-status-rejected-border',
  CANCELLED: 'bg-status-cancelled-bg text-status-cancelled-text ring-1 ring-inset ring-status-cancelled-border',
}

// Employment status colors (Light/Dark via CSS variables in tokens.css)
const EMPLOYMENT_STATUS_STYLES: Record<string, string> = {
  ACTIVE: 'bg-status-active-bg text-status-active-text ring-1 ring-inset ring-status-active-border',
  INACTIVE: 'bg-status-inactive-bg text-status-inactive-text ring-1 ring-inset ring-status-inactive-border',
}

// Holiday type colors - uses centralized holidayStatus config
const HOLIDAY_TYPES = ['HPE_HOLIDAY', 'US', 'PUBLIC', 'OPTIONAL', 'OBSERVED'] as const

export function StatusBadge({ status, className }: { status: string; className?: string }) {
  const upper = status.toUpperCase()

  // Leave request status
  if (upper in LEAVE_STATUS_STYLES) {
    return (
      <span className={cn('inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium', LEAVE_STATUS_STYLES[upper], className)}>
        {upper.toLowerCase().replace(/_/g, ' ')}
      </span>
    )
  }

  // Employment status
  if (upper in EMPLOYMENT_STATUS_STYLES) {
    return (
      <span className={cn('inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium', EMPLOYMENT_STATUS_STYLES[upper], className)}>
        {upper.toLowerCase().replace(/_/g, ' ')}
      </span>
    )
  }

  // Holiday type - use centralized config
  if (HOLIDAY_TYPES.includes(upper as typeof HOLIDAY_TYPES[number])) {
    const style = getHolidayCellStyle(upper)
    return (
      <span className={cn('inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium', className)} style={style}>
        {holidayLabel(upper)}
      </span>
    )
  }

  // Fallback
  return (
    <span className={cn('inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium', 'bg-status-active-bg text-status-active-text ring-1 ring-inset ring-status-active-border', className)}>
      {status.toLowerCase().replace(/_/g, ' ')}
    </span>
  )
}