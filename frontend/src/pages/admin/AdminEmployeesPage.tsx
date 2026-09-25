import { useEffect, useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Pencil, Power, Plus, Search, UserPlus } from 'lucide-react'
import toast from 'react-hot-toast'
import type { Summary } from '@/types'
import { adminApi, departmentApi, todayApi } from '@/api'
import { employeeSchema, type EmployeeForm } from '@/validations/schemas'
import { extractMessage } from '@/api/client'
import { useDebouncedValue } from '@/hooks/useDebouncedValue'
import { formatDate } from '@/utils'
import { formatShiftDisplay, formatShiftTime, to24hShift } from '@/utils/shift'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Select } from '@/components/ui/Select'
import { Modal } from '@/components/ui/Modal'

type LiveStatus = 'PRESENT' | 'WFH' | 'ON_LEAVE' | 'OFF_SHIFT'

const LIVE_STYLES: Record<LiveStatus, string> = {
  PRESENT: 'bg-status-present-bg text-status-present-text ring-1 ring-inset ring-status-present-border',
  WFH: 'bg-status-wfh-bg text-status-wfh-text ring-1 ring-inset ring-status-wfh-border',
  ON_LEAVE: 'bg-status-leave-bg text-status-leave-text ring-1 ring-inset ring-status-leave-border',
  OFF_SHIFT: 'bg-status-offshift-bg text-status-offshift-text ring-1 ring-inset ring-status-offshift-border',
}

const LIVE_LABELS: Record<LiveStatus, string> = {
  PRESENT: 'Present',
  WFH: 'WFH',
  ON_LEAVE: 'On Leave',
  OFF_SHIFT: 'Off Shift',
}

function parseShiftWindow(shift: string): { start: number; end: number } | null {
  const m = /^(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})$/.exec(shift)
  if (!m) return null
  const start = Number(m[1]) * 60 + Number(m[2])
  let end = Number(m[3]) * 60 + Number(m[4])
  if (end <= start) end += 24 * 60
  return { start, end }
}

function isWithinShift(shift: string, nowMinutes: number): boolean {
  const w = parseShiftWindow(shift)
  if (!w) return false
  let t = nowMinutes
  if (t < w.start) t += 24 * 60
  return t >= w.start && t < w.end
}

function liveStatusOf(
  e: Summary,
  now: Date,
  map: { leaveIds: Set<number>; wfhIds: Set<number>; offTodayIds: Set<number> },
): LiveStatus {
  if (e.employmentStatus === 'INACTIVE') return 'OFF_SHIFT'
  if (map.leaveIds.has(e.id)) return 'ON_LEAVE'
  if (map.wfhIds.has(e.id)) return 'WFH'
  if (map.offTodayIds.has(e.id)) return 'OFF_SHIFT'
  if (e.shift && isWithinShift(e.shift, now.getHours() * 60 + now.getMinutes())) return 'PRESENT'
  return 'OFF_SHIFT'
}
import { Avatar } from '@/components/ui/Avatar'
import { EmptyState } from '@/components/ui/EmptyState'
import { Spinner } from '@/components/ui/Spinner'

const SHIFT_STYLES: [string, string][] = [
  ['05:30-14:30', 'bg-shift-morning-bg text-shift-morning-text ring-1 ring-inset ring-shift-morning-border'],
  ['13:30-22:30', 'bg-shift-afternoon-bg text-shift-afternoon-text ring-1 ring-inset ring-shift-afternoon-border'],
  ['15:00-00:00', 'bg-shift-evening-bg text-shift-evening-text ring-1 ring-inset ring-shift-evening-border'],
  ['17:00-02:00', 'bg-shift-dusk-bg text-shift-dusk-text ring-1 ring-inset ring-shift-dusk-border'],
  ['19:00-04:00', 'bg-shift-night-bg text-shift-night-text ring-1 ring-inset ring-shift-night-border'],
  ['21:00-06:00', 'bg-shift-latenight-bg text-shift-latenight-text ring-1 ring-inset ring-shift-latenight-border'],
]

const FALLBACK_SHIFT_STYLES = [
  'bg-shift-morning-bg text-shift-morning-text ring-1 ring-inset ring-shift-morning-border',
  'bg-shift-afternoon-bg text-shift-afternoon-text ring-1 ring-inset ring-shift-afternoon-border',
  'bg-shift-evening-bg text-shift-evening-text ring-1 ring-inset ring-shift-evening-border',
  'bg-shift-dusk-bg text-shift-dusk-text ring-1 ring-inset ring-shift-dusk-border',
  'bg-shift-night-bg text-shift-night-text ring-1 ring-inset ring-shift-night-border',
  'bg-shift-latenight-bg text-shift-latenight-text ring-1 ring-inset ring-shift-latenight-border',
  'bg-shift-morning-bg text-shift-morning-text ring-1 ring-inset ring-shift-morning-border',
  'bg-shift-afternoon-bg text-shift-afternoon-text ring-1 ring-inset ring-shift-afternoon-border',
]

