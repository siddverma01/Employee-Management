import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import type { z } from 'zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, Pencil, Trash2 } from 'lucide-react'
import toast from 'react-hot-toast'
import type { CompanyEvent, Holiday } from '@/types'
import { adminApi, departmentApi } from '@/api'
import { eventSchema, holidaySchema, type EventForm, type HolidayForm } from '@/validations/schemas'
import { extractMessage } from '@/api/client'
import { cn, formatDate } from '@/utils'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Select } from '@/components/ui/Select'
import { Textarea } from '@/components/ui/Textarea'
import { Modal } from '@/components/ui/Modal'
import { StatusBadge } from '@/components/ui/StatusBadge'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'

const STATUS_LABELS: Record<string, string> = { PUBLIC: 'PUBLIC', OPTIONAL: 'OPTIONAL', OBSERVED: 'OBSERVED' }

function ScopeBadge({ scope, team }: { scope: string; team?: string }) {
  return (
    <span
      className={cn(
        'rounded-full px-2 py-0.5 text-[10px] font-semibold',
        scope === 'GLOBAL' ? 'bg-surface-100 text-surface-600' : 'bg-brand-50 text-brand-700 dark:bg-surface-100 dark:text-brand-400',
      )}
    >
      {scope.charAt(0) + scope.slice(1).toLowerCase()}{team ? ` · ${team}` : ''}
    </span>
  )
}

