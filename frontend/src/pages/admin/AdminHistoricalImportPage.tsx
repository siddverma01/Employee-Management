import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import {
  AlertTriangle, CheckCircle2, ChevronLeft, ChevronRight, Download, FileSpreadsheet, GitMerge,
  History, Tag, UploadCloud, XCircle,
} from 'lucide-react'
import type {
  HistoricalCommitResponse, HistoricalHistoryItem, HistoricalInspectResponse, HistoricalPreviewResponse,
  HistoricalRowView, HistoricalSheetAnalysis, HistoricalStatusItem, HistoricalUnknownCode,
  HistoricalUnknownCodeDetail, HistoricalValidationIssue,
} from '@/types'
import { adminApi } from '@/api'
import { extractMessage } from '@/api/client'
import { cn, formatDate, formatDateTime } from '@/utils'
import { formatShiftDisplay } from '@/utils/shift'
import { useTeam } from '@/hooks/useTeam'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'
import { Pagination } from '@/components/ui/Pagination'
import { Input } from '@/components/ui/Input'
import { Select } from '@/components/ui/Select'

const ACTION_STYLES: Record<string, string> = {
  INSERT: 'bg-emerald-50 text-emerald-700 ring-1 ring-inset ring-emerald-200',
  UPDATE: 'bg-sky-50 text-sky-700 ring-1 ring-inset ring-sky-200',
  DUPLICATE: 'bg-amber-50 text-amber-700 ring-1 ring-inset ring-amber-200',
  INVALID: 'bg-red-50 text-red-600 ring-1 ring-inset ring-red-200',
}

function ActionBadge({ action }: { action: string }) {
  return (
    <span className={cn('inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold',
      ACTION_STYLES[action] ?? 'bg-gray-50 text-gray-600')}>
      {action}
    </span>
  )
}

function StatusPill({ code, unknown }: { code: string; unknown?: boolean }) {
  return (
    <span className={cn(
      'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold',
      unknown
        ? 'bg-red-50 text-red-600 ring-1 ring-inset ring-red-200'
        : 'bg-emerald-50 text-emerald-700 ring-1 ring-inset ring-emerald-200',
    )}>
      {unknown && <AlertTriangle className="mr-1 h-3 w-3" />}
      {code}
    </span>
  )
}

function SummaryStat({ label, value, accent }: { label: string; value: number | string; accent?: boolean }) {
  return (
    <div className="rounded-lg border border-surface-200 bg-surface-0 px-4 py-3">
      <div className={cn('text-2xl font-bold tabular-nums', accent ? 'text-brand-600' : 'text-surface-800')}>
        {value.toLocaleString()}
      </div>
      <div className="mt-0.5 text-xs font-medium text-surface-500">{label}</div>
    </div>
  )
}

