import { useQuery } from '@tanstack/react-query'
import { Gift, PartyPopper, Sun } from 'lucide-react'
import { eventApi, holidayApi } from '@/api'
import { useTeam } from '@/hooks/useTeam'
import { formatMonthYear, toISODate } from '@/utils'
import { PageHeader } from '@/components/ui/PageHeader'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'

const currentYear = new Date().getFullYear()
const from = `${currentYear}-01-01`
const to = `${currentYear}-12-31`

const holidayIcons: Record<string, React.ReactNode> = {
  PUBLIC: <Sun className="h-4 w-4 text-amber-500" />,
  OPTIONAL: <Gift className="h-4 w-4 text-violet-500" />,
  OBSERVED: <PartyPopper className="h-4 w-4 text-brand-500" />,
}

function MonthSection({ label, holidays, events }: { label: string; holidays: { name: string; date: string; holidayType?: string }[]; events: { title: string; eventDate: string; eventType?: string }[] }) {
  const items = [
    ...holidays.map((h) => ({ key: 'h' + h.name + h.date, icon: holidayIcons[h.holidayType ?? ''] ?? <Sun className="h-4 w-4 text-amber-500" />, name: h.name, date: h.date, tag: h.holidayType ?? 'HOLIDAY', color: 'text-amber-700 bg-amber-50 dark:text-amber-400 dark:bg-surface-100' })),
    ...events.map((e) => ({ key: 'e' + e.title + e.eventDate, icon: <PartyPopper className="h-4 w-4 text-brand-500" />, name: e.title, date: e.eventDate, tag: e.eventType ?? 'EVENT', color: 'text-brand-700 bg-brand-50 dark:text-brand-400 dark:bg-surface-100' })),
  ].sort((a, b) => a.date.localeCompare(b.date))

  if (items.length === 0) return null

  return (
    <div className="card p-4">
      <h3 className="mb-3 text-sm font-semibold text-surface-700">{label}</h3>
      <ul className="divide-y divide-surface-200">
        {items.map((it) => (
          <li key={it.key} className="flex items-center gap-3 py-2">
            <span className="grid h-9 w-12 shrink-0 place-items-center rounded-lg bg-surface-100 text-center">
              <span className="text-lg font-bold leading-none text-surface-700">{Number(it.date.slice(8, 10))}</span>
              <span className="text-[10px] text-surface-400">{new Date(it.date + 'T00:00:00').toLocaleString('en-US', { month: 'short' })}</span>
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium text-surface-800">{it.name}</p>
            </div>
            <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${it.color}`}>{it.tag.toLowerCase()}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

export function HolidaysPage() {
  const { effectiveTeamId } = useTeam()
  const { data: holidays, isLoading } = useQuery({
    queryKey: ['holidays', 'year', effectiveTeamId ?? 'default'],
    queryFn: () => holidayApi.list({ from, to, teamId: effectiveTeamId }),
  })
  const { data: events } = useQuery({
    queryKey: ['events', 'year', effectiveTeamId ?? 'default'],
    queryFn: () => eventApi.list({ from, to, teamId: effectiveTeamId }),
  })

  if (isLoading) return <LoadingState label="Loading holidays…" />

  if (!holidays?.length && !events?.length) {
    return (
      <div>
        <PageHeader title="Holidays & events" subtitle={`Public holidays and company events for ${currentYear}`} />
        <EmptyState title="Nothing scheduled" description="No holidays or events for this year yet." />
      </div>
    )
  }

  const months = Array.from({ length: 12 }, (_, i) => {
    const dt = new Date(currentYear, i, 1)
    return {
      label: formatMonthYear(toISODate(dt)),
      key: `${currentYear}-${String(i + 1).padStart(2, '0')}`,
      holidays: (holidays ?? []).filter((h) => h.date.startsWith(`${currentYear}-${String(i + 1).padStart(2, '0')}`)),
      events: (events ?? []).filter((e) => e.eventDate.startsWith(`${currentYear}-${String(i + 1).padStart(2, '0')}`)),
    }
  })

  return (
    <div>
      <PageHeader title="Holidays & events" subtitle={`Public holidays and company events for ${currentYear}`} />
      <div className="grid gap-4 md:grid-cols-2">
        {months.map((m) => (
          <MonthSection key={m.key} label={m.label} holidays={m.holidays} events={m.events} />
        ))}
      </div>
    </div>
  )
}