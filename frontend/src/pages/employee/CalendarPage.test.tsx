import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CalendarPage } from '@/pages/employee/CalendarPage'
import { calendarApi, eventApi, holidayApi } from '@/api'
import type { CalendarEvent } from '@/types'

vi.mock('@/api', () => ({
  calendarApi: { month: vi.fn() },
  holidayApi: { list: vi.fn() },
  eventApi: { list: vi.fn() },
  departmentApi: { list: vi.fn() },
  authApi: { login: vi.fn(), me: vi.fn(), logout: vi.fn() },
}))

function ev(overrides: Partial<CalendarEvent>): CalendarEvent {
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
    description: 'HPE holiday - applicable to Pune/Mumbai',
    extra: { holidayType: 'HPE_HOLIDAY', country: 'IN', scope: 'GLOBAL' },
    ...overrides,
  }
}

const hpeHoliday = ev({})
const publicHoliday = ev({
  id: 2,
  title: 'Company Day',
  subtitle: 'PUBLIC',
  date: '2026-09-01',
  extra: { holidayType: 'PUBLIC', country: 'IN', scope: 'GLOBAL' },
})
// Stored as PUBLIC, but country=US must be identified as a US Holiday in the UI
const usHoliday = ev({
  id: 3,
  title: 'Labor Day',
  subtitle: 'PUBLIC',
  date: '2026-09-07',
  extra: { holidayType: 'PUBLIC', country: 'US', scope: 'GLOBAL' },
})

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <CalendarPage />
    </QueryClientProvider>,
  )
}

describe('CalendarPage holiday identification', () => {
  beforeEach(() => {
    vi.mocked(calendarApi.month).mockResolvedValue([usHoliday, publicHoliday, hpeHoliday])
    vi.mocked(holidayApi.list).mockResolvedValue([])
    vi.mocked(eventApi.list).mockResolvedValue([])
  })

  it('renders the HPE holiday chip with an HPEH badge and HPE Holiday colours', async () => {
    renderPage()

    const title = await screen.findByText('Ganesh Chaturthi')
    const chip = title.closest('div') as HTMLElement
    expect(chip.textContent).toContain('HPEH')
    const style = chip.getAttribute('style') ?? ''
    expect(style).toContain('--holiday-hpe-bg')
    expect(style).toContain('--holiday-hpe-text')
    expect(style).toContain('--holiday-hpe-border')
  })

  it('renders the US holiday chip with a US badge and US Holiday colours', async () => {
    renderPage()

    const title = await screen.findByText('Labor Day')
    const chip = title.closest('div') as HTMLElement
    expect(chip.textContent).toContain('US')
    const style = chip.getAttribute('style') ?? ''
    expect(style).toContain('--holiday-us-bg')
    expect(style).toContain('--holiday-us-text')
    expect(style).toContain('--holiday-us-border')
  })

  it('gives HPE Holiday and US Holiday different colours', async () => {
    renderPage()

    const hpe = (await screen.findByText('Ganesh Chaturthi')).closest('div') as HTMLElement
    const us = (await screen.findByText('Labor Day')).closest('div') as HTMLElement
    expect(hpe.getAttribute('style')).not.toBe(us.getAttribute('style'))
  })

  it('exposes the full meaning of the compact badge via the tooltip/aria label', async () => {
    renderPage()

    const title = await screen.findByText('Ganesh Chaturthi')
    const chip = title.closest('div') as HTMLElement
    expect(chip.getAttribute('title')).toBe('HPE Holiday Ganesh Chaturthi')

    const cell = chip.closest('button') as HTMLElement
    expect(cell.getAttribute('aria-label')).toContain('HPE Holiday Ganesh Chaturthi')
  })
})

describe('CalendarPage legend', () => {
  beforeEach(() => {
    vi.mocked(calendarApi.month).mockResolvedValue([usHoliday, publicHoliday, hpeHoliday])
    vi.mocked(holidayApi.list).mockResolvedValue([])
    vi.mocked(eventApi.list).mockResolvedValue([])
  })

  it('lists the six categories', async () => {
    renderPage()

    for (const label of [
      'Leave',
      'Comp Off',
      'HPE Holiday',
      'US Holiday',
      'Birthday',
      'Company Event',
    ]) {
      expect(await screen.findByText(label)).toBeInTheDocument()
    }
  })

  it('never shows "Public Holiday" in the legend', async () => {
    renderPage()

    await screen.findByText('US Holiday')
    expect(screen.queryByText('Public Holiday')).toBeNull()
  })

  it('uses the same colour tokens for the legend swatch as the event chip', async () => {
    renderPage()

    await screen.findByText('US Holiday')

    const swatch = screen.getByTitle('US Holiday').querySelector('span') as HTMLElement
    const swatchStyle = swatch.getAttribute('style') ?? ''
    expect(swatchStyle).toContain('--holiday-us-bg')

    const chip = (await screen.findByText('Labor Day')).closest('div') as HTMLElement
    expect(chip.getAttribute('style')).toContain('--holiday-us-bg')
  })

  it('gives each legend category a distinct colour', async () => {
    renderPage()

    await screen.findByText('Company Event')

    const styles = [
      'Leave',
      'Comp Off',
      'HPE Holiday',
      'US Holiday',
      'Birthday',
      'Company Event',
    ].map((label) => {
      const swatch = screen.getByTitle(label).querySelector('span') as HTMLElement
      return swatch.getAttribute('style')
    })

    expect(new Set(styles).size).toBe(styles.length)
  })

  it('wraps instead of overflowing on narrow screens', async () => {
    renderPage()

    const legend = (await screen.findByText('Leave')).closest('ul') as HTMLElement
    expect(legend.className).toContain('flex-wrap')
  })
})

describe('CalendarPage details modal', () => {
  beforeEach(() => {
    vi.mocked(calendarApi.month).mockResolvedValue([usHoliday, publicHoliday, hpeHoliday])
    vi.mocked(holidayApi.list).mockResolvedValue([])
    vi.mocked(eventApi.list).mockResolvedValue([])
  })

  it('labels an HPE holiday "HPE Holiday" with an HPEH badge', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByText('Ganesh Chaturthi'))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('HPE Holiday')).toBeInTheDocument()
    expect(within(dialog).getByText('HPEH')).toBeInTheDocument()
    expect(within(dialog).getByText('Ganesh Chaturthi')).toBeInTheDocument()
    expect(within(dialog).getByText('Details')).toBeInTheDocument()
  })

  it('labels a US holiday "US Holiday" even though it is stored as PUBLIC', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByText('Labor Day'))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('US Holiday')).toBeInTheDocument()
    expect(within(dialog).getByText('US')).toBeInTheDocument()
    expect(within(dialog).queryByText('Public Holiday')).toBeNull()
  })

  it('keeps a non-US, non-HPE public holiday labelled Public Holiday', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByText('Company Day'))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Public Holiday')).toBeInTheDocument()
    expect(within(dialog).queryByText('HPEH')).toBeNull()
    expect(within(dialog).queryByText('HPE Holiday')).toBeNull()
  })
})