function shiftStyle(shift?: string | null): string {
  if (!shift) return 'text-surface-400'
  const match = SHIFT_STYLES.find(([s]) => s === shift)
  if (match) return match[1]
  let hash = 0
  for (let i = 0; i < shift.length; i++) hash = (hash * 31 + shift.charCodeAt(i)) % FALLBACK_SHIFT_STYLES.length
  return FALLBACK_SHIFT_STYLES[hash]
}

function EmployeeFormModal({ open, onClose, employee, departments }: { open: boolean; onClose: () => void; employee: Summary | null; departments: { id: number; name: string }[] }) {
  const queryClient = useQueryClient()
  const isEdit = Boolean(employee)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<z.input<typeof employeeSchema>, unknown, z.output<typeof employeeSchema>>({ resolver: zodResolver(employeeSchema) })

  useEffect(() => {
    if (open) {
      reset({
        employeeCode: employee?.employeeCode ?? '',
        fullName: employee?.fullName ?? '',
        email: employee?.email ?? '',
        role: 'EMPLOYEE',
        departmentId: employee ? departments.find((d) => d.name === employee.department)?.id ?? undefined : undefined,
        phone: employee?.phone ?? '',
        designation: employee?.designation ?? '',
        location: employee?.location ?? '',
        shift: employee?.shift ? (formatShiftTime(employee.shift) ?? employee.shift ?? '') : '',
        weekOff: employee?.weekOff ?? '',
        dateOfJoining: employee?.dateOfJoining ? String(employee.dateOfJoining).slice(0, 10) : '',
      })
    }
  }, [open, employee, departments, reset])

  const mutation = useMutation({
    mutationFn: (payload: EmployeeForm) => {
      if (isEdit) {
        const { employeeCode: _ignored, role: _role, password: _pwd, ...rest } = payload
        return adminApi.updateEmployee(employee!.id, rest)
      }
      return adminApi.createEmployee(payload)
    },
    onSuccess: () => {
      toast.success(isEdit ? 'Employee updated' : 'Employee created')
      queryClient.invalidateQueries({ queryKey: ['admin', 'employees'] })
      onClose()
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  return (
    <Modal open={open} onClose={onClose} title={isEdit ? `Edit ${employee?.fullName}` : 'New employee'} size="lg">
      <form onSubmit={handleSubmit((v) => mutation.mutate({ ...v, shift: to24hShift(v.shift) ?? (v.shift ?? '') }))} className="space-y-4">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input label="Employee Code" placeholder="EMP-010" disabled={isEdit} {...register('employeeCode')} error={errors.employeeCode?.message} />
          <Input label="Full Name" placeholder="Jane Doe" {...register('fullName')} error={errors.fullName?.message} />
        </div>
        <Input label="Email" type="email" placeholder="jane.doe@company.com" {...register('email')} error={errors.email?.message} />
        {!isEdit && <Input label="Password (optional)" type="password" placeholder="Leave blank for default password" {...register('password')} error={errors.password?.message} />}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Select
            label="Team"
            placeholder="Select team"
            options={departments.map((d) => ({ value: String(d.id), label: d.name }))}
            error={errors.departmentId?.message}
            {...register('departmentId')}
          />
          <Select
            label="Role"
            disabled={isEdit}
            options={[
              { value: 'EMPLOYEE', label: 'Employee' },
              { value: 'ADMIN', label: 'Admin' },
            ]}
            {...register('role')}
          />
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input label="Designation" placeholder="Software Engineer" {...register('designation')} />
          <Input label="Location" placeholder="New York" {...register('location')} />
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input label="Shift" placeholder="e.g. 05:30 AM - 02:30 PM" {...register('shift')} />
          <Input label="Week Off" placeholder="Sat-Sun" {...register('weekOff')} />
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input label="Phone" placeholder="+1 555 0000" {...register('phone')} />
        </div>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input label="Date of Joining" type="date" {...register('dateOfJoining')} error={errors.dateOfJoining?.message} />
          <Input label="Date of Birth" type="date" {...register('dateOfBirth')} />
        </div>
        <div className="flex justify-end gap-2 border-t border-surface-100 pt-4">
          <Button type="button" variant="secondary" onClick={onClose}>Cancel</Button>
          <Button type="submit" loading={mutation.isPending}>{isEdit ? 'Save Changes' : 'Create Employee'}</Button>
        </div>
      </form>
    </Modal>
  )
}

export function AdminEmployeesPage() {
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const debouncedSearch = useDebouncedValue(search, 300)
  const [status, setStatus] = useState('')
  const [departmentId, setDepartmentId] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<Summary | null>(null)

  const { data: departments } = useQuery({ queryKey: ['departments'], queryFn: departmentApi.list })
  const { data: pageData, isLoading } = useQuery({
    queryKey: ['admin', 'employees', { search: debouncedSearch, status, departmentId }],
    queryFn: () =>
      adminApi.employees({
        page: 0,
        size: 100,
        sort: 'shift',
        search: debouncedSearch || undefined,
        status: status || undefined,
        departmentId: departmentId ? Number(departmentId) : undefined,
      }),
  })

  const { data: liveToday } = useQuery({
    queryKey: ['today', 'status', 'live'],
    queryFn: () => todayApi.status(),
    refetchInterval: 60_000,
    retry: 1,
  })

  const liveMap = useMemo(() => {
    const leaveIds = new Set<number>((liveToday?.onLeave ?? []).map((o) => o.employeeId))
    const wfhIds = new Set<number>()
    const offTodayIds = new Set<number>()
    for (const m of liveToday?.members ?? []) {
      if (m.status === 'WFH') wfhIds.add(m.employeeId)
      if (m.status === 'WEEK_OFF') offTodayIds.add(m.employeeId)
      if (m.status === 'OFF' && !leaveIds.has(m.employeeId)) offTodayIds.add(m.employeeId)
    }
    return { leaveIds, wfhIds, offTodayIds }
  }, [liveToday])

  const employees = useMemo(() => {
    const rows = pageData?.content ?? []
    return [...rows].sort(
      (a, b) => String(a.shift ?? '').localeCompare(String(b.shift ?? '')) || a.fullName.localeCompare(b.fullName),
    )
  }, [pageData])

  const toggleStatus = useMutation({
    mutationFn: (e: Summary) => adminApi.setEmployeeStatus(e.id, e.employmentStatus === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'),
    onSuccess: () => {
      toast.success('Employee status updated')
      queryClient.invalidateQueries({ queryKey: ['admin', 'employees'] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  return (
    <div>
      <PageHeader
        title="Employees"
        subtitle="Manage employee records and access"
        actions={<Button onClick={() => { setEditing(null); setModalOpen(true); }}><Plus className="h-4 w-4" /> New employee</Button>}
      />

      <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-4">
        <div className="relative sm:col-span-2">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-surface-400" />
          <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search by name, code, or email…" className="input pl-9" />
        </div>
        <select value={status} onChange={(e) => setStatus(e.target.value)} className="select">
          <option value="">All statuses</option>
          <option value="ACTIVE">Active</option>
          <option value="INACTIVE">Inactive</option>
        </select>
        <select value={departmentId} onChange={(e) => setDepartmentId(e.target.value)} className="select">
          <option value="">All teams</option>
          {(departments ?? []).map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
        </select>
      </div>

      <div className="card overflow-hidden">
        {isLoading ? (
          <div className="grid place-items-center py-20"><Spinner /></div>
        ) : !pageData?.content.length ? (
          <EmptyState title="No employees found" description="Try adjusting your search or filters." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[820px]">
              <thead className="border-b border-surface-200 bg-surface-50">
                <tr>
                  <th className="th">Employee</th>
                  <th className="th">Department</th>
                  <th className="th">Designation</th>
                  <th className="th">Location</th>
                  <th className="th">Shift</th>
                  <th className="th">Week off</th>
                  <th className="th">Joining</th>
                  <th className="th">Status</th>
                  <th className="th" />
                </tr>
              </thead>
<tbody>
                  {employees.map((e) => {
                  const live = liveStatusOf(e, new Date(), liveMap)
                  return (
                  <tr key={e.id} className="bg-row transition-colors duration-150 hover:bg-rowhover">
                    <td className="td">
                      <div className="flex items-center gap-3">
                        <Avatar name={e.fullName} size="sm" />
                        <div className="min-w-0">
                          <Link to={`/admin/employees/${e.id}`} className="truncate text-sm font-medium text-surface-800 transition-colors hover:text-brand-400">{e.fullName}</Link>
                          <p className="text-xs text-surface-500">{e.employeeCode} · {e.email}</p>
                        </div>
                      </div>
                    </td>
                    <td className="td">{e.department ?? '—'}</td>
                    <td className="td">{e.designation ?? '—'}</td>
                    <td className="td">{e.location ?? '—'}</td>
                    <td className="td">{e.shift ? <span className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium ${shiftStyle(e.shift)}`}>{formatShiftDisplay(e.shift)}</span> : '—'}</td>
                    <td className="td">{e.weekOff ?? '—'}</td>
                    <td className="td"><span className="text-surface-500">{formatDate(String(e.dateOfJoining))}</span></td>
                    <td className="td">
                      <span className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium ${LIVE_STYLES[live]}`}>
                        {LIVE_LABELS[live]}
                      </span>
                    </td>
                    <td className="td text-right">
                      <div className="flex justify-end gap-1">
                        <button title="Edit" className="icon-btn" onClick={() => { setEditing(e); setModalOpen(true); }}><Pencil className="h-4 w-4" /></button>
                        <button title={e.employmentStatus === 'ACTIVE' ? 'Deactivate' : 'Activate'} className="icon-btn" onClick={() => toggleStatus.mutate(e)} disabled={toggleStatus.isPending}>
                          <Power className="h-4 w-4" />
                        </button>
                      </div>
                    </td>
                  </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {modalOpen && (
        <EmployeeFormModal open={modalOpen} onClose={() => setModalOpen(false)} employee={editing} departments={departments ?? []} />
      )}
    </div>
  )
}