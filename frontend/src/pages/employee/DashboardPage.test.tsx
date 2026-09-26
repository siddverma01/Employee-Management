import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { DashboardPage } from '@/pages/employee/DashboardPage'
import { dashboardApi, holidayApi, leaveApi } from '@/api'
import type { EmployeeDashboard, Holiday } from '@/types'

vi.mock('@/api', () => ({
  dashboardApi: { me: vi.fn(), team: vi.fn() },
  holidayApi: { upcoming: vi.fn(), myHolidays: vi.fn(), list: vi.fn() },
  leaveApi: { balances: vi.fn() },
  authApi: { login: vi.fn(), me: vi.fn(), logout: vi.fn() },
  departmentApi: { list: vi.fn() },
}))

const dashboard: EmployeeDashboard = {
  fullName: 'Masher Choudhary',
  department: 'Voice',
  designation: 'Engineer',
  profilePicture: null,
  today: '2026-09-26',
  todayType: 'WORK_FROM_OFFICE',
  leaveBalances: [],
  wfhDays: 1,
  wfoDays: 2,
  pendingLeaves: 0,
  approvedUpcomingLeaves: 0,
  upcomingHolidays: [],
  upcomingBirthdays: [{ date: '2026-10-01', name: 'Priya Shah', type: 'BIRTHDAY' }],
}

function holiday(overrides: Partial<Holiday>): Holiday {
  return {
    id: 1,
    name: 'Gandhi Jayanti',
    date: '2026-10-02',
    country: 'IN',
    holidayType: 'HPE_HOLIDAY',
    applicableLocations: 'ALL',
    description: null,
    scope: 'GLOBAL',
    teamId: null,
    ...overrides,
  }
}

const hpeHoliday = holiday({})
const usHoliday = holiday({ id: 2, name: 'Columbus Day', country: 'US', holidayType: 'PUBLIC' })

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <DashboardPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

describe('DashboardPage Upcoming list', () => {
  beforeEach(() => {
    vi.mocked(dashboardApi.me).mockResolvedValue(dashboard)
    vi.mocked(dashboardApi.team).mockResolvedValue(null as never)
    vi.mocked(leaveApi.balances).mockResolvedValue([])
    vi.mocked(holidayApi.upcoming).mockResolvedValue([hpeHoliday, usHoliday])
  })

  it('labels a US holiday as "US Holiday" in the Upcoming card', async () => {
    renderPage()

    expect(await screen.findByText('Upcoming')).toBeInTheDocument()
    expect(screen.getByText('Columbus Day')).toBeInTheDocument()

    const label = screen.getByText('US Holiday')
    const style = label.getAttribute('style') ?? ''
    expect(style).toContain('--holiday-us-bg')
    expect(style).toContain('--holiday-us-text')
  })

  it('labels an HPE holiday as "HPE Holiday" in the Upcoming card', async () => {
    renderPage()

    expect(await screen.findByText('Gandhi Jayanti')).toBeInTheDocument()

    const label = screen.getByText('HPE Holiday')
    const style = label.getAttribute('style') ?? ''
    expect(style).toContain('--holiday-hpe-bg')
    expect(style).toContain('--holiday-hpe-text')
  })

  it('does not fall back to "Public Holiday" for a US holiday', async () => {
    renderPage()

    await screen.findByText('Columbus Day')
    expect(screen.queryByText('Public Holiday')).toBeNull()
  })

  it('labels birthdays so every Upcoming row is identifiable', async () => {
    renderPage()

    await screen.findByText('Columbus Day')
    const label = screen.getByText('Birthday')
    const style = label.getAttribute('style') ?? ''
    expect(style).toContain('--attendance-wx-bg')
  })
})
