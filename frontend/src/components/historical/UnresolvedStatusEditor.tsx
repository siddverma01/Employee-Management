import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import { AlertTriangle, CheckCircle2 } from 'lucide-react'
import type { HistoricalStatusItem } from '@/types'
import { adminApi } from '@/api'
import { extractMessage } from '@/api/client'
import { cn } from '@/utils'
import { Button } from '@/components/ui/Button'
import { Select } from '@/components/ui/Select'
import { Pagination } from '@/components/ui/Pagination'
import { LoadingState } from '@/components/ui/LoadingState'

const PAGE_SIZE = 25

function CountTile({ label, value, tone }: {
  label: string
  value: number
  tone: 'default' | 'success' | 'muted' | 'warning'
}) {
  return (
    <div className="rounded-lg border border-surface-200 bg-surface-0 px-4 py-3">
      <div className={cn('text-2xl font-bold tabular-nums',
        tone === 'warning' ? 'text-brand-600' : tone === 'success' ? 'text-success-700' : 'text-surface-800')}>
        {value.toLocaleString()}
      </div>
      <div className="mt-0.5 text-xs font-medium text-surface-500">{label}</div>
    </div>
  )
}

/**
 * Worklist for staged historical attendance cells that cannot be trusted yet.
 *
 * Every entry keeps the value the workbook actually contained, so a correction is
 * always visibly a correction and never looks like the file said so. Correcting
 * replaces only this import's staged row; the workbook itself is never touched and
 * no attendance record exists until the import is committed.
 */
