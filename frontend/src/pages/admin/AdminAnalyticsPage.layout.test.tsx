import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AdminAnalyticsPage } from '@/pages/admin/AdminAnalyticsPage'
import { adminApi } from '@/api'
import type { EmployeeSummary, EmployeeStatsResponse } from '@/types'

vi.mock('@/api', () => ({
  adminApi: {
    analyticsMeta: vi.fn(),
    analyticsEmployees: vi.fn(),
    analyticsEmployeeDetail: vi.fn(),
    analyticsExport: vi.fn(),
  },
}))

vi.mock('react-router-dom', () => ({
  useNavigate: () => vi.fn(),
  useSearchParams: () => [new URLSearchParams('month=2026-10'), vi.fn()],
}))

/**
 * Column geometry cannot be measured in jsdom (there is no layout engine), so these
 * tests assert the invariants that make the layout correct instead: one shared width
 * source, matching header/body/colgroup counts, sticky offsets equal to the cumulative
 * width of the columns to their left, and opaque backgrounds on every sticky cell.
 * Each of those is a precondition for "no cell overlaps another at any viewport width".
 */

const LONG_NAME = 'Ramakrishnan Venkataraghavan Iyer Subramanian'

function emp(over: Partial<EmployeeSummary> = {}): EmployeeSummary {
  return {
    sNo: 1,
    employeeId: '60179401',
    employeeName: 'Abhilash Yadav',
    location: 'Bangalore',
    shift: 'General Shift',
    weekOff: 'Sunday',
    wfo: 1,
    wfh: 2,
    wo: 3,
    pl: 0,
    co: 0,
    sl: 0,
    hd: 0,
    wkWrk: 22,
    shrinkage: 23.1,
    atr: '23.1',
    employmentStatus: 'ACTIVE',
    totalWorkingDays: 130,
    totalLeaves: 2,
    attendancePercentage: 96.9,
    attendanceRecorded: 26,
    inCurrentRoster: true,
    ...over,
  }
}

const response = {
  employees: [
    emp({ sNo: 1 }),
    emp({
      sNo: 2,
      employeeId: 'EMP0002',
      employeeName: LONG_NAME,
      location: 'Hyderabad Long Location Name',
      shift: 'Night Shift (US Hours) 22:00 - 06:00',
      inCurrentRoster: false,
    }),
  ],
  totalElements: 2,
  page: 0,
  size: 25,
  totalPages: 1,
  overallSummary: null,
} as unknown as EmployeeStatsResponse

const tableOf = () => screen.getByRole('table')
const headers = () => Array.from(tableOf().querySelectorAll('thead th'))
const cols = () => Array.from(tableOf().querySelectorAll('colgroup col'))
const firstRowCells = () =>
  Array.from(tableOf().querySelectorAll('tbody tr')[0].querySelectorAll('td'))

const px = (el: Element) => Number.parseFloat((el as HTMLElement).style.width)

