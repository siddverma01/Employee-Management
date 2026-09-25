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
  return el.closest('div') as HTMLElement
}

describe('CalendarGrid HPE holidays', () => {
  it('shows an HPEH badge and keeps the existing holiday chip styling', () => {
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
    expect(badge.className).toContain('cal-hpeh-tag')

    const chip = chipFor('Ganesh Chaturthi')
    expect(chip.className).toContain('bg-emerald-50')
    expect(chip.className).toContain('text-emerald-700')
    expect(chip.textContent).toContain('HPEH')
    expect(chip.textContent).toContain('Ganesh Chaturthi')
  })

  it('does not badge a regular public holiday', () => {
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
    expect(chipFor('Company Day').className).toContain('bg-emerald-50')
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
