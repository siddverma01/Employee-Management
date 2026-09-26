import { useEffect, useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { X } from 'lucide-react'
import toast from 'react-hot-toast'
import { leaveApi, holidayApi, hpeHolidayApi } from '@/api'
import { leaveSchema, type LeaveForm } from '@/validations/schemas'
import { extractMessage } from '@/api/client'
import { formatDate, toISODate } from '@/utils'
import type { HPEEntitlement } from '@/types'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Select } from '@/components/ui/Select'
import { Textarea } from '@/components/ui/Textarea'
import { Modal } from '@/components/ui/Modal'
import { cn } from '@/utils'

function calculateLeaveDays(startIso: string, endIso: string, holidayDates: Set<string>): number {
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

interface ApplyLeaveModalProps {
  isOpen: boolean
  onClose: () => void
}

export function ApplyLeaveModal({ isOpen, onClose }: ApplyLeaveModalProps) {
  const queryClient = useQueryClient()
  const [daysCount, setDaysCount] = useState(0)

  const { data: holidays } = useQuery({ queryKey: ['holidays', 'list'], queryFn: () => holidayApi.list() })
  const holidayDates = useMemo(() => new Set((holidays ?? []).map((h) => h.date)), [holidays])

  const {
    register,
    handleSubmit,
    watch,
    setValue,
    formState: { errors },
  } = useForm<LeaveForm>({
    resolver: zodResolver(leaveSchema),
    defaultValues: { leaveType: 'PRIVILEGE_LEAVE', attachment: null, hpeEntitlementId: undefined },
  })

  const leaveType = watch('leaveType')
  const startDate = watch('startDate')
  const endDate = watch('endDate')
  const hpeEntitlementId = watch('hpeEntitlementId')
  const isCompOff = leaveType === 'COMP_OFF'

  const { data: hpeEntitlements, isLoading: loadingHpeEntitlements } = useQuery({
    queryKey: ['hpe-holidays', 'entitlements', 'available'],
    queryFn: hpeHolidayApi.availableEntitlements,
    enabled: isCompOff,
  })

  useEffect(() => {
    if (startDate && endDate && startDate <= endDate) {
      setDaysCount(calculateLeaveDays(startDate, endDate, holidayDates))
    } else {
      setDaysCount(0)
    }
  }, [startDate, endDate, holidayDates])

  useEffect(() => {
    if (!isCompOff && hpeEntitlementId) {
      setValue('hpeEntitlementId', undefined)
    }
  }, [isCompOff, hpeEntitlementId, setValue])

  const hpeOptions = useMemo(
    () =>
      (hpeEntitlements ?? []).map((e: HPEEntitlement) => ({
        value: String(e.id),
        label: `${e.holidayName} — ${formatDate(e.holidayDate)}`,
      })),
    [hpeEntitlements],
  )

  const selectedEntitlement = useMemo(
    () => (hpeEntitlements ?? []).find((e) => String(e.id) === hpeEntitlementId) ?? null,
    [hpeEntitlements, hpeEntitlementId],
  )

  const mutation = useMutation({
    mutationFn: leaveApi.apply,
    onSuccess: () => {
      toast.success('Leave request submitted')
      queryClient.invalidateQueries({ queryKey: ['leaves'] })
      queryClient.invalidateQueries({ queryKey: ['hpe-holidays'] })
      onClose()
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  if (!isOpen) return null

  return (
<Modal
      open={isOpen}
      onClose={onClose}
      title="Apply for Leave"
      size="lg"
      footer={
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>Cancel</Button>
          <Button type="submit" form="apply-leave-form" loading={mutation.isPending}>Submit Request</Button>
        </div>
      }
    >
      <form
        id="apply-leave-form"
        onSubmit={handleSubmit(({ attachment, hpeEntitlementId: entitlementId, ...v }) =>
          mutation.mutate({
            ...v,
            ...(attachment ? { attachment } : {}),
            ...(entitlementId ? { hpeEntitlementId: entitlementId } : {}),
          }),
        )}
        className="space-y-5"
      >
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

        {isCompOff && (
          <div className="space-y-3">
            <Select
              label="HPE Holiday to Use"
              name="hpeEntitlementId"
              placeholder="Select HPE Holiday..."
              options={hpeOptions}
              disabled={loadingHpeEntitlements}
              onChange={(e) =>
                setValue('hpeEntitlementId', e.target.value || undefined, { shouldValidate: true })
              }
              value={hpeEntitlementId ?? ''}
              error={errors.hpeEntitlementId?.message}
            />

            {loadingHpeEntitlements ? (
              <p className="text-sm text-surface-500">Loading your earned HPE holidays…</p>
            ) : hpeOptions.length === 0 ? (
              <div className="rounded-lg border border-surface-200 bg-surface-50 p-3 text-sm text-surface-600">
                You have no available HPE holidays to use. An entitlement is earned when you work on an
                HPE holiday, and can be used within 3 calendar months of that date.
              </div>
            ) : selectedEntitlement ? (
              <div className="rounded-lg border border-surface-200 bg-surface-50 p-3 text-sm">
                <p className="font-medium text-surface-800">
                  {selectedEntitlement.holidayName} — {formatDate(selectedEntitlement.holidayDate)}
                </p>
                <p className="mt-1 text-xs text-surface-500">
                  Earned {formatDate(selectedEntitlement.earnedDate)} · Expires:{' '}
                  {formatDate(selectedEntitlement.expiryDate)}
                </p>
              </div>
            ) : (
              <p className="text-sm text-surface-500">
                Pick the HPE holiday you worked on. The compensatory off date is chosen separately below.
              </p>
            )}
          </div>
        )}

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <Input label="Start Date" type="date" {...register('startDate')} error={errors.startDate?.message} />
          <Input label="End Date" type="date" {...register('endDate')} error={errors.endDate?.message} />
        </div>

        {daysCount > 0 && (
          <div className="inline-flex items-center gap-2 rounded-full bg-brand-50 px-3 py-1.5 text-sm font-medium text-brand-700 dark:bg-brand-500/10 dark:text-brand-400">
            {daysCount} {daysCount === 1 ? 'Day' : 'Days'} selected (excluding weekends & holidays)
          </div>
        )}

        <Textarea label="Reason" rows={3} placeholder="Explain the reason for your leave…" {...register('reason')} error={errors.reason?.message} />
      </form>
    </Modal>
  )
}