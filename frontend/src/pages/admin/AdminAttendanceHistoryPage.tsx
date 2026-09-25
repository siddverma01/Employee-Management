import { useEffect, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  ChevronDown, ChevronUp, ChevronsUpDown, Download, FileSpreadsheet, Search, UserRound, X,
} from 'lucide-react'
import toast from 'react-hot-toast'
import type {
  AttendanceHistoryMeta, AttendanceHistoryRecord, AttendanceHistoryResponse,
} from '@/types'
import { adminApi } from '@/api'
import { extractMessage } from '@/api/client'
import { cn } from '@/utils'
import { formatShiftDisplay } from '@/utils/shift'
import { PageHeader } from '@/components/ui/PageHeader'
import { Button } from '@/components/ui/Button'
import { Select } from '@/components/ui/Select'
import { Input } from '@/components/ui/Input'
import { Pagination } from '@/components/ui/Pagination'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'
import { Modal } from '@/components/ui/Modal'
import { monthLabel } from '@/components/roster/RosterLegend'
import { rosterStatusClass, statusLabel } from '@/constants/rosterStatus'

const PAGE_SIZE = 25
const SUMMARY_CODES = ['WFO', 'WFH', 'WO', 'PL', 'SL', 'CO'] as const
const SUMMARY_LABELS: Record<string, string> = {
  WFO: 'Work From Office',
  WFH: 'Work From Home',
  WO: 'Week Off',
  PL: 'Privilege Leave',
  SL: 'Sick Leave',
  CO: 'Compensatory Off',
}

type SortKey =
  | 'date' | 'employeeId' | 'employeeName' | 'teamName'
  | 'location' | 'shift' | 'status' | 'sourceMonth' | 'sourceSheet'

const SORT_COLUMNS: { key: SortKey; label: string }[] = [
  { key: 'date', label: 'Date' },
  { key: 'employeeId', label: 'Emp ID' },
  { key: 'employeeName', label: 'Employee Name' },
  { key: 'teamName', label: 'Team' },
  { key: 'location', label: 'Location' },
  { key: 'shift', label: 'Shift' },
  { key: 'status', label: 'Status' },
  { key: 'sourceMonth', label: 'Source Month' },
  { key: 'sourceSheet', label: 'Source Sheet' },
]

function StatusBadge({ code }: { code: string }) {
  return (
    <span
      className={cn('roster-badge min-w-[2.1rem] px-1 text-[9px]', rosterStatusClass(code))}
      title={statusLabel(code)}
    >
      {code}
    </span>
  )
}

function StatCard({ label, value, total, code }: { label: string; value: number; total: number; code?: string }) {
  const pct = total > 0 ? Math.round((value / total) * 100) : 0
  return (
    <div className="card p-4">
      <div className="flex items-center gap-2">
        {code ? <StatusBadge code={code} /> : <span className="roster-badge min-w-[2.1rem] bg-surface-100 px-1 text-[9px] text-surface-500">All</span>}
        <span className="truncate text-xs font-medium text-surface-600">{label}</span>
      </div>
      <div className="mt-2 text-2xl font-semibold tabular-nums text-surface-800">{value.toLocaleString()}</div>
      <div className="mt-0.5 text-[11px] text-surface-400">{pct}%</div>
    </div>
  )
}

function ThSort({
  label, k, sortBy, sortDir, onSort,
}: {
  label: string
  k: SortKey
  sortBy: SortKey
  sortDir: 'asc' | 'desc'
  onSort: (k: SortKey) => void
}) {
  const active = sortBy === k
  return (
    <th
      className="th cursor-pointer select-none whitespace-nowrap"
      onClick={() => onSort(k)}
      aria-sort={active ? (sortDir === 'asc' ? 'ascending' : 'descending') : 'none'}
    >
      <span className="inline-flex items-center gap-1">
        {label}
        {active
          ? (sortDir === 'asc' ? <ChevronUp className="h-3.5 w-3.5 text-brand-500" /> : <ChevronDown className="h-3.5 w-3.5 text-brand-500" />)
          : <ChevronsUpDown className="h-3 w-3 text-surface-300" />}
      </span>
    </th>
  )
}