function formatBytes(n: number) {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(2)} MB`
}

const STEPS = ['Upload', 'Analysis', 'Validation', 'Status Mapping', 'Preview', 'Import', 'Result'] as const
const IMPORT_STAGES = [
  'Reading workbook…',
  'Analyzing sheets…',
  'Normalizing employees…',
  'Normalizing attendance…',
  'Checking duplicates…',
  'Saving records…',
]

// ------------------------------------------------------------------ wizard steps

function StepIndicator({ step, maxClickable, onStep }: {
  step: number; maxClickable: number; onStep: (s: number) => void;
}) {
  return (
    <ol className="mb-6 flex flex-wrap items-center gap-y-2">
      {STEPS.map((label, i) => {
        const n = i + 1
        const active = n === step
        const done = n < step
        const clickable = n < maxClickable
        return (
          <li key={label} className="flex items-center">
            {i > 0 && <span className={cn('mx-2 h-px w-6', n <= step ? 'bg-emerald-400' : 'bg-surface-200')} />}
            <button
              type="button"
              disabled={!clickable}
              onClick={() => clickable && onStep(n)}
              className={cn('flex items-center gap-1.5 text-xs font-medium',
                active ? 'text-brand-600' : clickable ? 'text-emerald-600 hover:text-brand-600' : 'text-surface-400')}
            >
              <span className={cn(
                'flex h-5 w-5 items-center justify-center rounded-full text-[10px] font-bold',
                active
                  ? 'bg-brand-500 text-white'
                  : done
                    ? 'bg-emerald-100 text-emerald-700'
                    : 'bg-surface-100 text-surface-400',
              )}>
                {done ? <CheckCircle2 className="h-3 w-3" /> : n}
              </span>
              <span className={cn(active && 'font-semibold')}>{label}</span>
            </button>
          </li>
        )
      })}
    </ol>
  )
}

function AnalysisTable({ analysis }: { analysis: HistoricalSheetAnalysis[] }) {
  return (
    <div className="overflow-hidden rounded-lg border border-gray-200">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-gray-50 text-left">
              {['Sheet Name', 'Detected Month', 'Header Row', 'Employee Columns', 'Date Columns',
                'Employees', 'Attendance Cells', 'Unknown Codes', 'Warnings', 'Importability'].map((h) => (
                <th key={h} className="px-4 py-3 text-xs font-semibold uppercase tracking-wide text-gray-500">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {analysis.map((a) => (
              <tr key={a.sheetName} className="align-top hover:bg-surface-50">
                <td className="px-4 py-3 font-medium text-surface-800">{a.sheetName}</td>
                <td className="whitespace-nowrap px-4 py-3 text-gray-700">{a.month ?? '—'}</td>
                <td className="px-4 py-3 text-gray-700">{a.headerRow}</td>
                <td className="px-4 py-3 text-gray-700">{a.employeeColumns}</td>
                <td className="px-4 py-3 text-gray-700">{a.dateColumns}</td>
                <td className="px-4 py-3 text-gray-700">{a.employeeCount}</td>
                <td className="px-4 py-3 text-gray-700">{a.cellCount}</td>
                <td className={cn('px-4 py-3', a.unknownCodeCount > 0 ? 'font-semibold text-red-600' : 'text-gray-700')}>
                  {a.unknownCodeCount}
                </td>
                <td className={cn('max-w-[240px] px-4 py-3 text-xs', a.warnings.length > 0 ? 'text-amber-600' : 'text-gray-400')}>
                  {a.warnings.length > 0 ? (
                    <ul className="space-y-1">
                      {a.warnings.map((w, i) => <li key={i} className="flex items-start gap-1"><AlertTriangle className="mt-0.5 h-3 w-3 shrink-0" />{w}</li>)}
                    </ul>
                  ) : 'None'}
                </td>
                <td className="px-4 py-3">
                  {a.skipped ? (
                    <span className="rounded-full bg-gray-100 px-2.5 py-0.5 text-xs font-semibold text-gray-500" title={a.skipReason ?? undefined}>
                      Skipped
                    </span>
                  ) : a.importable ? (
                    <span className="rounded-full bg-emerald-50 px-2.5 py-0.5 text-xs font-semibold text-emerald-700">Importable</span>
                  ) : (
                    <span className="rounded-full bg-red-50 px-2.5 py-0.5 text-xs font-semibold text-red-600">Blocked</span>
                  )}
                </td>
              </tr>
            ))}
            {analysis.length === 0 && (
              <tr><td colSpan={10} className="px-4 py-8 text-center text-surface-500">No sheets were analysed.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function ValidationPanel({ issues, onImportDisabled }: { issues: HistoricalValidationIssue[]; onImportDisabled: boolean }) {
  const errors = issues.filter((i) => i.severity === 'ERROR')
  const warnings = issues.filter((i) => i.severity === 'WARN')
  const info = issues.filter((i) => i.severity === 'INFO')

  const row = (i: HistoricalValidationIssue) => (
    <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 text-sm">
      <span className="font-medium text-surface-800">{i.message}</span>
      {i.sheetName && <span className="text-xs text-surface-500">sheet: {i.sheetName}</span>}
      {i.row != null && <span className="text-xs text-surface-500">row: {i.row}</span>}
      {i.employeeId && <span className="font-mono text-xs text-surface-500">emp: {i.employeeId}</span>}
      {i.count > 1 && <span className="text-xs text-surface-400">×{i.count}</span>}
    </div>
  )

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-3 gap-3">
        <SummaryStat label="Fatal errors" value={errors.length} accent={errors.length > 0} />
        <SummaryStat label="Warnings" value={warnings.length} accent={warnings.length > 0} />
        <SummaryStat label="Info" value={info.length} />
      </div>

      {errors.length > 0 && (
        <div className="rounded-lg border border-red-200 bg-red-50/60">
          <div className="flex items-center gap-2 border-b border-red-100 px-4 py-2.5 text-sm font-semibold text-red-700">
            <XCircle className="h-4 w-4" /> Fatal errors — these must be fixed by editing the workbook before importing
          </div>
          <ul className="divide-y divide-red-100/60 px-4">
            {errors.map((e, i) => <li key={i} className="py-2">{row(e)}</li>)}
          </ul>
        </div>
      )}

      {warnings.length > 0 && (
        <div className="rounded-lg border border-amber-200 bg-amber-50/60">
          <div className="flex items-center gap-2 border-b border-amber-100 px-4 py-2.5 text-sm font-semibold text-amber-700">
            <AlertTriangle className="h-4 w-4" /> Warnings — you can continue, but review these before importing
          </div>
          <ul className="divide-y divide-amber-100/60 px-4">
            {warnings.map((w, i) => <li key={i} className="py-2">{row(w)}</li>)}
          </ul>
        </div>
      )}

      {info.length > 0 && (
        <div className="rounded-lg border border-sky-200 bg-sky-50/60">
          <div className="px-4 py-3 text-sm text-sky-700">
            {info.map((n, i) => <div key={i}>{n.message}</div>)}
          </div>
        </div>
      )}

      {issues.length === 0 && (
        <div className="flex items-center gap-2 rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">
          <CheckCircle2 className="h-4 w-4" /> No validation issues detected — this workbook looks clean.
        </div>
      )}

      {onImportDisabled && (
        <div className="flex items-center gap-2 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          <XCircle className="h-4 w-4" />
          Import is blocked while the workbook has fatal errors. Fix the source file and re-upload.
        </div>
      )}
    </div>
  )
}

function StatusMappingStep({ preview, statuses, onApplied, applying }: {
  preview: HistoricalPreviewResponse; statuses: HistoricalStatusItem[];
  onApplied: () => void; applying: boolean;
}) {
  const [draft, setDraft] = useState<Record<string, string>>({})
  const [single, setSingle] = useState<string | null>(null)

  const known = statuses.map((s) => s.code)
  const unknown = preview.unknownCodes

  const apply = async (code: string, to?: string) => {
    const target = to ?? draft[code]
    if (!target || target === 'KEEP') return
    setSingle(code)
    try {
      const res = await adminApi.historicalMapStaged(preview.importId, { from: code, to: target })
      toast.success(`Mapped ${res.mapped.toLocaleString()} record(s) to ${res.to ?? ''}`)
      setDraft((d) => { const next = { ...d }; delete next[code]; return next })
      onApplied()
    } catch (e) {
      toast.error(extractMessage(e))
    } finally {
      setSingle(null)
    }
  }

  const applyAll = async () => {
    const entries = Object.entries(draft).filter(([, to]) => to && to !== 'KEEP')
    if (entries.length === 0) return
    let mapped = 0
    try {
      for (const [from, to] of entries) {
        const res = await adminApi.historicalMapStaged(preview.importId, { from, to })
        mapped += res.mapped
      }
      toast.success(`Mapped ${mapped.toLocaleString()} record(s)`)
      setDraft({})
      onApplied()
    } catch (e) {
      toast.error(extractMessage(e))
    }
  }

  if (unknown.length === 0) {
    return (
      <div className="flex items-center gap-2 rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-700">
        <CheckCircle2 className="h-4 w-4" /> All status codes in this workbook are already recognised — nothing to map.
      </div>
    )
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-surface-600">
        {unknown.length} unknown code(s) were found. Map each code to a recognized status, or keep it as unknown so it
        is preserved in the record without being normalised.
      </p>
      <div className="overflow-hidden rounded-lg border border-gray-200">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="bg-gray-50 text-left">
                {['Unknown Code', 'Example Value', 'Suggested Meaning', 'Map To', ''].map((h) => (
                  <th key={h} className="px-4 py-3 text-xs font-semibold uppercase tracking-wide text-gray-500">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {unknown.map((u: HistoricalUnknownCodeDetail) => (
                <tr key={u.code} className="hover:bg-surface-50">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <Tag className="h-4 w-4 text-red-500" />
                      <span className="font-mono font-semibold text-red-700">{u.code}</span>
                      <span className="text-xs text-surface-500">{u.count.toLocaleString()} record(s)</span>
                    </div>
                  </td>
                  <td className="px-4 py-3 font-mono text-xs text-surface-600">{u.example ?? '—'}</td>
                  <td className="max-w-[220px] px-4 py-3 text-xs text-surface-600">{u.suggested ?? 'No suggestion'}</td>
                  <td className="px-4 py-3">
                    <Select
                      className="w-48 py-1.5"
                      placeholder="Keep as unknown"
                      value={draft[u.code] ?? ''}
                      onChange={(e) => setDraft((d) => ({ ...d, [u.code]: e.target.value }))}
                      options={known.map((s) => ({ value: s, label: s }))}
                    />
                  </td>
                  <td className="px-4 py-3">
                    <Button size="sm" variant="secondary" disabled={applying || !draft[u.code] || draft[u.code] === 'KEEP'}
                      loading={single === u.code} onClick={() => apply(u.code)}>
                      Apply
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="flex items-center justify-between">
        <p className="text-xs text-surface-500">
          Using “Keep as unknown” records the raw code without mapping it to a known status.
        </p>
        <Button variant="secondary" disabled={applying}
          onClick={applyAll}>
          Apply all mappings
        </Button>
      </div>
    </div>
  )
}

function WizardPreviewTable({ rows }: { rows: HistoricalRowView[] }) {
  return (
    <div className="overflow-hidden rounded-lg border border-gray-200">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-gray-50 text-left">
              {['Employee ID', 'Employee Name', 'Date', 'Status', 'Location', 'Shift', 'Source Sheet', 'Source Row'].map((h) => (
                <th key={h} className="px-4 py-3 text-xs font-semibold uppercase tracking-wide text-gray-500">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {rows.length === 0 && (
              <tr><td colSpan={8} className="px-4 py-8 text-center text-surface-500">No preview rows available.</td></tr>
            )}
            {rows.map((r) => (
              <tr key={r.id} className="hover:bg-surface-50">
                <td className="px-4 py-3 font-mono text-gray-700">{r.employeeId}</td>
                <td className="max-w-[200px] px-4 py-3 truncate text-gray-700">{r.employeeName ?? '—'}</td>
                <td className="whitespace-nowrap px-4 py-3 text-gray-700">{formatDate(r.attendanceDate) ?? '—'}</td>
                <td className="px-4 py-3"><StatusPill code={r.incomingStatus} unknown={r.unknown} /></td>
                <td className="max-w-[160px] truncate px-4 py-3 text-gray-600">{r.location ?? '—'}</td>
                <td className="max-w-[160px] truncate px-4 py-3 text-gray-600">{formatShiftDisplay(r.shift)}</td>
                <td className="px-4 py-3 text-gray-600">{r.sheetName}</td>
                <td className="px-4 py-3 text-gray-600">{r.sourceRow}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function ResultStep({ result, importId, onImportDetails, showDetails, onDownload, onClose }: {
  result: HistoricalCommitResponse; importId: number;
  onImportDetails: () => void; showDetails: boolean; onDownload: () => void; onClose: () => void;
}) {
  const r = result.result
  const stats = [
    { label: 'Employees', value: r.employees },
    { label: 'Attendance Records', value: r.attendanceRecords },
    { label: 'Inserted', value: r.inserted, accent: true },
    { label: 'Updated', value: r.updated, accent: true },
    { label: 'Duplicates skipped', value: r.duplicatesSkipped },
    { label: 'Warnings', value: r.warnings, accent: r.warnings > 0 },
    { label: 'Unknown statuses', value: r.unknownStatuses, accent: r.unknownStatuses > 0 },
    { label: 'Failed rows', value: r.failedRows, accent: r.failedRows > 0 },
  ]
  return (
    <div className="space-y-5">
      <div className="flex items-start gap-3 rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3">
        <CheckCircle2 className="mt-0.5 h-5 w-5 shrink-0 text-emerald-600" />
        <div>
          <p className="text-sm font-semibold text-emerald-800">Import completed successfully</p>
          <p className="text-xs text-emerald-700">
            {result.originalFileName} was committed in a single transaction ({result.committedRows.toLocaleString()} rows).
          </p>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {stats.map((it) => <SummaryStat key={it.label} {...it} />)}
      </div>

      {showDetails && result.summary.sheetDetails.length > 0 && (
        <AnalysisTable analysis={result.summary.sheetDetails} />
      )}

      <div className="flex flex-wrap items-center gap-2">
        <Button variant="secondary" onClick={onImportDetails}>
          View Import Details
        </Button>
        <Button variant="secondary" onClick={onDownload}>
          <Download className="h-4 w-4" /> Download Error Report
        </Button>
        <div className="flex-1" />
        <Button onClick={onClose}>Close</Button>
      </div>
    </div>
  )
}

// ------------------------------------------------------------------ secondary tabs

function HistoryPanel({ history }: { history: HistoricalHistoryItem[] }) {
  return (
    <div className="overflow-hidden rounded-lg border border-gray-200">
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="bg-gray-50 text-left">
              {['File', 'Imported at', 'Sheets', 'Employees', 'Records', 'New', 'Updated', 'Dup', 'Unknown', 'Invalid', 'Status'].map((h) => (
                <th key={h} className="px-4 py-3 text-xs font-semibold uppercase tracking-wide text-gray-500">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {history.map((h: HistoricalHistoryItem) => (
              <tr key={h.id} className="hover:bg-surface-50">
                <td className="max-w-[220px] truncate px-4 py-3 font-medium text-gray-700">{h.originalFileName}</td>
                <td className="whitespace-nowrap px-4 py-3 text-gray-600">{formatDateTime(h.importedAt)}</td>
                <td className="px-4 py-3 text-gray-600">{h.sheetsImported}/{h.totalSheets}</td>
                <td className="px-4 py-3 text-gray-600">{h.employeesDetected}</td>
                <td className="px-4 py-3 text-gray-600">{h.recordsDetected}</td>
                <td className="px-4 py-3 text-emerald-700">{h.newRecords}</td>
                <td className="px-4 py-3 text-sky-700">{h.updatedRecords}</td>
                <td className="px-4 py-3 text-amber-600">{h.duplicateRecords}</td>
                <td className={cn('px-4 py-3', h.unknownCodes > 0 ? 'font-semibold text-red-600' : 'text-gray-600')}>{h.unknownCodes}</td>
                <td className={cn('px-4 py-3', h.invalidRows > 0 ? 'font-semibold text-red-600' : 'text-gray-600')}>{h.invalidRows}</td>
                <td className="px-4 py-3"><ActionBadge action={h.status} /></td>
              </tr>
            ))}
            {history.length === 0 && (
              <tr><td colSpan={11} className="px-4 py-10 text-center text-surface-500">No imports yet.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function UnknownCodesPanel({ statuses, onMapped }: { statuses: HistoricalStatusItem[]; onMapped: () => void }) {
  const queryClient = useQueryClient()
  const { data: codes, isLoading } = useQuery({
    queryKey: ['hist-unknown-codes'],
    queryFn: adminApi.historicalUnknownCodes,
  })
  const [target, setTarget] = useState('')
  const mutation = useMutation({
    mutationFn: (payload: { from: string; to: string }) => adminApi.historicalMapUnknown(payload.from, payload.to),
    onSuccess: (res) => {
      toast.success(`Mapped ${res.mapped} record(s) to ${res.to}`)
      queryClient.invalidateQueries({ queryKey: ['hist-unknown-codes'] })
      onMapped()
    },
    onError: (e) => toast.error(extractMessage(e)),
  })

  const known = useMemo(() => new Set(statuses.map((s) => s.code)), [statuses])
  const unknown = (codes ?? []).filter((c) => !known.has(c.code))

  if (isLoading) return <LoadingState />
  if (!codes || codes.length === 0) {
    return <div className="text-sm text-surface-500">No unknown status codes to review. All imported codes are recognised.</div>
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        {unknown.map((c: HistoricalUnknownCode) => (
          <div key={c.code} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-red-100 bg-red-50/50 px-4 py-2.5">
            <div className="flex items-center gap-2">
              <Tag className="h-4 w-4 text-red-500" />
              <span className="font-mono text-sm font-semibold text-red-700">{c.code}</span>
              <span className="text-xs text-surface-500">{c.count} record(s)</span>
            </div>
            <div className="flex items-center gap-2">
              <Select value={target} onChange={(e) => setTarget(e.target.value)} className="w-44"
                placeholder="Map to…"
                options={statuses.filter((s) => s.code !== c.code)
                  .map((s) => ({ value: s.code, label: `${s.code} — ${s.name}` }))} />
              <Button size="sm" variant="secondary" disabled={!target}
                loading={mutation.isPending}
                onClick={() => mutation.mutate({ from: c.code, to: target })}>
                Apply
              </Button>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

function RecordsBrowser() {
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [empCode, setEmpCode] = useState('')
  const [page, setPage] = useState(0)
  const [applied, setApplied] = useState<Record<string, unknown> | null>(null)

  const { data, isLoading } = useQuery({
    queryKey: ['hist-records', applied, page],
    queryFn: () => adminApi.historicalRecords({ ...(applied ?? {}), page, size: 50 }),
    enabled: !!applied,
  })

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3 rounded-lg border border-surface-200 bg-surface-0 p-4">
        <label className="text-sm text-surface-600">
          From
          <Input type="date" className="mt-1" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label className="text-sm text-surface-600">
          To
          <Input type="date" className="mt-1" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        <label className="text-sm text-surface-600">
          Employee code
          <Input className="mt-1" placeholder="e.g. 25106149" value={empCode} onChange={(e) => setEmpCode(e.target.value)} />
        </label>
        <Button variant="secondary"
          onClick={() => { setPage(0); setApplied({ from: from || undefined, to: to || undefined, employeeId: empCode || undefined }) }}>
          Search
        </Button>
        <Button variant="ghost" onClick={() => { setFrom(''); setTo(''); setEmpCode(''); setApplied(null) }}>Clear</Button>
      </div>
      {!applied ? (
        <div className="text-sm text-surface-500">Choose a date range (and optionally an employee code) to browse normalised attendance records.</div>
      ) : isLoading ? <LoadingState /> : data && data.records.length === 0 ? (
        <EmptyState title="No records" description="Nothing found for the selected filter." />
      ) : data ? (
        <div className="overflow-hidden rounded-lg border border-gray-200">
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="bg-gray-50 text-left">
                  {['Date', 'Employee', 'Status', 'Source sheet', 'Source row', 'Source file', 'Imported'].map((h) => (
                    <th key={h} className="px-4 py-3 text-xs font-semibold uppercase tracking-wide text-gray-500">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {data.records.map((r) => (
                  <tr key={r.id} className="hover:bg-surface-50">
                    <td className="whitespace-nowrap px-4 py-3 text-gray-700">{formatDate(r.attendanceDate)}</td>
                    <td className="max-w-[200px] px-4 py-3">
                      <div className="truncate text-gray-700">{r.employeeId}</div>
                      {r.employeeName && <div className="truncate text-xs text-surface-500">{r.employeeName}</div>}
                    </td>
                    <td className="px-4 py-3"><StatusPill code={r.statusCode} unknown={r.unknown} /></td>
                    <td className="px-4 py-3 text-gray-600">{r.sourceSheet ?? '—'}</td>
                    <td className="px-4 py-3 text-gray-600">{r.sourceRow ?? '—'}</td>
                    <td className="max-w-[180px] truncate px-4 py-3 text-xs text-surface-500">{r.sourceFile ?? '—'}</td>
                    <td className="px-4 py-3 text-gray-600">{formatDateTime(r.importedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={data.page} size={data.size} totalElements={data.total}
            totalPages={Math.max(1, Math.ceil(data.total / data.size))} onPageChange={setPage} />
        </div>
      ) : null}
    </div>
  )
}

// ------------------------------------------------------------------ page

export default function AdminHistoricalImportPage() {
  const queryClient = useQueryClient()
  const { teams } = useTeam()
  const timerRef = useRef<number | null>(null)

  const [tab, setTab] = useState<'import' | 'history' | 'unknown' | 'records'>('import')
  const [step, setStep] = useState(1)
  const [file, setFile] = useState<File | null>(null)
  const [inspect, setInspect] = useState<HistoricalInspectResponse | null>(null)
  const [preview, setPreview] = useState<HistoricalPreviewResponse | null>(null)
  const [result, setResult] = useState<HistoricalCommitResponse | null>(null)
  const [dragOver, setDragOver] = useState(false)
  const [page, setPage] = useState(0)
  const [commitTeamId, setCommitTeamId] = useState<number | ''>('')
  const [progressStage, setProgressStage] = useState(-1)
  const [showDetails, setShowDetails] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)

  const { data: statuses } = useQuery({ queryKey: ['hist-statuses'], queryFn: adminApi.historicalStatuses })
  const { data: history, refetch: refetchHistory } = useQuery({
    queryKey: ['hist-history'],
    queryFn: adminApi.historicalHistory,
  })

  useEffect(() => () => {
    if (timerRef.current) window.clearInterval(timerRef.current)
  }, [])

  const inspectMutation = useMutation({
    mutationFn: (f: File) => adminApi.historicalInspect(f),
    onSuccess: (p) => setInspect(p),
    onError: (e) => toast.error(extractMessage(e)),
  })

  const analyzeMutation = useMutation({
    mutationFn: (f: File) => adminApi.historicalPreview(f),
    onSuccess: (p) => {
      setPreview(p)
      setPage(0)
      setStep(2)
      toast.success('Workbook analyzed — review the sheet analysis before continuing.')
    },
    onError: (e) => toast.error(extractMessage(e)),
  })

  const commitMutation = useMutation({
    mutationFn: ({ id, teamId }: { id: number; teamId?: number | null }) => adminApi.historicalCommit(id, teamId),
    onSuccess: (c) => {
      if (timerRef.current) window.clearInterval(timerRef.current)
      setProgressStage(IMPORT_STAGES.length)
      setResult(c)
      setShowDetails(false)
      setStep(7)
      queryClient.invalidateQueries({ queryKey: ['hist-history'] })
      queryClient.invalidateQueries({ queryKey: ['hist-unknown-codes'] })
      toast.success(`Committed ${c.committedRows.toLocaleString()} record(s).`)
    },
    onError: (e) => {
      if (timerRef.current) window.clearInterval(timerRef.current)
      setProgressStage(-1)
      toast.error(extractMessage(e))
    },
  })

  const onFile = (f: File | undefined) => {
    if (!f) return
    if (!/\.(xlsx|xls)$/i.test(f.name)) {
      toast.error('Only .xlsx and .xls files are supported')
      return
    }
    setFile(f)
    setInspect(null)
    setPreview(null)
    setResult(null)
    setStep(1)
    inspectMutation.mutate(f)
  }

  const refreshPreview = async (size = 100) => {
    if (!preview) return
    try {
      setPreview(await adminApi.historicalPreviewOf(preview.importId, 0, size))
    } catch (e) {
      toast.error(extractMessage(e))
    }
  }

  const goToPreview = async () => {
    setStep(5)
    if (!preview) return
    try {
      const p = await adminApi.historicalPreviewOf(preview.importId, 0, 100)
      setPreview(p)
    } catch (e) {
      toast.error(extractMessage(e))
    }
  }

  const startImport = () => {
    if (!preview) return
    setProgressStage(0)
    timerRef.current = window.setInterval(() => {
      setProgressStage((s) => Math.min(s + 1, IMPORT_STAGES.length - 1))
    }, 450)
    commitMutation.mutate({
      id: preview.importId,
      teamId: commitTeamId === '' ? undefined : Number(commitTeamId),
    })
  }

  const reset = () => {
    if (timerRef.current) window.clearInterval(timerRef.current)
    setStep(1)
    setFile(null)
    setInspect(null)
    setPreview(null)
    setResult(null)
    setProgressStage(-1)
    setShowDetails(false)
    setCommitTeamId('')
    refetchHistory()
  }

  const downloadReport = async () => {
    if (!preview) return
    try {
      const blob = await adminApi.historicalReport(preview.importId)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `historical-import-${preview.importId}-report.csv`
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    } catch (e) {
      toast.error(extractMessage(e))
    }
  }

  const errors = preview?.issues.filter((i) => i.severity === 'ERROR').length ?? 0
  const importing = progressStage >= 0 && progressStage < IMPORT_STAGES.length
  const maxClickable = step

  const tabs = [
    { id: 'import', label: 'Import', icon: UploadCloud },
    { id: 'history', label: 'Import History', icon: History },
    { id: 'unknown', label: 'Unknown Codes', icon: Tag },
    { id: 'records', label: 'Attendance Records', icon: GitMerge },
  ] as const

  return (
    <div>
      <PageHeader
        title="Import Historical Attendance"
        subtitle="Seven-step wizard: upload a workbook, review the per-sheet analysis and validation, map unknown statuses, preview every row, then commit in a single transaction."
      />

      <div className="mb-5 flex flex-wrap gap-1 border-b border-surface-200">
        {tabs.map((t) => (
          <button
            key={t.id}
            onClick={() => setTab(t.id)}
            className={cn(
              'flex items-center gap-1.5 border-b-2 px-3 py-2 text-sm font-medium transition-colors',
              tab === t.id ? 'border-brand-500 text-brand-600' : 'border-transparent text-surface-500 hover:text-surface-700',
            )}
          >
            <t.icon className="h-4 w-4" />
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'history' && <HistoryPanel history={history ?? []} />}

      {tab === 'unknown' && (
        <UnknownCodesPanel statuses={statuses ?? []}
          onMapped={() => queryClient.invalidateQueries({ queryKey: ['hist-history'] })} />
      )}

      {tab === 'records' && <RecordsBrowser />}

      {tab === 'import' && (
        <div className="space-y-6">
          <StepIndicator step={step} maxClickable={maxClickable} onStep={setStep} />

          {/* STEP 1 — Upload */}
          {step === 1 && (
            <div className="space-y-4">
              {!inspect && !inspectMutation.isPending ? (
                <div
                  onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
                  onDragLeave={() => setDragOver(false)}
                  onDrop={(e) => { e.preventDefault(); setDragOver(false); onFile(e.dataTransfer.files?.[0]) }}
                  onClick={() => fileInput.current?.click()}
                  className={cn(
                    'flex cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed px-6 py-16 text-center transition-colors',
                    dragOver ? 'border-brand-500 bg-brand-50' : 'border-surface-300 bg-surface-0 hover:border-brand-400 hover:bg-surface-50',
                  )}
                >
                  <input
                    ref={fileInput}
                    type="file"
                    accept=".xlsx,.xls"
                    className="hidden"
                    onChange={(e) => { onFile(e.target.files?.[0]); e.target.value = '' }}
                  />
                  <FileSpreadsheet className="mb-3 h-10 w-10 text-brand-500" />
                  <p className="text-sm font-medium text-surface-800">Drop a workbook here, or click to browse</p>
                  <p className="mt-1 max-w-md text-xs text-surface-500">
                    Detects header rows, identity columns, date columns and monthly sheets automatically.
                    Multi-sheet and mixed layouts are handled per sheet.
                  </p>
                </div>
              ) : inspectMutation.isPending ? (
                <LoadingState />
              ) : (
                <div className="space-y-4">
                  <div className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-surface-200 bg-surface-0 p-5">
                    <div className="flex items-start gap-3">
                      <FileSpreadsheet className="mt-0.5 h-8 w-8 text-brand-500" />
                      <div>
                        <p className="text-sm font-semibold text-surface-800">{inspect?.fileName ?? file?.name}</p>
                        <p className="mt-0.5 text-xs text-surface-500">
                          {file && formatBytes(file.size)} · {inspect?.sheetCount ?? 0} sheet(s)
                        </p>
                        {inspect && inspect.sheets.length > 0 && (
                          <div className="mt-2 flex flex-wrap gap-1">
                            {inspect.sheets.map((s) => (
                              <span key={s} className="rounded-full bg-surface-100 px-2.5 py-0.5 text-xs font-medium text-surface-600">
                                {s}
                              </span>
                            ))}
                          </div>
                        )}
                      </div>
                    </div>
                    <Button variant="ghost" size="sm" onClick={() => { fileInput.current?.click() }}>Choose another file</Button>
                  </div>
                  <p className="text-xs text-surface-500">
                    The workbook was read successfully. Click “Analyze Workbook” to detect sheets, header rows, employee
                    and date columns, and build the staged import.
                  </p>
                  <div className="flex items-center justify-end gap-2">
                    <Button onClick={() => analyzeMutation.mutate(file!)} loading={analyzeMutation.isPending}>
                      Analyze Workbook
                      <ChevronRight className="h-4 w-4" />
                    </Button>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* STEP 2 — Analysis */}
          {step === 2 && preview && (
            <div className="space-y-5">
              <div className="flex items-start gap-2 rounded-lg border border-surface-200 bg-surface-0 px-4 py-3">
                <FileSpreadsheet className="mt-0.5 h-5 w-5 shrink-0 text-brand-500" />
                <div className="text-sm text-surface-600">
                  <span className="font-semibold text-surface-800">{preview.originalFileName}</span> — {preview.summary.sheetsImported}
                  /{preview.summary.totalSheets} sheet(s) readable, {preview.summary.employeesDetected} employee(s),
                  {preview.summary.recordsDetected.toLocaleString()} attendance record(s) detected.
                </div>
              </div>
              <AnalysisTable analysis={preview.analysis} />
              <div className="flex items-center justify-between">
                <Button variant="ghost" onClick={() => setStep(1)}><ChevronLeft className="h-4 w-4" /> Back</Button>
                <Button onClick={() => setStep(3)}>Continue to Validation <ChevronRight className="h-4 w-4" /></Button>
              </div>
            </div>
          )}

          {/* STEP 3 — Validation */}
          {step === 3 && preview && (
            <div className="space-y-5">
              <ValidationPanel issues={preview.issues} onImportDisabled={errors > 0} />
              <div className="flex items-center justify-between">
                <Button variant="ghost" onClick={() => setStep(2)}><ChevronLeft className="h-4 w-4" /> Back</Button>
                <div className="flex items-center gap-2">
                  {errors > 0 && (
                    <p className="text-xs text-red-600">Cannot continue: {errors} fatal error(s) detected.</p>
                  )}
                  <Button onClick={() => setStep(4)} disabled={errors > 0}>
                    Continue to Status Mapping <ChevronRight className="h-4 w-4" />
                  </Button>
                </div>
              </div>
            </div>
          )}

          {/* STEP 4 — Status Mapping */}
          {step === 4 && preview && (
            <div className="space-y-5">
              <StatusMappingStep preview={preview} statuses={statuses ?? []}
                applying={false} onApplied={() => refreshPreview(100)} />
              <div className="flex items-center justify-between">
                <Button variant="ghost" onClick={() => setStep(3)}><ChevronLeft className="h-4 w-4" /> Back</Button>
                <Button onClick={goToPreview}>Continue to Preview <ChevronRight className="h-4 w-4" /></Button>
              </div>
            </div>
          )}

          {/* STEP 5 — Preview */}
          {step === 5 && preview && (
            <div className="space-y-5">
              <p className="text-sm text-surface-600">
                Showing the first {preview.rows.length.toLocaleString()} of {preview.totalRows.toLocaleString()} records.
              </p>
              <WizardPreviewTable rows={preview.rows.slice(0, 100)} />
              <div className="flex items-center justify-between">
                <Button variant="ghost" onClick={() => setStep(4)}><ChevronLeft className="h-4 w-4" /> Back</Button>
                <Button disabled={errors > 0} onClick={() => setStep(6)}>
                  Continue to Import <ChevronRight className="h-4 w-4" />
                </Button>
              </div>
            </div>
          )}

          {/* STEP 6 — Import */}
          {step === 6 && preview && (
            <div className="space-y-5">
              {result && (
                <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3">
                  <p className="text-sm text-emerald-700">This import has already been committed successfully.</p>
                  <Button size="sm" variant="secondary" onClick={() => setStep(7)}>View Result</Button>
                </div>
              )}
              <div className="rounded-lg border border-surface-200 bg-surface-0 p-5">
                <h3 className="text-sm font-semibold text-surface-800">Import Historical Attendance</h3>
                <p className="mt-1 max-w-2xl text-xs text-surface-500">
                  The import runs in a single database transaction — if anything fails midway, all staged changes are
                  rolled back so no partial data is ever left behind. Records already present are updated idempotently.
                </p>

                <div className="mt-4">
                  <label className="text-sm text-surface-600">
                    Team (optional)
                    <Select
                      className="mt-1 max-w-xs"
                      placeholder="No team"
                      value={String(commitTeamId)}
                      onChange={(e) => setCommitTeamId(e.target.value === '' ? '' : Number(e.target.value))}
                      options={(teams ?? []).map((t) => ({ value: String(t.id), label: t.name }))}
                    />
                  </label>
                </div>

                <div className="mt-5 flex items-center gap-2">
                  <Button
                    size="md"
                    loading={commitMutation.isPending}
                    disabled={errors > 0 || importing || !!result}
                    onClick={startImport}
                  >
                    <UploadCloud className="h-4 w-4" />
                    Import Historical Attendance
                  </Button>
                  {errors > 0 && (
                    <p className="text-xs text-red-600">Blocked: the workbook has {errors} fatal error(s).</p>
                  )}
                </div>
              </div>

              {progressStage >= 0 && (
                <div className="rounded-lg border border-surface-200 bg-surface-0 p-5">
                  <ul className="space-y-2">
                    {IMPORT_STAGES.map((label, i) => {
                      const done = i < progressStage
                      const active = i === progressStage
                      return (
                        <li key={label} className="flex items-center gap-2 text-sm">
                          {done ? (
                            <CheckCircle2 className="h-4 w-4 text-emerald-500" />
                          ) : active ? (
                            <span className="h-4 w-4 animate-spin rounded-full border-2 border-brand-500 border-t-transparent" />
                          ) : (
                            <span className="h-4 w-4 rounded-full border border-surface-300" />
                          )}
                          <span className={cn(done ? 'text-surface-600' : active ? 'font-medium text-surface-800' : 'text-surface-400')}>
                            {label}
                          </span>
                        </li>
                      )
                    })}
                  </ul>
                </div>
              )}

              <div className="flex justify-between">
                <Button variant="ghost" onClick={() => setStep(5)} disabled={importing}><ChevronLeft className="h-4 w-4" /> Back</Button>
              </div>
            </div>
          )}

          {/* STEP 7 — Result */}
          {step === 7 && result && (
            <ResultStep result={result} importId={result.importId}
              showDetails={showDetails}
              onImportDetails={() => setShowDetails((v) => !v)}
              onDownload={downloadReport}
              onClose={reset} />
          )}
        </div>
      )}
    </div>
  )
}