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
 * Stitch dark mode: charcoal backgrounds with category accents
 */
const CHIP_COLORS = {
  LEAVE:
    'bg-[#FFFBEB] text-[#92400E] border-[#FDE68A] hover:border-[#FCD34D] dark:bg-[rgba(180,83,9,0.25)] dark:text-[#FCD34D] dark:border-[rgba(245,158,11,0.35)] dark:hover:border-amber-400',
  COMP_OFF:
    'bg-[#F1F5F9] text-[#475569] border-[#CBD5E1] hover:border-[#94A3B8] dark:bg-[#1E232B] dark:text-[#CBD5E1] dark:border-[#334155] dark:hover:border-slate-500',
  EVENT:
    'bg-[#FAF5FF] text-[#7E22CE] border-[#DDD6FE] hover:border-[#C4B5FD] dark:bg-[rgba(126,34,206,0.25)] dark:text-[#C4B5FD] dark:border-[rgba(167,139,250,0.35)] dark:hover:border-purple-400',
  BIRTHDAY:
    'bg-[#FDF2F8] text-[#BE185D] border-[#FBCFE8] hover:border-[#F9A8D4] dark:bg-[rgba(190,24,93,0.25)] dark:text-[#F9A8D4] dark:border-[rgba(244,114,182,0.35)] dark:hover:border-pink-400',
  HPE_HOLIDAY:
    'bg-[#ECFDF5] text-[#047857] border-[#A7F3D0] hover:border-[#6EE7B7] dark:bg-[rgba(5,150,105,0.20)] dark:text-[#6EE7B7] dark:border-[rgba(16,185,129,0.30)] dark:hover:border-emerald-400',
  US: 'bg-[#EFF6FF] text-[#1D4ED8] border-[#BFDBFE] hover:border-[#93C5FD] dark:bg-[rgba(14,116,144,0.28)] dark:text-[#7DD3FC] dark:border-[rgba(14,165,233,0.35)] dark:hover:border-sky-400',
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
  HPE_HOLIDAY: 'bg-[#D1FAE5] text-[#047857] dark:bg-[#0B3B2E] dark:text-[#6EE7B7]',
  US: 'bg-[#DBEAFE] text-[#1D4ED8] dark:bg-[#0C3040] dark:text-[#7DD3FC]',
} as const

const CHIP_BADGE_TEXT = {
  HPE_HOLIDAY: 'HPEH',
  US: 'US',
} as const