export function AdminHolidaysPage() {
  const [tab, setTab] = useState<'holidays' | 'events'>('holidays')
  const year = new Date().getFullYear()
  const [from] = useState(`${year}-01-01`)
  const [to] = useState(`${year}-12-31`)
  const queryClient = useQueryClient()

  const holidaysQuery = useQuery({ queryKey: ['admin', 'holidays', from, to], queryFn: () => adminApi.holidays({ from, to }) })
  const eventsQuery = useQuery({ queryKey: ['admin', 'events', from, to], queryFn: () => adminApi.events({ from, to }) })
  const { data: departments = [] } = useQuery({ queryKey: ['teams'], queryFn: departmentApi.list })

  const [holidayModal, setHolidayModal] = useState<{ open: boolean; holiday: Holiday | null }>({ open: false, holiday: null })
  const [eventModal, setEventModal] = useState<{ open: boolean; event: CompanyEvent | null }>({ open: false, event: null })

  // ---- Holiday mutations ----
  const holidayForm = useForm<z.input<typeof holidaySchema>, unknown, z.output<typeof holidaySchema>>({ resolver: zodResolver(holidaySchema) })
  const holidayMutation = useMutation({
    mutationFn: (v: HolidayForm) =>
      holidayModal.holiday
        ? adminApi.updateHoliday(holidayModal.holiday.id, v)
        : adminApi.createHoliday(v),
    onSuccess: () => { toast.success('Holiday saved'); setHolidayModal({ open: false, holiday: null }); queryClient.invalidateQueries({ queryKey: ['admin', 'holidays'] }); },
    onError: (err) => toast.error(extractMessage(err)),
  })
  const deleteHoliday = useMutation({
    mutationFn: adminApi.deleteHoliday,
    onSuccess: () => { toast.success('Holiday deleted'); queryClient.invalidateQueries({ queryKey: ['admin', 'holidays'] }); },
    onError: (err) => toast.error(extractMessage(err)),
  })

  // ---- Event mutations ----
  const eventForm = useForm<z.input<typeof eventSchema>, unknown, z.output<typeof eventSchema>>({ resolver: zodResolver(eventSchema) })
  const eventMutation = useMutation({
    mutationFn: (v: EventForm) =>
      eventModal.event
        ? adminApi.updateEvent(eventModal.event.id, v)
        : adminApi.createEvent(v),
    onSuccess: () => { toast.success('Event saved'); setEventModal({ open: false, event: null }); queryClient.invalidateQueries({ queryKey: ['admin', 'events'] }); },
    onError: (err) => toast.error(extractMessage(err)),
  })
  const deleteEvent = useMutation({
    mutationFn: adminApi.deleteEvent,
    onSuccess: () => { toast.success('Event deleted'); queryClient.invalidateQueries({ queryKey: ['admin', 'events'] }); },
    onError: (err) => toast.error(extractMessage(err)),
  })

  const openHolidayModal = (h: Holiday | null) => {
    setHolidayModal({ open: true, holiday: h })
    holidayForm.reset(
      h
        ? { name: h.name, date: String(h.date).slice(0, 10), country: h.country, holidayType: h.holidayType as HolidayForm['holidayType'], description: h.description ?? '', scope: h.scope, teamId: h.teamId ?? undefined }
        : { name: '', date: '', country: 'US', holidayType: 'PUBLIC', description: '', scope: 'GLOBAL', teamId: undefined },
    )
  }
  const openEventModal = (e: CompanyEvent | null) => {
    setEventModal({ open: true, event: e })
    eventForm.reset(
      e
        ? { title: e.title, eventDate: String(e.eventDate).slice(0, 10), eventType: e.eventType, description: e.description ?? '', scope: e.scope, teamId: e.teamId ?? undefined }
        : { title: '', eventDate: '', eventType: 'COMPANY_EVENT', description: '', scope: 'GLOBAL', teamId: undefined },
    )
  }

  if (holidaysQuery.isLoading) return <LoadingState label="Loading…" />

  return (
    <div>
      <PageHeader
        title="Holidays & events"
        subtitle={`Manage public holidays and company events for ${year}`}
        actions={
          <Button
            onClick={() => (tab === 'holidays' ? openHolidayModal(null) : openEventModal(null))}
          >
            <Plus className="h-4 w-4" /> {tab === 'holidays' ? 'New holiday' : 'New event'}
          </Button>
        }
      />

      <div className="mb-4 flex gap-1 rounded-lg bg-surface-100 p-1 w-fit">
        {(['holidays', 'events'] as const).map((t) => (
          <button
            key={t}
            onClick={() => setTab(t)}
            className={`rounded-md px-4 py-1.5 text-sm font-medium capitalize transition-colors ${tab === t ? 'bg-surface-0 text-brand-700 shadow-sm dark:shadow-none dark:border dark:border-surface-200 dark:text-brand-300' : 'text-surface-500 hover:text-surface-700'}`}
          >
            {t}
          </button>
        ))}
      </div>

      {tab === 'holidays' ? (
        <div className="card overflow-hidden">
          {!holidaysQuery.data?.length ? (
            <EmptyState title="No holidays" description="Add a public holiday for this year." />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[640px]">
                <thead className="border-b border-surface-200 bg-surface-50">
                  <tr>
                    <th className="th">Name</th>
                    <th className="th">Date</th>
                    <th className="th">Type</th>
                    <th className="th">Country</th>
                    <th className="th">Scope</th>
                    <th className="th" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-surface-200">
                  {holidaysQuery.data.map((h) => (
                    <tr key={h.id} className="transition-colors duration-150 hover:bg-rowhover">
                      <td className="td font-medium text-surface-800">{h.name}</td>
                      <td className="td">{formatDate(h.date)}</td>
                      <td className="td"><StatusBadge status={h.holidayType} /></td>
                      <td className="td text-xs text-surface-500">{h.country || '—'}</td>
                      <td className="td text-xs">
                        <ScopeBadge scope={h.scope} team={departments.find((d) => d.id === h.teamId)?.name} />
                      </td>
                      <td className="td text-right">
                        <div className="flex justify-end gap-1">
                          <button className="icon-btn" title="Edit" onClick={() => openHolidayModal(h)}><Pencil className="h-4 w-4" /></button>
                          <button className="icon-btn" title="Delete" onClick={() => { if (confirm(`Delete holiday "${h.name}"?`)) deleteHoliday.mutate(h.id); }}><Trash2 className="h-4 w-4 text-red-600" /></button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ) : (
        <div className="card overflow-hidden">
          {!eventsQuery.data?.length ? (
            <EmptyState title="No events" description="Add a company event for this year." />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[640px]">
                <thead className="border-b border-surface-200 bg-surface-50">
                  <tr>
                    <th className="th">Title</th>
                    <th className="th">Date</th>
                    <th className="th">Type</th>
                    <th className="th">Scope</th>
                    <th className="th">Description</th>
                    <th className="th" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-surface-200">
                  {eventsQuery.data.map((e) => (
                    <tr key={e.id} className="transition-colors duration-150 hover:bg-rowhover">
                      <td className="td font-medium text-surface-800">{e.title}</td>
                      <td className="td">{formatDate(e.eventDate)}</td>
                      <td className="td text-xs">{e.eventType}</td>
                      <td className="td text-xs">
                        <ScopeBadge scope={e.scope} team={departments.find((d) => d.id === e.teamId)?.name} />
                      </td>
                      <td className="td max-w-[280px] truncate text-xs text-surface-500">{e.description}</td>
                      <td className="td text-right">
                        <div className="flex justify-end gap-1">
                          <button className="icon-btn" title="Edit" onClick={() => openEventModal(e)}><Pencil className="h-4 w-4" /></button>
                          <button className="icon-btn" title="Delete" onClick={() => { if (confirm(`Delete event "${e.title}"?`)) deleteEvent.mutate(e.id); }}><Trash2 className="h-4 w-4 text-red-600" /></button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {/* Holiday modal */}
      <Modal open={holidayModal.open} onClose={() => setHolidayModal({ open: false, holiday: null })} title={holidayModal.holiday ? 'Edit Holiday' : 'New Holiday'}>
        <form onSubmit={holidayForm.handleSubmit((v) => holidayMutation.mutate(v))} className="space-y-4">
          <Input label="Name" placeholder="Independence Day" {...holidayForm.register('name')} error={holidayForm.formState.errors.name?.message} />
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input label="Date" type="date" {...holidayForm.register('date')} error={holidayForm.formState.errors.date?.message} />
            <Select label="Type" options={Object.keys(STATUS_LABELS).map((v) => ({ value: v, label: v }))} {...holidayForm.register('holidayType')} />
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input label="Country" placeholder="US" {...holidayForm.register('country')} />
            <Input label="Description" placeholder="Optional" {...holidayForm.register('description')} />
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Select
              label="Scope"
              options={[
                { value: 'GLOBAL', label: 'Global (all teams)' },
                { value: 'TEAM', label: 'Team-specific' },
              ]}
              {...holidayForm.register('scope')}
            />
            {holidayForm.watch('scope') === 'TEAM' ? (
              <Select
                label="Team"
                options={departments.map((d) => ({ value: String(d.id), label: d.name }))}
                error={holidayForm.formState.errors.teamId?.message}
                {...holidayForm.register('teamId')}
              />
            ) : (
              <div />
            )}
          </div>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={() => setHolidayModal({ open: false, holiday: null })}>Cancel</Button>
            <Button type="submit" loading={holidayMutation.isPending}>Save</Button>
          </div>
        </form>
      </Modal>

      {/* Event modal */}
      <Modal open={eventModal.open} onClose={() => setEventModal({ open: false, event: null })} title={eventModal.event ? 'Edit Event' : 'New Event'}>
        <form onSubmit={eventForm.handleSubmit((v) => eventMutation.mutate(v))} className="space-y-4">
          <Input label="Title" placeholder="Quarterly Town Hall" {...eventForm.register('title')} error={eventForm.formState.errors.title?.message} />
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input label="Date" type="date" {...eventForm.register('eventDate')} error={eventForm.formState.errors.eventDate?.message} />
            <Input label="Type" placeholder="COMPANY_EVENT" {...eventForm.register('eventType')} />
          </div>
          <Textarea label="Description" rows={3} placeholder="Optional" {...eventForm.register('description')} />
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Select
              label="Scope"
              options={[
                { value: 'GLOBAL', label: 'Global (all teams)' },
                { value: 'TEAM', label: 'Team-specific' },
              ]}
              {...eventForm.register('scope')}
            />
            {eventForm.watch('scope') === 'TEAM' ? (
              <Select
                label="Team"
                options={departments.map((d) => ({ value: String(d.id), label: d.name }))}
                error={eventForm.formState.errors.teamId?.message}
                {...eventForm.register('teamId')}
              />
            ) : (
              <div />
            )}
          </div>
          <div className="flex justify-end gap-2">
            <Button type="button" variant="secondary" onClick={() => setEventModal({ open: false, event: null })}>Cancel</Button>
            <Button type="submit" loading={eventMutation.isPending}>Save</Button>
          </div>
        </form>
      </Modal>
    </div>
  )
}