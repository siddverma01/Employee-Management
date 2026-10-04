import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { UnresolvedStatusEditor } from '@/components/historical/UnresolvedStatusEditor'
import { adminApi } from '@/api'
import type { HistoricalUnresolvedResponse } from '@/types'

vi.mock('@/api', () => ({
  adminApi: {
    historicalUnresolved: vi.fn(),
    historicalCorrectRow: vi.fn(),
    historicalSkipRow: vi.fn(),
    historicalBulkResolve: vi.fn(),
  },
}))

const statuses = [
  { code: 'WFO', name: 'Work From Office', description: 'Present in office', displayColor: 'sky' },
  { code: 'PL', name: 'Privilege Leave', description: 'Planned leave', displayColor: 'amber' },
]

function entry(
  over: Partial<HistoricalUnresolvedResponse['entries'][number]> = {},
): HistoricalUnresolvedResponse['entries'][number] {
  return {
    id: 1,
    sheetName: 'Sep 2025',
    sourceRow: 12,
    sourceColumn: 4,
    cellRef: 'Row 12, Column D',
    employeeId: '60175312',
    employeeName: 'Pardhi',
    attendanceDate: '2025-09-03',
    originalStatus: 'P',
    incomingStatus: null,
    statusName: null,
    issue: "Unrecognised status 'P'",
    category: 'UNMAPPED',
    description: null,
    descriptionAuthor: null,
    action: 'INSERT',
    corrected: false,
    skipped: false,
    ...over,
  }
}

function response(over: Partial<HistoricalUnresolvedResponse> = {}): HistoricalUnresolvedResponse {
  return {
    importId: 1,
    summary: { totalEntries: 10, validEntries: 7, flagged: 3, corrected: 0, skipped: 1, remaining: 2 },
    page: 0,
    size: 25,
    totalEntries: 2,
    entries: [
      entry(),
      entry({
        id: 2,
        employeeId: '60175313',
        employeeName: 'Siya',
        originalStatus: null,
        issue: 'Blank status — the cell has no value',
        cellRef: 'Row 13, Column E',
      }),
    ],
    ...over,
  }
}

function renderEditor(onChanged = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return {
    onChanged,
    ...render(
      <QueryClientProvider client={client}>
        <UnresolvedStatusEditor importId={1} statuses={statuses} onChanged={onChanged} />
      </QueryClientProvider>,
    ),
  }
}

describe('UnresolvedStatusEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(adminApi.historicalUnresolved).mockResolvedValue(response())
    vi.mocked(adminApi.historicalCorrectRow).mockResolvedValue({
      entry: entry({ incomingStatus: 'PL', statusName: 'Privilege Leave', corrected: true, action: 'INSERT' }),
      summary: { totalEntries: 10, validEntries: 7, flagged: 3, corrected: 1, skipped: 1, remaining: 1 },
    })
    vi.mocked(adminApi.historicalSkipRow).mockResolvedValue({
      entry: entry({ skipped: true, action: 'SKIPPED' }),
      summary: { totalEntries: 10, validEntries: 7, flagged: 3, corrected: 0, skipped: 2, remaining: 1 },
    })
    vi.mocked(adminApi.historicalBulkResolve).mockResolvedValue({
      affected: 2,
      summary: { totalEntries: 10, validEntries: 10, flagged: 3, corrected: 2, skipped: 1, remaining: 0 },
    })
  })

  it('shows the counts and the value the workbook actually held', async () => {
    renderEditor()

    await waitFor(() => expect(screen.getByText('60175312')).toBeInTheDocument())
    expect(screen.getByText('Pending review')).toBeInTheDocument()
    expect(screen.getByText('Remaining unresolved')).toBeInTheDocument()
    // pending review and remaining unresolved both show the same live count
    expect(screen.getAllByText('2')).toHaveLength(2)
    // The original cell contents stay visible so a correction is never mistaken
    // for what the file said.
    expect(screen.getByText('P')).toBeInTheDocument()
    expect(screen.getByText('blank')).toBeInTheDocument()
    expect(screen.getByText("Unrecognised status 'P'")).toBeInTheDocument()
    expect(screen.getByText('Row 12, Column D')).toBeInTheDocument()
  })

  it('corrects one entry with the status the admin picked', async () => {
    const user = userEvent.setup()
    renderEditor()

    await waitFor(() => expect(screen.getByText('60175312')).toBeInTheDocument())
    const selects = screen.getAllByLabelText(/Status for/)
    await user.selectOptions(selects[0], 'PL')
    await user.click(screen.getAllByRole('button', { name: 'Correct' })[0])

    await waitFor(() =>
      expect(adminApi.historicalCorrectRow).toHaveBeenCalledWith(1, 1, 'PL'),
    )
  })

  it('cannot correct until a status is chosen', async () => {
    renderEditor()

    await waitFor(() => expect(screen.getByText('60175312')).toBeInTheDocument())
    expect(screen.getAllByRole('button', { name: 'Correct' })[0]).toBeDisabled()
  })

  it('skips an entry only after the admin explicitly confirms', async () => {
    const user = userEvent.setup()
    renderEditor()

    await waitFor(() => expect(screen.getByText('60175312')).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Skip' })[0])

    // Nothing is skipped just by asking — a second, explicit action is required.
    expect(adminApi.historicalSkipRow).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Confirm skip' }))

    await waitFor(() => expect(adminApi.historicalSkipRow).toHaveBeenCalledWith(1, 1, undefined))
  })

  it('filters the worklist by decision state', async () => {
    const user = userEvent.setup()
    renderEditor()

    await waitFor(() => expect(screen.getByText('60175312')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: 'Corrected' }))

    await waitFor(() => expect(adminApi.historicalUnresolved).toHaveBeenCalledWith(
      1, 0, 25, expect.objectContaining({ state: 'CORRECTED' }),
    ))
  })

  it('offers the full centralized status list, not a hardcoded subset', async () => {
    renderEditor()

    await waitFor(() => expect(screen.getByText('60175312')).toBeInTheDocument())
    const select = screen.getAllByLabelText(/Status for/)[0]
    expect(Array.from(select.querySelectorAll('option')).map((o) => o.textContent)).toEqual([
      'Choose a status…',
      'WFO — Work From Office',
      'PL — Privilege Leave',
    ])
  })

  it('confirms completion once nothing is left to review', async () => {
    vi.mocked(adminApi.historicalUnresolved).mockResolvedValue(
      response({
        summary: { totalEntries: 10, validEntries: 10, flagged: 3, corrected: 2, skipped: 1, remaining: 0 },
        totalEntries: 0,
        entries: [],
      }),
    )
    renderEditor()

    await waitFor(() => expect(screen.getByText(/Every flagged entry has been dealt with/)).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Correct' })).not.toBeInTheDocument()
  })

  it('tells the parent to refresh so the commit gate stays in step', async () => {
    const user = userEvent.setup()
    const { onChanged } = renderEditor()

    await waitFor(() => expect(screen.getByText('60175312')).toBeInTheDocument())
    await user.click(screen.getAllByRole('button', { name: 'Skip' })[0])
    await user.click(screen.getByRole('button', { name: 'Confirm skip' }))

    await waitFor(() => expect(onChanged).toHaveBeenCalled())
  })
})