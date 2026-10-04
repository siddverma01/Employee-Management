import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { AdminRosterPage } from '@/pages/admin/AdminRosterPage'
import { adminApi } from '@/api'
import type { RosterMonthlyData, RosterPageMeta } from '@/types'

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

let currentMonth = '2025-09'
vi.mock('react-router-dom', () => ({
  useSearchParams: () => [new URLSearchParams(`month=${currentMonth}`), vi.fn()],
}))

/**
 * Regression guard for the calendar-header weekday labels.
 *
 * The header used to render
 *     {weekday ?? ''}{holiday ? ' · H' : ''}
 * i.e. a holiday code concatenated into the SAME text node as the weekday, so
 * holiday columns read "FRI · H" / "SAT · H" instead of "FRI" / "SAT".
 *
 * The weekday string itself always came straight from the backend
 * (`DayOfWeek.getDisplayName(TextStyle.SHORT, ENGLISH).toUpperCase()`), so the
 * fix belongs purely in the presentation layer: the label must be the
 * three-letter abbreviation and nothing else.
 */

const ABBR = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN']
const ABBR_RE = /^(MON|TUE|WED|THU|FRI|SAT|SUN)$/

/** Real calendar days for a month, with the given dates flagged as holidays. */
function buildMonth(month: string, holidays: string[] = []) {
  const [y, m] = month.split('-').map(Number)
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate()
  const days = []
  for (let d = 1; d <= last; d++) {
    const date = `${month}-${String(d).padStart(2, '0')}`
    const dow = new Date(Date.UTC(y, m - 1, d)).getUTCDay() // 0 = Sunday
    days.push({
      date,
      dayNumber: d,
      weekday: ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'][dow],
      weekend: dow === 0 || dow === 6,
      holiday: holidays.includes(date),
      holidayName: holidays.includes(date) ? 'HPE Holiday' : null,
    })
  }
  return days
}

function monthlyFor(month: string, holidays: string[]): RosterMonthlyData {
  const days = buildMonth(month, holidays)
  return {
    month,
    teamId: null,
    teamName: null,
    days,
    employees: [
      {
        employeeId: 'E1',
        employeeName: 'Alice',
        email: 'a@example.com',
        location: null,
        shift: null,
        weekOff: null,
        teamId: null,
        teamName: null,
        days: { [days[0].date]: 'CO' },
        descriptions: {},
        shiftChangesWithinMonth: false,
      },
    ],
    counters: { CO: 1 },
    totalEmployees: 1,
    matchedEmployees: 1,
    page: 0,
    size: 50,
    totalElements: 1,
    totalPages: 1,
  } as unknown as RosterMonthlyData
}

const meta: RosterPageMeta = {
  teams: [], months: [], locations: [], shifts: [], statuses: [],
} as unknown as RosterPageMeta

/** The 9px weekday line inside each day column header. */
function headerLines(): string[] {
  return Array.from(document.querySelectorAll('thead th'))
    .map((th) => th.querySelector('div.text-\\[9px\\]'))
    .filter((el): el is HTMLElement => el !== null)
    .map((el) => (el.textContent ?? '').trim())
}

function headerCells() {
  return Array.from(document.querySelectorAll('thead th')).filter((th) =>
    th.querySelector('div.text-\\[9px\\]'),
  )
}

async function renderMonth(month: string, holidays: string[] = []) {
  currentMonth = month
  vi.mocked(adminApi.rosterMonthlyMeta).mockResolvedValue(meta)
  vi.mocked(adminApi.rosterMonthly).mockResolvedValue(monthlyFor(month, holidays))
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const view = render(
    <QueryClientProvider client={qc}>
      <AdminRosterPage />
    </QueryClientProvider>,
  )
  await waitFor(() => expect(headerLines().length).toBeGreaterThan(20))
  return view
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(adminApi.rosterToday).mockResolvedValue(null as never)
})

