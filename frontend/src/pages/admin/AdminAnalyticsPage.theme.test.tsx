import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
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

const read = (path: string) =>
  readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8')

const page = read('./AdminAnalyticsPage.tsx')
const themeCss = read('../../styles/attendance-analytics.css')
const tokens = read('../../styles/tokens.css')
const tailwindConfig = read('../../../tailwind.config.js')

const lightBlock = tokens.slice(0, tokens.indexOf('.dark {'))
const darkBlock = tokens.slice(tokens.indexOf('.dark {'))

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
  employees: [emp({ sNo: 1 }), emp({ sNo: 2, employeeId: 'EMP0002', employeeName: 'Second Person' })],
  totalElements: 2,
  page: 0,
  size: 25,
  totalPages: 1,
  overallSummary: emp({ employeeName: 'Overall Summary' }),
} as unknown as EmployeeStatsResponse

const root = () => document.querySelector('.attendance-analytics') as HTMLElement
const table = () => document.querySelector('table.aa-table') as HTMLTableElement
const cells = () => Array.from(table().querySelectorAll('th, td'))

/* ------------------------------------------------------------------ *
 * 1. Stylesheet architecture
 * ------------------------------------------------------------------ */
describe('analytics theme layer architecture', () => {
  it('scopes every rule to the dark theme inside the analytics page', () => {
    // Strip comments, then every remaining selector must start with the
    // dark gate. A single ungated selector could repaint another page.
    const css = themeCss.replace(/\/\*[\s\S]*?\*\//g, '')
    const selectors = css
      .split('}')
      .map((chunk) => chunk.split('{')[0]?.trim())
      .filter((s): s is string => Boolean(s) && s.length > 0)

    expect(selectors.length).toBeGreaterThan(10)
    for (const selector of selectors) {
      for (const part of selector.split(',')) {
        expect(
          part.trim().startsWith('.dark .attendance-analytics'),
          `unscoped selector: ${part.trim()}`,
        ).toBe(true)
      }
    }
  })

  it('contains no light-mode rule that could alter the approved light design', () => {
    const css = themeCss.replace(/\/\*[\s\S]*?\*\//g, '')
    // Every mention of the root class must be gated by `.dark `, otherwise it
    // would restyle the approved light design.
    const ungated = [...css.matchAll(/\.attendance-analytics/g)].filter((m) => {
      const before = css.slice(Math.max(0, m.index - 6), m.index)
      return !before.endsWith('.dark ')
    })
    expect(ungated.map((m) => css.slice(m.index - 30, m.index + 25))).toEqual([])
    expect(css).not.toContain(':root')
  })

  it('uses no !important and no global element selectors', () => {
    const css = themeCss.replace(/\/\*[\s\S]*?\*\//g, '')
    expect(css).not.toContain('!important')
    // No selector may *begin* with an element or universal selector, which is
    // what would let the layer escape the page and repaint other screens.
    const selectors = css.split('}').map((c) => c.split('{')[0]?.trim() ?? '')
      .filter(Boolean)
    for (const sel of selectors) {
      for (const part of sel.split(',')) {
        expect(part.trim(), `selector not rooted in the analytics page: ${part}`).toMatch(
          /^\.dark \.attendance-analytics/,
        )
      }
    }
    expect(css).not.toMatch(/,\s*(html|body|\*)\s*[,{]/)
  })

  it('drives every surface from a token rather than a literal colour', () => {
    const css = themeCss.replace(/\/\*[\s\S]*?\*\//g, '')
    expect(css).not.toMatch(/#[0-9a-fA-F]{3,8}\b/)
    expect(css).not.toMatch(/rgba?\(\s*[\d.]/)
    const tokenRefs = css.match(/var\(--hpe-analytics-[a-z-]+\)/g) ?? []
    expect(tokenRefs.length).toBeGreaterThan(10)
  })

  it('makes every cell inherit its row surface so sticky and scrolling cells cannot diverge', () => {
    const css = themeCss.replace(/\/\*[\s\S]*?\*\//g, '')
    const cellRule = css.match(/\.aa-table th,\s*\.dark \.attendance-analytics \.aa-table td\s*\{([^}]*)\}/)
    expect(cellRule).not.toBeNull()
    expect(cellRule![1]).toContain('background-color: inherit')
  })

  it('defines normal, alternate, hover and selected row surfaces', () => {
    const css = themeCss.replace(/\/\*[\s\S]*?\*\//g, '')
    expect(css).toMatch(/tbody tr\s*\{[^}]*var\(--hpe-analytics-row\)/)
    expect(css).toMatch(/tbody tr:nth-child\(even\)\s*\{[^}]*var\(--hpe-analytics-row-alt\)/)
    expect(css).toMatch(/tbody tr:hover\s*\{[^}]*var\(--hpe-analytics-hover\)/)
    expect(css).toMatch(/aa-row-selected[^{]*\{[^}]*var\(--hpe-analytics-selected\)/)
  })

  it('does not recolour status columns, so status meaning is preserved', () => {
    const css = themeCss.replace(/\/\*[\s\S]*?\*\//g, '')
    // Setting `color` on a generic `td` would override every `dark:text-*-400`
    // status utility (scoped rules outrank utilities on specificity).
    const genericCellRule = css.match(/\.aa-table th,\s*\.dark \.attendance-analytics \.aa-table td\s*\{([^}]*)\}/)
    // a standalone `color:` declaration (not `background-color:`/`border-color:`)
    expect(genericCellRule![1]).not.toMatch(/(^|[\s;{])color\s*:/)
    // The only cell colour rule is scoped to the name column alone.
    const nameRule = css.match(/\.aa-table td\.aa-col-name\s*\{([^}]*)\}/)
    expect(nameRule).not.toBeNull()
  })
})

/* ------------------------------------------------------------------ *
 * 2. Token definitions
 * ------------------------------------------------------------------ */
describe('analytics tokens', () => {
  const DARK_SPEC: Record<string, string> = {
    '--hpe-analytics-bg': '21 23 25',
    '--hpe-analytics-surface': '28 31 35',
    '--hpe-analytics-row': '29 32 37',
    '--hpe-analytics-row-alt': '36 40 46',
    '--hpe-analytics-header': '32 35 41',
    '--hpe-analytics-tablehead': '39 43 49',
    '--hpe-analytics-border': '54 59 67',
    '--hpe-analytics-hover': '48 54 64',
    '--hpe-analytics-selected': '52 59 69',
    '--hpe-analytics-heading': '243 244 246',
    '--hpe-analytics-subtitle': '166 176 190',
    '--hpe-analytics-name': '241 243 245',
    '--hpe-analytics-header-text': '183 193 208',
    '--hpe-analytics-text-secondary': '161 170 184',
    '--hpe-analytics-text-muted': '137 148 164',
  }

  it.each(Object.entries(DARK_SPEC))('defines %s in the dark block', (token, value) => {
    expect(darkBlock).toMatch(new RegExp(`${token}:\\s*${value.replace(/ /g, '\\s')}\\s*;`))
  })

  it.each(Object.keys(DARK_SPEC))('also defines %s in :root so both themes resolve', (token) => {
    expect(lightBlock).toMatch(new RegExp(`${token}:`))
  })

  it('never points a dark analytics token at a light-mode value', () => {
    const block = darkBlock.slice(darkBlock.indexOf('--hpe-analytics-bg:'))
    const values = block.slice(0, block.indexOf('/* Semantic families'))
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l.startsWith('--hpe-analytics-'))
      .map((l) => l.split(':')[1].split(';')[0].trim())
      .filter(Boolean)

    expect(values.length).toBeGreaterThan(10)
    const lines = darkBlock
      .slice(darkBlock.indexOf('--hpe-analytics-bg:'))
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l.startsWith('--hpe-analytics-'))
      .map((l) => l.split(':')[1].split(';')[0].trim())
      .filter(Boolean)

    const TEXT = ['heading', 'subtitle', 'name', 'header-text', 'text-secondary', 'text-muted']
    for (const v of lines) {
      const [r, g, b] = v.split(/\s+/).map(Number)
      const token = Object.entries(DARK_SPEC).find(([, val]) => val === v)?.[0] ?? ''
      if (TEXT.some((t) => token.includes(t))) {
        expect(Math.min(r, g, b), `${token} is not a light text colour`).toBeGreaterThanOrEqual(120)
      } else {
        // Surfaces and lines: genuinely dark, and never a light-mode value.
        expect(Math.max(r, g, b), `${token} is not a dark surface: ${v}`).toBeLessThanOrEqual(70)
      }
    }
    const textTokens = ['--hpe-analytics-heading', '--hpe-analytics-subtitle', '--hpe-analytics-name',
      '--hpe-analytics-header-text', '--hpe-analytics-text-secondary', '--hpe-analytics-text-muted']
    for (const t of textTokens) {
      const m = darkBlock.match(new RegExp(`${t}:\\s*([\\d\\s]+);`))
      const [r, g, b] = m![1].trim().split(/\s+/).map(Number)
      expect(Math.min(r, g, b), `${t} is not a light text colour`).toBeGreaterThanOrEqual(120)
    }
  })

  it('parses cleanly — no unterminated comment can swallow declarations', () => {
    // A real scan: comment prose may legitimately contain '/*' (e.g. '*-200/*-500'),
    // so counting delimiters is not enough. What matters is that no comment is
    // left open, which is how the decorative-brand/focus tokens were lost.
    let openAt = 0
    let unterminated = false
    for (let i = 0; i < tokens.length - 1; i++) {
      if (tokens.startsWith('/*', i)) {
        const close = tokens.indexOf('*/', i + 2)
        if (close === -1) { unterminated = true; openAt = i; break }
        i = close + 1
      }
    }
    expect(
      unterminated,
      `unterminated comment swallows declarations from offset ${openAt}`,
    ).toBe(false)
    for (const t of ['--hpe-decorative-brand', '--hpe-decorative-green', '--hpe-focus']) {
      const idx = tokens.indexOf('.dark {')
      const body = tokens.slice(idx).replace(/\/\*[\s\S]*?\*\//g, '')
      expect(body, `${t} missing from the dark block`).toContain(t)
    }
  })
})

/* ------------------------------------------------------------------ *
 * 3. Tailwind namespace
 * ------------------------------------------------------------------ */
describe('tailwind analytics namespace', () => {
  it('is declared at the top level, not nested inside surface', () => {
    // Nesting flattens `surface.analytics-surface` to `bg-surface-analytics-surface`,
    // so `bg-analytics-surface` silently generated nothing.
    const start = tailwindConfig.indexOf('surface: {')
    const end = tailwindConfig.indexOf('\n        },', start)
    const surfaceBlock = tailwindConfig.slice(start, end)
    expect(surfaceBlock).toContain('--hpe-surface-950')
    expect(surfaceBlock).not.toContain('analytics')
  })

  it('maps each token to a same-named class key', () => {
    const ns = tailwindConfig.slice(tailwindConfig.indexOf('analytics: {'))
    for (const key of ['bg', 'surface', 'row', 'row-alt', 'header', 'tablehead', 'border', 'hover', 'selected']) {
      expect(ns).toMatch(new RegExp(`'${key}':|\\b${key}:`))
    }
  })
})

/* ------------------------------------------------------------------ *
 * 4. No dead utilities left in the component
 * ------------------------------------------------------------------ */
describe('analytics component has no dead dark utilities', () => {
  it('uses no analytics-* utility that no longer resolves', () => {
    expect(page).not.toMatch(/dark:(bg|border|divide|text)-analytics/)
  })

  it('uses no !important background that would defeat the scoped layer', () => {
    expect(page).not.toMatch(/!bg-/)
  })

  it('does not use dark:text-surface-300 or dark:text-surface-100, which render near-black on dark surfaces', () => {
    expect(page).not.toMatch(/dark:text-surface-(100|200|300)\b/)
  })

  it('does not stripe rows with dark:bg-surface-900, which .dark remaps to white', () => {
    expect(page).not.toMatch(/dark:bg-surface-900\b/)
  })

  it('keeps every light-mode utility intact', () => {
    // The approved light design depends on these exact utilities.
    expect(page).toContain('attendance-analytics')
    expect(page).toContain('bg-surface-50')
    expect(page).toContain('bg-white')
    expect(page).toContain('border-surface-200/60')
  })
})

/* ------------------------------------------------------------------ *
 * 5. Rendered structure
 * ------------------------------------------------------------------ */
describe('analytics dark surfaces are driven by row state, not per cell', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(adminApi.analyticsMeta).mockResolvedValue({
      months: ['2026-10'], teams: [], locations: ['Bangalore'], shifts: [], statuses: [],
    } as never)
    vi.mocked(adminApi.analyticsEmployees).mockResolvedValue(response)
  })

  it('scopes the page with the analytics root class', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(table()).toBeTruthy())
    expect(root()).toBeTruthy()
    expect(root().className).toContain('attendance-analytics')
  })

  it('gives no cell its own dark background, so sticky cells cannot diverge from their row', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(table()).toBeTruthy())

    for (const cell of cells()) {
      expect(cell.className, `cell overrides its row surface: ${cell.className}`).not.toMatch(/dark:bg-/)
    }
  })

  it('marks every table, the title bar, the card and the toggle with a styling hook', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(table()).toBeTruthy())

    expect(document.querySelectorAll('table.aa-table').length).toBe(1)
    expect(document.querySelector('.aa-titlebar')).toBeTruthy()
    expect(document.querySelector('.aa-card')).toBeTruthy()
    expect(document.querySelector('.aa-mode-toggle')).toBeTruthy()
    expect(document.querySelectorAll('.aa-mode-option').length).toBe(2)
  })

  it('keeps the frozen and right-pinned columns wired to the shared row surface', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(table()).toBeTruthy())

    const stickyCells = cells().filter((c) => c.className.includes('sticky'))
    expect(stickyCells.length).toBeGreaterThan(0)
    for (const c of stickyCells) {
      // Light mode keeps its opaque bg-white; dark is supplied by the scoped layer.
      expect(c.className, `sticky cell lost its opaque light background: ${c.className}`)
        .toMatch(/\bbg-white\b/)
      expect(c.className).not.toMatch(/dark:bg-/)
    }
    // S.No., Emp ID and Name are frozen; Recorded is pinned right.
    const firstRow = Array.from(table().querySelectorAll('tbody tr'))[0]
    const tds = Array.from(firstRow.querySelectorAll('td'))
    expect(tds[0].className).toContain('sticky')
    expect(tds[1].className).toContain('sticky')
    expect(tds[2].className).toContain('sticky')
    expect(tds[2].className).toContain('aa-col-name')
    expect(tds[tds.length - 1].className).toContain('right-0')
  })

  it('alternates rows so the base state is never the hover state', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(table()).toBeTruthy())

    const rows = Array.from(table().querySelectorAll('tbody tr'))
    const cls = (el: Element) => el.getAttribute('class') ?? ''
    const dataRows = rows.filter((r) => !cls(r).includes('aa-row-summary'))
    expect(dataRows.length).toBeGreaterThan(1)
    // Light parity is expressed with utilities; dark parity comes from
    // nth-child(even) in the scoped layer. Both must be present.
    expect(cls(dataRows[0])).toContain('bg-white')
    expect(cls(dataRows[1])).toContain('bg-surface-50')
  })
})

