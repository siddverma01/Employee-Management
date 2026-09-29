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
    // light + dark emerald tint, matching the Stitch HPE Holiday chip
    expect(badge.className).toContain('bg-[#D1FAE5]')
    expect(badge.className).toContain('dark:bg-[#0B3B2E]')

    const chip = chipFor('Ganesh Chaturthi')
    expect(chip.className).toContain('bg-[#ECFDF5]')
    expect(chip.className).toContain('text-[#047857]')
    expect(chip.className).toContain('border-[#A7F3D0]')
    expect(chip.className).toContain('dark:bg-[rgba(5,150,105,0.20)]')
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
    expect(chip.className).toContain('bg-[#EFF6FF]')
    expect(chip.className).toContain('text-[#1D4ED8]')
    expect(chip.className).toContain('border-[#BFDBFE]')
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

describe('CalendarGrid Today cell', () => {
  it('never renders source comments as visible text', () => {
    const { container } = render(
      <CalendarGrid
        year={2026}
        month={9}
        events={[makeEvent({ id: 7, date: '2026-09-25', title: 'Town Hall', kind: 'EVENT', extra: {} })]}
        onSelectDay={() => undefined}
        today="2026-09-25"
      />,
    )

    const text = container.textContent ?? ''
    expect(text).not.toContain('/*')
    expect(text).not.toContain('*/')
    expect(text).not.toContain('circular date badge')
    expect(text).not.toContain('shrink-0')
  })

  it('keeps the 24px badge, TODAY label and the event below them', () => {
    const { container } = render(
      <CalendarGrid
        year={2026}
        month={9}
        events={[makeEvent({ id: 8, date: '2026-09-25', title: 'Town Hall', kind: 'EVENT', extra: {} })]}
        onSelectDay={() => undefined}
        today="2026-09-25"
      />,
    )

    const badge = screen.getByText('25')
    expect(badge.className).toContain('w-6')
    expect(badge.className).toContain('h-6')
    expect(badge.className).toContain('min-w-6')
    expect(badge.className).toContain('shrink-0')
    expect(badge.className).toContain('rounded-full')

    const label = screen.getByText('Today')
    expect(label.className).toContain('uppercase')
    expect(label.className).toContain('shrink-0')

    const dateRow = badge.parentElement
    expect(dateRow?.className).toContain('flex')
    expect(dateRow?.className).toContain('items-center')
    expect(dateRow?.className).toContain('justify-between')
    expect(dateRow?.className).toContain('w-full')
    expect(dateRow?.className).toContain('shrink-0')

    const chip = chipFor('Town Hall')
    const eventRow = chip.parentElement
    expect(eventRow?.className).toContain('mt-2')
    expect(eventRow?.className).toContain('flex-col')
    expect(dateRow?.contains(eventRow as Node)).toBe(false)
    expect(dateRow?.compareDocumentPosition(eventRow as Node)).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
  })
})
