import { useQuery } from '@tanstack/react-query'
import type { AvailabilityStatus, TeamMemberAvailability, TodayEntry } from '@/types'
import { todayApi } from '@/api'
import { useTeam } from '@/hooks/useTeam'
import { cn, formatDayShort } from '@/utils'
import { PageHeader } from '@/components/ui/PageHeader'
import { Avatar } from '@/components/ui/Avatar'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'

const STATUS_STYLES: Record<AvailabilityStatus, string> = {
  WORKING: 'bg-emerald-50 text-emerald-700',
  WFH: 'bg-violet-50 text-violet-700',
  OFF: 'bg-amber-50 text-amber-700',
  WEEK_OFF: 'bg-surface-100 text-surface-500',
}

function PersonCard({ entry, mode }: { entry: TodayEntry; mode: 'working' | 'leave' }) {
  const isHome = entry.attendanceType === 'WORK_FROM_HOME'
  return (
    <li className="flex items-center gap-3 rounded-lg border border-surface-200 bg-surface-0 px-3 py-2.5">
      <Avatar name={entry.fullName} src={entry.avatar} />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-surface-800">{entry.fullName}</p>
        <p className="truncate text-xs text-surface-400">
          {entry.designation ?? ''} {entry.department ? `· ${entry.department}` : ''}
        </p>
      </div>
      {mode === 'working' ? (
        <span
          className={cn(
            'rounded-full px-2 py-0.5 text-[11px] font-semibold',
            isHome ? 'bg-violet-50 text-violet-700 dark:bg-surface-100 dark:text-violet-400' : 'bg-brand-50 text-brand-700 dark:bg-surface-100 dark:text-brand-400',
          )}
        >
          {isHome ? 'WFH' : 'WFO'}
        </span>
      ) : (
        <span className="rounded-full bg-amber-50 px-2 py-0.5 text-[11px] font-semibold text-amber-700">
          {entry.leaveType ?? 'Leave'}
        </span>
      )}
    </li>
  )
}

function AvailabilityRow({ m }: { m: TeamMemberAvailability }) {
  return (
    <li className="flex items-center gap-3 rounded-lg border border-surface-200 bg-surface-0 px-3 py-2.5">
      <Avatar name={m.fullName} src={m.avatar ?? undefined} />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-surface-800">{m.fullName}</p>
        <p className="truncate text-xs text-surface-400">
          {[m.designation, m.shift, m.location, m.weekOff]
            .filter(Boolean)
            .map((s) => (s as string).replace(/_/g, ' '))
            .join(' · ') || m.employeeCode}
        </p>
      </div>
      <span className={cn('rounded-full px-2 py-0.5 text-[11px] font-semibold', STATUS_STYLES[m.status])}>
        {m.status.replace(/_/g, ' ')}
      </span>
    </li>
  )
}

export function TodayPage() {
  const { effectiveTeamId, teamLabel } = useTeam()
  const { data: status, isLoading } = useQuery({
    queryKey: ['today', 'status', effectiveTeamId ?? 'default'],
    queryFn: () => todayApi.status(effectiveTeamId),
  })

  const today = new Date().toISOString().slice(0, 10)

  const working = (status?.working ?? []).sort((a) => (a.attendanceType === 'WORK_FROM_HOME' ? 0 : 1))
  const onLeave = status?.onLeave ?? []
  const members = status?.members ?? []

  return (
    <div>
      <PageHeader
        title={`Today · ${formatDayShort(today)}`}
        subtitle={`${teamLabel}${status?.mode === 'BASIC' ? ' · basic availability view' : ''}`}
      />

      {isLoading ? (
        <LoadingState label="Loading today's status…" />
      ) : status?.mode === 'BASIC' ? (
        <div>
          <div className="mb-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-700">
            You have basic access to {status.teamName ? `the ${status.teamName} team` : 'this team'} — internal leave
            and attendance details are not shown.
          </div>
          <div>
            <div className="mb-3 flex items-center justify-between">
              <h2 className="text-sm font-semibold text-surface-700">
                Team availability <span className="text-surface-400">({members.length})</span>
              </h2>
              <div className="flex gap-3 text-[11px] text-surface-400">
                <span className="flex items-center gap-1"><i className="h-2 w-2 rounded-full bg-emerald-500" /> Working</span>
                <span className="flex items-center gap-1"><i className="h-2 w-2 rounded-full bg-violet-500" /> WFH</span>
                <span className="flex items-center gap-1"><i className="h-2 w-2 rounded-full bg-amber-500" /> Off</span>
                <span className="flex items-center gap-1"><i className="h-2 w-2 rounded-full bg-surface-400" /> Week off</span>
              </div>
            </div>
            {members.length === 0 ? (
              <EmptyState title="No members on this team" />
            ) : (
              <ul className="space-y-2">
                {members.map((m) => <AvailabilityRow key={m.employeeId} m={m} />)}
              </ul>
            )}
          </div>
        </div>
      ) : (
        <div className="grid gap-6 lg:grid-cols-2">
          <div>
            <div className="mb-3 flex items-center justify-between">
              <h2 className="text-sm font-semibold text-surface-700">
                Working <span className="text-surface-400">({working.length})</span>
              </h2>
              <div className="flex gap-3 text-[11px] text-surface-400">
                <span className="flex items-center gap-1"><i className="h-2 w-2 rounded-full bg-brand-500" /> WFO</span>
                <span className="flex items-center gap-1"><i className="h-2 w-2 rounded-full bg-violet-500" /> WFH</span>
              </div>
            </div>
            {working.length === 0 ? (
              <EmptyState title="No one working today" />
            ) : (
              <ul className="space-y-2">
                {working.map((w) => <PersonCard key={w.employeeId} entry={w} mode="working" />)}
              </ul>
            )}
          </div>

          <div>
            <h2 className="mb-3 text-sm font-semibold text-surface-700">
              On Leave <span className="text-surface-400">({onLeave.length})</span>
            </h2>
            {onLeave.length === 0 ? (
              <EmptyState title="No one on leave today" />
            ) : (
              <ul className="space-y-2">
                {onLeave.map((o) => <PersonCard key={o.employeeId} entry={o} mode="leave" />)}
              </ul>
            )}
          </div>
        </div>
      )}
    </div>
  )
}