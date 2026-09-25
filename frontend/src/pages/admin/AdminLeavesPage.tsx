import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, X } from 'lucide-react'
import toast from 'react-hot-toast'
import type { Leave } from '@/types'
import { adminApi } from '@/api'
import { rejectSchema, type RejectForm } from '@/validations/schemas'
import { extractMessage } from '@/api/client'
import { formatDate, formatDateTime } from '@/utils'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Modal } from '@/components/ui/Modal'
import { Textarea } from '@/components/ui/Textarea'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { Pagination } from '@/components/ui/Pagination'
import { EmptyState } from '@/components/ui/EmptyState'
import { Spinner } from '@/components/ui/Spinner'

export function AdminLeavesPage() {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState('PENDING')
  const [leaveType, setLeaveType] = useState('')
  const [rejecting, setRejecting] = useState<Leave | null>(null)

  const { data, isLoading } = useQuery({
    queryKey: ['admin', 'leaves', { page, status, leaveType }],
    queryFn: () => adminApi.leaves({ page, size: 10, status: status || undefined, leaveType: leaveType || undefined }),
  })

  const approveMutation = useMutation({
    mutationFn: adminApi.approveLeave,
    onSuccess: () => {
      toast.success('Leave approved')
      queryClient.invalidateQueries({ queryKey: ['admin', 'leaves'] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  const rejectMutation = useMutation({
    mutationFn: (args: { id: number; reason: string }) => adminApi.rejectLeave(args.id, args.reason),
    onSuccess: () => {
      toast.success('Leave rejected')
      setRejecting(null)
      queryClient.invalidateQueries({ queryKey: ['admin', 'leaves'] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  const { register, handleSubmit, reset } = useForm<RejectForm>({ resolver: zodResolver(rejectSchema) })

  return (
    <div>
      <PageHeader title="Leave approvals" subtitle="Review and decide on employee leave requests" />

      <div className="mb-4 flex gap-3">
        <select className="select w-44" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
          <option value="PENDING">Pending</option>
          <option value="">All statuses</option>
          <option value="APPROVED">Approved</option>
          <option value="REJECTED">Rejected</option>
        </select>
        <select className="select w-44" value={leaveType} onChange={(e) => { setLeaveType(e.target.value); setPage(0); }}>
          <option value="">All types</option>
          <option value="PRIVILEGE_LEAVE">PL</option>
          <option value="SICK_LEAVE">SL</option>
          <option value="COMP_OFF">CO</option>
        </select>
      </div>

      <div className="card overflow-hidden">
        {isLoading ? (
          <div className="grid place-items-center py-20"><Spinner /></div>
        ) : !data?.content.length ? (
          <EmptyState title="No leave requests" description="No requests match the selected filters." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[860px]">
              <thead className="border-b border-surface-200 bg-surface-50">
                <tr>
                  <th className="th">Employee</th>
                  <th className="th">Type</th>
                  <th className="th">Dates</th>
                  <th className="th">Days</th>
                  <th className="th">Reason</th>
                  <th className="th">Applied</th>
                  <th className="th">Status</th>
                  <th className="th">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-surface-200">
                {data.content.map((l) => (
                  <tr key={l.id} className="transition-colors duration-150 hover:bg-rowhover">
                    <td className="td">
                      <p className="font-medium text-surface-800">{l.employeeName}</p>
                      <p className="text-xs text-surface-400">{l.employeeCode}</p>
                    </td>
                    <td className="td">
                      <span className="text-xs font-semibold text-surface-700">{l.leaveTypeCode}</span>
                    </td>
                    <td className="td text-xs">
                      {formatDate(l.startDate)} → {formatDate(l.endDate)}
                    </td>
                    <td className="td">{l.days}</td>
                    <td className="td max-w-[200px] truncate" title={l.reason ?? ''}>{l.reason}</td>
                    <td className="td text-xs text-surface-500">{formatDateTime(l.appliedOn)}</td>
                    <td className="td"><StatusBadge status={l.status} /></td>
                    <td className="td">
                      {l.status === 'PENDING' ? (
                        <div className="flex gap-1">
                          <button title="Approve" className="icon-btn text-emerald-600" onClick={() => approveMutation.mutate(l.id)} disabled={approveMutation.isPending}>
                            <Check className="h-4 w-4" />
                          </button>
                          <button title="Reject" className="icon-btn text-red-600" onClick={() => { setRejecting(l); reset({ rejectionReason: '' }); }}>
                            <X className="h-4 w-4" />
                          </button>
                        </div>
                      ) : l.status === 'REJECTED' && l.rejectionReason ? (
                        <span className="text-xs text-red-600" title={l.rejectionReason}>{l.rejectionReason}</span>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {(data?.totalPages ?? 0) > 1 && (
          <Pagination page={data!.page} size={data!.size} totalPages={data!.totalPages} totalElements={data!.totalElements} onPageChange={(p) => setPage(p)} />
        )}
      </div>

      <Modal open={Boolean(rejecting)} onClose={() => setRejecting(null)} title={`Reject leave · ${rejecting?.employeeName}`}>
        <form onSubmit={handleSubmit((v) => rejectMutation.mutate({ id: rejecting!.id, reason: v.rejectionReason }))} className="space-y-4">
          <Textarea label="Rejection reason" rows={3} placeholder="Explain why this leave is being rejected…" {...register('rejectionReason')} />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={() => setRejecting(null)}>Cancel</Button>
            <Button type="submit" variant="danger" loading={rejectMutation.isPending}>Reject leave</Button>
          </div>
        </form>
      </Modal>
    </div>
  )
}