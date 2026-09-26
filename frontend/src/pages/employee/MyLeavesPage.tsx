import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import toast from 'react-hot-toast'
import { leaveApi } from '@/api'
import { extractMessage } from '@/api/client'
import { formatDate, formatDateTime } from '@/utils'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'

export function MyLeavesPage() {
  const queryClient = useQueryClient()
  const { data: leaves, isLoading } = useQuery({ queryKey: ['leaves', 'mine'], queryFn: leaveApi.my })

  const cancelMutation = useMutation({
    mutationFn: leaveApi.cancel,
    onSuccess: () => {
      toast.success('Leave request cancelled')
      queryClient.invalidateQueries({ queryKey: ['leaves'] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  if (isLoading) return <LoadingState label="Loading leaves…" />

  return (
    <div>
      <PageHeader
        title="My leaves"
        subtitle="Your leave history and request status"
        actions={<Button><Link to="/leaves/new">Apply Leave</Link></Button>}
      />

      <div className="card overflow-hidden">
        {!leaves?.length ? (
          <EmptyState title="No leave requests yet" description="Apply for leave to get started." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px]">
              <thead className="border-b border-surface-200 bg-surface-50">
                <tr>
                  <th className="th">Leave Type</th>
                  <th className="th">Start Date</th>
                  <th className="th">End Date</th>
                  <th className="th">Days</th>
                  <th className="th">Reason</th>
                  <th className="th">Status</th>
                  <th className="th">Applied On</th>
                  <th className="th" />
                </tr>
              </thead>
              <tbody className="divide-y divide-surface-200">
                {leaves.map((l) => (
                  <tr key={l.id} className="transition-colors duration-150 hover:bg-rowhover">
                    <td className="td">
                      <span className="font-medium text-surface-800">{l.leaveTypeCode}</span>
                      <span className="ml-1 text-xs text-surface-400">{l.leaveTypeLabel}</span>
                      {l.hpeEntitlementId && l.hpeHolidayName && (
                        <p className="text-xs text-surface-400">
                          {l.hpeHolidayName} · {formatDate(l.hpeHolidayDate)}
                        </p>
                      )}
                    </td>
                    <td className="td">{formatDate(l.startDate)}</td>
                    <td className="td">{formatDate(l.endDate)}</td>
                    <td className="td">{l.days}</td>
                    <td className="td max-w-[220px] truncate" title={l.reason ?? ''}>{l.reason}</td>
                    <td className="td"><StatusBadge status={l.status} /></td>
                    <td className="td text-xs text-surface-500">{formatDateTime(l.appliedOn)}</td>
                    <td className="td text-right">
                      {l.status === 'PENDING' && (
                        <Button variant="ghost" size="sm" onClick={() => cancelMutation.mutate(l.id)}>
                          Cancel
                        </Button>
                      )}
                      {l.status === 'REJECTED' && l.rejectionReason && (
                        <span className="text-xs text-red-600" title={l.rejectionReason}>Rejected: {l.rejectionReason}</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}