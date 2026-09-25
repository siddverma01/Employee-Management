import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useNavigate } from 'react-router-dom'
import toast from 'react-hot-toast'
import { swapOffApi } from '@/api'
import { swapOffSchema, type SwapOffForm } from '@/validations/schemas'
import { extractMessage } from '@/api/client'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Textarea } from '@/components/ui/Textarea'

export function ApplySwapOffPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<SwapOffForm>({ resolver: zodResolver(swapOffSchema), defaultValues: { attachment: null } })

  const mutation = useMutation({
    mutationFn: swapOffApi.apply,
    onSuccess: () => {
      toast.success('Swap off request submitted')
      queryClient.invalidateQueries({ queryKey: ['swap-offs'] })
      navigate('/swap-off')
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  return (
    <div className="mx-auto max-w-2xl">
      <PageHeader title="Apply swap off / Compensatory off" subtitle="Request a day off for a day you worked outside your schedule" />

      <form onSubmit={handleSubmit(({ attachment, ...v }) => mutation.mutate({ ...v, ...(attachment ? { attachment } : {}) }))} className="card space-y-5 p-6">
        <div className="rounded-lg border border-brand-100 bg-brand-50 px-4 py-3 text-sm text-brand-800 dark:border-brand-500/20 dark:bg-brand-500/10 dark:text-brand-300">
          Example: you worked on Sunday (20 Sep 2026) for production support — request the comp off on a day you wish to take off (e.g. 25 Sep 2026).
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input label="Worked Date" type="date" {...register('workedDate')} error={errors.workedDate?.message} />
          <Input label="Requested Off Date" type="date" {...register('requestedOffDate')} error={errors.requestedOffDate?.message} />
        </div>

        <Textarea label="Reason" rows={3} placeholder="Explain the swap…" {...register('reason')} error={errors.reason?.message} />

        <div className="flex justify-end gap-2 border-t border-surface-100 pt-4">
          <Button type="button" variant="secondary" onClick={() => navigate(-1)}>Cancel</Button>
          <Button type="submit" loading={mutation.isPending}>Submit request</Button>
        </div>
      </form>
    </div>
  )
}