import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { AlertTriangle, CalendarRange, CheckCircle2, FileSpreadsheet, UploadCloud } from 'lucide-react'
import toast from 'react-hot-toast'
import type { RosterImportPreview } from '@/types'
import { adminApi } from '@/api'
import { extractMessage } from '@/api/client'
import { useTeam } from '@/hooks/useTeam'
import { RosterLegend, monthLabel } from '@/components/roster/RosterLegend'
import { rosterStatusClass } from '@/constants/rosterStatus'
import { Button } from '@/components/ui/Button'

const ROW_STATUS_STYLES: Record<string, string> = {
  NEW: 'bg-emerald-50 text-emerald-700',
  EXISTING: 'bg-amber-50 text-amber-700',
  DUPLICATE: 'bg-amber-50 text-amber-700',
  INVALID: 'bg-red-50 text-red-700',
}

function daysSorted(days: Record<string, string>): string[] {
  return Object.keys(days).sort()
}

export function RosterImportPanel({ initialFile }: { initialFile?: File | null }) {
  const { teams } = useTeam()
  const queryClient = useQueryClient()
  const fileRef = useRef<HTMLInputElement>(null)
  const processedRef = useRef<string | null>(null)
  const [teamId, setTeamId] = useState<number | null>(null)
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<RosterImportPreview | null>(null)
  const [result, setResult] = useState<{ imported: number; skipped: number } | null>(null)

  useEffect(() => {
    if (initialFile && teamId != null && preview == null && processedRef.current !== initialFile.name) {
      processedRef.current = initialFile.name
      previewMutation.mutate(initialFile)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [teamId, initialFile, preview])

  const previewMutation = useMutation({
    mutationFn: (file: File) => adminApi.rosterImportPreview(teamId!, file),
    onSuccess: (res) => {
      setPreview(res)
      setResult(null)
      toast.success(`Parsed ${res.rows.length} roster rows for ${monthLabel(res.month)}`)
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  const importMutation = useMutation({
    mutationFn: () => {
      if (!preview) return Promise.reject(new Error('No preview'))
      const rows = preview.rows
        .filter((r) => r.status === 'NEW')
        .map((r) => ({
          employeeCode: r.employeeCode,
          employeeName: r.employeeName || r.employeeCode,
          email: r.email,
          location: r.location,
          shift: r.shift,
          weekOff: r.weekOff,
          days: r.days,
        }))
      return adminApi.rosterSave({ teamId: preview.teamId, month: preview.month, mode: 'IMPORT', rows })
    },
    onSuccess: (res) => {
      setResult({ imported: res.imported, skipped: res.skipped })
      toast.success(`Imported ${res.imported} roster row${res.imported === 1 ? '' : 's'}`)
      queryClient.invalidateQueries({ queryKey: ['admin', 'roster'] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  const onFile = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0]
    if (f) previewMutation.mutate(f)
  }

  const dates = useMemo(() => (preview ? daysSorted(preview.rows[0]?.days ?? {}) : []), [preview])

  const reset = () => {
    setPreview(null)
    setResult(null)
    if (fileRef.current) fileRef.current.value = ''
  }

  return (
    <div className="space-y-4">
      <div className="card p-6">
        <h3 className="mb-1 text-sm font-semibold text-surface-700">Step 1 · Upload monthly roster</h3>
        <p className="mb-4 text-xs text-surface-400">
          Select the target team, then upload the team's monthly attendance roster (.xlsx / .xls).
        </p>

        <label className="mb-4 block">
          <span className="label">Target team <span className="text-red-500">*</span></span>
          <select
            className="select max-w-md"
            value={teamId ?? ''}
            onChange={(e) => setTeamId(e.target.value === '' ? null : Number(e.target.value))}
          >
            <option value="">— Select a team —</option>
            {teams.map((t) => (
              <option key={t.id} value={t.id}>{t.name}</option>
            ))}
          </select>
          {initialFile && !preview && (
            <p className="mt-1.5 flex items-center gap-1 text-xs text-surface-500">
              <UploadCloud className="h-3 w-3 text-brand-500" /> Queued: {initialFile.name} — pick a team to parse it.
            </p>
          )}
        </label>

        <label
          className={`flex cursor-pointer flex-col items-center justify-center gap-2 rounded-lg border-2 border-dashed px-6 py-10 text-center transition-colors ${
            teamId == null
              ? 'border-surface-200 bg-surface-50/60 text-surface-400'
              : 'border-surface-300 bg-surface-50 hover:border-brand-400 hover:bg-brand-50/40 dark:hover:bg-brand-500/10'
          }`}
        >
          {teamId == null ? (
            <AlertTriangle className="h-8 w-8 text-surface-300" />
          ) : (
            <UploadCloud className="h-8 w-8 text-brand-500" />
          )}
          <span className="text-sm font-medium text-surface-700">
            {teamId == null ? 'Select a team first' : 'Click to choose the roster file'}
          </span>
          <span className="text-xs text-surface-400">
            Expected columns: Employee ID · Email · Employee Name · Location · Shift · Week Off, then one column per
            day (status: WO, WFO, WFH, PL, SL, CO, FL, HPEH, ATRn).
          </span>
          <input ref={fileRef} type="file" accept=".xlsx,.xls" className="hidden" disabled={teamId == null} onChange={onFile} />
        </label>
        {previewMutation.isPending && <p className="mt-2 text-center text-xs text-surface-400">Parsing file…</p>}
      </div>

      {preview && (
        <div className="card p-6">
          <div className="flex flex-wrap items-center gap-3">
            <h3 className="text-sm font-semibold text-surface-700">Step 2 · Review roster — {monthLabel(preview.month)}</h3>
            <div className="ml-auto flex flex-wrap items-center gap-2 text-xs font-medium">
              <span className="rounded-full bg-emerald-50 px-2.5 py-1 text-emerald-700">{preview.newRows} new</span>
              <span className="rounded-full bg-amber-50 px-2.5 py-1 text-amber-700">{preview.existingRows} existing</span>
              <span className="rounded-full bg-red-50 px-2.5 py-1 text-red-700">{preview.invalidRows} invalid</span>
              <span className="rounded-full bg-surface-100 px-2.5 py-1 text-surface-600">{preview.totalRows} total</span>
            </div>
          </div>

          <div className="mt-4 flex flex-wrap items-center justify-between gap-2">
            <RosterLegend />
            <span className="text-xs text-surface-400">{preview.fileName}</span>
          </div>

          {preview.warnings.length > 0 && (
            <div className="mt-3 rounded-md bg-amber-50 px-3 py-2 text-xs text-amber-700">
              {preview.warnings.map((w) => <p key={w}>⚠ {w}</p>)}
            </div>
          )}

          <div className="mt-4 max-h-[28rem] overflow-auto rounded-lg border border-surface-200">
            <table className="min-w-full">
              <thead className="sticky top-0 z-10 border-b border-surface-200 bg-surface-50">
                <tr>
                  <th className="th sticky left-0 z-10 border-r border-surface-200 bg-surface-50">Emp ID</th>
                  <th className="th sticky left-[7.5rem] z-10 border-r border-surface-200 bg-surface-50">Name</th>
                  <th className="th">Email</th>
                  <th className="th">Loc.</th>
                  <th className="th">Shift</th>
                  <th className="th">WO</th>
                  {dates.map((d) => (
                    <th key={d} className="th px-2 text-center">{Number(d.slice(8))}</th>
                  ))}
                  <th className="th">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-surface-200">
                {preview.rows.map((r, i) => (
                  <tr key={`${r.employeeCode}-${i}`}>
                    <td className="td sticky left-0 z-0 whitespace-nowrap border-r border-surface-200 bg-surface-0 font-medium text-surface-800">{r.employeeCode || '—'}</td>
                    <td className="td sticky left-[7.5rem] z-0 whitespace-nowrap border-r border-surface-200 bg-surface-0">{r.employeeName || '—'}</td>
                    <td className="td whitespace-nowrap text-xs">{r.email || '—'}</td>
                    <td className="td whitespace-nowrap text-xs">{r.location || '—'}</td>
                    <td className="td whitespace-nowrap text-xs">{r.shift || '—'}</td>
                    <td className="td whitespace-nowrap text-xs">{r.weekOff || '—'}</td>
                    {dates.map((d) => (
                      <td key={d} className="px-1 py-1.5 text-center">
                        <span className={`roster-cell ${rosterStatusClass(r.days[d])}`}>{r.days[d] || '·'}</span>
                      </td>
                    ))}
                    <td className="td">
                      <span className={`rounded-full px-2 py-0.5 text-[10px] font-semibold ${ROW_STATUS_STYLES[r.status ?? ''] ?? 'bg-surface-100 text-surface-600'}`}>
                        {String(r.status ?? '').toLowerCase()}
                      </span>
                      {r.errors?.length ? <span className="ml-1 text-[10px] text-red-600" title={r.errors.join('; ')}>!</span> : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
            {result ? (
              <div className="flex items-center gap-2 text-sm">
                <CheckCircle2 className="h-5 w-5 text-emerald-600" />
                <span className="text-surface-700">
                  Imported <strong className="text-emerald-700">{result.imported}</strong> row{result.imported === 1 ? '' : 's'}
                  {result.skipped > 0 && <span> · skipped {result.skipped} existing</span>}
                </span>
                <Link to={`/admin/roster?teamId=${preview.teamId}&month=${preview.month}`} className="ml-2 inline-flex items-center gap-1 text-sm font-medium text-brand-600 hover:underline">
                  <CalendarRange className="h-4 w-4" /> Open in Attendance Roster
                </Link>
              </div>
            ) : (
              <Button
                onClick={() => importMutation.mutate()}
                disabled={preview.newRows === 0}
                loading={importMutation.isPending}
              >
                Import {preview.newRows} row{preview.newRows === 1 ? '' : 's'}
              </Button>
            )}
            <button
              className="text-xs text-surface-400 hover:underline"
              onClick={reset}
              disabled={importMutation.isPending}
            >
              ← Upload a different file
            </button>
          </div>
        </div>
      )}

      <div className="card p-4">
        <h3 className="mb-2 text-sm font-semibold text-surface-700">Notes</h3>
        <ul className="space-y-1 text-xs leading-relaxed text-surface-500">
          <li>• The roster is saved to the selected team for the detected month. You never overwrite existing data — employees already imported for that team &amp; month are skipped and can be edited later in the “Attendance Roster” section.</li>
          <li>• The month is read from the file (e.g. a "September 2026" header, date columns, or the sheet name). If not found it assumes the current month.</li>
          <li>• ATRn covers attrition &amp; anyone who left the team (e.g. ATR1, ATR2, …).</li>
        </ul>
      </div>
    </div>
  )
}