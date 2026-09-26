import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { ArrowRight, CalendarDays, CalendarPlus, FileText, RefreshCcw, User } from 'lucide-react'
import { dashboardApi, holidayApi, leaveApi } from '@/api'
import { useAuth } from '@/hooks/useAuth'
import { useTeam } from '@/hooks/useTeam'
import { PageHeader } from '@/components/ui/PageHeader'
import { StatCard } from '@/components/ui/StatCard'
import { LoadingState } from '@/components/ui/LoadingState'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { resolveHolidayDisplayType } from '@/constants/holidayStatus'
import { ATTENDANCE_TYPE_LABELS, cn, formatDayShort } from '@/utils'
import type { AttendanceType } from '@/types'

function BalancesCard() {
  const { data: balances } = useQuery({ queryKey: ['leaves', 'balances'], queryFn: leaveApi.balances })
  if (!balances?.length) return null
  return (
    <div className="card p-4">
      <h3 className="mb-3 text-sm font-semibold text-surface-700">Leave Balances</h3>
      <div className="space-y-3">
        {balances.map((b) => (
          <div key={b.leaveType}>
            <div className="flex items-center justify-between text-xs">
              <span className="font-medium text-surface-600">{b.leaveTypeLabel}</span>
              <span className="text-surface-400">
                {b.used} used / {b.allocated} allocated
              </span>
            </div>
            <div className="mt-1 h-2 overflow-hidden rounded-full bg-surface-100">
              <div
                className={cn('h-full rounded-full', b.available > 0 ? 'bg-brand-500' : 'bg-red-400')}
                style={{
                  width: `${b.allocated > 0 ? Math.min(100, (b.used / b.allocated) * 100) : 0}%`,
                }}
              />
            </div>
            <p className="mt-1 text-right text-xs font-semibold text-surface-700">{b.available} available</p>
          </div>
        ))}
      </div>
    </div>
  )
}

