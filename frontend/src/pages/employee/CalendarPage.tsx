import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { calendarApi, holidayApi, eventApi } from '@/api'
import { useTeam } from '@/hooks/useTeam'
import { CalendarGrid } from '@/components/calendar/CalendarGrid'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Modal } from '@/components/ui/Modal'
import { LoadingState } from '@/components/ui/LoadingState'
import { formatDate, HPE_HOLIDAY_BADGE, HPE_HOLIDAY_LABEL, isHpeHoliday } from '@/utils'
import type { CalendarEvent } from '@/types'

const KIND_LABELS: Record<string, string> = {
  LEAVE: 'Leave',
  COMP_OFF: 'Comp Off',
  HOLIDAY: 'Holiday',
  BIRTHDAY: 'Birthday',
  EVENT: 'Company Event',
}

export function CalendarPage() {
  const now = new Date()
  const [year, setYear] = useState(now.getFullYear())
  const [month, setMonth] = useState(now.getMonth() + 1)
  const [selected, setSelected] = useState<CalendarEvent | null>(null)
  const { effectiveTeamId } = useTeam()

  const todayStr = useMemo(() => {
    const t = new Date()
    return `${t.getFullYear()}-${String(t.getMonth() + 1).padStart(2, '0')}-${String(t.getDate()).padStart(2, '0')}`
  }, [])

  const { data: events, isLoading } = useQuery({
    queryKey: ['calendar', year, month, effectiveTeamId ?? 'default'],
    queryFn: () => calendarApi.month(year, month, effectiveTeamId),
  })

  // preload holidays & events to support the legend
  useQuery({ queryKey: ['holidays', 'current'], queryFn: () => holidayApi.list() })
  useQuery({ queryKey: ['events', 'current'], queryFn: () => eventApi.list() })

  const yearMonth = (y: number, m: number) => new Date(y, m - 1, 1)

  const change = (delta: number) => {
    const d = new Date(year, month - 1 + delta, 1)
    setYear(d.getFullYear())
    setMonth(d.getMonth() + 1)
  }

  const goToday = () => {
    setYear(now.getFullYear())
    setMonth(now.getMonth() + 1)
  }

  return (
    <div>
      <PageHeader
        title="Team calendar"
        subtitle="Leaves, comp offs, holidays, birthdays and company events"
        actions={
          <>
            <Button variant="secondary" size="sm" onClick={goToday}>Today</Button>
            <Button variant="secondary" size="sm" onClick={() => change(-1)}><ChevronLeft className="h-4 w-4" /></Button>
            <Button variant="secondary" size="sm" onClick={() => change(1)}><ChevronRight className="h-4 w-4" /></Button>
          </>
        }
      />

      <div className="mb-4 flex items-center gap-3 text-sm font-semibold text-surface-800">
        <span className="text-lg">{yearMonth(year, month).toLocaleDateString('en-US', { month: 'long', year: 'numeric' })}</span>
      </div>

      {isLoading ? (
        <LoadingState label="Loading calendar…" />
      ) : (
        <CalendarGrid year={year} month={month} events={events ?? []} onSelectDay={(ds) => {
          const evs = (events ?? []).filter((e) => e.date === ds)
          if (evs.length === 1) setSelected(evs[0])
          else if (evs.length > 0) setSelected(evs[0])
        }} today={todayStr} />
      )}

      <div className="mt-4 flex flex-wrap gap-4">
        {Object.entries(KIND_LABELS).map(([key, label]) => (
          <div key={key} className="flex items-center gap-2 text-xs text-surface-600">
            <span className={`h-3 w-3 rounded ${key === 'LEAVE' ? 'bg-sky-200' : key === 'COMP_OFF' ? 'bg-violet-200' : key === 'HOLIDAY' ? 'bg-emerald-200' : key === 'BIRTHDAY' ? 'bg-pink-200' : 'bg-amber-200'}`} />
            {label}
          </div>
        ))}
        <div className="flex items-center gap-2 text-xs text-surface-600">
          <span className="cal-hpeh-tag">{HPE_HOLIDAY_BADGE}</span>
          {HPE_HOLIDAY_LABEL}
        </div>
      </div>

      <Modal open={selected !== null} onClose={() => setSelected(null)} title="Event details" size="sm">
        {selected && (
          <div className="space-y-3 text-sm">
            <div>
              <p className="text-xs text-surface-400">Title</p>
              <p className="font-medium text-surface-900">
                {selected.title}
                {isHpeHoliday(selected) && (
                  <span className="cal-hpeh-tag ml-2 align-middle">{HPE_HOLIDAY_BADGE}</span>
                )}
              </p>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <p className="text-xs text-surface-400">Type</p>
                <p className="font-medium">
                  {isHpeHoliday(selected) ? HPE_HOLIDAY_LABEL : KIND_LABELS[selected.kind] ?? selected.kind}
                </p>
              </div>
              <div>
                <p className="text-xs text-surface-400">Date</p>
                <p className="font-medium">{formatDate(selected.date)}</p>
              </div>
            </div>
            {selected.employeeName && (
              <div>
                <p className="text-xs text-surface-400">Employee</p>
                <p className="font-medium">{selected.employeeName} <span className="text-surface-400">({selected.employeeCode})</span></p>
              </div>
            )}
            {selected.leaveType && (
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <p className="text-xs text-surface-400">Leave type</p>
                  <p className="font-medium">{selected.leaveTypeLabel ?? '-'}</p>
                </div>
                <div>
                  <p className="text-xs text-surface-400">Status</p>
                  <p className="font-medium">{selected.status}</p>
                </div>
              </div>
            )}
            {selected.description && (
              <div>
                <p className="text-xs text-surface-400">Details</p>
                <p className="text-surface-700">{selected.description}</p>
              </div>
            )}
          </div>
        )}
      </Modal>
    </div>
  )
}