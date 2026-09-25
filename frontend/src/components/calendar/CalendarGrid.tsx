import { useMemo } from 'react'
import { useState } from 'react'
import type { CalendarEvent } from '@/types'
import { cn } from '@/utils'

interface CalendarGridProps {
  year: number
  month: number // 1-12
  events: CalendarEvent[]
  onSelectDay: (date: string) => void
  today?: string
}

const KIND_COLORS: Record<string, string> = {
  LEAVE: 'bg-sky-50 text-sky-700 border-sky-200',
  COMP_OFF: 'bg-violet-50 text-violet-700 border-violet-200',
  HOLIDAY: 'bg-emerald-50 text-emerald-700 border-emerald-200',
  BIRTHDAY: 'bg-pink-50 text-pink-700 border-pink-200',
  EVENT: 'bg-amber-50 text-amber-700 border-amber-200',
}

export function CalendarGrid({ year, month, events, onSelectDay, today }: CalendarGridProps) {
  const daysInMonth = new Date(year, month, 0).getDate()
  const firstDayIndex = new Date(year, month - 1, 1).getDay()

  const days = useMemo(() => {
    const list = Array.from({ length: daysInMonth }, (_, i) => i + 1)
    return [...Array(firstDayIndex).fill(null), ...list]
  }, [daysInMonth, firstDayIndex])

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
    <div className="overflow-hidden rounded-xl border border-surface-200 bg-surface-0">
      <div className="grid grid-cols-7 border-b border-surface-200 bg-surface-50">
        {['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map((d) => (
          <div key={d} className="px-2 py-2 text-center text-xs font-semibold text-surface-500">
            {d}
          </div>
        ))}
      </div>
      <div className="grid grid-cols-7">
        {days.map((day, idx) => {
          if (day === null) return <div key={`empty-${idx}`} className="min-h-[90px] border-r border-b border-surface-100" />
          const dateStr = `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
          const dayEvents = eventsByDay.get(dateStr) ?? []
          const isToday = dateStr === today
          return (
            <button
              key={dateStr}
              onClick={() => onSelectDay(dateStr)}
              className={cn(
                'min-h-[90px] border-r border-b border-surface-200 p-1.5 text-left align-top transition-colors hover:bg-brand-50 dark:hover:bg-surface-100',
                isToday && 'bg-brand-50 ring-2 ring-inset ring-brand-500 dark:bg-brand-500/10 dark:ring-brand-500/40',
              )}
            >
              <span
                className={cn(
                  'flex h-6 w-6 items-center justify-center rounded-full text-xs font-medium',
                  isToday ? 'bg-brand-600 text-white' : 'text-surface-600',
                )}
              >
                {day}
              </span>
              <div className="mt-1 space-y-1">
                {dayEvents.slice(0, 4).map((ev) => (
                  <div
                    key={`${ev.kind}-${ev.id}-${ev.date}`}
                    className={cn(
                      'flex items-center gap-1 truncate rounded border px-1 py-0.5 text-[10px] font-medium',
                      KIND_COLORS[ev.kind] ?? KIND_COLORS.EVENT,
                    )}
                  >
                    <span className="truncate">{ev.title}</span>
                  </div>
                ))}
                {dayEvents.length > 4 && <p className="text-[10px] text-surface-400">+{dayEvents.length - 4} more</p>}
              </div>
            </button>
          )
        })}
      </div>
    </div>
  )
}