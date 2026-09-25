import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import toast from 'react-hot-toast'
import { swapOffApi } from '@/api'
import { extractMessage } from '@/api/client'
import { formatDate, formatDateTime } from '@/utils'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'

export function MySwapOffsPage() {
  const queryClient = useQueryClient()
  const { data: requests, isLoading } = useQuery({ queryKey: ['swap-offs', 'mine'], queryFn: swapOffApi.my })

  const cancelMutation = useMutation({
    mutationFn: swapOffApi.cancel,
    onSuccess: () => {
      toast.success('Request cancelled')
      queryClient.invalidateQueries({ queryKey: ['swap-offs'] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  if (isLoading) return <LoadingState label="Loading swap off requests…" />

  return (
    <div>
      <PageHeader
        title="My swap offs"
        subtitle="Swap off requests you have submitted"
        actions={<Button><Link to="/swap-off/new">Apply Swap Off</Link></Button>}
      />

      <div className="card overflow-hidden">
        {!requests?.length ? (
          <EmptyState title="No swap off requests yet" description="Worked on behalf of someone? Apply for a swap off." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[720px]">
              <thead className="border-b border-surface-200 bg-surface-50">
                <tr>
                  <th className="th">Worked Date</th>
                  <th className="th">Off Date</th>
                  <th className="th">Worked For</th>
                  <th className="th">Reason</th>
                  <th className="th">Status</th>
                  <th className="th">Applied On</th>
                  <th className="th" />
                </tr>
              </thead>
              <tbody className="divide-y divide-surface-200">
                {requests.map((r) => (
                  <tr key={r.id} className="transition-colors duration-150 hover:bg-rowhover">
                    <td className="td">{formatDate(r.workedDate)}</td>
                    <td className="td">{formatDate(r.requestedOffDate)}</td>
                    <td className="td">
                      <p className="font-medium text-surface-800">{r.workedForEmployeeName}</p>
                      <p className="text-xs text-surface-400">{r.workedForEmployeeCode}</p>
                    </td>
                    <td className="td max-w-[220px] truncate" title={r.reason ?? ''}>{r.reason}</td>
                    <td className="td"><StatusBadge status={r.status} /></td>
                    <td className="td text-xs text-surface-500">{formatDateTime(r.appliedOn)}</td>
                    <td className="td text-right">
                      {r.status === 'PENDING' && (
                        <Button variant="ghost" size="sm" onClick={() => cancelMutation.mutate(r.id)}>Cancel</Button>
                      )}
                      {r.status === 'REJECTED' && r.rejectionReason && (
                        <span className="text-xs text-red-600" title={r.rejectionReason}>Rejected: {r.rejectionReason}</span>
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