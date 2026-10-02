import { useMemo, useState } from 'react'
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
import { holidayLabel, resolveHolidayDisplayType } from '@/constants/holidayStatus'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'

/** Selectable holiday types. Labels come from the shared holiday config so the
 *  form never offers the raw enum name (e.g. "PUBLIC") to the user. */
const STATUS_LABELS = ['PUBLIC', 'OPTIONAL', 'OBSERVED', 'HPE_HOLIDAY'] as const

const APPLICABLE_LOCATIONS = [
  { value: 'ALL', label: 'All Locations' },
  { value: 'PUNE_MUMBAI', label: 'Pune / Mumbai' },
  { value: 'BANGALORE', label: 'Bangalore' },
  { value: 'DELHI', label: 'Delhi' },
  { value: 'HYDERABAD', label: 'Hyderabad' },
  { value: 'CHENNAI', label: 'Chennai' },
  { value: 'KOLKATA', label: 'Kolkata' },
  { value: 'US', label: 'US' },
]

/**
 * Several stored holidays that share a date and name, collapsed into one row.
 *
 * <p>"New Year's Day" exists once per country, so the same date can arrive as
 * several rows that a reader considers a single holiday. The row shows every
 * distinct type and country as its own tag.</p>
 */
interface MergedHoliday {
  /** Every underlying row, ascending by id; the first one drives edit. */
  holidays: Holiday[]
  /** Resolved display types, e.g. ['US', 'PUBLIC'] -> "US Holiday", "Public Holiday". */
  types: string[]
  countries: string[]
  applicableTo: string
}

/** Distinct, order-preserving. Keeps duplicate tags off a merged row. */
function unique(values: string[]): string[] {
  return Array.from(new Set(values))
}

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

  /**
   * One table row, which may stand for several stored holidays.
   *
   * <p>`holidays` is the representative row (lowest id) and drives the edit
   * form, since the fields shown are identical across the group. `rows` keeps
   * every underlying id so a delete can remove the whole group rather than
   * leaving orphans behind.</p>
   */
  const mergedHolidays = useMemo<MergedHoliday[]>(() => {
    const groups = new Map<string, Holiday[]>()
    for (const h of holidaysQuery.data ?? []) {
      // Name is trimmed and lowercased so "Republic Day" and "republic day " are
      // one group. The date is already a normalised ISO date from the API.
      const key = `${h.date}_${h.name.trim().toLowerCase()}`
      const group = groups.get(key)
      if (group) group.push(h)
      else groups.set(key, [h])
    }

    return Array.from(groups.values())
      .map((rows): MergedHoliday => {
        const sorted = [...rows].sort((a, b) => a.id - b.id)
        const first = sorted[0]
        return {
          holidays: sorted,
          // Display type is resolved per row, not read off the raw column: it
          // depends on country and applicableLocations, so the same holidayType
          // can render as "US Holiday" on one row and "Public Holiday" on another.
          types: unique(sorted.map((h) => resolveHolidayDisplayType(h))),
          countries: unique(sorted.map((h) => h.country).filter(Boolean)),
          applicableTo: unique(sorted.map((h) => h.applicableLocations).filter(Boolean)).join(', ') || 'ALL',
        }
      })
      .sort((a, b) => {
        const byDate = new Date(a.holidays[0].date).getTime() - new Date(b.holidays[0].date).getTime()
        // Same-day groups sort by name, then id, so the order is deterministic
        // rather than dependent on Map insertion order.
        if (byDate !== 0) return byDate
        const byName = a.holidays[0].name.localeCompare(b.holidays[0].name)
        return byName !== 0 ? byName : a.holidays[0].id - b.holidays[0].id
      })
  }, [holidaysQuery.data])

  const sortedEvents = useMemo(
    () =>
      [...(eventsQuery.data ?? [])].sort(
        (a, b) =>
          new Date(a.eventDate).getTime() - new Date(b.eventDate).getTime() ||
          a.id - b.id,
      ),
    [eventsQuery.data],
  )

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
        ? { name: h.name, date: String(h.date).slice(0, 10), country: h.country, holidayType: h.holidayType as HolidayForm['holidayType'], applicableLocations: (h.applicableLocations ?? 'ALL') as HolidayForm['applicableLocations'], description: h.description ?? '', scope: h.scope, teamId: h.teamId ?? undefined }
        : { name: '', date: '', country: 'US', holidayType: 'PUBLIC', applicableLocations: 'ALL', description: '', scope: 'GLOBAL', teamId: undefined },
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
        subtitle={`Manage holidays and company events for ${year}`}
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
          {!mergedHolidays.length ? (
            <EmptyState title="No holidays" description="Add a holiday for this year." />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[640px]">
                <thead className="border-b border-surface-200 bg-surface-50">
                  <tr>
                    <th className="th">Name</th>
                    <th className="th">Date</th>
                    <th className="th">Type</th>
                    <th className="th">Country</th>
                    <th className="th">Applicable To</th>
                    <th className="th">Scope</th>
                    <th className="th" />
                  </tr>
                </thead>
                <tbody className="divide-y divide-surface-200">
                  {mergedHolidays.map((m) => {
                    const h = m.holidays[0]
                    return (
                    <tr key={m.holidays.map((r) => r.id).join('-')} className="transition-colors duration-150 hover:bg-rowhover">
                      <td className="td font-medium text-surface-800">{h.name}</td>
                      <td className="td">{formatDate(h.date)}</td>
                      <td className="td">
                        {/* One badge per distinct type: a merged row can be a US
                            holiday and an Indian one on the same day. */}
                        <div className="flex flex-wrap gap-1">
                          {m.types.map((t) => (
                            <StatusBadge key={t} status={t} />
                          ))}
                        </div>
                      </td>
                      <td className="td text-xs text-surface-500">{m.countries.length ? m.countries.join(', ') : '—'}</td>
                      <td className="td text-xs text-surface-500">{m.applicableTo}</td>
                      <td className="td text-xs">
                        {/* Only one scope is shown, since the group's rows can
                            disagree; the widest is the least misleading. */}
                        <ScopeBadge scope={h.scope} team={departments.find((d) => d.id === h.teamId)?.name} />
                      </td>
                      <td className="td text-right">
                        <div className="flex justify-end gap-1">
                          <button className="icon-btn" title="Edit" onClick={() => openHolidayModal(h)}><Pencil className="h-4 w-4" /></button>
                          <button
                            className="icon-btn"
                            title="Delete"
                            onClick={() => {
                              // Deletes every row in the group, otherwise the
                              // duplicates resurface as separate entries on reload.
                              const label = m.holidays.length > 1
                                ? `Delete all ${m.holidays.length} entries for "${h.name}"?`
                                : `Delete holiday "${h.name}"?`
                              if (confirm(label)) {
                                m.holidays.forEach((row) => deleteHoliday.mutate(row.id))
                              }
                            }}
                          >
                            <Trash2 className="h-4 w-4 text-red-600" />
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
      ) : (
        <div className="card overflow-hidden">
          {!sortedEvents.length ? (
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
                  {sortedEvents.map((e) => (
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
            <Select label="Type" options={STATUS_LABELS.map((v) => ({ value: v, label: holidayLabel(v) }))} {...holidayForm.register('holidayType')} />
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input label="Country" placeholder="US" {...holidayForm.register('country')} />
            <Select label="Applicable Locations" options={APPLICABLE_LOCATIONS} {...holidayForm.register('applicableLocations')} />
          </div>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <Input label="Description" placeholder="Optional" {...holidayForm.register('description')} />
            <div />
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