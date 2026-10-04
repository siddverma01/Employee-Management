import { useId, useState } from 'react'
import { ChevronDown } from 'lucide-react'
import { ROSTER_STATUSES, getAttendanceCellStyle } from '@/constants/rosterStatus'

/** "2026-09" -> "September 2026". */
export function monthLabel(ym: string): string {
  const [year, month] = ym.split('-').map(Number)
  if (!year || !month) return ym
  return new Date(year, month - 1, 1).toLocaleDateString('en-US', { month: 'long', year: 'numeric' })
}

/** Statuses shown inline without any interaction. These are the codes the roster
 *  actually reports on in a typical month, so the default row stays scannable. */
export const ROSTER_LEGEND_PRIMARY = ['WO', 'WFO', 'WFH', 'PL', 'SL', 'CO'] as const

/** One code + its colour. Colour comes from the shared attendance palette, so a
 *  chip here always matches the matching cell in the grid in either theme. */
function LegendPill({ code, label }: { code: string; label: string }) {
  return (
    <span className="roster-legend-item inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap text-[11px] text-surface-500">
      <span
        data-status={code}
        className="inline-flex h-[18px] min-w-[30px] shrink-0 items-center justify-center whitespace-nowrap rounded-[3px] border border-black/5 px-1 text-[9px] font-bold"
        style={getAttendanceCellStyle(code)}
      >
        {code}
      </span>
      {label}
    </span>
  )
}

/**
 * Roster legend.
 *
 * Previously every one of the 17 statuses rendered inline in a wrapping flex row,
 * which cost two lines of vertical space above the grid for codes that are rarely
 * present. Now the six most common codes stay inline and the complete set is
 * revealed by an accessible disclosure — every code and its colour is preserved,
 * nothing is renamed or removed.
 */
export function RosterLegend({ className }: { className?: string }) {
  const [open, setOpen] = useState(false)
  const panelId = useId()

  const primary = ROSTER_LEGEND_PRIMARY
    .map((code) => ROSTER_STATUSES.find((s) => s.code === code))
    .filter((s): s is (typeof ROSTER_STATUSES)[number] => Boolean(s))
  const secondary = ROSTER_STATUSES.filter((s) => !primary.includes(s))

  return (
    <div className={`flex min-w-0 flex-wrap items-center gap-x-4 gap-y-2 ${className ?? ''}`}>
      {primary.map((s) => (
        <LegendPill key={s.code} code={s.code} label={s.label} />
      ))}

      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-controls={panelId}
        className="inline-flex h-6 shrink-0 items-center gap-1 rounded-md border border-surface-200 bg-surface-0 px-2 text-[11px] font-medium text-surface-600 transition-colors hover:bg-surface-100 hover:text-surface-700 dark:border-[#20252E] dark:bg-[#1B1E23] dark:text-surface-300 dark:hover:bg-[#242833]"
      >
        Legend
        <ChevronDown
          className={`h-3 w-3 transition-transform duration-150 ${open ? 'rotate-180' : ''}`}
          aria-hidden="true"
        />
      </button>

      {open && (
        <div
          id={panelId}
          className="roster-legend-panel basis-full rounded-lg border border-surface-200 bg-surface-0 p-3 dark:border-[#20252E] dark:bg-[#1E2128]"
        >
          <div className="grid grid-cols-2 gap-x-4 gap-y-2 sm:grid-cols-3 lg:grid-cols-4">
            {secondary.map((s) => (
              <LegendPill key={s.code} code={s.code} label={s.label} />
            ))}
          </div>
        </div>
      )}
    </div>
  )
}