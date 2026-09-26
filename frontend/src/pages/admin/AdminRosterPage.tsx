import { createPortal } from 'react-dom'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router-dom'
import {
  AlertTriangle, CalendarRange, Check, ChevronLeft, ChevronRight, Eraser, Save, Search, X,
} from 'lucide-react'
import toast from 'react-hot-toast'
import type { RosterCellEdit, RosterEmployeeRow, RosterPageMeta, RosterStatusDetail, RosterMonthlyData, RosterTodayData, RosterDayInfo } from '@/types'
import { adminApi } from '@/api'
import { extractMessage } from '@/api/client'
import { cn, formatDate } from '@/utils'
import { formatShiftDisplay, formatShiftTime } from '@/utils/shift'
import { Button } from '@/components/ui/Button'
import { Select } from '@/components/ui/Select'
import { Pagination } from '@/components/ui/Pagination'
import { LoadingState } from '@/components/ui/LoadingState'
import { EmptyState } from '@/components/ui/EmptyState'
import { useAuth } from '@/hooks/useAuth'
import { RosterLegend, monthLabel } from '@/components/roster/RosterLegend'
import { ROSTER_STATUS_CODES, getAttendanceCellStyle, statusLabel } from '@/constants/rosterStatus'

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

/** Week Off display — strips any shift timing enclosed in parentheses
 *  (e.g. "Sun-Mon (9-6)" -> "Sun-Mon"). The raw employee value is untouched. */
function weekOffDisplay(weekOff: string | null | undefined): string {
  if (!weekOff) return ''
  const cleaned = weekOff.replace(/\s*\([^)]*\)/g, '').trim()
  return cleaned || weekOff.trim()
}

const SHIFT_GROUP_COUNT = 6

/** Normalize a shift string so harmless formatting differences (case, extra
 *  whitespace around the dash) map to the same shift group. */
function normalizeShift(shift: string | null | undefined): string | null {
  if (!shift) return null
  const s = shift.trim().toLowerCase().replace(/\s+/g, ' ')
  return s || null
}

/**
 * Frozen employee columns. The panel (`.roster-frozen-panel`, sticky left) owns
 * ALL left-side pinning — individual cells carry no `sticky left-*` offsets.
 * The panel is a separate table that never participates in the day matrix's
 * horizontal scroll, so the frozen area can never move or bleed through.
 * Widths sum to 45+90+190+75+150+90 = 640px (matches the old cumulative offset).
 */
const FROZEN_COL = {
  sno: 'w-[45px] text-center',
  empId: 'w-[90px]',
  employee: 'w-[190px]',
  loc: 'w-[75px]',
  shift: 'w-[150px]',
  weekOff: 'w-[90px]',
} as const

const HEAD_TINT = 'top-0 z-30 border-r border-surface-200 px-3 py-2 text-left text-[11px] font-semibold text-surface-500 !bg-surface-50 dark:!bg-[#1E2228]'
/** Employee-info (frozen) cell geometry + gridlines only. The horizontal
 *  pinning/layering lives on the sticky panel, and the subtle shift-group tint
 *  comes from `roster-info-cell` + `shift-g{0..5}` (fully opaque rgb). */
const INFO_TINT = 'border-r border-surface-200 px-3 py-2 align-middle'
/** Slightly tighter vertical padding for the two-line Employee cell. */
const EMPLOYEE_INFO_TINT = 'border-r border-surface-200 px-3 py-1.5 align-middle'
/** Every body row in BOTH tables uses this same exact height so frozen rows and
 *  day rows stay aligned 1:1 while the single vertical scroll passes through. */
const ROW_HEIGHT = 'h-[44px]'
/** Last frozen column (Week Off): drop its gridline border — the frozen panel's
 *  right-edge separator is drawn by the panel itself instead of a moving cell. */
