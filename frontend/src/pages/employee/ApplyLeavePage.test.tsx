import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ApplyLeavePage } from '@/pages/employee/ApplyLeavePage'
import { holidayApi, hpeHolidayApi, leaveApi } from '@/api'
import type { HPEEntitlement } from '@/types'

vi.mock('@/api', () => ({
  leaveApi: { my: vi.fn(), balances: vi.fn(), apply: vi.fn(), cancel: vi.fn() },
  holidayApi: { list: vi.fn(), upcoming: vi.fn() },
  hpeHolidayApi: { availableEntitlements: vi.fn(), allEntitlements: vi.fn(), summary: vi.fn() },
}))

vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
  useSearchParams: () => [new URLSearchParams()],
}))

function entitlement(overrides: Partial<HPEEntitlement>): HPEEntitlement {
  return {
    id: 300,
    employeeId: 10,
    employeeName: 'John Doe',
    holidayId: 7,
    holidayName: 'Ganesh Chaturthi',
    holidayDate: '2026-09-14',
    earnedDate: '2026-09-14',
    expiryDate: '2026-12-14',
    status: 'AVAILABLE',
    usedDate: null,
    usedRequestId: null,
    reservedRequestId: null,
    notes: null,
    ...overrides,
  }
}

const ganesh = entitlement({})
const gandhiJayanti = entitlement({
  id: 301,
  holidayId: 8,
  holidayName: 'Gandhi Jayanti',
  holidayDate: '2026-10-02',
  earnedDate: '2026-10-02',
  expiryDate: '2027-01-02',
})

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <ApplyLeavePage />
    </QueryClientProvider>,
  )
}

/** Switches Leave Type to CO and waits for the entitlement dropdown to appear. */
async function chooseCompOff() {
  await userEvent.selectOptions(screen.getByLabelText('Leave Type'), 'COMP_OFF')
  return screen.findByLabelText('HPE Holiday to Use')
}

describe('ApplyLeavePage - HPE Holiday to Use', () => {
  beforeEach(() => {
    vi.mocked(holidayApi.list).mockResolvedValue([])
    vi.mocked(hpeHolidayApi.availableEntitlements).mockResolvedValue([ganesh, gandhiJayanti])
    vi.mocked(leaveApi.apply).mockResolvedValue({} as never)
  })

  it('hides the field for non-Compensatory-Off leave types', () => {
    renderPage()
    expect(screen.queryByLabelText('HPE Holiday to Use')).not.toBeInTheDocument()
  })

  it('does not call the entitlements endpoint until Compensatory Off is selected', () => {
    renderPage()
    expect(hpeHolidayApi.availableEntitlements).not.toHaveBeenCalled()
  })

  it('reveals the field with a placeholder and lists available entitlements', async () => {
    renderPage()
    const select = await chooseCompOff()

    expect(vi.mocked(hpeHolidayApi.availableEntitlements)).toHaveBeenCalled()
    expect(screen.getByRole('option', { name: 'Select HPE Holiday...' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Ganesh Chaturthi — Sep 14, 2026' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Gandhi Jayanti — Oct 2, 2026' })).toBeInTheDocument()
    expect(select).toBeInTheDocument()
  })

  it('shows the expiry of the selected entitlement', async () => {
    renderPage()
    const select = await chooseCompOff()

    await userEvent.selectOptions(select, '300')

    // The summary card restates the chosen holiday and its expiry (the <option>
    // carries the same text, so pick the non-option node).
    const summary = screen
      .getAllByText('Ganesh Chaturthi — Sep 14, 2026')
      .find((el) => el.tagName !== 'OPTION')!
      .closest('div')
    expect(summary).toHaveTextContent('Earned Sep 14, 2026')
    expect(summary).toHaveTextContent('Expires: Dec 14, 2026')
  })

  it('explains the absence of entitlements instead of offering a dead dropdown', async () => {
    vi.mocked(hpeHolidayApi.availableEntitlements).mockResolvedValue([])
    renderPage()
    await chooseCompOff()

    expect(screen.getByText(/no available HPE holidays/i)).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Select HPE Holiday...' })).toBeInTheDocument()
  })

  it('blocks submission when no HPE Holiday is selected', async () => {
    renderPage()
    const select = await chooseCompOff()

    await userEvent.type(screen.getByLabelText('Start Date'), '2026-10-05')
    await userEvent.type(screen.getByLabelText('End Date'), '2026-10-05')
    await userEvent.type(screen.getByLabelText('Reason'), 'worked on the HPE holiday')
    await userEvent.click(screen.getByRole('button', { name: 'Submit request' }))

    expect(await screen.findByText('Select the HPE Holiday to use')).toBeInTheDocument()
    expect(leaveApi.apply).not.toHaveBeenCalled()
    expect(select).toBeInTheDocument()
  })

  it('submits the chosen entitlement together with an independent off date', async () => {
    renderPage()
    const select = await chooseCompOff()

    // The off date is deliberately not the original HPE holiday date (14 Sep).
    await userEvent.selectOptions(select, '300')
    await userEvent.type(screen.getByLabelText('Start Date'), '2026-10-05')
    await userEvent.type(screen.getByLabelText('End Date'), '2026-10-05')
    await userEvent.type(screen.getByLabelText('Reason'), 'worked on the HPE holiday')
    await userEvent.click(screen.getByRole('button', { name: 'Submit request' }))

    await vi.waitFor(() => expect(leaveApi.apply).toHaveBeenCalledTimes(1))
    expect(vi.mocked(leaveApi.apply).mock.calls[0][0]).toEqual(
      expect.objectContaining({
        leaveType: 'COMP_OFF',
        startDate: '2026-10-05',
        endDate: '2026-10-05',
        hpeEntitlementId: '300',
      }),
    )
  })

  it('omits the entitlement when the leave type is switched away from Compensatory Off', async () => {
    renderPage()
    const select = await chooseCompOff()
    await userEvent.selectOptions(select, '300')

    await userEvent.selectOptions(screen.getByLabelText('Leave Type'), 'SICK_LEAVE')

    expect(screen.queryByLabelText('HPE Holiday to Use')).not.toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Start Date'), '2026-10-05')
    await userEvent.type(screen.getByLabelText('End Date'), '2026-10-05')
    await userEvent.type(screen.getByLabelText('Reason'), 'not feeling well')
    await userEvent.click(screen.getByRole('button', { name: 'Submit request' }))

    await vi.waitFor(() => expect(leaveApi.apply).toHaveBeenCalledTimes(1))
    const payload = vi.mocked(leaveApi.apply).mock.calls[0][0]
    expect(payload).not.toHaveProperty('hpeEntitlementId')
  })

  it('does not require an entitlement for the other leave types', async () => {
    renderPage()

    await userEvent.type(screen.getByLabelText('Start Date'), '2026-10-05')
    await userEvent.type(screen.getByLabelText('End Date'), '2026-10-05')
    await userEvent.type(screen.getByLabelText('Reason'), 'not feeling well')
    await userEvent.click(screen.getByRole('button', { name: 'Submit request' }))

    await vi.waitFor(() => expect(leaveApi.apply).toHaveBeenCalledTimes(1))
  })
})
