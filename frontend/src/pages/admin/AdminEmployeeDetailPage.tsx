import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, Navigate, useParams } from 'react-router-dom'
import { CalendarDays, ChevronLeft, ChevronRight, Power } from 'lucide-react'
import toast from 'react-hot-toast'
import type { EmployeeHistoricalAttendance } from '@/types'
import { adminApi } from '@/api'
import { extractMessage } from '@/api/client'
import { cn, formatDate } from '@/utils'
import { formatShiftDisplay } from '@/utils/shift'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { StatCard } from '@/components/ui/StatCard'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { Avatar } from '@/components/ui/Avatar'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'
import { Select } from '@/components/ui/Select'
import { monthLabel } from '@/components/roster/RosterLegend'
import { rosterStatusClass, statusLabel } from '@/constants/rosterStatus'

const OVERVIEW_CODES = ['WFO', 'WFH', 'WO', 'PL', 'SL', 'CO', 'HPEH', 'FL', 'HD'] as const
const MONTH_CODES = ['WFO', 'WFH', 'WO', 'PL', 'SL', 'CO'] as const
const WEEKDAY_LABELS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

interface Cell {
  date: string | null
  day: number
}

function buildMonthCells(month: string): Cell[] {
  const [y, m] = month.split('-').map(Number)
  const firstDow = new Date(y, m - 1, 1).getDay()
  const length = new Date(y, m, 0).getDate()
  const cells: Cell[] = []
  for (let i = 0; i < firstDow; i++) cells.push({ date: null, day: 0 })
  for (let d = 1; d <= length; d++) {
    cells.push({ date: `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`, day: d })
  }
  while (cells.length % 7 !== 0) cells.push({ date: null, day: 0 })
  return cells
}

/** Small stat card with a roster-colored status badge. */
function OverviewStat({ label, value, code }: { label: string; value: string | number; code?: string }) {
  return (
    <div className="flex items-center justify-between gap-2 rounded-lg border border-surface-200 p-3">
      <div className="min-w-0">
        <p className="truncate text-[11px] font-medium text-surface-400">{label}</p>
        <p className="mt-0.5 text-base font-semibold tabular-nums text-surface-800">{value}</p>
      </div>
      {code && (
        <span className={cn('roster-badge shrink-0 px-1.5 py-0.5 text-[9px]', rosterStatusClass(code))}>{code}</span>
      )}
    </div>
  )
}