describe('AdminAnalyticsPage table layout', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(adminApi.analyticsMeta).mockResolvedValue({
      months: ['2026-10'],
      teams: [],
      locations: ['Bangalore'],
      shifts: [],
      statuses: [],
    } as never)
    vi.mocked(adminApi.analyticsEmployees).mockResolvedValue(response)
    vi.mocked(adminApi.analyticsEmployeeDetail).mockResolvedValue({
      detail: {
        ...emp(),
        employeeEmail: 'a@b.com',
        monthlyBreakdown: [
          { month: '2026-08', wfo: 1, wfh: 2, wo: 3, pl: 0, co: 0, sl: 0, hd: 0, wkWrk: 21, shrinkage: 22.5, totalLeaves: 1 },
          { month: '2026-09', wfo: 0, wfh: 1, wo: 2, pl: 0, co: 0, sl: 0, hd: 0, wkWrk: 22, shrinkage: 20.0, totalLeaves: 1 },
        ],
        attendanceHistory: [
          { date: '2026-08-03', day: 'Mon', status: 'WFH', description: 'Work From Home' },
          { date: '2026-08-04', day: 'Tue', status: 'WO', description: 'Weekly Off' },
        ],
      },
    } as never)
  })

  it('renders the table', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(headers().length).toBeGreaterThan(0))
  })

  it('declares every column once in a shared colgroup', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(headers().length).toBeGreaterThan(0))

    const c = cols()
    expect(c.length).toBe(headers().length)
    expect(c.every((el) => Number.isFinite(px(el)) && px(el) > 0)).toBe(true)
  })

  it('gives header, body and colgroup identical column counts', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(headers().length).toBeGreaterThan(0))

    expect(firstRowCells().length).toBe(headers().length)
    tableOf().querySelectorAll('tbody tr').forEach((tr) => {
      expect(tr.querySelectorAll('td').length).toBe(headers().length)
    })
  })

  it('sets table min-width to the exact sum of the column widths', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(headers().length).toBeGreaterThan(0))

    const sum = cols().reduce((n, el) => n + px(el), 0)
    expect((tableOf() as HTMLElement).style.minWidth).toBe(`${sum}px`)
  })

  it('does not squeeze the table into its container', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(headers().length).toBeGreaterThan(0))

    const table = tableOf()
    // `w-full` would force width:100% and defeat both fixed layout and h-scroll
    expect(table.className).not.toContain('w-full')
    expect(table.className).toContain('table-fixed')
    const container = table.parentElement as HTMLElement
    expect(container.className).toContain('overflow-x-auto')
    // declared width must exceed a typical viewport, otherwise nothing ever scrolls
    expect(cols().reduce((n, el) => n + px(el), 0)).toBeGreaterThan(1024)
  })

  it('leaves no second width source on the cells', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(headers().length).toBeGreaterThan(0))

    const cells = [...headers(), ...firstRowCells()]
    // Only flag layout-affecting width classes (w-full, w-auto, w-screen, w-min, w-max, w-fit, or w-XX where XX >= 12)
    // Icon sizes like w-3, w-4, w-5, w-6, w-8, w-10 are allowed
    const hasLayoutWidth = cells.some((el) => {
      const cls = el.className
      return /\bw-full\b/.test(cls) ||
        /\bw-auto\b/.test(cls) ||
        /\bw-screen\b/.test(cls) ||
        /\bw-min\b/.test(cls) ||
        /\bw-max\b/.test(cls) ||
        /\bw-fit\b/.test(cls) ||
        /\bw-\d{2,}\b/.test(cls)
    })
    expect(hasLayoutWidth).toBe(false)
  })

  it('pins each sticky column to the cumulative width of the columns before it', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(headers().length).toBeGreaterThan(0))

    const widths = cols().map(px)
    let expectedLeft = 0
    headers().forEach((th, i) => {
      if (!th.className.includes('sticky') || th.className.includes('right-0')) return
      expect((th as HTMLElement).style.left).toBe(`${expectedLeft}px`)
      expectedLeft += widths[i]
    })
    // body cells must use the same offsets as their headers
    firstRowCells().forEach((td, i) => {
      const th = headers()[i]
      if (!th.className.includes('sticky') || th.className.includes('right-0')) return
      expect((td as HTMLElement).style.left).toBe((th as HTMLElement).style.left)
      expect(td.className).toContain('sticky')
    })
  })


  it('gives every sticky cell an opaque background so scrolled content cannot bleed through', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(headers().length).toBeGreaterThan(0))

    const stickyCells = [...headers(), ...firstRowCells()].filter(
      (el) => el.className.includes('sticky'),
    )
    expect(stickyCells.length).toBeGreaterThan(0)
    for (const cell of stickyCells) {
      const cls = cell.className
      const opaque =
        /bg-white\b/.test(cls) ||
        /bg-surface-50\b/.test(cls) ||
        /!bg-white\b/.test(cls) ||
        /!bg-surface-800\/50\b/.test(cls) ||
        /bg-inherit\b/.test(cls) ||
        /dark:bg-surface-950\b/.test(cls) ||
        /dark:bg-surface-800\/50\b/.test(cls) ||
        /dark:bg-surface-900\b/.test(cls) ||
        /dark:!bg-surface-800\/50\b/.test(cls)
      expect(opaque, `transparent sticky cell: ${cls}`).toBe(true)
    }
  })

  it('keeps the right-pinned Recorded column sticky in both header and body', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(headers().length).toBeGreaterThan(0))

    const idx = headers().findIndex((th) => th.textContent?.trim() === 'Recorded')
    expect(idx).toBeGreaterThan(-1)
    expect(headers()[idx].className).toContain('right-0')
    expect(firstRowCells()[idx].className).toContain('right-0')
  })

  it('confines long names, locations and shifts to their own cell with a tooltip', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(headers().length).toBeGreaterThan(0))

    const labels = headers().map((th) => th.textContent?.trim())
    const nameIdx = labels.findIndex((l) => l?.startsWith('Name'))
    const locIdx = labels.findIndex((l) => l?.startsWith('Location'))
    const shiftIdx = labels.findIndex((l) => l?.startsWith('Shift'))
    expect([nameIdx, locIdx, shiftIdx]).not.toContain(-1)

    const cells = firstRowCells()
    // Name: truncate is on the inner span inside the td
    expect(cells[nameIdx].querySelector('span[title]')).not.toBeNull()
    expect(cells[nameIdx].querySelector('span[title]')?.className).toContain('truncate')
    // Location & Shift: truncate is on the td itself, title attribute is on the td
    for (const i of [locIdx, shiftIdx]) {
      expect(cells[i].className).toContain('truncate')
      expect(cells[i].getAttribute('title')).not.toBeNull()
    }

    // the full name is preserved in the DOM even when visually truncated
    const row2 = tableOf().querySelectorAll('tbody tr')[1]
    expect(row2.querySelectorAll('td')[nameIdx].textContent).toContain(LONG_NAME)
  })

  it('gives the detail-modal tables the same width-stability treatment', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(headers().length).toBeGreaterThan(0))

    const mainTable = tableOf()
    await userEvent.click(screen.getByText('Abhilash Yadav'))
    await waitFor(() => expect(screen.getByText('Monthly Breakdown')).toBeTruthy())

    const modalTables = Array.from(document.querySelectorAll('table')).filter(
      (t) => t !== mainTable,
    )
    expect(modalTables.length).toBeGreaterThan(0)

    for (const t of modalTables) {
      const c = Array.from(t.querySelectorAll('colgroup col'))
      const h = Array.from(t.querySelectorAll('thead th'))
      const rows = Array.from(t.querySelectorAll('tbody tr'))
      expect((t as HTMLElement).className).not.toContain('w-full')
      expect((t as HTMLElement).className).toContain('table-fixed')
      expect((t as HTMLElement).style.minWidth).toBe(`${c.reduce((n, el) => n + px(el), 0)}px`)
      expect(c.length).toBe(h.length)
      rows.forEach((r) => expect(r.querySelectorAll('td').length).toBe(h.length))
    }
  })



  it('does not render the email column', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(headers().length).toBeGreaterThan(0))
    expect(headers().some((th) => /email/i.test(th.textContent ?? ''))).toBe(false)
  })
})