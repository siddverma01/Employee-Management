import { useEffect, useMemo, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, Filter, Clock } from 'lucide-react'
import { calendarApi, holidayApi, eventApi } from '@/api'
import { useTeam } from '@/hooks/useTeam'
import { CalendarGrid } from '@/components/calendar/CalendarGrid'
import { Modal } from '@/components/ui/Modal'
import { LoadingState } from '@/components/ui/LoadingState'
import { formatDate } from '@/utils'
import { getHolidayDisplayInfo } from '@/constants/holidayStatus'
import {
  CALENDAR_CATEGORIES,
  calendarEventKindLabel,
  getCalendarEntryDisplay,
} from '@/constants/calendarEventKind'
import type { CalendarEvent } from '@/types'
import { cn } from '@/utils'

/** Modal "Type" row: full category name, e.g. "HPE Holiday" / "US Holiday". */
function eventTypeLabel(event: CalendarEvent): string {
  if (event.kind === 'HOLIDAY') {
    return getHolidayDisplayInfo(event).label
  }
  return calendarEventKindLabel(event.kind) || event.kind
}

type CalendarView = 'month' | 'week' | 'day' | 'list'

const CALENDAR_FILTER_KINDS = [
  { kind: 'LEAVE', label: 'Leaves' },
  { kind: 'COMP_OFF', label: 'Comp Offs' },
  { kind: 'HOLIDAY', label: 'Holidays' },
  { kind: 'EVENT', label: 'Events' },
  { kind: 'BIRTHDAY', label: 'Birthdays' },
] as const

