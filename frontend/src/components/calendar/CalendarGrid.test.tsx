import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { CalendarGrid } from '@/components/calendar/CalendarGrid'
import type { CalendarEvent } from '@/types'

function makeEvent(overrides: Partial<CalendarEvent> = {}): CalendarEvent {
  return {
    id: 1,
    kind: 'HOLIDAY',
    date: '2026-09-14',
    startDate: '2026-09-14',
    endDate: '2026-09-14',
    title: 'Ganesh Chaturthi',
    subtitle: 'HPE_HOLIDAY',
    employeeName: null,
    employeeCode: null,
    employeeId: null,
    leaveType: null,
    leaveTypeCode: null,
    status: null,
    description: null,
    extra: { holidayType: 'HPE_HOLIDAY' },
    ...overrides,
  }
}

function chipFor(title: string): HTMLElement {
  const el = screen.getByText(title)
  const chip = el.closest('.event-chip')
  if (!chip) throw new Error(`no .event-chip ancestor for "${title}"`)
  return chip as HTMLElement
}

describe('CalendarGrid HPE holidays', () => {
  it('shows an HPEH badge and keeps the holiday chip styling', () => {
    render(
      <CalendarGrid
        year={2026}
        month={9}
        events={[makeEvent()]}
        onSelectDay={() => undefined}
        today="2026-09-25"
      />,
    )

    const badge = screen.getByText('HPEH')
    expect(badge.className).toContain('text-[9px]')
    expect(badge.className).toContain('font-bold')
    expect(badge.className).toContain('uppercase')
    // light + dark emerald tint, matching the Stitch Company Holiday chip
    expect(badge.className).toContain('bg-emerald-200/80')
    expect(badge.className).toContain('dark:bg-emerald-800/80')

    const chip = chipFor('Ganesh Chaturthi')
    expect(chip.className).toContain('bg-emerald-50')
    expect(chip.className).toContain('text-emerald-900')
    expect(chip.className).toContain('border-emerald-200')
    expect(chip.className).toContain('dark:bg-emerald-950/70')
    expect(chip.textContent).toContain('HPEH')
    expect(chip.textContent).toContain('Ganesh Chaturthi')
  })

  it('badges a regular public holiday as US rather than HPEH', () => {
    render(
      <CalendarGrid
        year={2026}
        month={9}
        events={[
          makeEvent({
            id: 2,
            title: 'Company Day',
            subtitle: 'PUBLIC',
            extra: { holidayType: 'PUBLIC' },
          }),
        ]}
        onSelectDay={() => undefined}
        today="2026-09-25"
      />,
    )

    expect(screen.queryByText('HPEH')).toBeNull()
    expect(screen.getByText('US')).toBeInTheDocument()

    const chip = chipFor('Company Day')
    expect(chip.className).toContain('bg-blue-50')
    expect(chip.className).toContain('text-blue-900')
    expect(chip.className).toContain('border-blue-200')
  })

  it('does not badge leave, event or birthday entries', () => {
    render(
      <CalendarGrid
        year={2026}
        month={9}
        events={[
          makeEvent({ id: 3, kind: 'LEAVE', title: 'Siddhesh Verma - Sick Leave', extra: {} }),
          makeEvent({ id: 4, kind: 'EVENT', title: 'Town Hall', extra: {} }),
          makeEvent({ id: 5, kind: 'BIRTHDAY', title: 'Priya Shah', extra: {} }),
        ]}
        onSelectDay={() => undefined}
        today="2026-09-25"
      />,
    )

    expect(screen.queryByText('HPEH')).toBeNull()
  })

  it('keeps HPE holidays on their own date alongside other events', () => {
    render(
      <CalendarGrid
        year={2026}
        month={9}
        events={[
          makeEvent(),
          makeEvent({ id: 6, kind: 'LEAVE', title: 'Alex Doe - Comp Off', extra: {} }),
        ]}
        onSelectDay={() => undefined}
        today="2026-09-25"
      />,
    )

    expect(screen.getByText('HPEH')).toBeInTheDocument()
    expect(screen.getByText('Alex Doe - Comp Off')).toBeInTheDocument()
  })
})
