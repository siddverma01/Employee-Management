import { ROSTER_STATUSES, getAttendanceCellStyle } from '@/constants/rosterStatus'

/** "2026-09" -> "September 2026". */
export function monthLabel(ym: string): string {
  const [year, month] = ym.split('-').map(Number)
  if (!year || !month) return ym
  return new Date(year, month - 1, 1).toLocaleDateString('en-US', { month: 'long', year: 'numeric' })
}

/** Compact legend row used above roster grids. It wraps onto extra lines whenever
 *  the available width shrinks. `min-w-0` lets it act as a shrinkable flex item,
 *  so the badge+label units reflow instead of pushing the row horizontally. */
export function RosterLegend({ className }: { className?: string }) {
  return (
    <div className={`flex min-w-0 flex-wrap items-center gap-x-4 gap-y-1.5 ${className ?? ''}`}>
      {ROSTER_STATUSES.map((s) => (
        <span key={s.code} className="inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap text-[11px] text-surface-500">
          <span
            data-status={s.code}
            className="inline-flex h-4 min-w-9 items-center justify-center whitespace-nowrap rounded-[2px] border border-black/5 px-1 text-[9px] font-bold"
            style={getAttendanceCellStyle(s.code)}
          >
            {s.code}
          </span>
          {s.label}
        </span>
      ))}
    </div>
  )
}