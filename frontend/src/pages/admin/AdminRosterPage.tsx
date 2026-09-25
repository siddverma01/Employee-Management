import { createPortal } from 'react-dom'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  AlertTriangle, CalendarRange, Check, ChevronLeft, ChevronRight, Eraser, Save, Search, X,
} from 'lucide-react'
import toast from 'react-hot-toast'
import type { RosterCellEdit, RosterEmployeeRow, RosterPageMeta } from '@/types'
import { adminApi } from '@/api'
import { extractMessage } from '@/api/client'
import { cn } from '@/utils'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Select } from '@/components/ui/Select'
import { Pagination } from '@/components/ui/Pagination'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'
import { RosterLegend, monthLabel } from '@/components/roster/RosterLegend'
import { ROSTER_STATUS_CODES, rosterStatusClass, statusLabel } from '@/constants/rosterStatus'

const EMPTY_CODE = ''
const PAGE_SIZE = 30
const COUNTER_CODES = ['WO', 'PL', 'WFH', 'WFO', 'SL', 'CO'] as const

function currentMonth(): string {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
}

function shiftMonth(ym: string, delta: number): string {
  const [y, m] = ym.split('-').map(Number)
  const d = new Date(y, m - 1 + delta, 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

/** "yyyy-MM-dd" -> weekday number 0..6 (0 = Sun) */
function weekdayOf(dateStr: string): number {
  const [y, m, d] = dateStr.split('-').map(Number)
  return new Date(y, m - 1, d).getDay()
}

function cellKey(employeeId: string, date: string): string {
  return `${employeeId}|${date}`
}

/** Sticky widths/offsets keep the employee info frozen while dates scroll. */
const TEAM_CLS = 'sticky left-0 w-24'
const EMP_CLS = 'sticky left-0 md:left-[6rem] w-28'
const NAME_CLS = 'sticky left-[7rem] md:left-[13rem] w-44 md:w-56'
const HEAD_EMP = `${TEAM_CLS} top-0 z-30 hidden md:table-cell border-r border-surface-200 px-3 py-2 text-left text-[11px] font-semibold text-surface-500 bg-surface-50`
const HEAD_EMP_ID = `${EMP_CLS} top-0 z-30 border-r border-surface-200 px-3 py-2 text-left text-[11px] font-semibold text-surface-500 bg-surface-50`
const HEAD_NAME = `${NAME_CLS} top-0 z-30 border-r border-surface-200 px-3 py-2 text-left text-[11px] font-semibold text-surface-500 bg-surface-50`
const BODY_TEAM = `${TEAM_CLS} z-20 hidden md:table-cell border-r border-surface-200 bg-surface-0 px-3 py-2 align-middle text-xs text-surface-500`
const BODY_EMP_ID = `${EMP_CLS} z-20 border-r border-surface-200 bg-surface-0 px-3 py-2 align-middle font-mono text-xs font-medium text-surface-700`
const BODY_NAME = `${NAME_CLS} z-20 border-r border-surface-200 bg-surface-0 px-3 py-2 align-middle text-sm font-medium text-surface-800`

interface PickerState {
  employeeId: string
  date: string
  currentCode: string
  top: number
  left: number
}

/** Small popover listing every status code; rendered through a portal so the
 *  grid's internal scroll container never clips it. */
function StatusPicker({
  state,
  options,
  onPick,
  onClose,
}: {
  state: PickerState
  options: string[]
  onPick: (code: string) => void
  onClose: () => void
}) {
  const menuRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const uniqueOptions = useMemo(() => {
    const set = new Set(options)
    if (state.currentCode && !set.has(state.currentCode)) set.add(state.currentCode)
    return [...set]
  }, [options, state.currentCode])

  return createPortal(
    <>
      <div className="fixed inset-0 z-[60]" onMouseDown={onClose} />
      <div
        ref={menuRef}
        role="menu"
        className="fixed z-[70] w-56 overflow-hidden rounded-lg border border-surface-200 bg-surface-0 shadow-hpe-lg dark:shadow-none"
        style={{ top: state.top, left: state.left }}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between gap-2 border-b border-surface-200 bg-surface-50 px-3 py-2">
          <div className="min-w-0">
            <p className="truncate text-[11px] font-semibold text-surface-700">{state.employeeId}</p>
            <p className="text-[10px] text-surface-400">{state.date}</p>
          </div>
          <button
            type="button"
            className="rounded p-0.5 text-surface-400 transition-colors hover:bg-surface-100 hover:text-surface-600"
            onClick={onClose}
            aria-label="Close"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="grid max-h-60 grid-cols-2 overflow-auto p-1.5">
          <button
            type="button"
            className={cn(
              'col-span-2 flex items-center gap-1.5 rounded-md px-2 py-1 text-[11px] text-surface-500 transition-colors hover:bg-surface-100',
              state.currentCode === EMPTY_CODE && 'text-brand-600',
            )}
            onClick={() => onPick(EMPTY_CODE)}
          >
            <Eraser className="h-3.5 w-3.5" /> Clear status
          </button>
          {uniqueOptions.map((code) => (
            <button
              key={code}
              type="button"
              role="menuitem"
              className={cn(
                'flex items-center justify-between gap-1 rounded-md px-2 py-1 text-left text-[11px] font-medium transition-colors hover:bg-surface-100',
                code === state.currentCode && 'text-brand-600',
              )}
              onClick={() => onPick(code)}
            >
              <span className="flex min-w-0 items-center gap-1.5">
                <span className={cn('roster-badge min-w-[2.1rem] px-1 text-[8px]', rosterStatusClass(code))}>{code}</span>
                <span className="truncate">{statusLabel(code)}</span>
              </span>
              {code === state.currentCode ? <Check className="h-3 w-3 shrink-0" /> : null}
            </button>
          ))}
        </div>
      </div>
    </>,
    document.body,
  )
}

export function AdminRosterPage() {
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()

  const teamParam = searchParams.get('teamId')
  const [teamId, setTeamId] = useState<number | null>(teamParam ? Number(teamParam) : null)
  const [month, setMonth] = useState(searchParams.get('month') ?? currentMonth())

  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState(EMPTY_CODE)
  const [locationFilter, setLocationFilter] = useState(EMPTY_CODE)
  const [shiftFilter, setShiftFilter] = useState(EMPTY_CODE)
  const [page, setPage] = useState(0)

  const [dirty, setDirty] = useState<Map<string, string>>(() => new Map())
  const [picker, setPicker] = useState<PickerState | null>(null)

  // ------------------------------------------------------------ metadata

  const { data: meta, isLoading: metaLoading } = useQuery<RosterPageMeta>({
    queryKey: ['admin', 'roster', 'monthly', 'meta', { teamId, month }],
    queryFn: () => adminApi.rosterMonthlyMeta({ teamId: teamId ?? undefined, month }),
  })

  const months = meta?.months ?? []
  const chosenMonthInList = months.includes(month)

  // Snap to the most recent month with data when the URL/default month has none.
  useEffect(() => {
    if (months.length === 0 || chosenMonthInList || searchParams.get('month') !== null && searchParams.get('month') === month) {
      return
    }
    const latest = months[months.length - 1]
    setMonth(latest)
    const next = new URLSearchParams(searchParams)
    next.set('month', latest)
    setSearchParams(next, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [months.length, chosenMonthInList])

  useEffect(() => {
    const id = window.setTimeout(() => setDebouncedSearch(search.trim()), 350)
    return () => window.clearTimeout(id)
  }, [search])

  useEffect(() => {
    setPage(0)
  }, [teamId, month, statusFilter, locationFilter, shiftFilter, debouncedSearch])

  // ------------------------------------------------------------ grid data

  const params: Record<string, unknown> = useMemo(
    () => ({
      teamId: teamId ?? undefined,
      month,
      q: debouncedSearch || undefined,
      status: statusFilter || undefined,
      location: locationFilter || undefined,
      shift: shiftFilter || undefined,
      page,
      size: PAGE_SIZE,
    }),
    [teamId, month, debouncedSearch, statusFilter, locationFilter, shiftFilter, page],
  )

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ['admin', 'roster', 'monthly', params],
    queryFn: () => adminApi.rosterMonthly(params),
    enabled: month.length === 7,
  })

  const dirtyCount = dirty.size
  const rowCount = data?.employees.length ?? 0

  const syncParams = (tid: number | null, ym: string) => {
    const next = new URLSearchParams(searchParams)
    if (tid != null) next.set('teamId', String(tid))
    else next.delete('teamId')
    next.set('month', ym)
    setSearchParams(next)
  }

  const guardDirty = (fn: () => void) => {
    if (dirtyCount > 0 && !window.confirm('You have unsaved changes. Discard them?')) return
    fn()
  }

  const updateTeam = (id: number | null) => guardDirty(() => {
    setTeamId(id)
    syncParams(id, month)
  })

  const updateMonth = (ym: string) => {
    if (!ym) return
    guardDirty(() => {
      setMonth(ym)
      syncParams(teamId, ym)
    })
  }

  // ------------------------------------------------------------ editing

  const openPicker = (employee: RosterEmployeeRow, date: string, el: HTMLElement) => {
    const rect = el.getBoundingClientRect()
    const width = 236
    const left = Math.max(8, Math.min(rect.left, window.innerWidth - width - 8))
    const top = Math.min(rect.bottom + 4, window.innerHeight - 320)
    setPicker({
      employeeId: employee.employeeId,
      date,
      currentCode: dirty.get(cellKey(employee.employeeId, date)) ?? employee.days[date] ?? EMPTY_CODE,
      top: Math.max(8, top),
      left,
    })
  }

  const pickStatus = (code: string) => {
    if (!picker) return
    setDirty((prev) => {
      const next = new Map(prev)
      next.set(cellKey(picker.employeeId, picker.date), code)
      return next
    })
    setPicker((prev) => (prev ? { ...prev, currentCode: code } : prev))
  }

  const commitDirty = () => {
    setDirty(new Map())
    setPicker(null)
  }

  const saveMutation = useMutation({
    mutationFn: (changes: RosterCellEdit[]) => adminApi.rosterMonthlySave(changes),
    onSuccess: (res) => {
      commitDirty()
      toast.success(`Saved ${res.saved.toLocaleString()} change${res.saved === 1 ? '' : 's'}.`)
      queryClient.invalidateQueries({ queryKey: ['admin', 'roster', 'monthly'] })
    },
    onError: (err) => toast.error(extractMessage(err)),
  })

  // ------------------------------------------------------------ day/header helpers

  const headerCell = (date: string, weekend: boolean, holiday: boolean) => (
    <th
      key={date}
      className={cn(
        'sticky top-0 z-20 min-w-[2.3rem] border-l border-surface-200 px-0.5 py-1.5 text-center align-middle first:border-l-0',
        weekend && 'bg-brand-50 dark:bg-white/[0.04]',
        holiday && 'bg-amber-50 dark:bg-amber-400/10',
        !weekend && !holiday && 'bg-surface-50',
      )}
      title={holiday ? statusLabel('HPEH') : undefined}
    >
      <div className="text-[11px] font-semibold text-surface-700">{weekdayOf(date) === 0 ? 'S' : date.slice(8)}</div>
      <div className={cn('text-[9px] font-medium uppercase', weekend ? 'text-brand-400' : 'text-surface-400')}>
        {data?.days.find((d) => d.date === date)?.weekday ?? ''}
        {holiday ? ' · H' : ''}
      </div>
    </th>
  )

  const statusCell = (employee: RosterEmployeeRow, date: string, weekend: boolean, holiday: boolean) => {
    const key = cellKey(employee.employeeId, date)
    const edited = dirty.get(key)
    const code = edited !== undefined ? edited : (employee.days[date] ?? EMPTY_CODE)
    const display = code || '·'
    return (
      <td
        key={date}
        className={cn(
          'min-w-[2.3rem] border-l border-surface-200 p-1 text-center align-middle first:border-l-0',
          weekend && 'bg-surface-50 dark:bg-white/[0.03]',
          holiday && 'bg-amber-50/60 dark:bg-amber-400/[0.07]',
        )}
      >
        <button
          type="button"
          className={cn(
            'roster-cell',
            rosterStatusClass(code),
            edited !== undefined && 'outline outline-2 outline-offset-1 outline-amber-400',
          )}
          title={code ? `${statusLabel(code)} — ${date}` : `Set status — ${date}`}
          onClick={(e) => openPicker(employee, date, e.currentTarget)}
        >
          {display}
        </button>
      </td>
    )
  }

  // ---------------------------------------------------------------- render

  const isLoadingData = isLoading || (isFetching && !data)
  const counters = data?.counters ?? {}

  return (
    <div>
      <PageHeader
        title="Attendance Roster"
        subtitle="Month-wise imported roster — editable by admin"
        actions={
          <div className="flex flex-wrap items-center gap-1.5">
            {COUNTER_CODES.map((c) => (
              <span
                key={c}
                className="flex items-center gap-1.5 rounded-md border border-surface-200 bg-surface-0 px-2 py-1 shadow-hpe-sm dark:shadow-none"
                title={statusLabel(c)}
              >
                <span className={cn('roster-badge min-w-[2rem] px-1 text-[9px]', rosterStatusClass(c))}>{c}</span>
                <span className="text-xs font-semibold tabular-nums text-surface-700">{counters[c] ?? 0}</span>
              </span>
            ))}
          </div>
        }
      />

      {/* ------------------------------------------------ filters */}
      <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-6">
        <div>
          <label className="label">Team</label>
          <select className="select" value={teamId ?? ''} onChange={(e) => updateTeam(e.target.value === '' ? null : Number(e.target.value))}>
            <option value="">All Teams</option>
            {(meta?.teams ?? []).map((t) => (
              <option key={t.id} value={t.id}>{t.name}</option>
            ))}
          </select>
        </div>
        <div>
          <label className="label">Month</label>
          <div className="flex items-center gap-1.5">
            <Button variant="secondary" size="sm" className="px-2" onClick={() => updateMonth(shiftMonth(month, -1))} aria-label="Previous month">
              <ChevronLeft className="h-4 w-4" />
            </Button>
            <Select
              className="px-2 py-1.5"
              placeholder="Pick a month"
              value={month}
              onChange={(e) => updateMonth(e.target.value)}
              options={months.map((ym) => ({ value: ym, label: monthLabel(ym) }))}
            />
            <Button variant="secondary" size="sm" className="px-2" onClick={() => updateMonth(shiftMonth(month, 1))} aria-label="Next month">
              <ChevronRight className="h-4 w-4" />
            </Button>
          </div>
          {metaLoading && <span className="mt-1 block text-[11px] text-surface-400">Loading months…</span>}
        </div>
        <div>
          <label className="label">Employee</label>
          <div className="relative">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-surface-400" />
            <input className="input pl-8" placeholder="Search name / ID / email" value={search} onChange={(e) => setSearch(e.target.value)} />
          </div>
        </div>
        <div>
          <label className="label">Status</label>
          <Select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            placeholder="Any status"
            options={(meta?.statuses ?? []).map((s) => ({ value: s.code, label: `${s.code} — ${s.name}` }))}
          />
        </div>
        <div>
          <label className="label">Location</label>
          <Select
            value={locationFilter}
            onChange={(e) => setLocationFilter(e.target.value)}
            placeholder="All locations"
            options={(meta?.locations ?? []).map((l) => ({ value: l, label: l }))}
          />
        </div>
        <div>
          <label className="label">Shift</label>
          <Select
            value={shiftFilter}
            onChange={(e) => setShiftFilter(e.target.value)}
            placeholder="All shifts"
            options={(meta?.shifts ?? []).map((s) => ({ value: s, label: s }))}
          />
        </div>
      </div>

      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <RosterLegend />
        <div className="flex items-center gap-1.5 text-xs text-surface-500">
          {dirtyCount > 0 ? (
            <>
              <span className="flex items-center gap-1.5 rounded-md bg-amber-50 px-2 py-1 text-amber-700">
                <AlertTriangle className="h-3.5 w-3.5" /> {dirtyCount} unsaved change{dirtyCount === 1 ? '' : 's'}
              </span>
              <Button size="sm" variant="secondary" onClick={() => setDirty(new Map())}>Cancel Changes</Button>
              <Button size="sm" onClick={() => saveMutation.mutate(buildChanges(dirty))} loading={saveMutation.isPending}>
                <Save className="h-3.5 w-3.5" /> Save Changes
              </Button>
            </>
          ) : null}
        </div>
      </div>

      {isLoadingData ? (
        <div className="card p-12"><LoadingState /></div>
      ) : !data || (data.employees.length === 0 && data.totalElements === 0) ? (
        <div className="card p-12">
          <EmptyState
            title={`No roster data for ${monthLabel(month)}`}
            description="Import a historical monthly roster, or adjust the team and filters."
          />
          <div className="mt-4 flex justify-center gap-2">
            <Button onClick={() => window.location.assign('/admin/historical-import')}>Import historical attendance</Button>
          </div>
        </div>
      ) : (
        <div className="card overflow-hidden">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-surface-200 px-4 py-3">
            <div className="flex items-center gap-2 text-sm font-semibold text-surface-700">
              <CalendarRange className="h-4 w-4 text-brand-500" />
              {monthLabel(month)}
              {data.teamName ? <span className="text-xs font-normal text-surface-400">· {data.teamName}</span> : null}
            </div>
            <div className="flex items-center gap-2">
              <span className="rounded-full bg-surface-100 px-2.5 py-1 text-[11px] font-semibold tabular-nums text-surface-600">
                {data.matchedEmployees.toLocaleString()} / {data.totalEmployees.toLocaleString()} employees
              </span>
            </div>
          </div>

          <div className="max-h-[72vh] overflow-auto">
            <table className="min-w-full border-collapse">
              <thead>
                <tr className="border-b border-surface-200">
                  <th className={HEAD_EMP}>Team</th>
                  <th className={HEAD_EMP_ID}>Emp ID</th>
                  <th className={HEAD_NAME}>Employee Name</th>
                  <th className="th">Email</th>
                  <th className="th">Location</th>
                  <th className="th">Shift</th>
                  <th className="th">Week Off</th>
                  {data.days.map((d) => headerCell(d.date, d.weekend, d.holiday))}
                </tr>
              </thead>
              <tbody className="divide-y divide-surface-200">
                {data.employees.map((employee) => (
                  <tr key={employee.employeeId} className="group hover:bg-surface-50/60 dark:hover:bg-white/[0.02]">
                    <td className={BODY_TEAM}>{employee.teamName ?? '—'}</td>
                    <td className={BODY_EMP_ID}>{employee.employeeId}</td>
                    <td className={BODY_NAME}>{employee.employeeName}</td>
                    <td className="td text-xs text-surface-500">{employee.email ?? '—'}</td>
                    <td className="td text-xs text-surface-500">{employee.location ?? '—'}</td>
                    <td className="td text-xs text-surface-500">{employee.shift ?? '—'}</td>
                    <td className="td text-xs text-surface-500">{employee.weekOff ?? '—'}</td>
                    {data.days.map((d) => statusCell(employee, d.date, d.weekend, d.holiday))}
                  </tr>
                ))}
              </tbody>
            </table>
            {rowCount === 0 && (
              <div className="p-10">
                <EmptyState title="No matching employees" description="Adjust the search, status, location or shift filters." />
              </div>
            )}
          </div>

          <Pagination
            page={data.page}
            size={data.size}
            totalElements={data.totalElements}
            totalPages={data.totalPages}
            onPageChange={(p) => setPage(p)}
          />
        </div>
      )}

      {picker && (
        <StatusPicker
          state={picker}
          options={ROSTER_STATUS_CODES as unknown as string[]}
          onPick={pickStatus}
          onClose={() => setPicker(null)}
        />
      )}
    </div>
  )
}

function buildChanges(dirty: Map<string, string>): RosterCellEdit[] {
  const changes: RosterCellEdit[] = []
  dirty.forEach((code, key) => {
    const sep = key.indexOf('|')
    changes.push({ employeeId: key.slice(0, sep), date: key.slice(sep + 1), statusCode: code })
  })
  return changes
}