import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { HolidaysPage } from '@/pages/employee/HolidaysPage'
import { eventApi, holidayApi } from '@/api'
import type { Holiday } from '@/types'

vi.mock('@/api', () => ({
  holidayApi: { myHolidays: vi.fn(), list: vi.fn(), upcoming: vi.fn() },
  eventApi: { list: vi.fn() },
  authApi: { login: vi.fn(), me: vi.fn(), logout: vi.fn() },
}))

function holiday(overrides: Partial<Holiday>): Holiday {
  return {
    id: 1,
    name: 'Ganesh Chaturthi',
    date: '2026-09-14',
    country: 'IN',
    holidayType: 'HPE_HOLIDAY',
    applicableLocations: 'PUNE_MUMBAI',
    description: null,
    scope: 'GLOBAL',
    teamId: null,
    ...overrides,
  }
}

const hpeHoliday = holiday({})
const usHoliday = holiday({
  id: 2,
  name: 'Labor Day',
  date: '2026-09-07',
  country: 'US',
  holidayType: 'PUBLIC',
  applicableLocations: 'ALL',
})

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <HolidaysPage />
    </QueryClientProvider>,
  )
}

describe('HolidaysPage holiday types', () => {
  beforeEach(() => {
    vi.mocked(holidayApi.myHolidays).mockResolvedValue([usHoliday, hpeHoliday])
    vi.mocked(eventApi.list).mockResolvedValue([])
  })

  it('labels a US holiday as "US Holiday" with the US colour, not "Public Holiday"', async () => {
    renderPage()

    expect(await screen.findByText('Labor Day')).toBeInTheDocument()

    const labels = screen.getAllByText('US Holiday')
    expect(labels.length).toBeGreaterThan(0)

    const style = labels[0].getAttribute('style') ?? ''
    expect(style).toContain('--holiday-us-bg')
    expect(style).toContain('--holiday-us-text')

    // The generic label must not be used for a US holiday
    expect(screen.queryByText('Public Holiday')).toBeNull()
  })

  it('labels an HPE holiday as "HPE Holiday" with the HPE colour', async () => {
    renderPage()

    expect(await screen.findByText('Ganesh Chaturthi')).toBeInTheDocument()

    const labels = screen.getAllByText('HPE Holiday')
    expect(labels.length).toBeGreaterThan(0)

    const style = labels[0].getAttribute('style') ?? ''
    expect(style).toContain('--holiday-hpe-bg')
    expect(style).toContain('--holiday-hpe-text')
  })

  it('shows US and HPE holidays in the same month section', async () => {
    renderPage()

    expect(await screen.findByText('Labor Day')).toBeInTheDocument()
    expect(screen.getByText('Ganesh Chaturthi')).toBeInTheDocument()

    // September section holds both
    const september = screen.getByText('September 2026').closest('div') as HTMLElement
    expect(september.textContent).toContain('Labor Day')
    expect(september.textContent).toContain('Ganesh Chaturthi')
  })
})
