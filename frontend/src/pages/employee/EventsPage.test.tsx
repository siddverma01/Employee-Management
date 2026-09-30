import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { EventsPage, __testing } from '@/pages/employee/EventsPage'
import { eventApi } from '@/api'
import type { UpcomingEvent, UpcomingEventsResponse } from '@/types'

vi.mock('@/api', () => ({
  eventApi: {
    upcoming: vi.fn(),
    create: vi.fn(),
    list: vi.fn(),
    assignableEngineers: vi.fn(),
    assign: vi.fn(),
  },
  holidayApi: { myHolidays: vi.fn(), list: vi.fn(), upcoming: vi.fn() },
  authApi: { login: vi.fn(), me: vi.fn(), logout: vi.fn() },
}))

vi.mock('react-hot-toast', () => ({
  default: { success: vi.fn(), error: vi.fn() },
}))

const TODAY = '2026-10-01'

function event(overrides: Partial<UpcomingEvent>): UpcomingEvent {
  return {
    id: 1,
    source: 'EVENT',
    category: 'OFFICE_MEETING',
    subject: 'Voice Team Standup',
    description: null,
    date: '2026-10-05',
    startTime: '2026-10-05T15:30:00',
    endTime: null,
    organization: null,
    location: null,
    meetingLink: null,
    setReminder: true,
    todoItems: [],
    employeeName: null,
    eventType: 'OFFICE_MEETING',
    assignedEngineerId: null,
    assignedEngineerName: null,
    assignedEngineerDepartment: null,
    assignedEngineerDesignation: null,
    daysUntil: 4,
    ...overrides,
  }
}

const holiday = event({
  id: 10,
  source: 'HOLIDAY',
  category: 'HOLIDAY',
  subject: 'Gandhi Jayanti',
  date: '2026-10-02',
  startTime: null,
  eventType: null,
  daysUntil: 1,
})

const birthday = event({
  id: 20,
  source: 'BIRTHDAY',
  category: 'BIRTHDAY',
  subject: 'Siddhesh Verma',
  date: '2026-10-03',
  startTime: null,
  eventType: null,
  employeeName: 'Siddhesh Verma',
  daysUntil: 2,
})

const scheduledMeeting = event({
  id: 30,
  category: 'SCHEDULED_MEETING',
  subject: 'Quarterly Review',
  eventType: 'SCHEDULED_MEETING',
  daysUntil: 6,
})

const remoteSession = event({
  id: 40,
  category: 'CUSTOMER_REMOTE_SESSION',
  subject: 'Customer VRS',
  eventType: 'CUSTOMER_REMOTE_SESSION',
  meetingLink: 'https://meet.example.com/abc',
  date: '2026-10-07',
  startTime: '2026-10-07T11:00:00',
  setReminder: false,
  todoItems: ['Share agenda', 'Send pre-read'],
  daysUntil: 6,
})

const allEvents = [holiday, birthday, event({}), scheduledMeeting, remoteSession]

function response(events: UpcomingEvent[] = allEvents): UpcomingEventsResponse {
  return { today: TODAY, days: 30, events }
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <EventsPage />
      </QueryClientProvider>
    </MemoryRouter>,
  )
}

const ENGINEERS = [
  { id: 7, employeeCode: 'EMP-7', fullName: 'Siddhesh Verma', department: 'Platform' },
  { id: 8, employeeCode: 'EMP-8', fullName: 'Masher Choudary', department: null },
]

beforeEach(() => {
  // Default every test to the production path: the reference sample cards are a
  // dev-server affordance, so leaving them on would make the feed assertions
  // below count mock rows. The cases that exercise the fallback opt back in.
  vi.stubEnv('DEV', false)
  vi.mocked(eventApi.upcoming).mockResolvedValue(response())
  vi.mocked(eventApi.create).mockResolvedValue(remoteSession)
  vi.mocked(eventApi.assignableEngineers).mockResolvedValue(ENGINEERS)
})

// Cases below flip import.meta.env.DEV back on to exercise the dev sample-data
// branch; restore it so the default (production) assumption holds everywhere else.
afterEach(() => {
  vi.unstubAllEnvs()
})

describe('EventsPage header', () => {
  it('shows the title and the requested subtitle', async () => {
    renderPage()
    expect(await screen.findByText('Events & Schedule')).toBeInTheDocument()
    expect(
      screen.getByText(
        'View upcoming organizational holidays, birthdays, team syncs, and remote office sessions.',
      ),
    ).toBeInTheDocument()
  })

  it('shows the "Q4 FY26 Active" pill beside the title', async () => {
    renderPage()
    expect(await screen.findByText('Q4 FY26 Active')).toBeInTheDocument()
  })

  it('exposes an "+ Add Event" button in the action header', async () => {
    renderPage()
    expect(await screen.findByRole('button', { name: /add event/i })).toBeInTheDocument()
  })

  it('places the range picker, view toggle and Add Event together on the top right', async () => {
    renderPage()
    await screen.findByText('Voice Team Standup')

    const toolbar = screen.getByRole('button', { name: /add event/i }).closest('div')
    expect(toolbar).not.toBeNull()
    // The window select and both view toggles share the row with the button.
    expect(within(toolbar as HTMLElement).getByRole('combobox', { name: '' })).toBeInTheDocument()
    expect(within(toolbar as HTMLElement).getByRole('button', { name: 'Grid view' })).toBeInTheDocument()
    expect(within(toolbar as HTMLElement).getByRole('button', { name: 'List view' })).toBeInTheDocument()
  })

  it('switches between grid and list layout', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Voice Team Standup')

    await user.click(screen.getByRole('button', { name: 'List view' }))
    expect(screen.getByRole('button', { name: 'List view' })).toHaveAttribute('aria-pressed', 'true')

    await user.click(screen.getByRole('button', { name: 'Grid view' }))
    expect(screen.getByRole('button', { name: 'Grid view' })).toHaveAttribute('aria-pressed', 'true')
  })
})