const FROZEN_LAST = 'frozen-col-last'

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
        className="fixed z-[70] w-64 overflow-hidden rounded-lg border border-surface-200 bg-surface-0 shadow-hpe-lg dark:shadow-none"
        style={{ top: state.top, left: state.left }}
        onMouseDown={(e) => e.stopPropagation()}
      >
        {/* Popup header — employee + date, light and unobtrusive */}
        <div className="flex items-center justify-between gap-2 border-b border-surface-200 px-3 pb-1.5 pt-2">
          <div className="min-w-0">
            <p className="truncate text-[11px] font-medium text-surface-700">{state.employeeId}</p>
            <p className="text-[10px] text-surface-400">{state.date}</p>
          </div>
          <button
            type="button"
            className="shrink-0 rounded-md p-1 text-surface-400 transition-colors hover:bg-surface-100 hover:text-surface-600 dark:hover:bg-white/[0.06]"
            onClick={onClose}
            aria-label="Close"
          >
            <X className="h-3.5 w-3.5" />
          </button>
        </div>

        {/* Status grid — even 3-column pills; scrolls internally when tall */}
        <div className="roster-picker-scroll grid max-h-60 grid-cols-[repeat(auto-fit,minmax(64px,1fr))] gap-2 overflow-y-auto p-2">
          {/* Clear-status action — full width, separated by a subtle divider */}
          <button
            type="button"
            className={cn(
              'col-span-full flex items-center justify-center gap-1.5 rounded-md border border-dashed border-surface-300/80 px-2 py-1.5 text-[11px] font-medium text-surface-500 transition-colors hover:bg-surface-100 hover:text-surface-700',
              state.currentCode === EMPTY_CODE && 'border-brand-400/60 text-brand-600 dark:text-brand-400',
            )}
            onClick={() => onPick(EMPTY_CODE)}
          >
            <Eraser className="h-3.5 w-3.5" /> Clear status
          </button>
          <div className="col-span-full h-px bg-surface-200/70 dark:bg-white/[0.06]" />

          {uniqueOptions.map((code) => {
            const selected = code === state.currentCode
            const style = getAttendanceCellStyle(code)
            return (
              <button
                key={code}
                type="button"
                role="menuitem"
                aria-checked={selected}
                className={cn(
                  'flex h-7 min-w-0 items-center justify-center gap-1 rounded-md px-1 text-[11px] font-semibold transition-all hover:brightness-105',
                  'dark:shadow-[inset_0_1px_0_rgba(255,255,255,0.08)]',
                  selected && 'ring-2 ring-brand-500/70',
                )}
                style={{ backgroundColor: style.backgroundColor, color: style.color }}
                onClick={() => onPick(code)}
              >
                <span className="truncate">{code}</span>
                {selected && <Check className="h-3 w-3 shrink-0" />}
              </button>
            )
          })}
        </div>
      </div>
    </>,
    document.body,
  )
}

/** "2026-09-18" -> "18 Sep 2026" */
function formatDayLabel(dateStr: string): string {
  const [y, m, d] = dateStr.split('-').map(Number)
  const dt = new Date(y, m - 1, d)
  return dt.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
}

/** ISO instant -> "18 Sep 2026, 10:32 AM" (local time). */
function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const dt = new Date(iso)
  if (Number.isNaN(dt.getTime())) return iso
  const date = dt.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
  const time = dt.toLocaleTimeString('en-GB', { hour: 'numeric', minute: '2-digit' })
  return `${date}, ${time}`
}

interface DetailPopupState {
  employee: RosterEmployeeRow
  date: string
  currentCode: string
  top: number
  left: number
}

/**
 * Status description popup opened when any user clicks a status cell. Anchored
 * to the clicked cell (portal-rendered so the roster scroll container never
 * clips it) and clamped to the viewport.
 *
 * Admins may also change the cell's status here (existing picker options,
 * same dirty/save pipeline as before). Normal users get a strictly read-only
 * description view with no edit or status controls.
 */