/** Full region name, so the compact badge's meaning survives in the tooltip. */
const CHIP_LABELS = {
  HPE_HOLIDAY: 'HPE Holiday',
  US: 'US Holiday',
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
    /* One shared geometry for both themes: 9px radius, 38px weekday rail, 8px
       cell padding. `dark:` below carries surface, border and ink colours only. */
    <div className="flex min-h-0 flex-1 flex-col w-full border border-[#E2E8F0] rounded-[9px] overflow-hidden bg-white dark:border-[#23252A] dark:bg-[#141518] dark:shadow-none">
      {/* Day Headers — 38px in both themes */}
      <div className="shrink-0 grid grid-cols-7 h-[38px] border-b border-[#E2E8F0] bg-[#F8FAFC] text-[11px] font-semibold leading-[38px] tracking-[0.08em] text-[#94A3B8] uppercase text-center items-center dark:border-[#23252A] dark:bg-[#141518] dark:text-slate-400">
        {WEEKDAYS.map((d, i) => (
          <div
            key={d}
            className={cn(
              'flex h-full items-center justify-center text-center',
              (i === 0 || i === 6) && 'text-[#CBD5E1] dark:text-[#94A3B8]',
            )}
          >
            {d}
          </div>
        ))}
      </div>

      {/* 7 x N Day Grid — rows share the available height via auto-rows-fr */}
      <div className="grid min-h-0 flex-1 auto-rows-fr grid-cols-7 divide-x divide-y divide-[#E2E8F0] text-xs dark:divide-[#23252B]">
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
                'calendar-cell min-h-0 p-2 flex flex-col items-stretch justify-start text-left transition-colors duration-150 bg-white overflow-hidden dark:bg-[#16171B]',
                /* Today is a background tint in both themes — no ring, no extra
                   border width, no shadow, so cell geometry is identical. */
                isToday
                  ? 'bg-[#F0FDF9] relative shadow-none dark:bg-[rgba(0,179,136,0.08)]'
                  : !cell.inMonth
                    ? 'bg-[#F8FAFC] text-slate-400 font-medium dark:bg-[#121316] dark:text-slate-600 dark:hover:bg-[#1D1E24]'
                    : isWeekend
                      ? 'bg-white hover:bg-[#F8FAFC] font-medium dark:bg-[#131418] dark:hover:bg-[#1D1E24]'
                      : 'bg-white hover:bg-[#F8FAFC] dark:hover:bg-[#1D1E24]',
              )}
            >
              {/* Day number / Today marker */}
              {isToday ? (
                <div className="flex w-full shrink-0 items-center justify-between">
                  <span className="w-6 h-6 min-w-6 shrink-0 rounded-full bg-[#00B388] text-white text-xs font-bold leading-none flex items-center justify-center shadow-none dark:bg-[#00B388] dark:shadow-none">
                    {cell.day}
                  </span>
                  <span className="shrink-0 text-[10px] font-bold leading-none tracking-[0.06em] uppercase text-[#059669] dark:text-[#34D399]">
                    Today
                  </span>
                </div>
              ) : (
                <span
                  className={cn(
                    /* The date is its own fixed-height block so the event list
                       below can never share its line; 16px row and 16px
                       leading are shared by both themes. */
                    'block h-[16px] shrink-0 leading-4',
                    cell.inMonth
                      ? 'text-xs font-semibold text-[#334155] dark:text-slate-300'
                      : 'text-[11px] font-medium text-[#CBD5E1] dark:text-slate-600',
                  )}
                >
                  {cell.day}
                </span>
              )}

              {/* Event pills */}
              <div className="mt-2 flex min-h-0 shrink-0 flex-col gap-1">
                {visible.map((ev) => {
                  const key = chipKeyFor(ev)
                  const isHoliday = key === 'HPE_HOLIDAY' || key === 'US'
                  const isEmphasis =
                    !isHoliday && EMPHASIS_EVENTS.some((t) => ev.title.toLowerCase().includes(t.toLowerCase()))

                  return (
                    <span
                      key={`${ev.kind}-${ev.id}-${ev.date}`}
                      title={
                        isHoliday
                          ? `${CHIP_LABELS[key as 'HPE_HOLIDAY' | 'US']} ${ev.title}`
                          : ev.title
                      }
                      className={cn(
                        /* Shared pill metrics for both themes: 24px tall, 8px
                           horizontal padding, 4px radius, 11px/500, ellipsis
                           truncation, no lift. CHIP_COLORS supplies the colours. */
                        'event-chip h-[24px] min-w-0 w-full flex items-center gap-1.5 px-2 rounded-[4px] border text-[11px] font-medium leading-none whitespace-nowrap overflow-hidden shadow-none dark:shadow-none',
                        CHIP_COLORS[key],
                        /* Town Hall reads as the headline item in both themes. */
                        isEmphasis && 'font-semibold',
                        !cell.inMonth && 'opacity-60',
                      )}
                    >
                      {isHoliday ? (
                        <span
                          className={cn(
                            /* 9px/700 uppercase prefix at a 3px radius, shared
                               by both themes. */
                            'px-0.5 py-0.5 rounded-[3px] text-[9px] font-bold leading-none tracking-wide uppercase shrink-0',
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
                      <span className="min-w-0 flex-1 overflow-hidden text-ellipsis whitespace-nowrap">{ev.title}</span>
                    </span>
                  )
                })}
                {overflow > 0 && (
                  <span className="px-1 text-[10px] leading-none text-slate-500 dark:text-slate-400">+{overflow} more</span>
                )}
              </div>
            </button>
          )
        })}
      </div>
    </div>
  )
}
