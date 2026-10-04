import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import toast from 'react-hot-toast'
import { AlertTriangle, CheckCircle2, Search, X } from 'lucide-react'
import type { HistoricalReviewCategory, HistoricalReviewState, HistoricalStatusItem } from '@/types'
import { adminApi } from '@/api'
import { extractMessage } from '@/api/client'
import { cn } from '@/utils'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { Select } from '@/components/ui/Select'
import { Pagination } from '@/components/ui/Pagination'
import { LoadingState } from '@/components/ui/LoadingState'

const PAGE_SIZE = 25

const CATEGORY_LABELS: Record<HistoricalReviewCategory, string> = {
  INVALID: 'Incorrect mapping',
  UNMAPPED: 'Unmapped code',
  DUPLICATE: 'Duplicated row',
  MISSING_DATA: 'Missing data',
  DATE_MISMATCH: 'Incorrect date',
}

const CATEGORY_STYLES: Record<HistoricalReviewCategory, string> = {
  INVALID: 'bg-amber-50 text-amber-700 ring-amber-200',
  UNMAPPED: 'bg-orange-50 text-orange-700 ring-orange-200',
  DUPLICATE: 'bg-purple-50 text-purple-700 ring-purple-200',
  MISSING_DATA: 'bg-sky-50 text-sky-700 ring-sky-200',
  DATE_MISMATCH: 'bg-rose-50 text-rose-700 ring-rose-200',
}

const CATEGORY_OPTIONS = [
  { value: 'ALL', label: 'All reasons' },
  { value: 'INVALID', label: 'Incorrect mapping' },
  { value: 'UNMAPPED', label: 'Unmapped code' },
  { value: 'DUPLICATE', label: 'Duplicated row' },
  { value: 'MISSING_DATA', label: 'Missing data' },
  { value: 'DATE_MISMATCH', label: 'Incorrect date' },
]

const STATE_TABS: { value: HistoricalReviewState; label: string }[] = [
  { value: 'PENDING', label: 'Pending' },
  { value: 'CORRECTED', label: 'Corrected' },
  { value: 'SKIPPED', label: 'Skipped' },
  { value: 'ALL', label: 'All' },
]

function CategoryBadge({ category }: { category: HistoricalReviewCategory }) {
  return (
    <span className={cn(
      'inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-semibold ring-1 ring-inset',
      CATEGORY_STYLES[category] ?? 'bg-surface-100 text-surface-600 ring-surface-200',
    )}>
      {CATEGORY_LABELS[category] ?? category}
    </span>
  )
}

const STATE_BADGE_STYLES: Record<'PENDING' | 'CORRECTED' | 'SKIPPED', string> = {
  PENDING: 'bg-warning-50 text-warning-700 ring-warning-200',
  CORRECTED: 'bg-success-50 text-success-700 ring-success-200',
  SKIPPED: 'bg-surface-100 text-surface-600 ring-surface-200',
}

function StateBadge({ state }: { state: 'PENDING' | 'CORRECTED' | 'SKIPPED' }) {
  return (
    <span className={cn(
      'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ring-1 ring-inset',
      STATE_BADGE_STYLES[state],
    )}>
      {state === 'PENDING' ? 'Pending review' : state === 'CORRECTED' ? 'Corrected' : 'Skipped'}
    </span>
  )
}

function CountTile({ label, value, tone, onClick, active }: {
  label: string
  value: number
  tone: 'default' | 'success' | 'muted' | 'warning' | 'info'
  onClick?: () => void
  active?: boolean
}) {
  const body = (
    <>
      <div className={cn('text-2xl font-bold tabular-nums',
        tone === 'warning' ? 'text-brand-600'
          : tone === 'success' ? 'text-success-700'
            : tone === 'info' ? 'text-sky-700'
              : tone === 'muted' ? 'text-surface-500' : 'text-surface-800')}>
        {value.toLocaleString()}
      </div>
      <div className="mt-0.5 text-xs font-medium text-surface-500">{label}</div>
    </>
  )
  if (!onClick) {
    return <div className="rounded-lg border border-surface-200 bg-surface-0 px-4 py-3">{body}</div>
  }
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={cn(
        'rounded-lg border bg-surface-0 px-4 py-3 text-left transition-colors hover:border-brand-300',
        active ? 'border-brand-400 ring-1 ring-brand-300' : 'border-surface-200',
      )}
    >
      {body}
    </button>
  )
}

