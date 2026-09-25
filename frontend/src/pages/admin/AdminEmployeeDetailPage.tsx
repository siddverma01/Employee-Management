import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, Navigate, useParams } from 'react-router-dom'
import { Power } from 'lucide-react'
import toast from 'react-hot-toast'
import { adminApi } from '@/api'
import { extractMessage } from '@/api/client'
import { formatDate } from '@/utils'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { StatCard } from '@/components/ui/StatCard'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { Avatar } from '@/components/ui/Avatar'
import { LoadingState } from '@/components/ui/LoadingState'

export function AdminEmployeeDetailPage() {
  const { id } = useParams()
  const queryClient = useQueryClient()

  const { data, isLoading } = useQuery({
    queryKey: ['admin', 'employee', id],
    queryFn: () => adminApi.employee(Number(id)),
    enabled: Boolean(id),
  })

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
            {[['Phone', data.phone], ['Manager', data.manager], ['Location', data.location], ['Shift', data.shift], ['Week off', data.weekOff], ['Date of joining', formatDate(data.dateOfJoining)], ['Date of birth', formatDate(data.dateOfBirth)]].map(([k, v]) => (
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
    </div>
  )
}