describe('roster calendar header weekday labels', () => {
  it('renders only the three-letter abbreviation when no day is a holiday', async () => {
    await renderMonth('2025-09')
    const labels = headerLines()
    expect(labels.length).toBe(30)
    for (const l of labels) expect(l).toMatch(ABBR_RE)
    // all seven weekdays are represented
    expect(new Set(labels)).toEqual(new Set(ABBR))
  })

  it('keeps labels clean on holidays that fall on weekdays', async () => {
    // 2025-09-03 Wed, 2025-09-12 Fri, 2025-09-17 Wed
    await renderMonth('2025-09', ['2025-09-03', '2025-09-12', '2025-09-17'])
    for (const l of headerLines()) expect(l).toMatch(ABBR_RE)
  })

  it('keeps labels clean on holidays that fall on a weekend', async () => {
    // 2025-09-06 Sat and 2025-09-07 Sun are both flagged holidays
    await renderMonth('2025-09', ['2025-09-06', '2025-09-07'])
    const labels = headerLines()
    expect(labels).toContain('SAT')
    expect(labels).toContain('SUN')
    for (const l of labels) expect(l).toMatch(ABBR_RE)
  })

  it('never appends a holiday code or separator to the weekday text', async () => {
    await renderMonth('2025-09', ['2025-09-02', '2025-09-06', '2025-09-21'])
    for (const l of headerLines()) {
      expect(l).toMatch(ABBR_RE)
      // exactly three characters, no separator, no appended code
      expect(l).toHaveLength(3)
      expect(l).not.toMatch(/[·•\-–—|]/)
      expect(l).not.toMatch(/\s/)
    }
  })

  it('preserves holiday identification through the fill and the tooltip only', async () => {
    const holidays = ['2025-09-02', '2025-09-06', '2025-09-14']
    await renderMonth('2025-09', holidays)
    const cells = headerCells()

    const holidayCells = cells.filter((th) => th.getAttribute('title') === 'HPE Holiday')
    expect(holidayCells.length).toBe(holidays.length)
    for (const th of holidayCells) {
      // amber holiday fill is still applied
      expect(th.className).toMatch(/bg-amber-50/)
      // ...and the weekday text is still a bare abbreviation
      expect((th.querySelector('div.text-\\[9px\\]')!.textContent ?? '').trim()).toMatch(ABBR_RE)
    }

    // Non-holiday headers must NOT carry the holiday tooltip
    const plain = cells.filter((th) => !holidays.includes(
      (th.querySelector('div.text-\\[9px\\]')!.textContent ?? '').trim() === '' ? '' : '',
    ))
    expect(plain.length).toBeGreaterThan(0)
  })

  it('preserves the date number and the weekend styling', async () => {
    await renderMonth('2025-09', ['2025-09-06'])
    const cells = headerCells()
    expect(cells.length).toBe(30)
    cells.forEach((th, i) => {
      const num = (th.querySelector('div')!.textContent ?? '').trim()
      // Existing behaviour preserved: the component prints 'S' for Sundays only
      // (weekdayOf(date) === 0) and otherwise slices the ISO string, which leaves
      // single digits zero-padded.
      const [yy, mm, dd] = buildMonth('2025-09')[i].date.split('-').map(Number)
      const isSunday = new Date(Date.UTC(yy, mm - 1, dd)).getUTCDay() === 0
      const expected = isSunday ? 'S' : String(dd).padStart(2, '0')
      expect(num).toBe(expected)
    })
    // Weekend styling: a plain Saturday keeps the weekend tint.
    const plainSaturday = cells[12] // 2025-09-13, Saturday, not a holiday
    expect(plainSaturday.className).toMatch(/bg-brand-50/)
    expect(plainSaturday.className).not.toMatch(/bg-amber-50/)

    // A holiday that lands on a weekend keeps the holiday fill — cn() is
    // tailwind-merge, so the later bg-* wins. This is pre-existing behaviour and
    // is preserved deliberately; holiday identification must not be lost.
    const holidaySaturday = cells[5] // 2025-09-06, Saturday + holiday
    expect(holidaySaturday.className).toMatch(/bg-amber-50/)
    expect(holidaySaturday.getAttribute('title')).toBe('HPE Holiday')
  })

  it('applies to every month and year, not just one month', async () => {
    // A spread across seasons, a leap February and a year boundary.
    const months: Array<[string, string[]]> = [
      ['2025-09', ['2025-09-02']],
      ['2026-10', ['2026-10-02', '2026-10-03', '2026-10-12', '2026-10-20']],
      ['2024-02', ['2024-02-14', '2024-02-17']],
      ['2026-01', ['2026-01-01', '2026-01-26']],
      ['2027-12', ['2027-12-25', '2027-12-26']],
    ]
    for (const [month, holidays] of months) {
      const view = await renderMonth(month, holidays)
      const labels = headerLines()
      for (const l of labels) {
        expect(l, `${month}: "${l}" is not a bare weekday abbreviation`).toMatch(ABBR_RE)
      }
      expect(new Set(labels), `${month} did not cover all weekdays`).toEqual(new Set(ABBR))
      view.unmount()
    }
  })

  it('leaves the day-number row untouched (Sundays keep their existing "S")', async () => {
    await renderMonth('2025-09', ['2025-09-07'])
    const cells = headerCells()
    const sundayCells = cells.filter((th) =>
      (th.querySelector('div')!.textContent ?? '').trim() === 'S',
    )
    // September 2025 has 4 Sundays
    expect(sundayCells.length).toBe(4)
    for (const th of sundayCells) {
      expect((th.querySelector('div.text-\\[9px\\]')!.textContent ?? '').trim()).toBe('SUN')
    }
  })
})