function CalendarLegend({ highlightedCategories, onToggle }: { 
  highlightedCategories: Set<string>
  onToggle: (key: string) => void
}) {
  return (
    <div className="flex flex-wrap items-center gap-4">
      <span className="text-slate-500 dark:text-slate-400 font-semibold tracking-wide uppercase text-[10px]">Legend:</span>
      <ul className="flex flex-wrap items-center gap-4" role="list" aria-label="Calendar event categories">
        {CALENDAR_CATEGORIES.map((category) => {
          const isHighlighted = highlightedCategories.has(category.key)
          return (
            <li key={category.key}>
              <button
                type="button"
                onClick={() => onToggle(category.key)}
                aria-pressed={isHighlighted}
                className={cn(
                  'flex items-center gap-1.5 text-xs cursor-pointer transition-colors',
                  isHighlighted
                    ? 'text-slate-700 dark:text-slate-100 font-semibold'
                    : 'text-slate-600 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200',
                )}
                title={category.label}
              >
                <span
                  aria-hidden="true"
                  className={cn('w-2.5 h-2.5 shrink-0 rounded-[3px]', category.swatchClass)}
                />
                <span>{category.label}</span>
              </button>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

export function CalendarPage() {
  const now = new Date()
  const [year, setYear] = useState(now.getFullYear())
  const [month, setMonth] = useState(now.getMonth() + 1)
  const [selected, setSelected] = useState<CalendarEvent | null>(null)
  const [view, setView] = useState<CalendarView>('month')
  const [filterOpen, setFilterOpen] = useState(false)
  const [enabledKinds, setEnabledKinds] = useState<Set<string>>(
    () => new Set<string>(CALENDAR_FILTER_KINDS.map((k) => k.kind)),
  )
  const [highlightedCategories, setHighlightedCategories] = useState<Set<string>>(new Set())
  const filterRef = useRef<HTMLDivElement>(null)
  const { effectiveTeamId } = useTeam()

  const todayStr = useMemo(() => {
    const t = new Date()
    return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`
  }, [])

  const { data: events, isLoading } = useQuery({
    queryKey: ['calendar', year, month, effectiveTeamId ?? 'default'],
    queryFn: () => calendarApi.month(year, month, effectiveTeamId),
  })

  // preload holidays & events to support the legend
  useQuery({ queryKey: ['holidays', 'current'], queryFn: () => holidayApi.list() })
  useQuery({ queryKey: ['events', 'current'], queryFn: () => eventApi.list() })

  const yearMonth = (y: number, m: number) => new Date(y, m - 1, 1)

  const change = (delta: number) => {
    const d = new Date(year, month - 1 + delta, 1)
    setYear(d.getFullYear())
    setMonth(d.getMonth() + 1)
  }

  const goToday = () => {
    setYear(now.getFullYear())
    setMonth(now.getMonth() + 1)
  }

  const toggleHighlight = (key: string) => {
    setHighlightedCategories((prev) => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }

  const toggleKind = (kind: string) => {
    setEnabledKinds((prev) => {
      const next = new Set(prev)
      if (next.has(kind)) next.delete(kind)
      else next.add(kind)
      return next
    })
  }

  // close the filter popover on outside click
  useEffect(() => {
    if (!filterOpen) return
    const onPointerDown = (e: MouseEvent) => {
      if (filterRef.current && !filterRef.current.contains(e.target as Node)) {
        setFilterOpen(false)
      }
    }
    document.addEventListener('mousedown', onPointerDown)
    return () => document.removeEventListener('mousedown', onPointerDown)
  }, [filterOpen])

  const filteredEvents = useMemo(() => {
    if (!events) return []
    return events.filter((ev) => {
      if (!enabledKinds.has(ev.kind)) return false
      if (highlightedCategories.size === 0) return true
      const display = getCalendarEntryDisplay(ev)
      return display.isHoliday
        ? highlightedCategories.has('HPE_HOLIDAY') || highlightedCategories.has('US')
        : highlightedCategories.has(display.label.replace(' ', '_').toUpperCase()) ||
            highlightedCategories.has(ev.kind)
    })
  }, [events, enabledKinds, highlightedCategories])

  // Calculate stats for the header summary
  const stats = useMemo(() => {
    if (!events) return { total: 0, leaves: 0 }
    const leaves = events.filter(e => e.kind === 'LEAVE' || e.kind === 'COMP_OFF').length
    return { total: events.length, leaves }
  }, [events])

  return (
    <div className="flex h-full flex-col overflow-auto">
      <section className="p-5 pb-3 border-b border-slate-200 bg-white shrink-0 dark:border-slate-800 dark:bg-[#111827]">
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
          {/* Title & Live Status Indicator */}
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-3">
              <h1 className="text-2xl font-bold text-slate-900 dark:text-white tracking-tight">Team calendar</h1>
              <span className="inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold bg-emerald-50 text-emerald-700 border border-emerald-200 dark:bg-emerald-950/60 dark:text-emerald-400 dark:border-emerald-800/60">
                <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 mr-1.5 animate-pulse dark:bg-emerald-400"></span>
                Live Schedule
              </span>
            </div>
            <p className="block text-xs text-slate-500 dark:text-slate-400 mt-1">
              Leaves, comp offs, holidays, birthdays and company events
            </p>
          </div>

          {/* View Switcher, Date Navigator & Filter */}
          <div className="flex flex-wrap items-center gap-2.5">
            {/* Segmented View Switcher */}
            <div className="inline-flex shrink-0 items-center p-0.5 rounded-lg bg-slate-50 border border-slate-200 dark:bg-[#1e293b]/70 dark:border-slate-700 text-xs">
              {(['month', 'week', 'day', 'list'] as CalendarView[]).map((v) => (
                <button
                  key={v}
                  type="button"
                  onClick={() => setView(v)}
                  aria-pressed={view === v}
                  className={cn(
                    'whitespace-nowrap shrink-0 px-3 py-1 text-xs rounded-[6px] font-medium',
                    view === v
                      ? 'text-white bg-slate-700 shadow-sm border border-slate-600/80'
                      : 'text-surface-500 dark:text-slate-400 hover:text-surface-900 dark:hover:text-white transition'
                  )}
                >
                  {v.charAt(0).toUpperCase() + v.slice(1)}
                </button>
              ))}
            </div>

            {/* Grouped Date Navigator */}
            <div className="flex items-center bg-slate-50 border border-slate-200 dark:bg-[#1e293b]/70 dark:border-slate-700 rounded-lg p-0.5 shadow-sm">
              <button
                type="button"
                onClick={goToday}
                className="px-2.5 py-1 text-xs font-semibold text-surface-800 dark:text-slate-200 hover:text-surface-900 dark:hover:text-white hover:bg-surface-200 rounded transition dark:hover:bg-slate-700/80"
              >
                Today
              </button>
              <div className="h-3 w-[1px] bg-slate-700 mx-1" />
              <button
                type="button"
                onClick={() => change(-1)}
                title="Previous Month"
                className="p-1 text-surface-500 dark:text-slate-400 hover:text-surface-900 dark:hover:text-white hover:bg-surface-200 rounded transition dark:hover:bg-slate-700/80"
              >
                <ChevronLeft className="w-4 h-4" />
              </button>
              <button
                type="button"
                onClick={() => change(1)}
                title="Next Month"
                className="p-1 text-surface-500 dark:text-slate-400 hover:text-surface-900 dark:hover:text-white hover:bg-surface-200 rounded transition dark:hover:bg-slate-700/80"
              >
                <ChevronRight className="w-4 h-4" />
              </button>
            </div>

            {/* Filter Dropdown */}
            <div className="relative" ref={filterRef}>
              <button
                type="button"
                onClick={() => setFilterOpen((o) => !o)}
                aria-expanded={filterOpen}
                aria-haspopup="true"
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-slate-50 border border-slate-200 dark:bg-[#1e293b]/70 dark:border-slate-700 text-xs text-slate-700 dark:text-slate-200 hover:text-slate-900 hover:border-slate-400 dark:hover:text-white dark:hover:border-slate-500 transition shadow-sm font-medium"
              >
                <Filter className="w-3.5 h-3.5 text-surface-500 dark:text-slate-400" />
                <span>Filter</span>
              </button>

              {filterOpen && (
                <div className="absolute right-0 z-20 mt-2 w-52 rounded-lg border border-slate-200 bg-white p-1.5 shadow-xl dark:border-slate-700 dark:bg-[#1a1b20]">
                  <p className="px-2.5 py-1.5 text-[10px] font-semibold uppercase tracking-wider text-surface-500 dark:text-slate-400">
                    Event categories
                  </p>
                  {CALENDAR_FILTER_KINDS.map(({ kind, label }) => (
                    <label
                      key={kind}
                      className="flex cursor-pointer items-center gap-2.5 rounded-md px-2.5 py-1.5 text-xs text-surface-700 dark:text-slate-300 hover:bg-surface-100 hover:text-surface-900 transition dark:hover:bg-slate-800/60 dark:hover:text-surface-900 dark:hover:text-white"
                    >
                      <input
                        type="checkbox"
                        checked={enabledKinds.has(kind)}
                        onChange={() => toggleKind(kind)}
                        className="h-3.5 w-3.5 rounded border-surface-300 bg-white text-emerald-600 dark:border-slate-600 dark:bg-slate-800 dark:text-emerald-500 focus:ring-emerald-500/40 focus:ring-offset-0"
                      />
                      <span>{label}</span>
                    </label>
                  ))}
                </div>
              )}
            </div>
          </div>
        </div>

        {/* Month title & stats */}
        <div className="mt-4 flex items-center justify-between gap-3">
          <h2 className="text-xl font-bold text-slate-900 dark:text-white tracking-tight">
            {yearMonth(year, month).toLocaleDateString('en-US', { month: 'long', year: 'numeric' })}
          </h2>
          <span className="text-xs text-slate-500 dark:text-slate-400 font-medium">
            {stats.total} Events • {stats.leaves} Leaves scheduled
          </span>
        </div>
      </section>

      {/* Calendar viewport */}
      <section className="flex-1 p-5 min-w-[900px] flex flex-col justify-start" data-purpose="calendar-viewport">
        {isLoading ? (
          <LoadingState label="Loading calendar…" />
        ) : (
          <CalendarGrid
            year={year}
            month={month}
            events={filteredEvents}
            onSelectDay={(ds) => {
              const evs = (events ?? []).filter((e) => e.date === ds)
              if (evs.length > 0) setSelected(evs[0])
            }}
            today={todayStr}
          />
        )}

        {/* Legend & Timezone */}
        <footer className="mt-4 pt-3 border-t border-slate-200 dark:border-t dark:border-slate-800 flex flex-wrap items-center justify-between gap-3 text-xs text-slate-600 dark:text-slate-400">
          <CalendarLegend
            highlightedCategories={highlightedCategories}
            onToggle={toggleHighlight}
          />

          <div className="flex items-center gap-2 text-[11px] text-slate-500 dark:text-slate-400">
            <Clock className="w-3.5 h-3.5" />
            <span>Timezone: UTC+05:30 (IST)</span>
          </div>
        </footer>
      </section>

    {/* Modal for Event Details */}

    <Modal open={selected !== null} onClose={() => setSelected(null)} title="Event details" size="sm">
      {selected && (
        <div className="space-y-3 text-sm">
          <div>
            <p className="text-xs text-slate-500 dark:text-slate-400">Title</p>
            <p className="font-medium text-slate-900 dark:text-white">
              {selected.title}
              {selected.kind === 'HOLIDAY' && selected.extra?.holidayType === 'HPE_HOLIDAY' && (
                <span
                  className="ml-2 inline-flex items-center gap-1 rounded px-1.5 py-0.5 align-middle text-[10px] font-semibold"
                  style={getHolidayDisplayInfo(selected).style}
                >
                  {getHolidayDisplayInfo(selected).compactLabel}
                </span>
              )}
            </p>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <p className="text-xs text-slate-500 dark:text-slate-400">Type</p>
              <p className="font-medium">{eventTypeLabel(selected)}</p>
            </div>
            <div>
              <p className="text-xs text-slate-500 dark:text-slate-400">Date</p>
              <p className="font-medium">{formatDate(selected.date)}</p>
            </div>
          </div>
          {selected.employeeName && (
            <div>
              <p className="text-xs text-slate-500 dark:text-slate-400">Employee</p>
              <p className="font-medium">{selected.employeeName} <span className="text-surface-500 dark:text-slate-400">({selected.employeeCode})</span></p>
            </div>
          )}
          {selected.leaveType && (
            <div className="grid grid-cols-2 gap-3">
              <div>
                <p className="text-xs text-slate-500 dark:text-slate-400">Leave type</p>
                <p className="font-medium">{selected.leaveTypeLabel ?? '-'}</p>
              </div>
              <div>
                <p className="text-xs text-slate-500 dark:text-slate-400">Status</p>
                <p className="font-medium">{selected.status}</p>
              </div>
            </div>
          )}
          {selected.description && (
            <div>
              <p className="text-xs text-slate-500 dark:text-slate-400">Details</p>
              <p className="text-surface-700 dark:text-slate-300">{selected.description}</p>
            </div>
          )}
        </div>
      )}
    </Modal>
  </div>
  )
}