function UpcomingList() {
  const { effectiveTeamId } = useTeam()
  const { data: holidays } = useQuery({
    queryKey: ['holidays', 'upcoming', effectiveTeamId ?? 'default'],
    queryFn: () => holidayApi.upcoming(3, effectiveTeamId),
  })
  const { data: me } = useQuery({ queryKey: ['dashboard', 'me'], queryFn: dashboardApi.me })
  if (!holidays?.length && !me?.upcomingBirthdays.length) return null
  return (
    <div className="card p-4">
      <h3 className="mb-3 text-sm font-semibold text-surface-700">Upcoming</h3>
      <div className="space-y-2 text-sm">
        {holidays?.map((h) => (
          <div key={h.id} className="flex items-center gap-3">
            <span className="w-24 shrink-0 text-xs text-surface-400">{formatDayShort(h.date)}</span>
            <span className="min-w-0 flex-1 truncate text-surface-700">{h.name}</span>
            <StatusBadge
              status={resolveHolidayDisplayType(h)}
              className="shrink-0 px-2 py-0.5 text-[10px] font-semibold"
            />
          </div>
        ))}
        {me?.upcomingBirthdays.map((b) => (
          <div key={b.name + b.date} className="flex items-center gap-3">
            <span className="w-24 shrink-0 text-xs text-surface-400">{formatDayShort(b.date)}</span>
            <span className="min-w-0 flex-1 truncate text-surface-700">Birthday: {b.name}</span>
            <span
              className="shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold"
              style={{ backgroundColor: 'var(--attendance-wx-bg)', color: 'var(--attendance-wx-text)' }}
            >
              Birthday
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

export function DashboardPage() {
  const { user } = useAuth()
  const { effectiveTeamId, teamLabel } = useTeam()
  const { data, isLoading } = useQuery({ queryKey: ['dashboard', 'me'], queryFn: dashboardApi.me })
  const { data: holidays } = useQuery({
    queryKey: ['holidays', 'upcoming', effectiveTeamId ?? 'default'],
    queryFn: () => holidayApi.upcoming(3, effectiveTeamId),
  })
  const { data: team } = useQuery({
    queryKey: ['dashboard', 'team', effectiveTeamId ?? 'default'],
    queryFn: () => dashboardApi.team(effectiveTeamId),
  })

  if (isLoading) return <LoadingState label="Loading dashboard…" />

  const todayType = data?.todayType as AttendanceType | undefined

  return (
    <div>
      <PageHeader
        title={`Welcome back, ${data?.fullName ?? user?.fullName ?? '👋'}`}
        subtitle={`${data?.designation ?? ''}${data?.department ? ` · ${data.department}` : ''}`}
      />

      <div className="mb-6 rounded-lg border border-surface-200 bg-surface-0 p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <p className="text-xs font-medium text-brand-700">Today's status</p>
            <p className="mt-1 text-lg font-semibold text-surface-900">
              {todayType ? ATTENDANCE_TYPE_LABELS[todayType] ?? todayType : '—'}
            </p>
            <p className="text-xs text-surface-500">{formatDayShort(data?.today ?? new Date().toISOString().slice(0, 10))}</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" size="sm"><Link to="/attendance" className="flex items-center gap-1"><CalendarDays className="h-4 w-4" /> My attendance</Link></Button>
            <Button size="sm"><Link to="/leaves/new" className="flex items-center gap-1"><CalendarPlus className="h-4 w-4" /> Apply leave</Link></Button>
          </div>
        </div>
      </div>

      {team && (
        <div className="mb-6">
          <h3 className="mb-3 text-sm font-semibold text-surface-700">
            {team.teamName ?? teamLabel ?? 'All Teams'} · today
          </h3>
          <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
            <StatCard accent="emerald" label="Working" value={team.workingToday} />
            <StatCard accent="violet" label="Working from home" value={team.wfhToday} />
            <StatCard accent="amber" label="On leave / off" value={team.onLeaveToday} />
            <StatCard accent="brand" label="Week off" value={team.weekOffToday} />
          </div>
        </div>
      )}

      <div className="mb-6 grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard accent="emerald" label="PL taken" value={data?.leaveBalances.find((b) => b.leaveType === 'PRIVILEGE_LEAVE')?.used ?? 0} />
        <StatCard accent="amber" label="SL taken" value={data?.leaveBalances.find((b) => b.leaveType === 'SICK_LEAVE')?.used ?? 0} />
        <StatCard accent="violet" label="CO taken" value={data?.leaveBalances.find((b) => b.leaveType === 'COMP_OFF')?.used ?? 0} />
        <StatCard accent="brand" label="WFH days" value={data?.wfhDays ?? 0} />
        <StatCard accent="brand" label="WFO days" value={data?.wfoDays ?? 0} />
        <StatCard accent="red" label="Pending leaves" value={data?.pendingLeaves ?? 0} />
        <StatCard accent="brand" label="Upcoming approved leaves" value={data?.approvedUpcomingLeaves ?? 0} />
        <StatCard accent="emerald" label="Upcoming holidays" value={holidays?.length ?? 0} />
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <div className="space-y-4 lg:col-span-2">
          <div className="card divide-y divide-surface-200">
            <div className="flex items-center justify-between px-4 py-3">
              <h3 className="text-sm font-semibold text-surface-700">Quick Actions</h3>
            </div>
            <ul className="grid grid-cols-2 gap-px">
              {[
                { label: 'Apply leave', to: '/leaves/new', icon: <CalendarPlus className="h-5 w-5" /> },
                { label: 'Apply swap off', to: '/swap-off/new', icon: <RefreshCcw className="h-5 w-5" /> },
                { label: 'View calendar', to: '/calendar', icon: <CalendarDays className="h-5 w-5" /> },
                { label: 'View profile', to: '/profile', icon: <User className="h-5 w-5" /> },
                { label: "Today's status", to: '/today', icon: <FileText className="h-5 w-5" /> },
                { label: 'My leaves', to: '/leaves', icon: <FileText className="h-5 w-5" /> },
              ].map((a) => (
                <li key={a.to}>
                  <Link to={a.to} className="flex items-center gap-3 px-4 py-3 text-sm font-medium text-surface-700 transition-colors hover:bg-brand-50 hover:text-brand-700 dark:hover:bg-surface-100 dark:hover:text-brand-300">
                    {a.icon} {a.label}
                    <ArrowRight className="ml-auto h-4 w-4 text-surface-300" />
                  </Link>
                </li>
              ))}
            </ul>
          </div>
          <UpcomingList />
        </div>
        <div className="space-y-4">
          <BalancesCard />
        </div>
      </div>
    </div>
  )
}