function SourceModal({ record, onClose }: { record: AttendanceHistoryRecord; onClose: () => void }) {
  const rows: [string, string][] = [
    ['Date', record.date],
    ['Status', record.unknown ? `${record.statusCode} (unknown code)` : `${record.statusCode} — ${record.statusName ?? ''}`],
    ['Employee', `${record.employeeName ?? ''} (${record.employeeId})`],
    ['Source file', record.sourceFile ?? '—'],
    ['Source sheet', record.sourceSheet ?? '—'],
    ['Source row', record.sourceRow != null ? String(record.sourceRow) : '—'],
    ['Imported at', record.importedAt ?? '—'],
  ]
  return (
    <Modal open title="Source information" onClose={onClose} size="sm">
      <dl className="divide-y divide-surface-100">
        {rows.map(([k, v]) => (
          <div key={k} className="flex items-start justify-between gap-4 py-2.5">
            <dt className="shrink-0 text-xs font-medium text-surface-500">{k}</dt>
            <dd className="break-all text-right text-xs text-surface-700">{v}</dd>
          </div>
        ))}
      </dl>
    </Modal>
  )
}

export function AdminAttendanceHistoryPage() {
  const [searchParams, setSearchParams] = useSearchParams()

  const [dateFrom, setDateFrom] = useState(searchParams.get('dateFrom') ?? '')
  const [dateTo, setDateTo] = useState(searchParams.get('dateTo') ?? '')
  const [month, setMonth] = useState(searchParams.get('month') ?? '')
  const [year, setYear] = useState(searchParams.get('year') ?? '')
  const [teamId, setTeamId] = useState<number | null>(
    searchParams.get('teamId') ? Number(searchParams.get('teamId')) : null)
  const [location, setLocation] = useState(searchParams.get('location') ?? '')
  const [shift, setShift] = useState(searchParams.get('shift') ?? '')
  const [status, setStatus] = useState(searchParams.get('status') ?? '')
  const [employeeName, setEmployeeName] = useState(searchParams.get('employeeName') ?? '')
  const [employeeId, setEmployeeId] = useState(searchParams.get('employeeId') ?? '')
  const [page, setPage] = useState(0)
  const [sortBy, setSortBy] = useState<SortKey>((searchParams.get('sortBy') as SortKey) || 'date')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>(searchParams.get('sortDir') === 'desc' ? 'desc' : 'asc')

  const [debouncedName, setDebouncedName] = useState(employeeName)
  const [debouncedId, setDebouncedId] = useState(employeeId)
  const [exporting, setExporting] = useState<'csv' | 'xlsx' | null>(null)
  const [sourceRec, setSourceRec] = useState<AttendanceHistoryRecord | null>(null)
  const [empRec, setEmpRec] = useState<AttendanceHistoryRecord | null>(null)

  useEffect(() => {
    const id = window.setTimeout(() => setDebouncedName(employeeName.trim()), 350)
    return () => window.clearTimeout(id)
  }, [employeeName])

  useEffect(() => {
    const id = window.setTimeout(() => setDebouncedId(employeeId.trim()), 350)
    return () => window.clearTimeout(id)
  }, [employeeId])

  useEffect(() => {
    setPage(0)
  }, [dateFrom, dateTo, month, year, teamId, location, shift, status, debouncedName, debouncedId])

  useEffect(() => {
    const next = new URLSearchParams()
    if (dateFrom) next.set('dateFrom', dateFrom)
    if (dateTo) next.set('dateTo', dateTo)
    if (month) next.set('month', month)
    if (year) next.set('year', year)
    if (teamId != null) next.set('teamId', String(teamId))
    if (location) next.set('location', location)
    if (shift) next.set('shift', shift)
    if (status) next.set('status', status)
    if (employeeName) next.set('employeeName', employeeName)
    if (employeeId) next.set('employeeId', employeeId)
    if (sortBy !== 'date') next.set('sortBy', sortBy)
    if (sortDir !== 'asc') next.set('sortDir', sortDir)
    if (next.toString() !== searchParams.toString()) {
      setSearchParams(next, { replace: true })
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dateFrom, dateTo, month, year, teamId, location, shift, status, employeeName, employeeId, sortBy, sortDir])

  // ------------------------------------------------------------ meta + data

  const { data: meta } = useQuery<AttendanceHistoryMeta>({
    queryKey: ['admin', 'attendance-history', 'meta'],
    queryFn: () => adminApi.attendanceHistoryMeta(),
  })

  const filterParams = useMemo<Record<string, unknown>>(
    () => ({
      dateFrom: dateFrom || undefined,
      dateTo: dateTo || undefined,
      month: month || undefined,
      year: year || undefined,
      teamId: teamId ?? undefined,
      location: location || undefined,
      shift: shift || undefined,
      status: status || undefined,
      employeeName: debouncedName || undefined,
      employeeId: debouncedId || undefined,
    }),
    [dateFrom, dateTo, month, year, teamId, location, shift, status, debouncedName, debouncedId],
  )

  const queryParams = useMemo<Record<string, unknown>>(
    () => ({ ...filterParams, page, size: PAGE_SIZE, sortBy, sortDir }),
    [filterParams, page, sortBy, sortDir],
  )

  const { data, isLoading, isFetching } = useQuery<AttendanceHistoryResponse>({
    queryKey: ['admin', 'attendance-history', queryParams],
    queryFn: () => adminApi.attendanceHistory(queryParams),
  })

  const [empPage, setEmpPage] = useState(0)

  const { data: empData } = useQuery<AttendanceHistoryResponse>({
    queryKey: ['admin', 'attendance-history', 'employee', empRec?.employeeId, empPage],
    queryFn: () => adminApi.attendanceHistory({
      employeeId: empRec?.employeeId,
      page: empPage,
      size: PAGE_SIZE,
      sortBy: 'date',
      sortDir: 'asc',
    }),
    enabled: !!empRec,
  })

  const clearFilters = () => {
    setDateFrom('')
    setDateTo('')
    setMonth('')
    setYear('')
    setTeamId(null)
    setLocation('')
    setShift('')
    setStatus('')
    setEmployeeName('')
    setEmployeeId('')
    setDebouncedName('')
    setDebouncedId('')
  }

  const toggleSort = (k: SortKey) => {
    if (sortBy === k) {
      setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    } else {
      setSortBy(k)
      setSortDir('asc')
    }
  }

  // ------------------------------------------------------------ export

  const downloadBlob = (blob: Blob, filename: string) => {
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  }

  const runExport = async (fmt: 'csv' | 'xlsx') => {
    setExporting(fmt)
    try {
      const blob = await adminApi.attendanceHistoryExport(filterParams, fmt)
      downloadBlob(blob, `attendance-history-${new Date().toISOString().slice(0, 10)}.${fmt === 'xlsx' ? 'xlsx' : 'csv'}`)
    } catch (e) {
      toast.error(extractMessage(e))
    } finally {
      setExporting(null)
    }
  }

  // ------------------------------------------------------------ view model

  const summary = data?.summary
  const namedTotal = (summary?.byStatus ?? {})
  const namedCount = SUMMARY_CODES.reduce((acc, c) => acc + (namedTotal[c] ?? 0), 0)
  const others = Math.max(0, (summary?.total ?? 0) - namedCount)
  const isLoadingData = isLoading || (isFetching && !data)

  const openEmployee = (rec: AttendanceHistoryRecord) => {
    setEmpPage(0)
    setEmpRec(rec)
  }

  return (
    <div>
      <PageHeader
        title="Attendance History"
        subtitle="Search and analyse every imported attendance record across all sources"
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="secondary" size="sm" loading={exporting === 'csv'} disabled={!!exporting}
              onClick={() => runExport('csv')}>
              <Download className="h-3.5 w-3.5" /> Export CSV
            </Button>
            <Button variant="secondary" size="sm" loading={exporting === 'xlsx'} disabled={!!exporting}
              onClick={() => runExport('xlsx')}>
              <FileSpreadsheet className="h-3.5 w-3.5" /> Export Excel
            </Button>
          </div>
        }
      />

      {/* -------------------------------------------------- filters */}
      <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-10">
        <div className="lg:col-span-2">
          <Select
            label="Month"
            value={month}
            placeholder="Any month"
            onChange={(e) => setMonth(e.target.value)}
            options={(meta?.months ?? []).map((m) => ({ value: m, label: monthLabel(m) }))}
          />
        </div>
        <div className="lg:col-span-2">
          <Select
            label="Year"
            value={year}
            placeholder="Any year"
            onChange={(e) => setYear(e.target.value)}
            options={(meta?.years ?? []).map((y) => ({ value: y, label: y }))}
          />
        </div>
        <div className="lg:col-span-2">
          <Input
            label="From date"
            type="date"
            value={dateFrom}
            onChange={(e) => setDateFrom(e.target.value)}
          />
        </div>
        <div className="lg:col-span-2">
          <Input
            label="To date"
            type="date"
            value={dateTo}
            onChange={(e) => setDateTo(e.target.value)}
          />
        </div>
        <div className="flex items-end lg:col-span-2">
          <Button variant="ghost" size="sm" onClick={clearFilters}>
            <X className="h-3.5 w-3.5" /> Clear filters
          </Button>
        </div>

        <div className="lg:col-span-2">
          <Select
            label="Team"
            value={teamId ?? ''}
            placeholder="All teams"
            onChange={(e) => setTeamId(e.target.value === '' ? null : Number(e.target.value))}
            options={(meta?.teams ?? []).map((t) => ({ value: String(t.id), label: t.name }))}
          />
        </div>
        <div className="lg:col-span-2">
          <Select
            label="Location"
            value={location}
            placeholder="All locations"
            onChange={(e) => setLocation(e.target.value)}
            options={(meta?.locations ?? []).map((l) => ({ value: l, label: l }))}
          />
        </div>
        <div className="lg:col-span-2">
          <Select
            label="Shift"
            value={shift}
            placeholder="All shifts"
            onChange={(e) => setShift(e.target.value)}
            options={(meta?.shifts ?? []).map((s) => ({ value: s, label: formatShiftDisplay(s) }))}
          />
        </div>
        <div className="lg:col-span-2">
          <Select
            label="Status"
            value={status}
            placeholder="Any status"
            onChange={(e) => setStatus(e.target.value)}
            options={(meta?.statuses ?? []).map((s) => ({ value: s.code, label: `${s.code} — ${s.name}` }))}
          />
        </div>

        <div className="lg:col-span-2">
          <label className="label">Employee</label>
          <div className="relative">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-surface-400" />
            <input className="input pl-8" placeholder="Search name" value={employeeName} onChange={(e) => setEmployeeName(e.target.value)} />
          </div>
        </div>
        <div className="lg:col-span-2">
          <Input
            label="Employee ID"
            placeholder="Exact employee code"
            value={employeeId}
            onChange={(e) => setEmployeeId(e.target.value)}
          />
        </div>
      </div>

      {/* -------------------------------------------------- summary cards */}
      <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-4 xl:grid-cols-8">
        <StatCard label="Total records" value={summary?.total ?? 0} total={summary?.total ?? 0} />
        {SUMMARY_CODES.map((c) => (
          <StatCard key={c} label={SUMMARY_LABELS[c]} code={c}
            value={summary ? summary.byStatus[c] ?? 0 : 0} total={summary?.total ?? 0} />
        ))}
        <StatCard label="Other" value={others} total={summary?.total ?? 0} />
      </div>

      {/* -------------------------------------------------- table */}
      {isLoadingData ? (
        <div className="card p-12"><LoadingState /></div>
      ) : !data || data.records.length === 0 ? (
        <div className="card p-12">
          <EmptyState
            title="No attendance history found"
            description="Adjust the filters — or import historical attendance workbooks first."
          />
        </div>
      ) : (
        <div className="card overflow-hidden">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-surface-200 px-4 py-3">
            <div className="text-sm font-semibold text-surface-700">
              {data.totalElements.toLocaleString()} record{data.totalElements === 1 ? '' : 's'}
            </div>
            <div className="text-xs text-surface-400">Click an employee for their full history · click a date/status for source info</div>
          </div>
          <div className="overflow-x-auto">
            <table className="min-w-full border-collapse">
              <thead>
                <tr className="border-b border-surface-200">
                  {SORT_COLUMNS.map((c) => (
                    <ThSort key={c.key} label={c.label} k={c.key} sortBy={sortBy} sortDir={sortDir} onSort={toggleSort} />
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-surface-100">
                {data.records.map((r, i) => (
                  <tr key={`${r.employeeId}|${r.date}|${i}`} className="hover:bg-surface-50/60">
                    <td className="td">
                      <button type="button" className="cursor-pointer text-xs tabular-nums text-surface-700 underline-offset-2 hover:underline"
                        onClick={() => setSourceRec(r)}>
                        {r.date}
                      </button>
                    </td>
                    <td className="td">
                      <button type="button" className="cursor-pointer font-mono text-xs font-medium text-brand-600 underline-offset-2 hover:underline"
                        onClick={() => openEmployee(r)}>
                        {r.employeeId}
                      </button>
                    </td>
                    <td className="td">
                      <button type="button" className="flex cursor-pointer items-center gap-1.5 text-sm font-medium text-surface-800 underline-offset-2 hover:underline"
                        onClick={() => openEmployee(r)}>
                        <UserRound className="h-3.5 w-3.5 text-surface-400" />
                        {r.employeeName ?? '—'}
                      </button>
                    </td>
                    <td className="td text-xs text-surface-500">{r.teamName ?? '—'}</td>
                    <td className="td text-xs text-surface-500">{r.location ?? '—'}</td>
                    <td className="td text-xs text-surface-500">{formatShiftDisplay(r.shift)}</td>
                    <td className="td">
                      <button type="button" className="cursor-pointer" onClick={() => setSourceRec(r)}>
                        <StatusBadge code={r.statusCode} />
                      </button>
                    </td>
                    <td className="td text-xs text-surface-500">{r.sourceMonth ?? '—'}</td>
                    <td className="td text-xs text-surface-500">{r.sourceSheet ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination
            page={data.page}
            size={data.size}
            totalElements={data.totalElements}
            totalPages={data.totalPages}
            onPageChange={setPage}
          />
        </div>
      )}

      {/* -------------------------------------------------- source modal */}
      {sourceRec && <SourceModal record={sourceRec} onClose={() => setSourceRec(null)} />}

      {/* -------------------------------------------------- employee drill-down */}
      <Modal
        open={!!empRec}
        onClose={() => setEmpRec(null)}
        title={empRec ? `${empRec.employeeName ?? empRec.employeeId} — full history` : ''}
        size="lg"
      >
        {empRec && (
          <div>
            <div className="mb-3 flex flex-wrap items-center gap-2 text-xs text-surface-500">
              <span>Employee ID: <span className="font-mono font-medium text-surface-700">{empRec.employeeId}</span></span>
              <span>·</span>
              <span>Team: <span className="font-medium text-surface-700">{empRec.teamName ?? '—'}</span></span>
              <span>·</span>
              <span>Location: <span className="font-medium text-surface-700">{empRec.location ?? '—'}</span></span>
            </div>
            {!empData ? (
              <LoadingState label="Loading employee history…" />
            ) : empData.records.length === 0 ? (
              <EmptyState title="No history for this employee" />
            ) : (
              <>
                <div className="max-h-[50vh] overflow-auto">
                  <table className="min-w-full border-collapse">
                    <thead>
                      <tr className="border-b border-surface-200">
                        <th className="th">Date</th>
                        <th className="th">Status</th>
                        <th className="th">Location</th>
                        <th className="th">Shift</th>
                        <th className="th">Source Month</th>
                        <th className="th">Source Sheet</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-surface-100">
                      {empData.records.map((r, i) => (
                        <tr key={`${r.date}|${i}`} className="hover:bg-surface-50/60">
                          <td className="td">
                            <button type="button" className="cursor-pointer text-xs tabular-nums text-surface-700 underline-offset-2 hover:underline"
                              onClick={() => setSourceRec(r)}>
                              {r.date}
                            </button>
                          </td>
                          <td className="td"><StatusBadge code={r.statusCode} /></td>
                          <td className="td text-xs text-surface-500">{r.location ?? '—'}</td>
                          <td className="td text-xs text-surface-500">{formatShiftDisplay(r.shift)}</td>
                          <td className="td text-xs text-surface-500">{r.sourceMonth ?? '—'}</td>
                          <td className="td text-xs text-surface-500">{r.sourceSheet ?? '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <Pagination
                  page={empData.page}
                  size={empData.size}
                  totalElements={empData.totalElements}
                  totalPages={empData.totalPages}
                  onPageChange={setEmpPage}
                />
              </>
            )}
          </div>
        )}
      </Modal>
    </div>
  )
}