import { useEffect, useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import toast from 'react-hot-toast'
import { leaveApi, holidayApi } from '@/api'
import { leaveSchema, type LeaveForm } from '@/validations/schemas'
import { extractMessage } from '@/api/client'
import { toISODate } from '@/utils'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Select } from '@/components/ui/Select'
import { Textarea } from '@/components/ui/Textarea'

export function calculateLeaveDays(startIso: string, endIso: string, holidayDates: Set<string>): number {
  const start = new Date(startIso + 'T00:00:00')
  const end = new Date(endIso + 'T00:00:00')
  let days = 0
  for (let d = start; d <= end; d.setDate(d.getDate() + 1)) {
    const dow = d.getDay()
    const iso = toISODate(d)
    if (dow === 0 || dow === 6) continue
    if (holidayDates.has(iso)) continue
    days++
  }
  return days
}

export function ApplyLeavePage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [daysCount, setDaysCount] = useState(0)

  const { data: holidays } = useQuery({ queryKey: ['holidays', 'list'], queryFn: () => holidayApi.list() })
  const holidayDates = useMemo(() => new Set((holidays ?? []).map((h) => h.date)), [holidays])

  const {
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<LeaveForm>({
    resolver: zodResolver(leaveSchema),
    defaultValues: { leaveType: 'PRIVILEGE_LEAVE', attachment: null },
  })

  const startDate = watch('startDate')
  const endDate = watch('endDate')

  useEffect(() => {
    if (startDate && endDate && startDate <= endDate) {
      setDaysCount(calculateLeaveDays(startDate, endDate, holidayDates))
    } else {
      setDaysCount(0)
    }
  }, [startDate, endDate, holidayDates])

  const mutation = useMutation({
    mutationFn: leaveApi.apply,
    onSuccess: () => {
      toast.success('Leave request submitted')
      queryClient.invalidateQueries({ queryKey: ['leaves'] })
      navigate('/leaves')
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader title="Apply for leave" subtitle="Submit a new leave request for approval" />

      <form onSubmit={handleSubmit(({ attachment, ...v }) => mutation.mutate({ ...v, ...(attachment ? { attachment } : {}) }))} className="card space-y-5 p-6">
        <Select
          label="Leave Type"
          options={[
            { value: 'PRIVILEGE_LEAVE', label: 'PL · Privilege Leave' },
            { value: 'SICK_LEAVE', label: 'SL · Sick Leave' },
            { value: 'COMP_OFF', label: 'CO · Compensatory Off' },
          ]}
          {...register('leaveType')}
          error={errors.leaveType?.message}
        />

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input label="Start Date" type="date" {...register('startDate')} error={errors.startDate?.message} />
          <Input label="End Date" type="date" {...register('endDate')} error={errors.endDate?.message} />
        </div>

        <div className="rounded-lg border border-surface-200 bg-surface-50 px-4 py-3 text-sm">
          <span className="text-surface-500">Number of days:</span>{' '}
          <span className="font-bold text-brand-700">{daysCount}</span>
          <span className="ml-2 text-xs text-surface-400">(weekends & public holidays excluded)</span>
        </div>

        <Textarea label="Reason" rows={3} placeholder="Explain the reason for your leave…" {...register('reason')} error={errors.reason?.message} />

        <div className="flex justify-end gap-2 border-t border-surface-100 pt-4">
          <Button type="button" variant="secondary" onClick={() => navigate(-1)}>Cancel</Button>
          <Button type="submit" loading={mutation.isPending}>Submit request</Button>
        </div>
      </form>
    </div>
  )
}