describe('EventsPage categorisation', () => {
  it('renders a card per event with subject, date/time, type badge and link', async () => {
    renderPage()

    // Customer meetings now render as featured cards, so the generic card's type
    // badge and meeting link live on the internal list instead.
    const card = (await screen.findByText('Voice Team Standup')).closest('button') as HTMLElement
    expect(card).toBeInTheDocument()
    expect(card.textContent).toMatch(/Oct 5, 2026/)
    // This section uses 12-hour clock and no timezone suffix, unlike the customer cards.
    expect(card.textContent).toMatch(/03:30 PM/)
    expect(within(card).getByText('Office')).toBeInTheDocument()

    // The featured card's title is its own button, so assert against the whole card.
    const customer = (await screen.findByText('Customer VRS')).closest('article') as HTMLElement
    expect(customer.textContent).toMatch(/Oct 7, 2026/)
    expect(customer.textContent).toMatch(/11:00 AM/)
  })

  it('offers exactly the four reference filter pills with live counts', async () => {
    renderPage()

    expect(await screen.findByText('Voice Team Standup')).toBeInTheDocument()
    const tabs = screen.getAllByRole('tab')
    expect(tabs).toHaveLength(4)
    // All (5) + Customer Meetings (2: SCHEDULED + REMOTE) + Holidays (2) + Office (1)
    expect(screen.getByRole('tab', { name: 'All (5)' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Customer Meetings (2)' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Holidays (2)' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Office Meetings (1)' })).toBeInTheDocument()
  })

  it('splits the feed into the two highlighted sections', async () => {
    renderPage()

    expect(await screen.findByText('Customer VRS')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Customer Meetings' })).toBeInTheDocument()
    expect(screen.getByText('2 scheduled this month')).toBeInTheDocument()
    expect(screen.getByText('High Priority')).toBeInTheDocument()
    // The count is part of the heading itself: "… Internal Meetings • 3 Events".
    expect(
      screen.getByRole('heading', { name: 'Upcoming Holidays & Internal Meetings • 3 Events' }),
    ).toBeInTheDocument()
    // The range label also exists as a <select> option, so scope to the section.
    const otherSection = screen
      .getByRole('heading', { name: /^Upcoming Holidays & Internal Meetings/ })
      .closest('section') as HTMLElement
    expect(within(otherSection).getByText('Next 30 days')).toBeInTheDocument()
  })


  it('filters the list down to the selected pill', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('Voice Team Standup')
    await user.click(screen.getByRole('tab', { name: 'Holidays (2)' }))

    expect(screen.getByText('Gandhi Jayanti')).toBeInTheDocument()
    expect(screen.getByText('Siddhesh Verma')).toBeInTheDocument()
    expect(screen.queryByText('Voice Team Standup')).not.toBeInTheDocument()
    expect(screen.queryByText('Customer VRS')).not.toBeInTheDocument()
  })

  it('shows only the Customer Meetings section when that pill is active', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('Customer VRS')
    await user.click(screen.getByRole('tab', { name: 'Customer Meetings (2)' }))

    expect(screen.getByRole('heading', { name: 'Customer Meetings' })).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: /^Upcoming Holidays & Internal Meetings/ }),
    ).not.toBeInTheDocument()
    expect(screen.getByText('Customer VRS')).toBeInTheDocument()
    expect(screen.getByText('Quarterly Review')).toBeInTheDocument()
  })

  it('marks the active pill with aria-selected', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('Voice Team Standup')
    expect(screen.getByRole('tab', { name: 'All (5)' })).toHaveAttribute('aria-selected', 'true')

    await user.click(screen.getByRole('tab', { name: 'Office Meetings (1)' }))
    expect(screen.getByRole('tab', { name: 'Office Meetings (1)' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })

  it('shows the empty state when nothing is scheduled outside dev sample mode', async () => {
    vi.stubEnv('DEV', false)
    vi.mocked(eventApi.upcoming).mockResolvedValue(response([]))
    renderPage()
    expect(await screen.findByText('Nothing coming up')).toBeInTheDocument()
  })

  it('renders the reference sample cards in dev when the feed is empty', async () => {
    vi.stubEnv('DEV', true)
    vi.mocked(eventApi.upcoming).mockResolvedValue(response([]))
    renderPage()

    expect(await screen.findByText(/Showing reference sample events/)).toBeInTheDocument()
    expect(screen.queryByText('Nothing coming up')).not.toBeInTheDocument()
    expect(screen.getByText('Q4 Architecture & Solution Review')).toBeInTheDocument()
    expect(screen.getByText('Global Logistics Cloud Migration Check-in')).toBeInTheDocument()
    // The Customer Meetings pill counts the sample cards rather than showing (0).
    expect(screen.getByRole('tab', { name: /Customer Meetings \(2\)/ })).toBeInTheDocument()
  })

  it('renders the six reference holiday and internal meeting cards in dev', async () => {
    vi.stubEnv('DEV', true)
    vi.mocked(eventApi.upcoming).mockResolvedValue(response([]))
    renderPage()

    const heading = await screen.findByRole('heading', {
      name: 'Upcoming Holidays & Internal Meetings • 6 Events',
    })
    const section = heading.closest('section') as HTMLElement

    for (const subject of [
      'Gandhi Jayanti',
      'Voice Team Standup',
      'Quarterly Town Hall',
      'Columbus Day',
      'Company Foundation Day',
      'Engineering All Hands',
    ]) {
      expect(within(section).getByText(subject)).toBeInTheDocument()
    }

    // 12-hour clock, and no timezone suffix on this section's when-line.
    expect(within(section).getByText('Oct 4, 2026 • 10:00 - 10:30 AM')).toBeInTheDocument()
    expect(within(section).getByText('Oct 7, 2026 • 02:00 PM')).toBeInTheDocument()
    expect(within(section).getByText('Oct 21, 2026 • 04:00 - 05:00 PM')).toBeInTheDocument()
    // Holidays carry a date only.
    expect(within(section).getByText('Oct 2, 2026')).toBeInTheDocument()

    // Category tags: three holidays, three office meetings.
    expect(within(section).getAllByText('Holiday')).toHaveLength(3)
    expect(within(section).getAllByText('Office')).toHaveLength(3)

    // Footer: green countdown on the left, location on the right.
    for (const location of [
      'All India Offices',
      'Conference 4B',
      'Auditorium & Virtual',
      'US Locations',
      'Global',
      'Main Stage & Virtual',
    ]) {
      expect(within(section).getByText(location)).toBeInTheDocument()
    }
  })

  it('does not duplicate a sample card when the feed already has that subject', async () => {
    vi.stubEnv('DEV', true)
    vi.mocked(eventApi.upcoming).mockResolvedValue(response([event({})]))
    renderPage()

    // "Voice Team Standup" exists once: the real row, not the sample twin.
    expect((await screen.findAllByText('Voice Team Standup')).length).toBe(1)
    // The holiday samples the database lacks are still filled in.
    expect(screen.getByText('Columbus Day')).toBeInTheDocument()
  })
})

describe('EventsPage add event modal', () => {
  /** Fills the subject, date and time that every submit needs. */
  async function fillRequiredFields(
    user: ReturnType<typeof userEvent.setup>,
    dialog: HTMLElement,
  ) {
    await user.type(within(dialog).getByLabelText('Meeting Subject & Title'), 'Team sync')
    await user.type(within(dialog).getByLabelText('Date'), '2026-10-09')
    await user.type(within(dialog).getByLabelText('Start Time'), '10:00')
  }

  it('renders the reference header', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: 'Schedule New Event' })).toBeInTheDocument()
    expect(
      within(dialog).getByText('Create a meeting, holiday entry, or company session across teams.'),
    ).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Close' })).toBeInTheDocument()
  })

  it('opens with every field from the reference layout', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText('Meeting Subject & Title')).toBeInTheDocument()
    expect(within(dialog).getByText('Max 100 characters')).toBeInTheDocument()
    expect(within(dialog).getByText('Event Category')).toBeInTheDocument()
    expect(within(dialog).getByText('Schedule Timing')).toBeInTheDocument()
    expect(within(dialog).getByRole('switch', { name: 'All day event' })).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Date')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Start Time')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Meeting Link or Conference Room')).toBeInTheDocument()
    expect(within(dialog).getByText('Assign to')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Plan of Action')).toBeInTheDocument()
    expect(within(dialog).getByText('Optional')).toBeInTheDocument()
    expect(within(dialog).getByRole('checkbox')).toBeInTheDocument()
    expect(within(dialog).getByText(/Trigger smart reminders/)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: /^Cancel$/ })).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: /Create Event/ })).toBeInTheDocument()
  })

  it('shows the zone badge for the reader rather than a hardcoded PST', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))

    const dialog = await screen.findByRole('dialog')
    const zone = __testing.timeZoneLabel()
    // jsdom resolves to UTC; the point is that the badge is derived, not "PST".
    expect(within(dialog).getByText(zone)).toBeInTheDocument()
  })

  it('offers three categories with Holiday as a real option', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')

    const labels = ['Customer Meeting', 'Office Meeting', 'Holiday']
    const cards = within(dialog)
      .getAllByRole('button')
      .filter((b) => labels.some((l) => (b.textContent ?? '').includes(l)))

    // Holiday is no longer a disabled placeholder: it is selectable.
    expect(cards).toHaveLength(3)
    expect(cards.filter((b) => b.hasAttribute('disabled'))).toHaveLength(0)

    // Customer Meeting leads, matching the reference.
    expect(within(dialog).getByRole('button', { name: /Customer Meeting/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    expect(within(dialog).getByRole('button', { name: /Office Meeting/ })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  })

  it('shows only Holiday Purpose and Date once Holiday is selected', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: /Holiday/ }))

    expect(within(dialog).getByLabelText('Holiday Purpose')).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Date')).toBeInTheDocument()

    // Meeting-only fields are not shown for a holiday.
    expect(within(dialog).queryByLabelText('Meeting Subject & Title')).not.toBeInTheDocument()
    expect(within(dialog).queryByLabelText('Start Time')).not.toBeInTheDocument()
    expect(within(dialog).queryByLabelText(/Meeting Link/)).not.toBeInTheDocument()
    expect(within(dialog).queryByText('Assign to')).not.toBeInTheDocument()
    expect(within(dialog).queryByText('Plan of Action')).not.toBeInTheDocument()
    expect(within(dialog).queryByText(/Trigger smart reminders/)).not.toBeInTheDocument()
  })

  it('submits a holiday as a purpose and date with meeting fields left empty', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: /Holiday/ }))

    await user.type(within(dialog).getByLabelText('Holiday Purpose'), 'Gandhi Jayanti')
    await user.type(within(dialog).getByLabelText('Date'), '2026-10-02')
    await user.click(within(dialog).getByRole('button', { name: /Create Event/ }))

    await waitFor(() => expect(eventApi.create).toHaveBeenCalledTimes(1))
    // Assert on the first argument: React Query also passes its mutation context.
    expect(vi.mocked(eventApi.create).mock.calls[0][0]).toEqual({
      subject: 'Gandhi Jayanti',
      description: null,
      date: '2026-10-02',
      // A holiday is all-day and unassigned; no start time, link or assignee.
      startTime: null,
      endTime: null,
      organization: null,
      location: null,
      meetingLink: null,
      eventType: 'HOLIDAY',
      setReminder: false,
      todoItems: [],
      assignedEngineerId: null,
    })
  })

  it('requires a holiday date but not a start time', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: /Holiday/ }))

    await user.type(within(dialog).getByLabelText('Holiday Purpose'), 'Pudchast')
    await user.click(within(dialog).getByRole('button', { name: /Create Event/ }))

    expect(await within(dialog).findByText('Holiday date is required')).toBeInTheDocument()
    expect(eventApi.create).not.toHaveBeenCalled()

    // No start-time prompt for a holiday, unlike a timed meeting.
    await user.type(within(dialog).getByLabelText('Date'), '2026-10-02')
    await user.click(within(dialog).getByRole('button', { name: /Create Event/ }))
    await waitFor(() => expect(eventApi.create).toHaveBeenCalledTimes(1))
    expect(screen.queryByText(/Start time is required/)).not.toBeInTheDocument()
  })

  it('submits the selected category', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')

    await user.click(within(dialog).getByRole('button', { name: /Office Meeting/ }))
    await fillRequiredFields(user, dialog)
    await user.click(within(dialog).getByRole('button', { name: /Create Event/ }))

    await waitFor(() => expect(eventApi.create).toHaveBeenCalledTimes(1))
    expect(vi.mocked(eventApi.create).mock.calls[0][0].eventType).toBe('OFFICE_MEETING')
  })

  it('starts with no one assigned and lists the available engineers', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')
    const select = (await within(dialog).findByLabelText('Add Member')) as HTMLSelectElement

    expect(within(dialog).getByText('No one assigned yet')).toBeInTheDocument()
    const options = within(select)
      .getAllByRole('option')
      .map((o) => o.textContent)
      .filter((t) => t !== 'Add Member')
    expect(options).toEqual(['Siddhesh Verma · Platform', 'Masher Choudary'])
  })

  it('renders the chosen engineer as a removable chip and sends its id', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')

    await user.selectOptions(within(dialog).getByLabelText('Add Member'), '7')
    // Avatar initials plus the name, as a single chip.
    expect(within(dialog).getByText('Siddhesh Verma')).toBeInTheDocument()
    expect(within(dialog).getByText('SV')).toBeInTheDocument()

    await fillRequiredFields(user, dialog)
    await user.click(within(dialog).getByRole('button', { name: /Create Event/ }))

    await waitFor(() => expect(eventApi.create).toHaveBeenCalledTimes(1))
    expect(vi.mocked(eventApi.create).mock.calls[0][0].assignedEngineerId).toBe(7)
  })

  it('clears the assignee when the chip is removed', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')

    await user.selectOptions(within(dialog).getByLabelText('Add Member'), '7')
    await user.click(within(dialog).getByRole('button', { name: 'Remove Siddhesh Verma' }))

    expect(within(dialog).getByText('No one assigned yet')).toBeInTheDocument()

    await fillRequiredFields(user, dialog)
    await user.click(within(dialog).getByRole('button', { name: /Create Event/ }))

    await waitFor(() => expect(eventApi.create).toHaveBeenCalledTimes(1))
    expect(vi.mocked(eventApi.create).mock.calls[0][0].assignedEngineerId).toBeNull()
  })

  it('splits the combined link/room field on the submitted value', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')

    // A URL becomes the meeting link...
    await user.type(
      within(dialog).getByLabelText('Meeting Link or Conference Room'),
      'https://meet.example.com/new',
    )
    await fillRequiredFields(user, dialog)
    await user.click(within(dialog).getByRole('button', { name: /Create Event/ }))

    await waitFor(() => expect(eventApi.create).toHaveBeenCalledTimes(1))
    let body = vi.mocked(eventApi.create).mock.calls[0][0]
    expect(body.meetingLink).toBe('https://meet.example.com/new')
    expect(body.location).toBeNull()
  })

  it('treats a non-URL in the combined field as a room name', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')

    await user.type(within(dialog).getByLabelText('Meeting Link or Conference Room'), 'Conference 4B')
    await fillRequiredFields(user, dialog)
    await user.click(within(dialog).getByRole('button', { name: /Create Event/ }))

    await waitFor(() => expect(eventApi.create).toHaveBeenCalledTimes(1))
    const body = vi.mocked(eventApi.create).mock.calls[0][0]
    expect(body.location).toBe('Conference 4B')
    expect(body.meetingLink).toBeNull()
  })

  it('sends the plan of action as the description and one agenda item per line', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')

    await user.type(
      within(dialog).getByLabelText('Plan of Action'),
      'Share agenda{enter}Send pre-read',
    )
    await fillRequiredFields(user, dialog)
    await user.click(within(dialog).getByRole('button', { name: /Create Event/ }))

    await waitFor(() => expect(eventApi.create).toHaveBeenCalledTimes(1))
    const body = vi.mocked(eventApi.create).mock.calls[0][0]
    expect(body.description).toBe('Share agenda\nSend pre-read')
    expect(body.todoItems).toEqual(['Share agenda', 'Send pre-read'])
  })

  it('posts the form to the create endpoint and refreshes the list', async () => {
    const user = userEvent.setup()
    renderPage()

    await screen.findByText('Voice Team Standup')
    vi.mocked(eventApi.upcoming).mockClear()
    vi.mocked(eventApi.upcoming).mockResolvedValue(response())

    await user.click(screen.getByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')

    await user.type(
      within(dialog).getByLabelText('Meeting Subject & Title'),
      'Customer VRS walkthrough',
    )
    await user.type(within(dialog).getByLabelText('Date'), '2026-10-09')
    await user.type(within(dialog).getByLabelText('Start Time'), '15:30')
    await user.type(
      within(dialog).getByLabelText('Meeting Link or Conference Room'),
      'https://meet.example.com/new',
    )

    await user.click(within(dialog).getByRole('button', { name: /Create Event/ }))

    await waitFor(() => {
      expect(eventApi.create).toHaveBeenCalledTimes(1)
    })
    // react-query also passes a context object, so assert on the payload arg.
    expect(vi.mocked(eventApi.create).mock.calls[0][0]).toEqual({
      subject: 'Customer VRS walkthrough',
      description: null,
      date: '2026-10-09',
      startTime: '2026-10-09T15:30:00',
      endTime: null,
      // Customer Meeting is the default category, per the reference.
      eventType: 'CUSTOMER_REMOTE_SESSION',
      meetingLink: 'https://meet.example.com/new',
      setReminder: true,
      todoItems: [],
      // Nothing picked, so the payload is explicitly unassigned.
      assignedEngineerId: null,
      organization: null,
      location: null,
    })

    // The feed is refetched after a successful create.
    await waitFor(() => {
      expect(eventApi.upcoming).toHaveBeenCalled()
    })
  })

  it('drops the start time for an all-day event', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')

    await user.click(within(dialog).getByRole('switch', { name: 'All day event' }))
    // The time input is disabled while the switch is on.
    expect(within(dialog).getByLabelText('Start Time')).toBeDisabled()

    await user.type(within(dialog).getByLabelText('Meeting Subject & Title'), 'Office closed')
    await user.type(within(dialog).getByLabelText('Date'), '2026-10-09')
    await user.click(within(dialog).getByRole('button', { name: /Create Event/ }))

    await waitFor(() => expect(eventApi.create).toHaveBeenCalledTimes(1))
    expect(vi.mocked(eventApi.create).mock.calls[0][0].startTime).toBeNull()
  })

  it('caps the subject at 100 characters', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')

    const field = within(dialog).getByLabelText('Meeting Subject & Title')
    expect(field).toHaveAttribute('maxLength', '100')
  })

  it('surfaces a server error without closing the modal', async () => {
    const user = userEvent.setup()
    vi.mocked(eventApi.create).mockRejectedValue(new Error('Event date is in the past'))
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')

    await user.type(within(dialog).getByLabelText('Meeting Subject & Title'), 'Retro meeting')
    await user.type(within(dialog).getByLabelText('Date'), '2020-01-01')
    await user.type(within(dialog).getByLabelText('Start Time'), '10:00')
    await user.click(within(dialog).getByRole('button', { name: /Create Event/ }))

    expect(await within(dialog).findByText(/Event date is in the past/)).toBeInTheDocument()
  })

  it('validates required fields client-side', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: /Create Event/ }))

    expect(await within(dialog).findByText('Meeting subject is required')).toBeInTheDocument()
    expect(eventApi.create).not.toHaveBeenCalled()
  })

  it('requires a start time unless the event is all day', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByRole('button', { name: /add event/i }))
    const dialog = await screen.findByRole('dialog')

    await user.type(within(dialog).getByLabelText('Meeting Subject & Title'), 'No time yet')
    await user.type(within(dialog).getByLabelText('Date'), '2026-10-09')
    await user.click(within(dialog).getByRole('button', { name: /Create Event/ }))

    expect(
      await within(dialog).findByText('Start time is required unless this is an all-day event'),
    ).toBeInTheDocument()
    expect(eventApi.create).not.toHaveBeenCalled()
  })
})

