import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import { leaveApi } from '@/api'
import { extractMessage } from '@/api/client'
import { formatDate, formatDateTime } from '@/utils'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'
import { Plus } from 'lucide-react'

interface MyLeavesTableProps {
  statusFilter?: 'all' | 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
  onNewLeaveClick?: () => void
}

export function MyLeavesTable({ statusFilter = 'all', onNewLeaveClick }: MyLeavesTableProps) {
  const queryClient = useQueryClient()
  const { data: leaves, isLoading } = useQuery({ queryKey: ['leaves', 'mine'], queryFn: leaveApi.my })

  const filteredLeaves = useMemo(() => {
    if (!leaves) return []
    if (statusFilter === 'all') return leaves
    return leaves.filter(l => l.status === statusFilter)
  }, [leaves, statusFilter])

  const cancelMutation = useMutation({
    mutationFn: leaveApi.cancel,
    onSuccess: () => {
      toast.success('Leave request cancelled')
      queryClient.invalidateQueries({ queryKey: ['leaves'] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  if (isLoading) return <LoadingState label="Loading leaves…" />

  if (!filteredLeaves?.length) {
    return (
      <div className="p-8 text-center">
        <div className="mx-auto mb-4 flex h-12 w-12 items-center justify-center rounded-full bg-surface-100 text-surface-400">
          <svg className="h-6 w-6" fill="none" stroke="currentColor" viewBox="0 0 24 24" aria-hidden="true">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
          </svg>
        </div>
        <h3 className="text-lg font-medium text-surface-900">No leave requests found</h3>
        <p className="mt-1 text-sm text-surface-500">
          {statusFilter === 'all' ? 'You have no leave requests yet.' : `No ${statusFilter.toLowerCase()} leave requests.`}
        </p>
        <div className="mt-4">
          <Button
            variant="ghost"
            size="sm"
            onClick={onNewLeaveClick}
            className="gap-2"
          >
            <Plus className="h-4 w-4" />
            Request your first leave
          </Button>
        </div>
      </div>
    )
  }

  return (
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
          {filteredLeaves.map((l) => (
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
  )
}

import { useMemo } from 'react'