export function UnresolvedStatusEditor({ importId, statuses, onChanged }: {
  importId: number
  statuses: HistoricalStatusItem[]
  onChanged: () => void
}) {
  const queryClient = useQueryClient()
  const [page, setPage] = useState(0)
  const [draft, setDraft] = useState<Record<number, string>>({})
  const [busyRow, setBusyRow] = useState<number | null>(null)

  const queryKey = ['hist-unresolved', importId, page] as const
  const { data, isLoading, isFetching } = useQuery({
    queryKey,
    queryFn: () => adminApi.historicalUnresolved(importId, page, PAGE_SIZE),
  })

  const invalidate = async () => {
    await queryClient.invalidateQueries({ queryKey: ['hist-unresolved', importId] })
    onChanged()
  }

  const correct = useMutation({
    mutationFn: (v: { rowId: number; status: string }) =>
      adminApi.historicalCorrectRow(importId, v.rowId, v.status),
    onSuccess: async (res) => {
      toast.success(`${res.entry.employeeId} · ${res.entry.cellRef ?? 'entry'} → ${res.entry.incomingStatus}`)
      setDraft((d) => {
        const next = { ...d }
        delete next[res.entry.id]
        return next
      })
      await invalidate()
    },
    onError: (e) => toast.error(extractMessage(e)),
    onSettled: () => setBusyRow(null),
  })

  const skip = useMutation({
    mutationFn: (v: { rowId: number; reason?: string }) =>
      adminApi.historicalSkipRow(importId, v.rowId, v.reason),
    onSuccess: async () => {
      toast.success('Entry skipped — it will not be imported')
      await invalidate()
    },
    onError: (e) => toast.error(extractMessage(e)),
    onSettled: () => setBusyRow(null),
  })

  const summary = data?.summary
  const entries = data?.entries ?? []
  const totalPages = data ? Math.ceil(data.totalEntries / PAGE_SIZE) : 0

  const submitCorrection = (rowId: number) => {
    const status = draft[rowId]
    if (!status) return
    setBusyRow(rowId)
    correct.mutate({ rowId, status })
  }

  return (
    <div className="space-y-4">
      {summary && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          <CountTile label="Needing review" value={summary.remaining} tone="warning" />
          <CountTile label="Corrected" value={summary.corrected} tone="success" />
          <CountTile label="Skipped" value={summary.skipped} tone="muted" />
          <CountTile label="Total flagged" value={summary.total} tone="default" />
        </div>
      )}

      {summary && summary.remaining === 0 ? (
        <div className="flex items-center gap-2 rounded-lg border border-success-2000/30 bg-success-50 px-4 py-3 text-sm text-success-700">
          <CheckCircle2 className="h-4 w-4 shrink-0" />
          Every flagged entry has been dealt with — {summary.corrected.toLocaleString()} corrected,
          {' '}{summary.skipped.toLocaleString()} skipped. You can continue to the preview.
        </div>
      ) : (
        <div className="flex items-start gap-2 rounded-lg border border-warning-2000/30 bg-warning-50/60 px-4 py-3 text-sm text-warning-700">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            Each entry below needs a decision before this import can be committed. Pick the status the cell
            should have held, or skip the entry to leave it out. Blank cells count as flagged: an empty cell
            would otherwise become a silently missing attendance record.
          </span>
        </div>
      )}

      {isLoading ? (
        <LoadingState label="Loading flagged entries…" />
      ) : entries.length === 0 ? null : (
        <div className="card overflow-hidden">
          <div className="overflow-x-auto">
            <table className="min-w-full border-collapse">
              <thead>
                <tr className="border-b border-surface-200">
                  <th className="th">Cell</th>
                  <th className="th">Employee</th>
                  <th className="th">Date</th>
                  <th className="th">In the workbook</th>
                  <th className="th">Why flagged</th>
                  <th className="th">Correct to</th>
                  <th className="th" />
                </tr>
              </thead>
              <tbody className="divide-y divide-surface-100">
                {entries.map((e) => {
                  const busy = busyRow === e.id || isFetching
                  const chosen = draft[e.id] ?? ''
                  return (
                    <tr key={e.id} className="hover:bg-surface-50/60">
                      <td className="td font-mono text-xs text-surface-500">{e.cellRef ?? '—'}</td>
                      <td className="td">
                        <div className="font-medium text-surface-800">{e.employeeId}</div>
                        {e.employeeName && <div className="text-xs text-surface-500">{e.employeeName}</div>}
                      </td>
                      <td className="td whitespace-nowrap text-surface-600">{e.attendanceDate ?? '—'}</td>
                      <td className="td">
                        {e.originalStatus ? (
                          <span className="inline-flex items-center rounded-full bg-error-50 px-2.5 py-0.5 text-xs font-semibold text-error-600 ring-1 ring-inset ring-red-200">
                            {e.originalStatus}
                          </span>
                        ) : (
                          <span className="inline-flex items-center rounded-full bg-surface-100 px-2.5 py-0.5 text-xs font-medium text-surface-600">
                            blank
                          </span>
                        )}
                      </td>
                      <td className="td max-w-xs text-xs text-surface-600">{e.issue}</td>
                      <td className="td">
                        <Select
                          aria-label={`Status for ${e.employeeId} ${e.cellRef ?? ''}`}
                          className="w-48 py-1.5"
                          placeholder="Choose a status…"
                          value={chosen}
                          onChange={(ev) => setDraft((d) => ({ ...d, [e.id]: ev.target.value }))}
                          options={statuses.map((s) => ({ value: s.code, label: `${s.code} — ${s.name}` }))}
                        />
                      </td>
                      <td className="td">
                        <div className="flex items-center justify-end gap-2">
                          <Button
                            size="sm"
                            variant="secondary"
                            disabled={!chosen || busy}
                            loading={busyRow === e.id && correct.isPending}
                            onClick={() => submitCorrection(e.id)}
                          >
                            Correct
                          </Button>
                          <Button
                            size="sm"
                            variant="ghost"
                            disabled={busy}
                            loading={busyRow === e.id && skip.isPending}
                            onClick={() => {
                              setBusyRow(e.id)
                              skip.mutate({ rowId: e.id })
                            }}
                          >
                            Skip
                          </Button>
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <Pagination
            page={page}
            size={PAGE_SIZE}
            totalElements={data?.totalEntries ?? 0}
            totalPages={totalPages}
            onPageChange={setPage}
          />
        </div>
      )}
    </div>
  )
}