export function AdminEmployeeDetailPage() {
  const { id } = useParams()
  const queryClient = useQueryClient()
  const [histMonth, setHistMonth] = useState<string>() // undefined -> server picks latest month

  const { data, isLoading } = useQuery({
    queryKey: ['admin', 'employee', id],
    queryFn: () => adminApi.employee(Number(id)),
    enabled: Boolean(id),
  })

  const { data: historical, isLoading: historicalLoading } = useQuery<EmployeeHistoricalAttendance>({
    queryKey: ['admin', 'employee', id, 'historical-attendance', histMonth],
    queryFn: () => adminApi.employeeHistoricalAttendance(Number(id), histMonth),
    enabled: Boolean(id),
  })

  useEffect(() => {
    if (historical?.calendar?.month && histMonth === undefined) {
      setHistMonth(historical.calendar.month)
    }
  }, [historical, histMonth])

  const toggleStatus = useMutation({
    mutationFn: () => adminApi.setEmployeeStatus(data!.id, data!.employmentStatus === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'),
    onSuccess: () => {
      toast.success('Status updated')
      queryClient.invalidateQueries({ queryKey: ['admin', 'employee', id] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  if (!id) return <Navigate to="/admin/employees" replace />
  if (isLoading) return <LoadingState label="Loading employee…" />
  if (!data) return <Navigate to="/admin/employees" replace />

  const s = data.stats
  const overview = historical?.overview
  const months = historical?.months ?? []
  const calMonth = historical?.calendar?.month ?? histMonth
  const monthIndex = months.indexOf(calMonth ?? '')
  const calByDate = new Map((historical?.calendar?.days ?? []).map((d) => [d.date, d]))

  return (
    <div>
      <PageHeader
        title={data.fullName}
        subtitle={`${data.employeeCode} · ${data.email}`}
        actions={<div className="flex gap-2"><Button variant="secondary" size="sm" onClick={() => toggleStatus.mutate()} loading={toggleStatus.isPending}><Power className="h-4 w-4" /> {data.employmentStatus === 'ACTIVE' ? 'Deactivate' : 'Activate'}</Button><Button variant="secondary" size="sm"><Link to="/admin/employees">Back to list</Link></Button></div>}
      />

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="card p-6 h-fit">
          <div className="flex items-center gap-4">
            <Avatar name={data.fullName} src={data.avatar} size="lg" />
            <div>
              <div className="flex items-center gap-2">
                <h2 className="text-lg font-bold text-surface-900">{data.fullName}</h2>
                <StatusBadge status={data.employmentStatus} />
              </div>
              <p className="text-sm text-surface-500">{data.designation ?? '—'} {data.department ? `· ${data.department}` : ''}</p>
            </div>
          </div>
          <dl className="mt-6 space-y-3 text-sm">
            {[['Phone', data.phone], ['Manager', data.manager], ['Location', data.location], ['Shift', formatShiftDisplay(data.shift)], ['Week off', data.weekOff], ['Date of joining', formatDate(data.dateOfJoining)], ['Date of birth', formatDate(data.dateOfBirth)]].map(([k, v]) => (
              <div key={k} className="flex justify-between gap-4">
                <dt className="text-surface-400">{k}</dt>
                <dd className="text-right font-medium text-surface-800">{v || '—'}</dd>
              </div>
            ))}
          </dl>
        </div>

        <div className="space-y-4 lg:col-span-2">
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <StatCard label="Working days" value={s.totalWorkingDays} />
            <StatCard label="WFO" value={s.workFromOfficeDays} accent="brand" />
            <StatCard label="WFH" value={s.workFromHomeDays} accent="violet" />
            <StatCard label="Leave days" value={s.totalLeaveDays} accent="amber" />
            <StatCard label="PL" value={s.plDays} accent="emerald" />
            <StatCard label="SL" value={s.slDays} accent="amber" />
            <StatCard label="CO" value={s.coDays} accent="violet" />
            <StatCard label="Pending" value={s.pendingLeaves} accent="red" />
          </div>

          <div className="card p-4">
            <h3 className="mb-3 text-sm font-semibold text-surface-700">Leave Balances</h3>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
              {data.leaveBalances.map((b) => (
                <div key={b.leaveType} className="rounded-lg border border-surface-200 p-4 text-center">
                  <p className="text-xs font-medium uppercase tracking-wide text-surface-400">{b.leaveTypeCode}</p>
                  <p className="mt-1 text-2xl font-bold text-surface-900">{b.available}<span className="text-sm font-normal text-surface-400"> / {b.allocated}</span></p>
                  <p className="text-xs text-surface-400">{b.used} used</p>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* ------------------------------------------------ historical attendance */}
      <div className="mt-8">
        <h2 className="mb-4 flex items-center gap-2 text-base font-semibold text-surface-800">
          <CalendarDays className="h-5 w-5 text-brand-500" />
          Historical attendance
          {historical?.found && <span className="text-xs font-normal text-surface-400">· from normalized attendance records</span>}
        </h2>

        {historicalLoading ? (
          <div className="card p-12"><LoadingState label="Loading attendance…" /></div>
        ) : !historical || !historical.found ? (
          <div className="card p-12">
            <EmptyState
              title="No historical attendance"
              description="This employee has no records in the normalized attendance data yet."
            />
          </div>
        ) : (
          <div className="space-y-6">
            {/* ------------------------------------------------ overview */}
            <div>
              <h3 className="mb-2 text-sm font-semibold text-surface-700">Attendance Overview</h3>
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
                <OverviewStat label="Date Joined" value={formatDate(overview?.dateJoined)} />
                <OverviewStat label="Total Days Since Joining" value={overview?.totalDays ?? 0} />
                {OVERVIEW_CODES.map((c) => (
                  <OverviewStat key={c} label={statusLabel(c)} code={c} value={overview?.byStatus[c] ?? 0} />
                ))}
                <OverviewStat label="Other Statuses" code="OTHER" value={overview?.byStatus.OTHER ?? 0} />
              </div>
            </div>

            {/* ------------------------------------------------ monthly */}
            <div>
              <h3 className="mb-2 text-sm font-semibold text-surface-700">Monthly Attendance</h3>
              <div className="card overflow-hidden">
                <div className="max-h-[24rem] overflow-auto">
                  <table className="min-w-full border-collapse">
                    <thead>
                      <tr className="border-b border-surface-200 bg-surface-50">
                        <th className="th text-left">Month</th>
                        {MONTH_CODES.map((c) => (
                          <th key={c} className="th text-center">
                            <span className={cn('roster-badge min-w-[2rem] px-1 text-[9px]', rosterStatusClass(c))}>{c}</span>
                          </th>
                        ))}
                        <th className="th text-center">Other</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-surface-100">
                      {historical!.monthly.map((m) => (
                        <tr key={m.month} className="hover:bg-surface-50/60">
                          <td className="td whitespace-nowrap text-xs font-medium text-surface-700">{monthLabel(m.month)}</td>
                          {MONTH_CODES.map((c) => (
                            <td key={c} className="td text-center text-sm tabular-nums text-surface-700">{m.counts[c] ?? 0}</td>
                          ))}
                          <td className="td text-center text-sm tabular-nums text-surface-500">{m.counts.OTHER ?? 0}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>

            {/* ------------------------------------------------ calendar */}
            <div>
              <div className="mb-2 flex flex-wrap items-center justify-between gap-3">
                <h3 className="text-sm font-semibold text-surface-700">Attendance Calendar</h3>
                <div className="flex items-center gap-1.5">
                  <Button variant="secondary" size="sm" className="px-2" disabled={monthIndex <= 0}
                    onClick={() => setHistMonth(months[monthIndex - 1])} aria-label="Previous month">
                    <ChevronLeft className="h-4 w-4" />
                  </Button>
                  <Select
                    className="w-44"
                    value={calMonth ?? ''}
                    onChange={(e) => setHistMonth(e.target.value)}
                    options={months.map((m) => ({ value: m, label: monthLabel(m) }))}
                  />
                  <Button variant="secondary" size="sm" className="px-2" disabled={monthIndex < 0 || monthIndex >= months.length - 1}
                    onClick={() => setHistMonth(months[monthIndex + 1])} aria-label="Next month">
                    <ChevronRight className="h-4 w-4" />
                  </Button>
                </div>
              </div>
              <div className="card overflow-hidden">
                <div className="grid grid-cols-7 border-b border-surface-200 bg-surface-50">
                  {WEEKDAY_LABELS.map((w) => (
                    <div key={w} className="px-1 py-1.5 text-center text-[10px] font-semibold uppercase text-surface-400">{w}</div>
                  ))}
                </div>
                <div className="grid grid-cols-7">
                  {buildMonthCells(calMonth ?? '').map((cell, i) => {
                    const day = cell.date ? calByDate.get(cell.date) : undefined
                    const code = day?.statusCode ?? null
                    return (
                      <div
                        key={i}
                        title={day && day.statusName ? `${day.date} — ${statusLabel(day.statusCode ?? '')}${day.unknown ? ' (unknown)' : ''}` : cell.date ?? ''}
                        className={cn(
                          'min-h-[3.4rem] border-b border-r border-surface-100 p-1 last:border-r-0',
                          day?.weekend && 'bg-surface-50 dark:bg-white/[0.03]',
                        )}
                      >
                        <div className={cn('text-[10px] tabular-nums', day?.weekend ? 'text-surface-400' : 'text-surface-500')}>
                          {cell.day || ''}
                        </div>
                        {code ? (
                          <span className={cn('roster-badge mt-1 block w-fit min-w-[2.1rem] px-1 text-[9px]', rosterStatusClass(code))}>
                            {code}
                          </span>
                        ) : null}
                      </div>
                    )
                  })}
                </div>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}