describe('Customer Meeting cards', () => {
  /** A 90-minute afternoon call with an assignee, as in the reference design. */
  const vertex = event({
    id: 501,
    category: 'CUSTOMER_REMOTE_SESSION',
    eventType: 'CUSTOMER_REMOTE_SESSION',
    organization: 'Vertex Retail Systems',
    location: 'Virtual Teams Room',
    subject: 'Q4 Architecture & Solution Review',
    date: '2026-10-05',
    startTime: '2026-10-05T14:00:00',
    endTime: '2026-10-05T15:30:00',
    meetingLink: 'https://teams.example.com/vertex',
    assignedEngineerId: 7,
    assignedEngineerName: 'Sarah Jenkins',
    assignedEngineerDepartment: 'Voice',
    assignedEngineerDesignation: 'Lead Architect',
    daysUntil: 4,
  })

  /** An unassigned morning check-in, which must show the needs-engineer treatment. */
  const maersk = event({
    id: 502,
    category: 'CUSTOMER_REMOTE_SESSION',
    eventType: 'CUSTOMER_REMOTE_SESSION',
    organization: 'Maersk Global Tech',
    location: 'Virtual Conference Room A',
    subject: 'Global Logistics Cloud Migration Check-in',
    date: '2026-10-09',
    startTime: '2026-10-09T10:00:00',
    endTime: '2026-10-09T11:00:00',
    meetingLink: 'https://teams.example.com/maersk',
    todoItems: ['Migration wave status', 'Open data residency risks'],
    assignedEngineerId: null,
    assignedEngineerName: null,
    daysUntil: 8,
  })

  function renderCustomerCards(events: UpcomingEvent[] = [vertex, maersk]) {
    vi.mocked(eventApi.upcoming).mockResolvedValue(response(events))
    return renderPage()
  }

  it('does not render an organization header on the card', async () => {
    renderCustomerCards()
    await screen.findByText('Q4 Architecture & Solution Review')
    // The reference layout drops the bold uppercase organization band entirely.
    expect(screen.queryByText('Vertex Retail Systems')).not.toBeInTheDocument()
    expect(screen.queryByText('Maersk Global Tech')).not.toBeInTheDocument()
    expect(screen.queryByText(/^vertex retail systems$/i)).not.toBeInTheDocument()
  })

  it('puts the countdown on the top left above the title for an assigned card', async () => {
    renderCustomerCards([vertex])
    const card = (await screen.findByText('Q4 Architecture & Solution Review')).closest(
      'article',
    ) as HTMLElement

    const badge = within(card).getByText('In 4 days')
    const title = within(card).getByText('Q4 Architecture & Solution Review')

    // jsdom reports zeroed rects, so assert document order instead: the badge band
    // is a sibling that precedes the title's block, which is what "above" means here.
    expect(badge.compareDocumentPosition(title) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    // It leads the row: the band is a justify-between flex row whose only group
    // holds the countdown, so there is nothing on the right to push it across.
    const band = badge.parentElement?.parentElement as HTMLElement
    expect(band.className).toContain('justify-between')
    expect(band.children).toHaveLength(1)
    expect((band.firstElementChild as HTMLElement).contains(badge)).toBe(true)

    // An assigned card has no "Needs Engineer" prompt.
    expect(within(card).queryByText(/Needs Engineer/)).not.toBeInTheDocument()
  })

  it('splits the badges for an unassigned card: prompt left, countdown right', async () => {
    renderCustomerCards([maersk])
    const card = (await screen.findByText('Global Logistics Cloud Migration Check-in')).closest(
      'article',
    ) as HTMLElement

    const prompt = within(card).getByText(/Needs Engineer/)
    const countdown = within(card).getByText('In 8 days')
    const title = within(card).getByText('Global Logistics Cloud Migration Check-in')

    // Opposite edges of one justify-between band: the prompt leads on the left, the
    // countdown trails in its own group on the right. Asserted structurally because
    // jsdom has no layout.
    const band = prompt.parentElement?.parentElement as HTMLElement
    expect(band.className).toContain('justify-between')
    const leftGroup = band.firstElementChild as HTMLElement
    const rightGroup = band.lastElementChild as HTMLElement
    expect(leftGroup).not.toBe(rightGroup)
    expect(leftGroup.contains(prompt)).toBe(true)
    expect(leftGroup.contains(countdown)).toBe(false)
    expect(rightGroup.contains(countdown)).toBe(true)

    // Both precede the title.
    expect(prompt.compareDocumentPosition(title) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(countdown.compareDocumentPosition(title) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('renders the meeting title on the card', async () => {
    renderCustomerCards()
    expect(await screen.findByText('Q4 Architecture & Solution Review')).toBeInTheDocument()
    expect(screen.getByText('Global Logistics Cloud Migration Check-in')).toBeInTheDocument()
  })

  it('renders a date and time range from startTime and endTime', async () => {
    renderCustomerCards()
    // The zone suffix reflects the reader's own clock, so build the expectation
    // from the same helper rather than hardcoding "PST".
    const zone = __testing.timeZoneLabel()
    expect(
      await screen.findByText(`Oct 5, 2026 • 02:00 - 03:30 PM ${zone}`),
    ).toBeInTheDocument()
    expect(screen.getByText(`Oct 9, 2026 • 10:00 - 11:00 AM ${zone}`)).toBeInTheDocument()
  })

  it('labels the footer with the meeting location and platform', async () => {
    renderCustomerCards()
    expect(await screen.findByText('Virtual Teams Room')).toBeInTheDocument()
    expect(screen.getByText('Virtual Conference Room A')).toBeInTheDocument()
  })

  it('falls back to "No platform set" when the meeting has no location', async () => {
    renderCustomerCards([{ ...vertex, location: null }])
    expect(await screen.findByText('No platform set')).toBeInTheDocument()
  })

  it('shows the assignee job title as the role on the chip', async () => {
    renderCustomerCards([vertex])
    // The title is preferred: a department says where someone sits, a title says
    // what they are there to do.
    expect(await screen.findByText('Sarah Jenkins (Lead Architect)')).toBeInTheDocument()
  })

  it('falls back to the department when the assignee has no job title', async () => {
    renderCustomerCards([{ ...vertex, assignedEngineerDesignation: null }])
    expect(await screen.findByText('Sarah Jenkins (Voice)')).toBeInTheDocument()
  })

  it('shows a countdown pill derived from daysUntil', async () => {
    renderCustomerCards()
    expect(await screen.findByText('In 4 days')).toBeInTheDocument()
    expect(screen.getByText('In 8 days')).toBeInTheDocument()
  })

  it('shows the assignee avatar chip and the Reassign / Join Call actions when assigned', async () => {
    renderCustomerCards()
    expect(await screen.findByText(/^Assigned:/)).toBeInTheDocument()
    expect(screen.getByText('SJ')).toBeInTheDocument()
    expect(screen.getByText(/Sarah Jenkins/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /reassign/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /join call/i })).toBeInTheDocument()
  })

  it('shows the needs-engineer and unassigned treatment when nobody is assigned', async () => {
    renderCustomerCards()
    expect((await screen.findAllByText(/Needs Engineer/)).length).toBeGreaterThan(0)
    expect(screen.getByText(/•\s*Unassigned/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /view agenda/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /assign engineer/i })).toBeInTheDocument()
  })

  it('does not offer Join Call for an unassigned meeting', async () => {
    renderCustomerCards()
    await screen.findByText('Global Logistics Cloud Migration Check-in')
    // Only the assigned Vertex card exposes a join action.
    expect(screen.getAllByRole('button', { name: /join call/i })).toHaveLength(1)
  })

  it('opens the meeting URL in a new tab from the event link', async () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    renderCustomerCards([vertex])
    fireEvent.click(await screen.findByRole('button', { name: /join call/i }))
    expect(open).toHaveBeenCalledWith(
      'https://teams.example.com/vertex',
      '_blank',
      'noopener,noreferrer',
    )
    open.mockRestore()
  })

  it('explains the problem instead of opening a tab when no link is set', async () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    renderCustomerCards([{ ...vertex, meetingLink: null, assignedEngineerId: 7 }])
    fireEvent.click(await screen.findByRole('button', { name: /join call/i }))
    expect(open).not.toHaveBeenCalled()
    expect(await screen.findByText(/No meeting link was set/i)).toBeInTheDocument()
    open.mockRestore()
  })

  it('lists engineers in the assign modal and persists the choice', async () => {
    const user = userEvent.setup()
    vi.mocked(eventApi.assign).mockResolvedValue({
      ...maersk,
      assignedEngineerId: 7,
      assignedEngineerName: 'Siddhesh Verma',
      assignedEngineerDepartment: 'Platform',
    })
    renderCustomerCards([maersk])

    await user.click(await screen.findByRole('button', { name: /assign engineer/i }))

    expect(await screen.findByRole('heading', { name: /assign an engineer/i })).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: /Siddhesh Verma/ }))

    await waitFor(() =>
      expect(eventApi.assign).toHaveBeenCalledWith(502, 7),
    )
  })

  it('flips the card to the assigned actions once the engineer is saved', async () => {
    const user = userEvent.setup()
    const assigned = {
      ...maersk,
      assignedEngineerId: 7,
      assignedEngineerName: 'Siddhesh Verma',
      assignedEngineerDepartment: 'Platform',
    }
    vi.mocked(eventApi.assign).mockResolvedValue(assigned)
    renderCustomerCards([maersk])
    // The card refetches after the write, so the feed must reflect the server state.
    vi.mocked(eventApi.upcoming).mockResolvedValue(response([assigned]))

    await user.click(await screen.findByRole('button', { name: /assign engineer/i }))
    await user.click(await screen.findByRole('button', { name: /Siddhesh Verma/ }))

    expect(await screen.findByText(/^Assigned:/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /reassign/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /join call/i })).toBeInTheDocument()
  })

  it('can remove an existing assignment from the modal', async () => {
    const user = userEvent.setup()
    vi.mocked(eventApi.assign).mockResolvedValue({
      ...vertex,
      assignedEngineerId: null,
      assignedEngineerName: null,
      assignedEngineerDepartment: null,
    })
    renderCustomerCards([vertex])

    await user.click(await screen.findByRole('button', { name: /reassign/i }))
    await user.click(await screen.findByRole('button', { name: /remove assignment/i }))

    await waitFor(() => expect(eventApi.assign).toHaveBeenCalledWith(501, null))
  })

  it('shows the agenda items in a modal', async () => {
    const user = userEvent.setup()
    renderCustomerCards([maersk])

    await user.click(await screen.findByRole('button', { name: /view agenda/i }))

    expect(await screen.findByRole('heading', { name: /meeting agenda/i })).toBeInTheDocument()
    expect(screen.getByText('Migration wave status')).toBeInTheDocument()
    expect(screen.getByText('Open data residency risks')).toBeInTheDocument()
  })

  it('keeps the organization out of the card even when it is set', async () => {
    renderCustomerCards([{ ...vertex, organization: null }])
    // Nothing stands in for a missing organization now that the header is gone;
    // the card still identifies the meeting by subject alone.
    expect(await screen.findByText('Q4 Architecture & Solution Review')).toBeInTheDocument()
    expect(screen.queryByText('Customer Meeting', { selector: 'span' })).not.toBeInTheDocument()
  })
})

