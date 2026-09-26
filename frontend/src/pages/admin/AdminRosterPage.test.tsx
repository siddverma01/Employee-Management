import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AdminRosterPage } from '@/pages/admin/AdminRosterPage'
import { adminApi } from '@/api'
import type {
  RosterMonthlyData,
  RosterPageMeta,
  RosterStatusDetail,
  RosterTodayData,
} from '@/types'

vi.mock('@/api', () => ({
  adminApi: {
    rosterMonthlyMeta: vi.fn(),
    rosterMonthly: vi.fn(),
    rosterToday: vi.fn(),
    rosterStatusDetail: vi.fn(),
    saveRosterStatusDetail: vi.fn(),
    clearRosterStatusDetail: vi.fn(),
    rosterMonthlySave: vi.fn(),
  },
}))

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => ({ user: { role: 'ADMIN' } }),
}))

vi.mock('react-router-dom', () => ({
  useSearchParams: () => [new URLSearchParams('month=2025-09'), vi.fn()],
}))

const MONTH = '2025-09'
const HPE_DATE = '2025-09-14'
const OFF_DATE = '2025-09-22'

const meta: RosterPageMeta = {
  teams: [],
  months: [MONTH],
  locations: [],
  shifts: [],
  statuses: [],
}

const days = [
  {
    date: OFF_DATE,
    dayNumber: 22,
    weekday: 'Mon',
    weekend: false,
    holiday: false,
    holidayName: null,
  },
]

const monthly: RosterMonthlyData = {
  month: MONTH,
  teamId: null,
  teamName: null,
  days,
  employees: [
    {
      employeeId: 'E1',
      employeeName: 'Alice',
      email: 'alice@example.com',
      location: null,
      shift: null,
      weekOff: null,
      teamId: null,
      teamName: null,
      days: { [OFF_DATE]: 'CO' },
    },
    {
      employeeId: 'E2',
      employeeName: 'Bob',
      email: 'bob@example.com',
      location: null,
      shift: null,
      weekOff: null,
      teamId: null,
      teamName: null,
      days: { [OFF_DATE]: 'CO' },
    },
  ],
  counters: { CO: 2 },
  totalEmployees: 2,
  matchedEmployees: 2,
  page: 0,
  size: 50,
  totalElements: 2,
  totalPages: 1,
}

const today: RosterTodayData = {
  date: OFF_DATE,
  teamId: null,
  teamName: null,
  weekday: 'Mon',
  weekend: false,
  holiday: false,
  holidayName: null,
  employees: monthly.employees,
  counters: { CO: 2 },
  totalEmployees: 2,
  matchedEmployees: 2,
}

function detail(overrides: Partial<RosterStatusDetail> = {}): RosterStatusDetail {
  return {
    employeeId: 'E1',
    date: OFF_DATE,
    statusCode: 'CO',
    statusName: 'Compensatory Off',
    description: null,
    createdByName: null,
    createdAt: null,
    updatedByName: null,
    updatedAt: null,
    sourceRequestId: 11,
    sourceRequestType: 'LEAVE',
    sourceReason: 'Compensatory off earned from Ganesh Chaturthi',
    submittedByName: 'Alice',
    approvedByName: 'Manager Bob',
    approvedAt: '2025-09-01T10:00:00Z',
    workedForName: null,
    workedDate: null,
    hpeHolidayName: 'Ganesh Chaturthi',
    hpeHolidayDate: HPE_DATE,
    ...overrides,
  }
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <AdminRosterPage />
    </QueryClientProvider>,
  )
}

/**
 * Opens the popup for a specific employee's CO cell on the requested off date.
 * The frozen employee-info panel and the day-status grid are separate tables,
 * so the cell is located by its `data-status` attribute, ordered as rendered.
 */
async function openCoCell(employeeName: string) {
  await screen.findByText(employeeName)
  const row = monthly.employees.findIndex((e) => e.employeeName === employeeName)
  const cell = document.querySelectorAll<HTMLButtonElement>(
    'button[data-status="CO"]',
  )[row]
  await userEvent.click(cell)
}

describe('AdminRosterPage CO status detail', () => {
  beforeEach(() => {
    vi.mocked(adminApi.rosterMonthlyMeta).mockResolvedValue(meta)
    vi.mocked(adminApi.rosterMonthly).mockResolvedValue(monthly)
    vi.mocked(adminApi.rosterToday).mockResolvedValue(today)
    vi.mocked(adminApi.rosterStatusDetail).mockResolvedValue(detail())
  })

  it('shows status, HPE holiday and approver for an HPE-backed CO cell', async () => {
    renderPage()
    await openCoCell('Alice')

    await waitFor(() =>
      expect(screen.getByText(/HPE Holiday: Ganesh Chaturthi/)).toBeInTheDocument(),
    )
    expect(screen.getByText(/Status: Compensatory Off/)).toBeInTheDocument()
    expect(screen.getByText(/Approved by: Manager Bob/)).toBeInTheDocument()
    // The employee's own reason is preserved verbatim.
    expect(
      screen.getByText('Compensatory off earned from Ganesh Chaturthi'),
    ).toBeInTheDocument()
  })

  it('does not render an HPE Holiday row for a non-HPE CO', async () => {
    vi.mocked(adminApi.rosterStatusDetail).mockResolvedValue(
      detail({ hpeHolidayName: null, hpeHolidayDate: null }),
    )
    renderPage()
    await openCoCell('Alice')

    await waitFor(() => expect(screen.getByText(/Approved by:/)).toBeInTheDocument())
    expect(screen.queryByText(/HPE Holiday:/)).not.toBeInTheDocument()
  })

  it('resolves the popup per employee so no details leak across employees', async () => {
    vi.mocked(adminApi.rosterStatusDetail).mockImplementation(async (employeeId) =>
      employeeId === 'E1'
        ? detail()
        : detail({
            employeeId: 'E2',
            hpeHolidayName: 'Independence Day',
            hpeHolidayDate: '2025-07-04',
            approvedByName: 'Manager Carol',
            sourceReason: 'Compensatory off earned from Independence Day',
          }),
    )

    renderPage()
    await openCoCell('Bob')

    await waitFor(() =>
      expect(screen.getByText(/HPE Holiday: Independence Day/)).toBeInTheDocument(),
    )
    expect(screen.getByText(/Approved by: Manager Carol/)).toBeInTheDocument()
    expect(screen.queryByText(/Ganesh Chaturthi/)).not.toBeInTheDocument()
    expect(adminApi.rosterStatusDetail).toHaveBeenCalledWith('E2', OFF_DATE)
  })

  it('never exposes a save control for an HPE-sourced CO cell', async () => {
    renderPage()
    await openCoCell('Alice')

    await waitFor(() => expect(screen.getByText(/HPE Holiday:/)).toBeInTheDocument())
    // Source-backed cells are read-only, so no manual description textarea.
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.queryByRole('textbox')).not.toBeInTheDocument()
    expect(adminApi.saveRosterStatusDetail).not.toHaveBeenCalled()
  })

  it('still shows an editable description for a cell with no source request', async () => {
    vi.mocked(adminApi.rosterStatusDetail).mockResolvedValue(
      detail({
        sourceRequestId: null,
        sourceRequestType: null,
        sourceReason: null,
        submittedByName: null,
        approvedByName: null,
        approvedAt: null,
        hpeHolidayName: null,
        hpeHolidayDate: null,
      }),
    )
    renderPage()
    await openCoCell('Alice')

    await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument())
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getByText('Description')).toBeInTheDocument()
    expect(dialog.getByRole('textbox')).toBeInTheDocument()
  })
})
