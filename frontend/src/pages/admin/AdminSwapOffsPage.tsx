import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, X } from 'lucide-react'
import toast from 'react-hot-toast'
import type { SwapOff } from '@/types'
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

export function AdminSwapOffsPage() {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState('PENDING')
  const [rejecting, setRejecting] = useState<SwapOff | null>(null)

  const { data, isLoading } = useQuery({
    queryKey: ['admin', 'swap-offs', { page, status }],
    queryFn: () => adminApi.swapOffs({ page, size: 10, status: status || undefined }),
  })

  const approveMutation = useMutation({
    mutationFn: adminApi.approveSwapOff,
    onSuccess: () => {
      toast.success('Swap off approved & CO credited')
      queryClient.invalidateQueries({ queryKey: ['admin', 'swap-offs'] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  const rejectMutation = useMutation({
    mutationFn: (args: { id: number; reason: string }) => adminApi.rejectSwapOff(args.id, args.reason),
    onSuccess: () => {
      toast.success('Swap off rejected')
      setRejecting(null)
      queryClient.invalidateQueries({ queryKey: ['admin', 'swap-offs'] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  const { register, handleSubmit, reset } = useForm<RejectForm>({ resolver: zodResolver(rejectSchema) })

  return (
    <div>
      <PageHeader title="Swap off approvals" subtitle="Review and decide on compensatory off requests" />

      <div className="mb-4 flex gap-3">
        <select className="select w-44" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
          <option value="PENDING">Pending</option>
          <option value="">All statuses</option>
          <option value="APPROVED">Approved</option>
          <option value="REJECTED">Rejected</option>
        </select>
      </div>

      <div className="card overflow-hidden">
        {isLoading ? (
          <div className="grid place-items-center py-20"><Spinner /></div>
        ) : !data?.content.length ? (
          <EmptyState title="No swap off requests" description="No requests match the selected filters." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[860px]">
              <thead className="border-b border-surface-200 bg-surface-50">
                <tr>
                  <th className="th">Employee</th>
                  <th className="th">Worked Date</th>
                  <th className="th">Requested Off</th>
                  <th className="th">Reason</th>
                  <th className="th">Comp off</th>
                  <th className="th">Applied</th>
                  <th className="th">Status</th>
                  <th className="th">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-surface-200">
                {data.content.map((r) => (
                  <tr key={r.id} className="transition-colors duration-150 hover:bg-rowhover">
                    <td className="td">
                      <p className="font-medium text-surface-800">{r.employeeName}</p>
                      <p className="text-xs text-surface-400">{r.employeeCode} · {r.department}</p>
                    </td>
                    <td className="td text-xs">{formatDate(r.workedDate)}</td>
                    <td className="td text-xs">{formatDate(r.requestedOffDate)}</td>
                    <td className="td max-w-[200px] truncate" title={r.reason ?? ''}>{r.reason}</td>
                    <td className="td">{r.compOffCredited ? <span className="text-xs font-medium text-emerald-700">Credited</span> : '—'}</td>
                    <td className="td text-xs text-surface-500">{formatDateTime(r.appliedOn)}</td>
                    <td className="td"><StatusBadge status={r.status} /></td>
                    <td className="td">
                      {r.status === 'PENDING' ? (
                        <div className="flex gap-1">
                          <button title="Approve & credit CO" className="icon-btn text-emerald-600" onClick={() => approveMutation.mutate(r.id)} disabled={approveMutation.isPending}>
                            <Check className="h-4 w-4" />
                          </button>
                          <button title="Reject" className="icon-btn text-red-600" onClick={() => { setRejecting(r); reset({ rejectionReason: '' }); }}>
                            <X className="h-4 w-4" />
                          </button>
                        </div>
                      ) : r.status === 'REJECTED' && r.rejectionReason ? (
                        <span className="text-xs text-red-600" title={r.rejectionReason}>{r.rejectionReason}</span>
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

      <Modal open={Boolean(rejecting)} onClose={() => setRejecting(null)} title={`Reject swap off · ${rejecting?.employeeName}`}>
        <form onSubmit={handleSubmit((v) => rejectMutation.mutate({ id: rejecting!.id, reason: v.rejectionReason }))} className="space-y-4">
          <Textarea label="Rejection reason" rows={3} placeholder="Explain why this swap off is being rejected…" {...register('rejectionReason')} />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={() => setRejecting(null)}>Cancel</Button>
            <Button type="submit" variant="danger" loading={rejectMutation.isPending}>Reject request</Button>
          </div>
        </form>
      </Modal>
    </div>
  )
}