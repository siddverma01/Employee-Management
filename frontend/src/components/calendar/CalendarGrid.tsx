import { useMemo } from 'react'
import type { CalendarEvent } from '@/types'
import { resolveHolidayDisplayType } from '@/constants/holidayStatus'
import { cn } from '@/utils'

interface CalendarGridProps {
  year: number
  month: number // 1-12
  events: CalendarEvent[]
  onSelectDay: (date: string) => void
  today?: string
}

const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'] as const

/** Number of event pills rendered per day before the "+N more" overflow. */
const MAX_VISIBLE_EVENTS = 3

interface DayCell {
  date: string
  day: number
  inMonth: boolean
}

function toDateStr(dt: Date): string {
  return `${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}-${String(dt.getDate()).padStart(2, '0')}`
}

/**
 * Event pill palette. `event-chip` owns layout + hover lift (index.css);
 * these classes only supply the per-category colour.
 */
const CHIP_COLORS = {
  LEAVE:
    'bg-amber-50 text-amber-900 border-amber-200 hover:border-amber-400 dark:bg-amber-950/70 dark:text-amber-200 dark:border-amber-700/60 dark:hover:border-amber-400',
  COMP_OFF:
    'bg-slate-100 text-slate-800 border-slate-200 hover:border-slate-400 dark:bg-slate-800 dark:text-slate-300 dark:border-slate-700 dark:hover:border-slate-500',
  EVENT:
    'bg-purple-50 text-purple-900 border-purple-200 hover:border-purple-400 dark:bg-purple-950/70 dark:text-purple-300 dark:border-purple-700/60 dark:hover:border-purple-500',
  BIRTHDAY:
    'bg-pink-50 text-pink-900 border-pink-200 hover:border-pink-400 dark:bg-pink-950/70 dark:text-pink-300 dark:border-pink-700/60 dark:hover:border-pink-500',
  HPE_HOLIDAY:
    'bg-emerald-50 text-emerald-900 border-emerald-200 hover:border-emerald-400 dark:bg-emerald-950/70 dark:text-emerald-300 dark:border-emerald-700/60 dark:hover:border-emerald-500',
  US: 'bg-blue-50 text-blue-900 border-blue-200 hover:border-blue-400 dark:bg-sky-950/70 dark:text-sky-300 dark:border-sky-700/60 dark:hover:border-sky-500',
} as const

/** Coloured dot used by the non-holiday pills (holidays use a text badge). */
const CHIP_DOTS = {
  LEAVE: 'bg-amber-500 dark:bg-amber-400',
  COMP_OFF: 'bg-slate-500 dark:bg-slate-400',
  EVENT: 'bg-purple-500 dark:bg-purple-400',
  BIRTHDAY: 'bg-pink-500 dark:bg-pink-400',
} as const

/** Text badge prefix used by the holiday pills. */
const CHIP_BADGES = {
  HPE_HOLIDAY: 'bg-emerald-200/80 text-emerald-800 dark:bg-emerald-800/80 dark:text-emerald-200',
  US: 'bg-blue-200/80 text-blue-800 dark:bg-sky-800/80 dark:text-sky-200',
} as const

const CHIP_BADGE_TEXT = {
  HPE_HOLIDAY: 'HPEH',
  US: 'US',
} as const

type ChipKey = keyof typeof CHIP_COLORS

/** Town Hall reads as the headline item, so it gets a heavier weight. */
const EMPHASIS_EVENTS = ['Town Hall']

/** Resolves a calendar entry to the palette key used for its pill. */
function chipKeyFor(event: CalendarEvent): ChipKey {
  if (event.kind === 'HOLIDAY') {
    return resolveHolidayDisplayType(event.extra ?? {}) === 'HPE_HOLIDAY'
      ? 'HPE_HOLIDAY'
      : 'US'
  }
  if (event.kind === 'LEAVE' || event.kind === 'COMP_OFF' || event.kind === 'BIRTHDAY' || event.kind === 'EVENT') {
    return event.kind
  }
  return 'EVENT'
}

