import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import toast from 'react-hot-toast'
import type { Attendance, AttendanceType } from '@/types'
import { adminApi, departmentApi } from '@/api'
import { extractMessage } from '@/api/client'
import { attendanceShort, formatDate } from '@/utils'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Select } from '@/components/ui/Select'
import { Modal } from '@/components/ui/Modal'
import { Pagination } from '@/components/ui/Pagination'
import { EmptyState } from '@/components/ui/EmptyState'
import { Spinner } from '@/components/ui/Spinner'

const ATTENDANCE_OPTIONS = ['WORK_FROM_OFFICE', 'WORK_FROM_HOME', 'COMP_OFF']

export function AdminAttendancePage() {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [type, setType] = useState('')
  const [departmentId, setDepartmentId] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const [form, setForm] = useState({ employeeCode: '', date: '', attendanceType: 'WORK_FROM_OFFICE' as AttendanceType, remarks: '' })

  const { data: departments } = useQuery({ queryKey: ['departments'], queryFn: departmentApi.list })
  const { data, isLoading } = useQuery({
    queryKey: ['admin', 'attendance', { page, from, to, type, departmentId }],
    queryFn: () =>
      adminApi.attendance({
        page,
        size: 15,
        from: from || undefined,
        to: to || undefined,
        type: type || undefined,
        departmentId: departmentId ? Number(departmentId) : undefined,
      }),
  })

  const upsert = useMutation({
    mutationFn: () => adminApi.upsertAttendance(form),
    onSuccess: () => {
      toast.success('Attendance saved')
      setModalOpen(false)
      setForm({ employeeCode: '', date: '', attendanceType: 'WORK_FROM_OFFICE', remarks: '' })
      queryClient.invalidateQueries({ queryKey: ['admin', 'attendance'] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  const remove = useMutation({
    mutationFn: adminApi.deleteAttendance,
    onSuccess: () => {
      toast.success('Attendance record deleted')
      queryClient.invalidateQueries({ queryKey: ['admin', 'attendance'] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  return (
    <div>
      <PageHeader
        title="Manage attendance"
        subtitle="View, add, or remove attendance records"
        actions={<Button onClick={() => setModalOpen(true)}><Plus className="h-4 w-4" /> Manual Entry</Button>}
      />

      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-5">
        <input type="date" value={from} onChange={(e) => { setFrom(e.target.value); setPage(0); }} className="select" aria-label="From" />
        <input type="date" value={to} onChange={(e) => { setTo(e.target.value); setPage(0); }} className="select" aria-label="To" />
        <select className="select" value={type} onChange={(e) => { setType(e.target.value); setPage(0); }}>
          <option value="">All types</option>
          {ATTENDANCE_OPTIONS.map((t) => <option key={t} value={t}>{attendanceShort(t)}</option>)}
        </select>
        <select className="select md:col-span-2" value={departmentId} onChange={(e) => { setDepartmentId(e.target.value); setPage(0); }}>
          <option value="">All teams</option>
          {(departments ?? []).map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
        </select>
      </div>

      <div className="card overflow-hidden">
        {isLoading ? (
          <div className="grid place-items-center py-20"><Spinner /></div>
        ) : !data?.content.length ? (
          <EmptyState title="No attendance records" description="Adjust your filters or add a manual entry." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[760px]">
              <thead className="border-b border-surface-200 bg-surface-50">
                <tr>
                  <th className="th">Employee</th>
                  <th className="th">Date</th>
                  <th className="th">Type</th>
                  <th className="th">Source</th>
                  <th className="th">Remarks</th>
                  <th className="th" />
                </tr>
              </thead>
              <tbody className="divide-y divide-surface-200">
                {data.content.map((a) => (
                  <tr key={a.id} className="transition-colors duration-150 hover:bg-rowhover">
                    <td className="td">
                      <p className="font-medium text-surface-800">{a.employeeName}</p>
                      <p className="text-xs text-surface-400">{a.employeeCode} · {a.department}</p>
                    </td>
                    <td className="td">{formatDate(a.date)}</td>
                    <td className="td"><span className="rounded-full bg-brand-50 px-2 py-0.5 text-xs font-medium text-brand-700 dark:bg-surface-100 dark:text-brand-400">{attendanceShort(a.attendanceType)}</span></td>
                    <td className="td text-xs text-surface-500 capitalize">{a.source.toLowerCase()}</td>
                    <td className="td max-w-[200px] truncate text-xs text-surface-500">{a.remarks}</td>
                    <td className="td text-right">
                      <button className="icon-btn" title="Delete" onClick={() => { if (confirm('Delete this attendance record?')) remove.mutate(a.id); }}><Trash2 className="h-4 w-4 text-red-600" /></button>
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

      <Modal open={modalOpen} onClose={() => setModalOpen(false)} title="Manual attendance entry">
        <div className="space-y-4">
          <Input label="Employee Code" placeholder="EMP-001" value={form.employeeCode} onChange={(e) => setForm((f) => ({ ...f, employeeCode: e.target.value }))} />
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input label="Date" type="date" value={form.date} onChange={(e) => setForm((f) => ({ ...f, date: e.target.value }))} />
            <Select
              label="Type"
              value={form.attendanceType}
              onChange={(e) => setForm((f) => ({ ...f, attendanceType: e.target.value as AttendanceType }))}
              options={ATTENDANCE_OPTIONS.map((t) => ({ value: t, label: attendanceShort(t) }))}
            />
          </div>
          <Input label="Remarks (optional)" value={form.remarks} onChange={(e) => setForm((f) => ({ ...f, remarks: e.target.value }))} />
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={() => setModalOpen(false)}>Cancel</Button>
            <Button onClick={() => upsert.mutate()} loading={upsert.isPending}>Save record</Button>
          </div>
        </div>
      </Modal>
    </div>
  )
}