/* ------------------------------------------------------------------ *
 * 6. Month navigator
 * ------------------------------------------------------------------ */
describe('month navigator reads as one refined control', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(adminApi.analyticsMeta).mockResolvedValue({
      months: ['2026-10'], teams: [], locations: ['Bangalore'], shifts: [], statuses: [],
    } as never)
    vi.mocked(adminApi.analyticsEmployees).mockResolvedValue(response)
  })

  it('wraps prev, label and next in a single bordered unit', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(table()).toBeTruthy())

    const nav = document.querySelector('.aa-monthnav') as HTMLElement
    expect(nav).toBeTruthy()
    // one container, three segments, hairline dividers between them
    expect(nav.children.length).toBe(3)
    expect(nav.className).toContain('divide-x')
    expect(nav.className).toContain('overflow-hidden')
    expect(nav.className).toContain('rounded-lg')
    expect(nav.className).toContain('border')
    expect(nav.children[1].className).toContain('aa-monthnav-label')
  })

  it('uses 32px square borderless arrow buttons', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(table()).toBeTruthy())

    const btns = Array.from(document.querySelectorAll('.aa-monthnav-btn'))
    expect(btns.length).toBe(2)
    for (const b of btns) {
      expect(b.className).toContain('h-8')
      expect(b.className).toContain('w-8')
      // the container owns the outline; a per-button border would fragment it
      expect(b.className).not.toMatch(/(^|\s)border(\s|$)/)
      expect(b.className).not.toContain('rounded-')
    }
  })

  it('renders the month label uppercase at 13px medium weight', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(table()).toBeTruthy())

    const label = document.querySelector('.aa-monthnav-label') as HTMLElement
    expect(label.className).toContain('uppercase')
    expect(label.className).toContain('text-[13px]')
    expect(label.className).toContain('font-medium')
    // centred between the two arrows (it is a flex container, so justify-center
    // is what centres the text node)
    expect(label.className).toContain('justify-center')
    expect(label.className).toContain('items-center')
  })

  it('keeps prev/next navigation wired to the existing handlers', async () => {
    render(<AdminAnalyticsPage />)
    await waitFor(() => expect(table()).toBeTruthy())

    expect(screen.getByLabelText('Previous month')).toBeTruthy()
    expect(screen.getByLabelText('Next month')).toBeTruthy()
  })

  it('keeps dark surfaces on tokens and never white', () => {
    const css = themeCss.replace(/\/\*[\s\S]*?\*\//g, '')
    expect(css).toMatch(/\.aa-monthnav\s*\{[^}]*var\(--hpe-analytics-border\)/)
    // hairline dividers between segments
    expect(css).toMatch(/\.aa-monthnav > \* \+ \*\s*\{[^}]*border-left-color/)
    // chevrons: muted at rest, lifted on hover, and hover must exclude :disabled
    expect(css).toMatch(/\.aa-monthnav-btn\s*\{[^}]*var\(--hpe-analytics-text-muted\)/)
    expect(css).toMatch(/\.aa-monthnav-btn:hover:not\(:disabled\)\s*\{[^}]*var\(--hpe-analytics-hover\)/)
    expect(css).toMatch(/\.aa-monthnav-btn:disabled\s*\{/)
    expect(css).toMatch(/\.aa-monthnav-label\s*\{[^}]*var\(--hpe-analytics-header-text\)/)
    expect(css).not.toMatch(/\.aa-monthnav[^}]*#fff/i)
  })

  it('does not brighten the disabled next button on hover in light mode', () => {
    expect(page).toContain('disabled:hover:bg-transparent')
  })
})