function useDebounced<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delay)
    return () => clearTimeout(t)
  }, [value, delay])
  return debounced
}

/**
 * Worklist for staged historical attendance cells that cannot be trusted yet.
 *
 * Every entry keeps the value the workbook actually contained, so a correction is
 * always visibly a correction and never looks like the file said so. Correcting
 * replaces only this import's staged row; the workbook itself is never touched and
 * no attendance record exists until the import is committed. Nothing here is ever
 * chosen for the admin: an entry leaves the pending bucket only when the admin
 * picks a status or confirms a skip.
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
  const [search, setSearch] = useState('')
  const [category, setCategory] = useState('ALL')
  const [state, setState] = useState<HistoricalReviewState>('PENDING')
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [bulkStatus, setBulkStatus] = useState('')
  const [confirmAll, setConfirmAll] = useState(false)
  const [confirmSkipId, setConfirmSkipId] = useState<number | null>(null)
  const [confirmBulkSkip, setConfirmBulkSkip] = useState(false)

  const debouncedSearch = useDebounced(search)
  const isPending = state === 'PENDING'
  const reasonFilterApplies = state === 'PENDING' || state === 'ALL'
  const filter = useMemo(
    () => ({
      search: debouncedSearch.trim(),
      category: reasonFilterApplies && category !== 'ALL' ? category : '',
      state,
    }),
    [debouncedSearch, category, state, reasonFilterApplies],
  )

  useEffect(() => {
    setPage(0)
    setSelected(new Set())
    setConfirmSkipId(null)
    setConfirmBulkSkip(false)
    setConfirmAll(false)
  }, [filter.search, filter.category, filter.state])

  const queryKey = ['hist-unresolved', importId, page, filter.search, filter.category, filter.state] as const
  const { data, isLoading, isFetching } = useQuery({
    queryKey,
    queryFn: () => adminApi.historicalUnresolved(importId, page, PAGE_SIZE, filter),
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

  const bulk = useMutation({
    mutationFn: (v: { rowIds?: number[]; status?: string; skip?: boolean }) =>
      adminApi.historicalBulkResolve(importId, {
        rowIds: v.rowIds,
        status: v.status,
        skip: v.skip,
        search: v.rowIds?.length ? undefined : filter.search,
        category: v.rowIds?.length ? undefined : filter.category,
      }),
    onSuccess: async (res) => {
      toast.success(`Resolved ${res.affected.toLocaleString()} entr${res.affected === 1 ? 'y' : 'ies'}`)
      setSelected(new Set())
      setConfirmAll(false)
      setConfirmBulkSkip(false)
      await invalidate()
    },
    onError: (e) => toast.error(extractMessage(e)),
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

  const allOnPageSelected = entries.length > 0 && entries.every((e) => selected.has(e.id))
  const toggleAll = () => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (allOnPageSelected) {
        entries.forEach((e) => next.delete(e.id))
      } else {
        entries.forEach((e) => next.add(e.id))
      }
      return next
    })
  }
  const toggleOne = (id: number) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const selectedIds = Array.from(selected)
  const filterActive = Boolean(filter.search || filter.category)

  return (
    <div className="space-y-4">
      {summary && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          <CountTile label="Total entries" value={summary.totalEntries} tone="default" />
          <CountTile label="Valid entries" value={summary.validEntries} tone="info" />
          <CountTile
            label="Pending review"
            value={summary.remaining}
            tone="warning"
            active={state === 'PENDING'}
            onClick={() => setState('PENDING')}
          />
          <CountTile
            label="Manually corrected"
            value={summary.corrected}
            tone="success"
            active={state === 'CORRECTED'}
            onClick={() => setState('CORRECTED')}
          />
          <CountTile
            label="Explicitly skipped"
            value={summary.skipped}
            tone="muted"
            active={state === 'SKIPPED'}
            onClick={() => setState('SKIPPED')}
          />
          <CountTile
            label="Remaining unresolved"
            value={summary.remaining}
            tone="warning"
            onClick={() => setState('PENDING')}
          />
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
            should have held, or explicitly skip the entry to leave it out. Nothing is chosen for you — use
            search, the reason filter and the bulk actions below to clear large batches deliberately.
          </span>
        </div>
      )}

      <div className="flex flex-col gap-3 rounded-lg border border-surface-200 bg-surface-0 p-3 lg:flex-row lg:items-center">
        <div className="flex flex-wrap gap-1 rounded-md bg-surface-100 p-1">
          {STATE_TABS.map((tab) => (
            <button
              key={tab.value}
              type="button"
              onClick={() => setState(tab.value)}
              aria-pressed={state === tab.value}
              className={cn(
                'rounded px-3 py-1.5 text-xs font-semibold transition-colors',
                state === tab.value
                  ? 'bg-surface-0 text-brand-600 shadow-sm'
                  : 'text-surface-500 hover:text-surface-700',
              )}
            >
              {tab.label}
            </button>
          ))}
        </div>
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-surface-400" />
          <Input
            aria-label="Search entries"
            className="pl-9"
            placeholder="Search by employee, id, cell, code or reason…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        {reasonFilterApplies && (
          <div className="lg:w-52">
            <Select
              aria-label="Filter by reason"
              value={category}
              onChange={(e) => setCategory(e.target.value)}
              options={CATEGORY_OPTIONS}
            />
          </div>
        )}
        {isPending && filterActive && !confirmAll && (
          <Button variant="secondary" size="sm" onClick={() => setConfirmAll(true)}>
            Resolve all matching
          </Button>
        )}
      </div>

      {confirmAll && (
        <div className="flex flex-wrap items-center gap-3 rounded-lg border border-error-200 bg-error-50/60 px-4 py-3 text-sm text-error-700">
          <span>
            Resolve every pending entry matching the current filter{filterActive ? ' (search / reason)' : ''}? This
            cannot be undone.
          </span>
          <div className="w-52">
            <Select
              aria-label="Resolve all to status"
              placeholder="Correct all to…"
              value={bulkStatus}
              onChange={(e) => setBulkStatus(e.target.value)}
              options={statuses.map((s) => ({ value: s.code, label: `${s.code} — ${s.name}` }))}
            />
          </div>
          <div className="flex gap-2">
            <Button
              size="sm"
              variant="secondary"
              disabled={!bulkStatus}
              loading={bulk.isPending}
              onClick={() => bulk.mutate({ status: bulkStatus })}
            >
              Correct all
            </Button>
            <Button
              size="sm"
              variant="danger"
              loading={bulk.isPending}
              onClick={() => bulk.mutate({ skip: true })}
            >
              Skip all
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setConfirmAll(false)}>
              <X className="h-4 w-4" /> Cancel
            </Button>
          </div>
        </div>
      )}

      {isPending && selectedIds.length > 0 && (
        <div className="flex flex-wrap items-center gap-3 rounded-lg border border-brand-200 bg-brand-50/60 px-4 py-3">
          <span className="text-sm font-medium text-brand-700">
            {selectedIds.length.toLocaleString()} selected
          </span>
          <div className="w-52">
            <Select
              aria-label="Bulk status"
              placeholder="Correct selected to…"
              value={bulkStatus}
              onChange={(e) => setBulkStatus(e.target.value)}
              options={statuses.map((s) => ({ value: s.code, label: `${s.code} — ${s.name}` }))}
            />
          </div>
          <Button
            size="sm"
            variant="secondary"
            disabled={!bulkStatus}
            loading={bulk.isPending}
            onClick={() => bulk.mutate({ rowIds: selectedIds, status: bulkStatus })}
          >
            Correct selected
          </Button>
          {confirmBulkSkip ? (
            <>
              <span className="text-xs font-medium text-error-700">
                Skip {selectedIds.length.toLocaleString()} selected and leave them out?
              </span>
              <Button
                size="sm"
                variant="danger"
                loading={bulk.isPending}
                onClick={() => bulk.mutate({ rowIds: selectedIds, skip: true })}
              >
                Confirm skip
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setConfirmBulkSkip(false)}>
                Cancel
              </Button>
            </>
          ) : (
            <Button
              size="sm"
              variant="ghost"
              onClick={() => setConfirmBulkSkip(true)}
            >
              Skip selected
            </Button>
          )}
          <Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>
            Clear
          </Button>
        </div>
      )}

      {isLoading ? (
        <LoadingState label="Loading flagged entries…" />
      ) : entries.length === 0 ? (
        state === 'PENDING' && summary?.remaining === 0 ? null : (
          <div className="rounded-lg border border-surface-200 bg-surface-0 px-4 py-6 text-center text-sm text-surface-500">
            No entries match the current search or filter.
          </div>
        )
      ) : (
        <div className="card overflow-hidden">
          <div className="overflow-x-auto">
            <table className="min-w-full border-collapse">
              <thead>
                <tr className="border-b border-surface-200">
                  {isPending && (
                    <th className="th w-10">
                      <input
                        type="checkbox"
                        aria-label="Select all on page"
                        className="h-4 w-4 rounded border-surface-300"
                        checked={allOnPageSelected}
                        onChange={toggleAll}
                      />
                    </th>
                  )}
                  <th className="th">Cell</th>
                  <th className="th">Employee</th>
                  <th className="th">Date</th>
                  <th className="th">Original Excel value</th>
                  <th className="th">Mapped status</th>
                  <th className="th">Description</th>
                  <th className="th">Original author</th>
                  <th className="th">Reason for review</th>
                  {isPending ? <th className="th">Correct to</th> : <th className="th">State</th>}
                  {isPending && <th className="th" />}
                </tr>
              </thead>
              <tbody className="divide-y divide-surface-100">
                {entries.map((e) => {
                  const busy = busyRow === e.id || isFetching
                  const chosen = draft[e.id] ?? ''
                  const rowState: 'PENDING' | 'CORRECTED' | 'SKIPPED' =
                    e.skipped ? 'SKIPPED' : e.corrected ? 'CORRECTED' : 'PENDING'
                  return (
                    <tr key={e.id} className="hover:bg-surface-50/60">
                      {isPending && (
                        <td className="td">
                          <input
                            type="checkbox"
                            aria-label={`Select ${e.employeeId} ${e.cellRef ?? ''}`}
                            className="h-4 w-4 rounded border-surface-300"
                            checked={selected.has(e.id)}
                            onChange={() => toggleOne(e.id)}
                          />
                        </td>
                      )}
                      <td className="td font-mono text-xs text-surface-500">{e.cellRef ?? '—'}</td>
                      <td className="td">
                        <div className="font-medium text-surface-800">{e.employeeName ?? '—'}</div>
                        <div className="text-xs text-surface-500">{e.employeeId}</div>
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
                      <td className="td whitespace-nowrap text-surface-600">
                        {e.incomingStatus ? `${e.incomingStatus}${e.statusName ? ` — ${e.statusName}` : ''}` : '—'}
                      </td>
                      <td className="td max-w-[16rem] text-xs text-surface-600">{e.description ?? '—'}</td>
                      <td className="td whitespace-nowrap text-xs text-surface-600">{e.descriptionAuthor ?? '—'}</td>
                      <td className="td max-w-xs">
                        <CategoryBadge category={e.category} />
                        <div className="mt-1 text-xs text-surface-600">{e.issue}</div>
                      </td>
                      {isPending ? (
                        <>
                          <td className="td">
                            <Select
                              aria-label={`Status for ${e.employeeId} ${e.cellRef ?? ''}`}
                              className="w-44 py-1.5"
                              placeholder="Choose a status…"
                              value={chosen}
                              onChange={(ev) => setDraft((d) => ({ ...d, [e.id]: ev.target.value }))}
                              options={statuses.map((s) => ({ value: s.code, label: `${s.code} — ${s.name}` }))}
                            />
                          </td>
                          <td className="td">
                            {confirmSkipId === e.id ? (
                              <div className="flex items-center justify-end gap-2">
                                <span className="text-xs text-surface-500">Skip this entry?</span>
                                <Button
                                  size="sm"
                                  variant="danger"
                                  loading={busyRow === e.id && skip.isPending}
                                  onClick={() => {
                                    setBusyRow(e.id)
                                    setConfirmSkipId(null)
                                    skip.mutate({ rowId: e.id })
                                  }}
                                >
                                  Confirm skip
                                </Button>
                                <Button size="sm" variant="ghost" onClick={() => setConfirmSkipId(null)}>
                                  Cancel
                                </Button>
                              </div>
                            ) : (
                              <div className="flex items-center justify-end gap-2">
                                {chosen && (
                                  <Button
                                    size="sm"
                                    variant="ghost"
                                    onClick={() => setDraft((d) => {
                                      const next = { ...d }
                                      delete next[e.id]
                                      return next
                                    })}
                                  >
                                    Clear
                                  </Button>
                                )}
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
                                  onClick={() => setConfirmSkipId(e.id)}
                                >
                                  Skip
                                </Button>
                              </div>
                            )}
                          </td>
                        </>
                      ) : (
                        <td className="td">
                          <StateBadge state={rowState} />
                        </td>
                      )}
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
