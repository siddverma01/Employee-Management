import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import AdminHistoricalImportPage from '@/pages/admin/AdminHistoricalImportPage'
import { adminApi } from '@/api'
import type { HistoricalCommitResponse, HistoricalPreviewResponse } from '@/types'

vi.mock('@/api', () => ({
  adminApi: {
    historicalInspect: vi.fn(),
    historicalPreview: vi.fn(),
    historicalPreviewOf: vi.fn(),
    historicalUnresolved: vi.fn(),
    historicalCommit: vi.fn(),
    historicalHistory: vi.fn(),
    historicalStatuses: vi.fn(),
    historicalCorrectRow: vi.fn(),
    historicalSkipRow: vi.fn(),
    historicalBulkResolve: vi.fn(),
    historicalReport: vi.fn(),
    historicalMapStaged: vi.fn(),
    historicalUnknownCodes: vi.fn(),
  },
  departmentApi: { list: vi.fn() },
}))
vi.mock('@/api/client', () => ({ extractMessage: (e: unknown) => String(e) }))
vi.mock('react-hot-toast', () => ({ default: { success: vi.fn(), error: vi.fn() } }))
vi.mock('@/components/historical/UnresolvedStatusEditor', () => ({
  UnresolvedStatusEditor: () => <div data-testid="unresolved-editor" />,
}))

const preview: HistoricalPreviewResponse = {
  importId: 7,
  fileName: 'stored.xlsx',
  originalFileName: 'leave-tracker-2025.xlsx',
  status: 'PREVIEWED',
  summary: {
    totalSheets: 1,
    sheetsImported: 1,
    sheetsSkipped: 0,
    employeesDetected: 2,
    recordsDetected: 2,
    newRecords: 2,
    updatedRecords: 0,
    duplicateRecords: 0,
    unknownCodes: 0,
    invalidRows: 0,
    warnings: 0,
    errors: 0,
    importableRows: 2,
    failedRows: 0,
    sheetDetails: [],
  },
  totalRows: 2,
  page: 0,
  size: 100,
  rows: [],
  analysis: [],
  issues: [],
  unknownCodes: [],
}

const commit: HistoricalCommitResponse = {
  importId: 7,
  fileName: 'stored.xlsx',
  originalFileName: 'leave-tracker-2025.xlsx',
  status: 'COMMITTED',
  importedAt: '2026-10-03T08:29:24Z',
  summary: preview.summary,
  committedRows: 2,
  result: {
    employees: 2,
    records: 2,
    inserted: 2,
    updated: 0,
    duplicatesSkipped: 0,
    warnings: 0,
    unknownStatuses: 0,
    failedRows: 0,
    corrected: 0,
    skipped: 0,
  },
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <AdminHistoricalImportPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AdminHistoricalImportPage — successful commit flow', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(adminApi.historicalStatuses).mockResolvedValue([
      { code: 'WFO', name: 'Work From Office', description: null, displayColor: 'sky' },
    ])
    vi.mocked(adminApi.historicalHistory).mockResolvedValue([])
    vi.mocked(adminApi.historicalInspect).mockResolvedValue({
      fileName: 'leave-tracker-2025.xlsx',
      fileSize: 123,
      sheetCount: 1,
      sheets: ['Sep 2025'],
    })
    vi.mocked(adminApi.historicalPreview).mockResolvedValue(preview)
    vi.mocked(adminApi.historicalPreviewOf).mockResolvedValue(preview)
    vi.mocked(adminApi.historicalUnresolved).mockResolvedValue({
      importId: 7,
      summary: { totalEntries: 2, validEntries: 2, flagged: 0, corrected: 0, skipped: 0, remaining: 0 },
      page: 0,
      size: 1,
      totalEntries: 0,
      entries: [],
    })
    vi.mocked(adminApi.historicalCommit).mockResolvedValue(commit)
  })

  it('navigates to Step 8 and renders the result without blanking', async () => {
    const user = userEvent.setup()
    const { container } = renderPage()

    const input = container.querySelector('input[type="file"]') as HTMLInputElement
    fireEvent.change(input, { target: { files: [new File(['x'], 'leave-tracker-2025.xlsx')] } })

    await user.click(await screen.findByRole('button', { name: /Analyze Workbook/i }))
    await user.click(await screen.findByRole('button', { name: /Continue to Validation/i }))
    await user.click(await screen.findByRole('button', { name: /Continue to Status Mapping/i }))
    await user.click(await screen.findByRole('button', { name: /Continue to Making/i }))
    await user.click(await screen.findByRole('button', { name: /Continue to Preview/i }))
    await user.click(await screen.findByRole('button', { name: /Continue to Review & Import/i }))

    await user.click(await screen.findByRole('button', { name: /Import Historical Attendance/i }))

    await waitFor(() => expect(screen.getByText('Import completed successfully')).toBeInTheDocument())
    expect(screen.getAllByText('Attendance Records').length).toBeGreaterThan(0)
    // The crash previously surfaced here as a blank route; make sure the shell still renders.
    expect(screen.getByText('Import Historical Attendance')).toBeInTheDocument()
    expect(screen.queryByText(/unexpected error/i)).not.toBeInTheDocument()
  })
})
