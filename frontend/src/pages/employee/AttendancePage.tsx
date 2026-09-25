import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { attendanceApi } from '@/api'
import { attendanceShort, formatMonthYear, toISODate } from '@/utils'
import type { Attendance, AttendanceType } from '@/types'
import { PageHeader } from '@/components/ui/PageHeader'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'
import { StatCard } from '@/components/ui/StatCard'

const TYPE_STYLES: Record<string, string> = {
  WORK_FROM_OFFICE: 'bg-brand-50 text-brand-700 dark:bg-surface-100 dark:text-brand-400',
  WORK_FROM_HOME: 'bg-violet-50 text-violet-700 dark:bg-surface-100 dark:text-violet-400',
  LEAVE: 'bg-amber-50 text-amber-700 dark:bg-surface-100 dark:text-amber-400',
  PRIVILEGE_LEAVE: 'bg-amber-50 text-amber-700 dark:bg-surface-100 dark:text-amber-400',
  SICK_LEAVE: 'bg-orange-50 text-orange-700 dark:bg-surface-100 dark:text-orange-400',
  COMP_OFF: 'bg-emerald-50 text-emerald-700 dark:bg-surface-100 dark:text-emerald-400',
  FURLOUGH: 'bg-rose-50 text-rose-700 dark:bg-surface-100 dark:text-rose-400',
  HOLIDAY: 'bg-pink-50 text-pink-700 dark:bg-surface-100 dark:text-pink-500',
  WEEK_OFF: 'bg-surface-100 text-surface-500',
  ATTRITION: 'bg-red-50 text-red-700 dark:bg-surface-100 dark:text-red-400',
}

function monthMatrix(year: number, month: number): (string | null)[] {
  const first = new Date(year, month, 1).getDay()
  const daysInMonth = new Date(year, month + 1, 0).getDate()
  const cells: (string | null)[] = Array(first).fill(null)
  for (let d = 1; d <= daysInMonth; d++) cells.push(toISODate(new Date(year, month, d)))
  return cells
}

export function AttendancePage() {
  const now = new Date()
  const [cursor, setCursor] = useState({ year: now.getFullYear(), month: now.getMonth() })

  const { data: records, isLoading } = useQuery({
    queryKey: ['attendance', 'me', cursor.year, cursor.month],
    queryFn: () => attendanceApi.me(cursor.year, cursor.month),
  })

  const byDate = useMemo(() => new Map((records ?? []).map((r) => [r.date, r])), [records])

  const counts = useMemo(() => {
    const c: Record<string, number> = {}
    for (const r of records ?? []) c[r.attendanceType] = (c[r.attendanceType] ?? 0) + 1
    return c
  }, [records])

  const monthLabel = formatMonthYear(toISODate(new Date(cursor.year, cursor.month, 1)))

  const canPrev = cursor.year > now.getFullYear() - 3
  const canNext = cursor.year < now.getFullYear() || (cursor.year === now.getFullYear() && cursor.month < now.getMonth())

  return (
    <div>
      <PageHeader
        title="My attendance"
        subtitle={monthLabel}
        actions={
          <div className="flex items-center gap-2">
            <button disabled={!canPrev} onClick={() => setCursor((c) => (c.month === 0 ? { year: c.year - 1, month: 11 } : { ...c, month: c.month - 1 }))} className="btn-secondary" aria-label="Previous month">
              ‹
            </button>
            <span className="w-9 text-center text-sm font-medium text-surface-600">{(cursor.month + 1).toString().padStart(2, '0')}/{cursor.year}</span>
            <button disabled={!canNext} onClick={() => setCursor((c) => (c.month === 11 ? { year: c.year + 1, month: 0 } : { ...c, month: c.month + 1 }))} className="btn-secondary" aria-label="Next month">
              ›
            </button>
          </div>
        }
      />

      <div className="mb-6 grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-6">
        {Object.entries(counts).map(([type, count]) => (
          <StatCard key={type} label={attendanceShort(type)} value={count} />
        ))}
        {Object.keys(counts).length === 0 && (
          <EmptyState title="No records for this month" description="No attendance entries found." />
        )}
      </div>

      {isLoading ? (
        <LoadingState label="Loading attendance…" />
      ) : (
        <div className="card p-4">
          <div className="mb-3 grid grid-cols-7 gap-1 text-center text-[11px] font-semibold text-surface-400">
            {['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].map((d) => <div key={d}>{d}</div>)}
          </div>
          <div className="grid grid-cols-7 gap-1">
            {monthMatrix(cursor.year, cursor.month).map((iso, i) => {
              if (!iso) return <div key={`empty-${i}`} />
              const rec: Attendance | undefined = byDate.get(iso)
              const day = new Date(iso + 'T00:00:00').getDay()
              const weekend = day === 0 || day === 6
              return (
                <div
                  key={iso}
                  title={rec ? `${attendanceShort(rec.attendanceType)} · ${rec.date}` : weekend ? 'Week Off' : 'No record'}
                  className={`grid aspect-square place-items-center rounded-lg border text-xs font-medium ${
                    rec
                      ? `border-transparent ${TYPE_STYLES[rec.attendanceType] ?? 'bg-surface-100 text-surface-500'}`
                      : weekend
                        ? 'border-surface-100 bg-surface-50 text-surface-300'
                        : 'border-surface-100 bg-surface-0 text-surface-300'
                  }`}
                >
                  {rec ? attendanceShort(rec.attendanceType) : new Date(iso + 'T00:00:00').getDate()}
                </div>
              )
            })}
          </div>
          <div className="mt-4 flex flex-wrap gap-3 text-[11px] text-surface-500">
            {(Object.keys(TYPE_STYLES) as AttendanceType[]).map((t) => (
              <span key={t} className="flex items-center gap-1">
                <i className={`h-2 w-2 rounded-full ${TYPE_STYLES[t].split(' ')[0]}`} /> {attendanceShort(t)}
              </span>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}