import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useState } from 'react'
import toast from 'react-hot-toast'
import { swapOffApi, employeeApi, authApi } from '@/api'
import { swapOffSchema, type SwapOffForm } from '@/validations/schemas'
import { extractMessage } from '@/api/client'
import { formatDate } from '@/utils'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Textarea } from '@/components/ui/Textarea'
import { Select } from '@/components/ui/Select'
import { Modal } from '@/components/ui/Modal'

interface EmployeeOption {
  employeeCode: string
  fullName: string
  department: string | null
}

interface ApplySwapOffFormProps {
  onSubmitSuccess?: () => void
}

export function ApplySwapOffForm({ onSubmitSuccess }: ApplySwapOffFormProps) {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const preSelectedWorkedForCode = searchParams.get('workedForId') ?? null
  const queryClient = useQueryClient()

  const { data: currentUser } = useQuery({
    queryKey: ['auth', 'me'],
    queryFn: authApi.me,
  })

  const currentMonth = new Date().toISOString().slice(0, 7)

  const form = useForm<SwapOffForm>({
    resolver: zodResolver(swapOffSchema),
    defaultValues: {
      attachment: null,
      workedForEmployeeId: preSelectedWorkedForCode ?? undefined,
    },
  })

  const workedDate = form.watch('workedDate')
  const requestedOffDate = form.watch('requestedOffDate')
  const workedForEmployeeId = form.watch('workedForEmployeeId')

  const { data: employees, isLoading: loadingEmployees } = useQuery({
    queryKey: ['employees', 'roster-month-search', currentMonth],
    queryFn: () => employeeApi.rosterMonthSearch(currentMonth, ''),
    enabled: true,
  })

  const filteredEmployees = employees?.filter(e => e.employeeCode !== undefined) ?? []

  const [showConfirm, setShowConfirm] = useState(false)

  const mutation = useMutation({
    mutationFn: swapOffApi.apply,
    onSuccess: () => {
      toast.success('Swap off request submitted')
      queryClient.invalidateQueries({ queryKey: ['swap-offs'] })
      onSubmitSuccess?.()
      navigate('/leaves')
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  const handleSubmitWithConfirm = (data: SwapOffForm) => {
    setShowConfirm(true)
  }

  const confirmedSubmit = () => {
    setShowConfirm(false)
    const formData = form.getValues()
    mutation.mutate({
      workedDate: formData.workedDate,
      requestedOffDate: formData.requestedOffDate,
      workedForEmployeeId: formData.workedForEmployeeId!,
      reason: formData.reason,
      attachment: formData.attachment ?? undefined,
    })
  }

  const getWorkedForEmployee = () => {
    if (!workedForEmployeeId) return null
    return filteredEmployees.find(e => e.employeeCode === workedForEmployeeId) ?? null
  }

  const workedForEmployee = getWorkedForEmployee()

  const selectOptions = filteredEmployees.map(emp => ({
    value: emp.employeeCode,
    label: `${emp.fullName} (${emp.employeeCode})${emp.department ? ` · ${emp.department}` : ''}`,
  }))

  return (
    <>
      <form onSubmit={form.handleSubmit(handleSubmitWithConfirm)} className="space-y-3 p-4">
        <div className="rounded border border-brand-100 bg-brand-50 px-3 py-2 text-sm text-brand-800 dark:border-brand-500/20 dark:bg-brand-500/10 dark:text-brand-300">
          Example: You worked on behalf of another employee on Sunday (20 Sep 2026). Select the employee you worked for and request your corresponding day off.
        </div>

        <div>
          <Select
            label="Working On Behalf Of"
            error={form.formState.errors.workedForEmployeeId?.message}
            options={selectOptions}
            placeholder="Select employee..."
            name="workedForEmployeeId"
            onChange={(e) => {
              const value = e.target.value
              form.setValue('workedForEmployeeId', value || '', { shouldValidate: true })
            }}
          />
          {form.formState.errors.workedForEmployeeId && (
            <p className="mt-1 text-sm text-red-600">{form.formState.errors.workedForEmployeeId.message}</p>
          )}
        </div>

        {workedForEmployee && (
          <div className="rounded border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-800 dark:border-emerald-500/20 dark:bg-emerald-500/10 dark:text-emerald-300 space-y-0.5">
            <p className="font-medium">You are working on behalf of {workedForEmployee.fullName}</p>
            {workedDate && <p>Worked Date: <span className="font-medium">{formatDate(workedDate)}</span></p>}
            {requestedOffDate && <p>Requested Off Date: <span className="font-medium">{formatDate(requestedOffDate)}</span></p>}
            <p className="text-emerald-700">{workedForEmployee.fullName} will receive the requested day off after this swap is approved.</p>
          </div>
        )}

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <Input label="Worked Date" type="date" {...form.register('workedDate')} error={form.formState.errors.workedDate?.message} />
          <Input label="Requested Off Date" type="date" {...form.register('requestedOffDate')} error={form.formState.errors.requestedOffDate?.message} />
        </div>

        <Textarea label="Reason" rows={2} placeholder="Explain the swap…" {...form.register('reason')} error={form.formState.errors.reason?.message} />

        <div className="flex justify-end gap-2 border-t border-surface-100 pt-2">
          <Button type="button" variant="secondary" size="sm" onClick={() => navigate(-1)}>Cancel</Button>
          <Button type="submit" size="sm" loading={mutation.isPending}>Submit request</Button>
        </div>
      </form>

      <Modal open={showConfirm} onClose={() => setShowConfirm(false)} title="Confirm Swap Off Request" size="sm">
        <div className="space-y-3 text-sm">
          <div className="grid grid-cols-2 gap-2 text-xs">
            <div className="text-surface-500">You (Requester)</div>
            <div className="font-medium text-right">{currentUser?.fullName ?? '—'}</div>
            <div className="text-surface-500">Working on behalf of</div>
            <div className="font-medium text-right">{workedForEmployee?.fullName ?? '—'}</div>
            <div className="text-surface-500">Worked Date</div>
            <div className="font-medium text-right">{workedDate ? formatDate(workedDate) : '—'}</div>
            <div className="text-surface-500">Requested Off Date</div>
            <div className="font-medium text-right">{requestedOffDate ? formatDate(requestedOffDate) : '—'}</div>
          </div>
          {form.watch('reason') && (
            <div>
              <div className="text-surface-500 text-xs">Reason</div>
              <div className="font-medium">{form.watch('reason')}</div>
            </div>
          )}
        </div>
        <div className="flex justify-end gap-2 border-t border-surface-100 pt-4">
          <Button type="button" variant="secondary" onClick={() => setShowConfirm(false)}>Cancel</Button>
          <Button type="button" onClick={confirmedSubmit} loading={mutation.isPending}>
            {mutation.isPending ? 'Submitting…' : 'Submit Request'}
          </Button>
        </div>
      </Modal>
    </>
  )
}