function StatusDetailPopup({
  state,
  canEdit,
  onPickStatus,
  onClose,
}: {
  state: DetailPopupState
  canEdit: boolean
  onPickStatus: (code: string) => void
  onClose: () => void
}) {
  const [detail, setDetail] = useState<RosterStatusDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    setDetail(null)
    setText('')
    adminApi
      .rosterStatusDetail(state.employee.employeeId, state.date)
      .then((d) => {
        if (cancelled) return
        setDetail(d)
        setText(d?.description ?? '')
        setLoading(false)
      })
      .catch((e) => {
        if (cancelled) return
        setError(extractMessage(e))
        setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [state.employee.employeeId, state.date])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  const selectedCode = state.currentCode || state.employee.days[state.date] || ''
  const badgeStyle = getAttendanceCellStyle(selectedCode)
  const stored = detail?.description ?? ''
  const changed = text.trim() !== stored.trim()

  const sourceActive = !!detail?.sourceRequestType && (!!detail?.sourceReason || !!detail?.hpeHolidayName)
  const sourceTypeLabel = detail?.sourceRequestType === 'SWAP_OFF' ? 'Swap Off' : 'Leave'

  const save = async () => {
    setSaving(true)
    try {
      const res =
        text.trim() === ''
          ? await adminApi.clearRosterStatusDetail(state.employee.employeeId, state.date)
          : await adminApi.saveRosterStatusDetail(state.employee.employeeId, state.date, text)
      setDetail(res)
      setText(res?.description ?? '')
      toast.success(text.trim() === '' ? 'Description cleared.' : 'Description saved.')
    } catch (e) {
      toast.error(extractMessage(e))
    } finally {
      setSaving(false)
    }
  }

  const clear = async () => {
    setSaving(true)
    try {
      const res = await adminApi.clearRosterStatusDetail(state.employee.employeeId, state.date)
      setDetail(res)
      setText(res?.description ?? '')
      toast.success('Description cleared.')
    } catch (e) {
      toast.error(extractMessage(e))
    } finally {
      setSaving(false)
    }
  }

  const auditBlock = (() => {
    if (!detail) return null
    const rows: string[] = []
    if (detail.createdByName) {
      rows.push(`Added by: ${detail.createdByName}`)
      rows.push(detail.createdAt ? `Added: ${formatDateTime(detail.createdAt)}` : '')
    }
    if (detail.updatedByName) {
      rows.push(`Last updated by: ${detail.updatedByName}`)
      rows.push(detail.updatedAt ? `Updated: ${formatDateTime(detail.updatedAt)}` : '')
    }
    return rows.filter(Boolean)
  })()

  return createPortal(
    <>
      <div className="fixed inset-0 z-[60]" onMouseDown={onClose} />
      <div
        role="dialog"
        aria-label="Status description"
        className="fixed z-[70] w-80 max-w-[calc(100vw-16px)] max-h-[calc(100vh-16px)] overflow-y-auto rounded-lg border border-surface-200 bg-surface-0 shadow-hpe-lg dark:border-[#20252E] dark:bg-[#1B1E23] dark:shadow-none"
        style={{ top: state.top, left: state.left }}
        onMouseDown={(e) => e.stopPropagation()}
      >
        {/* header */}
        <div className="flex items-start justify-between gap-2 border-b border-surface-200 px-3 pb-2 pt-2.5 dark:border-white/[0.06]">
          <div className="min-w-0">
            <p className="truncate text-[13px] font-semibold text-surface-800 dark:text-[#E8EDF3]">{state.employee.employeeName || state.employee.employeeId}</p>
            <p className="mt-0.5 flex items-center gap-1.5 text-[11px] text-surface-500">
              <span className="inline-flex h-4 min-w-[2.25rem] items-center justify-center rounded-[3px] px-1.5 text-[9px] font-bold" style={badgeStyle}>
                {selectedCode || '·'}
              </span>
              <span className="truncate">{formatDayLabel(state.date)}</span>
            </p>
          </div>
          <button
            type="button"
            className="shrink-0 rounded-md p-1 text-surface-400 transition-colors hover:bg-surface-100 hover:text-surface-600 dark:hover:bg-white/[0.06]"
            onClick={onClose}
            aria-label="Close"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {/* admin-only status selection (same options/pipeline as the legacy picker) */}
        {canEdit && (
          <div className="border-b border-surface-200 px-3 pb-2 pt-2 dark:border-white/[0.06]">
            <p className="mb-1.5 text-[10px] font-semibold uppercase tracking-wide text-surface-400">Status</p>
            <div className="roster-picker-scroll grid max-h-40 grid-cols-[repeat(auto-fit,minmax(64px,1fr))] gap-1.5 overflow-y-auto">
              {ROSTER_STATUS_CODES.map((code) => {
                const selected = code === selectedCode
                const style = getAttendanceCellStyle(code)
                return (
                  <button
                    key={code}
                    type="button"
                    aria-pressed={selected}
                    className={cn(
                      'flex h-7 min-w-0 items-center justify-center gap-1 rounded-md px-1 text-[11px] font-semibold transition-all hover:brightness-105',
                      'dark:shadow-[inset_0_1px_0_rgba(255,255,255,0.08)]',
                      selected && 'ring-2 ring-brand-500/70',
                    )}
                    style={{ backgroundColor: style.backgroundColor, color: style.color }}
                    onClick={() => onPickStatus(code)}
                  >
                    <span className="truncate">{code}</span>
                    {selected && <Check className="h-3 w-3 shrink-0" />}
                  </button>
                )
              })}
              <button
                type="button"
                className={cn(
                  'col-span-full flex items-center justify-center gap-1.5 rounded-md border border-dashed border-surface-300/80 px-2 py-1.5 text-[11px] font-medium text-surface-500 transition-colors hover:bg-surface-100 hover:text-surface-700',
                  selectedCode === EMPTY_CODE && 'border-brand-400/60 text-brand-600 dark:text-brand-400',
                )}
                onClick={() => onPickStatus(EMPTY_CODE)}
              >
                <Eraser className="h-3.5 w-3.5" /> Clear status
              </button>
            </div>
          </div>
        )}

        {/* description — auto-sourced reason (approved Leave / Swap Off request) or manual note */}
        <div className="px-3 pb-2 pt-2">
          {loading ? (
            <p className="text-[12px] text-surface-400">Loading…</p>
          ) : error ? (
            <p className="text-[12px] text-rose-500">{error}</p>
          ) : sourceActive ? (
            <>
              <p className="mb-1.5 text-[10px] font-semibold uppercase tracking-wide text-surface-400">Reason</p>
              <p className="whitespace-pre-wrap text-[13px] leading-relaxed text-surface-700 dark:text-[#D5DBE3]">{detail!.sourceReason}</p>
              <p className="mt-1 text-[10px] text-surface-400">
                Auto from approved {sourceTypeLabel} request — not editable here.
              </p>
              {detail?.sourceRequestType === 'SWAP_OFF' && (detail?.workedForName || detail?.workedDate) ? (
                <div className="mt-2.5 space-y-1.5 border-t border-surface-200/70 pt-2 text-[11px] text-surface-500 dark:border-white/[0.06] dark:text-[#8B95A3]">
                  <div className="grid grid-cols-2 gap-x-3 gap-y-1">
                    <div className="text-surface-400">WORKED BY</div>
                    <div className="font-medium text-right">{detail.submittedByName}</div>
                    <div className="text-surface-400">WORKED FOR</div>
                    <div className="font-medium text-right">{detail.workedForName}</div>
                    <div className="text-surface-400">WORKED DATE</div>
                    <div className="font-medium text-right">{detail.workedDate ? formatDate(detail.workedDate) : '—'}</div>
                    <div className="text-surface-400">APPROVED BY</div>
                    <div className="font-medium text-right">{detail.approvedByName ?? '—'}</div>
                    <div className="text-surface-400">APPROVED ON</div>
                    <div className="font-medium text-right">{detail.approvedAt ? formatDateTime(detail.approvedAt) : '—'}</div>
                  </div>
                </div>
            ) : (
              <div className="mt-2.5 space-y-0.5 border-t border-surface-200/70 pt-2 text-[11px] text-surface-500 dark:border-white/[0.06] dark:text-[#8B95A3]">
                {detail?.statusName || detail?.statusCode ? (
                  <p className="truncate">Status: {detail.statusName ?? detail.statusCode}</p>
                ) : null}
                {detail?.hpeHolidayName || detail?.hpeHolidayDate ? (
                  <p className="truncate">
                    HPE Holiday:{' '}
                    {detail.hpeHolidayName ?? '—'}
                    {detail.hpeHolidayDate ? `, ${formatDate(detail.hpeHolidayDate)}` : ''}
                  </p>
                ) : null}
                {detail?.submittedByName ? <p className="truncate">Submitted by: {detail.submittedByName}</p> : null}
                {detail?.approvedByName ? <p className="truncate">Approved by: {detail.approvedByName}</p> : null}
                {detail?.approvedAt ? <p className="truncate">Approved on: {formatDateTime(detail.approvedAt)}</p> : null}
              </div>
            )}
            </>
          ) : (
            <>
              <div className="mb-1.5 flex items-center justify-between gap-2">
                <p className="text-[10px] font-semibold uppercase tracking-wide text-surface-400">Description</p>
                {canEdit && (stored.trim() !== '' || text.trim() !== '') && (
                  <button
                    type="button"
                    className="text-[11px] font-medium text-surface-500 underline-offset-2 transition-colors hover:text-rose-500"
                    onClick={clear}
                    disabled={saving}
                  >
                    Clear
                  </button>
                )}
              </div>

              {canEdit ? (
                <textarea
                  value={text}
                  onChange={(e) => setText(e.target.value)}
                  rows={3}
                  maxLength={2000}
                  placeholder={selectedCode ? 'No description added.' : 'Assign a status to add a description.'}
                  className="input block w-full resize-none rounded-md border border-surface-200 bg-surface-50 px-2.5 py-2 text-[13px] leading-relaxed text-surface-700 placeholder:text-surface-400 focus:border-brand-500 focus:ring-brand-500/30 dark:bg-[#242833] dark:text-[#D5DBE3] dark:placeholder:text-[#7D8794]"
                />
              ) : stored.trim() === '' ? (
                <p className="text-[12px] text-surface-400">No description added.</p>
              ) : (
                <p className="whitespace-pre-wrap text-[13px] leading-relaxed text-surface-700 dark:text-[#D5DBE3]">{stored}</p>
              )}

              {/* audit trail */}
              {!loading && !error && auditBlock && auditBlock.length > 0 && (
                <div className="mt-2.5 space-y-0.5 border-t border-surface-200/70 pt-2 text-[11px] text-surface-500 dark:border-white/[0.06] dark:text-[#8B95A3]">
                  {auditBlock.map((line) => (
                    <p key={line} className="truncate">{line}</p>
                  ))}
                </div>
              )}
            </>
          )}
        </div>

        {/* footer */}
        {canEdit && !sourceActive && (
          <div className="flex items-center justify-end gap-2 border-t border-surface-200 px-3 py-2 dark:border-white/[0.06]">
            <Button variant="secondary" size="sm" onClick={onClose}>Cancel</Button>
            <Button size="sm" onClick={save} loading={saving} disabled={loading || !changed}>
              <Save className="h-3.5 w-3.5" /> Save
            </Button>
          </div>
        )}
      </div>
    </>,
    document.body,
  )
}

export function AdminRosterPage() {
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()

  // Only ADMIN users may edit roster statuses. Normal users still get the full
  // read-only view; the status picker and all edit affordances are gated on this.
  const { user } = useAuth()
  const canEdit = user?.role === 'ADMIN'

  const teamParam = searchParams.get('teamId')
  const [teamId, setTeamId] = useState<number | null>(teamParam ? Number(teamParam) : null)
  const [month, setMonth] = useState(searchParams.get('month') ?? currentMonth())
  const [viewMode, setViewMode] = useState<'month' | 'today'>('month')

  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const [statusFilter, setStatusFilter] = useState(EMPTY_CODE)
  const [locationFilter, setLocationFilter] = useState(EMPTY_CODE)
  const [shiftFilter, setShiftFilter] = useState(EMPTY_CODE)
  const [page, setPage] = useState(0)

  const [dirty, setDirty] = useState<Map<string, string>>(() => new Map())
  const [picker, setPicker] = useState<PickerState | null>(null)
  const [detailPopup, setDetailPopup] = useState<DetailPopupState | null>(null)

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

  const todayParams: Record<string, unknown> = useMemo(
    () => ({
      teamId: teamId ?? undefined,
      q: debouncedSearch || undefined,
      status: statusFilter || undefined,
      location: locationFilter || undefined,
      shift: shiftFilter || undefined,
    }),
    [teamId, debouncedSearch, statusFilter, locationFilter, shiftFilter],
  )

  const { data: monthlyData, isLoading: monthlyLoading, isFetching: monthlyFetching } = useQuery({
    queryKey: ['admin', 'roster', 'monthly', params],
    queryFn: () => adminApi.rosterMonthly(params),
    enabled: viewMode === 'month' && month.length === 7,
  })

  const { data: todayData, isLoading: todayLoading, isFetching: todayFetching } = useQuery({
    queryKey: ['admin', 'roster', 'today', todayParams],
    queryFn: () => adminApi.rosterToday(todayParams),
    enabled: viewMode === 'today',
  })

  // Use the appropriate data based on view mode
  const data = viewMode === 'month' ? monthlyData : todayData
  const isLoading = viewMode === 'month' ? monthlyLoading : todayLoading
  const isFetching = viewMode === 'month' ? monthlyFetching : todayFetching

  const dirtyCount = dirty.size
  const rowCount = data?.employees.length ?? 0

  // Deterministic shift groups: distinct normalized shifts are sorted and
  // mapped 0..5 cyclically, so the same shift always gets the same subtle
  // accent regardless of employee names or ordering.
  const shiftGroupByShift = useMemo(() => {
    const keys: string[] = []
    for (const e of data?.employees ?? []) {
      const k = normalizeShift(e.shift)
      if (k && !keys.includes(k)) keys.push(k)
    }
    keys.sort()
    const byShift = new Map<string, number>()
    keys.forEach((k, i) => byShift.set(k, i % SHIFT_GROUP_COUNT))
    return byShift
  }, [data])

  const rowGroups = useMemo(
    () =>
      (data?.employees ?? []).map((e: RosterEmployeeRow) => {
        const k = normalizeShift(e.shift)
        return k ? (shiftGroupByShift.get(k) ?? -1) : -1
      }),
    [data, shiftGroupByShift],
  )

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
    setDetailPopup(null)
    setPicker({
      employeeId: employee.employeeId,
      date,
      currentCode: dirty.get(cellKey(employee.employeeId, date)) ?? employee.days[date] ?? EMPTY_CODE,
      top: Math.max(8, top),
      left,
    })
  }

  /** Description popup anchored to a status cell, clamped to the viewport and
   *  flipped upward when the cell sits too close to the bottom edge. */
  const openDetail = (employee: RosterEmployeeRow, date: string, el: HTMLElement) => {
    const rect = el.getBoundingClientRect()
    const width = 320
    const estimateHeight = canEdit ? 520 : 320
    const below = rect.bottom + 6 + estimateHeight <= window.innerHeight
    const top = Math.max(8, below ? rect.bottom + 6 : rect.top - estimateHeight - 6)
    const left = Math.max(8, Math.min(rect.left, window.innerWidth - width - 8))
    setPicker(null)
    setDetailPopup({
      employee,
      date,
      currentCode: dirty.get(cellKey(employee.employeeId, date)) ?? employee.days[date] ?? EMPTY_CODE,
      top,
      left,
    })
  }

  /** Apply a status to a cell through the existing dirty-map pipeline (the
   *  same mechanism the legacy picker uses before the page-level Save). */
  const applyStatus = (employeeId: string, date: string, code: string) => {
    setDirty((prev) => {
      const next = new Map(prev)
      next.set(cellKey(employeeId, date), code)
      return next
    })
  }

  const pickStatus = (code: string) => {
    if (!picker) return
    applyStatus(picker.employeeId, picker.date, code)
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

  const headerCell = (date: string, weekend: boolean, holiday: boolean, weekday?: string) => (
    <th
      key={date}
      className={cn(
        'sticky top-0 z-10 w-11 border-l border-surface-200 px-0.5 py-1.5 text-center align-middle first:border-l-0',
        weekend && 'bg-brand-50 dark:bg-[#1B2A29]',
        holiday && 'bg-amber-50 dark:bg-[#383020]',
        !weekend && !holiday && 'bg-surface-50 dark:bg-[#1E2228]',
      )}
      title={holiday ? statusLabel('HPEH') : undefined}
    >
      <div className="text-xs font-semibold text-surface-700 dark:text-[#E5E7EB]">{weekdayOf(date) === 0 ? 'S' : date.slice(8)}</div>
      <div className={cn('text-[9px] font-medium uppercase', weekend ? 'text-brand-400 dark:text-[#8FBDB7]' : 'text-surface-400 dark:text-[#8B95A3]')}>
        {weekday ?? ''}
        {holiday ? ' · H' : ''}
      </div>
    </th>
  )

  const statusCell = (employee: RosterEmployeeRow, date: string) => {
    const key = cellKey(employee.employeeId, date)
    const edited = dirty.get(key)
    const code = edited !== undefined ? edited : (employee.days[date] ?? EMPTY_CODE)
    const display = code || '.'
    return (
      <td key={date} className="min-w-[2.3rem] text-center align-middle">
        <button
          type="button"
          data-status={code}
          className={cn(
            'attendance-cell',
            !canEdit && 'cursor-default hover:shadow-none',
            edited !== undefined && 'outline outline-2 outline-offset-[-2px] outline-amber-400 dark:outline-[color:rgba(120,160,180,0.45)]',
          )}
          style={getAttendanceCellStyle(code)}
          title={canEdit ? (code ? `${statusLabel(code)} — ${date}` : `Set status — ${date}`) : `${statusLabel(code)} — ${date}`}
          aria-disabled={!canEdit}
          tabIndex={canEdit ? undefined : -1}
          onClick={(e) => {
            if (!code) {
              if (canEdit) openPicker(employee, date, e.currentTarget)
              return
            }
            openDetail(employee, date, e.currentTarget)
          }}
        >
          <span className="attendance-status">{display}</span>
        </button>
      </td>
    )
  }

  // ---------------------------------------------------------------- render

  // Type guards to narrow the union type
  const isTodayView = viewMode === 'today'
  const isMonthView = viewMode === 'month'

  const todayDataTyped = isTodayView ? data as RosterTodayData | undefined : undefined
  const monthlyDataTyped = isMonthView ? data as RosterMonthlyData | undefined : undefined

  const isLoadingData = isLoading || (isFetching && !data)
  const counters = data?.counters ?? {}

  return (
    <div className="space-y-5">
      {/* ------------------------------------------------ title + summary */}
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-4">
        <div className="min-w-0">
          <h1 className="text-[22px] font-semibold leading-tight tracking-tight text-surface-800">Attendance Roster</h1>
          <p className="mt-1.5 text-[13px] text-surface-500">{canEdit ? 'Month-wise imported roster — editable by admin' : 'Month-wise imported roster — view only'}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {/* Month/Today Toggle */}
          <div className="inline-flex items-center p-0.5 rounded-lg bg-surface-100 dark:bg-[#242833] border border-surface-200 dark:border-[#30363D]">
            <button
              type="button"
              className={cn(
                'px-2.5 py-1 text-[11px] font-medium rounded-[6px] transition-colors',
                viewMode === 'month'
                  ? 'bg-brand-500 text-white'
                  : 'text-surface-500 dark:text-surface-400 hover:text-surface-700 dark:hover:text-surface-200'
              )}
              onClick={() => setViewMode('month')}
              aria-label="Month view"
            >
              MN
            </button>
            <button
              type="button"
              className={cn(
                'px-2.5 py-1 text-[11px] font-medium rounded-[6px] transition-colors',
                viewMode === 'today'
                  ? 'bg-brand-500 text-white'
                  : 'text-surface-500 dark:text-surface-400 hover:text-surface-700 dark:hover:text-surface-200'
              )}
              onClick={() => setViewMode('today')}
              aria-label="Today view"
            >
              TD
            </button>
          </div>
          {COUNTER_CODES.map((c) => (
            <span
              key={c}
              className="flex min-w-[4.75rem] flex-col gap-1 rounded-lg border border-surface-200 bg-surface-0 px-2.5 py-1.5 shadow-hpe-sm dark:border-[#20252E] dark:bg-[#1B1E23] dark:shadow-none"
              title={statusLabel(c)}
            >
              <span className="flex items-center gap-1.5">
                <span className="inline-flex h-4 min-w-[2rem] items-center justify-center rounded-[3px] px-1 text-[9px] font-bold" style={getAttendanceCellStyle(c)}>{c}</span>
                <span className="text-sm font-semibold tabular-nums text-surface-800">{counters[c] ?? 0}</span>
              </span>
              <span className="text-[10px] leading-none text-surface-400">{statusLabel(c)}</span>
            </span>
          ))}
        </div>
      </div>

      {/* ------------------------------------------------ filters */}
      <div className="grid grid-cols-1 gap-x-4 gap-y-4 sm:grid-cols-2 lg:grid-cols-6">
        <div className="min-w-0">
          <label className="mb-1.5 block text-[11px] font-medium text-surface-500">Team</label>
          <select className="select h-[38px]" value={teamId ?? ''} onChange={(e) => updateTeam(e.target.value === '' ? null : Number(e.target.value))}>
            <option value="">All Teams</option>
            {(meta?.teams ?? []).map((t) => (
              <option key={t.id} value={t.id}>{t.name}</option>
            ))}
          </select>
        </div>
        <div className="min-w-0">
          <label className="mb-1.5 block text-[11px] font-medium text-surface-500">Month</label>
          <div className="flex items-center gap-1.5">
            <Button variant="secondary" size="sm" className="h-[38px] w-10 shrink-0 px-0" onClick={() => updateMonth(shiftMonth(month, -1))} aria-label="Previous month">
              <ChevronLeft className="h-4 w-4" />
            </Button>
            <Select
              className="min-w-0 flex-1"
              placeholder="Pick a month"
              value={month}
              onChange={(e) => updateMonth(e.target.value)}
              options={months.map((ym) => ({ value: ym, label: monthLabel(ym) }))}
            />
            <Button variant="secondary" size="sm" className="h-[38px] w-10 shrink-0 px-0" onClick={() => updateMonth(shiftMonth(month, 1))} aria-label="Next month">
              <ChevronRight className="h-4 w-4" />
            </Button>
          </div>
          {metaLoading && <span className="mt-1 block text-[11px] text-surface-400">Loading months…</span>}
        </div>
        <div className="min-w-0">
          <label className="mb-1.5 block text-[11px] font-medium text-surface-500">Employee</label>
          <div className="relative">
            <Search className="pointer-events-none absolute left-2.5 top-1/2 h-4 w-4 -translate-y-1/2 text-surface-400" />
            <input className="input h-[38px] pl-8" placeholder="Search name / ID / email" value={search} onChange={(e) => setSearch(e.target.value)} />
          </div>
        </div>
        <div className="min-w-0">
          <label className="mb-1.5 block text-[11px] font-medium text-surface-500">Status</label>
          <Select
            className="h-[38px]"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            placeholder="Any status"
            options={(meta?.statuses ?? []).map((s) => ({ value: s.code, label: `${s.code} — ${s.name}` }))}
          />
        </div>
        <div className="min-w-0">
          <label className="mb-1.5 block text-[11px] font-medium text-surface-500">Location</label>
          <Select
            className="h-[38px]"
            value={locationFilter}
            onChange={(e) => setLocationFilter(e.target.value)}
            placeholder="All locations"
            options={(meta?.locations ?? []).map((l) => ({ value: l, label: l }))}
          />
        </div>
        <div className="min-w-0">
          <label className="mb-1.5 block text-[11px] font-medium text-surface-500">Shift</label>
          <Select
            className="h-[38px]"
            value={shiftFilter}
            onChange={(e) => setShiftFilter(e.target.value)}
            placeholder="All shifts"
            options={(meta?.shifts ?? []).map((s) => ({ value: s, label: formatShiftDisplay(s) }))}
          />
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-3">
        <RosterLegend className="min-w-0 flex-1" />
        <div className="flex shrink-0 items-center gap-1.5 text-xs text-surface-500">
          {dirtyCount > 0 ? (
            <>
              <span className="flex items-center gap-1.5 rounded-md bg-amber-50 px-2 py-1 text-amber-700 dark:border dark:border-[#4E4524] dark:bg-[#38301E] dark:text-[#E4C76A]">
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
      ) : !data || (data.employees.length === 0 && (monthlyDataTyped?.totalElements ?? 0) === 0) ? (
        <div className="card p-12">
          <EmptyState
            title={viewMode === 'today' ? `No roster data for today` : `No roster data for ${monthLabel(month)}`}
            description="Import a historical monthly roster, or adjust the team and filters."
          />
          <div className="mt-4 flex justify-center gap-2">
            {canEdit && (
              <Button onClick={() => window.location.assign('/admin/historical-import')}>Import historical attendance</Button>
            )}
          </div>
        </div>
      ) : (
        <div className="card overflow-hidden dark:border-[#20252E] dark:bg-[#1B1E23]">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-surface-200 px-4 py-3">
            <div className="flex items-center gap-2 text-sm font-semibold text-surface-700">
              <CalendarRange className="h-4 w-4 text-brand-500" />
              {viewMode === 'today'
                ? <>
                  {todayDataTyped!.weekday} {todayDataTyped!.date}
                  {todayDataTyped!.holiday && <span className="text-xs font-normal text-amber-500">· Holiday</span>}
                  {todayDataTyped!.weekend && <span className="text-xs font-normal text-brand-500">· Weekend</span>}
                </>
                : <>
                  {monthLabel(month)}
                  {monthlyDataTyped?.teamName ? <span className="text-xs font-normal text-surface-400">· {monthlyDataTyped.teamName}</span> : null}
                </>
              }
            </div>
            <div className="flex items-center gap-2">
              <span className="rounded-full bg-surface-100 px-2.5 py-1 text-[11px] font-semibold tabular-nums text-surface-600">
                {data.matchedEmployees.toLocaleString()} / {data.totalEmployees.toLocaleString()} employees
              </span>
            </div>
          </div>

          <div className="isolate max-h-[72vh] overflow-auto">
            <div className="flex min-w-max">
              {/* FROZEN employee panel — a sticky left layer that never scrolls
                  horizontally. The day matrix scrolls beneath it, and the panel's
                  own opaque surface + fixed right-edge separator form the "wall". */}
              <div className="roster-frozen-panel sticky left-0 z-20">
                <table className="table-fixed border-separate border-spacing-0">
                  <thead>
                    <tr className="border-b border-surface-200">
                      <th className={`${FROZEN_COL.sno} ${HEAD_TINT}`}>SNO</th>
                      <th className={`${FROZEN_COL.empId} ${HEAD_TINT}`}>Emp ID</th>
                      <th className={`${FROZEN_COL.employee} ${HEAD_TINT}`}>Employee</th>
                      <th className={`${FROZEN_COL.loc} ${HEAD_TINT}`}>Location</th>
                      <th className={`${FROZEN_COL.shift} ${HEAD_TINT}`}>Shift</th>
                      <th className={`${FROZEN_COL.weekOff} ${HEAD_TINT} ${FROZEN_LAST}`}>Week Off</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-surface-200">
                    {data.employees.map((employee: RosterEmployeeRow, index: number) => {
                      const group = rowGroups[index] ?? -1
                      const groupClass = group >= 0 ? `shift-g${group}` : 'shift-g-none'
                      const groupEnd = index === rowGroups.length - 1 || rowGroups[index] !== rowGroups[index + 1]
                      return (
                        <tr key={employee.employeeId} className={cn('group', ROW_HEIGHT, 'hover:bg-surface-50/60 dark:hover:bg-white/[0.02]', groupEnd && 'shift-group-end')}>
                          <td className={`${FROZEN_COL.sno} ${INFO_TINT} roster-info-cell shift-accent-bar ${groupClass} text-center text-xs text-surface-500`}>{index + 1}</td>
                          <td className={`${FROZEN_COL.empId} ${INFO_TINT} roster-info-cell ${groupClass} font-mono text-xs font-medium text-surface-700 dark:text-[#A9B4C2]`}>{employee.employeeId}</td>
                          <td className={`${FROZEN_COL.employee} ${EMPLOYEE_INFO_TINT} roster-info-cell ${groupClass}`}>
                            <div className="truncate text-[13px] font-medium leading-tight text-surface-800 dark:font-semibold dark:text-[13.5px] dark:text-[#E8EDF3]" title={employee.employeeName ?? undefined}>{employee.employeeName ?? '—'}</div>
                            <div className="truncate text-[11px] leading-snug text-surface-500 dark:text-[#7D8794]" title={employee.email ?? undefined}>{employee.email ? employee.email : '—'}</div>
                          </td>
                          <td className={`${FROZEN_COL.loc} ${INFO_TINT} roster-info-cell ${groupClass} truncate text-xs text-surface-500`}>{employee.location ?? '—'}</td>
                          <td className={`${FROZEN_COL.shift} ${INFO_TINT} roster-info-cell ${groupClass} whitespace-nowrap text-xs text-surface-500`}>{formatShiftTime(employee.shift) ?? '—'}</td>
                          <td className={`${FROZEN_COL.weekOff} ${INFO_TINT} roster-info-cell ${groupClass} ${FROZEN_LAST} truncate text-xs text-surface-500`}>{employee.weekOff ? weekOffDisplay(employee.weekOff) : '—'}</td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>

              {/* DAY matrix — the only horizontally scrollable content. It starts flush
                  after the frozen panel's fixed 8px gutter (see .roster-frozen-panel
                  padding-right in index.css), so the breathing space stays glued to
                  the frozen section while only these columns scroll. */}
              <div>
                <table className="table-fixed border-separate border-spacing-0" style={{ width: `${(viewMode === 'today' ? 1 : monthlyDataTyped?.days.length ?? 0) * 44}px` }}>
                  <thead>
                    <tr className="border-b border-surface-200">
                      {viewMode === 'today'
                        ? headerCell(todayDataTyped!.date, todayDataTyped!.weekend, todayDataTyped!.holiday, todayDataTyped!.weekday)
                        : monthlyDataTyped!.days.map((d: RosterDayInfo) => headerCell(d.date, d.weekend, d.holiday, d.weekday))
                      }
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-surface-200">
                    {data.employees.map((employee: RosterEmployeeRow, index: number) => {
                      const groupEnd = index === rowGroups.length - 1 || rowGroups[index] !== rowGroups[index + 1]
                      return (
                        <tr key={employee.employeeId} className={cn('group', ROW_HEIGHT, 'hover:bg-surface-50/60 dark:hover:bg-white/[0.02]', groupEnd && 'shift-group-end')}>
                          {viewMode === 'today'
                            ? statusCell(employee, todayDataTyped!.date)
                            : monthlyDataTyped!.days.map((d: RosterDayInfo) => statusCell(employee, d.date))
                          }
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
              {rowCount === 0 && (
                <div className="p-10">
                  <EmptyState title="No matching employees" description="Adjust the search, status, location or shift filters." />
                </div>
              )}
            </div>

            {viewMode === 'month' && (
              <Pagination
                page={monthlyData?.page ?? 0}
                size={monthlyData?.size ?? PAGE_SIZE}
                totalElements={monthlyData?.totalElements ?? 0}
                totalPages={monthlyData?.totalPages ?? 0}
                onPageChange={(p) => setPage(p)}
              />
            )}

          </div>
        </div>
      )}

      {canEdit && picker && (
        <StatusPicker
          state={picker}
          options={ROSTER_STATUS_CODES as unknown as string[]}
          onPick={pickStatus}
          onClose={() => setPicker(null)}
        />
      )}

      {detailPopup && (
        <StatusDetailPopup
          state={detailPopup}
          canEdit={canEdit}
          onPickStatus={(code) => {
            applyStatus(detailPopup.employee.employeeId, detailPopup.date, code)
            setDetailPopup((prev) => (prev ? { ...prev, currentCode: code } : prev))
          }}
          onClose={() => setDetailPopup(null)}
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