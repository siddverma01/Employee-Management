import { useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { AlertTriangle, CheckCircle2, Table2, UploadCloud } from 'lucide-react'
import toast from 'react-hot-toast'
import type { ImportPreview, ImportRow, ImportRowDetail, ImportUpload } from '@/types'
import { adminApi } from '@/api'
import { extractMessage } from '@/api/client'
import { formatDateTime } from '@/utils'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'
import { RosterImportPanel } from '@/components/roster/RosterImportPanel'
import { cn } from '@/utils'

function isRosterUpload(upload: ImportUpload): boolean {
  const headers = upload.headers ?? []
  const suggested = upload.suggestedMapping ?? {}
  const missingDateType = !suggested.attendanceDate || !suggested.attendanceType
  const dayCols = headers.filter((h) => /^\d{1,2}$/.test(h) || /^\d{1,2}\/\d{1,2}$/.test(h)).length
  const rosterShell = headers.some((h) => /employee\s*name|week\s*off|shift|\blocation\b/i.test(h))
  return missingDateType && (dayCols >= 3 || rosterShell)
}

type MappedRow = {
  _originalIndex: number
  _rowNumber: number
  _errors: string[]
  _details: ImportRowDetail[]
  employeeCode: string | null
  attendanceDate: string | null
  attendanceType: string | null
  remarks: string | null
  _status: 'valid' | 'invalid' | 'duplicate'
}

type MappedPreview = {
  importId: number
  rows: MappedRow[]
  stats: { valid: number; invalid: number; duplicate: number; total: number }
  importableRows: number
}

const MAPPED_FIELD_KEYS = ['employeeCode', 'attendanceDate', 'attendanceType', 'remarks'] as const

function processAndValidateData(
  rawExcelData: Array<Record<string, unknown>>,
  currentMappings: Record<string, string>,
  backendRows?: ImportRow[],
): MappedRow[] {
  if (!Array.isArray(rawExcelData)) return []

  return rawExcelData.map((row, index) => {
    // 1. Map raw row data to target fields based on user selections
    const valueAt = (col: string | undefined) =>
      col && col.length > 0 ? String((row as Record<string, unknown>)[col] ?? '') : null
    const employeeCode = valueAt(currentMappings.employeeCode)
    const attendanceDate = valueAt(currentMappings.attendanceDate)
    const attendanceType = valueAt(currentMappings.attendanceType)
    const remarks = valueAt(currentMappings.remarks)
    const backend = backendRows?.[index]

    // 2. Validation: the backend is authoritative — it re-parses dates, resolves employee
    //    codes and flags duplicates. Fall back to a simple heuristic when no backend row exists.
    let status: MappedRow['_status']
    const backendStatus = backend ? String(backend.status ?? '').toLowerCase() : ''
    if (backendStatus === 'valid' || backendStatus === 'invalid' || backendStatus === 'duplicate') {
      status = backendStatus
    } else {
      status = employeeCode && attendanceDate ? 'valid' : 'invalid'
    }

    const mappedRow: MappedRow = {
      _originalIndex: index,
      _rowNumber: backend?.rowNumber ?? index + 1,
      _errors: backend?.errors ?? [],
      _details: backend?.details ?? [],
      employeeCode,
      attendanceDate,
      attendanceType,
      remarks,
      _status: status,
    }

    return mappedRow
  })
}

function MappingStep({ upload, onDone }: { upload: ImportUpload; onDone: (preview: MappedPreview) => void }) {
  const suggestedEntry = (field: string) =>
    Object.entries(upload.suggestedMapping ?? {}).find(([, f]) => f === field)?.[0] ?? ''
  const [mappings, setMappings] = useState<Record<string, string>>({
    employeeCode: suggestedEntry('employeeCode'),
    attendanceDate: suggestedEntry('attendanceDate'),
    attendanceType: suggestedEntry('attendanceType'),
    remarks: suggestedEntry('remarks'),
  })
  const selectedValues = Object.values(mappings).filter((val) => val !== '')
  const hasDuplicates = new Set(selectedValues).size !== selectedValues.length
  const hasRequiredFields = mappings.employeeCode !== '' && mappings.attendanceDate !== ''
  const isMappingValid = hasRequiredFields && !hasDuplicates
  const mappingMutation = useMutation({
    mutationFn: () => adminApi.setMapping(upload.importId, mappings),
    onSuccess: (backendPreview: ImportPreview) => {
      const rawExcelData = (backendPreview.rows ?? []).map((r) => r.data ?? {})
      const rows = processAndValidateData(rawExcelData, mappings, backendPreview.rows)
      onDone({
        importId: backendPreview.importId,
        rows,
        stats: {
          valid: rows.filter((r) => r._status === 'valid').length,
          invalid: rows.filter((r) => r._status === 'invalid').length,
          duplicate: rows.filter((r) => r._status === 'duplicate').length,
          total: rows.length,
        },
        importableRows: backendPreview.validRows,
      })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  return (
    <div className="card p-6">
      <h3 className="mb-1 text-sm font-semibold text-surface-700">Step 2 · Column Mapping</h3>
      <p className="mb-4 text-xs text-surface-400">Map each source column to a target field.</p>
      <div className="space-y-3">
        <div className="grid grid-cols-1 items-center gap-2 sm:grid-cols-2">
          <span className="text-sm font-medium text-surface-700">Employee code</span>
          <select
            className="select"
            value={mappings.employeeCode}
            onChange={(e) => setMappings({ ...mappings, employeeCode: e.target.value })}
          >
            <option value="">— Skip —</option>
            {upload.headers.map((h) => (
              <option key={h} value={h}>{h}</option>
            ))}
          </select>
        </div>
        <div className="grid grid-cols-1 items-center gap-2 sm:grid-cols-2">
          <span className="text-sm font-medium text-surface-700">Attendance date</span>
          <select
            className="select"
            value={mappings.attendanceDate}
            onChange={(e) => setMappings({ ...mappings, attendanceDate: e.target.value })}
          >
            <option value="">— Skip —</option>
            {upload.headers.map((h) => (
              <option key={h} value={h}>{h}</option>
            ))}
          </select>
        </div>
        <div className="grid grid-cols-1 items-center gap-2 sm:grid-cols-2">
          <span className="text-sm font-medium text-surface-700">Attendance type <span className="text-xs font-normal text-surface-400">(optional)</span></span>
          <select
            className="select"
            value={mappings.attendanceType}
            onChange={(e) => setMappings({ ...mappings, attendanceType: e.target.value })}
          >
            <option value="">— Skip —</option>
            {upload.headers.map((h) => (
              <option key={h} value={h}>{h}</option>
            ))}
          </select>
        </div>
        <div className="grid grid-cols-1 items-center gap-2 sm:grid-cols-2">
          <span className="text-sm font-medium text-surface-700">Remarks</span>
          <select
            className="select"
            value={mappings.remarks}
            onChange={(e) => setMappings({ ...mappings, remarks: e.target.value })}
          >
            <option value="">— Skip —</option>
            {upload.headers.map((h) => (
              <option key={h} value={h}>{h}</option>
            ))}
          </select>
        </div>
      </div>
      {hasDuplicates ? (
        <p className="mt-3 flex items-center gap-1 rounded bg-amber-50 p-2 text-xs font-medium text-amber-600">
          <AlertTriangle className="h-3 w-3" /> Each source column can only be mapped once — please choose unique columns.
        </p>
      ) : !isMappingValid ? (
        <p className="mt-3 flex items-center gap-1 rounded bg-amber-50 p-2 text-xs font-medium text-amber-600">
          <AlertTriangle className="h-3 w-3" /> employeeCode and attendanceDate are required to continue.
        </p>
      ) : null}
      <div className="mt-4 flex justify-end">
        <Button
          className={isMappingValid ? 'bg-[#01A982]' : undefined}
          onClick={() => mappingMutation.mutate()}
          disabled={!isMappingValid}
          loading={mappingMutation.isPending}
        >
          Validate Rows
        </Button>
      </div>
    </div>
  )
}

function PreviewStep({ preview, onCommit }: { preview: MappedPreview; onCommit: () => void }) {
  const { valid, invalid, duplicate, total } = preview.stats
  const problemRows = preview.rows.filter((r) => r._status !== 'valid')

  return (
    <div className="space-y-4">
      <div className="card p-6">
        <div className="flex flex-wrap items-center gap-4">
          <h3 className="text-sm font-semibold text-surface-700">Step 3 · Validation Preview</h3>
          <div className="ml-auto flex flex-wrap items-center gap-3">
            <span className="bg-green-100 px-3 py-1 rounded-full text-sm font-medium text-green-700">{valid} valid</span>
            <span className="bg-red-100 px-3 py-1 rounded-full text-sm font-medium text-red-700">{invalid} invalid</span>
            <span className="bg-yellow-100 px-3 py-1 rounded-full text-sm font-medium text-yellow-700">{duplicate} duplicate</span>
            <span className="bg-gray-100 px-3 py-1 rounded-full text-sm font-medium text-gray-700">{total} total</span>
          </div>
        </div>

        {problemRows.length > 0 && (
          <div className="mt-4 rounded-lg border border-red-100 bg-red-50/60 p-4">
            <h4 className="text-sm font-semibold text-red-800">
              {invalid} invalid row{invalid === 1 ? '' : 's'} and {duplicate} duplicate row{duplicate === 1 ? '' : 's'} need attention
            </h4>
            <ul className="mt-2 divide-y divide-red-100">
              {problemRows.map((row) => (
                <li key={row._originalIndex} className="py-2 text-xs text-red-700">
                  <span className="font-semibold">Row {row._rowNumber}</span>
                  <span className="ml-2 text-red-400">({row._status})</span>
                  <div className="mt-1 space-y-1">
                    {row._details.length > 0 ? (
                      row._details.map((d, i) => (
                        <p key={i} className="rounded bg-white/70 px-2 py-1">
                          {d.column ? <b>{d.column} · </b> : null}
                          {d.message.trim()}
                          {d.rawValue ? <span className="text-red-500"> — raw value: &quot;{d.rawValue}&quot;</span> : null}
                        </p>
                      ))
                    ) : (
                      <p className="rounded bg-white/70 px-2 py-1">
                        {row._errors.join('; ') || (row._status === 'duplicate' ? 'Duplicate row' : 'Invalid row')}
                      </p>
                    )}
                  </div>
                </li>
              ))}
            </ul>
            <p className="mt-2 text-[11px] text-red-500">
              Invalid rows are skipped on import. Fix the values in the source file or adjust the column mapping and validate again.
            </p>
          </div>
        )}

        <div className="mt-4 overflow-hidden rounded-lg border border-gray-200">
          <div className="max-h-96 overflow-auto">
            <table className="w-full">
              <thead className="bg-gray-50">
                <tr>
                  {MAPPED_FIELD_KEYS.map((field) => (
                    <th key={field} className="py-3 px-4 text-left text-sm font-semibold text-gray-500">{field}</th>
                  ))}
                  <th className="py-3 px-4 text-left text-sm font-semibold text-gray-500">Status</th>
                </tr>
              </thead>
              <tbody>
                {preview.rows.map((row) => (
                  <tr key={row._originalIndex} className="border-b border-gray-100">
                    {MAPPED_FIELD_KEYS.map((field) => (
                      <td key={field} className="py-3 px-4 text-sm text-gray-700">{row[field] || '—'}</td>
                    ))}
                    <td className="py-3 px-4 text-sm text-gray-700">
                      {row._status === 'invalid' ? (
                        <span
                          title="Needs both an employee code and an attendance date"
                          className="inline-flex items-center gap-1 bg-red-100 px-2 py-0.5 rounded text-xs font-medium text-red-700"
                        >
                          invalid <span className="text-red-500">!</span>
                        </span>
                      ) : row._status === 'duplicate' ? (
                        <span className="bg-yellow-100 px-2 py-0.5 rounded text-xs font-medium text-yellow-700">duplicate</span>
                      ) : (
                        <span className="bg-green-100 px-2 py-0.5 rounded text-xs font-medium text-green-700">valid</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
      <div className="flex justify-end">
        <Button onClick={onCommit} disabled={preview.importableRows === 0} variant={invalid > 0 ? 'secondary' : 'primary'}>
          Import {preview.importableRows} valid row{preview.importableRows === 1 ? '' : 's'}
        </Button>
      </div>
    </div>
  )
}

export function AdminImportPage() {
  const queryClient = useQueryClient()
  const fileRef = useRef<HTMLInputElement>(null)
  const lastFileRef = useRef<File | null>(null)
  const [mode, setMode] = useState<'attendance' | 'roster'>('attendance')
  const [rosterHandoffFile, setRosterHandoffFile] = useState<File | null>(null)
  const [upload, setUpload] = useState<ImportUpload | null>(null)
  const [preview, setPreview] = useState<MappedPreview | null>(null)
  const [commitResult, setCommitResult] = useState<{ imported: number; total: number } | null>(null)

  const uploadMutation = useMutation({
    mutationFn: adminApi.uploadExcel,
    onSuccess: (res) => {
      if (isRosterUpload(res)) {
        // Roster-format grid detected — hand it to the roster importer instead.
        const f = lastFileRef.current
        setRosterHandoffFile(f)
        setMode('roster')
        reset()
        toast.success('Monthly roster format detected — select the team and it imports automatically')
        return
      }
      setUpload(res)
      setPreview(null)
      setCommitResult(null)
      toast.success('File uploaded — map the columns to continue')
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  const commitMutation = useMutation({
    mutationFn: adminApi.commitImport,
    onSuccess: (res) => {
      setCommitResult({ imported: res.importedRows, total: res.totalRows })
      toast.success(`Imported ${res.importedRows} attendance records`)
      queryClient.invalidateQueries({ queryKey: ['attendance'] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  const { data: history, isLoading } = useQuery({ queryKey: ['admin', 'imports', 'history'], queryFn: adminApi.importHistory })

  const reset = () => {
    setUpload(null)
    setPreview(null)
    setCommitResult(null)
    if (fileRef.current) fileRef.current.value = ''
  }

  return (
    <div>
      <PageHeader title="Excel import" subtitle="Import attendance records or a monthly attendance roster" />

      <div className="mb-4 grid grid-cols-1 gap-2 rounded-lg border border-surface-200 bg-surface-0 p-1.5 sm:grid-cols-2">
        <button
          onClick={() => setMode('attendance')}
          className={cn(
            'flex items-center justify-center gap-2 rounded-md px-4 py-2 text-sm font-medium transition-colors',
            mode === 'attendance'
              ? 'bg-[#01A982] text-white shadow-hpe-teal'
              : 'border border-surface-300 bg-surface-0 text-surface-500 hover:text-surface-700',
          )}
        >
          <UploadCloud className="h-4 w-4" /> Attendance records
        </button>
        <button
          onClick={() => setMode('roster')}
          className={cn(
            'flex items-center justify-center gap-2 rounded-md px-4 py-2 text-sm font-medium transition-colors',
            mode === 'roster'
              ? 'bg-[#01A982] text-white shadow-hpe-teal'
              : 'border border-surface-300 bg-surface-0 text-surface-500 hover:text-surface-700',
          )}
        >
          <Table2 className="h-4 w-4" /> Monthly attendance roster
        </button>
      </div>

      {mode === 'roster' ? (
        <div className="max-w-5xl">
          <RosterImportPanel initialFile={rosterHandoffFile} />
        </div>
      ) : (
      <div className="grid gap-4 lg:grid-cols-10">
        <div className="space-y-4 lg:col-span-7">
          <div className="card p-6">
            <h3 className="mb-3 text-sm font-semibold text-surface-700">Step 1 · Upload file</h3>
            <label className="flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed border-[#01A982] bg-brand-50/40 px-6 py-10 text-center transition-colors hover:bg-brand-50/70 dark:bg-brand-500/10 dark:hover:bg-brand-500/20">
              <UploadCloud className="h-8 w-8 text-brand-500" />
              <span className="text-sm font-medium text-surface-700">Click to choose an .xlsx / .xls file</span>
              <span className="text-xs text-surface-400">Expected columns: employee code, attendance date (+ optional attendance type &amp; remarks)</span>
              <input
                ref={fileRef}
                type="file"
                accept=".xlsx,.xls"
                className="hidden"
                onChange={(e) => {
                  const f = e.target.files?.[0]
                  if (f) {
                    lastFileRef.current = f
                    uploadMutation.mutate(f)
                  }
                }}
              />
            </label>
            {uploadMutation.isPending && <p className="mt-3 text-center text-xs text-surface-400">Uploading…</p>}
          </div>

          {commitResult && (
            <div className="card border-emerald-200 bg-emerald-50 p-6">
              <div className="flex items-center gap-3">
                <CheckCircle2 className="h-6 w-6 text-emerald-600" />
                <div>
                  <p className="font-semibold text-emerald-800">Import complete</p>
                  <p className="text-sm text-emerald-700">{commitResult.imported} of {commitResult.total} rows successfully imported as attendance records.</p>
                </div>
                <Button className="ml-auto" size="sm" variant="secondary" onClick={reset}>Start a new import</Button>
              </div>
            </div>
          )}

          {upload && !commitResult && !preview && (
            <div className="card p-4 text-xs text-surface-500">
              <p><strong className="text-surface-700">{upload.originalFileName}</strong> · {upload.totalRows} rows · headers: {upload.headers.join(', ')}</p>
            </div>
          )}

          {upload && !commitResult && !preview && <MappingStep upload={upload} onDone={setPreview} />}

          {preview && !commitResult && (
            <>
              <PreviewStep preview={preview} onCommit={() => commitMutation.mutate(preview.importId)} />
              <button className="text-xs text-surface-400 hover:underline" onClick={reset}>← Re-upload a different file</button>
            </>
          )}

          <div className="card p-4">
            <h3 className="mb-2 text-sm font-semibold text-surface-700">Notes</h3>
            <ul className="space-y-1 text-xs leading-relaxed text-surface-500">
              <li>• Attendance type accepted values: <code className="rounded bg-surface-100 px-1">WFO</code>, <code className="rounded bg-surface-100 px-1">WFH</code>, <code className="rounded bg-surface-100 px-1">PL</code>, <code className="rounded bg-surface-100 px-1">SL</code>, <code className="rounded bg-surface-100 px-1">CO</code>, <code className="rounded bg-surface-100 px-1">FL</code>, <code className="rounded bg-surface-100 px-1">WO</code>, <code className="rounded bg-surface-100 px-1">ATRn</code>, <code className="rounded bg-surface-100 px-1">HPEH</code>. When skipped, rows default to Work From Office.</li>
              <li>• Employee code must match an existing employee (numeric or alphanumeric). Dates parse from formats such as <code className="rounded bg-surface-100 px-1">YYYY-MM-DD</code>, <code className="rounded bg-surface-100 px-1">DD/MM/YYYY</code>, <code className="rounded bg-surface-100 px-1">MM/DD/YYYY</code>, <code className="rounded bg-surface-100 px-1">01-Sep-2025</code>, <code className="rounded bg-surface-100 px-1">Sep 1, 2025</code>.</li>
              <li>• Rows that already exist for the same employee + date are marked DUPLICATE and skipped — existing records are never overwritten.</li>
              <li>• Multi-sheet files are read from the sheet containing the import table (cover/title sheets are ignored).</li>
            </ul>
          </div>
        </div>

        <div className="card overflow-hidden lg:col-span-3">
          <h3 className="border-b border-surface-200 px-4 py-3 text-sm font-semibold text-surface-700">Import history</h3>
          {isLoading ? (
            <div className="p-8"><LoadingState /></div>
          ) : !history?.length ? (
            <div className="p-4"><EmptyState title="No imports yet" /></div>
          ) : (
            <ul className="divide-y divide-surface-200">
              {history.map((h) => (
                <li key={h.id} className="px-4 py-3">
                  <div className="flex items-center justify-between gap-2">
                    <p className="truncate text-sm font-medium text-surface-800" title={h.originalFileName}>{h.originalFileName}</p>
                    <span className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-semibold ${h.status === 'COMMITTED' ? 'bg-emerald-50 text-emerald-700' : h.status === 'FAILED' ? 'bg-red-50 text-red-700' : 'bg-surface-100 text-surface-600'}`}>
                      {h.status.toLowerCase()}
                    </span>
                  </div>
                  <p className="mt-1 text-xs text-surface-400">by {h.uploadedBy} · {formatDateTime(h.uploadedAt)}</p>
                  <p className="mt-0.5 text-xs text-surface-400">
                    {h.totalRows} rows · {h.validRows} valid · {h.invalidRows} invalid · {h.duplicateRows} dup · <span className="font-medium text-[#01A982]">{h.importedRows} imported</span>
                  </p>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
      )}
    </div>
  )
}