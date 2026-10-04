import { useEffect, useMemo, useRef, useState } from 'react'
import {
  ArrowDownUp, ChevronRight, ChevronLeft, Download, Loader2, Search, X,
} from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import toast from 'react-hot-toast'
import { adminApi } from '@/api'
import { cn, formatDate } from '@/utils'
import { formatShiftDisplay } from '@/utils/shift'
import { Button } from '@/components/ui/Button'
import { Pagination } from '@/components/ui/Pagination'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'
import { Modal } from '@/components/ui/Modal'
import { useTeam } from '@/hooks/useTeam'
import type {
  EmployeeSummary,
  EmployeeDetail,
  EmployeeDetailResponse,
  EmployeeStatsResponse,
  AnalyticsMetaResponse,
  AnalyticsQuery,
  AnalyticsReportMode,
} from '@/types'

const MONTHS = [
  { value: 1, label: 'January', short: 'Jan' }, { value: 2, label: 'February', short: 'Feb' },
  { value: 3, label: 'March', short: 'Mar' }, { value: 4, label: 'April', short: 'Apr' },
  { value: 5, label: 'May', short: 'May' }, { value: 6, label: 'June', short: 'Jun' },
  { value: 7, label: 'July', short: 'Jul' }, { value: 8, label: 'August', short: 'Aug' },
  { value: 9, label: 'September', short: 'Sep' }, { value: 10, label: 'October', short: 'Oct' },
  { value: 11, label: 'November', short: 'Nov' }, { value: 12, label: 'December', short: 'Dec' },
] as const

function formatMonthYear(month: number, year: number): string {
  return `${MONTHS.find(m => m.value === month)?.label ?? ''} ${year}`
}

function formatMonthYearShort(month: number, year: number): string {
  return `${MONTHS.find(m => m.value === month)?.short ?? ''} ${year}`
}

const SORTABLE_COLUMNS = [
  'employeeName', 'employeeId', 'location', 'shift', 'wfo', 'wfh', 'pl', 'sl', 'co', 'totalWorkingDays', 'shrinkage', 'totalWorkingDays'
] as const

type SortBy = typeof SORTABLE_COLUMNS[number]

