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

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <CalendarPage />
    </QueryClientProvider>,
  )
}

describe('CalendarPage HPE holidays', () => {
  beforeEach(() => {
    vi.mocked(calendarApi.month).mockResolvedValue([publicHoliday, hpeHoliday])
    vi.mocked(holidayApi.list).mockResolvedValue([])
    vi.mocked(eventApi.list).mockResolvedValue([])
  })

  it('renders the HPE holiday in the grid with an HPEH badge', async () => {
    renderPage()

    const title = await screen.findByText('Ganesh Chaturthi')
    const chip = title.closest('div') as HTMLElement
    expect(chip.textContent).toContain('HPEH')
    expect(chip.className).toContain('bg-emerald-50')

    expect(screen.getByText('Company Day')).toBeInTheDocument()
    expect(await screen.findByText('HPE Holiday')).toBeInTheDocument() // legend entry
  })

  it('keeps the legend entry for HPE Holiday', async () => {
    renderPage()

    expect(await screen.findByText('HPE Holiday')).toBeInTheDocument()
  })

  it('opens the details modal labelled HPE Holiday with the HPEH status', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByText('Ganesh Chaturthi'))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('HPE Holiday')).toBeInTheDocument()
    expect(within(dialog).getByText('HPEH')).toBeInTheDocument()
    expect(within(dialog).getByText('Ganesh Chaturthi')).toBeInTheDocument()
    expect(within(dialog).getByText('Details')).toBeInTheDocument()
  })

  it('labels a regular holiday as Holiday without the HPEH status', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByText('Company Day'))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Holiday')).toBeInTheDocument()
    expect(within(dialog).queryByText('HPEH')).toBeNull()
    expect(within(dialog).queryByText('HPE Holiday')).toBeNull()
  })
})
