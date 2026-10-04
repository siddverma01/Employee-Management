import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ResultStep } from '@/pages/admin/AdminHistoricalImportPage'
import type { HistoricalCommitResponse } from '@/types'

vi.mock('@/api', () => ({ adminApi: {} }))
vi.mock('@/api/client', () => ({ extractMessage: (e: unknown) => String(e) }))

const response: HistoricalCommitResponse = {
  importId: 7,
  fileName: 'stored.xlsx',
  originalFileName: 'leave-tracker-2025.xlsx',
  status: 'COMMITTED',
  summary: {
    totalSheets: 12,
    sheetsImported: 12,
    sheetsSkipped: 0,
    employeesDetected: 67,
    recordsDetected: 19330,
    newRecords: 0,
    updatedRecords: 19327,
    duplicateRecords: 0,
    unknownCodes: 0,
    invalidRows: 0,
    warnings: 1,
    errors: 0,
    importableRows: 19327,
    failedRows: 0,
    sheetDetails: [],
  },
  committedRows: 19327,
  result: {
    employees: 67,
    records: 19330,
    inserted: 0,
    updated: 19327,
    duplicatesSkipped: 0,
    warnings: 1,
    unknownStatuses: 0,
    failedRows: 0,
    corrected: 2,
    skipped: 1,
  },
}

const props = {
  importId: 7,
  onImportDetails: () => {},
  showDetails: false,
  onDownload: () => {},
  onClose: () => {},
}

describe('ResultStep', () => {
  it('renders backend statistics from the serialized `records` field', () => {
    render(<ResultStep result={response} {...props} />)
    expect(screen.getByText('Import completed successfully')).toBeInTheDocument()
    expect(screen.getByText('Attendance Records')).toBeInTheDocument()
    expect(screen.getByText('19,330')).toBeInTheDocument()
    expect(screen.getByText('Manually corrected')).toBeInTheDocument()
    expect(screen.getAllByText('19,327').length).toBeGreaterThan(0)
  })

  it('renders a safe fallback instead of crashing when expected fields are missing', () => {
    const sparse = {
      importId: 7,
      fileName: 'x.xlsx',
      originalFileName: 'x.xlsx',
      status: 'COMMITTED',
      committedRows: 0,
    } as unknown as HistoricalCommitResponse

    render(<ResultStep result={sparse} {...props} />)
    expect(screen.getByText('Import completed successfully')).toBeInTheDocument()
    expect(screen.getByText('Attendance Records')).toBeInTheDocument()
  })
})