export function AdminAnalyticsPage() {
  const navigate = useNavigate()
  const { teamId: globalTeamId, isAdmin } = useTeam()

  // Filters - use global team, remove local teamId/location/status/employeeId
  const [mode, setMode] = useState<'MONTHLY' | 'OVERALL'>('MONTHLY')
  const [month, setMonth] = useState<number>(new Date().getMonth() + 1)
  const [year, setYear] = useState<number>(new Date().getFullYear())
  const [employeeName, setEmployeeName] = useState<string | null>(null)
  const [page, setPage] = useState(0)
  const [size] = useState(30)
  const [sortBy, setSortBy] = useState<SortBy>('employeeName')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')
  const [searchOpen, setSearchOpen] = useState(false)

  // Data
  const [employees, setEmployees] = useState<EmployeeSummary[]>([])
  const [overallSummary, setOverallSummary] = useState<EmployeeSummary | null>(null)
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(0)

  // UI state
  const [isLoading, setIsLoading] = useState(false)
  const [isLoadingMeta, setIsLoadingMeta] = useState(true)
  const [meta, setMeta] = useState<AnalyticsMetaResponse['meta'] | null>(null)
  const [detailModalOpen, setDetailModalOpen] = useState(false)
  const [selectedEmployee, setSelectedEmployee] = useState<EmployeeDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)

  // Debounced search
  const [debouncedEmployeeName, setDebouncedEmployeeName] = useState('')
  const [debouncedEmployeeId, setDebouncedEmployeeId] = useState('')

  // Effective team ID: global team for admin, undefined for non-admin (uses their own team)
  const effectiveTeamId = isAdmin ? (globalTeamId ?? undefined) : undefined

  // Fetch meta on mount
  useEffect(() => {
    adminApi.analyticsMeta()
      .then(res => setMeta(res.meta))
      .catch(() => toast.error('Failed to load filter options'))
      .finally(() => setIsLoadingMeta(false))
  }, [])

  // Reload the location options when the roster scope changes
  useEffect(() => {
    adminApi.analyticsMeta({
      month: mode === 'MONTHLY' ? month : undefined,
      year: mode === 'MONTHLY' ? year : undefined,
      teamId: effectiveTeamId,
    })
      .then(res => setMeta(prev => (prev ? { ...prev, locations: res.meta.locations } : res.meta)))
      .catch(() => undefined)
  }, [mode, month, year, effectiveTeamId])

  // Debounced search
  useEffect(() => {
    const id = setTimeout(() => {
      setDebouncedEmployeeName(employeeName?.trim() ?? '')
    }, 350)
    return () => clearTimeout(id)
  }, [employeeName])

  // Any change to the query scope returns to the first page. Searching is how a former
  // employee is reached, and a new term routinely shrinks the result set below the page
  // the user is sitting on, which would otherwise render an empty table.
  const scopeKey = mode === 'MONTHLY'
    ? `${mode}|${month}|${year}|${effectiveTeamId}|${debouncedEmployeeName}`
    : `${mode}|${effectiveTeamId}|${debouncedEmployeeName}`
  const lastScopeKey = useRef(scopeKey)
  useEffect(() => {
    if (lastScopeKey.current === scopeKey) return
    lastScopeKey.current = scopeKey
    setPage(0)
  }, [scopeKey])

  useEffect(() => {
    const isMonthly = mode === 'MONTHLY'
    const query = {
      mode,
      // Monthly needs both parts: the backend resolves the month range from
      // month+year together, and without the year it would total all history.
      month: isMonthly ? month : undefined,
      year: isMonthly ? year : undefined,
      teamId: effectiveTeamId,
      employeeName: debouncedEmployeeName ?? undefined,
      page,
      size: 30,
      sortBy: sortBy === 'employeeName' ? undefined : sortBy,
      sortDir: sortBy === 'employeeName' ? undefined : (sortDir as 'asc' | 'desc' | undefined),
    }

    setIsLoading(true)
    adminApi.analyticsEmployees(query)
      .then(res => {
        setEmployees(res.employees)
        setOverallSummary(res.overallSummary)
        setTotalElements(res.totalElements)
        setTotalPages(res.totalPages)
      })
      .catch(() => toast.error('Failed to load analytics'))
      .finally(() => setIsLoading(false))
  }, [mode, month, year, effectiveTeamId, debouncedEmployeeName, page, sortBy, sortDir])

  // Rows surfaced only by the search (former / historical-only employees). Drives the
  // "Historical" badge and the banner, so a widened result set is never mistaken for
  // the current roster.
  const historicalCount = useMemo(
    () => employees.filter(e => e.inCurrentRoster === false).length,
    [employees],
  )

  const handleEmployeeClick = (employee: EmployeeSummary) => {
    setDetailLoading(true)
    adminApi.analyticsEmployeeDetail(employee.employeeId)
      .then(res => {
        setSelectedEmployee(res.detail)
        setDetailModalOpen(true)
      })
      .catch(() => toast.error('Failed to load employee detail'))
      .finally(() => setDetailLoading(false))
  }

  const handleExport = () => {
    const isMonthly = mode === 'MONTHLY'
    const request = {
      mode,
      month: isMonthly ? month : undefined,
      year: isMonthly ? year : undefined,
      teamId: effectiveTeamId,
      employeeId: debouncedEmployeeId || undefined,
    }
    adminApi.analyticsExport(request)
      .then(res => {
        const url = window.URL.createObjectURL(res)
        const a = document.createElement('a')
        a.href = url
        a.download = `attendance-analytics-${Date.now()}.csv`
        a.click()
        window.URL.revokeObjectURL(url)
        toast.success('Export downloaded')
      })
      .catch(() => toast.error('Export failed'))
  }

  const handleSort = (column: SortBy) => {
    if (sortBy === column) {
      setSortDir(sortDir === 'asc' ? 'desc' : 'asc')
    } else {
      setSortBy(column)
      setSortDir('asc')
    }
  }

  const handleReset = () => {
    setMode('MONTHLY')
    setMonth(new Date().getMonth() + 1)
    setYear(new Date().getFullYear())
    setEmployeeName(null)
    setSearchOpen(false)
    setPage(0)
    setSortBy('employeeName')
    setSortDir('asc')
  }

  const hasActiveFilters = employeeName

  const handleSortClick = (column: SortBy) => {
    handleSort(column)
    const isMonthly = mode === 'MONTHLY'
    const query = {
      mode,
      month: isMonthly ? month : undefined,
      year: isMonthly ? year : undefined,
      teamId: effectiveTeamId,
      employeeName: debouncedEmployeeName ?? undefined,
      page: 0,
      size: 30,
      sortBy: column,
      sortDir: (sortBy === column && sortDir === 'asc' ? 'desc' : 'asc') as 'asc' | 'desc',
    }
    setIsLoading(true)
    adminApi.analyticsEmployees(query)
      .then(res => {
        setEmployees(res.employees)
        setOverallSummary(res.overallSummary)
        setTotalElements(res.totalElements)
        setTotalPages(res.totalPages)
      })
      .catch(() => toast.error('Failed to load analytics'))
      .finally(() => setIsLoading(false))
  }

  return (
    <div className="attendance-analytics space-y-4 bg-surface-50">
      {/* Header */}
      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-3 bg-surface-50 ">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="h-6 w-0.5 bg-brand-500 rounded-full hidden sm:block" aria-hidden="true" />
            <h1 className="aa-page-title text-[22px] font-semibold leading-tight tracking-tight text-surface-900">Employee Attendance Analytics</h1>
          </div>
          <p className="aa-page-subtitle mt-1.5 text-[13px] text-surface-500 dark:text-surface-400">
            Unified view of historical Excel imports and website-maintained attendance records.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {/* Month Navigation (Monthly mode only) */}
          {mode === 'MONTHLY' && (
            <div className="aa-monthnav inline-flex items-center divide-x divide-surface-200 overflow-hidden rounded-lg border border-surface-200 bg-transparent">
              <button
                type="button"
                onClick={() => {
                  if (month === 1) {
                    setMonth(12)
                    setYear(y => y - 1)
                  } else {
                    setMonth(m => m - 1)
                  }
                }}
                className="aa-monthnav-btn inline-flex h-8 w-8 shrink-0 items-center justify-center bg-transparent text-surface-500 transition-colors hover:bg-surface-100 hover:text-surface-700 disabled:cursor-not-allowed disabled:opacity-30 disabled:hover:bg-transparent"
                aria-label="Previous month"
              >
                <ChevronLeft className="h-4 w-4" />
              </button>
              <span className="aa-monthnav-label inline-flex h-8 min-w-[48px] select-none items-center justify-center px-2 text-[13px] font-medium uppercase tracking-wide text-surface-800">
                {MONTHS[month - 1]?.short}
              </span>
              <button
                type="button"
                onClick={() => {
                  const now = new Date()
                  const currentMonth = now.getMonth() + 1
                  const currentYear = now.getFullYear()
                  const isCurrentOrFuture = year > currentYear || (year === currentYear && month >= currentMonth)
                  if (!isCurrentOrFuture) {
                    if (month === 12) {
                      setMonth(1)
                      setYear(y => y + 1)
                    } else {
                      setMonth(m => m + 1)
                    }
                  }
                }}
                disabled={year > new Date().getFullYear() || (year === new Date().getFullYear() && month >= new Date().getMonth() + 1)}
                className="aa-monthnav-btn inline-flex h-8 w-8 shrink-0 items-center justify-center bg-transparent text-surface-500 transition-colors hover:bg-surface-100 hover:text-surface-700 disabled:cursor-not-allowed disabled:opacity-30 disabled:hover:bg-transparent disabled:cursor-not-allowed disabled:opacity-30"
                aria-label="Next month"
              >
                <ChevronRight className="h-4 w-4" />
              </button>
            </div>
          )}

          {/* Monthly | Overall Toggle */}
          <div className="aa-mode-toggle inline-flex items-center rounded-lg bg-surface-100 dark:bg-surface-100 border border-surface-200 dark:border-surface-200 p-0.5" role="group" aria-label="Report mode">
            <button
              type="button"
              onClick={() => setMode('MONTHLY')}
              className={cn(
                'aa-mode-option rounded-md px-3 py-1.5 text-[13px] font-medium transition-colors duration-150 whitespace-nowrap',
                mode === 'MONTHLY'
                  ? 'bg-brand-500 text-white'
                  : 'bg-transparent text-surface-700 dark:text-surface-700 font-normal hover:bg-surface-200 dark:hover:bg-surface-200 transition-colors duration-150'
              )}
              aria-pressed={mode === 'MONTHLY'}
            >
              Monthly
            </button>
            <button
              type="button"
              onClick={() => setMode('OVERALL')}
              className={cn(
                'aa-mode-option rounded-md px-3 py-1.5 text-[13px] font-medium transition-colors duration-150 whitespace-nowrap',
                mode === 'OVERALL'
                  ? 'bg-brand-500 text-white'
                  : 'bg-transparent text-surface-700 dark:text-surface-700 font-normal hover:bg-surface-200 dark:hover:bg-surface-200 transition-colors duration-150'
              )}
              aria-pressed={mode === 'OVERALL'}
            >
              Overall
            </button>
          </div>

          {/* Search Icon Button - Popover */}
          <div className="relative">
            <button
              type="button"
              onClick={() => setSearchOpen(!searchOpen)}
              className="inline-flex items-center justify-center rounded-lg p-2 text-surface-500 dark:text-surface-400 hover:bg-surface-100 dark:hover:bg-surface-800 transition-colors"
              aria-label="Search employees"
              aria-expanded={searchOpen}
              aria-haspopup="true"
            >
              <Search className="h-4 w-4" />
            </button>

            {searchOpen && (
              <div
                className="absolute right-0 top-full mt-1.5 z-50 w-[320px] bg-surface-50  rounded-lg border border-surface-200/60  shadow-lg py-2"
                role="menu"
              >
                <div className="relative">
                  <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-surface-400" />
                  <input
                    type="text"
                    className="input h-[38px] pl-9 pr-9 w-full focus:ring-2 focus:ring-brand-500/30 bg-white  border-surface-300/50 dark:border-surface-600/50"
                    placeholder="Search by ID or name"
                    value={employeeName ?? ''}
                    onChange={e => setEmployeeName(e.target.value)}
                    autoFocus
                    onKeyDown={e => e.key === 'Escape' && setSearchOpen(false)}
                  />
                  {hasActiveFilters && (
                    <button
                      type="button"
                      onClick={() => { handleReset(); setSearchOpen(false); }}
                      className="absolute right-2.5 top-1/2 -translate-y-1/2 text-surface-500 dark:text-surface-400 hover:text-brand-600 dark:hover:text-brand-400"
                      aria-label="Clear search"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  )}
                </div>
              </div>
            )}

            <div
              className={searchOpen ? 'fixed inset-0 z-40' : 'hidden'}
              onClick={() => setSearchOpen(false)}
              aria-hidden="true"
            />
          </div>

          {/* Export Excel */}
          <Button variant="secondary" size="sm" onClick={handleExport} disabled={employees.length === 0}>
            <Download className="h-4 w-4" /> Export Excel
          </Button>
        </div>
      </div>

      {/* Results */}
      {isLoadingMeta ? (
        <div className="aa-card card border border-surface-200/60  bg-white  p-12"><LoadingState /></div>
      ) : isLoading ? (
        <div className="aa-card card border border-surface-200/60  bg-white  p-12"><LoadingState /></div>
      ) : employees.length === 0 ? (
        <div className="aa-card card border border-surface-200/60  bg-white  p-12">
          <EmptyState
            title="No attendance data"
            description="Adjust the filters or import historical attendance workbooks first."
          />
        </div>
      ) : (
        <>
          {/* Employee Table with Heading */}
          <div className="aa-card card border border-surface-200/60  overflow-hidden bg-white ">
            {/* Table Heading with Month and Count */}
            <div className="aa-titlebar flex items-center justify-between px-4 py-2.5 border-b border-surface-200/60  bg-surface-50 ">
              <h2 className="aa-titlebar-label text-[13px] font-medium text-surface-700 dark:text-surface-700">
                {mode === 'MONTHLY' ? formatMonthYear(month, year) : 'All-Time Overview'}
              </h2>
              <span className="aa-titlebar-count text-[12px] text-surface-500 dark:text-surface-400 font-medium tabular-nums">
                {employees.length}/{totalElements.toLocaleString()}
              </span>
            </div>
            {historicalCount > 0 && (
              <div className="flex items-start gap-2 border-b border-amber-200/60 dark:border-amber-500/30 bg-amber-50/50 dark:bg-amber-500/10 px-4 py-2.5 text-xs text-amber-800 dark:text-amber-200">
                <Search className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                <p>
                  Showing <strong>{historicalCount}</strong> historical{' '}
                  {historicalCount === 1 ? 'employee' : 'employees'} who are not on the{' '}
                  {mode === 'MONTHLY' ? `${formatMonthYear(month, year)} roster` : 'any roster'}
                  , matched by your search. Clear the search to return to the
                  current roster only.
                </p>
              </div>
            )}
            <div className="overflow-x-auto">
              <table
                className="aa-table table-fixed border-collapse"
                style={{ minWidth: 1294 }}
              >
                <colgroup>
                  <col key={0} style={{ width: 52 }} />
                  <col key={1} style={{ width: 105 }} />
                  <col key={2} style={{ width: 100 }} />
                  <col key={3} style={{ width: 55 }} />
                  <col key={4} style={{ width: 85 }} />
                  <col key={5} style={{ width: 85 }} />
                  <col key={6} style={{ width: 50 }} />
                  <col key={7} style={{ width: 50 }} />
                  <col key={8} style={{ width: 50 }} />
                  <col key={9} style={{ width: 50 }} />
                  <col key={10} style={{ width: 50 }} />
                  <col key={11} style={{ width: 50 }} />
                  <col key={12} style={{ width: 50 }} />
                  <col key={13} style={{ width: 62 }} />
                  <col key={14} style={{ width: 50 }} />
                  <col key={15} style={{ width: 68 }} />
                  <col key={16} style={{ width: 68 }} />
                  <col key={17} style={{ width: 68 }} />
                  <col key={18} style={{ width: 68 }} />
                  <col key={19} style={{ width: 78 }} />
                </colgroup>
                <thead>
                  <tr className="border-b border-surface-200/60  bg-surface-50 ">
                    <th style={{ left: 0 }} className="sticky z-20 bg-white border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-surface-600 dark:text-surface-400 text-center"><div className="flex items-center gap-1 justify-center"><span>S.No.</span></div></th>
                    <th style={{ left: 52 }} className="sticky z-20 bg-white border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-surface-600 dark:text-surface-400 text-left"><div className="flex items-center gap-1 "><span>Emp ID</span><button onClick={() => handleSortClick('employeeId')} className="ml-1 p-0.5 hover:bg-surface-200/50 dark:hover:bg-surface-700/50 rounded"><ArrowDownUp className="h-3 w-3 text-surface-400" /></button></div></th>
                    <th style={{ left: 157 }} className="sticky z-20 bg-white border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-surface-600 dark:text-surface-400 text-left"><div className="flex items-center gap-1 min-w-0"><span className="truncate">Name</span><button onClick={() => handleSortClick('employeeName')} className="ml-1 p-0.5 hover:bg-surface-200/50 dark:hover:bg-surface-700/50 rounded flex-shrink-0"><ArrowDownUp className="h-3 w-3 text-surface-400" /></button></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-surface-600 dark:text-surface-400 text-left"><div className="flex items-center gap-1 min-w-0"><span className="truncate">Location</span><button onClick={() => handleSortClick('location')} className="ml-1 p-0.5 hover:bg-surface-200/50 dark:hover:bg-surface-700/50 rounded flex-shrink-0"><ArrowDownUp className="h-3 w-3 text-surface-400" /></button></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-surface-600 dark:text-surface-400 text-left"><div className="flex items-center gap-1 min-w-0"><span className="truncate">Shift</span><button onClick={() => handleSortClick('shift')} className="ml-1 p-0.5 hover:bg-surface-200/50 dark:hover:bg-surface-700/50 rounded flex-shrink-0"><ArrowDownUp className="h-3 w-3 text-surface-400" /></button></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-surface-600 dark:text-surface-400 text-center"><div className="flex items-center gap-1 justify-center"><span>Week Off</span></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-emerald-600 dark:text-emerald-400 text-center"><div className="flex items-center gap-1 justify-center"><span>WFO</span></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-sky-600 dark:text-sky-400 text-center"><div className="flex items-center gap-1 justify-center"><span>WFH</span></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-slate-500 dark:text-slate-400 text-center"><div className="flex items-center gap-1 justify-center"><span>WO</span></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-amber-600 dark:text-amber-400 text-center"><div className="flex items-center gap-1 justify-center"><span>PL</span></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-violet-600 dark:text-violet-400 text-center"><div className="flex items-center gap-1 justify-center"><span>CO</span></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-rose-600 dark:text-rose-400 text-center"><div className="flex items-center gap-1 justify-center"><span>SL</span></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-amber-600 dark:text-amber-400 text-center"><div className="flex items-center gap-1 justify-center"><span>HD</span></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-teal-600 dark:text-teal-400 text-center"><div className="flex items-center gap-1 justify-center"><span>WK WRK</span></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-surface-600 dark:text-surface-400 text-center"><div className="flex items-center gap-1 justify-center"><span>Shrinkage</span><button onClick={() => handleSortClick('shrinkage')} className="ml-1 p-0.5 hover:bg-surface-200/50 dark:hover:bg-surface-700/50 rounded"><ArrowDownUp className="h-3 w-3 text-surface-400" /></button></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-surface-600 dark:text-surface-400 text-center"><div className="flex items-center gap-1 justify-center"><span>ATR</span></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-surface-600 dark:text-surface-400 text-center"><div className="flex items-center gap-1 justify-center"><span>Total Work Days</span><button onClick={() => handleSortClick('totalWorkingDays')} className="ml-1 p-0.5 hover:bg-surface-200/50 dark:hover:bg-surface-700/50 rounded"><ArrowDownUp className="h-3 w-3 text-surface-400" /></button></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-surface-600 dark:text-surface-400 text-center"><div className="flex items-center gap-1 justify-center"><span>Total Leaves</span></div></th>
                    <th className="border-r border-surface-200/60  px-2.5 py-2.5 text-xs font-medium text-teal-600 dark:text-teal-400 text-center"><div className="flex items-center gap-1 justify-center"><span>Attendance %</span></div></th>
                    <th className="sticky right-0 z-20 border-r border-surface-200/60  px-2.5 py-2.5 text-center text-xs font-medium text-surface-600 dark:text-surface-400 bg-white ">Recorded</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-surface-200/40 ">
                  {employees.map((emp, idx) => (
                    <tr
                      key={emp.employeeId}
                      className={cn(
                        'hover:bg-surface-50  cursor-pointer transition-colors duration-150',
                        idx % 2 === 0 ? 'bg-white ' : 'bg-surface-50'
                      )}
                      onClick={() => handleEmployeeClick(emp)}
                    >
                      <td style={{ left: 0 }} className="sticky z-10 bg-white  hover:bg-surface-50  border-r border-surface-200/40  px-2.5 py-2 text-center text-xs text-surface-500 dark:text-surface-400">{emp.sNo}</td>
                      <td style={{ left: 52 }} className="sticky z-10 bg-white  hover:bg-surface-50  border-r border-surface-200/40  px-2.5 py-2 font-mono text-xs font-medium text-surface-700 dark:text-surface-700">{emp.employeeId}</td>
                      <td style={{ left: 157 }} className="aa-col-name sticky z-10 bg-white  hover:bg-surface-50  border-r border-surface-200/40  px-2.5 py-2 text-[13px] font-medium text-surface-900 min-w-0"><div className="flex min-w-0 items-center gap-1.5"><span className="min-w-0 truncate" title={emp.employeeName}>{emp.employeeName}</span>{emp.inCurrentRoster === false && (<span title="Not on the selected month's roster. Shown because of the current search." className="shrink-0 rounded border border-amber-300/60 bg-amber-50/60 px-1 py-px text-[10px] font-medium uppercase tracking-wide text-amber-700 dark:border-amber-500/40 dark:bg-amber-500/10 dark:text-amber-300">Historical</span>)}</div></td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-xs text-surface-500 dark:text-surface-400 min-w-0 truncate" title={emp.location ?? undefined}>{emp.location ?? '—'}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-xs text-surface-500 dark:text-surface-400 min-w-0 truncate" title={emp.shift ?? undefined}>{formatShiftDisplay(emp.shift)}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-center text-xs text-surface-500 dark:text-surface-400">{emp.weekOff?.split(' (')[0] ?? '—'}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-center text-xs font-medium text-emerald-600 dark:text-emerald-400">{emp.wfo}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-center text-xs font-medium text-sky-600 dark:text-sky-400">{emp.wfh}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-center text-xs font-medium text-slate-500 dark:text-slate-400">{emp.wo}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-center text-xs font-medium text-amber-600 dark:text-amber-400">{emp.pl}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-center text-xs font-medium text-violet-600 dark:text-violet-400">{emp.co}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-center text-xs font-medium text-rose-600 dark:text-rose-400">{emp.sl}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-center text-xs font-medium text-amber-600 dark:text-amber-400">{emp.hd}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-center text-xs font-medium text-teal-600 dark:text-teal-400">{emp.wkWrk}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-center text-xs font-medium">{emp.shrinkage > 25 ? <span className="text-red-600 dark:text-red-400">{emp.shrinkage.toFixed(1)}%</span> : emp.shrinkage > 15 ? <span className="text-amber-600 dark:text-amber-400">{emp.shrinkage.toFixed(1)}%</span> : <span className="text-emerald-600 dark:text-emerald-400">{emp.shrinkage.toFixed(1)}%</span>}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-center text-xs font-medium text-slate-600 dark:text-slate-400">{emp.atr}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-center text-xs font-medium text-surface-700 dark:text-surface-700">{emp.totalWorkingDays.toLocaleString()}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-center text-xs font-medium text-amber-600 dark:text-amber-400">{emp.totalLeaves}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2 text-center text-xs font-semibold text-teal-600 dark:text-teal-400">{emp.attendancePercentage.toFixed(1)}%</td>
                      <td className="sticky right-0 z-10 bg-white  hover:bg-surface-50  border-r border-surface-200/40  px-2.5 py-2 text-center tabular-nums text-xs font-medium text-surface-700 dark:text-surface-700">{emp.attendanceRecorded}</td>
                    </tr>
                  ))}
                  {overallSummary && (
                    <tr className="aa-row-summary bg-surface-50  font-semibold border-t border-surface-200/60 ">
                      <td style={{ left: 0 }} className="sticky z-10 bg-white border-r border-surface-200/40  px-2.5 py-2.5 text-center text-xs text-surface-500 dark:text-surface-400">—</td>
                      <td style={{ left: 52 }} className="sticky z-10 bg-white border-r border-surface-200/40  px-2.5 py-2.5 font-mono text-xs font-medium text-surface-700 dark:text-surface-700">—</td>
                      <td style={{ left: 157 }} className="aa-col-name sticky z-10 bg-white border-r border-surface-200/40  px-2.5 py-2.5 truncate text-[13px] font-medium text-surface-900 min-w-0">Overall Summary</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 truncate text-xs text-surface-500 dark:text-surface-400 min-w-0">—</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 truncate text-xs text-surface-500 dark:text-surface-400 min-w-0">—</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 text-center text-xs text-surface-500 dark:text-surface-400">—</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 text-center text-xs font-semibold text-emerald-600 dark:text-emerald-400">{overallSummary.wfo}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 text-center text-xs font-semibold text-sky-600 dark:text-sky-400">{overallSummary.wfh}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 text-center text-xs font-semibold text-slate-500 dark:text-slate-400">{overallSummary.wo}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 text-center text-xs font-semibold text-amber-600 dark:text-amber-400">{overallSummary.pl}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 text-center text-xs font-semibold text-violet-600 dark:text-violet-400">{overallSummary.co}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 text-center text-xs font-semibold text-rose-600 dark:text-rose-400">{overallSummary.sl}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 text-center text-xs font-semibold text-amber-600 dark:text-amber-400">{overallSummary.hd}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 text-center text-xs font-semibold text-teal-600 dark:text-teal-400">{overallSummary.wkWrk}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 text-center text-xs font-semibold text-red-600 dark:text-red-400">{overallSummary.shrinkage.toFixed(1)}%</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 text-center text-xs font-semibold text-slate-600 dark:text-slate-400">{overallSummary.atr}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 text-center text-xs font-semibold text-surface-700 dark:text-surface-700">{overallSummary.totalWorkingDays.toLocaleString()}</td>
                      <td className="border-r border-surface-200/40  px-2.5 py-2.5 text-center text-xs font-semibold text-amber-600 dark:text-amber-400">{overallSummary.totalLeaves}</td>
                      <td className="sticky right-0 z-10 bg-white border-r border-surface-200/40  px-2.5 py-2.5 text-center tabular-nums text-xs font-semibold text-surface-700 dark:text-surface-700">{overallSummary.attendanceRecorded}</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>

            <div className="px-1 pt-2">
              <Pagination
                page={page}
                size={30}
                totalElements={totalElements}
                totalPages={totalPages}
                onPageChange={setPage}
              />
            </div>
          </div>
        </>
      )}

      {/* Detail Modal */}
      <Modal
        open={detailModalOpen}
        onClose={() => setDetailModalOpen(false)}
        size="2xl"
        title={selectedEmployee ? `${selectedEmployee.employeeName} (${selectedEmployee.employeeId})` : 'Employee Details'}
      >
        {detailLoading ? (
          <div className="flex items-center justify-center h-64"><LoadingState /></div>
        ) : selectedEmployee ? (
          <div className="attendance-analytics space-y-6">
            {/* Overview Cards */}
            <div className="grid grid-cols-2 md:grid-cols-6 gap-4">
              <StatCard label="Working Days" value={selectedEmployee.totalWorkingDays} color="blue" />
              <StatCard label="WFO" value={selectedEmployee.wfo} color="emerald" />
              <StatCard label="WFH" value={selectedEmployee.wfh} color="sky" />
              <StatCard label="WO" value={selectedEmployee.wo} color="slate" />
              <StatCard label="PL" value={selectedEmployee.pl} color="violet" />
              <StatCard label="CO" value={selectedEmployee.co} color="blue" />
              <StatCard label="SL" value={selectedEmployee.sl} color="rose" />
              <StatCard label="HD" value={selectedEmployee.hd} color="amber" />
              <StatCard label="WK WRK" value={selectedEmployee.wkWrk} color="orange" />
              <StatCard label="Shrinkage %" value={Math.round(selectedEmployee.shrinkage * 10) / 10} color="red" />
              <StatCard label="ATR" value={selectedEmployee.atr === 'Yes' ? 1 : 0} color="red" />
              <StatCard label="Total Leaves" value={selectedEmployee.totalLeaves} color="amber" />
            </div>

            {/* Employee Info */}
            <div className="grid grid-cols-2 gap-4 text-sm">
              <div>
                <p className="text-surface-500 dark:text-surface-400">Employee ID</p>
                <p className="font-medium text-surface-900 dark:text-surface-900">{selectedEmployee.employeeId}</p>
              </div>
              <div>
                <p className="text-surface-500 dark:text-surface-400">Email</p>
                <p className="font-medium text-surface-900 dark:text-surface-900">{selectedEmployee.employeeEmail ?? '—'}</p>
              </div>
              <div>
                <p className="text-surface-500 dark:text-surface-400">Team</p>
                <p className="font-medium text-surface-900 dark:text-surface-900">{selectedEmployee.teamName ?? '—'}</p>
              </div>
              <div>
                <p className="text-surface-500 dark:text-surface-400">Location</p>
                <p className="font-medium text-surface-900 dark:text-surface-900">{selectedEmployee.location ?? '—'}</p>
              </div>
              <div>
                <p className="text-surface-500 dark:text-surface-400">Shift</p>
                <p className="font-medium text-surface-900 dark:text-surface-900">{formatShiftDisplay(selectedEmployee.shift)}</p>
              </div>
              <div>
                <p className="text-surface-500 dark:text-surface-400">Week Off</p>
                <p className="font-medium text-surface-900 dark:text-surface-900">{selectedEmployee.weekOff?.split(' (')[0] ?? '—'}</p>
              </div>
              <div>
                <p className="text-surface-500 dark:text-surface-400">Joining Date</p>
                <p className="font-medium text-surface-900 dark:text-surface-900">{selectedEmployee.joiningDate ?? '—'}</p>
              </div>
              <div>
                <p className="text-surface-500 dark:text-surface-400">Status</p>
                <p className="font-medium text-surface-900 dark:text-surface-900">{selectedEmployee.employmentStatus}</p>
              </div>
            </div>

{/* Monthly Breakdown */}
            {selectedEmployee.monthlyBreakdown.length > 0 && (
              <div>
                <h4 className="mb-3 text-sm font-semibold text-surface-700 dark:text-surface-700">Monthly Breakdown</h4>
                <div className="overflow-x-auto">
                  <table
                    className="aa-table table-fixed border-separate border-spacing-0"
                    style={{ minWidth: 1024 }}
                  >
                    <colgroup>
                      <col key={0} style={{ width: 128 }} />
                      <col key={1} style={{ width: 96 }} />
                      <col key={2} style={{ width: 80 }} />
                      <col key={3} style={{ width: 80 }} />
                      <col key={4} style={{ width: 80 }} />
                      <col key={5} style={{ width: 80 }} />
                      <col key={6} style={{ width: 80 }} />
                      <col key={7} style={{ width: 80 }} />
                      <col key={8} style={{ width: 80 }} />
                      <col key={9} style={{ width: 80 }} />
                      <col key={10} style={{ width: 80 }} />
                      <col key={11} style={{ width: 80 }} />
                    </colgroup>
                    <thead>
                      <tr className="border-b border-surface-200/60  bg-surface-50 ">
                        <th className="w-32 border-r border-surface-200/60  px-3 py-2 text-left text-xs font-medium text-surface-600 dark:text-surface-400">Month</th>
                        <th className="w-24 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-surface-600 dark:text-surface-400">Work Days</th>
                        <th className="w-20 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-emerald-600 dark:text-emerald-400">WFO</th>
                        <th className="w-20 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-sky-600 dark:text-sky-400">WFH</th>
                        <th className="w-20 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-slate-500 dark:text-slate-400">WO</th>
                        <th className="w-20 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-amber-600 dark:text-amber-400">PL</th>
                        <th className="w-20 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-violet-600 dark:text-violet-400">CO</th>
                        <th className="w-20 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-rose-600 dark:text-rose-400">SL</th>
                        <th className="w-20 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-amber-600 dark:text-amber-400">HD</th>
                        <th className="w-20 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-teal-600 dark:text-teal-400">WK WRK</th>
                        <th className="w-20 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-surface-600 dark:text-surface-400">Shrinkage %</th>
                        <th className="w-20 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-surface-600 dark:text-surface-400">Total Leaves</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-surface-200/40 ">
                      {selectedEmployee.monthlyBreakdown.map((m, i) => (
                        <tr key={i} className={cn(
                          'hover:bg-surface-50  transition-colors duration-150',
                          i % 2 === 0 ? 'bg-white ' : 'bg-surface-50'
                        )}>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-sm font-medium text-surface-700 dark:text-surface-700">{m.month}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center tabular-nums text-xs text-surface-700 dark:text-surface-700">{m.workingDays}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center tabular-nums text-xs font-medium text-emerald-600 dark:text-emerald-400">{m.wfo}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center tabular-nums text-xs font-medium text-sky-600 dark:text-sky-400">{m.wfh}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center tabular-nums text-xs font-medium text-slate-500 dark:text-slate-400">{m.wo}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center tabular-nums text-xs font-medium text-amber-600 dark:text-amber-400">{m.pl}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center tabular-nums text-xs font-medium text-violet-600 dark:text-violet-400">{m.co}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center tabular-nums text-xs font-medium text-rose-600 dark:text-rose-400">{m.sl}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center tabular-nums text-xs font-medium text-amber-600 dark:text-amber-400">{m.hd}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center tabular-nums text-xs font-medium text-teal-600 dark:text-teal-400">{m.wkWrk}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center tabular-nums text-xs font-medium">{m.shrinkage > 25 ? <span className="text-red-600 dark:text-red-400">{m.shrinkage.toFixed(1)}%</span> : m.shrinkage > 15 ? <span className="text-amber-600 dark:text-amber-400">{m.shrinkage.toFixed(1)}%</span> : <span className="text-emerald-600 dark:text-emerald-400">{m.shrinkage.toFixed(1)}%</span>}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center tabular-nums text-xs font-medium text-amber-600 dark:text-amber-400">{m.totalLeaves}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
            {/* Attendance History */}
            {selectedEmployee.attendanceHistory.length > 0 ? (
              <div>
                <h4 className="mb-3 text-sm font-semibold text-surface-700 dark:text-surface-700">Attendance History</h4>
                <div className="overflow-x-auto max-h-80">
                  <table
                    className="aa-table table-fixed border-separate border-spacing-0"
                    style={{ minWidth: 672 }}
                  >
                    <colgroup>
                    <col key={0} style={{ width: 112 }} />
                    <col key={1} style={{ width: 80 }} />
                    <col key={2} style={{ width: 96 }} />
                    <col key={3} style={{ width: 160 }} />
                    <col key={4} style={{ width: 112 }} />
                    <col key={5} style={{ width: 112 }} />
                    </colgroup>
                    <thead>
                      <tr className="border-b border-surface-200/60  sticky top-0 bg-white ">
                        <th className="w-28 border-r border-surface-200/60  px-3 py-2 text-left text-xs font-medium text-surface-600 dark:text-surface-400">Date</th>
                        <th className="w-20 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-surface-600 dark:text-surface-400">Day</th>
                        <th className="w-24 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-surface-600 dark:text-surface-400">Status</th>
                        <th className="w-40 border-r border-surface-200/60  px-3 py-2 text-left text-xs font-medium text-surface-600 dark:text-surface-400">Description</th>
                        <th className="w-28 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-surface-600 dark:text-surface-400">Source</th>
                        <th className="w-28 border-r border-surface-200/60  px-3 py-2 text-center text-xs font-medium text-surface-600 dark:text-surface-400">Updated</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-surface-200/40 ">
                      {selectedEmployee.attendanceHistory.slice(0, 100).map((h, i) => (
                        <tr key={i} className={cn(
                          'hover:bg-surface-50  transition-colors duration-150',
                          i % 2 === 0 ? 'bg-white ' : 'bg-surface-50'
                        )}>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-xs text-surface-700 dark:text-surface-700">{h.date}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center text-xs text-surface-700 dark:text-surface-700">{h.day}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center text-xs font-medium">
                            <span className={cn(
                              'inline-flex items-center justify-center rounded px-1.5 py-0.5 text-[10px] font-bold',
                              h.status === 'WFO' && 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400',
                              h.status === 'WFH' && 'bg-sky-100 text-sky-700 dark:bg-sky-900/30 dark:text-sky-400',
                              h.status === 'PL' && 'bg-violet-100 text-violet-700 dark:bg-violet-900/30 dark:text-violet-400',
                              h.status === 'SL' && 'bg-rose-100 text-rose-700 dark:bg-rose-900/30 dark:text-rose-400',
                              h.status === 'CO' && 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
                              h.status === 'WO' && 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-400',
                              h.status === 'HPEH' && 'bg-teal-100 text-teal-700 dark:bg-teal-900/30 dark:text-teal-400',
                              h.status === 'FL' && 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400',
                              'bg-surface-200/50 text-surface-700 dark:bg-surface-700 dark:text-surface-700'
                            )}>
                              {h.status}
                            </span>
                          </td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-xs truncate text-surface-600 dark:text-surface-400">{h.description ?? '—'}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center text-xs text-surface-500 dark:text-surface-400">{h.originalAuthor}</td>
                          <td className="border-r border-surface-200/40  px-3 py-2 text-center text-xs text-surface-500 dark:text-surface-400">{h.lastUpdated || '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            ) : (
              <div className="flex items-center justify-center h-64 text-surface-500 dark:text-surface-400">Select an employee to view details</div>
            )}
          </div>
        ) : (
          <div className="flex items-center justify-center h-64 text-surface-500 dark:text-surface-400">Select an employee to view details</div>
        )}
      </Modal>
    </div>
  )
}

function StatCard({ label, value, color }: { label: string; value: number; color: string }) {
  const colorMap: Record<string, string> = {
    blue: 'bg-blue-50/60 text-blue-700 dark:bg-blue-900/15 dark:text-blue-400 border border-blue-100/50 dark:border-blue-800/30',
    emerald: 'bg-emerald-50/60 text-emerald-700 dark:bg-emerald-900/15 dark:text-emerald-400 border border-emerald-100/50 dark:border-emerald-800/30',
    sky: 'bg-sky-50/60 text-sky-700 dark:bg-sky-900/15 dark:text-sky-400 border border-sky-100/50 dark:border-sky-800/30',
    violet: 'bg-violet-50/60 text-violet-700 dark:bg-violet-900/15 dark:text-violet-400 border border-violet-100/50 dark:border-violet-800/30',
    rose: 'bg-rose-50/60 text-rose-700 dark:bg-rose-900/15 dark:text-rose-400 border border-rose-100/50 dark:border-rose-800/30',
    slate: 'bg-slate-50/60 text-slate-700 dark:bg-slate-800/15 dark:text-slate-400 border border-slate-100/50 dark:border-slate-800/30',
    cyan: 'bg-cyan-50/60 text-cyan-700 dark:bg-cyan-900/15 dark:text-cyan-400 border border-cyan-100/50 dark:border-cyan-800/30',
    orange: 'bg-orange-50/60 text-orange-700 dark:bg-orange-900/15 dark:text-orange-400 border border-orange-100/50 dark:border-orange-800/30',
    red: 'bg-red-50/60 text-red-700 dark:bg-red-900/15 dark:text-red-400 border border-red-100/50 dark:border-red-800/30',
    amber: 'bg-amber-50/60 text-amber-700 dark:bg-amber-900/15 dark:text-amber-400 border border-amber-100/50 dark:border-amber-800/30',
  }
  return (
    <div className={cn('rounded-lg p-4', colorMap[color] || colorMap.blue)}>
      <p className="text-xs font-medium uppercase tracking-wide text-surface-500 dark:text-surface-400">{label}</p>
      <p className="mt-1 text-2xl font-bold tabular-nums text-surface-900 dark:text-surface-900">{typeof value === 'number' ? value.toLocaleString() : value}</p>
    </div>
  )
}

function ChevronUp({ className }: { className?: string }) {
  return (
    <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M5 15l7-7 7 7" />
    </svg>
  )
}

function ChevronDown({ className }: { className?: string }) {
  return (
    <svg className={className} fill="none" stroke="currentColor" viewBox="0 0 24 24" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
    </svg>
  )
}