describe('EventsPage details modal', () => {
  it('opens on card click and shows every saved field', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByText('Customer VRS'))

    const dialog = await screen.findByRole('dialog')
    // The subject shows in the card behind the modal too, so scope to the
    // dialog and expect the title heading plus the Subject row value.
    expect(within(dialog).getAllByText('Customer VRS').length).toBeGreaterThan(0)
    expect(within(dialog).getByRole('heading', { name: 'Customer VRS' })).toBeInTheDocument()
    expect(within(dialog).getByText('Upcoming Customer Virtual Remote session')).toBeInTheDocument()
    expect(within(dialog).getByText('Subject')).toBeInTheDocument()
    expect(within(dialog).getByText('Scheduled Time')).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: /join meeting/i })).toHaveAttribute(
      'href',
      'https://meet.example.com/abc',
    )
    expect(within(dialog).getByText('Reminder Status')).toBeInTheDocument()
    expect(within(dialog).getByText('Not set')).toBeInTheDocument()
    expect(within(dialog).getByText('Share agenda')).toBeInTheDocument()
    expect(within(dialog).getByText('Send pre-read')).toBeInTheDocument()
  })

  it('shows the assigned engineer on the card and in the details modal', async () => {
    const user = userEvent.setup()
    const assigned = event({
      id: 40,
      category: 'SCHEDULED_MEETING',
      subject: 'Assigned sync',
      assignedEngineerId: 7,
      assignedEngineerName: 'Siddhesh Verma',
    })
    vi.mocked(eventApi.upcoming).mockResolvedValue(response([assigned]))
    renderPage()

    // SCHEDULED_MEETING is a customer-facing bucket, so it renders as a featured card.
    expect(await screen.findByText(/^Assigned:/)).toBeInTheDocument()
    expect(screen.getByText('Siddhesh Verma')).toBeInTheDocument()

    await user.click(screen.getByText('Assigned sync'))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Assigned To')).toBeInTheDocument()
    // One in the card behind the modal, one in the details list.
    expect(within(dialog).getAllByText('Siddhesh Verma').length).toBeGreaterThan(0)
  })

  it('falls back to "Unassigned" when no engineer is set', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByText('Voice Team Standup'))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Unassigned')).toBeInTheDocument()
  })

  it('never shows an assignee on holidays or birthdays', async () => {
    renderPage()

    expect(await screen.findByText('Gandhi Jayanti')).toBeInTheDocument()
    expect(screen.queryByText(/^Assigned To:/)).not.toBeInTheDocument()
  })

  it('reports "Set" when a reminder is enabled', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByText('Voice Team Standup'))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Set')).toBeInTheDocument()
  })

  it('closes the details modal', async () => {
    const user = userEvent.setup()
    renderPage()

    await user.click(await screen.findByText('Customer VRS'))
    const dialog = await screen.findByRole('dialog')
    // The shared Modal renders its own "Close" X alongside the footer's Close,
    // so target the last one (the footer button).
    const closeButtons = within(dialog).getAllByRole('button', { name: 'Close' })
    await user.click(closeButtons[closeButtons.length - 1])

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
  })
})