export function CalendarGrid({ year, month, events, onSelectDay, today }: CalendarGridProps) {
  /**
   * Builds a full 7-column grid, padding the first and last weeks with the
   * adjacent months' days so every row is complete.
   */
  const cells = useMemo(() => {
    const list: DayCell[] = []
    const daysInMonth = new Date(year, month, 0).getDate()
    const firstDayIndex = new Date(year, month - 1, 1).getDay()
    const daysInPrevMonth = new Date(year, month - 1, 0).getDate()

    for (let i = firstDayIndex - 1; i >= 0; i -= 1) {
      const day = daysInPrevMonth - i
      list.push({ date: toDateStr(new Date(year, month - 2, day)), day, inMonth: false })
    }

    for (let day = 1; day <= daysInMonth; day += 1) {
      list.push({ date: toDateStr(new Date(year, month - 1, day)), day, inMonth: true })
    }

    const totalCells = Math.ceil(list.length / 7) * 7
    for (let day = 1; list.length < totalCells; day += 1) {
      list.push({ date: toDateStr(new Date(year, month, day)), day, inMonth: false })
    }

    return list
  }, [year, month])

  const eventsByDay = useMemo(() => {
    const map = new Map<string, CalendarEvent[]>()
    for (const ev of events) {
      const arr = map.get(ev.date) ?? []
      arr.push(ev)
      map.set(ev.date, arr)
    }
    return map
  }, [events])

  return (
    <div className="border border-slate-200 rounded-xl overflow-hidden shadow-sm bg-white dark:border-slate-800 dark:bg-[#111827] dark:shadow-xl">
      {/* Day Headers */}
      <div className="grid grid-cols-7 border-b border-slate-200 bg-slate-50 text-[11px] font-semibold tracking-wider text-slate-500 uppercase py-2.5 text-center dark:border-slate-800 dark:bg-[#0d131f] dark:text-slate-400 dark:py-0">
        {WEEKDAYS.map((d, i) => (
          <div
            key={d}
            className={cn('py-2.5 text-center', (i === 0 || i === 6) && 'text-slate-400 dark:text-slate-500')}
          >
            {d}
          </div>
        ))}
      </div>

      {/* 7 x N Day Grid */}
      <div className="grid grid-cols-7 divide-x divide-y divide-slate-200 text-xs dark:divide-slate-800/90">
        {cells.map((cell) => {
          const dayEvents = eventsByDay.get(cell.date) ?? []
          const isToday = cell.date === today
          const dow = new Date(`${cell.date}T00:00:00`).getDay()
          const isWeekend = dow === 0 || dow === 6
          const visible = dayEvents.slice(0, MAX_VISIBLE_EVENTS)
          const overflow = dayEvents.length - visible.length

          return (
            <button
              key={cell.date}
              type="button"
              onClick={() => onSelectDay(cell.date)}
              aria-label={`${cell.date}${dayEvents.length ? `, ${dayEvents.length} events` : ''}`}
              className={cn(
                'calendar-cell min-h-[128px] p-2 flex flex-col justify-start text-left transition-colors duration-150 bg-white dark:bg-[#16171b]',
                isToday
                  ? 'bg-emerald-50/30 border-2 border-emerald-500 rounded-md relative shadow-sm dark:bg-emerald-950/20 dark:border-[#00B388]'
                  : !cell.inMonth
                    ? 'bg-slate-50/70 text-slate-400 font-medium dark:bg-[#0b0f17]/60 dark:text-slate-600'
                    : isWeekend
                      ? 'bg-slate-50/40 hover:bg-slate-50 font-medium dark:bg-[#0d131f]/60 dark:hover:bg-[#1a2234]'
                      : 'hover:bg-slate-50/80 dark:hover:bg-[#1a2234]',
              )}
            >
              {/* Day number / Today marker */}
              {isToday ? (
                <div className="flex items-center justify-between">
                  <span className="w-6 h-6 rounded-full bg-emerald-500 text-white font-bold flex items-center justify-center text-xs shadow-sm dark:bg-[#00B388]">
                    {cell.day}
                  </span>
                  <span className="text-[10px] font-bold text-emerald-600 tracking-wide uppercase dark:text-[#00B388]">
                    Today
                  </span>
                </div>
              ) : (
                <span
                  className={cn(
                    'font-semibold',
                    cell.inMonth
                      ? 'text-slate-700 dark:text-slate-300'
                      : 'font-medium text-slate-400 dark:text-slate-600',
                  )}
                >
                  {cell.day}
                </span>
              )}

              {/* Event pills */}
              <div className="flex flex-col gap-1">
                {visible.map((ev) => {
                  const key = chipKeyFor(ev)
                  const isHoliday = key === 'HPE_HOLIDAY' || key === 'US'
                  const isEmphasis =
                    !isHoliday && EMPHASIS_EVENTS.some((t) => ev.title.toLowerCase().includes(t.toLowerCase()))

                  return (
                    <span
                      key={`${ev.kind}-${ev.id}-${ev.date}`}
                      title={ev.title}
                      className={cn(
                        'event-chip px-2 py-1 rounded border text-[11px] font-medium shadow-sm',
                        CHIP_COLORS[key],
                        isEmphasis && 'font-semibold text-purple-900 dark:text-purple-200',
                        !cell.inMonth && 'opacity-60',
                      )}
                    >
                      {isHoliday ? (
                        <span
                          className={cn(
                            'px-0.5 py-0.5 rounded text-[9px] font-bold tracking-wide uppercase',
                            CHIP_BADGES[key as 'HPE_HOLIDAY' | 'US'],
                          )}
                        >
                          {CHIP_BADGE_TEXT[key as 'HPE_HOLIDAY' | 'US']}
                        </span>
                      ) : (
                        <span
                          className={cn(
                            'w-1.5 h-1.5 rounded-full shrink-0',
                            CHIP_DOTS[key as 'LEAVE' | 'COMP_OFF' | 'EVENT' | 'BIRTHDAY'],
                          )}
                        />
                      )}
                      <span className="truncate">{ev.title}</span>
                    </span>
                  )
                })}
                {overflow > 0 && (
                  <span className="px-1 text-[10px] text-slate-500 dark:text-slate-500">+{overflow} more</span>
                )}
              </div>
            </button>
          )
        })}
      </div>
    </div>
  )
}
