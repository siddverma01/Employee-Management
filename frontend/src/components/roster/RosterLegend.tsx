import { ROSTER_STATUSES, rosterStatusClass } from '@/constants/rosterStatus'

/** "2026-09" -> "September 2026". */
export function monthLabel(ym: string): string {
  const [year, month] = ym.split('-').map(Number)
  if (!year || !month) return ym
  return new Date(year, month - 1, 1).toLocaleDateString('en-US', { month: 'long', year: 'numeric' })
}

/** Compact legend row used above roster grids. */
export function RosterLegend({ className }: { className?: string }) {
  return (
    <div className={`flex flex-wrap items-center gap-x-4 gap-y-1.5 ${className ?? ''}`}>
      {ROSTER_STATUSES.map((s) => (
        <span key={s.code} className="flex items-center gap-1.5 whitespace-nowrap text-[11px] text-surface-500">
          <span className={`roster-badge w-9 ${rosterStatusClass(s.code)}`}>{s.code}</span>
          {s.label}
        </span>
      ))}
    </div>
  )
}