import { useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'
import { leaveApi } from '@/api'
import { CalendarDays, Clock, RefreshCw } from 'lucide-react'
import { cn } from '@/utils'

interface LeaveBalanceCardProps {
  title: string
  value: string | number
  subtitle?: string
  icon?: React.ReactNode
  trend?: string
  trendPositive?: boolean
}

export function LeaveBalanceCard({ title, value, subtitle, icon, trend, trendPositive }: LeaveBalanceCardProps) {
  return (
    <div className={cn(
      'card p-5 transition-shadow duration-200 hover:shadow-hpe-md',
      'border-surface-200 bg-surface-0'
    )}>
      <div className="flex items-start justify-between">
        <div className="min-w-0">
          <p className="text-sm font-medium text-surface-500 truncate">{title}</p>
          <p className="mt-1 text-2xl font-semibold text-surface-900 tabular-nums">{value}</p>
          {subtitle && <p className="mt-1 text-xs text-surface-400">{subtitle}</p>}
          {trend && (
            <p className={cn('mt-1 text-xs font-medium', trendPositive ? 'text-emerald-600' : 'text-red-600')}>
              {trend}
            </p>
          )}
        </div>
        {icon && (
          <div className="flex-shrink-0 ml-4 p-2 rounded-lg bg-brand-50 text-brand-600 dark:bg-brand-500/10 dark:text-brand-400">
            {icon}
          </div>
        )}
      </div>
    </div>
  )
}

export function LeaveBalanceCards() {
  const { data: leaves } = useQuery({ queryKey: ['leaves', 'mine'], queryFn: leaveApi.my })

  const stats = useMemo(() => {
    if (!leaves) return { pl: 14, cl: 6, pending: 0, swapOffs: 0 }
    const plUsed = leaves.filter(l => l.leaveTypeCode === 'PL' && l.status === 'APPROVED').reduce((sum, l) => sum + l.days, 0)
    const clUsed = leaves.filter(l => l.leaveTypeCode === 'SL' && l.status === 'APPROVED').reduce((sum, l) => sum + l.days, 0)
    const pending = leaves.filter(l => l.status === 'PENDING').length
    const swapOffs = leaves.filter(l => l.leaveTypeCode === 'CO').length
    return { pl: 14 - plUsed, cl: 6 - clUsed, pending, swapOffs }
  }, [leaves])

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
      <LeaveBalanceCard
        title="Privilege Leave"
        value={stats.pl}
        subtitle="days remaining"
        icon={<CalendarDays className="h-5 w-5" />}
      />
      <LeaveBalanceCard
        title="Casual / Sick Leave"
        value={stats.cl}
        subtitle="days remaining"
        icon={<CalendarDays className="h-5 w-5" />}
      />
      <LeaveBalanceCard
        title="Pending Requests"
        value={stats.pending}
        subtitle="awaiting approval"
        icon={<Clock className="h-5 w-5" />}
      />
      <LeaveBalanceCard
        title="Swap Offs Used"
        value={stats.swapOffs}
        subtitle="this year"
        icon={<RefreshCw className="h-5 w-5" />}
      />
    </div>
  )
}